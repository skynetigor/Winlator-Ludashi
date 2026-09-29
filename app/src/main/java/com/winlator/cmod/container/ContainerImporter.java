package com.winlator.cmod.container;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.winlator.cmod.contents.AdrenotoolsManager;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.contents.Downloader;
import com.winlator.cmod.contents.RemoteDriverCatalog;
import com.winlator.cmod.core.DefaultVersion;
import com.winlator.cmod.core.ProtonPackageManager;
import com.winlator.cmod.core.WineInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Imports a {@link ContainerProfile} envelope into a new container, installing any missing runtime
 * components first so the result is usable. Reuses the app's own install paths
 * ({@link ProtonPackageManager}, {@link ContentsManager}, {@link Downloader}); it only orchestrates.
 *
 * Call {@link #run} on a background thread — it blocks on downloads and container creation.
 */
public final class ContainerImporter {
    public interface Progress {
        void status(String message);
    }

    public static final class Result {
        public final Container container;      // null on failure
        public final List<String> warnings;

        Result(Container container, List<String> warnings) {
            this.container = container;
            this.warnings = warnings;
        }
    }

    private static final long INSTALL_TIMEOUT_SECONDS = 180;
    private static final String TAG = "ContainerImporter";

    private ContainerImporter() {}

    public static Result run(Context context, ContainerManager manager, ContentsManager contents,
                             ContainerProfile.Envelope envelope, Progress progress) {
        List<String> warnings = new ArrayList<>();
        JSONObject data = envelope.container;
        JSONArray hints = envelope.components;

        // Refresh the catalog once so remote lookups (tiers 4-5) can resolve.
        report(progress, "Checking components…");
        try {
            String url = PreferenceManager.getDefaultSharedPreferences(context)
                    .getString("downloadable_contents_url", ContentsManager.REMOTE_PROFILES);
            String json = Downloader.downloadString(url);
            if (json != null) contents.setRemoteProfiles(json);
        } catch (Exception e) {
            warnings.add("Couldn't refresh the components catalog (offline?); using what's installed or bundled.");
        }

        // Wine/Proton is critical: it must exist before the prefix is built.
        String wineVersion = data.optString("wineVersion", "");
        if (!ensureWine(context, contents, wineVersion, hints, progress, warnings)) {
            String fallback = firstInstalledWine(context, contents);
            if (fallback == null) {
                warnings.add("No Wine/Proton runtime available; cannot create the container.");
                return new Result(null, warnings);
            }
            warnings.add("Wine/Proton '" + wineVersion + "' was unavailable; using '" + fallback + "' instead.");
            try { data.put("wineVersion", fallback); } catch (Exception ignored) {}
        }

        // Graphics driver: an AdrenoTools/Turnip id that isn't on this device makes the launcher's
        // getVulkanVersion(...).split(".")[2] throw and crash the app. Rewrite it to System if the
        // referenced driver isn't available here.
        sanitizeGraphicsDriver(context, data, hints, progress, warnings);

        // Optional runtimes: applied when the container runs; the run-time path falls back to the
        // bundled asset, so pre-install when we can and otherwise leave a version that resolves.
        String dxwrapperConfig = data.optString("dxwrapperConfig", "");
        String dxvk = ContainerProfile.readConfigValue(dxwrapperConfig, "version", ',');
        if (!ensureContent(context, contents, ContentProfile.ContentType.CONTENT_TYPE_DXVK, dxvk, hints, progress, warnings)) {
            dxwrapperConfig = setConfigValue(dxwrapperConfig, "version", DefaultVersion.DXVK, ',');
            warnings.add("DXVK " + dxvk + " unavailable; using bundled " + DefaultVersion.DXVK + ".");
        }
        String vkd3d = ContainerProfile.readConfigValue(dxwrapperConfig, "vkd3dVersion", ',');
        if (!ensureContent(context, contents, ContentProfile.ContentType.CONTENT_TYPE_VKD3D, vkd3d, hints, progress, warnings)) {
            dxwrapperConfig = setConfigValue(dxwrapperConfig, "vkd3dVersion", DefaultVersion.VKD3D, ',');
            warnings.add("VKD3D " + vkd3d + " unavailable; using " + DefaultVersion.VKD3D + ".");
        }
        try { data.put("dxwrapperConfig", dxwrapperConfig); } catch (Exception ignored) {}

        // Box64/WOWBox64/FEXCore ship bundled, so these resolve without a download.
        ensureContent(context, contents, ContentProfile.ContentType.CONTENT_TYPE_BOX64,
                data.optString("box64Version", ""), hints, progress, warnings);
        ensureContent(context, contents, ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64,
                data.optString("box64Version", ""), hints, progress, warnings);
        ensureContent(context, contents, ContentProfile.ContentType.CONTENT_TYPE_FEXCORE,
                data.optString("fexcoreVersion", ""), hints, progress, warnings);

        if (data.optString("extraData", "").contains("lsfg") || data.toString().contains("lsfgEnabled"))
            warnings.add("Frame generation settings were imported; re-import your Lossless.dll to enable it.");

        // We're already off the main thread, so create synchronously (createContainerAsync would
        // call new Handler() on this looper-less thread and crash).
        report(progress, "Creating container…");
        Container created = manager.createContainerFromData(data, contents);
        if (created == null) warnings.add("Container creation failed.");
        return new Result(created, warnings);
    }

    // ---- Wine/Proton -------------------------------------------------------

    private static boolean ensureWine(Context context, ContentsManager contents, String version,
                                      JSONArray hints, Progress progress, List<String> warnings) {
        if (version == null || version.isEmpty()) return false;
        if (wineAvailable(context, contents, version)) return true;

        ProtonPackageManager.PackageInfo pkg = ProtonPackageManager.getPackage(version);
        if (pkg != null) {
            report(progress, "Downloading " + pkg.title + "…");
            File out = new File(context.getCacheDir(), pkg.identifier + ".dl");
            try {
                boolean ok = ProtonPackageManager.downloadPackage(pkg, out, null)
                        && ProtonPackageManager.installPackage(context, pkg.identifier, out);
                if (ok) return true;
            } finally { out.delete(); }
            warnings.add("Failed to install " + pkg.title + ".");
            return false;
        }

        // Catalog / recorded remoteUrl for a content-based Wine or Proton.
        for (ContentProfile.ContentType type : new ContentProfile.ContentType[]{
                ContentProfile.ContentType.CONTENT_TYPE_WINE, ContentProfile.ContentType.CONTENT_TYPE_PROTON}) {
            String remoteUrl = remoteUrlFor(contents, type, version, hints);
            if (remoteUrl != null) {
                report(progress, "Downloading " + version + "…");
                if (downloadAndInstall(context, contents, remoteUrl)) return true;
            }
        }
        return false;
    }

    private static boolean wineAvailable(Context context, ContentsManager contents, String version) {
        if (version.equals(WineInfo.MAIN_WINE_VERSION.identifier())) return true;
        if (ProtonPackageManager.isInstalled(context, version)) return true;
        for (ContentProfile.ContentType type : new ContentProfile.ContentType[]{
                ContentProfile.ContentType.CONTENT_TYPE_WINE, ContentProfile.ContentType.CONTENT_TYPE_PROTON}) {
            for (ContentProfile p : contents.getInstalledProfiles(type)) {
                if (version.equals(ContentsManager.getEntryName(p)) || version.equals(p.verName)) return true;
            }
        }
        return false;
    }

    private static String firstInstalledWine(Context context, ContentsManager contents) {
        if (ProtonPackageManager.isInstalled(context, WineInfo.MAIN_WINE_VERSION.identifier())
                || bundledPatternExists(context, WineInfo.MAIN_WINE_VERSION.identifier()))
            return WineInfo.MAIN_WINE_VERSION.identifier();
        for (String id : ProtonPackageManager.getInstalledIdentifiers(context)) return id;
        for (ContentProfile.ContentType type : new ContentProfile.ContentType[]{
                ContentProfile.ContentType.CONTENT_TYPE_WINE, ContentProfile.ContentType.CONTENT_TYPE_PROTON}) {
            for (ContentProfile p : contents.getInstalledProfiles(type)) return ContentsManager.getEntryName(p);
        }
        return null;
    }

    // ---- Optional content runtimes ----------------------------------------

    /** @return true if the version is present (installed or bundled) or was installed here. */
    private static boolean ensureContent(Context context, ContentsManager contents,
                                         ContentProfile.ContentType type, String version,
                                         JSONArray hints, Progress progress, List<String> warnings) {
        if (version == null || version.isEmpty() || "None".equalsIgnoreCase(version)) return true;
        for (ContentProfile p : contents.getInstalledProfiles(type)) {
            if (version.equals(p.verName) || version.equals(ContentsManager.getEntryName(p))) return true; // installed
        }
        if (bundledAssetExists(context, type, version)) return true; // run-time falls back to the bundled copy

        String remoteUrl = remoteUrlFor(contents, type, version, hints);
        if (remoteUrl != null) {
            report(progress, "Downloading " + type.toString() + " " + version + "…");
            if (downloadAndInstall(context, contents, remoteUrl)) return true;
        }
        Log.w(TAG, "Unresolved component " + type + " " + version);
        return false;
    }

    /**
     * Makes the container's AdrenoTools graphics driver resolve on this device: if it isn't present,
     * download it from the URL recorded in the profile; if that isn't possible, fall back to System.
     */
    private static void sanitizeGraphicsDriver(Context context, JSONObject data, JSONArray hints,
                                               Progress progress, List<String> warnings) {
        String config = data.optString("graphicsDriverConfig", "");
        if (config.isEmpty()) return;
        String driverId = ContainerProfile.readConfigValue(config, "version", ';');
        if (driverId.isEmpty() || "System".equalsIgnoreCase(driverId)) return;

        AdrenotoolsManager adreno = new AdrenotoolsManager(context);
        if (driverAvailable(adreno, driverId)) return;

        // Prefer the exact URL the profile recorded; otherwise best-effort match a configured
        // driver repo by name.
        String remoteUrl = graphicsDriverRemoteUrl(hints, driverId);
        boolean byName = false;
        if (remoteUrl == null) {
            remoteUrl = catalogUrlByName(context, driverId);
            byName = remoteUrl != null;
        }
        if (remoteUrl != null) {
            report(progress, "Downloading graphics driver…");
            try {
                String installedId = RemoteDriverCatalog.install(context, remoteUrl);
                if (installedId != null && !installedId.isEmpty()) {
                    if (!installedId.equals(driverId)) {
                        data.put("graphicsDriverConfig", setConfigValue(config, "version", installedId, ';'));
                        Log.i(TAG, "Installed graphics driver as '" + installedId + "' (profile referenced '" + driverId + "')");
                    }
                    if (byName) warnings.add("Graphics driver '" + driverId + "' was matched by name from a driver repo; verify it's the one you wanted.");
                    return;
                }
            } catch (Exception e) {
                Log.w(TAG, "Graphics driver download failed", e);
            }
        }

        // Couldn't obtain it — leave a working driver rather than a crash-inducing ghost.
        try { data.put("graphicsDriverConfig", setConfigValue(config, "version", "System", ';')); } catch (Exception ignored) {}
        warnings.add("Graphics driver '" + driverId + "' couldn't be installed"
                + (remoteUrl == null ? " (no download source in the profile)" : "")
                + "; using System. Install it manually and reselect for best results.");
        Log.w(TAG, "Fell back to System for unavailable graphics driver '" + driverId + "'");
    }

    private static boolean driverAvailable(AdrenotoolsManager adreno, String driverId) {
        try {
            return adreno.isFromResources(driverId) || adreno.enumarateInstalledDrivers().contains(driverId);
        } catch (Exception e) {
            return false;
        }
    }

    /** Best-effort: find a configured driver repo whose release/asset name matches the driver id. */
    private static String catalogUrlByName(Context context, String driverId) {
        String target = normalize(driverId);
        if (target.isEmpty()) return null;
        try {
            for (RemoteDriverCatalog.Entry entry : RemoteDriverCatalog.load(context)) {
                String name = normalize(entry.name);
                if (name.isEmpty()) continue;
                if (name.equals(target) || name.contains(target) || target.contains(name)) return entry.url;
            }
        } catch (Exception e) {
            Log.w(TAG, "Driver catalog lookup failed", e);
        }
        return null;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(java.util.Locale.ENGLISH).replaceAll("[^a-z0-9]", "");
    }

    private static String graphicsDriverRemoteUrl(JSONArray hints, String driverId) {
        if (hints == null) return null;
        for (int i = 0; i < hints.length(); i++) {
            JSONObject c = hints.optJSONObject(i);
            if (c == null || !"GraphicsDriver".equals(c.optString("type"))) continue;
            if (!driverId.equals(c.optString("version"))) continue;
            String url = c.optString("remoteUrl", "");
            return url.isEmpty() ? null : url;
        }
        return null;
    }

    /** Replaces (or appends) key=value in a delimiter-separated config string. */
    private static String setConfigValue(String config, String key, String value, char delimiter) {
        String d = String.valueOf(delimiter);
        String[] tokens = config.isEmpty() ? new String[0] : config.split(java.util.regex.Pattern.quote(d), -1);
        StringBuilder out = new StringBuilder();
        boolean replaced = false;
        for (String token : tokens) {
            if (out.length() > 0) out.append(delimiter);
            int eq = token.indexOf('=');
            if (eq > 0 && token.substring(0, eq).trim().equals(key)) {
                out.append(key).append('=').append(value);
                replaced = true;
            } else {
                out.append(token);
            }
        }
        if (!replaced) {
            if (out.length() > 0) out.append(delimiter);
            out.append(key).append('=').append(value);
        }
        return out.toString();
    }

    // ---- Shared helpers ----------------------------------------------------

    /** Catalog match (remote profile) or a remoteUrl recorded in the profile's components hint. */
    private static String remoteUrlFor(ContentsManager contents, ContentProfile.ContentType type,
                                       String version, JSONArray hints) {
        List<ContentProfile> profiles = contents.getProfiles(type);
        if (profiles != null) {
            for (ContentProfile p : profiles) {
                if (p.remoteUrl != null && (version.equals(p.verName) || version.equals(ContentsManager.getEntryName(p))))
                    return p.remoteUrl;
            }
        }
        if (hints != null) {
            for (int i = 0; i < hints.length(); i++) {
                JSONObject c = hints.optJSONObject(i);
                if (c == null) continue;
                if (type.toString().equalsIgnoreCase(c.optString("type")) && version.equals(c.optString("version"))) {
                    String url = c.optString("remoteUrl", "");
                    if (!url.isEmpty()) return url;
                }
            }
        }
        return null;
    }

    private static boolean downloadAndInstall(Context context, ContentsManager contents, String remoteUrl) {
        File tmp = new File(context.getCacheDir(), "wcfg_" + System.nanoTime() + ".dl");
        try {
            if (!Downloader.downloadFile(remoteUrl, tmp)) return false;
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicBoolean ok = new AtomicBoolean(false);
            contents.extraContentFile(Uri.fromFile(tmp), new ContentsManager.OnInstallFinishedCallback() {
                @Override public void onSucceed(ContentProfile profile) {
                    contents.recordProfileSource(profile, remoteUrl); // keep it re-exportable
                    ok.set(true); latch.countDown();
                }
                @Override public void onFailed(ContentsManager.InstallFailedReason reason, Exception e) { latch.countDown(); }
            });
            latch.await(INSTALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return ok.get();
        } catch (Exception e) {
            return false;
        } finally {
            tmp.delete();
        }
    }

    private static boolean bundledAssetExists(Context context, ContentProfile.ContentType type, String version) {
        String path;
        switch (type) {
            case CONTENT_TYPE_DXVK:     path = "dxwrapper/dxvk-" + version + ".tzst"; break;
            case CONTENT_TYPE_VKD3D:    path = "dxwrapper/vkd3d-" + version + ".tzst"; break;
            case CONTENT_TYPE_BOX64:    path = "box64/box64-" + version + ".tzst"; break;
            case CONTENT_TYPE_WOWBOX64: path = "wowbox64/wowbox64-" + version + ".tzst"; break;
            case CONTENT_TYPE_FEXCORE:  path = "fexcore/fexcore-" + version + ".tzst"; break;
            default: return false;
        }
        return assetExists(context, path);
    }

    private static boolean bundledPatternExists(Context context, String wineIdentifier) {
        return assetExists(context, wineIdentifier + "_container_pattern.tzst");
    }

    private static boolean assetExists(Context context, String path) {
        try (java.io.InputStream in = context.getAssets().open(path)) {
            return in != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static void report(Progress progress, String message) {
        if (progress != null) progress.status(message);
    }
}
