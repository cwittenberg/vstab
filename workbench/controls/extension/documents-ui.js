'use strict';

function registerDocumentCommands(vscode, context, native, beforeNavigate = async () => true) {
  const remoteUri = path => vscode.Uri.from({scheme: 'vscode-remote', authority: 'remote', path});
  const guarded = callback => async (...args) => {
    try { return await callback(...args); }
    catch (error) { if (error.code !== 'Cancelled') await vscode.window.showErrorMessage(error.message); }
  };
  const register = (name, action) => context.subscriptions.push(vscode.commands.registerCommand(name, guarded(action)));
  for (const [command, method] of [['vstab.documents.openFolder', 'documents.pickFolder'], ['vstab.documents.openFile', 'documents.pickFile']]) {
    register(command, async () => {
      const state = await native.request(method);
      if (!state.folderPath) return;
      if (!state.local) {
        if (await beforeNavigate()) await native.request('documents.openApproved');
        return;
      }
      const root = vscode.workspace.workspaceFolders?.[0]?.uri;
      if (root?.path === state.folderPath && state.filePath) await vscode.window.showTextDocument(remoteUri(state.filePath));
      else await vscode.commands.executeCommand('vscode.openFolder', remoteUri(state.folderPath), {forceNewWindow: false});
    });
  }
  register('vstab.window.new', () => native.request('window.new'));
  register('vstab.documents.sync', () => native.request('documents.sync'));
  register('vstab.documents.review', async () => {
    const state = await native.request('documents.getState');
    if (!state.local || state.folderPath !== vscode.workspace.workspaceFolders?.[0]?.uri.path) {
      if (!state.folderPath) { await vscode.window.showInformationMessage('Open a shared file or folder first.'); return; }
      const action = await vscode.window.showInformationMessage('Open the shared project on Android to review its files.', 'Open shared project');
      if (action && await beforeNavigate()) await native.request('documents.openApproved');
      return;
    }
    if (state.error) await vscode.window.showWarningMessage(state.error);
    const choices = (state.conflicts || []).map(conflict => ({label: conflict.path, conflict}));
    if (!choices.length) { await vscode.window.showInformationMessage(state.message || 'No shared-file conflicts.'); return; }
    const chosen = await vscode.window.showQuickPick(choices, {title: 'Shared-file conflicts', placeHolder: 'Choose a file to compare'});
    if (!chosen) return;
    if (chosen.conflict.recoveryPath) await vscode.commands.executeCommand('vscode.diff', remoteUri(state.folderPath + '/' + chosen.conflict.path),
      remoteUri(state.folderPath + '/' + chosen.conflict.recoveryPath), chosen.label + ' — app / device');
    else await vscode.window.showInformationMessage('One side deleted this file. Choose which version to keep, or cancel to leave both sides unchanged.');
    const decision = await vscode.window.showWarningMessage('Resolve ' + chosen.label + '? Both versions are retained until you choose.',
      'Keep app version', 'Keep device version');
    if (decision) await native.request('documents.resolve', {path: chosen.conflict.path, keepLocal: decision === 'Keep app version'});
  });
  const status = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 35);
  status.command = 'vstab.documents.review';
  context.subscriptions.push(status);
  let lastAlert = '', lastOpened = '', checking = false;
  async function check() {
    if (checking) return;
    checking = true;
    try {
      const state = await native.request('documents.getState');
      const folder = vscode.workspace.workspaceFolders?.[0]?.uri.path;
      if (!state.local || folder !== state.folderPath) { status.hide(); return; }
      const conflicts = state.conflicts?.length || 0;
      status.text = conflicts ? '$(warning) Shared conflicts: ' + conflicts : state.error ? '$(warning) Shared save pending' : '$(sync) Shared: ' + state.name;
      status.tooltip = state.error || state.message; status.show();
      if (state.error && state.error !== lastAlert) {
        lastAlert = state.error;
        void vscode.window.showWarningMessage(state.error, 'Review shared files').then(action => {
          if (action) void vscode.commands.executeCommand('vstab.documents.review');
        });
      }
      const file = state.openedFile || state.filePath;
      if (file && lastOpened !== file) { lastOpened = file; await vscode.window.showTextDocument(remoteUri(file)); }
    } catch { /* Initial activation can precede the native handshake. */ }
    finally { checking = false; }
  }
  const timer = setInterval(check, 3000);
  context.subscriptions.push({dispose() { clearInterval(timer); }});
  void check();
}
exports.registerDocumentCommands = registerDocumentCommands;
