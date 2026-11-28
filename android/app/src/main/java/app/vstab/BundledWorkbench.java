package app.vstab;

import android.content.Context;
import android.content.res.AssetManager;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Serves the actual, version-pinned Code OSS client from APK assets. */
final class BundledWorkbench {
    final AuthCallbackServer authCallbacks;
    private final Context context;
    private final AssetManager assets;
    private final ConnectionController.Ready session;
    private final String origin;
    private final JSONObject manifest;
    private final String staticPrefix;
    private final String vsBase;
    private final byte[] startup;
    private final VerifiedBackendAssets verifiedBackend;

    BundledWorkbench(Context context, ConnectionController.Ready session) throws Exception {
        this.context = context.getApplicationContext();
        this.assets = context.getAssets();
        this.session = java.util.Objects.requireNonNull(session, "A native backend session is required.");
        manifest = new JSONObject(readText("workbench/bundle.json"));
        origin = session.origin;
        String expectedServer = manifest.getString("codeServerVersion");
        String expectedCode = manifest.getString("codeVersion");
        if (!expectedServer.equals(session.serverVersion) || !expectedCode.equals(session.version)) {
            throw new IllegalStateException("The bundled editor and remote server versions differ. Update vstab or reinstall its matching remote server.");
        }
        staticPrefix = leadingSlash(manifest.getString("staticPrefix"));
        vsBase = manifest.optString("vsBase", staticPrefix.substring(0, staticPrefix.lastIndexOf("/static/")));
        Map<String, VerifiedBackendAssets.Entry> shared = new HashMap<>();
        JSONObject omitted = manifest.optJSONObject("sharedBackendAssets");
        if (omitted != null) {
            java.util.Iterator<String> names = omitted.keys();
            while (names.hasNext()) {
                String name = names.next();
                if (!(name.startsWith("static/") || name.startsWith("code-server-static/"))
                        || manifest.getJSONObject("files").has(name))
                    throw new IllegalStateException("The pinned editor asset manifest is inconsistent.");
                JSONObject item = omitted.getJSONObject(name);
                String backendPath = item.getString("backendPath");
                if (!(backendPath.startsWith(staticPrefix) || backendPath.startsWith("/_static/")))
                    throw new IllegalStateException("The pinned editor asset is outside the static backend routes.");
                shared.put(name, new VerifiedBackendAssets.Entry(item.getString("sha256"), item.getLong("bytes"), backendPath));
            }
        }
        verifiedBackend = shared.isEmpty() ? null : new VerifiedBackendAssets(
                new java.io.File(context.getCacheDir(), "verified-editor-assets"), shared,
                VerifiedBackendAssets.authenticatedHttp(origin, session.cookies));
        authCallbacks = new AuthCallbackServer();
        try { startup = render().getBytes(StandardCharsets.UTF_8); }
        catch (Exception error) { authCallbacks.close(); throw error; }
    }

    String provenance() {
        return (verifiedBackend == null ? "Editor delivered from APK assets" : "Pinned editor delivered from APK and verified backend assets") + "\nCode OSS " + manifest.optString("codeVersion")
                + "\nSource commit: " + manifest.optString("upstreamCommit")
                + "\nServer integration: code-server " + manifest.optString("codeServerVersion");
    }

