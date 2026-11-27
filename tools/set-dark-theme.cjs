// Select Dark Modern using the real VS Code command palette and theme picker.
const {CDP}=require('./cdp.cjs');
(async()=>{const c=await CDP.connect();try{
  await c.waitFor('!!document.querySelector(".monaco-workbench")');
  await c.key('Escape','Escape',27);
  await c.key('P','KeyP',80,10);
  await c.text('Preferences: Color Theme');
  await c.waitFor('[...document.querySelectorAll(".quick-input-widget .label-name")].some(e=>e.textContent.trim()==="Preferences: Color Theme")');
  await c.clickText('Preferences: Color Theme','.quick-input-widget .label-name');
  await c.waitFor('[...document.querySelectorAll(".quick-input-widget .label-name")].some(e=>e.textContent.trim()==="Dark Modern")');
  await c.clickText('Dark Modern','.quick-input-widget .label-name');
  await c.waitFor('!!document.querySelector(".monaco-workbench.vs-dark")');
  console.log('Selected Dark Modern in actual Android Code OSS UI');
}finally{c.close();}})().catch(e=>{console.error(e.message);process.exitCode=1;});
