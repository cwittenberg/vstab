// Real Android UI helpers for repeatable feature proof. No editor/model test hooks.
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const {execFileSync} = require('node:child_process');
const {CDP} = require('./cdp.cjs');
const root = path.resolve(__dirname, '..');
const sdk = process.env.ANDROID_HOME;
const adbPath = sdk ? path.join(sdk, 'platform-tools/adb') : 'adb';
const serial = process.env.VSTAB_ANDROID_SERIAL || 'emulator-5580';
const adb = (...args) => execFileSync(adbPath, ['-s', serial, ...args], {maxBuffer: 12 * 1024 * 1024});
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
async function command(c, label) {
  await c.waitFor('!!document.querySelector(".monaco-workbench")', 90000);
  await c.key('Escape', 'Escape', 27);
  await c.key('P', 'KeyP', 80, 10);
  await c.waitFor('!!document.querySelector(".quick-input-widget input") && document.querySelector(".quick-input-widget").offsetHeight > 0');
  await c.text(label);
  await c.waitFor(`[...document.querySelectorAll('.quick-input-widget .label-name')].some(e => e.textContent.trim() === ${JSON.stringify(label)})`);
  await c.clickText(label, '.quick-input-widget .label-name');
}
function screenshot(name) {
  const target = path.join(root, 'artifacts/features', name);
  fs.mkdirSync(path.dirname(target), {recursive: true});
  fs.writeFileSync(target, adb('exec-out', 'screencap', '-p'));
  return target;
}
async function openFile(c, filename) {
  await c.key('p', 'KeyP', 80, 2);
  await c.waitFor('!!document.querySelector(".quick-input-widget input") && document.querySelector(".quick-input-widget").offsetHeight > 0');
  await c.text(filename);
  await c.waitFor('document.querySelectorAll(".quick-input-widget .quick-input-list .monaco-list-row").length > 0');
  await c.key('Enter', 'Enter', 13);
  const selector = '.monaco-editor[data-uri*=' + JSON.stringify(filename.split('/').pop()) + '] .view-lines';
  await c.waitFor(`!!document.querySelector(${JSON.stringify(selector)})`);
}
async function replaceFile(c, basename, text) {
  const selector = '.monaco-editor[data-uri*=' + JSON.stringify(basename) + '] .view-lines';
  await c.click(selector);
  await c.key('a', 'KeyA', 65, 2);
  await c.text(text);
  await c.key('s', 'KeyS', 83, 2);
  await delay(500);
}
async function terminal(c, text) {
  await c.click('.terminal-wrapper.active .xterm-screen');
  // Xterm's keypress path is exercised with Android's real key events. CDP's
  // insertText can change its hidden textarea without delivering terminal data.
  const quoted = "'" + text.replace(/ /g, '%s').replace(/'/g, "'\\''") + "'";
  adb('shell', 'input', 'text', quoted);
  adb('shell', 'input', 'keyevent', 'KEYCODE_ENTER');
}
async function report(c, name, extra = {}) {
  const result = {capturedAt: new Date().toISOString(), environment: 'Android API36 x86_64 tablet emulator, actual app.vstab APK/WebView',
    title: await c.evaluate('document.title'), origin: await c.evaluate('location.origin'),
    visibleText: await c.evaluate('document.body.innerText.slice(-16000)'), ...extra};
  fs.writeFileSync(path.join(root, 'artifacts/features', name), JSON.stringify(result, null, 2) + '\n');
  return result;
}
module.exports = {CDP, adb, command, delay, screenshot, openFile, replaceFile, terminal, report};
