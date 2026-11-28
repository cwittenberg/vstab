'use strict';
// A controlled extension uses the real VS Code URI API and Android browser.
// This proves callback delivery, without provider credentials or a model call.
const fs = require('node:fs');
const path = require('node:path');
const {execFileSync} = require('node:child_process');
const {CDP, command, delay} = require('./feature-proof.cjs');
const adbPath = path.join(process.env.ANDROID_HOME, 'platform-tools/adb');
const serial = process.env.VSTAB_ANDROID_SERIAL || 'emulator-5584';
const quote = value => "'" + String(value).replace(/'/g, "'\\''") + "'";
const adb = args => execFileSync(adbPath, ['-s', serial, ...args], {maxBuffer: 8 * 1024 * 1024});
const shell = text => adb(['shell', 'run-as com.vstab sh -c ' + quote(text)]).toString();
const marker = 'callback-' + Date.now();
const id = 'vstab-proof.auth-return';
const folder = 'files/home/.local/share/vstab/user-data/extensions/' + id + '-0.0.1';
const stage = 'files/auth-callback-stage-' + marker;
const registry = 'files/home/.local/share/vstab/user-data/extensions/extensions.json';
const resultPath = 'files/home/.vstab-auth-return-proof.json';
function write(filename, bytes) {
  const data = Buffer.from(bytes).toString('base64');
  shell('printf %s ' + quote(data) + ' | base64 -d > ' + quote(filename));
}
function modifyRegistry(transform) {
  const values = JSON.parse(shell('cat ' + quote(registry)));
  write(registry, JSON.stringify(transform(values)));
}
async function main() {
  let client, forwardedPort;
  let registered = false;
  try {
    const home = shell('pwd').trim();
    shell('mkdir -p ' + quote(stage));
    write(stage + '/package.json', JSON.stringify({name: 'auth-return', publisher: 'vstab-proof', version: '0.0.1',
      engines: {vscode: '^1.100.0'}, main: './extension.cjs', activationEvents: ['onCommand:vstab-proof.browser-callback'],
      contributes: {commands: [{command: 'vstab-proof.browser-callback', title: 'VStab Proof: Browser Callback'}]}}));
    write(stage + '/extension.cjs', `const vscode=require('vscode');exports.activate=context=>{
      context.subscriptions.push(vscode.window.registerUriHandler({handleUri(uri){
        require('fs').writeFileSync(process.env.HOME+'/.vstab-auth-return-proof.json',JSON.stringify({uri:uri.toString(true),query:uri.query,fragment:uri.fragment}));
        void vscode.window.showInformationMessage('Browser sign-in callback reached VStab.');
      }}));
      context.subscriptions.push(vscode.commands.registerCommand('vstab-proof.browser-callback',async()=>{
        const target=await vscode.env.asExternalUri(vscode.Uri.from({scheme:vscode.env.uriScheme,authority:'${id}',path:'/return',query:'state=${marker}&literal=original%252F&second=one%26two%2Fthree',fragment:'a&b%'}));
        const address=new URL(target.toString(true));address.searchParams.set('code','literal%2F+&code');
        await vscode.env.openExternal(vscode.Uri.from({scheme:address.protocol.slice(0,-1),authority:address.host,path:address.pathname,query:address.search.slice(1)}));
      }));
    };`);
    shell('mv ' + quote(stage) + ' ' + quote(folder));
    modifyRegistry(values => [...values.filter(value => value.identifier?.id !== id),
      {identifier: {id}, version: '0.0.1', location: {scheme: 'file', path: home + '/' + folder},
        relativeLocation: id + '-0.0.1', metadata: {targetPlatform: 'undefined'}}]);
    registered = true;
    // A normal process restart lets the extension service scan the fixture.
    // Do not invoke page reload or change another emulator's CDP forwarding.
    adb(['shell', 'am', 'force-stop', 'com.vstab']);
    adb(['shell', 'am', 'start', '-n', 'com.vstab/app.vstab.MainActivity']);
    let pid;
    for (let i = 0; i < 40; i++) {
      try { pid = adb(['shell', 'pidof', 'com.vstab']).toString().trim(); } catch {}
      if (/^\d+$/.test(pid || '')) break;
      await delay(250);
    }
    if (!/^\d+$/.test(pid || '')) throw Error('The app did not restart.');
    forwardedPort = adb(['forward', 'tcp:0', 'localabstract:webview_devtools_remote_' + pid]).toString().trim();
    process.env.VSTAB_CDP_PORT = forwardedPort;
    client = await CDP.connect();
    if (new URL(await client.evaluate('location.href')).hostname !== 'localhost') throw Error('Use a local workspace.');
    await command(client, 'VStab Proof: Browser Callback');
    await delay(1500);
    // Bring the same editor activity back from the external browser.
    adb(['shell', 'am', 'start', '--activity-reorder-to-front', '-n', 'com.vstab/app.vstab.MainActivity']);
    let result;
    for (let i = 0; i < 40; i++) {
      try { result = JSON.parse(shell('cat ' + quote(resultPath))); } catch {}
      if (result?.query?.includes(marker)) break;
      await delay(250);
    }
    if (!result?.query?.includes(marker)) throw Error('The extension did not receive the browser callback.');
    if (new URLSearchParams(result.query).get('code') !== 'literal%2F+&code') throw Error('Encoded callback data changed.');
    if (new URLSearchParams(result.query).get('literal') !== 'original%2F') throw Error('Original callback query changed.');
    if (new URLSearchParams(result.query).get('second') !== 'one&two/three') throw Error('Original multi-parameter query changed.');
    if (result.fragment !== 'a&b%') throw Error('Original callback fragment changed.');
    const output = path.resolve(__dirname, '../artifacts/git-ai/auth-callback.json');
    fs.mkdirSync(path.dirname(output), {recursive: true});
    fs.writeFileSync(output, JSON.stringify({status: 'passed', marker, result, fixtureLoadedAfterAppRestart: true,
      proof: 'Controlled extension: env.asExternalUri -> env.openExternal -> external Android browser -> native receiver -> registerUriHandler',
      limit: 'No provider-authenticated sign-in or inference performed.'}, null, 2));
    console.log('PASS: actual extension/browser callback returned intact. ' + output);
  } finally {
    if (registered) modifyRegistry(values => values.filter(value => value.identifier?.id !== id));
    shell('rm -rf ' + quote(folder) + ' ' + quote(stage) + ' ' + quote(resultPath));
    client?.close();
    if (forwardedPort) adb(['forward', '--remove', 'tcp:' + forwardedPort]);
  }
}
main().catch(error => { console.error(error.message); process.exitCode = 1; });
