// Record only safe response metadata from the actual Android WebView.
const fs=require('fs');
const path=require('path');
const {CDP}=require('./cdp.cjs');
(async()=>{
  const c=await CDP.connect();
  const responses=[];
  try{
    c.on('Network.responseReceived',event=>{
      const response=event.response;
      const url=new URL(response.url);
      if(url.protocol!=='http:'&&url.protocol!=='https:')return;
      const headers=Object.fromEntries(Object.entries(response.headers).map(([k,v])=>[k.toLowerCase(),v]));
      responses.push({origin:url.origin,path:url.pathname,status:response.status,mimeType:response.mimeType,
        source:headers['x-vstab-source']||null,codeOSSCommit:headers['x-vstab-codeoss-commit']||null});
    });
    await c.send('Network.enable');
    await c.send('Network.setCacheDisabled',{cacheDisabled:true});
    const resourceChecks=await c.evaluate(`(async()=>{
      const base=document.getElementById('vscode-workbench-web-base-url').getAttribute('data-settings').replace(/\\/$/,'');
      const checks=[];
      for(const resource of ['/',base+'/out/vs/code/browser/workbench/workbench.js',base+'/out/vs/code/browser/workbench/workbench.css']){
        const r=await fetch(resource,{cache:'no-store'});
        const bytes=await r.arrayBuffer();
        const digest=await crypto.subtle.digest('SHA-256',bytes);
        checks.push({path:new URL(resource,location.href).pathname,status:r.status,source:r.headers.get('X-Vstab-Source'),
          codeOSSCommit:r.headers.get('X-Vstab-CodeOSS-Commit'),bytes:bytes.byteLength,
          sha256:[...new Uint8Array(digest)].map(b=>b.toString(16).padStart(2,'0')).join('')});
      }
      return checks;
    })()`);
    const report={capturedAt:new Date().toISOString(),environment:'Actual Android emulator WebView inside com.vstab',
      editor:await c.evaluate('({title:document.title,origin:location.origin,bodyText:document.body.innerText.slice(0,1800)})'),
      apkResponses:responses.filter(r=>r.source==='apk').length,resourceChecks,responses};
    if(!report.apkResponses)throw new Error('No APK-served editor responses were observed');
    const output=path.resolve(__dirname,'../artifacts/demo',process.argv[2]||'bundled-workbench-network.json');
    fs.writeFileSync(output,JSON.stringify(report,null,2)+'\n');
    console.log('Recorded '+report.apkResponses+' APK-served workbench responses at '+output);
  }finally{c.close();}
})().catch(e=>{console.error(e.message);process.exitCode=1;});
