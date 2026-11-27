// Minimal CDP client for the actual Android WebView; no browser launch needed.
class CDP {
  constructor(socket) {
    this.socket = socket;
    this.sequence = 0;
    this.pending = new Map();
    this.listeners = new Map();
    this.executionContexts = new Map();
    socket.addEventListener('message', event => {
      const message = JSON.parse(event.data);
      if (message.id) {
        const pending = this.pending.get(message.id);
        if (!pending) return;
        this.pending.delete(message.id);
        clearTimeout(pending.timeout);
        if (message.error) pending.reject(new Error(message.error.message));
        else pending.resolve(message.result);
      } else {
        if (message.method === 'Runtime.executionContextCreated') {
          this.executionContexts.set(message.params.context.id, message.params.context);
        } else if (message.method === 'Runtime.executionContextDestroyed') {
          this.executionContexts.delete(message.params.executionContextId);
        } else if (message.method === 'Runtime.executionContextsCleared') {
          this.executionContexts.clear();
        }
        for (const handler of this.listeners.get(message.method) || []) handler(message.params);
      }
    });
  }
  static async connect(targetId = process.env.VSTAB_CDP_TARGET) {
    const deadline=Date.now()+90000;
    let target;
    while(!target && Date.now()<deadline){
      try {
        const port = process.env.VSTAB_CDP_PORT || '9227';
        const targets = await (await fetch('http://127.0.0.1:'+port+'/json/list')).json();
        const candidates=targets.filter(t => t.type === 'page' && (/^http:\/\/127\.0\.0\.[1-4]:/.test(t.url) || t.url.startsWith('http://localhost:') || t.url.startsWith('https://appassets.androidplatform.net/')));
        target=targetId ? candidates.find(t => t.id === targetId) : candidates.find(t=>{try{const d=JSON.parse(t.description);return d.visible&&d.attached;}catch{return false;}})||candidates[0];
      } catch {}
      if(!target)await new Promise(resolve=>setTimeout(resolve,250));
    }
    if (!target) throw new Error('No vstab workbench page found');
    const socket = new WebSocket(target.webSocketDebuggerUrl);
    await new Promise((resolve, reject) => {
      socket.addEventListener('open', resolve, {once:true});
      socket.addEventListener('error', reject, {once:true});
    });
    const client = new CDP(socket);
    client.target = target;
    await client.send('Runtime.enable');
    return client;
  }
  send(method, params={}) {
    const id = ++this.sequence;
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {this.pending.delete(id);reject(new Error('CDP timeout: '+method));},30000);
      this.pending.set(id,{resolve,reject,timeout});
      this.socket.send(JSON.stringify({id,method,params}));
    });
  }
  on(method, handler) {
    const list = this.listeners.get(method) || [];
    list.push(handler);this.listeners.set(method,list);
  }
  async evaluate(expression) {
    return this.evaluateInContext(expression);
  }
  async evaluateInContext(expression, contextId) {
    const options={expression,returnByValue:true,awaitPromise:true};
    if (contextId !== undefined) options.contextId=contextId;
    const result = await this.send('Runtime.evaluate',options);
    if (result.exceptionDetails) throw new Error(result.exceptionDetails.text+': '+(result.exceptionDetails.exception?.description || ''));
    return result.result.value;
  }
  contexts() {return [...this.executionContexts.values()];}
  async frameTree() {return (await this.send('Page.getFrameTree')).frameTree;}
  async elementPointInContext(elementExpression, contextId) {
    const local=await this.evaluateInContext(`(()=>{const e=(${elementExpression});if(!e)throw Error('Missing frame element');if(!e.checkVisibility({checkOpacity:true,checkVisibilityCSS:true}))throw Error('Frame element is hidden');const r=e.getBoundingClientRect();return{x:r.x+r.width/2,y:r.y+r.height/2,width:innerWidth,height:innerHeight};})()`,contextId);
    const frameId=this.executionContexts.get(contextId)?.auxData?.frameId;
    const tree=await this.frameTree();
    if(!frameId)throw new Error('Execution context has no frame');
    if(frameId===tree.frame.id)return{x:local.x,y:local.y};
    // CDP supplies the frame owner's content quad in main-page coordinates,
    // including ancestors. Webviews may deliberately hide window.parent.
    const owner=await this.send('DOM.getFrameOwner',{frameId});
    const {model}=await this.send('DOM.getBoxModel',{backendNodeId:owner.backendNodeId});
    const q=model.content,u=local.x/local.width,v=local.y/local.height;
    return{x:q[0]+u*(q[2]-q[0])+v*(q[6]-q[0]),y:q[1]+u*(q[3]-q[1])+v*(q[7]-q[1])};
  }
  async click(selector) {
    const point = await this.evaluate(`(()=>{const e=[...document.querySelectorAll(${JSON.stringify(selector)})].find(e=>{const r=e.getBoundingClientRect();return r.width>0&&r.height>0&&e.checkVisibility({checkVisibilityCSS:true});});if(!e)throw Error('Missing visible UI element');const r=e.getBoundingClientRect();return{x:r.x+r.width/2,y:r.y+r.height/2};})()`);
    await this.send('Input.dispatchMouseEvent',{type:'mousePressed',...point,button:'left',buttons:1,clickCount:1});
    await this.send('Input.dispatchMouseEvent',{type:'mouseReleased',...point,button:'left',buttons:0,clickCount:1});
  }
  async clickText(text, selector='button,a.monaco-button,[role="button"]') {
    const point=await this.evaluate(`(()=>{const e=[...document.querySelectorAll(${JSON.stringify(selector)})].find(e=>e.textContent.trim()===${JSON.stringify(text)}&&e.getBoundingClientRect().height>0&&e.checkVisibility({checkVisibilityCSS:true}));if(!e)throw Error('Missing visible UI text');const r=e.getBoundingClientRect();return{x:r.x+r.width/2,y:r.y+r.height/2};})()`);
    await this.send('Input.dispatchMouseEvent',{type:'mousePressed',...point,button:'left',buttons:1,clickCount:1});
    await this.send('Input.dispatchMouseEvent',{type:'mouseReleased',...point,button:'left',buttons:0,clickCount:1});
  }
  async key(key, code, windowsVirtualKeyCode, modifiers=0) {
    const params={key,code,windowsVirtualKeyCode,nativeVirtualKeyCode:windowsVirtualKeyCode,modifiers};
    await this.send('Input.dispatchKeyEvent',{type:'rawKeyDown',...params});
    await this.send('Input.dispatchKeyEvent',{type:'keyUp',...params});
  }
  async text(text) {await this.send('Input.insertText',{text});}
  async waitFor(expression, timeout=30000) {
    const deadline=Date.now()+timeout;
    while(Date.now()<deadline){if(await this.evaluate(expression))return;await new Promise(r=>setTimeout(r,250));}
    throw new Error('Timed out waiting for Android UI state');
  }
  close(){this.socket.close();}
}
module.exports={CDP};
