package com.winlator.cmod.lsfg;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Choreographer;
import android.view.WindowManager;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lossless Scaling frame generation (LSFG) through the lsfg-vk Vulkan layer.
 *
 * The layer binary ships in jniLibs and its manifest in assets. Both are installed once into
 * the imagefs; per-container state (the user's Lossless.dll and conf.toml) lives in the
 * container's home. The manifest is placed outside every directory the Vulkan loader searches
 * by default, so the layer only loads when {@link #prepareLaunch} adds it through
 * VK_ADD_IMPLICIT_LAYER_PATH.
 */
public final class LsfgManager {
    private static final String TAG = "LsfgManager";

    public static final String EXTRA_ENABLED = "lsfgEnabled";
    public static final String EXTRA_MULTIPLIER = "lsfgMultiplier";
    public static final String EXTRA_FLOW_SCALE = "lsfgFlowScale";
    public static final String EXTRA_PERFORMANCE_MODE = "lsfgPerformanceMode";
    public static final String EXTRA_PRESENT_MODE = "lsfgPresentMode";

    public static final int DEFAULT_MULTIPLIER = 2;
    public static final float DEFAULT_FLOW_SCALE = 0.80f;
    public static final String PRESENT_MODE_MAILBOX = "mailbox";
    public static final String PRESENT_MODE_FIFO = "fifo";

    /** Bump whenever the bundled layer binary changes so existing installs are refreshed. */
    private static final String RUNTIME_VERSION = "lsfg-vk-android v1.0.4-android";

    private static final String LAYER_LIBRARY = "liblsfg-vk-layer.so";
    private static final String LAYER_MANIFEST = "VkLayer_LS_frame_generation.json";
    private static final String ASSET_MANIFEST = "lsfg_vk/" + LAYER_MANIFEST;
    /** Relative to the imagefs root. Deliberately not a vulkan/implicit_layer.d search path. */
    private static final String LAYER_DIR = "usr/share/lsfg-vk/layer";
    private static final String VERSION_FILE = ".version";

    /** Relative to the container root, which is what $HOME points to inside the guest. */
    private static final String CONFIG_FILE = ".config/lsfg-vk/conf.toml";
    private static final String VSYNC_FILE = ".config/lsfg-vk/vsync.txt";
    private static final String DLL_FILE = ".local/share/lsfg-vk/Lossless.dll";

    /** Under Wine /proc/self/exe is the Wine loader, so the [[game]] profile matches this instead. */
    private static final String PROCESS_ID = "winlator-lsfg";

    private LsfgManager() {}

    // ---- Settings ----------------------------------------------------------

    public static boolean isEnabled(Container container) {
        return parseBool(container.getExtra(EXTRA_ENABLED, "0"));
    }

    public static void setEnabled(Container container, boolean enabled) {
        container.putExtra(EXTRA_ENABLED, enabled ? "1" : "0");
        container.saveData();
    }

    public static int getMultiplier(Container container) {
        try {
            int value = Integer.parseInt(container.getExtra(EXTRA_MULTIPLIER, String.valueOf(DEFAULT_MULTIPLIER)));
            return Math.max(2, Math.min(4, value));
        }
        catch (NumberFormatException e) {
            return DEFAULT_MULTIPLIER;
        }
    }

    public static void setMultiplier(Container container, int multiplier) {
        container.putExtra(EXTRA_MULTIPLIER, String.valueOf(Math.max(2, Math.min(4, multiplier))));
        container.saveData();
    }

    public static float getFlowScale(Container container) {
        try {
            float value = Float.parseFloat(container.getExtra(EXTRA_FLOW_SCALE, String.valueOf(DEFAULT_FLOW_SCALE)));
            return Math.max(0.25f, Math.min(1.0f, value));
        }
        catch (NumberFormatException e) {
            return DEFAULT_FLOW_SCALE;
        }
    }

    public static void setFlowScale(Container container, float flowScale) {
        container.putExtra(EXTRA_FLOW_SCALE, formatFlowScale(flowScale));
        container.saveData();
    }

    public static boolean isPerformanceMode(Container container) {
        return parseBool(container.getExtra(EXTRA_PERFORMANCE_MODE, "1"));
    }

    public static void setPerformanceMode(Container container, boolean performanceMode) {
        container.putExtra(EXTRA_PERFORMANCE_MODE, performanceMode ? "1" : "0");
        container.saveData();
    }

    /**
     * Mailbox by default: the layer already paces to vsync, and a FIFO queue underneath it
     * breaks the display cadence.
     */
    public static String getPresentMode(Container container) {
        String value = container.getExtra(EXTRA_PRESENT_MODE, PRESENT_MODE_MAILBOX);
        return PRESENT_MODE_FIFO.equals(value) ? PRESENT_MODE_FIFO : PRESENT_MODE_MAILBOX;
    }

    public static void setPresentMode(Container container, String presentMode) {
        container.putExtra(EXTRA_PRESENT_MODE, PRESENT_MODE_FIFO.equals(presentMode) ? PRESENT_MODE_FIFO : PRESENT_MODE_MAILBOX);
        container.saveData();
    }

    // ---- Lossless.dll ------------------------------------------------------

    public static File getDllFile(Container container) {
        return new File(container.getRootDir(), DLL_FILE);
    }

    public static boolean hasDll(Container container) {
        File dll = getDllFile(container);
        return dll.isFile() && dll.length() > 0;
    }

    /** Frame generation runs only when the user enabled it and supplied a Lossless.dll. */
    public static boolean isArmed(Container container) {
        return isEnabled(container) && hasDll(container);
    }

    /**
     * Copies the user's Lossless.dll into the container. The DLL is proprietary to Lossless
     * Scaling, so it is never bundled with the app.
     *
     * @return null on success, otherwise a message describing why the file was rejected
     */
    public static String importDll(Context context, Container container, Uri uri) {
        File target = getDllFile(container);
        File parent = target.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) return "Unable to create " + parent;

        File tmp = new File(parent, target.getName() + ".tmp");
        if (!FileUtils.copy(context, uri, tmp)) {
            tmp.delete();
            return "Unable to read the selected file";
        }
        if (!isPortableExecutable(tmp)) {
            tmp.delete();
            return "Not a Windows DLL — select Lossless.dll from your Lossless Scaling install";
        }
        if (!tmp.renameTo(target)) {
            tmp.delete();
            return "Unable to save Lossless.dll";
        }
        FileUtils.chmod(target, 0644);
        Log.i(TAG, "Imported Lossless.dll (" + target.length() + " bytes) into " + target);
        return null;
    }

    public static void removeDll(Container container) {
        File dll = getDllFile(container);
        if (dll.exists() && !dll.delete()) Log.w(TAG, "Unable to delete " + dll);
    }

    private static boolean isPortableExecutable(File file) {
        try (InputStream in = new FileInputStream(file)) {
            byte[] header = new byte[2];
            return in.read(header) == 2 && header[0] == 'M' && header[1] == 'Z';
        }
        catch (IOException e) {
            return false;
        }
    }

    // ---- Launch ------------------------------------------------------------

    /**
     * Installs the layer, writes conf.toml and exposes the layer to the guest's Vulkan loader.
     * Does nothing when frame generation is not armed, leaving any user-set variables alone.
     *
     * @return true if the layer will be loaded
     */
    public static boolean prepareLaunch(Context context, ImageFs imageFs, Container container, EnvVars envVars) {
        if (!isArmed(container)) {
            if (isEnabled(container)) Log.w(TAG, "LSFG enabled but no Lossless.dll imported; skipping");
            return false;
        }

        File layerDir = new File(imageFs.getRootDir(), LAYER_DIR);
        if (!ensureRuntimeInstalled(context, layerDir)) return false;
        if (!writeConfig(container)) return false;

        envVars.put("LSFG_CONFIG", new File(container.getRootDir(), CONFIG_FILE).getAbsolutePath());
        envVars.put("LSFG_PROCESS", PROCESS_ID);

        String implicitPath = envVars.get("VK_ADD_IMPLICIT_LAYER_PATH");
        String layerPath = layerDir.getAbsolutePath();
        envVars.put("VK_ADD_IMPLICIT_LAYER_PATH", implicitPath.isEmpty() ? layerPath : implicitPath + ":" + layerPath);

        Log.i(TAG, String.format(Locale.US, "LSFG armed: multiplier=%d flowScale=%s performance=%b presentMode=%s",
                getMultiplier(container), formatFlowScale(getFlowScale(container)),
                isPerformanceMode(container), getPresentMode(container)));
        return true;
    }

    private static boolean ensureRuntimeInstalled(Context context, File layerDir) {
        File library = new File(layerDir, LAYER_LIBRARY);
        File manifest = new File(layerDir, LAYER_MANIFEST);
        File version = new File(layerDir, VERSION_FILE);

        if (library.isFile() && manifest.isFile() && version.isFile()
                && RUNTIME_VERSION.equals(FileUtils.readString(version).trim())) {
            return true;
        }

        File source = new File(context.getApplicationInfo().nativeLibraryDir, LAYER_LIBRARY);
        if (!source.isFile()) {
            Log.e(TAG, "Bundled layer not found: " + source);
            return false;
        }
        if (!layerDir.isDirectory() && !layerDir.mkdirs()) {
            Log.e(TAG, "Unable to create " + layerDir);
            return false;
        }
        // FileUtils.copy reports success even when the copy throws, so verify the size instead.
        FileUtils.copy(source, library);
        if (library.length() != source.length()) {
            Log.e(TAG, "Unable to copy " + source + " to " + library);
            library.delete();
            return false;
        }
        FileUtils.chmod(library, 0755);

        String manifestText = FileUtils.readString(context, ASSET_MANIFEST);
        if (manifestText == null || manifestText.isEmpty()) {
            Log.e(TAG, "Unable to read asset " + ASSET_MANIFEST);
            return false;
        }
        manifestText = manifestText.replaceFirst("\"library_path\"\\s*:\\s*\"[^\"]*\"",
                "\"library_path\": \"" + library.getAbsolutePath() + "\"");
        if (!FileUtils.writeString(manifest, manifestText) || !FileUtils.writeString(version, RUNTIME_VERSION)) {
            Log.e(TAG, "Unable to write the layer manifest");
            return false;
        }
        Log.i(TAG, "Installed " + RUNTIME_VERSION + " into " + layerDir);
        return true;
    }

    /** The layer rereads conf.toml when its mtime changes, so it must never see a partial write. */
    private static boolean writeConfig(Container container) {
        return writeConfig(container, getMultiplier(container));
    }

    /** @param multiplier written verbatim; 1 keeps the layer resident but passes frames through. */
    private static boolean writeConfig(Container container, int multiplier) {
        StringBuilder toml = new StringBuilder();
        toml.append("version = 1\n\n");
        toml.append("[global]\n");
        toml.append("dll = ").append(tomlString(getDllFile(container).getAbsolutePath())).append('\n');
        toml.append("no_fp16 = false\n\n");
        toml.append("[[game]]\n");
        toml.append("exe = ").append(tomlString(PROCESS_ID)).append('\n');
        toml.append("multiplier = ").append(Math.max(1, Math.min(4, multiplier))).append('\n');
        toml.append("flow_scale = ").append(formatFlowScale(getFlowScale(container))).append('\n');
        toml.append("performance_mode = ").append(isPerformanceMode(container)).append('\n');
        toml.append("hdr_mode = false\n");
        toml.append("fps_limit = 0\n");
        toml.append("experimental_present_mode = ").append(tomlString(getPresentMode(container))).append('\n');

        return writeAtomic(new File(container.getRootDir(), CONFIG_FILE), toml.toString());
    }

    /** In-game "off": layer stays loaded, frames pass through unmodified. */
    public static final int RUNTIME_OFF_MULTIPLIER = 1;

    private static final ExecutorService runtimeWriter = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "lsfg-runtime");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Rewrites conf.toml for the running container so the layer reloads live. Multiplier and flow
     * scale changes force a swapchain recreation inside the layer; the file write happens off the
     * UI thread. Persists 2x-4x as the container's preferred multiplier, but not the transient
     * {@link #RUNTIME_OFF_MULTIPLIER}, so a relaunch restores the last real multiplier.
     */
    public static void applyRuntimeConfig(Container container, int multiplier, float flowScale) {
        final int m = Math.max(RUNTIME_OFF_MULTIPLIER, Math.min(4, multiplier));
        setFlowScale(container, flowScale);
        if (m >= 2) setMultiplier(container, m);
        runtimeWriter.execute(() -> writeConfig(container, m));
    }

    // ---- Vsync clock -------------------------------------------------------

    private static volatile Handler vsyncHandler;
    private static final ExecutorService vsyncWriter = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "lsfg-vsync");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Publishes the display's vsync timestamp and period to vsync.txt once a second so the
     * layer can phase-lock its pacing to the display instead of free-running against it.
     * Choreographer timestamps are CLOCK_MONOTONIC, the clock the layer paces with.
     */
    public static void startVsyncClock(Context context, Container container) {
        stopVsyncClock();
        if (!isArmed(container)) return;

        final File file = new File(container.getRootDir(), VSYNC_FILE);
        final WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        final Handler handler = new Handler(Looper.getMainLooper());
        vsyncHandler = handler;

        handler.post(new Runnable() {
            @Override
            public void run() {
                if (vsyncHandler != handler) return;
                Choreographer.getInstance().postFrameCallback(frameTimeNanos -> {
                    if (vsyncHandler != handler) return;
                    float refreshRate = 60f;
                    try {
                        float rate = windowManager.getDefaultDisplay().getRefreshRate();
                        if (rate > 1f) refreshRate = rate;
                    }
                    catch (RuntimeException ignored) {}
                    final long periodNs = (long) (1_000_000_000.0 / refreshRate);
                    vsyncWriter.execute(() -> writeAtomic(file, "vsync_ns=" + frameTimeNanos + "\nperiod_ns=" + periodNs + "\n"));
                });
                handler.postDelayed(this, 1000);
            }
        });
    }

    public static void stopVsyncClock() {
        Handler handler = vsyncHandler;
        vsyncHandler = null;
        if (handler != null) handler.removeCallbacksAndMessages(null);
    }

    // ---- Helpers -----------------------------------------------------------

    private static boolean writeAtomic(File file, String text) {
        File parent = file.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) return false;
        File tmp = new File(parent, file.getName() + ".tmp");
        if (!FileUtils.writeString(tmp, text)) return false;
        FileUtils.chmod(tmp, 0644);
        if (!tmp.renameTo(file)) {
            tmp.delete();
            return false;
        }
        return true;
    }

    private static String tomlString(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static String formatFlowScale(float value) {
        return String.format(Locale.US, "%.2f", Math.max(0.25f, Math.min(1.0f, value)));
    }

    private static boolean parseBool(String value) {
        return "1".equals(value) || "true".equalsIgnoreCase(value);
    }
}