    WebResourceResponse intercept(WebResourceRequest request) {
        Uri url = request.getUrl();
        Uri base = Uri.parse(origin);
        if (!base.getScheme().equals(url.getScheme()) || !base.getHost().equals(url.getHost()) || base.getPort() != url.getPort()) return null;
        if (!"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod())) return null;
        String path = url.getPath();
        if (path == null) return null;
        try {
            if (path.equals("/") || path.equals("/index.html")) {
                return response("text/html", new ByteArrayInputStream(startup), false);
            }
            if (path.startsWith(staticPrefix)) {
                String relative = path.substring(staticPrefix.length());
                if (!safeAssetPath(relative)) return missing();
                if (verifiedBackend != null && verifiedBackend.contains("static/" + relative))
                    return verifiedResponse("static/" + relative, mime(relative));
                return response(mime(relative), assets.open("workbench/static/" + relative, AssetManager.ACCESS_STREAMING), true);
            }
            if (path.startsWith("/controls/")) {
                String relative = path.substring("/controls/".length());
                if (!safeAssetPath(relative)) return missing();
                return response(mime(relative), assets.open("workbench/controls/" + relative, AssetManager.ACCESS_STREAMING), true);
            }
            if (path.startsWith("/_static/")) {
                String relative = path.substring("/_static/".length());
                if (!safeAssetPath(relative)) return missing();
                if (verifiedBackend != null && verifiedBackend.contains("code-server-static/" + relative))
                    return verifiedResponse("code-server-static/" + relative, mime(relative));
                return response(mime(relative), assets.open("workbench/code-server-static/" + relative, AssetManager.ACCESS_STREAMING), true);
            }
            if (path.equals(vsBase + "/manifest.json")) {
                JSONObject app = new JSONObject();
                app.put("name", "vstab"); app.put("short_name", "vstab");
                app.put("start_url", "/"); app.put("display", "standalone");
                app.put("background_color", "#1f1f1f"); app.put("theme_color", "#1f1f1f");
                return response("application/manifest+json", new ByteArrayInputStream(app.toString().getBytes(StandardCharsets.UTF_8)), true);
            }
        } catch (Exception missing) {
            // A client asset must never silently fall back to a server-supplied script.
            return missing();
        }
        return null;
    }

    private String render() throws Exception {
        JSONObject configuration = manifest.has("configurationDefaults")
                ? new JSONObject(manifest.getJSONObject("configurationDefaults").toString()) : new JSONObject();
        configuration.put("serverBasePath", ".");
        configuration.put("webviewEndpoint", trimSlash(staticPrefix) + "/out/vs/workbench/contrib/webview/browser/pre");
        configuration.put("isEnabledFileDownloads", true);
        configuration.put("isEnabledFileUploads", true);
        configuration.put("isEnabledFileCache", false);
        configuration.put("isEnabledCoderGettingStarted", false);
        configuration.put("enableWorkspaceTrust", true);
        configuration.put("developmentOptions", new JSONObject().put("logLevel", 3));
        JSONObject defaults = configuration.optJSONObject("configurationDefaults");
        if (defaults == null) defaults = new JSONObject();
        // Some Android WebView/SwiftShader combinations corrupt xterm's WebGL
        // atlas. The supported DOM renderer is the reliable Android default.
        defaults.put("terminal.integrated.gpuAcceleration", "off");
        // WebView 133 can update an EditContext's backing text without applying
        // it to a newly opened document model. Use Monaco's supported textarea
        // input path so Android Open with receives ordinary edits reliably.
        defaults.put("editor.editContext", false);
        configuration.put("configurationDefaults", defaults);
        configuration.put("vstabDirectLoopback", session.directLoopback);
        configuration.put("vstabEnvironmentProvider", session.environmentProvider);
        configuration.put("remoteAuthority", "remote");
        configuration.put("userDataPath", session.home + "/.local/share/vstab/user-data");
        configuration.put("callbackRoute", trimLeading(vsBase) + "/callback");
        configuration.put("vstabAuthCallbackUrl", authCallbacks.url());
        if (session.localRuntime) {
            configuration.put("folderUri", new JSONObject().put("scheme", "vscode-remote").put("authority", "remote").put("path", session.folder));
        } else configuration.remove("folderUri");
        Uri address = Uri.parse(origin);
        org.json.JSONArray extensions = configuration.optJSONArray("additionalBuiltinExtensions");
        if (extensions == null) extensions = new org.json.JSONArray();
        extensions.put(new JSONObject().put("scheme", address.getScheme()).put("authority", address.getEncodedAuthority())
                .put("path", "/controls/extension"));
        configuration.put("additionalBuiltinExtensions", extensions);
        configuration.put("windowIndicator", new JSONObject()
                .put("label", session.localRuntime ? "$(remote)" : "$(remote) SSH: " + sshAlias())
                .put("tooltip", session.localRuntime ? "Open Remote SSH" : session.user + "@" + session.host + ":" + session.sshPort)
                .put("command", "workbench.action.remote.showMenu"));
        byte[] random = new byte[24]; new SecureRandom().nextBytes(random);
        String nonce = android.util.Base64.encodeToString(random, android.util.Base64.NO_WRAP | android.util.Base64.URL_SAFE);
        String template = readText(manifest.optString("template", "workbench/workbench.html"));
        String base = trimSlash(staticPrefix);
        if (!template.contains("{{VSTAB_LOCAL_BOOTSTRAP}}")) throw new IllegalStateException("The bundled startup hook is missing.");
        String html = template
                .replace("{{WORKBENCH_SCRIPT_NONCE}}", nonce)
                .replace("{{WORKBENCH_WEB_CONFIGURATION}}", escape(configuration.toString()))
                .replace("{{WORKBENCH_WEB_BASE_URL}}", base)
                .replace("{{WORKBENCH_AUTH_SESSION}}", "null")
                .replace("{{VSTAB_LOCAL_BOOTSTRAP}}", controlsBootstrap())
                .replace("{{WORKBENCH_NLS_FALLBACK_URL}}", base + "/out/nls.messages.js")
                .replace("{{WORKBENCH_NLS_URL}}", base + "/out/nls.messages.js")
                .replace("{{VS_BASE}}", vsBase)
                .replace("{{BASE}}", ".");
        String originalEntry = "<script type=\"module\" src=\"" + base + "/out/vs/code/browser/workbench/workbench.js\"></script>";
        if (!html.contains(originalEntry)) throw new IllegalStateException("The bundled workbench startup entry is unexpected.");
        return html.replace(originalEntry, "");
    }

    private WebResourceResponse response(String mime, InputStream stream, boolean immutable) {
        return response(mime, stream, immutable, "apk");
    }

    private WebResourceResponse response(String mime, InputStream stream, boolean immutable, String source) {
        Map<String, String> headers = new HashMap<>();
        // Alpha APKs can change embedder patches without changing upstream's
        // product commit in the URL. Never reuse a prior APK's editor scripts.
        headers.put("Cache-Control", "no-store");
        headers.put("X-Content-Type-Options", "nosniff");
        headers.put("X-Vstab-Source", source);
        headers.put("X-Vstab-CodeOSS-Commit", manifest.optString("upstreamCommit"));
        return new WebResourceResponse(mime, isText(mime) ? "UTF-8" : null, 200, "OK", headers, stream);
    }

    private WebResourceResponse verifiedResponse(String name, String mime) {
        try { return response(mime, verifiedBackend.open(name), true, "verified-backend"); }
        catch (IOException rejected) {
            Map<String, String> headers = new HashMap<>();
            headers.put("Cache-Control", "no-store"); headers.put("X-Content-Type-Options", "nosniff");
            headers.put("X-Vstab-Source", "verified-backend-rejected");
            String message = "The matching backend editor asset could not be verified: " + name
                    + ". Reconnect or repair the matching Linux backend.";
            return new WebResourceResponse("text/plain", "UTF-8", 502, "Editor Asset Verification Failed", headers,
                    new ByteArrayInputStream(message.getBytes(StandardCharsets.UTF_8)));
        }
    }

    private WebResourceResponse missing() {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-Vstab-Source", "apk"); headers.put("Cache-Control", "no-store");
        return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", headers,
                new ByteArrayInputStream("Bundled editor asset not found.".getBytes(StandardCharsets.UTF_8)));
    }

