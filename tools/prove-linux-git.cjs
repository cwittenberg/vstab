#!/usr/bin/env node
'use strict';
// Real Android VS Code Git UI against a newly created, task-owned SSH fixture.
// Guest SSH is used only to read independently; it never performs a mutation.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {execFileSync} = require('node:child_process');
const f = require('./feature-proof.cjs');
const ROOT = path.resolve(__dirname, '..');
const output = path.resolve(process.env.VSTAB_PROOF_OUTPUT || path.join(ROOT, 'artifacts/shared-linux/ux-v2/git'));
const keys = process.env.VSTAB_GIT_FIXTURE_KEYS || '/home/zs/vspad/.local/demo/sidebar-keys';
const connectionFile = path.resolve(process.env.VSTAB_LINUX_CONNECTION || path.join(ROOT, '.local/linux-debug/connection.json'));
const resumePendingCommit = process.argv.includes('--resume-pending-commit');
const resumeAfterPull = process.argv.includes('--resume-after-pull') || resumePendingCommit;
const previousFixture = resumeAfterPull ? JSON.parse(fs.readFileSync(path.join(output,'fixture.json'))) : null;
const previousReport = resumeAfterPull ? JSON.parse(fs.readFileSync(path.join(output,'proof.json'))) : null;
const run = previousFixture?.run || Date.now().toString();
const alias = 'vstab-linux-git-' + run;
const filename = alias + '-encrypted.key';
const fixture = previousFixture || {container:'vstab-demo-ssh',run,repo:'/home/developer/' + alias + '.git',seed:'/home/developer/' + alias + '-seed'};
const destination = '/home/developer/workspaces/';
const local = destination + alias;
const url = alias + ':' + fixture.repo;
const quote = value => "'" + String(value).replaceAll("'", "'\\''") + "'";
let c;
let connection;
const report = {schema:1,capturedAt:new Date().toISOString(),checks:{},screenshots:[],credentialPrompts:0,
  environment:'Actual com.vstab Android WebView and shared Linux guest; controlled SSH Git server',
  proofMethod:'Remote Explorer key import and Android document picker; Git Clone, Pull, Commit and Push through the ordinary VS Code UI; independent guest/server readback',
  fixtureNetworkAdapter:'adb reverse tcp:22227 tcp:22227 exposes the task-owned host SSH server to the nested guest. This is test-network routing only.',...previousReport};
