// Reproduce the ordinary Android Files -> vstab -> original-file save workflow.
// Prerequisite: the controlled proof project Documents/vstab-shared-proof/app.js
// already exists in Android shared storage. This uses no test provider, native
// test hook, explicit VIEW intent, editor model API, or synthetic rendered state.
// Run only while this process owns the emulator:
//   source .local/env.sh && node tools/prove-android-documents.cjs
'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const {execFileSync} = require('node:child_process');
const {CDP, adb, delay, replaceFile, screenshot, report} = require('./feature-proof.cjs');

const root = path.resolve(__dirname, '..');
const candidateApk = path.resolve(process.env.VSTAB_CANDIDATE_APK || path.join(root, 'android/app/build/outputs/apk/debug/app-debug.apk'));
const expectedApk = process.env.VSTAB_PROOF_APK_SHA256 || crypto.createHash('sha256').update(fs.readFileSync(candidateApk)).digest('hex');
const project = 'vstab-shared-proof';
const filename = 'app.js';
const originalPath = `/sdcard/Documents/${project}/${filename}`;
const originalUri = 'content://com.android.externalstorage.documents/document/' + encodeURIComponent(`primary:Documents/${project}/${filename}`);
const sessionId = crypto.createHash('sha256').update('file:' + originalUri).digest('hex').slice(0, 24);
const prefix = process.env.VSTAB_DOCUMENT_PROOF_PREFIX || 'final-documents';
const editorSelector = '.monaco-editor[data-uri*="/shared-documents/' + sessionId + '/workspace/app.js"]';
const editedText = 'console.log("final APK Android Files save-back");';
const externalText = 'console.log("final APK external update without reopening");';
const temporaryFile = path.join(root, '.local', `${prefix}-external.js`);
let client;

