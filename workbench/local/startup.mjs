import { createAndroidNativeTransport, createLocalWorkbenchIntegration } from './bridge.mjs';

const transport = createAndroidNativeTransport();
const query = new URL(location.href).searchParams;
const integration = createLocalWorkbenchIntegration({
  extensionUri: new URL('./extension/', import.meta.url),
  requestNative: transport.request,
  workspacePath: query.get('vstabFolder') || '/workspace',
  async openWorkspace(workspace) {
    const next = new URL(location.href);
    next.searchParams.set('vstabFolder', workspace.folderUri.path);
    location.assign(next.href);
    return true;
  }
});
integration.options.additionalBuiltinExtensions.push({
  scheme: location.protocol.slice(0, -1), authority: location.host, path: '/controls/extension'
});
transport.onFilesChanged(changes => integration.filesChanged(changes));
globalThis.__vstabLocalOptions = integration.options;
globalThis.addEventListener('pagehide', () => { integration.dispose(); transport.dispose(); }, { once: true });

// Resolve the same versioned base that the upstream workbench HTML uses. The
// Android asset handler preserves this path so worker/resource URLs stay valid.
const baseElement = document.getElementById('vscode-workbench-web-base-url');
const baseValue = baseElement?.getAttribute('data-settings');
if (!baseValue) throw new Error('The bundled Code OSS base URL is missing.');
const baseUrl = new URL(baseValue.replace(/\/$/, '') + '/', location.href);
if (baseUrl.origin !== location.origin) throw new Error('The local Code OSS bundle must use the app origin.');
// This imports the real editor engine plus a narrow hook at its existing
// create() call. No editor, explorer or extension API is reimplemented.
await import(new URL('out/vs/code/browser/workbench/workbench.js', baseUrl).href);
