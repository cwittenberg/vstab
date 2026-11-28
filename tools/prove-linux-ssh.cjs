#!/usr/bin/env node
'use strict';
// Drive the normal Android VS Code UI against an explicitly owned SSH fixture.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {execFileSync} = require('node:child_process');
const f = require('./feature-proof.cjs');
const root = path.resolve(__dirname, '..');
let client;
let fixture;

async function main() {
  assert.ok(process.env.VSTAB_ANDROID_SERIAL && process.env.VSTAB_CDP_PORT,
    'Select the owned emulator and WebView explicitly.');
  assert.ok(process.env.VSTAB_SSH_FIXTURE && process.env.VSTAB_CANDIDATE_SHA256,
    'Provide the owned SSH fixture and exact candidate SHA-256.');
  fixture = JSON.parse(fs.readFileSync(process.env.VSTAB_SSH_FIXTURE));
  assert.equal(fixture.container, 'vstab-demo-ssh');
  const output = path.resolve(process.env.VSTAB_PROOF_OUTPUT || path.join(root, 'artifacts/shared-linux/ssh'));
  fs.mkdirSync(output, {recursive:true});
  const installed = f.adb('shell', 'pm', 'path', 'com.vstab').toString().trim().replace(/^package:/, '');
  const apkSha256 = f.adb('shell', 'sha256sum', installed).toString().split(/\s+/)[0];
  assert.equal(apkSha256, process.env.VSTAB_CANDIDATE_SHA256);
  const fingerprintFile = path.join(path.dirname(process.env.VSTAB_SSH_FIXTURE), 'known_hosts');
  const fingerprint = execFileSync('ssh-keygen', ['-lf', fingerprintFile], {encoding:'utf8'}).match(/SHA256:[A-Za-z0-9+/]+/)[0];
  const marker = 'vstab_linux_ssh_' + Date.now();
  const alias = marker.replaceAll('_', '-');
  const report = {schema:1, capturedAt:new Date().toISOString(), apkSha256,
    emulator:process.env.VSTAB_ANDROID_SERIAL, marker, checks:{}, screenshots:[],
    method:'Normal VS Code Add New SSH Host, Connect to Host, masked password prompt, editor save and integrated terminal; independent remote file readback.',
    limits:['Android x86_64 emulator; physical ARM64 is not established.']};
  const capture = name => { const p = path.join(output, name+'.png'); fs.writeFileSync(p, f.adb('exec-out', 'screencap', '-p')); report.screenshots.push(p); };
  const remoteRead = file => execFileSync('docker', ['exec', '--user', fixture.username, fixture.container, 'cat', file], {encoding:'utf8',timeout:10000});
  const until = async (fn, timeout=120000) => { const end=Date.now()+timeout; while(Date.now()<end){const value=await fn();if(value)return value;await f.delay(300);}throw Error('Timed out waiting for the actual SSH UI.'); };
  try {
    client = await f.CDP.connect();
    assert.equal(await client.evaluate('location.hostname'), 'localhost');
    await f.command(client, 'Remote-SSH: Add New SSH Host...');
    await client.waitFor(`document.body.innerText.includes('Enter an SSH connection command')`);
    await client.click('.quick-input-widget input');
    await f.typeKeys(client, `ssh -o HostName=${fixture.emulatorHost} -o PreferredAuthentications=password -p ${fixture.port} ${fixture.username}@${alias}`);
    await client.key('Enter','Enter',13);
    await client.waitFor(`document.body.innerText.includes('SSH host added to ~/.ssh/config.')`);
    await f.command(client, 'Remote-SSH: Connect to Host...');
    await client.waitFor(`[...document.querySelectorAll('.quick-input-widget .label-name')].some(e=>e.textContent.trim()===${JSON.stringify(alias)})`);
    await client.clickText(alias, '.quick-input-widget .label-name');
    let answered=false;
    const target = await until(async () => {
      const list=await (await fetch('http://127.0.0.1:'+process.env.VSTAB_CDP_PORT+'/json/list')).json();
      const target=list.find(t=>{try{const d=JSON.parse(t.description);return d.visible&&d.attached&&/^http:\/\/127\.0\.0\.[1-4]:/.test(t.url);}catch{return false;}});
      if(target)return target;
      if(await client.evaluate(`!!document.querySelector('.quick-input-widget')?.offsetHeight && document.querySelector('.quick-input-widget input')?.type==='password'`)){
        assert.equal(answered,false,'Repeated credential prompt is an authentication failure.');
        capture('01-masked-password');
        await client.click('.quick-input-widget input[type=password]');
        await f.typeKeys(client,fixture.password);await client.key('Enter','Enter',13);answered=true;
        report.checks.passwordEnteredThroughMaskedUi=true;
      }
      const dialog=await client.evaluate(`document.querySelector('.monaco-dialog-box')?.offsetHeight?document.querySelector('.monaco-dialog-box').innerText:''`);
      if(dialog.includes('Compare this SHA-256 fingerprint')){
        assert.ok(dialog.includes(fingerprint),'Compare the independent controlled host fingerprint.');
        capture('02-host-fingerprint');await client.clickText('Continue','.monaco-dialog-box .monaco-button');
        report.checks.hostFingerprintVerified=true;
      }
      const error=await client.evaluate(`document.body.innerText.match(/SSH authentication failed|Remote server download failed|The SSH connection failed/)?.[0]`);
      if(error)throw Error(error);
      return null;
    },180000);
    client.close();client=await f.CDP.connect(target.id);
    await client.waitFor('!!document.querySelector(".monaco-workbench")',90000);
    const trust = await client.evaluate(`(()=>{const e=document.querySelector('.monaco-dialog-box');return e?.offsetHeight?e.innerText:''})()`);
    if (/Do you trust|trust the authors/.test(trust)) {
      const label=await client.evaluate(`[...document.querySelectorAll('.monaco-dialog-box .monaco-button')].map(e=>e.textContent.trim()).find(t=>/Yes, I trust/.test(t))`);
      assert.ok(label);await client.clickText(label,'.monaco-dialog-box .monaco-button');
    }
    report.remoteOrigin=await client.evaluate('location.origin');
    report.remoteFolder=await client.evaluate('new URL(location.href).searchParams.get("folder")');
    report.checks.remoteWorkbenchOpened=true;
    const file=fixture.workspace+'/'+marker+'.txt';
    // Create a controlled fixture file independently, then edit it through Monaco.
    execFileSync('docker',['exec','--user',fixture.username,fixture.container,'sh','-c','printf "%s\\n" "$2" > "$1"','vstab-proof',file,'before UI edit']);
    await f.openFile(client,file);
    await f.delay(500);
    if(await client.evaluate(`[...document.querySelectorAll('.monaco-dialog-box .monaco-button')].some(e=>e.textContent.trim()==='Trust Folder & Continue'&&e.checkVisibility({checkVisibilityCSS:true}))`))
      await client.clickText('Trust Folder & Continue','.monaco-dialog-box .monaco-button');
    await f.replaceFile(client,marker+'.txt','Edited through the real VS Code SSH UI: '+marker+'\n');
    await until(()=>remoteRead(file).includes('Edited through the real VS Code SSH UI: '+marker),15000);
    report.checks.remoteEditorSavedAndReadBack=true;
    await f.command(client,'Terminal: Create New Terminal');
    await client.waitFor('!!document.querySelector(".terminal-wrapper.active .xterm-screen")');
    await f.terminal(client,`printf '%s\\n' 'SSH terminal: ${marker}' > '${fixture.workspace}/${marker}.terminal.txt'; uname -s; id -un; cat '${fixture.workspace}/${marker}.terminal.txt'`);
    await until(()=>{try{return remoteRead(fixture.workspace+'/'+marker+'.terminal.txt').includes(marker);}catch{return false;}},15000);
    report.checks.integratedTerminalExecutedOnRemote=true;
    await f.delay(800);capture('03-remote-editor-and-terminal');
    report.uiText=await client.evaluate('document.body.innerText.slice(-12000)');
    await f.command(client,'Remote-SSH: Close Remote Connection');
    client.close();client=null;
    await until(async()=>{const c=await f.CDP.connect();try{return await c.evaluate('location.hostname')==='localhost';}finally{c.close();}},30000);
    report.checks.disconnectReturnedToLocalLinux=true;
    fs.writeFileSync(path.join(output,'proof.json'),JSON.stringify(report,null,2)+'\n');
    console.log(JSON.stringify({passed:true,checks:report.checks,output}));
  } catch(error) {
    report.error=String(error.message).replaceAll(fixture.password,'<redacted>');
    fs.writeFileSync(path.join(output,'proof.json'),JSON.stringify(report,null,2)+'\n');throw error;
  } finally {client?.close();}
}
main().catch(error=>{console.error(String(error.message).replaceAll(fixture?.password||'__NO_PASSWORD__','<redacted>'));process.exitCode=1;});
