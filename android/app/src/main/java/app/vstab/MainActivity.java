package app.vstab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.json.JSONObject;
import org.json.JSONArray;

public class MainActivity extends Activity implements ConnectionController.Observer, LocalRuntimeManager.Observer {
    private static final int PICK_KEY = 51;
    private static final int PICK_HOST_KEY = 54;
    private static final int PICK_SHARED_FOLDER = 55;
    private static final int PICK_DOCUMENT = 56;
    private String windowId;
    private String localFolderPath;
    private String openedDocumentPath;
    private boolean openingDocument;
    private boolean firstConnectRequested;
    private Consumer<JSONObject> approvedDocumentSuccess;
    private Consumer<NativeSshBridge.Failure> approvedDocumentFailure;
    private SharedDocumentManager documents;
    private AndroidFileTransfers fileTransfers;
    private String sharedSessionId;
    private String sharedMessage = "";
    private String sharedFailure = "";
    private final java.util.List<SharedDocumentManager.Listener> documentListeners = new java.util.ArrayList<>();
    private static final int INK = Color.rgb(27, 39, 55);
    private static final int SECONDARY = Color.rgb(75, 92, 113);
    private static final int BLUE = Color.rgb(20, 101, 192);
    private FrameLayout root;
    private ConnectionController connection;
    private LocalRuntimeManager runtime;
    private ConnectionController.Ready cachedLocalReady;
    private TextView runtimeStatus;
    private SharedPreferences preferences;
    private EditText host, port, user, password, folder, passphrase;
    private Spinner auth;
    private LinearLayout passwordSection, keySection;
    private Button connect, pickKey;
    private TextView status;
    private ProgressBar progress;
    private Uri keyUri;
    private WebView web;
    private ConnectionController.Ready active;
    private BundledWorkbench bundled;
    private BundledWorkbench localBundle;
    private WebView localWeb;
    private View localSurface;
    private boolean localMode;
    private boolean formOpen;
    private int formRevision;
    private SavedHostStore savedHosts;
    private final java.util.Map<WebView, NativeSshBridge> sshBridges = new java.util.IdentityHashMap<>();
    private final java.util.concurrent.ExecutorService keyWorker = java.util.concurrent.Executors.newSingleThreadExecutor();
    private String sshPhase = "disconnected", sshMessage = "", sshProfileId = "", sshConnectionId = "";
    private String importHostId;
    private String formHostId;
    private android.os.FileObserver configObserver;
    private PendingSshPrompt pendingSshPrompt;
    private SavedHostStore.Host passwordFallbackHost;
    private String passwordFallbackFolder;
    private boolean passwordFallbackAllowed;

