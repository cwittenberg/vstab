'use strict';
const vscode = require('vscode');
const path = require('node:path');
const os = require('node:os');
const crypto = require('node:crypto');
const fs = require('node:fs');
const {SessionClient} = require('./client.cjs');
const {validName} = require('./session-core.cjs');
let disposeExtension;

function activate(context) {
  const workspace = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath || os.homedir();
  const client = new SessionClient({workspace, ptyModule: path.join(vscode.env.appRoot, 'node_modules/node-pty')});
  const views = new Map();
  const terminalIds = new WeakMap();
  const rememberedIds = new Set(context.workspaceState.get('attachedSessionIds', []));
  const changed = new vscode.EventEmitter();
  let sessions = [], disposed = false, retry;
  const channel = vscode.window.createOutputChannel('vstab Persistent Terminals');
  const remember = () => { if (!disposed) return context.workspaceState.update('attachedSessionIds', [...rememberedIds]); };
  const notify = error => {
    if (disposed) return;
    channel.appendLine(new Date().toISOString() + ' ' + error.message);
    void vscode.window.showErrorMessage('Persistent terminal: ' + error.message, 'Show Log').then(action => { if (action) channel.show(); });
  };
  async function refresh() {
    sessions = await client.request('list'); changed.fire(); return sessions;
  }
  function createView(session) {
    const existing = views.get(session.id);
    if (existing) { existing.terminal.show(); return existing.terminal; }
    const write = new vscode.EventEmitter(), close = new vscode.EventEmitter(), nameChanged = new vscode.EventEmitter();
    const pty = {
      onDidWrite: write.event, onDidClose: close.event, onDidChangeName: nameChanged.event,
      async open(size) {
        try {
          await client.request('attach', {sessionId: session.id});
          if (size) await client.request('resize', {sessionId: session.id,
            cols: Math.max(2, Math.min(500, size.columns)), rows: Math.max(1, Math.min(200, size.rows))});
        } catch (error) { write.fire('\r\n' + error.message + '\r\n'); notify(error); }
      },
      close() {
        if (views.get(session.id)?.pty === pty) {
          views.delete(session.id); changed.fire();
          if (!disposed) void client.request('detach', {sessionId: session.id}).catch(() => {});
        }
        write.dispose(); close.dispose(); nameChanged.dispose();
      },
      handleInput(data) {
        // Never retry input. An acknowledgement lost during reconnect is ambiguous.
        for (let offset = 0; offset < data.length;) {
          let end = Math.min(offset + 4096, data.length);
          if (end < data.length && /[\uD800-\uDBFF]/.test(data[end - 1])) end--;
          const chunk = data.slice(offset, end); offset = end;
          void client.request('input', {sessionId: session.id, inputId: crypto.randomUUID(), data: chunk}).catch(notify);
        }
      },
      setDimensions(size) {
        void client.request('resize', {sessionId: session.id,
          cols: Math.max(2, Math.min(500, size.columns)), rows: Math.max(1, Math.min(200, size.rows))}).catch(() => {});
      }
    };
    const terminal = vscode.window.createTerminal({name: session.name, pty, iconPath: new vscode.ThemeIcon('terminal')});
    views.set(session.id, {terminal, write, close, nameChanged, pty});
    terminalIds.set(terminal, session.id); rememberedIds.add(session.id);
    void remember(); changed.fire(); return terminal;
  }
  client.on('data', event => views.get(event.sessionId)?.write.fire(event.data));
  client.on('exit', event => {
    views.get(event.sessionId)?.write.fire('\r\n[Session exited with code ' + event.exitCode + '.]\r\n');
    void refresh().catch(() => {});
  });
  client.on('stopped', event => {
    const view = views.get(event.sessionId); if (view) { view.close.fire(0); view.terminal.dispose(); }
    views.delete(event.sessionId); rememberedIds.delete(event.sessionId); void remember();
  });
  client.on('changed', () => { void refresh().catch(() => {}); });
  async function reconnect(attempt = 0) {
    if (disposed || !views.size) return;
    try {
      await client.connect();
      const live = await refresh();
      for (const [id, view] of views) {
        if (!live.some(s => s.id === id)) {
          view.write.fire('\r\n[This session process ended. No commands have been rerun. Create a new session to continue.]\r\n');
          continue;
        }
        view.write.fire('\x1b[2J\x1b[H');
        await client.request('attach', {sessionId: id});
      }
    } catch (error) {
      if (attempt < 7) retry = setTimeout(() => void reconnect(attempt + 1), Math.min(10000, 500 * 2 ** attempt));
      else notify(error);
    }
  }
  client.on('disconnected', () => {
    if (disposed) return;
    for (const view of views.values()) view.write.fire('\r\n[Reconnecting to the existing session…]\r\n');
    clearTimeout(retry); retry = setTimeout(() => void reconnect(), 500);
  });
  context.subscriptions.push(vscode.window.onDidCloseTerminal(terminal => {
    const id = terminalIds.get(terminal);
    if (!id || disposed || (views.has(id) && views.get(id).terminal !== terminal)) return;
    // Shutdown is different from an intentional detach: retain its restoration record.
    const reason = terminal.exitStatus?.reason;
    if (reason === vscode.TerminalExitReason.User || reason === vscode.TerminalExitReason.Extension) {
      rememberedIds.delete(id); void remember();
    }
  }));
  const commands = {
    async create() {
      if (!vscode.workspace.isTrusted) {
        await vscode.window.showWarningMessage('Trust this workspace before creating a persistent shell.'); return;
      }
      const name = await vscode.window.showInputBox({title: 'New Persistent Terminal', prompt: 'Name this project session. Closing its tab detaches; Stop Persistent Terminal ends it.',
        value: 'Project shell', validateInput(value) { try { validName(value); } catch (error) { return error.message; } }});
      if (name === undefined) return;
      const session = await client.request('create', {id: crypto.randomUUID(), name, cols: 100, rows: 30});
      createView(session).show(); await refresh(); return session.id;
    },
    async attach(item) {
      const all = await refresh();
      const session = all.find(s => s.id === item?.id) || await vscode.window.showQuickPick(all.map(s => ({label: s.name,
        description: s.exited ? 'Exited · transcript available' : 'Running · PID ' + s.pid,
        detail: s.cwd, session: s})), {title: 'Attach to Persistent Terminal', placeHolder: all.length ? 'Choose a session' : 'No sessions yet. Run New Persistent Terminal.'});
      if (session) createView(session.session || session).show();
    },
    async rename(item) {
      const all = await refresh();
      const picked = all.find(s => s.id === item?.id) || await vscode.window.showQuickPick(all.map(s => ({label: s.name, session: s})), {title: 'Rename Persistent Terminal'});
      if (!picked) return;
      const session = picked.session || picked;
      const name = await vscode.window.showInputBox({title: 'Rename Persistent Terminal', value: session.name,
        validateInput(value) { try { validName(value); } catch (error) { return error.message; } }});
      if (name === undefined) return;
      await client.request('rename', {sessionId: session.id, name});
      views.get(session.id)?.nameChanged.fire(name);
      await refresh();
    },
    async stop(item) {
      const all = await refresh();
      const picked = all.find(s => s.id === item?.id) || await vscode.window.showQuickPick(all.map(s => ({label: s.name, session: s})), {title: 'Stop Persistent Terminal'});
      if (!picked) return;
      const session = picked.session || picked;
      const choice = await vscode.window.showWarningMessage('Stop “' + session.name + '” and its running shell?', {modal: true}, 'Stop Session');
      if (choice === 'Stop Session') { await client.request('stop', {sessionId: session.id}); await refresh(); }
    },
    refresh
  };
  for (const [name, run] of Object.entries(commands)) {
    context.subscriptions.push(vscode.commands.registerCommand('vstab.sessions.' + name, async (...args) => {
      try { return await run(...args); } catch (error) { notify(error); }
    }));
  }
  context.subscriptions.push(vscode.window.registerTreeDataProvider('vstab.persistentTerminals', {
    onDidChangeTreeData: changed.event,
    getChildren(element) { return element ? [] : sessions; },
    getTreeItem(session) {
      const item = new vscode.TreeItem(session.name, vscode.TreeItemCollapsibleState.None);
      item.id = session.id; item.description = session.exited ? 'Exited' : (views.has(session.id) ? 'Attached' : 'Detached');
      item.tooltip = session.cwd + '\n' + (session.exited ? 'Transcript retained until stopped' : 'PID ' + session.pid + ' · close tab to detach');
      item.iconPath = new vscode.ThemeIcon(session.exited ? 'terminal-decoration-error' : 'terminal');
      item.contextValue = 'vstabPersistentSession'; item.command = {command: 'vstab.sessions.attach', title: 'Attach', arguments: [session]}; return item;
    }
  }));
  const remembered = [...rememberedIds];
  // Do not start another daemon merely because an unused workspace was opened.
  if (remembered.length || fs.existsSync(client.socketPath)) {
    void refresh().then(all => {
      const found = all.filter(s => remembered.includes(s.id));
      for (const session of found) createView(session);
      rememberedIds.clear(); for (const session of found) rememberedIds.add(session.id);
      if (found.length < remembered.length) void vscode.window.showInformationMessage('Some persistent terminal processes ended. Their commands have not been rerun.');
      void remember();
    }).catch(notify);
  }
  disposeExtension = () => { if (disposed) return; disposed = true; clearTimeout(retry); client.close(); changed.dispose(); channel.dispose(); };
  context.subscriptions.push({dispose: disposeExtension});
}
exports.activate = activate;
exports.deactivate = () => { disposeExtension?.(); };
