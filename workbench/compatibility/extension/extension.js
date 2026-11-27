'use strict';

const path = require('node:path');
const os = require('node:os');
const {redact, classify, ActivationLogParser, BoundedLogReader, fingerprint} = require('./diagnostics');

const NOTIFIED_KEY = 'notifiedActivationFailures.v1';
const DAY = 24 * 60 * 60 * 1000;

function createDiagnostics(vscode, context, options = {}) {
  const android = options.android ?? (process.platform === 'android' || !!process.env.VSTAB_NODE_PATH);
  const host = android ? 'Android device' : 'SSH host';
  const logRoot = options.logRoot || path.dirname(context.logUri.fsPath);
  const readers = ['remoteexthost.log', 'exthost.log'].map(name => ({
    reader: new BoundedLogReader(path.join(logRoot, name)), parser: new ActivationLogParser()
  }));
  const records = new Map();
  const output = vscode.window.createOutputChannel('vstab Extension Compatibility');
  const status = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, -50);
  status.command = 'vstab.compatibility.showReport';
  const notified = new Map((context.globalState.get(NOTIFIED_KEY, []) || [])
    .filter(pair => Array.isArray(pair) && pair.length === 2 && typeof pair[0] === 'string' &&
      Number.isFinite(pair[1]) && Date.now() - pair[1] < DAY));
  let disposed = false;
  let scanning;
  let foundLog = false;
  let scanError = '';
  let persistence = Promise.resolve();

  function report() {
    output.clear();
    output.appendLine('Recorded extension activation failures — ' + host);
    output.appendLine('');
    if (scanError) output.appendLine('Log checking could not finish: ' + scanError);
    if (!foundLog) output.appendLine('Waiting for this workspace\'s extension-host log.');
    else if (!records.size) output.appendLine('No activation failures found in this extension host\'s current log.');
    for (const record of records.values()) {
      output.appendLine(`${record.displayName} (${record.extensionId}) ${record.version}`);
      output.appendLine(record.reason.summary);
      if (record.reason.suggestRemote) {
        output.appendLine('Try the extension in an SSH workspace on a compatible Linux host.');
      }
      output.appendLine('Details (credentials removed):');
      output.appendLine(record.details);
      output.appendLine('');
    }
    output.appendLine('Only recorded activation errors are listed. An installed or inactive extension is not evidence of compatibility.');
    output.show(true);
  }

  function updateStatus() {
    if (!records.size) { status.hide(); return; }
    status.text = `$(warning) Extensions: ${records.size}`;
    status.tooltip = `${records.size} extension${records.size === 1 ? '' : 's'} could not start on this ${host.toLowerCase()}. Show the compatibility report.`;
    status.show();
  }

  function accept(raw) {
    const installed = vscode.extensions.getExtension(raw.extensionId);
    if (!installed || raw.extensionId === context.extension.id.toLowerCase()) return;
    const details = redact(raw.details, {home: os.homedir(), extensionPath: installed.extensionPath}).slice(0, 4096);
    const record = {
      extensionId: raw.extensionId,
      version: String(installed.packageJSON.version || 'unknown'),
      displayName: redact(installed.packageJSON.displayName || installed.packageJSON.name || raw.extensionId)
        .replace(/[\r\n]/g, ' ').slice(0, 100),
      details,
      reason: classify(details, android)
    };
    records.delete(record.extensionId);
    records.set(record.extensionId, record);
    if (records.size > 64) records.delete(records.keys().next().value);
    updateStatus();
    const key = fingerprint(record);
    if (notified.has(key)) return;
    notified.set(key, Date.now());
    while (notified.size > 256) notified.delete(notified.keys().next().value);
    persistence = persistence.then(() => context.globalState.update(NOTIFIED_KEY, [...notified])).catch(() => {});
    const actions = ['Show details'];
    if (record.reason.suggestRemote) actions.push('Connect to SSH Host…');
    void vscode.window.showWarningMessage(
      `${record.displayName} (${record.extensionId}) could not start on this ${host.toLowerCase()}. ${record.reason.summary}`,
      ...actions
    ).then(action => {
      if (disposed) return;
      if (action === 'Show details') report();
      if (action === 'Connect to SSH Host…') {
        return vscode.commands.executeCommand('vstab.ssh.connect');
      }
    }).catch(() => {});
  }

  async function performScan() {
    try {
      for (const [id, record] of records) {
        const installed = vscode.extensions.getExtension(id);
        if (!installed || String(installed.packageJSON.version || 'unknown') !== record.version) records.delete(id);
      }
      updateStatus();
      for (const {reader, parser} of readers) {
        const batch = await reader.read();
        if (disposed) return;
        if (!batch.missing) foundLog = true;
        if (batch.rotated) parser.reset();
        for (const raw of parser.push(batch.text)) accept(raw);
        if (!batch.bytesRead && batch.caughtUp) {
          for (const raw of parser.flush()) accept(raw);
        }
      }
      scanError = '';
    } catch (error) {
      scanError = redact(error.message, {home: os.homedir()}).slice(0, 300);
    }
  }

  function scan() {
    if (disposed) return Promise.resolve();
    if (!scanning) scanning = performScan().finally(() => { scanning = undefined; });
    return scanning;
  }

  const commands = [
    vscode.commands.registerCommand('vstab.compatibility.showReport', async () => { await scan(); report(); }),
    vscode.commands.registerCommand('vstab.compatibility.checkNow', async () => { await scan(); report(); })
  ];
  const timer = setInterval(() => { void scan(); }, options.pollMs || 1200);
  const changed = vscode.extensions.onDidChange?.(() => { void scan(); });
  timer.unref?.();
  const instance = {
    scan,
    report,
    get failures() { return [...records.values()]; },
    dispose() {
      if (disposed) return;
      disposed = true;
      clearInterval(timer);
      changed?.dispose();
      for (const command of commands) command.dispose();
      status.dispose();
      output.dispose();
    }
  };
  context.subscriptions.push(instance);
  instance.ready = scan();
  return instance;
}

exports.activate = context => createDiagnostics(require('vscode'), context);
exports.createDiagnostics = createDiagnostics;
