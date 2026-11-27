// Execute an exact command through the actual Code OSS command palette.
const {CDP}=require('./cdp.cjs');
(async()=>{
  const label=process.argv[2];
  if(!label)throw new Error('Usage: node tools/run-ide-command.cjs "Exact command label"');
  const c=await CDP.connect();
  try{
    await c.waitFor('!!document.querySelector(".monaco-workbench")',90000);
    await c.key('Escape','Escape',27);
    await c.key('P','KeyP',80,10);
    // Verify the palette opened before sending text: a missed shortcut must
    // never insert a command label into a user's active editor buffer.
    await c.waitFor('!!document.querySelector(".quick-input-widget input") && document.querySelector(".quick-input-widget").offsetHeight > 0');
    await c.text(label);
    await c.waitFor(`[...document.querySelectorAll('.quick-input-widget .label-name')].some(e=>e.textContent.trim()===${JSON.stringify(label)})`);
    await c.clickText(label,'.quick-input-widget .label-name');
    console.log('Ran command through Android Code OSS UI: '+label);
  }finally{c.close();}
})().catch(e=>{console.error(e.message);process.exitCode=1;});