    private String readText(String path) throws IOException {
        try (InputStream input = assets.open(path)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private String sshAlias() {
        if (session.savedHostId != null && !session.savedHostId.isEmpty()) {
            try {
                SshConfigStore.Host saved = new SshConfigStore(context).find(session.savedHostId);
                if (saved != null && !saved.alias.isEmpty()) return saved.alias;
            } catch (IOException ignored) { }
        }
        return session.host;
    }

    private String controlsBootstrap() {
        return "<script type=\"module\" src=\"/controls/native-startup.mjs\"></script>";
    }

    private static boolean safeAssetPath(String path) {
        if (path.startsWith("/") || path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0) return false;
        for (String part : path.split("/")) if (part.equals("..") || part.equals(".")) return false;
        return !path.isEmpty();
    }
    private static String leadingSlash(String value) { return value.startsWith("/") ? value : "/" + value; }
    private static String trimSlash(String value) { return value.endsWith("/") ? value.substring(0, value.length() - 1) : value; }
    private static String trimLeading(String value) { return value.startsWith("/") ? value.substring(1) : value; }
    private static String escape(String value) { return value.replace("&", "&amp;").replace("\"", "&quot;").replace("'", "&#39;").replace("<", "&lt;").replace(">", "&gt;"); }
    private static boolean isText(String mime) { return mime.startsWith("text/") || mime.contains("javascript") || mime.contains("json") || mime.contains("xml"); }
    private static String mime(String path) {
        String name = path.toLowerCase(Locale.ROOT);
        if (name.endsWith(".js") || name.endsWith(".mjs")) return "application/javascript";
        if (name.endsWith(".css")) return "text/css";
        if (name.endsWith(".html")) return "text/html";
        if (name.endsWith(".json") || name.endsWith(".map")) return "application/json";
        if (name.endsWith(".wasm")) return "application/wasm";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".ico")) return "image/x-icon";
        if (name.endsWith(".woff2")) return "font/woff2";
        if (name.endsWith(".woff")) return "font/woff";
        if (name.endsWith(".ttf")) return "font/ttf";
        if (name.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }
}
