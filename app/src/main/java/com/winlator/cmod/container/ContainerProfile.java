package com.winlator.cmod.container;

import android.content.Context;

import com.winlator.cmod.contents.AdrenotoolsManager;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.FileUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Serializes a container's settings to a portable {@code .wcfg} profile and back.
 *
 * The profile is the container's own persisted JSON ({@link Container#saveData()} /
 * {@link Container#loadData(JSONObject)}) wrapped in a versioned envelope, minus device-specific
 * fields. It carries settings only — never the Wine prefix, games, or the proprietary Lossless.dll.
 * On import, {@link ContainerImporter} resolves and installs any missing runtime components.
 */
public final class ContainerProfile {
    public static final String FORMAT = "winlator-skynet-container";
    public static final int VERSION = 1;
    public static final String EXTENSION = "wcfg";

    /** Device-specific fields dropped on export; importers fall back to local defaults. */
    private static final String[] EXCLUDED_FIELDS = {"id", "drives", "rendererDriverId"};

    /**
     * "Already-provisioned" markers written into extraData at launch on the source device. Carried
     * over, they make a freshly-imported container skip extracting box64/fexcore/graphics runtime
     * into its (empty) imagefs, so the guest binary is missing and launch fails. Dropping them makes
     * the new container provision on first boot, like a newly-created one. User preferences in
     * extraData (graphics*, hud, startup, lsfg*) are kept.
     */
    private static final String[] EXCLUDED_EXTRA_MARKERS = {
            "imgVersion", "box64Version", "fexcoreVersion", "installedOpenGLDriver",
            "dxwrapper", "graphicsDriver", "audioDriver", "wincomponents", "desktopTheme"
    };

    private ContainerProfile() {}

    public static final class Envelope {
        public final JSONObject container;
        public final JSONArray components;

        Envelope(JSONObject container, JSONArray components) {
            this.container = container;
            this.components = components;
        }
    }

    /** Builds the {@code .wcfg} JSON text for a container. */
    public static String export(Container container, String appVersion, Context context) throws JSONException {
        String raw = FileUtils.readString(container.getConfigFile());
        if (raw == null || raw.isEmpty()) throw new JSONException("Container config is empty");
        JSONObject data = new JSONObject(raw);
        for (String field : EXCLUDED_FIELDS) data.remove(field);
        stripProvisioningMarkers(data);

        JSONArray components = buildComponents(data);
        enrichComponentSources(components, context);

        JSONObject envelope = new JSONObject();
        envelope.put("format", FORMAT);
        envelope.put("version", VERSION);
        envelope.put("exportedBy", "Winlator skyNET " + (appVersion == null ? "" : appVersion));
        envelope.put("exportedAt", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(new Date()));
        envelope.put("components", components);
        envelope.put("container", data);
        return envelope.toString(2);
    }

    /**
     * Records the download URL of each component, when known, so import can re-fetch it exactly:
     * the graphics driver from AdrenotoolsManager, and downloadable content (DXVK, VKD3D, Wine/
     * Proton, etc.) from ContentsManager's remembered sources.
     */
    private static void enrichComponentSources(JSONArray components, Context context) {
        if (context == null) return;
        try {
            AdrenotoolsManager adreno = new AdrenotoolsManager(context);
            ContentsManager contents = new ContentsManager(context);
            for (int i = 0; i < components.length(); i++) {
                JSONObject c = components.optJSONObject(i);
                if (c == null) continue;
                String type = c.optString("type");
                String version = c.optString("version");
                if (version.isEmpty()) continue;

                String url = "";
                if ("GraphicsDriver".equals(type)) {
                    url = adreno.getDriverSourceUrl(version);
                } else {
                    ContentProfile.ContentType ct = ContentProfile.ContentType.getTypeByName(type);
                    if (ct != null) url = contents.getSourceUrl(ct, version);
                }
                if (url != null && !url.isEmpty()) c.put("remoteUrl", url);
            }
        } catch (Exception ignored) {}
    }

    /** Parses and validates a {@code .wcfg} profile. Throws {@link JSONException} if malformed. */
    public static Envelope parse(String json) throws JSONException {
        if (json == null || json.trim().isEmpty()) throw new JSONException("Empty profile");
        JSONObject envelope = new JSONObject(json);
        if (!FORMAT.equals(envelope.optString("format")))
            throw new JSONException("Not a Winlator container profile");
        int version = envelope.optInt("version", -1);
        if (version < 1 || version > VERSION)
            throw new JSONException("Unsupported profile version: " + version);
        if (!envelope.has("container"))
            throw new JSONException("Profile has no container settings");

        JSONObject container = envelope.getJSONObject("container");
        for (String field : EXCLUDED_FIELDS) container.remove(field); // defensive
        stripProvisioningMarkers(container); // defensive: force a clean first-boot provision
        JSONArray components = envelope.optJSONArray("components");
        if (components == null) components = new JSONArray();
        return new Envelope(container, components);
    }

    /** Removes the launch-time "already provisioned" markers from the container's extraData. */
    private static void stripProvisioningMarkers(JSONObject data) {
        JSONObject extra = data.optJSONObject("extraData");
        if (extra == null) return;
        for (String key : EXCLUDED_EXTRA_MARKERS) extra.remove(key);
    }

    /** Suggested export file name, e.g. {@code My Game.wcfg}. */
    public static String suggestedFileName(Container container) {
        String name = container.getName();
        if (name == null || name.trim().isEmpty()) name = "container";
        name = name.replaceAll("[^A-Za-z0-9 ._-]", "_").trim();
        if (name.isEmpty()) name = "container";
        return name + "." + EXTENSION;
    }

    /**
     * Summarizes the runtime components a container references, as {@code {type, version}} entries.
     * Informational + a hint for the import plan; {@link ContainerImporter} re-derives from the
     * container fields (the source of truth) and may enrich entries with a {@code remoteUrl}.
     */
    private static JSONArray buildComponents(JSONObject data) throws JSONException {
        JSONArray out = new JSONArray();
        addComponent(out, "Proton", data.optString("wineVersion", ""));
        addComponent(out, "Box64", data.optString("box64Version", ""));
        addComponent(out, "FEXCore", data.optString("fexcoreVersion", ""));

        String dxConfig = data.optString("dxwrapperConfig", "");
        addComponent(out, "DXVK", readConfigValue(dxConfig, "version", ','));
        addComponent(out, "VKD3D", readConfigValue(dxConfig, "vkd3dVersion", ','));

        String gfxConfig = data.optString("graphicsDriverConfig", "");
        addComponent(out, "GraphicsDriver", readConfigValue(gfxConfig, "version", ';'));
        return out;
    }

    private static void addComponent(JSONArray out, String type, String version) throws JSONException {
        if (version == null || version.isEmpty() || "None".equalsIgnoreCase(version)) return;
        JSONObject c = new JSONObject();
        c.put("type", type);
        c.put("version", version);
        out.put(c);
    }

    /** Reads {@code key=value} from a delimiter-separated config string (matches KeyValueSet). */
    static String readConfigValue(String config, String key, char delimiter) {
        if (config == null || config.isEmpty()) return "";
        for (String token : config.split(java.util.regex.Pattern.quote(String.valueOf(delimiter)))) {
            int eq = token.indexOf('=');
            if (eq > 0 && token.substring(0, eq).trim().equals(key)) return token.substring(eq + 1).trim();
        }
        return "";
    }
}
