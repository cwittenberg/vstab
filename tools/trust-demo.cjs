// Grant Workspace Trust through the actual UI only for our controlled fixture.
const {CDP}=require('./cdp.cjs');
(async()=>{const c=await CDP.connect();try{
  if(await c.evaluate('document.body.innerText.includes("Restricted Mode")')){
    await c.click('[aria-label="Restricted Mode: Some features are disabled because this folder is not trusted."]');
    await c.waitFor('[...document.querySelectorAll("a.monaco-button")].some(e=>e.textContent.trim()==="Trust")');
    await c.clickText('Trust');
    await new Promise(r=>setTimeout(r,300));
    await c.key('Escape','Escape',27);
  }
  console.log('Controlled remote fixture trusted through Android Code OSS UI');
}finally{c.close();}})().catch(e=>{console.error(e.message);process.exitCode=1;});