    private static final class PendingSshPrompt {
        String id, connectionId;
        WebView source;
        Consumer<JSONObject> callback;
        Runnable timeout;
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        windowId = state != null ? state.getString("workspace-window-id") : null;
        if (windowId == null) windowId = this instanceof WorkspaceActivity || this instanceof DocumentActivity
                ? java.util.UUID.randomUUID().toString() : "main";
        try { connection = ((VstabApplication) getApplication()).connection(windowId); }
        catch (IllegalStateException limit) {
            new AlertDialog.Builder(this).setTitle("Workspace window limit").setMessage(limit.getMessage())
                    .setPositiveButton("Close", (dialog, which) -> finish()).show();
            return;
        }
        localFolderPath = state == null ? null : state.getString("local-folder");
        openedDocumentPath = state == null ? null : state.getString("opened-document");
        if (state == null && this instanceof WorkspaceActivity) {
            localFolderPath = getIntent().getStringExtra("local-folder");
            firstConnectRequested = getIntent().getBooleanExtra("connect-ssh", false);
        }
        runtime = ((VstabApplication) getApplication()).runtime();
        documents = ((VstabApplication) getApplication()).documents();
        fileTransfers = new AndroidFileTransfers(this, source -> source == web && !formOpen
                && source.isShown() && active != null && NativeSshBridge.matchesOrigin(source.getUrl(), active.origin));
        sharedSessionId = state == null ? null : state.getString("shared-session-id");
        connection.observe(this);
        preferences = getSharedPreferences("last-profile", MODE_PRIVATE);
        try { savedHosts = new SavedHostStore(this); }
        catch (Exception error) { Toast.makeText(this, "SSH configuration could not be loaded: " + error.getMessage(), Toast.LENGTH_LONG).show(); }
        String savedKey = preferences.getString("key-uri", "");
        if (!savedKey.isEmpty()) keyUri = Uri.parse(savedKey);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(31, 31, 31));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets keyboard = insets.getInsets(WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard.bottom));
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            root.setWindowInsetsAnimationCallback(new android.view.WindowInsetsAnimation.Callback(
                    android.view.WindowInsetsAnimation.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                @Override public WindowInsets onProgress(WindowInsets insets,
                        java.util.List<android.view.WindowInsetsAnimation> animations) {
                    return insets;
                }
                @Override public void onEnd(android.view.WindowInsetsAnimation animation) {
                    root.requestApplyInsets();
                    if (web != null) notifyWorkbenchViewport(web);
                }
            });
        }
        setContentView(root);
        useLightSystemBarIcons();
        if (savedHosts != null) {
            configObserver = new android.os.FileObserver(savedHosts.config.configFile().getParentFile(),
                    android.os.FileObserver.CLOSE_WRITE | android.os.FileObserver.MOVED_TO
                            | android.os.FileObserver.DELETE | android.os.FileObserver.CREATE) {
                @Override public void onEvent(int event, String path) {
                    if (path == null || path.equals("config")) root.postDelayed(MainActivity.this::publishConfig, 120);
                }
            };
            configObserver.startWatching();
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        openingDocument = this instanceof DocumentActivity && state == null;
        runtime.observe(this);
        ConnectionController.Ready current = connection.current();
        if (current != null) {
            sshPhase = "connected"; sshMessage = "Connected to " + current.host;
            sshProfileId = current.savedHostId; sshConnectionId = java.util.UUID.randomUUID().toString();
        }
        if (openingDocument) openExternalDocument(getIntent());
        else if (current != null) openWorkbench(current); else openLocalWorkspace();
        if (sharedSessionId != null) documents.restore(sharedSessionId, documentListener(null, null, false));
    }

    private void useLightSystemBarIcons() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) controller.setSystemBarsAppearance(0,
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        } else {
            View decor = getWindow().getDecorView();
            decor.setSystemUiVisibility(decor.getSystemUiVisibility()
                    & ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR));
        }
    }

    private void showConnect() {
        cancelSshPrompt();
        if (fileTransfers != null && web != null) fileTransfers.invalidate(web);
        formHostId = null;
        active = null;
        localMode = false;
        formOpen = true;
        formRevision++;
        if (web != null && web != localWeb) destroyWorkbench(web);
        web = null;
        root.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(245, 247, 250));
        scroll.setFillViewport(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        int wide = getResources().getDisplayMetrics().widthPixels;
        int horizontal = Math.max(dp(24), (wide - dp(620)) / 2);
        body.setPadding(horizontal, dp(28), horizontal, dp(24));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        TextView title = text("vstab", 30, INK);
        title.setTypeface(null, Typeface.BOLD);
        title.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_vstab, 0, 0, 0);
        title.setCompoundDrawablePadding(dp(10));
        body.addView(title, width(-2));
        TextView heading = text("Connect over SSH", 22, INK);
        body.addView(heading, spaced(18));
        TextView intro = text("Open a project on your Linux machine. Editing, extensions and terminals use that remote workspace.", 15, SECONDARY);
        intro.setLineSpacing(dp(3), 1);
        body.addView(intro, spaced(8));

        LinearLayout address = new LinearLayout(this);
        address.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout hostGroup = new LinearLayout(this);
        hostGroup.setOrientation(LinearLayout.VERTICAL);
        hostGroup.addView(label("Host"));
        host = input("server.example.com", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, R.id.host);
        host.setText(preferences.getString("host", ""));
        hostGroup.addView(host, width(-1));
        address.addView(hostGroup, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout portGroup = new LinearLayout(this);
        portGroup.setOrientation(LinearLayout.VERTICAL);
        portGroup.setPadding(dp(12), 0, 0, 0);
        portGroup.addView(label("Port"));
        port = input("22", InputType.TYPE_CLASS_NUMBER, R.id.port);
        port.setText(preferences.getString("port", "22"));
        portGroup.addView(port, width(-1));
        address.addView(portGroup, new LinearLayout.LayoutParams(dp(100), -2));
        body.addView(address, spaced(24));

        body.addView(label("User"), spaced(16));
        user = input("Remote account name", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, R.id.username);
        user.setText(preferences.getString("user", ""));
        body.addView(user, width(-1));

        body.addView(label("Authentication"), spaced(16));
        auth = new Spinner(this);
        ArrayAdapter<String> choices = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new String[]{"Password", "SSH key"});
        choices.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        auth.setAdapter(choices);
        auth.setContentDescription("Authentication method");
        body.addView(auth, new LinearLayout.LayoutParams(-1, dp(48)));

        passwordSection = new LinearLayout(this);
        passwordSection.setOrientation(LinearLayout.VERTICAL);
        passwordSection.addView(label("Password"));
        password = input("SSH password", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, R.id.password);
        passwordSection.addView(password, width(-1));
        body.addView(passwordSection, spaced(8));

        keySection = new LinearLayout(this);
        keySection.setOrientation(LinearLayout.VERTICAL);
        pickKey = button(keyUri == null ? "Select SSH private key" : "SSH key selected — choose another", false);
        pickKey.setOnClickListener(view -> chooseKey());
        keySection.addView(pickKey, width(-1));
        keySection.addView(label("Key passphrase"), spaced(12));
        passphrase = input("Leave empty for an unencrypted key", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, R.id.passphrase);
        keySection.addView(passphrase, width(-1));
        keySection.setVisibility(View.GONE);
        body.addView(keySection, spaced(8));
        auth.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                passwordSection.setVisibility(position == 0 ? View.VISIBLE : View.GONE);
                keySection.setVisibility(position == 1 ? View.VISIBLE : View.GONE);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        auth.setSelection(preferences.getBoolean("use-key", false) ? 1 : 0);

        body.addView(label("Remote folder"), spaced(16));
        folder = input("~/project (optional)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, R.id.folder);
        folder.setText(preferences.getString("folder", ""));
        body.addView(folder, width(-1));
        TextView note = text("The first connection installs a private VS Code server in your remote home folder. No administrator access is needed.", 13, SECONDARY);
        note.setLineSpacing(dp(2), 1);
        body.addView(note, spaced(12));

        connect = button("Connect and open workspace", true);
        connect.setId(R.id.connect);
        connect.setOnClickListener(view -> connect());
        body.addView(connect, spaced(20));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        body.addView(progress, new LinearLayout.LayoutParams(-1, dp(4)));
        status = text("Remote SSH is included. Your password is never saved.", 13, SECONDARY);
        status.setId(R.id.connection_status);
        status.setTextIsSelectable(true);
        body.addView(status, spaced(12));
        Button logs = button("Connection log", false);
        logs.setOnClickListener(view -> showLog());
        body.addView(logs, spaced(12));
        Button notices = button("About vstab and open-source notices", false);
        notices.setOnClickListener(view -> showNotices());
        body.addView(notices, spaced(4));
        Button back = button("Back to local workspace", false);
        back.setOnClickListener(view -> {
            if (connection.isBusy()) disconnectToLocal(); else openWorkbench(null);
        });
        body.addView(back, spaced(4));
        if (connection.isBusy()) onStatus("Connecting…", true);
    }

    private void connect() {
        String hostname = host.getText().toString().trim();
        String username = user.getText().toString().trim();
        String folderPath = folder.getText().toString().trim();
        int sshPort;
        try { sshPort = Integer.parseInt(port.getText().toString()); }
        catch (NumberFormatException error) { port.setError("Enter a port from 1 to 65535"); return; }
        if (hostname.isEmpty() || hostname.contains("/") || hostname.contains(" ")) { host.setError("Enter a hostname or IP address"); return; }
        if (sshPort < 1 || sshPort > 65535) { port.setError("Enter a port from 1 to 65535"); return; }
        if (username.isEmpty()) { user.setError("Enter the remote account name"); return; }
        boolean useKey = auth.getSelectedItemPosition() == 1;
        if (useKey && keyUri == null) { Toast.makeText(this, "Select an SSH key first.", Toast.LENGTH_LONG).show(); return; }
        if (!folderPath.isEmpty() && !folderPath.startsWith("/") && !folderPath.equals("~") && !folderPath.startsWith("~/")) {
            folder.setError("Use an absolute path or ~/project"); return;
        }
        preferences.edit().putString("host", hostname).putString("port", Integer.toString(sshPort))
                .putString("user", username).putString("folder", folderPath).putBoolean("use-key", useKey).apply();
        ConnectionController.Profile profile = new ConnectionController.Profile(hostname, sshPort, username,
                password.getText().toString(), folderPath, useKey ? keyUri : null, passphrase.getText().toString());
        try {
            if (savedHosts == null) throw new java.io.IOException("SSH configuration is unavailable.");
            SavedHostStore.Host previous = formHostId == null ? savedHosts.findEndpoint(hostname, sshPort, username) : savedHosts.find(formHostId);
            SavedHostStore.Host saved = savedHosts.save(previous == null ? null : previous.id, previous == null ? "" : previous.label,
                    hostname, sshPort, username, folderPath, useKey, useKey ? keyUri : null);
            profile = new ConnectionController.Profile(hostname, sshPort, username, password.getText().toString(), folderPath,
                    useKey ? keyUri : null, passphrase.getText().toString(), saved.id);
            formHostId = saved.id;
            publishConfig();
        } catch (Exception error) { onFailure(error.getMessage()); return; }
        final ConnectionController.Profile selectedProfile = profile;
        onStatus("Connecting to " + hostname + "…", true);
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(host.getWindowToken(), 0);
        // Cookie host matching ignores ports: replace the loopback server's
        // cookies, retaining the APK origin's cookies and all IndexedDB state.
        int revision = formRevision;
        clearRemoteCookies(() -> { if (formOpen && formRevision == revision) beginConnection(selectedProfile, false); });
    }

    private void openWorkbench(ConnectionController.Ready ready) {
        if (ready == null) { openLocalWorkspace(); return; }
        if (ready.localRuntime && localFolderPath != null && !localFolderPath.equals(ready.folder)) {
            try { ready = localReady(ready, checkedLocalFolder(localFolderPath)); }
            catch (Exception invalid) { showRuntimeState(invalid.getMessage(), true); return; }
        }
        final ConnectionController.Ready selectedReady = ready;
        if (ready.localRuntime && ready == cachedLocalReady && localSurface != null) {
            active = ready; localMode = true; formOpen = false;
            formRevision++;
            bundled = localBundle; web = localWeb;
            root.removeAllViews(); root.addView(localSurface, new FrameLayout.LayoutParams(-1, -1));
            resumeCachedWorkbench(localWeb, ready.origin);
            return;
        }
        try { bundled = new BundledWorkbench(this, ready); }
        catch (Exception error) {
            connection.disconnect();
            showConnect();
            onFailure("The bundled editor could not start: " + error.getMessage());
            return;
        }
        if (!ready.localRuntime && web != null && web == localWeb) fileTransfers.invalidate(web);
        active = ready;
        localMode = ready.localRuntime;
        formOpen = false;
        formRevision++;
        final boolean isLocal = localMode;
        final BundledWorkbench client = bundled;
        final String origin = ready.origin;
        final String startupUrl = ready.url;
        if (isLocal && localWeb != null && cachedLocalReady != ready) {
            destroyWorkbench(localWeb);
            if (web == localWeb) web = null;
            localWeb = null; localSurface = null; localBundle = null;
        }
        if (web != null && web != localWeb) destroyWorkbench(web);
        root.removeAllViews();
        LinearLayout editor = new LinearLayout(this);
        editor.setOrientation(LinearLayout.VERTICAL);
        editor.setBackgroundColor(Color.rgb(31, 31, 31));
        web = new WebView(this);
        // Also removes cache entries written by the first alpha before the
        // bundled client adopted no-store. IndexedDB and workspace files stay.
        web.clearCache(true);
        web.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                notifyWorkbenchViewport((WebView) view);
            }
        });
        web.setId(R.id.workbench);
        web.setBackgroundColor(Color.rgb(31, 31, 31));
        web.setFocusable(true); web.setFocusableInTouchMode(true);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setUserAgentString(settings.getUserAgentString() + " vstab/" + BuildConfig.VERSION_NAME);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        web.setWebViewClient(new WebViewClient() {
            @Override public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return client.intercept(request);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                Uri target = request.getUrl();
                if (sameWorkbench(target, origin)) {
                    String requested = target.getQueryParameter("folder");
                    if (isLocal && requested != null && !requested.equals(selectedReady.folder)) {
                        try {
                            java.io.File selected = checkedLocalFolder(requested);
                            localFolderPath = selected.getAbsolutePath(); openedDocumentPath = null;
                            openWorkbench(localReady(selectedReady, selected));
                        } catch (Exception invalid) { Toast.makeText(MainActivity.this, invalid.getMessage(), Toast.LENGTH_LONG).show(); }
                        return true;
                    }
                    return false;
                }
                if ("about".equals(target.getScheme())) return false;
                openExternal(target); return true;
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (view != web || active == null || active.localRuntime != isLocal
                        || !active.origin.equals(origin) || !NativeSshBridge.matchesOrigin(url, origin)) return;
                String path = Uri.parse(url).getPath();
                if (path != null && !path.isEmpty() && !path.equals("/") && !path.equals("/index.html")) return;
                NativeSshBridge bridge = sshBridges.get(view);
                if (bridge == null) {
                    bridge = new NativeSshBridge((method, params, success, failure) -> handleSshRequest(view, method, params, success, failure));
                    sshBridges.put(view, bridge);
                }
                bridge.attach(view, origin);
                publishSshState();
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                fileTransfers.invalidate(view);
                // Even a same-origin workbench reload replaces its JS context.
                if (pendingSshPrompt != null && pendingSshPrompt.source == view) cancelSshPrompt();
                NativeSshBridge bridge = sshBridges.get(view); if (bridge != null) bridge.close();
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) Toast.makeText(MainActivity.this,
                        isLocal ? "The bundled editor could not load." : "Workbench connection interrupted. Use Disconnect to reconnect.", Toast.LENGTH_LONG).show();
            }
        });
        ((VstabApplication) getApplication()).registerWorkbench(windowId + (isLocal ? ":local" : ""), client);
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, android.webkit.ValueCallback<Uri[]> callback,
                                                       WebChromeClient.FileChooserParams params) {
                return fileTransfers.showFileChooser(view, callback, params);
            }
            @Override public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message message) {
                WebView popup = new WebView(MainActivity.this);
                popup.setWebViewClient(new WebViewClient() {
                    @Override public boolean shouldOverrideUrlLoading(WebView child, WebResourceRequest request) {
                        openExternal(request.getUrl()); child.destroy(); return true;
                    }
                });
                ((WebView.WebViewTransport) message.obj).setWebView(popup);
                message.sendToTarget();
                return true;
            }
        });
        editor.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(editor, new FrameLayout.LayoutParams(-1, -1));
        if (isLocal) {
            localWeb = web; localBundle = client; localSurface = editor; cachedLocalReady = ready;
        }
        AtomicInteger outstanding = new AtomicInteger(ready.cookies.size());
        for (String cookie : ready.cookies) CookieManager.getInstance().setCookie(ready.origin, cookie, success -> {
            if (!success) {
                Toast.makeText(this, "Could not authenticate the workbench session.", Toast.LENGTH_LONG).show();
                return;
            }
            if (outstanding.decrementAndGet() == 0 && web != null && active == selectedReady) {
                CookieManager.getInstance().flush();
                web.loadUrl(startupUrl);
            }
        });
        if (password != null) password.setText("");
        if (passphrase != null) passphrase.setText("");
    }

    private boolean sameWorkbench(Uri uri, String origin) {
        Uri base = Uri.parse(origin);
        return base.getScheme().equals(uri.getScheme()) && base.getHost().equals(uri.getHost()) && base.getPort() == uri.getPort();
    }

    private void openExternal(Uri target) {
        if ("vstab".equalsIgnoreCase(target.getScheme())) { handleAppAction(target); return; }
        if (!"https".equalsIgnoreCase(target.getScheme()) && !"http".equalsIgnoreCase(target.getScheme())) {
            Toast.makeText(this, "This external link type is not supported.", Toast.LENGTH_LONG).show(); return;
        }
        try { startActivity(new Intent(Intent.ACTION_VIEW, target).addCategory(Intent.CATEGORY_BROWSABLE)); }
        catch (ActivityNotFoundException error) { Toast.makeText(this, "Install a browser to open this sign-in link.", Toast.LENGTH_LONG).show(); }
    }

    private void handleAppAction(Uri target) {
        // These are UI requests from the active main workbench only. No URI
        // accepts host details, credentials, commands or filesystem parameters.
        if (web == null || active == null || formOpen || target.getQuery() != null || target.getFragment() != null
                || target.getUserInfo() != null || target.getPort() != -1) return;
        String action = target.toString();
        if (action.equals("vstab://ssh/connect")) {
            if (active.localRuntime) showConnect();
            else new AlertDialog.Builder(this).setTitle("Connect to another SSH host?")
                    .setMessage("Save your remote files before changing workspaces.")
                    .setNegativeButton("Keep working", null)
                    .setPositiveButton("Choose host", (dialog, which) -> { disconnectToLocal(); showConnect(); }).show();
        } else if (action.equals("vstab://ssh/disconnect")) confirmDisconnect();
        else if (action.equals("vstab://workspace/local")) { if (!active.localRuntime) confirmDisconnect(); }
        else if (action.equals("vstab://about")) showNotices();
        else if (action.equals("vstab://window/new")) showNewWindow();
        else if (action.equals("vstab://window/close")) finish();
    }

    private void openLocalWorkspace() {
        ConnectionController.Ready ready = runtime.current();
        if (ready != null) { openWorkbench(ready); return; }
        showRuntimeState("Starting the on-device development environment…", false);
        try { startForegroundService(new Intent(this, LocalRuntimeService.class)); }
        catch (RuntimeException error) { onLocalRuntimeFailure("Android could not start the local runtime service. Reopen vstab and retry."); }
    }

    private java.io.File checkedLocalFolder(String path) throws java.io.IOException {
        java.io.File base = getFilesDir().getCanonicalFile();
        java.io.File candidate = new java.io.File(path).getCanonicalFile();
        if (!candidate.getPath().startsWith(base.getPath() + java.io.File.separator))
            throw new java.io.IOException("Choose an app project or use Open Shared Folder for Android storage.");
        if (!candidate.isDirectory() && !candidate.mkdirs()) throw new java.io.IOException("This project folder could not be opened.");
        return candidate;
    }

    private ConnectionController.Ready localReady(ConnectionController.Ready base, java.io.File directory) {
        if (cachedLocalReady != null && cachedLocalReady.origin.equals(base.origin) && cachedLocalReady.folder.equals(directory.getAbsolutePath()))
            return cachedLocalReady;
        ConnectionController.Profile profile = new ConnectionController.Profile(base.host, base.sshPort, base.user, "", directory.getAbsolutePath(), null, "");
        return new ConnectionController.Ready(base.origin, base.origin + "/?folder=" + Uri.encode(directory.getAbsolutePath()), profile,
                base.version, base.serverVersion, base.home, directory.getAbsolutePath(), base.remotePort, base.cookies, true);
    }

    private void showNewWindow() {
        new AlertDialog.Builder(this).setTitle("New workspace window")
                .setItems(new String[]{"Android project", "SSH workspace"}, (dialog, which) -> {
                    if (which == 1) { launchWorkspaceWindow(null, true); return; }
                    EditText path = input("Project folder", InputType.TYPE_CLASS_TEXT, View.NO_ID);
                    path.setText(new java.io.File(getFilesDir(), "workspaces/project-" + (System.currentTimeMillis() % 100000)).getAbsolutePath());
                    LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
                    body.setPadding(dp(24), dp(8), dp(24), dp(8)); body.addView(path, width(-1));
                    AlertDialog chooser = new AlertDialog.Builder(this).setTitle("Android project in new window").setView(body)
                            .setNegativeButton("Cancel", null).setPositiveButton("Open window", null).create();
                    chooser.setOnShowListener(ignored -> chooser.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                        try { java.io.File selected = checkedLocalFolder(path.getText().toString());
                            chooser.dismiss(); launchWorkspaceWindow(selected.getAbsolutePath(), false); }
                        catch (Exception error) { path.setError(error.getMessage()); }
                    })); chooser.show();
                }).setNegativeButton("Cancel", null).show();
    }

    private void launchWorkspaceWindow(String path, boolean ssh) {
        Intent next = new Intent(this, WorkspaceActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK | Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
                .putExtra("connect-ssh", ssh);
        if (path != null) next.putExtra("local-folder", path);
        startActivity(next);
    }

    private void showRuntimeState(String message, boolean failed) {
        active = null; localMode = true; formOpen = false; formRevision++;
        root.removeAllViews();
        LinearLayout loading = new LinearLayout(this);
        loading.setOrientation(LinearLayout.VERTICAL); loading.setGravity(Gravity.CENTER);
        loading.setPadding(dp(28), dp(28), dp(28), dp(28));
        loading.setBackgroundColor(Color.rgb(31, 31, 31));
        if (!failed) loading.addView(new ProgressBar(this), new LinearLayout.LayoutParams(dp(48), dp(48)));
        runtimeStatus = text(message, 16, Color.WHITE);
        runtimeStatus.setGravity(Gravity.CENTER); runtimeStatus.setTextIsSelectable(failed);
        loading.addView(runtimeStatus, spaced(20));
        if (failed) {
            Button retry = button("Retry local runtime", true);
            retry.setOnClickListener(view -> openLocalWorkspace()); loading.addView(retry, spaced(20));
            Button ssh = button("Connect to SSH host", false);
            ssh.setTextColor(Color.rgb(110, 180, 255)); ssh.setOnClickListener(view -> showConnect());
            loading.addView(ssh, spaced(8));
        }
        root.addView(loading, new FrameLayout.LayoutParams(-1, -1));
    }

    private void shortcut(int key, int modifiers) {
        if (web == null) return;
        web.requestFocus();
        long time = android.os.SystemClock.uptimeMillis();
        web.dispatchKeyEvent(new KeyEvent(time, time, KeyEvent.ACTION_DOWN, key, 0, modifiers));
        web.dispatchKeyEvent(new KeyEvent(time, time + 1, KeyEvent.ACTION_UP, key, 0, modifiers));
    }

    private void notifyWorkbenchViewport(WebView target) {
        // Android's view layout and Chromium's visual viewport settle on
        // separate frames during IME/window animations. Notify the ordinary
        // workbench layout listener after the real browser viewport updates;
        // never synthesize dimensions or alter editor DOM/model state.
        target.postOnAnimation(() -> {
            if (!target.isAttachedToWindow() || target.getWidth() == 0 || target.getHeight() == 0) return;
            target.evaluateJavascript("requestAnimationFrame(()=>window.dispatchEvent(new Event('resize')))", null);
        });
    }

    private void resumeCachedWorkbench(WebView restored, String origin) {
        restored.onResume();
        restored.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                android.view.ViewTreeObserver observer = restored.getViewTreeObserver();
                if (observer.isAlive()) observer.removeOnGlobalLayoutListener(this);
                if (web != restored || !restored.isAttachedToWindow() || !restored.isShown()) return;
                restored.requestFocus(View.FOCUS_DOWN);
                restored.requestApplyInsets();
                notifyWorkbenchViewport(restored);
                if (NativeSshBridge.matchesOrigin(restored.getUrl(), origin)) {
                    NativeSshBridge bridge = sshBridges.get(restored);
                    if (bridge == null) {
                        bridge = new NativeSshBridge((method, params, success, failure) -> handleSshRequest(restored, method, params, success, failure));
                        sshBridges.put(restored, bridge);
                    }
                    bridge.attach(restored, origin);
                }
                publishSshState();
            }
        });
        root.requestApplyInsets();
        restored.requestLayout();
    }

    private void chooseKey() {
        Intent request = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
        request.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(request, PICK_KEY);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (fileTransfers != null && fileTransfers.onActivityResult(request, result, data)) return;
        if (request == PICK_SHARED_FOLDER || request == PICK_DOCUMENT) {
            Consumer<JSONObject> success = approvedDocumentSuccess;
            Consumer<NativeSshBridge.Failure> failure = approvedDocumentFailure;
            approvedDocumentSuccess = null; approvedDocumentFailure = null;
            if (result != RESULT_OK || data == null || data.getData() == null) {
                if (failure != null) failure.accept(new NativeSshBridge.Failure("Cancelled", "The Android picker was cancelled."));
                return;
            }
            SharedDocumentManager.Listener listener = documentListener(success, failure, false);
            if (request == PICK_SHARED_FOLDER) documents.openTree(data.getData(), data.getFlags(), listener);
            else documents.openDocument(data.getData(), data.getFlags(), listener);
            return;
        }
        if (request == PICK_HOST_KEY) {
            String profileId = importHostId; importHostId = null;
            if (profileId == null || result != RESULT_OK || data == null || data.getData() == null) return;
            Uri selected = data.getData();
            keyWorker.execute(() -> {
                try {
                    String imported = NativeSshKeys.importKey(this, selected);
                    SshConfigStore.Host configured = savedHosts.config.find(profileId);
                    if (configured == null) throw new java.io.IOException("The SSH host was removed before key import finished.");
                    java.util.List<String> identities = new java.util.ArrayList<>(configured.identityPaths);
                    identities.remove(imported); identities.add(0, imported);
                    String authMethods = configured.preferredAuthentications;
                    if (!java.util.Arrays.asList(authMethods.split(",")).contains("publickey"))
                        authMethods = "publickey" + (authMethods.isEmpty() ? "" : "," + authMethods);
                    savedHosts.config.saveHost(configured.id, configured.alias, configured.hostname, configured.port,
                            configured.user, identities, configured.identitiesOnly, authMethods);
                    savedHosts.clearMigrationKey(profileId);
                    runOnUiThread(() -> { publishConfig(); Toast.makeText(this, "SSH key imported privately. Its original encryption is preserved.", Toast.LENGTH_LONG).show(); });
                } catch (Exception error) { runOnUiThread(() -> Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show()); }
            });
            return;
        }
        if (request != PICK_KEY || result != RESULT_OK || data == null || data.getData() == null) return;
        keyUri = data.getData();
        try { getContentResolver().takePersistableUriPermission(keyUri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (SecurityException ignored) { }
        preferences.edit().putString("key-uri", keyUri.toString()).apply();
        pickKey.setText("SSH key selected — choose another");
    }

    private void confirmDisconnect() {
        if (active == null || active.localRuntime) { Toast.makeText(this, "No SSH workspace is connected.", Toast.LENGTH_SHORT).show(); return; }
        new AlertDialog.Builder(this).setTitle("Disconnect from SSH?")
                .setMessage("Save your files first. Remote processes may continue on the server.")
                .setNegativeButton("Keep working", null)
                .setPositiveButton("Disconnect", (dialog, which) -> {
                    disconnectToLocal();
                }).show();
    }

    private void showLog() {
        String detail = connection.logText();
        if (detail.isEmpty()) detail = "Connection events will appear here. Passwords and authentication cookies are not logged.";
        TextView content = text(detail, 13, INK);
        content.setPadding(dp(24), dp(12), dp(24), dp(12));
        content.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        new AlertDialog.Builder(this).setTitle("Connection log").setView(scroll).setPositiveButton("Close", null).show();
    }

    private void clearRemoteCookies(Runnable complete) {
        CookieManager cookies = CookieManager.getInstance();
        String cookieOrigin = "http://" + connection.loopbackHost() + "/";
        String existing = cookies.getCookie(cookieOrigin);
        if (existing == null || existing.isEmpty()) { complete.run(); return; }
        java.util.List<String> names = new java.util.ArrayList<>();
        for (String item : existing.split(";")) {
            int equals = item.indexOf('=');
            if (equals <= 0) continue;
            String name = item.substring(0, equals).trim();
            if (name.matches("[a-zA-Z0-9_-]+")) names.add(name);
        }
        if (names.isEmpty()) { complete.run(); return; }
        AtomicInteger pending = new AtomicInteger(names.size());
        for (String name : names) cookies.setCookie(cookieOrigin, name + "=; Max-Age=0; Path=/", ignored -> {
            if (pending.decrementAndGet() == 0) { cookies.flush(); complete.run(); }
        });
    }

    private JSONObject sshState() throws Exception {
        if (savedHosts == null) throw new java.io.IOException("SSH configuration is unavailable.");
        JSONArray profiles = new JSONArray();
        ConnectionController.Ready current = connection.current();
        for (SavedHostStore.Host host : savedHosts.list()) profiles.put(host.publicMetadata(current != null && host.id.equals(current.savedHostId)));
        JSONObject snapshot = savedHosts.config.readConfig().json(); snapshot.remove("content");
        JSONObject transport = new JSONObject().put("phase", sshPhase).put("profileId", sshProfileId)
                .put("connectionId", sshConnectionId).put("message", sshMessage)
                .put("folder", current == null ? "" : current.folder).put("home", current == null ? "" : current.home);
        return new JSONObject().put("schema", 1).put("profiles", profiles).put("connection", transport).put("config", snapshot);
    }

    private void publishSshState() {
        try {
            JSONObject state = sshState();
            for (NativeSshBridge bridge : sshBridges.values()) bridge.publishState(state);
        } catch (Exception ignored) { /* Config requests report readable errors; do not overwrite a damaged file. */ }
    }

    private void publishConfig() {
        try {
            JSONObject snapshot = savedHosts.config.readConfig().json();
            for (NativeSshBridge bridge : sshBridges.values()) bridge.publishConfigChanged(snapshot);
        } catch (Exception error) { Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show(); }
        publishSshState();
    }

    private void setSshPhase(String phase, String message) {
        sshPhase = phase; sshMessage = message; publishSshState();
    }

    private void handleSshRequest(WebView source, String method, JSONObject params,
                                  Consumer<JSONObject> success, Consumer<NativeSshBridge.Failure> failure) {
        try {
            if (method.startsWith("files.")) { fileTransfers.request(source, method, params, success, failure); return; }
            if (method.equals("documents.getState")) { success.accept(documentState()); return; }
            if (source == web && !formOpen && source.isShown()) {
                if (method.equals("window.new")) {
                    success.accept(new JSONObject().put("accepted", true)); source.postDelayed(this::showNewWindow, 100); return;
                }
                if (method.startsWith("documents.")) {
                    handleDocumentRequest(method, params, success, failure); return;
                }
            }
            if (savedHosts == null) throw new java.io.IOException("SSH configuration is unavailable.");
            if (method.equals("ssh.getState")) { success.accept(sshState()); return; }
            if (method.equals("ssh.readConfig")) { success.accept(savedHosts.config.readConfig().json()); return; }
            if (source != web || formOpen || !source.isShown() || !hasWindowFocus()) {
                failure.accept(new NativeSshBridge.Failure("NoPermissions", "Use the visible workbench for SSH actions.")); return;
            }
            switch (method) {
                case "ssh.writeConfig": {
                    JSONObject snapshot = savedHosts.config.writeConfig(params.getString("content"), params.getString("expectedRevision")).json();
                    success.accept(snapshot); publishConfig(); return;
                }
                case "ssh.addHost": {
                    SshConfigStore.Host added = savedHosts.config.addFromCommand(params.getString("command"), params.optString("alias", null));
                    success.accept(new JSONObject().put("profileId", added.id).put("state", sshState())); publishConfig(); return;
                }
                case "ssh.removeHost":
                    savedHosts.remove(params.getString("profileId")); success.accept(sshState()); publishConfig(); return;
                case "ssh.connect": case "ssh.reconnect": {
                    String id = params.getString("profileId");
                    if (savedHosts.find(id) == null) { failure.accept(new NativeSshBridge.Failure("FileNotFound", "This SSH host was removed from config.")); return; }
                    success.accept(new JSONObject().put("accepted", true));
                    boolean reconnect = method.equals("ssh.reconnect");
                    String requestedFolder = params.optString("path", "");
                    postSshUi(source, () -> promptSavedConnection(id, reconnect, requestedFolder)); return;
                }
                case "ssh.respondPrompt": {
                    PendingSshPrompt prompt = pendingSshPrompt;
                    if (prompt == null || prompt.source != source || !prompt.id.equals(params.getString("promptId"))
                            || !prompt.connectionId.equals(sshConnectionId))
                        throw new SecurityException("This SSH prompt is no longer active.");
                    String value = params.optString("value", "");
                    if (value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 16 * 1024)
                        throw new IllegalArgumentException("The SSH credential response is too large.");
                    success.accept(new JSONObject().put("accepted", true));
                    pendingSshPrompt = null; root.removeCallbacks(prompt.timeout);
                    source.postDelayed(() -> {
                        if (source == web && !formOpen && source.isShown() && hasWindowFocus() && prompt.connectionId.equals(sshConnectionId)) prompt.callback.accept(params);
                        else try { prompt.callback.accept(new JSONObject().put("cancelled", true)); } catch (Exception ignored) { }
                    }, 100); return;
                }
                case "ssh.disconnect": case "workspace.local":
                    success.accept(new JSONObject().put("accepted", true)); postSshUi(source, this::disconnectToLocal); return;
                case "ssh.recordWorkspace": {
                    requireSession(params);
                    ConnectionController.Ready changed = connection.recordWorkspace(params.getString("path"));
                    active = changed;
                    savedHosts.markConnected(changed.savedHostId, changed.folder);
                    success.accept(new JSONObject().put("accepted", true));
                    setSshPhase("connected", "Connected to " + changed.host);
                    return;
                }
                case "ssh.importKey": {
                    String id = params.getString("profileId");
                    if (savedHosts.find(id) == null) { failure.accept(new NativeSshBridge.Failure("FileNotFound", "This SSH host was removed from config.")); return; }
                    if (importHostId != null) throw new IllegalArgumentException("Finish the current SSH key picker first.");
                    success.accept(new JSONObject().put("accepted", true));
                    postSshUi(source, () -> {
                        importHostId = id;
                        try { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                                .addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), PICK_HOST_KEY); }
                        catch (ActivityNotFoundException unavailable) { importHostId = null; Toast.makeText(this, "No Android document picker is available.", Toast.LENGTH_LONG).show(); }
                    }); return;
                }
                case "app.about":
                    success.accept(new JSONObject().put("accepted", true)); postSshUi(source, this::showNotices); return;
                default: failure.accept(new NativeSshBridge.Failure("NoPermissions", "This SSH action is unavailable."));
            }
        } catch (Exception error) {
            String message = error.getMessage() == null ? "The SSH action could not be completed." : error.getMessage();
            String code = error instanceof SshConfigStore.ConflictException ? "Conflict" : error instanceof SecurityException ? "NoPermissions" :
                    (message.toLowerCase(java.util.Locale.ROOT).contains("changed") || message.toLowerCase(java.util.Locale.ROOT).contains("revision") ? "Conflict" :
                    (error instanceof IllegalArgumentException ? "InvalidConfiguration" : "Unavailable"));
            failure.accept(new NativeSshBridge.Failure(code, message));
        }
    }

    private void handleDocumentRequest(String method, JSONObject params, Consumer<JSONObject> success,
                                       Consumer<NativeSshBridge.Failure> failure) throws Exception {
        if (method.equals("documents.pickFolder") || method.equals("documents.pickFile")) {
            if (approvedDocumentSuccess != null) throw new IllegalStateException("Finish the current Android picker first.");
            approvedDocumentSuccess = success; approvedDocumentFailure = failure;
            boolean tree = method.equals("documents.pickFolder");
            Intent request = tree ? new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE) : new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
            request.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | (tree ? Intent.FLAG_GRANT_PREFIX_URI_PERMISSION : 0));
            try { startActivityForResult(request, tree ? PICK_SHARED_FOLDER : PICK_DOCUMENT); }
            catch (ActivityNotFoundException error) {
                approvedDocumentSuccess = null; approvedDocumentFailure = null;
                failure.accept(new NativeSshBridge.Failure("Unavailable", "No Android document picker is available."));
            }
            return;
        }
        if (sharedSessionId == null || documents.session(sharedSessionId) == null)
            throw new IllegalStateException("Open a shared file or folder first.");
        if (method.equals("documents.openApproved")) {
            SharedDocumentManager.Session approved = documents.session(sharedSessionId);
            localFolderPath = approved.directory.getAbsolutePath();
            openedDocumentPath = approved.documentFile == null ? null : approved.documentFile.getAbsolutePath();
            success.accept(new JSONObject().put("accepted", true));
            root.postDelayed(this::disconnectToLocal, 100); return;
        }
        if (method.equals("documents.sync")) documents.syncNow(sharedSessionId);
        else if (method.equals("documents.resolve")) documents.resolve(sharedSessionId, params.getString("path"), params.getBoolean("keepLocal"));
        else throw new IllegalArgumentException("Unknown document action.");
        success.accept(new JSONObject().put("accepted", true));
    }

    private SharedDocumentManager.Listener documentListener(Consumer<JSONObject> success,
            Consumer<NativeSshBridge.Failure> failure, boolean navigate) {
        SharedDocumentManager.Listener listener = new SharedDocumentManager.Listener() {
            @Override public void onOpened(SharedDocumentManager.Session session) {
                if (isFinishing() || isDestroyed()) return;
                sharedSessionId = session.id; sharedFailure = "";
                sharedMessage = "Shared " + (session.singleDocument ? "file" : "project") + ": " + session.name;
                if (navigate) {
                    localFolderPath = session.directory.getAbsolutePath();
                    openedDocumentPath = session.documentFile == null ? null : session.documentFile.getAbsolutePath();
                    openingDocument = false; openLocalWorkspace();
                }
                if (success != null) try { success.accept(documentState()); }
                catch (Exception error) { onFailure(error.getMessage()); }
            }
            @Override public void onChanged(SharedDocumentManager.Session session, DocumentSyncEngine.Result result) {
                if (isFinishing() || isDestroyed() || !session.id.equals(sharedSessionId)) return;
                sharedFailure = result.warnings.isEmpty() ? "" : String.join("\n", result.warnings);
                sharedMessage = result.conflicts.isEmpty() ? "Shared changes synchronized" : "Shared edits need conflict review";
            }
            @Override public void onFailure(String message) {
                if (isFinishing() || isDestroyed()) return;
                sharedFailure = message; sharedMessage = "Shared file changes have not been delivered";
                if (failure != null) failure.accept(new NativeSshBridge.Failure("Unavailable", message));
                if (navigate && openingDocument) { openingDocument = false; showRuntimeState(message, true); }
            }
        };
        documentListeners.add(listener); return listener;
    }

    private JSONObject documentState() throws Exception {
        JSONObject result = new JSONObject().put("message", sharedMessage).put("error", sharedFailure)
                .put("local", localMode).put("openedFile", openedDocumentPath == null ? JSONObject.NULL : new java.io.File(openedDocumentPath).getCanonicalPath());
        SharedDocumentManager.Session session = sharedSessionId == null ? null : documents.session(sharedSessionId);
        if (session == null) return result;
        result.put("sessionId", session.id).put("folderPath", session.directory.getCanonicalPath())
                .put("filePath", session.documentFile == null ? JSONObject.NULL : session.documentFile.getCanonicalPath()).put("name", session.name);
        JSONArray conflicts = new JSONArray();
        for (DocumentSyncEngine.Conflict conflict : session.conflicts()) conflicts.put(new JSONObject()
                .put("path", conflict.path).put("recoveryPath", conflict.recoveryPath));
        result.put("conflicts", conflicts); return result;
    }

    private void openExternalDocument(Intent intent) {
        Uri source = intent.getData();
        if (!(Intent.ACTION_VIEW.equals(intent.getAction()) || Intent.ACTION_EDIT.equals(intent.getAction()))
                || source == null || !"content".equals(source.getScheme())) {
            openingDocument = false;
            showRuntimeState("Open a source file from Android Files and grant vstab access to it.", true); return;
        }
        showRuntimeState("Opening the Android document…", false);
        documents.openDocument(source, intent.getFlags(), documentListener(null, null, true));
    }

    private void requireSession(JSONObject params) throws Exception {
        ConnectionController.Ready current = connection.current();
        if (current == null || !"connected".equals(sshPhase) || !current.savedHostId.equals(params.getString("profileId"))
                || !sshConnectionId.equals(params.getString("connectionId")))
            throw new SecurityException("This directory request belongs to an inactive SSH session. Refresh Remote Explorer.");
    }

    private void postSshUi(WebView source, Runnable action) {
        source.postDelayed(() -> { if (source == web && !formOpen && source.isShown() && hasWindowFocus()) action.run(); }, 100);
    }

    private void beginConnection(ConnectionController.Profile profile, boolean reconnect) {
        cancelSshPrompt();
        sshProfileId = profile.savedHostId; sshConnectionId = java.util.UUID.randomUUID().toString();
        setSshPhase(reconnect ? "reconnecting" : "connecting", "Connecting to " + profile.host + "…");
        connection.connect(profile);
    }

    private void promptSavedConnection(String id, boolean reconnect, String requestedFolder) {
        try {
            SavedHostStore.Host host = savedHosts.find(id);
            if (host == null) throw new java.io.IOException("This SSH host was removed from config.");
            if (host.user.isEmpty() || host.host.isEmpty()) {
                showConnect(); formHostId = id; this.host.setText(host.host); port.setText(Integer.toString(host.port));
                user.setText(host.user); folder.setText(requestedFolder); return;
            }
            if (!host.configured.connectable) throw new java.io.IOException("This host has unsupported SSH configuration. Open its config diagnostics before connecting.");
            String selectedFolder = requestedFolder;
            if (!requestedFolder.isEmpty() && !requestedFolder.startsWith("/") && !requestedFolder.equals("~") && !requestedFolder.startsWith("~/"))
                throw new IllegalArgumentException("Use an absolute remote directory or ~/project.");
            java.util.List<Uri> keys = new java.util.ArrayList<>();
            java.util.List<String> methods = new java.util.ArrayList<>();
            String preferred = host.configured.preferredAuthentications;
            boolean publicKeyAllowed = preferred.isEmpty() || java.util.Arrays.asList(preferred.split(",")).contains("publickey");
            if (publicKeyAllowed && host.key != null) { keys.add(host.key); methods.add("Imported SSH key"); }
            for (String identity : publicKeyAllowed ? host.configured.identityPaths : java.util.Collections.<String>emptyList()) {
                try { keys.add(NativeSshKeys.resolve(this, identity, host.configured)); methods.add("SSH key: " + identity); }
                catch (java.io.IOException ignored) { /* Missing keys remain visible in config; password may still be allowed. */ }
            }
            boolean passwordAllowed = preferred.isEmpty() || java.util.Arrays.asList(preferred.split(",")).contains("password");
            if (passwordAllowed) { keys.add(null); methods.add("Password"); }
            if (methods.isEmpty()) throw new java.io.IOException("No readable IdentityFile is available. Import a key or correct its path in SSH config.");
            if (keys.get(0) != null && !NativeSshKeys.isEncrypted(this, keys.get(0))) {
                ConnectionController.Profile profile = new ConnectionController.Profile(host.host, host.port, host.user,
                        "", selectedFolder, keys.get(0), "", host.id);
                passwordFallbackHost = host; passwordFallbackFolder = selectedFolder; passwordFallbackAllowed = passwordAllowed;
                clearRemoteCookies(() -> beginConnection(profile, reconnect)); return;
            }
            if (promptDesktopAuthentication(host, keys.get(0), reconnect, selectedFolder, passwordAllowed)) return;
            LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(24), dp(8), dp(24), 0);
            Spinner choice = new Spinner(this);
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, methods);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); choice.setAdapter(adapter);
            choice.setContentDescription("SSH authentication method"); body.addView(choice, width(-1));
            EditText secret = input("Password or key passphrase", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, R.id.password);
            secret.setContentDescription("SSH password or private key passphrase"); body.addView(secret, spaced(12));
            TextView explanation = text("Passwords and key passphrases stay on this device for this connection and are never saved.", 13, SECONDARY);
            body.addView(explanation, spaced(12));
            choice.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long itemId) {
                    secret.setText(""); secret.setHint(keys.get(position) == null ? "SSH password" : "Key passphrase (empty for an unencrypted key)");
                }
                @Override public void onNothingSelected(AdapterView<?> parent) { }
            });
            new AlertDialog.Builder(this).setTitle("Connect to " + host.label).setView(body)
                    .setNegativeButton("Cancel", (dialog, which) -> secret.setText(""))
                    .setPositiveButton("Connect", (dialog, which) -> {
                        Uri selected = keys.get(choice.getSelectedItemPosition()); String supplied = secret.getText().toString(); secret.setText("");
                        ConnectionController.Profile profile = new ConnectionController.Profile(host.host, host.port, host.user,
                                selected == null ? supplied : "", selectedFolder, selected, selected == null ? "" : supplied, host.id);
                        passwordFallbackHost = host; passwordFallbackFolder = selectedFolder;
                        passwordFallbackAllowed = selected != null && passwordAllowed;
                        clearRemoteCookies(() -> beginConnection(profile, reconnect));
                    }).setOnCancelListener(dialog -> secret.setText("")).show();
        } catch (Exception error) { Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    private void disconnectToLocal() {
        cancelSshPrompt(); passwordFallbackAllowed = false;
        if (connection.current() != null || connection.isBusy()) setSshPhase("disconnecting", "Closing the SSH connection…");
        connection.disconnect(); clearRemoteCookies(() -> { });
        sshProfileId = ""; sshConnectionId = ""; setSshPhase("disconnected", "Disconnected");
        if (web != null && web != localWeb) { destroyWorkbench(web); web = null; }
        openLocalWorkspace();
    }

    private void destroyWorkbench(WebView target) {
        if (fileTransfers != null) fileTransfers.invalidate(target);
        if (pendingSshPrompt != null && pendingSshPrompt.source == target) cancelSshPrompt();
        NativeSshBridge bridge = sshBridges.remove(target);
        if (bridge != null) bridge.close();
        android.view.ViewParent parent = target.getParent();
        if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(target);
        target.stopLoading();
        target.destroy();
    }

    private boolean requestSshPrompt(JSONObject prompt, Consumer<JSONObject> callback) {
        NativeSshBridge bridge = web == null ? null : sshBridges.get(web);
        if (bridge == null || !bridge.isAttached() || formOpen || !web.isShown() || !hasWindowFocus()) return false;
        cancelSshPrompt();
        try {
            PendingSshPrompt pending = new PendingSshPrompt();
            pending.id = java.util.UUID.randomUUID().toString(); pending.connectionId = sshConnectionId;
            pending.source = web; pending.callback = callback;
            pending.timeout = () -> {
                if (pendingSshPrompt != pending) return;
                pendingSshPrompt = null;
                try { callback.accept(new JSONObject().put("cancelled", true)); } catch (Exception ignored) { }
            };
            pendingSshPrompt = pending;
            prompt.put("id", pending.id).put("profileId", sshProfileId);
            bridge.publishPrompt(prompt); root.postDelayed(pending.timeout, 120000); return true;
        } catch (Exception error) { cancelSshPrompt(); return false; }
    }

    private void cancelSshPrompt() {
        PendingSshPrompt pending = pendingSshPrompt;
        pendingSshPrompt = null;
        if (pending == null) return;
        root.removeCallbacks(pending.timeout);
        try { pending.callback.accept(new JSONObject().put("cancelled", true)); } catch (Exception ignored) { }
    }

    private boolean promptDesktopAuthentication(SavedHostStore.Host host, Uri identity, boolean reconnect, String folder, boolean passwordAllowed) throws Exception {
        String previousId = sshConnectionId;
        sshConnectionId = java.util.UUID.randomUUID().toString();
        sshProfileId = host.id;
        JSONObject prompt = new JSONObject().put("kind", identity == null ? "password" : "passphrase")
                .put("title", "Connect to " + host.label).put("host", host.host).put("port", host.port).put("user", host.user)
                .put("message", identity == null ? "SSH password for " + host.user + "@" + host.host : "SSH key passphrase (leave empty for an unencrypted key)")
                .put("detail", "This credential is used for this connection only and is never saved.");
        boolean displayed = requestSshPrompt(prompt, response -> {
            if (response.optBoolean("cancelled")) { sshConnectionId = previousId; restoreSshConnectionState(); return; }
            String secret = response.optString("value", "");
            ConnectionController.Profile profile = new ConnectionController.Profile(host.host, host.port, host.user,
                    identity == null ? secret : "", folder, identity, identity == null ? "" : secret, host.id);
            passwordFallbackHost = host; passwordFallbackFolder = folder; passwordFallbackAllowed = identity != null && passwordAllowed;
            clearRemoteCookies(() -> beginConnection(profile, reconnect));
        });
        if (displayed) setSshPhase("authenticating", "Waiting for SSH credentials for " + host.label + "…");
        else { sshConnectionId = previousId; restoreSshConnectionState(); }
        return displayed;
    }

    private void restoreSshConnectionState() {
        ConnectionController.Ready current = connection.current();
        if (current != null) { sshProfileId = current.savedHostId; setSshPhase("connected", "Connected to " + current.host); }
        else { sshProfileId = ""; sshConnectionId = ""; setSshPhase("disconnected", "Connection cancelled"); }
    }

    private void showNotices() {
        try (java.io.InputStream input = getAssets().open("third-party-notices.txt")) {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            String provenance;
            if (bundled != null) provenance = bundled.provenance();
            else {
                try (java.io.InputStream pin = getAssets().open("workbench/bundle.json")) {
                    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                    while ((count = pin.read(buffer)) != -1) bytes.write(buffer, 0, count);
                    org.json.JSONObject metadata = new org.json.JSONObject(new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
                    provenance = "Bundled Code OSS " + metadata.optString("codeVersion") + "\nSource commit: " + metadata.optString("upstreamCommit");
                } catch (Exception missing) { provenance = "Bundled editor metadata unavailable"; }
            }
            TextView content = text("vstab " + BuildConfig.VERSION_NAME + "\n\n" + provenance + "\n\n" + new String(output.toByteArray(), java.nio.charset.StandardCharsets.UTF_8), 13, INK);
            content.setPadding(dp(24), dp(12), dp(24), dp(12));
            content.setTextIsSelectable(true);
            ScrollView scroll = new ScrollView(this); scroll.addView(content);
            new AlertDialog.Builder(this).setTitle("About vstab").setIcon(R.drawable.ic_vstab).setView(scroll).setPositiveButton("Close", null).show();
        } catch (java.io.IOException error) { Toast.makeText(this, "Notices could not be loaded.", Toast.LENGTH_LONG).show(); }
    }

    @Override public void onStatus(String message, boolean busy) {
        sshMessage = message; publishSshState();
        if (active != null) return;
        if (status != null) status.setText(message);
        if (connect != null) { connect.setEnabled(!busy); connect.setText(busy ? "Connecting…" : "Connect and open workspace"); }
        if (progress != null) progress.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    @Override public void onHostKey(String remote, String algorithm, String fingerprint, Consumer<Boolean> answer) {
        try {
            JSONObject prompt = new JSONObject().put("kind", "host-key").put("title", "Verify SSH host")
                    .put("message", "Continue connecting to " + remote + "?")
                    .put("detail", "Compare this SHA-256 fingerprint against the server before trusting this host.")
                    .put("host", remote).put("algorithm", algorithm).put("fingerprint", fingerprint);
            if (requestSshPrompt(prompt, response -> answer.accept(!response.optBoolean("cancelled") && response.optBoolean("accepted")))) return;
        } catch (Exception ignored) { }
        new AlertDialog.Builder(this).setTitle("Verify SSH host")
                .setMessage(remote + "\n\n" + algorithm + "\n" + fingerprint + "\n\nConfirm this fingerprint with your server administrator before trusting this host.")
                .setNegativeButton("Cancel", (dialog, which) -> answer.accept(false))
                .setPositiveButton("Trust and connect", (dialog, which) -> answer.accept(true))
                .setOnCancelListener(dialog -> answer.accept(false)).show();
    }

    @Override public void onConnectionPhase(String phase, String message) { setSshPhase(phase, message); }

    @Override public void onReady(ConnectionController.Ready ready) {
        passwordFallbackAllowed = false;
        sshProfileId = ready.savedHostId;
        if (sshConnectionId.isEmpty()) sshConnectionId = java.util.UUID.randomUUID().toString();
        try { if (savedHosts != null && !ready.savedHostId.isEmpty()) savedHosts.markConnected(ready.savedHostId, ready.folder); }
        catch (Exception error) { Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show(); }
        setSshPhase("connected", "Connected to " + ready.host); openWorkbench(ready);
    }
    @Override public void onFailure(String message) {
        setSshPhase("error", message); onStatus(message, false);
        if (passwordFallbackAllowed && message.contains("SSH authentication failed") && passwordFallbackHost != null) {
            passwordFallbackAllowed = false;
            try { if (promptDesktopAuthentication(passwordFallbackHost, null, false, passwordFallbackFolder, false)) return; }
            catch (Exception ignored) { }
        }
        if (!formOpen) Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override public void onLocalRuntimeStatus(String message) {
        if (active == null && !formOpen && runtimeStatus != null) runtimeStatus.setText(message);
    }
    @Override public void onLocalRuntimeReady(ConnectionController.Ready ready) {
        if (!openingDocument && !formOpen && (active == null || active.localRuntime)) {
            openWorkbench(ready);
            if (firstConnectRequested) { firstConnectRequested = false; root.post(this::showConnect); }
        }
    }
    @Override public void onLocalRuntimeFailure(String message) {
        boolean showingLocal = active == null || active.localRuntime;
        if (localWeb != null) { destroyWorkbench(localWeb); if (web == localWeb) web = null; }
        localWeb = null; localSurface = null; cachedLocalReady = null; localBundle = null;
        if (!formOpen && showingLocal) showRuntimeState(message, true);
    }

    // API33+ uses the native predictive-back callback registered in onCreate.
    // The override is solely the compatibility path for Android29–32.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() { handleBack(); }

    private void handleBack() {
        if (formOpen) {
            if (connection.isBusy()) disconnectToLocal(); else openWorkbench(null);
        } else if (active != null && !active.localRuntime) confirmDisconnect(); else finish();
    }

    @Override protected void onDestroy() {
        cancelSshPrompt();
        if (fileTransfers != null) fileTransfers.close();
        if (connection != null) connection.unobserve(this);
        if (runtime != null) runtime.unobserve(this);
        if (configObserver != null) configObserver.stopWatching();
        for (NativeSshBridge bridge : sshBridges.values()) bridge.close();
        sshBridges.clear();
        keyWorker.shutdown();
        if (web != null && web != localWeb) destroyWorkbench(web);
        if (localWeb != null) destroyWorkbench(localWeb);
        if (documents != null) for (SharedDocumentManager.Listener listener : documentListeners) documents.removeListener(listener);
        web = null; localWeb = null;
        if (isFinishing() && windowId != null) ((VstabApplication) getApplication()).closeWindow(windowId);
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("workspace-window-id", windowId);
        state.putString("local-folder", localFolderPath);
        state.putString("opened-document", openedDocumentPath);
        state.putString("shared-session-id", sharedSessionId);
        super.onSaveInstanceState(state);
    }

    @Override protected void onPause() {
        cancelSshPrompt();
        super.onPause();
    }

    private TextView label(String value) {
        TextView label = text(value, 13, INK);
        label.setTypeface(null, Typeface.BOLD);
        label.setPadding(0, 0, 0, dp(4));
        return label;
    }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size); view.setTextColor(color);
        return view;
    }
    private EditText input(String hint, int type, int id) {
        EditText input = new EditText(this);
        input.setId(id); input.setHint(hint); input.setInputType(type);
        input.setTextSize(16); input.setTextColor(INK); input.setHintTextColor(SECONDARY);
        input.setSingleLine(true); input.setMinHeight(dp(48));
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable outline = new GradientDrawable();
        outline.setColor(Color.WHITE); outline.setCornerRadius(dp(6)); outline.setStroke(dp(1), Color.rgb(156, 169, 186));
        input.setBackground(outline);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        return input;
    }
    private Button button(String value, boolean primary) {
        Button button = new Button(this);
        button.setText(value); button.setAllCaps(false); button.setTextSize(14);
        button.setMinHeight(dp(48)); button.setMinimumWidth(dp(48));
        button.setTextColor(primary ? Color.WHITE : BLUE);
        if (primary) {
            button.setBackgroundTintList(ColorStateList.valueOf(BLUE));
            button.setTypeface(null, Typeface.BOLD);
        } else button.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        return button;
    }
    private LinearLayout.LayoutParams width(int width) { return new LinearLayout.LayoutParams(width, -2); }
    private LinearLayout.LayoutParams spaced(int top) {
        LinearLayout.LayoutParams params = width(-1); params.topMargin = dp(top); return params;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
