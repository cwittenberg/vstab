// Edit a fixture line using ordinary Code OSS UI input inside Android.
const fs=require('fs');
const path=require('path');
const {CDP}=require('./cdp.cjs');
(async()=>{
  const [filename,lineValue,replacement,reportName]=process.argv.slice(2);
  const line=Number(lineValue);
  if(!filename||!Number.isInteger(line)||line<1||!replacement||replacement.includes('\n'))
    throw new Error('Usage: node tools/edit-demo-line.cjs filename line replacement [report.json]');
  const c=await CDP.connect();
  try{
    await c.waitFor(`[...document.querySelectorAll('.explorer-viewlet .label-name, .tabs-container .label-name')].some(e=>e.textContent.trim()===${JSON.stringify(filename)})`,60000);
    const inExplorer=await c.evaluate(`[...document.querySelectorAll('.explorer-viewlet .label-name')].some(e=>e.textContent.trim()===${JSON.stringify(filename)})`);
    // Explorer rows extend well beyond their label. Clicking empty row space
    // selects the row without opening a file; use the actual filename label.
    await c.clickText(filename,inExplorer?'.explorer-viewlet .label-name':'.tabs-container .label-name');
    await c.waitFor(`!!document.querySelector('.monaco-editor[data-uri*='+${JSON.stringify(JSON.stringify(filename))}+']')`);
    const selector='.monaco-editor[data-uri*='+JSON.stringify(filename)+'] .view-lines';
    await c.click(selector);
    const focused=await c.evaluate('document.activeElement.getAttribute("aria-label")');
    if(!focused?.includes(filename))throw new Error('The file editor did not receive focus');
    await c.key('Home','Home',36,2);
    for(let i=1;i<line;i++)await c.key('ArrowDown','ArrowDown',40);
    await c.key('Home','Home',36);
    await c.key('End','End',35,8);
    await c.text(replacement);
    await c.key('s','KeyS',83,2);
    await new Promise(r=>setTimeout(r,600));
    const report={capturedAt:new Date().toISOString(),environment:'Actual Android WebView in app.vstab',
      method:'Code OSS editor UI mouse and keyboard input; no direct file/model write',filename,line,replacement,focused,
      title:await c.evaluate('document.title'),origin:await c.evaluate('location.origin'),
      renderedEditorText:await c.evaluate(`document.querySelector('.monaco-editor[data-uri*='+${JSON.stringify(JSON.stringify(filename))}+']').innerText`)};
    if(reportName){const output=path.resolve(__dirname,'../artifacts/demo',reportName);fs.writeFileSync(output,JSON.stringify(report,null,2)+'\n');}
    console.log('Edited and saved '+filename+' line '+line+' through the Android Code OSS UI');
  }finally{c.close();}
})().catch(e=>{console.error(e.message);process.exitCode=1;});
