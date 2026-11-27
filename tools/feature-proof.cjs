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
  await c.waitFor('document.querySelector(".quick-input-widget input")?.value === ">" && document.querySelector(".quick-input-widget").offsetHeight > 0');
  await delay(200); await c.click('.quick-input-widget input');
  await typeKeys(c, label);
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
  await typeKeys(c, filename);
  const basename = filename.split('/').pop();
  await c.waitFor(`[...document.querySelectorAll('.quick-input-widget .label-name')].some(e => e.textContent.trim() === ${JSON.stringify(basename)})`);
  await c.clickText(basename, '.quick-input-widget .label-name');
  const selector = '.monaco-editor[data-uri*=' + JSON.stringify(filename.split('/').pop()) + '] .view-lines';
  await c.waitFor(`!!document.querySelector(${JSON.stringify(selector)})`);
}
async function replaceFile(c, basename, text) {
  const selector = '.monaco-editor[data-uri*=' + JSON.stringify(basename) + '] .view-lines';
  let focused = false;
  for (let attempt = 0; attempt < 4; attempt++) {
    await c.click(selector); await delay(250);
    focused = await c.evaluate(`(document.activeElement.getAttribute('aria-label') || '').includes(${JSON.stringify(basename)})`);
    if (focused) break;
  }
  if (!focused) throw new Error('The file editor did not receive focus: ' + basename);
  await c.key('a', 'KeyA', 65, 2);
  await typeKeys(c, text);
  await c.key('Escape', 'Escape', 27);
  await c.key('s', 'KeyS', 83, 2);
  await delay(500);
}
async function terminal(c, text) {
  await c.click('.terminal-wrapper.active .xterm-screen');
  await c.waitFor('document.activeElement?.classList.contains("xterm-helper-textarea") && document.activeElement.closest(".terminal-wrapper")?.classList.contains("active")');
  await delay(200);
  // A harmless leading shell space absorbs WebView's first CDP character after focus.
  await typeKeys(c, ' ' + text);
  await c.key('Enter', 'Enter', 13);
}
async function typeKeys(c, text) {
  // Real keydown/keypress events reach Monaco EditContext and xterm a11y mode;
  // insertText alone can update a hidden input without delivering editor data.
  for (const key of text) {
    if (key === '\n') { await c.key('Enter', 'Enter', 13); continue; }
    const punctuation = {
      ' ': ['Space', 32], '-': ['Minus', 189], '_': ['Minus', 189, 8], '=': ['Equal', 187], '+': ['Equal', 187, 8],
      ';': ['Semicolon', 186], ':': ['Semicolon', 186, 8], ',': ['Comma', 188], '<': ['Comma', 188, 8],
      '.': ['Period', 190], '>': ['Period', 190, 8], '/': ['Slash', 191], '?': ['Slash', 191, 8],
      '`': ['Backquote', 192], '~': ['Backquote', 192, 8], '[': ['BracketLeft', 219], '{': ['BracketLeft', 219, 8],
      '\\': ['Backslash', 220], '|': ['Backslash', 220, 8], ']': ['BracketRight', 221], '}': ['BracketRight', 221, 8],
      "'": ['Quote', 222], '"': ['Quote', 222, 8], '!': ['Digit1', 49, 8], '@': ['Digit2', 50, 8],
      '#': ['Digit3', 51, 8], '$': ['Digit4', 52, 8], '%': ['Digit5', 53, 8], '^': ['Digit6', 54, 8],
      '&': ['Digit7', 55, 8], '*': ['Digit8', 56, 8], '(': ['Digit9', 57, 8], ')': ['Digit0', 48, 8]
    };
    let [code, windowsVirtualKeyCode, modifiers = 0] = punctuation[key] || ['', 0];
    if (/^[a-zA-Z]$/.test(key)) { code = 'Key' + key.toUpperCase(); windowsVirtualKeyCode = key.toUpperCase().charCodeAt(0); modifiers = key === key.toUpperCase() ? 8 : 0; }
    else if (/^[0-9]$/.test(key)) { code = 'Digit' + key; windowsVirtualKeyCode = key.charCodeAt(0); }
    const params = {key, code, windowsVirtualKeyCode, nativeVirtualKeyCode: windowsVirtualKeyCode, modifiers};
    await c.send('Input.dispatchKeyEvent', {type: 'keyDown', ...params, text: key, unmodifiedText: key});
    await c.send('Input.dispatchKeyEvent', {type: 'keyUp', ...params});
  }
}
async function report(c, name, extra = {}) {
  const result = {capturedAt: new Date().toISOString(), environment: 'Android API36 x86_64 tablet emulator, actual app.vstab APK/WebView',
    title: await c.evaluate('document.title'), origin: await c.evaluate('location.origin'),
    visibleText: await c.evaluate('document.body.innerText.slice(-16000)'), ...extra};
  fs.writeFileSync(path.join(root, 'artifacts/features', name), JSON.stringify(result, null, 2) + '\n');
  return result;
}
module.exports = {CDP, adb, command, delay, screenshot, openFile, replaceFile, terminal, typeKeys, report};
