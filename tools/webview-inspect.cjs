// Inspect the real debug Android WebView through adb-forwarded CDP.
const { CDP } = require('./cdp.cjs');
(async () => {
  const page = await CDP.connect();
  try {
    console.log(JSON.stringify({title:page.target.title,url:page.target.url}));
    console.log((await page.evaluate('document.body.innerText')).slice(0,16000));
  } finally { page.close(); }
})().catch(error => {console.error(error.message);process.exitCode=1;});