function installedApkSha256() {
  const apk = adb('shell', 'pm', 'path', 'app.vstab').toString().trim().replace(/^package:/, '');
  assert.match(apk, /^\/data\/app\/[A-Za-z0-9_./=+~\-]+\.apk$/, 'Unexpected installed APK path');
  return adb('shell', 'sha256sum', apk).toString().trim().split(/\s+/)[0];
}
function decodeXml(value) {
  return value.replace(/&(?:amp|lt|gt|quot|apos);|&#(?:x[0-9a-f]+|[0-9]+);/gi, entity => {
    const named = {'&amp;': '&', '&lt;': '<', '&gt;': '>', '&quot;': '"', '&apos;': "'"};
    if (named[entity]) return named[entity];
    return String.fromCodePoint(entity.startsWith('&#x') ? parseInt(entity.slice(3, -1), 16) : parseInt(entity.slice(2, -1), 10));
  });
}
function uiNodes() {
  adb('shell', 'uiautomator', 'dump', '--compressed', '/sdcard/vstab-documents-proof-ui.xml');
  const xml = adb('exec-out', 'cat', '/sdcard/vstab-documents-proof-ui.xml').toString();
  return [...xml.matchAll(/<node\b[^>]*>/g)].map(([node]) => Object.fromEntries(
    [...node.matchAll(/([\w-]+)="([^"]*)"/g)].map(([, key, value]) => [key, decodeXml(value)])
  ));
}
function bounds(node) {
  const numbers = node.bounds?.match(/-?\d+/g)?.map(Number);
  assert.equal(numbers?.length, 4, 'The Android UI item has no valid bounds');
  return numbers;
}
function tapText(text, content = false) {
  let choices = uiNodes().filter(node => node.text === text && node.enabled === 'true');
  if (content) {
    choices = choices.filter(node => node['resource-id'] === 'android:id/title');
    // On this tablet, the navigation rail and content folder can share a label.
    // Prefer the item farther right, in the actual file list.
    choices.sort((a, b) => bounds(b)[0] - bounds(a)[0]);
  } else choices.sort((a, b) => bounds(a)[0] - bounds(b)[0]);
  assert.ok(choices.length, 'Android Files did not show ' + text);
  const [left, top, right, bottom] = bounds(choices[0]);
  adb('shell', 'input', 'tap', String(Math.round((left + right) / 2)), String(Math.round((top + bottom) / 2)));
}
async function openThroughFiles() {
  adb('shell', 'am', 'start', '-n', 'com.google.android.documentsui/com.android.documentsui.LauncherActivity');
  await delay(350);
  const nodes = uiNodes();
  if (!nodes.some(node => node.text === 'Files in ' + project)) {
    tapText('sdk_gphone64_x86_64'); await delay(200);
    tapText('Documents', true); await delay(200);
    tapText(project, true); await delay(200);
  }
  screenshot(prefix + '-files.png');
  tapText(filename, true);
  execFileSync('python3', [path.join(root, 'tools/android-demo.py'), 'devtools'], {cwd: root, stdio: 'pipe'});
}
async function connectOpenedDocument() {
  const deadline = Date.now() + 90000;
  while (Date.now() < deadline) {
    const targets = await (await fetch('http://127.0.0.1:' + (process.env.VSTAB_CDP_PORT || '9227') + '/json/list')).json();
    const target = targets.find(target => {
      if (target.type !== 'page') return false;
      try {
        const description = JSON.parse(target.description || '{}');
        const folder = new URL(target.url).searchParams.get('folder') || '';
        return description.visible && description.attached && folder.endsWith('/shared-documents/' + sessionId + '/workspace');
      } catch { return false; }
    });
    if (target) return CDP.connect(target.id);
    await delay(250);
  }
  throw new Error('The Android Files tap did not open its document workspace');
}
async function waitOriginalBytes(expected) {
  const deadline = Date.now() + 20000;
  while (Date.now() < deadline) {
    if (adb('exec-out', 'cat', originalPath).toString() === expected) return;
    await delay(250);
  }
  assert.equal(adb('exec-out', 'cat', originalPath).toString(), expected, 'The original Android document did not receive the exact saved bytes');
}
async function hideKeyboard() {
  execFileSync('python3', [path.join(root, 'tools/hide-android-keyboard.py')], {cwd: root, stdio: 'pipe'});
  await delay(300);
}
async function main() {
  assert.match(prefix, /^[a-zA-Z0-9_-]+$/, 'The proof output prefix must be a simple file name');
  assert.equal(installedApkSha256(), expectedApk, 'The installed APK is not the requested frozen candidate');
  const bytesBefore = adb('exec-out', 'cat', originalPath).toString();
  await openThroughFiles();
  client = await connectOpenedDocument();
  await client.waitFor(`!!document.querySelector(${JSON.stringify(editorSelector + ' textarea.inputarea')})`, 60000);
  await client.waitFor('document.body.innerText.includes("Shared: app.js")', 30000);
  const activityState = adb('shell', 'dumpsys', 'activity', 'activities').toString();
  assert.match(activityState, /topResumedActivity=.*app\.vstab\/\.DocumentActivity/, 'The active Android window is not the document activity');
  assert.match(activityState, /launchedFromPackage=com\.google\.android\.documentsui/, 'The document did not come from Android Files');
  const targetId = client.target.id;
  const initialUrl = await client.evaluate('location.href');
  await replaceFile(client, filename, editedText);
  await client.waitFor(`document.querySelector(${JSON.stringify(editorSelector + ' .view-lines')})?.innerText.replaceAll('\u00a0', ' ') === ${JSON.stringify(editedText)}`, 10000);
  await waitOriginalBytes(editedText);
  await hideKeyboard();
  screenshot(prefix + '-save-back.png');
  fs.mkdirSync(path.dirname(temporaryFile), {recursive: true});
  fs.writeFileSync(temporaryFile, externalText);
  adb('push', temporaryFile, originalPath);
  await client.waitFor(`document.querySelector(${JSON.stringify(editorSelector + ' .view-lines')})?.innerText.replaceAll('\u00a0', ' ') === ${JSON.stringify(externalText)}`, 20000);
  assert.equal(await client.evaluate('location.href'), initialUrl, 'The editor workspace unexpectedly navigated during external refresh');
  assert.equal(client.target.id, targetId);
  assert.equal(adb('exec-out', 'cat', originalPath).toString(), externalText);
  assert.equal(installedApkSha256(), expectedApk, 'The APK changed during the proof');
  await hideKeyboard();
  screenshot(prefix + '-external-refresh.png');
  await report(client, prefix + '-android.json', {
    apkSha256: expectedApk,
    entry: 'Android Files launcher -> internal storage -> Documents/' + project + '/' + filename + ' -> normal file tap -> vstab DocumentActivity',
    explicitViewTestIntent: false,
    testProviderUsed: false,
    originalFile: originalPath,
    originalContentUri: originalUri,
    originalBytesBefore: bytesBefore,
    independentlyVerifiedSavedBytes: editedText,
    independentlyVerifiedExternalBytes: externalText,
    editorInput: 'Official Monaco textarea',
    sameOpenTargetAndWorkspaceDuringExternalRefresh: true,
    activityRoute: activityState.split('\n').filter(line => line.includes('DocumentActivity') || line.includes('launchedFromPackage=com.google.android.documentsui') || line.includes('Intent { act=android.intent.action.VIEW')),
    screenshots: [prefix + '-files.png', prefix + '-save-back.png', prefix + '-external-refresh.png']
  });
  console.log('PASS: actual Android Files launch, editor/save/original bytes, and live external refresh on APK ' + expectedApk);
}

if (require.main === module) main().catch(error => {
  try { screenshot(prefix + '-failure.png'); } catch { }
  console.error(error.stack || error.message);
  process.exitCode = 1;
}).finally(() => {
  client?.close();
  try { fs.unlinkSync(temporaryFile); } catch { }
});
