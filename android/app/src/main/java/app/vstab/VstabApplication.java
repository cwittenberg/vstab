package app.vstab;

import android.app.Application;

public final class VstabApplication extends Application {
    private final WorkspaceWindows windows = new WorkspaceWindows();
    private final java.util.Map<String, ConnectionController> controllers = new java.util.HashMap<>();
    private final java.util.Map<String, BundledWorkbench> workbenches = new java.util.HashMap<>();
    private LocalRuntimeManager runtime;
    private SharedDocumentManager documents;

    @Override public void onCreate() {
        super.onCreate();
        runtime = new LocalRuntimeManager(this);
        android.webkit.ServiceWorkerController.getInstance().setServiceWorkerClient(new android.webkit.ServiceWorkerClient() {
            @Override public android.webkit.WebResourceResponse shouldInterceptRequest(android.webkit.WebResourceRequest request) {
                final java.util.List<BundledWorkbench> candidates;
                synchronized (workbenches) { candidates = new java.util.ArrayList<>(workbenches.values()); }
                for (BundledWorkbench client : candidates) {
                    android.webkit.WebResourceResponse response = client.intercept(request);
                    if (response != null) return response;
                }
                return null;
            }
        });
    }

    synchronized ConnectionController connection() { return connection("main"); }
    synchronized ConnectionController connection(String id) {
        ConnectionController controller = controllers.get(id);
        if (controller == null) {
            controller = new ConnectionController(this, WorkspaceWindows.loopbackHost(windows.acquire(id)));
            controllers.put(id, controller);
        }
        return controller;
    }
    synchronized void closeWindow(String id) {
        ConnectionController controller = controllers.remove(id);
        if (controller != null) controller.close();
        windows.release(id);
        synchronized (workbenches) { workbenches.remove(id); workbenches.remove(id + ":local"); }
    }
    void registerWorkbench(String id, BundledWorkbench client) {
        synchronized (workbenches) { workbenches.put(id, client); }
    }
    LocalRuntimeManager runtime() { return runtime; }
    synchronized SharedDocumentManager documents() {
        if (documents == null) documents = new SharedDocumentManager(this);
        return documents;
    }
}
