'use strict';
const vscode = require('vscode');
const {NativeClient} = require('./native-client.js');
const CONFIG = vscode.Uri.parse('vstab-ssh-config:/config');
const busy = new Set(['connecting', 'verifying-host', 'authenticating', 'preparing', 'reconnecting', 'disconnecting']);

function activate(context) {
  const native = new NativeClient(context);
  context.subscriptions.push(vscode.commands.registerCommand('vstab.files.paste', async () => {
    try { await native.request('files.paste'); }
    catch (error) { await vscode.window.showErrorMessage(error.message || 'Android file paste failed.'); }
  }));
  context.subscriptions.push(native.onEvent(event => {
    if (event.event !== 'filesNotice' || typeof event.message !== 'string') return;
    if (event.kind === 'error') void vscode.window.showErrorMessage(event.message);
    else void vscode.window.showInformationMessage(event.message);
  }));
  require('../extension/documents-ui.js').registerDocumentCommands(vscode, context, native, closeDirtyEditors);
  const remoteWindow = /^127\.0\.0\.[1-4](?::|$)/.test(context.extensionUri.authority);
  void vscode.commands.executeCommand('setContext', 'vstab.ssh.remoteWindow', remoteWindow);
  const changed = new vscode.EventEmitter();
  const fileChanged = new vscode.EventEmitter();
  const diagnostics = vscode.languages.createDiagnosticCollection('vstab-ssh-config');
  const status = vscode.window.createStatusBarItem('vstab.ssh.connection', vscode.StatusBarAlignment.Left, 99);
  status.command = 'vstab.ssh.showHosts';
  let state = {profiles: [], connection: {phase: 'disconnected'}};
  let signature = '', initial, lastReadRevision, configRevision, disposed = false, syncingFolder = false, synchronizedFolder = '';
  const prompts = new Set();
  const profileFor = value => typeof value === 'string' ? state.profiles.find(p => p.id === value) : value?.profile || (value?.id ? value : undefined);
  const activeFor = profile => state.connection.phase === 'connected' && state.connection.profileId === profile.id;
  const detail = profile => `${profile.user || 'user'}@${profile.hostname}:${profile.port || 22}`;
  const showError = error => vscode.window.showErrorMessage(error.message || String(error));
  const run = action => async (...args) => { try { return await action(...args); } catch (error) { await showError(error); } };
  function setDiagnostics(snapshot) {
    diagnostics.set(CONFIG, (snapshot?.diagnostics || []).map(issue => new vscode.Diagnostic(
      new vscode.Range(Math.max(0, issue.line || 0), 0, Math.max(0, issue.line || 0), 200), issue.message,
      issue.severity === 'warning' ? vscode.DiagnosticSeverity.Warning : vscode.DiagnosticSeverity.Error)));
  }
  function update(next) {
    if (!next || !Array.isArray(next.profiles) || !next.connection) return;
    const nextSignature = JSON.stringify(next);
    if (nextSignature === signature) return;
    signature = nextSignature; state = next; setDiagnostics(next.config);
    void vscode.commands.executeCommand('setContext', 'vstab.ssh.connected', next.connection.phase === 'connected');
    const connection = next.connection;
    const profile = next.profiles.find(p => p.id === connection.profileId);
    const alias = profile?.alias || connection.profileId || 'host';
    tree.message = connection.phase === 'error' ? connection.message : busy.has(connection.phase) ? connection.message || `Connecting to ${alias}…` : undefined;
    status.color = undefined; status.backgroundColor = undefined;
    if (busy.has(connection.phase)) { status.text = `$(sync~spin) SSH: ${alias}`; status.show(); }
    else if (connection.phase === 'error') {
      status.text = '$(warning) SSH disconnected'; status.backgroundColor = new vscode.ThemeColor('statusBarItem.warningBackground'); status.show();
    } else if (connection.phase === 'connected') {
      status.text = '$(circle-filled)'; status.color = new vscode.ThemeColor('testing.iconPassed'); status.show();
    } else status.hide();
    status.tooltip = connection.message || (connection.phase === 'connected' ? `SSH: ${alias} · Connected` : 'Remote SSH');
    changed.fire();
    synchronizeWorkspaceFolder();
  }
  function synchronizeWorkspaceFolder() {
    if (!remoteWindow) return;
    const folder = vscode.workspace.workspaceFolders?.[0]?.uri;
    const path = folder?.scheme === 'vscode-remote' ? folder.path : (!folder && remoteWindow ? '' : undefined);
    if (syncingFolder || disposed || state.connection.phase !== 'connected' ||
        path === undefined || path === state.connection.folder) return;
    const key = `${state.connection.connectionId}:${path}`;
    if (key === synchronizedFolder) return;
    synchronizedFolder = key;
    syncingFolder = true;
    void native.request('ssh.recordWorkspace', {profileId: state.connection.profileId,
      connectionId: state.connection.connectionId, path})
      .then(() => native.request('ssh.getState')).then(update).catch(showError)
      .finally(() => { syncingFolder = false; });
  }
  async function refresh() { update(await native.request('ssh.getState')); changed.fire(); }
  const tree = vscode.window.createTreeView('vstab.sshTargets', {treeDataProvider: {
    onDidChangeTreeData: changed.event,
    async getChildren(node) {
      if (node) return [];
      await initial;
      return state.profiles.map(profile => ({profile}));
    },
    getTreeItem(node) {
      const profile = node.profile;
      const active = state.connection.profileId === profile.id, phase = active ? state.connection.phase : 'disconnected';
      const item = new vscode.TreeItem(profile.alias || profile.hostname, vscode.TreeItemCollapsibleState.None);
      item.id = `host:${profile.id}`;
      item.description = phase === 'connected' ? 'Connected' : phase === 'error' ? 'Connection failed' : busy.has(phase) ? phase.replaceAll('-', ' ') : `Disconnected · ${detail(profile)}`;
      item.tooltip = `${profile.alias}\n${detail(profile)}\n${active ? state.connection.message || phase : 'Disconnected'}\n${profile.hasIdentity ? 'Private key configured' : 'Password or configured SSH authentication'}`;
      item.iconPath = new vscode.ThemeIcon(busy.has(phase) ? 'sync~spin' : phase === 'error' || profile.connectable === false ? 'warning' : 'vm',
        phase === 'connected' ? new vscode.ThemeColor('testing.iconPassed') : undefined);
      item.contextValue = phase === 'connected' ? 'sshHostConnected' : phase === 'error' ? 'sshHostError' : 'sshHost';
      return item;
    }
  }});
  function configPath(uri) {
    if (uri.scheme !== CONFIG.scheme || uri.path !== '/config' || uri.authority || uri.query || uri.fragment)
      throw vscode.FileSystemError.NoPermissions('Only the SSH configuration file is available here.');
  }
  async function readConfig() { const result = await native.request('ssh.readConfig'); setDiagnostics(result); configRevision = result.revision; return result; }
  context.subscriptions.push(vscode.workspace.registerFileSystemProvider(CONFIG.scheme, {
    onDidChangeFile: fileChanged.event,
    watch(uri) { configPath(uri); return new vscode.Disposable(() => {}); },
    async stat(uri) { configPath(uri); const result = await readConfig(); return {type: vscode.FileType.File, ctime: 0, mtime: result.mtime, size: result.size}; },
    async readFile(uri) { configPath(uri); const result = await readConfig(); lastReadRevision = result.revision; return new TextEncoder().encode(result.content); },
    async writeFile(uri, bytes) {
      configPath(uri);
      if (bytes.byteLength > 256 * 1024) throw vscode.FileSystemError.Unavailable('SSH configuration is limited to 256 KiB.');
      const content = new TextDecoder('utf-8', {fatal: true}).decode(bytes);
      const result = await native.request('ssh.writeConfig', {content, expectedRevision: lastReadRevision});
      lastReadRevision = result.revision; configRevision = result.revision; setDiagnostics(result);
      fileChanged.fire([{type: vscode.FileChangeType.Changed, uri: CONFIG}]); await refresh();
    },
    readDirectory() { throw vscode.FileSystemError.NoPermissions(); },
    createDirectory() { throw vscode.FileSystemError.NoPermissions(); },
    delete() { throw vscode.FileSystemError.NoPermissions('Remove hosts through Remote Explorer or edit the configuration.'); },
    rename() { throw vscode.FileSystemError.NoPermissions(); }
  }, {isCaseSensitive: true}));
  async function chooseHost(value) {
    const selected = profileFor(value); if (selected) return selected;
    await refresh();
    const items = state.profiles.map(profile => ({label: `$(vm) ${profile.alias}`, description: detail(profile), profile}));
    items.push({label: 'Configure', kind: vscode.QuickPickItemKind.Separator}, {label: '$(add) Add New SSH Host...', action: 'add'}, {label: '$(gear) Open SSH Configuration File...', action: 'config'});
    const item = await vscode.window.showQuickPick(items, {title: 'Remote-SSH: Connect to Host', placeHolder: 'Select an SSH host', matchOnDescription: true});
    if (item?.action === 'add') await addHost(); if (item?.action === 'config') await openConfig(); return item?.profile;
  }
  async function closeDirtyEditors() {
    if (!vscode.workspace.textDocuments.some(document => document.isDirty)) return true;
    await vscode.commands.executeCommand('workbench.action.closeAllEditors');
    return !vscode.workspace.textDocuments.some(document => document.isDirty);
  }
  async function saveDirtyConfig() {
    const document = vscode.workspace.textDocuments.find(item => item.uri.toString() === CONFIG.toString());
    if (!document?.isDirty) return true;
    return await vscode.window.showWarningMessage('Save SSH configuration changes first?', {modal: true}, 'Save') === 'Save' && await document.save();
  }
  async function openConfig(value) {
    const document = await vscode.workspace.openTextDocument(CONFIG); await vscode.languages.setTextDocumentLanguage(document, 'ssh_config');
    const line = Math.max(0, profileFor(value)?.configLine || 0);
    await vscode.window.showTextDocument(document, {preview: false, selection: new vscode.Range(line, 0, line, 0)});
  }
  async function addHost() {
    if (!await saveDirtyConfig()) return;
    const command = await vscode.window.showInputBox({title: 'Remote-SSH: Add New SSH Host', prompt: 'Enter an SSH connection command', placeHolder: 'ssh -p 22 developer@hostname', ignoreFocusOut: true,
      validateInput: value => value.trim() ? undefined : 'Enter a hostname or SSH command.'});
    if (!command) return;
    const result = await native.request('ssh.addHost', {command}); update(result.state); await refresh();
    const choice = await vscode.window.showInformationMessage('SSH host added to ~/.ssh/config.', 'Connect', 'Open Config');
    if (choice === 'Connect') await connect(result.profileId); if (choice === 'Open Config') await openConfig(result.profileId);
  }
  async function connect(value) {
    const profile = await chooseHost(value); if (!profile) return;
    if (profile.connectable === false) { await openConfig(profile); throw new Error(profile.diagnostics?.map(item => item.message).join('\n') || 'Fix this host’s SSH configuration before connecting.'); }
    if (activeFor(profile)) return vscode.commands.executeCommand('workbench.view.explorer');
    if (await closeDirtyEditors()) await native.request('ssh.connect', {profileId: profile.id});
  }
  async function openStandardFolder(value) {
    const profile = profileFor(value);
    if (state.connection.phase === 'connected' && (!profile || activeFor(profile))) {
      // The same command used by VS Code's File menu and Explorer Open Folder.
      // Core owns the remote file dialog, filesystem, trust and workspace switch.
      return vscode.commands.executeCommand('workbench.action.files.openFolder');
    }
    const selected = await chooseHost(value);
    if (selected) await connect(selected);
  }
  async function answerPrompt(prompt) {
    if (!prompt?.id || prompts.has(prompt.id)) return; prompts.add(prompt.id);
    try {
      if (prompt.kind === 'host-key') {
        const choice = await vscode.window.showWarningMessage(prompt.message || `Verify the SSH host key for ${prompt.host}`, {modal: true,
          detail: [prompt.detail, prompt.algorithm, prompt.fingerprint].filter(Boolean).join('\n')}, 'Continue');
        await native.request('ssh.respondPrompt', {promptId: prompt.id, accepted: choice === 'Continue', cancelled: choice !== 'Continue'});
      } else if (prompt.kind === 'password' || prompt.kind === 'passphrase') {
        const value = await vscode.window.showInputBox({title: prompt.title || 'Remote-SSH Authentication', prompt: prompt.message, password: true, ignoreFocusOut: true});
        await native.request('ssh.respondPrompt', value === undefined ? {promptId: prompt.id, cancelled: true} : {promptId: prompt.id, value});
      }
    } catch (error) { if (!disposed && error.code !== 'SessionChanged') await showError(error); }
    finally { prompts.delete(prompt.id); }
  }
  const commands = {
    'vstab.ssh.connect': connect, 'vstab.ssh.addHost': addHost, 'vstab.ssh.openConfig': openConfig, 'vstab.ssh.editHost': openConfig,
    'vstab.ssh.refresh': refresh, 'vstab.ssh.showHosts': () => vscode.commands.executeCommand('workbench.view.extension.vstab-remote-explorer'),
    'vstab.ssh.browse': openStandardFolder,
    'vstab.ssh.importKey': async value => { const profile = await chooseHost(value); if (profile && await saveDirtyConfig()) await native.request('ssh.importKey', {profileId: profile.id}); },
    'vstab.ssh.removeHost': async value => { const profile = await chooseHost(value); if (!profile || !await saveDirtyConfig()) return;
      if (await vscode.window.showWarningMessage(`Remove SSH host “${profile.alias}” from ~/.ssh/config?`, {modal: true}, 'Remove') === 'Remove') { update(await native.request('ssh.removeHost', {profileId: profile.id})); await refresh(); } },
    'vstab.ssh.reconnect': async value => {
      const profile = await chooseHost(value), folder = vscode.workspace.workspaceFolders?.[0]?.uri;
      if (profile && await closeDirtyEditors()) await native.request('ssh.reconnect', {profileId: profile.id,
        ...(remoteWindow && folder?.scheme === 'vscode-remote' ? {path: folder.path} : {})});
    },
    'vstab.ssh.disconnect': async () => { if (await closeDirtyEditors()) await native.request('ssh.disconnect'); },
    'vstab.workspace.local': async () => { if (await closeDirtyEditors()) await native.request('workspace.local'); },
    'vstab.about': () => native.request('app.about')
  };
  for (const [id, action] of Object.entries(commands)) context.subscriptions.push(vscode.commands.registerCommand(id, run(action)));
  context.subscriptions.push(native.onEvent(event => {
    if (event.event === 'stateChanged') update(event.state);
    if (event.event === 'configChanged' && event.revision !== configRevision) { configRevision = event.revision; fileChanged.fire([{type: vscode.FileChangeType.Changed, uri: CONFIG}]); void refresh().catch(() => {}); }
    if (event.event === 'sshPrompt') void answerPrompt(event.prompt);
  }));
  context.subscriptions.push(vscode.workspace.onDidChangeWorkspaceFolders(synchronizeWorkspaceFolder));
  context.subscriptions.push(vscode.languages.registerCompletionItemProvider('ssh_config', {provideCompletionItems() {
    return ['Host', 'HostName', 'User', 'Port', 'IdentityFile', 'IdentitiesOnly', 'PreferredAuthentications', 'Include'].map(keyword => new vscode.CompletionItem(keyword, vscode.CompletionItemKind.Keyword));
  }}));
  initial = refresh().catch(error => { if (!signature) { tree.message = error.message; status.text = '$(warning) Remote SSH'; status.tooltip = error.message; status.show(); } });
  const timer = setInterval(() => { if (!disposed) void native.request('ssh.getState').then(update).catch(() => {}); }, 10000);
  context.subscriptions.push(native, tree, changed, fileChanged, status, diagnostics, new vscode.Disposable(() => { disposed = true; clearInterval(timer); }));
}
exports.activate = activate;
exports.deactivate = () => {};
