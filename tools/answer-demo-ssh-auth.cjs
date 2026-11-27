// Drive only the controlled fixture's real VS Code SSH prompts. Never log values.
const fs = require('fs');
const {execFileSync} = require('child_process');
const {CDP} = require('./cdp.cjs');
(async () => {
  const [alias, mode = 'password', output = 'ssh-auth-ui.json'] = process.argv.slice(2);
  if (!alias || !['password', 'key', 'encrypted-key'].includes(mode)) throw new Error('Expected fixture alias and auth mode');
  const expected = execFileSync('ssh-keygen', ['-lf', '.local/demo/known_hosts'], {encoding: 'utf8'}).split(/\s+/)[1];
  const c = await CDP.connect();
  const events = [];
  try {
    await c.waitFor(`[...document.querySelectorAll('.quick-input-widget .label-name')].some(e=>e.textContent.trim()===${JSON.stringify(alias)}&&e.getBoundingClientRect().height>0)`);
    await c.clickText(alias, '.quick-input-widget .label-name');
    let answered = false;
    const until = Date.now() + 150000;
    while (Date.now() < until) {
      const targets = await (await fetch('http://127.0.0.1:' + (process.env.VSTAB_CDP_PORT || '9227') + '/json/list')).json();
      const ready = targets.find(t => t.type === 'page' && /^http:\/\/127\.0\.0\.[1-4]:/.test(t.url) && (() => {
        try { const d = JSON.parse(t.description); return d.visible && d.attached; } catch { return false; }
      })());
      if (ready) {
        const report = {capturedAt: new Date().toISOString(), device: process.env.VSTAB_ANDROID_SERIAL,
          alias, mode, enteredMaskedCredential: answered, url: ready.url, events};
        fs.writeFileSync('artifacts/native-runtime/' + output, JSON.stringify(report, null, 2) + '\n');
        console.log('Authenticated fixture via actual VS Code UI: ' + alias + ' (' + mode + ')');
        return;
      }
      const ui = await c.evaluate(`({dialog:document.querySelector('.monaco-dialog-box')?.innerText||'',
        password:[...document.querySelectorAll('.quick-input-widget input[type=password]')].some(e=>e.getBoundingClientRect().height>0),
        prompt:document.querySelector('.quick-input-widget')?.innerText||'',body:document.body.innerText.slice(-1200)})`);
      if (ui.dialog.includes('SHA256:')) {
        if (!ui.dialog.includes(expected)) throw new Error('Host fingerprint does not match the independently verified fixture');
        events.push({event: 'verified-host-key', fingerprint: expected});
        await c.clickText('Continue', '.monaco-dialog-box .monaco-button');
      } else if (ui.password && !answered) {
        if (mode === 'key') throw new Error('Unencrypted private key unexpectedly requested a credential');
        if (!ui.prompt.includes(alias) && !ui.prompt.includes('developer')) throw new Error('Unexpected credential prompt; no value entered');
        if (mode === 'encrypted-key' && !/passphrase/i.test(ui.prompt)) throw new Error('Expected a private-key passphrase prompt; no value entered');
        if (mode === 'password' && /passphrase/i.test(ui.prompt)) throw new Error('Expected a password prompt; no value entered');
        events.push({event: mode === 'password' ? 'masked-password' : 'masked-key-passphrase', prompt: ui.prompt});
        const value = mode === 'password' ? JSON.parse(fs.readFileSync('.local/demo/connection.json')).password
          : fs.readFileSync('.local/demo/sidebar-keys/encrypted.passphrase', 'utf8').trim();
        await c.text(value); await c.key('Enter', 'Enter', 13); answered = true;
      }
      if (ui.body.includes('Connection failed')) throw new Error('SSH authentication failed; inspect connection status');
      await new Promise(resolve => setTimeout(resolve, 400));
    }
    throw new Error('SSH connection did not complete in time');
  } finally { c.close(); }
})().catch(error => { console.error(error.message); process.exitCode = 1; });