delete report.error;
const remote = script => execFileSync('docker',['exec','--user','developer',fixture.container,'python3','-c',script,JSON.stringify(fixture)],{encoding:'utf8',timeout:30000});
function guest(command) {
  try { return execFileSync('ssh',['-i',connection.key,'-p',String(connection.port),'-o','StrictHostKeyChecking=yes',
    '-o','UserKnownHostsFile='+connection.knownHosts,'-o','BatchMode=yes','-o','ConnectTimeout=10',
    'developer@127.0.0.1',command],{encoding:'utf8',timeout:30000,stdio:['ignore','pipe','pipe']}).trim(); }
  catch(error) { throw Error('Independent guest readback failed (exit '+error.status+').'); }
}
const read = filename => guest('cat -- ' + quote(filename));
const head = () => guest('git -C ' + quote(local) + ' rev-parse HEAD');
const androidRead = filename => f.adb('exec-out','run-as','com.vstab','cat',filename).toString();
async function until(fn,timeout=90000) {
  const end=Date.now()+timeout;
  while(Date.now()<end) { const value=await fn(); if(value)return value; await f.delay(250); }
  throw Error('Timed out waiting for the ordinary Linux Git UI workflow.');
}
async function input(value) {
  await c.click('.quick-input-widget input'); await c.key('a','KeyA',65,2); await f.typeKeys(c,value);
  assert.equal(await c.evaluate('document.querySelector(".quick-input-widget input").value'),value);
}
async function enter(value) { await input(value); await c.key('Enter','Enter',13); }
async function capture(name) {
  const target=path.join(output,name+'.png'); fs.writeFileSync(target,f.adb('exec-out','screencap','-p'));
  report.screenshots.push(path.relative(ROOT,target));
}
async function view() { return c.evaluate(`({quick:document.querySelector('.quick-input-widget')?.offsetHeight?document.querySelector('.quick-input-widget').innerText:'',masked:!!document.querySelector('.quick-input-widget input[type=password]')?.checkVisibility({checkVisibilityCSS:true}),dialog:document.querySelector('.monaco-dialog-box')?.offsetHeight?document.querySelector('.monaco-dialog-box').innerText:'',notifications:[...document.querySelectorAll('.notification-list-item')].map(e=>e.innerText)})`); }
async function authenticateUntil(done,label) {
  let answered=false;
  return until(async()=>{
    if(await done())return true;
    const state=await view();
    if(state.masked&&/passphrase|SSH Key/i.test(state.quick)) {
      assert.equal(answered,false,'A repeated passphrase prompt indicates an authentication failure.');
      await capture(label+'-encrypted-key-prompt');
      await c.click('.quick-input-widget input[type=password]');
      assert.equal(await c.evaluate('document.activeElement?.type'),'password');
      await f.typeKeys(c,fs.readFileSync(path.join(keys,'encrypted.passphrase'),'utf8').trim());
      await c.key('Enter','Enter',13); answered=true;report.credentialPrompts++;
    } else if(/Are you sure you want to continue connecting/.test(state.quick)) {
      assert.ok(state.quick.includes(report.serverFingerprint),'The fingerprint must match the controlled server.');
      await c.clickText('yes','.quick-input-widget .label-name');report.hostFingerprintCompared=true;
    }
    const errors=state.notifications.filter(text=>/Permission denied|Could not read from remote repository|Authentication failed|unable to fork|cannot run ssh|Could not resolve hostname/.test(text));
    assert.deepEqual(errors,[],'Git UI reported an operation failure.');
    return false;
  },120000);
}
function nativeNodes() {
  f.adb('shell','uiautomator','dump','/sdcard/vstab-linux-git-window.xml');
  const xml=f.adb('exec-out','cat','/sdcard/vstab-linux-git-window.xml').toString();
  return [...xml.matchAll(/<node\b[^>]*>/g)].map(match=>Object.fromEntries([...match[0].matchAll(/([\w-]+)="([^"]*)"/g)].map(item=>[item[1],item[2]])));
}
function tap(node) {
  const [a,b,x,y]=node.bounds.match(/\d+/g).map(Number);f.adb('shell','input','tap',String(Math.round((a+x)/2)),String(Math.round((b+y)/2)));
}
async function selectNativeKey() {
  await until(async()=>nativeNodes().some(n=>/documentsui/.test(n.package||'')));
  let nodes=nativeNodes(),chosen=nodes.find(n=>n.text===filename);
  if(!chosen) {
    const drawer=nodes.find(n=>n['content-desc']==='Show roots');if(drawer){tap(drawer);await f.delay(400);nodes=nativeNodes();}
    const downloads=nodes.find(n=>n.text==='Downloads');assert.ok(downloads,'Android picker must expose Downloads.');tap(downloads);
    chosen=await until(async()=>nativeNodes().find(n=>n.text===filename));
  }
  tap(chosen);
}
async function trustFixture() {
  const state=await view();
  if(/Do you trust|trust the authors/.test(state.dialog)) {
    const label=await c.evaluate(`[...document.querySelectorAll('.monaco-dialog-box .monaco-button')].map(e=>e.textContent.trim()).find(t=>/Yes, I trust/.test(t))`);
    assert.ok(label);await c.clickText(label,'.monaco-dialog-box .monaco-button');
  }
  const restricted='[aria-label="Restricted Mode: Some features are disabled because this folder is not trusted."]';
  if(await c.evaluate(`!!document.querySelector(${JSON.stringify(restricted)})`)) {
    await c.click(restricted);await c.waitFor(`[...document.querySelectorAll('a.monaco-button')].some(e=>e.textContent.trim()==='Trust')`);
    await c.clickText('Trust');await c.key('Escape','Escape',27);
  }
}
async function selectClonedWorkbench() {
  c.close();c=null;
  const selected=await until(async()=>{
    const targets=await(await fetch('http://127.0.0.1:'+process.env.VSTAB_CDP_PORT+'/json/list')).json();
    return targets.find(t=>{try{const d=JSON.parse(t.description);return d.visible&&d.attached&&new URL(t.url).searchParams.get('folder')===local;}catch{return false;}});
  });
  c=await f.CDP.connect(selected.id);await c.waitFor('!!document.querySelector(".monaco-workbench")',90000);
  await f.delay(2000);await c.key('G','KeyG',71,10);
  await until(async()=>{await trustFixture();return c.evaluate(`!!document.querySelector('textarea[aria-label^="Message (Ctrl+Enter to commit"]')`);},120000);
}
async function main() {
  if(!process.argv.includes('--run')){console.log('Prepared Linux Git UI proof. Use --run only after explicit device handover.');return;}
  assert.equal(process.env.VSTAB_ANDROID_SERIAL,'emulator-5584','Only the explicitly owned device is authorized.');
  assert.equal(process.env.VSTAB_CDP_PORT,'9234');assert.match(process.env.VSTAB_CANDIDATE_SHA256||'',/^[a-f0-9]{64}$/);
  connection=JSON.parse(fs.readFileSync(connectionFile));
  assert.equal(fixture.container,'vstab-demo-ssh');
  assert.equal(fixture.repo,'/home/developer/'+alias+'.git');
  assert.equal(fixture.seed,'/home/developer/'+alias+'-seed');
  fs.mkdirSync(output,{recursive:true});
  const installed=f.adb('shell','pm','path','com.vstab').toString().trim().split('\n')[0].replace(/^package:/,'');
  report.apkSha256=f.adb('shell','sha256sum',installed).toString().split(/\s+/)[0];assert.equal(report.apkSha256,process.env.VSTAB_CANDIDATE_SHA256);
  const probe=JSON.parse(androidRead('files/linux-environments/last-probe.json'));
  assert.equal(probe.status,'ready');assert.equal(probe.platform,'linux');assert.equal(probe.environmentId,connection.environmentId);
  report.guest={environmentId:probe.environmentId,provider:probe.provider,architecture:probe.architecture,buildId:probe.guest?.guestBuildId};
  report.checks.actualLinuxEnvironmentReady=true;
  report.serverFingerprint=execFileSync('ssh-keygen',['-lf','/home/zs/vspad/.local/demo/known_hosts'],{encoding:'utf8'}).match(/SHA256:[A-Za-z0-9+/]+/)[0];
  let remoteHead;
  if(resumeAfterPull) {
    assert.equal(previousReport.apkSha256,report.apkSha256);
    for(const name of ['importedEncryptedKeyThroughRemoteExplorer','cloneThroughGitUi','pullThroughGitUi']) assert.equal(previousReport.checks[name],true);
    report.driverContinuation='Resumed the same verified repository after waiting for workspace trust/Git activation and retrying the independent author-identity readback.';
    c=await f.CDP.connect();assert.equal(await c.evaluate('new URL(location.href).searchParams.get("folder")'),local);
    remoteHead=head();
  } else {
  fixture.initialCommit=remote(`import json,pathlib,subprocess,sys\nx=json.loads(sys.argv[1]);r=pathlib.Path(x['repo']);p=pathlib.Path(x['seed']);assert not r.exists() and not p.exists()\ndef g(*a):return subprocess.check_output(['git',*a],text=True,stderr=subprocess.STDOUT)\ng('init','--bare','--initial-branch=main',str(r));g('init','--initial-branch=main',str(p));g('-C',str(p),'config','user.name','VStab fixture');g('-C',str(p),'config','user.email','fixture@vstab.invalid');(p/'README.md').write_text('VStab Linux Git workflow '+x['run']+'\\n');g('-C',str(p),'add','.');g('-C',str(p),'commit','-m','Controlled Git fixture');g('-C',str(p),'remote','add','origin',str(r));g('-C',str(p),'push','-u','origin','main');print(g('-C',str(p),'rev-parse','HEAD').strip())`).trim();
  report.fixture={container:fixture.container,repo:fixture.repo,initialCommit:fixture.initialCommit};report.localRepository=local;
  fs.writeFileSync(path.join(output,'fixture.json'),JSON.stringify(fixture,null,2)+'\n');
  f.adb('reverse','tcp:22227','tcp:22227');
  f.adb('push',path.join(keys,'encrypted_ed25519'),'/sdcard/Download/'+filename);
  c=await f.CDP.connect();assert.equal(await c.evaluate('location.hostname'),'localhost');await trustFixture();
  await f.command(c,'Remote-SSH: Add New SSH Host...');await c.waitFor(`document.querySelector('.quick-input-widget input')?.value !== '>'`);
  await enter('ssh -o HostName=10.0.2.2 -o PreferredAuthentications=publickey -p 22227 developer@'+alias);
  await until(async()=>androidRead('files/home/.ssh/config').includes(alias));
  await f.command(c,'Remote-SSH: Import SSH Private Key...');
  await c.waitFor(`[...document.querySelectorAll('.quick-input-widget .monaco-list-row')].some(e=>e.innerText.includes(${JSON.stringify(alias)}))`);
  const point=await c.evaluate(`(()=>{const e=[...document.querySelectorAll('.quick-input-widget .monaco-list-row')].find(e=>e.innerText.includes(${JSON.stringify(alias)}));const r=e.getBoundingClientRect();return{x:r.x+r.width/2,y:r.y+r.height/2}})()`);
  await c.send('Input.dispatchMouseEvent',{type:'mousePressed',...point,button:'left',buttons:1,clickCount:1});
  await c.send('Input.dispatchMouseEvent',{type:'mouseReleased',...point,button:'left',buttons:0,clickCount:1});
  await selectNativeKey();
  await until(async()=>guest('ssh -G '+quote(alias)).split('\n').some(line=>line.startsWith('identityfile ~/.ssh/keys/')));
  report.checks.importedEncryptedKeyThroughRemoteExplorer=true;await capture('01-encrypted-key-imported');
  await f.command(c,'Git: Clone');await c.waitFor(`document.querySelector('.quick-input-widget input')?.getAttribute('aria-label') === 'Provide repository URL or pick a repository source.'`);
  await input(url);await c.waitFor(`[...document.querySelectorAll('.quick-input-widget .monaco-list-row')].some(e=>e.innerText.includes('Clone from URL')&&e.innerText.includes(${JSON.stringify(url)}))`);
  await c.clickText('Clone from URL','.quick-input-widget .label-name');
  await c.waitFor(`document.querySelector('.quick-input-widget')?.innerText.includes('Select as Repository Destination')`);
  await input(destination);await f.delay(600);await c.clickText('Select as Repository Destination','.quick-input-widget .monaco-button, .quick-input-widget button');
  await authenticateUntil(async()=>{try{return head()===fixture.initialCommit;}catch{return false;}},'02-clone');
  report.checks.cloneThroughGitUi=true;
  await until(async()=>(await view()).dialog.includes('Would you like to open'));await c.clickText('Open','.monaco-dialog-box .monaco-button');
  await selectClonedWorkbench();await c.key('G','KeyG',71,10);await f.delay(500);await capture('03-cloned-source-control');
  remoteHead=remote(`import json,pathlib,subprocess,sys\nx=json.loads(sys.argv[1]);p=pathlib.Path(x['seed']);(p/'REMOTE_CHANGE.md').write_text('REMOTE_PULL_'+x['run']+'\\n')\ndef g(*a):return subprocess.check_output(['git','-C',str(p),*a],text=True,stderr=subprocess.STDOUT)\ng('add','.');g('commit','-m','Controlled remote change');g('push','origin','main');print(g('rev-parse','HEAD').strip())`).trim();
  await f.command(c,'Git: Pull');await authenticateUntil(async()=>{try{return head()===remoteHead&&read(local+'/REMOTE_CHANGE.md')==='REMOTE_PULL_'+run;}catch{return false;}},'04-pull');
  report.checks.pullThroughGitUi=true;await capture('05-pulled-remote-change');
  }
  const saved='LOCAL_PUSH_'+run+' saved in VStab';
  if(!resumePendingCommit) {
  await f.openFile(c,'README.md');await f.replaceFile(c,'README.md',saved);await until(async()=>read(local+'/README.md')===saved);report.checks.editorSavedBeforePush=true;
  // The user identity is configured through the visible integrated terminal,
  // never through our independent SSH reader. Commit itself uses Source Control.
  await f.command(c,'Terminal: Create New Terminal');await c.waitFor('!!document.querySelector(".terminal-wrapper.active .xterm-screen")');
  await f.terminal(c,"git config user.name 'VStab Git proof'; git config user.email 'git-proof@vstab.invalid'");
  await until(async()=>{try{return guest('git -C '+quote(local)+' config user.email')==='git-proof@vstab.invalid';}catch{return false;}});
  await f.command(c,'Git: Stage All Changes');
  await f.command(c,'Git: Commit');
  } else assert.equal(previousReport.checks.editorSavedBeforePush,true);
  let messageEntered=false;
  await until(async()=>{
    if(head()!==remoteHead)return true;
    if(!messageEntered&&await c.evaluate(`!!document.querySelector('.monaco-editor[data-uri*="COMMIT_EDITMSG"] .view-lines')`)) {
      await f.replaceFile(c,'COMMIT_EDITMSG','LOCAL_PUSH_'+run);
      await c.click('a.action-label[aria-label="Commit"]');messageEntered=true;
    }
    return false;
  },180000);
  report.checks.commitThroughGitUi=true;const localHead=head();
  await f.command(c,'Git: Push');
  await authenticateUntil(async()=>remote(`import json,subprocess,sys\nx=json.loads(sys.argv[1]);print(subprocess.check_output(['git','--git-dir='+x['repo'],'rev-parse','HEAD'],text=True).strip())`).trim()===localHead,'06-push');
  report.checks.pushThroughGitUi=true;
  const pushed=remote(`import json,subprocess,sys\nx=json.loads(sys.argv[1]);sys.stdout.write(subprocess.check_output(['git','--git-dir='+x['repo'],'show','HEAD:README.md'],text=True))`);
  assert.equal(pushed,saved);report.checks.pushedEditorBytesVerifiedOnServer=true;
  report.checks.encryptedKeyPromptExercised=report.credentialPrompts>0;assert.ok(Object.values(report.checks).every(Boolean));
  report.finalRemoteCommit=localHead;await capture('07-pushed-commit');report.status='passed';
  console.log(JSON.stringify({passed:true,checks:report.checks,output}));
}
main().catch(error=>{report.status='failed';report.error=String(error.message);console.error('Linux Git UI proof failed: '+error.message);process.exitCode=1;}).finally(()=>{
  c?.close();report.completedAt=new Date().toISOString();if(process.argv.includes('--run')){fs.mkdirSync(output,{recursive:true});fs.writeFileSync(path.join(output,'proof.json'),JSON.stringify(report,null,2)+'\n');}
});
