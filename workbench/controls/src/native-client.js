'use strict';

// No request payloads are logged: prompt replies can contain session secrets.
class NativeClient {
  constructor(context) {
    this.protocol = context.messagePassingProtocol;
    if (!this.protocol) throw new Error('The native Remote SSH channel is unavailable. Restart vstab.');
    this.sequence = 0; this.pending = new Map(); this.listeners = new Set();
    this.subscription = this.protocol.onDidReceiveMessage(message => {
      if (!message || message.v !== 1) return;
      if (message.event) { for (const listener of this.listeners) listener(message); return; }
      const call = this.pending.get(message.id);
      if (!call) return;
      this.pending.delete(message.id); clearTimeout(call.timer);
      if (message.ok) call.resolve(message.result);
      else call.reject(Object.assign(new Error(message.error?.message || 'The SSH operation failed.'),
        {code: message.error?.code || 'Unavailable'}));
    });
  }
  request(method, params = {}) {
    if (this.pending.size >= 64) return Promise.reject(new Error('Too many pending SSH operations.'));
    const id = ++this.sequence;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { this.pending.delete(id);
        reject(Object.assign(new Error('The native Android controller did not respond.'), {code: 'Unavailable'})); }, 120000);
      this.pending.set(id, {resolve, reject, timer});
      try { this.protocol.postMessage({v: 1, id, method, params}); }
      catch (error) { clearTimeout(timer); this.pending.delete(id); reject(error); }
    });
  }
  onEvent(listener) { this.listeners.add(listener); return {dispose: () => this.listeners.delete(listener)}; }
  dispose() {
    this.subscription.dispose();
    for (const call of this.pending.values()) { clearTimeout(call.timer); call.reject(new Error('The SSH window was closed.')); }
    this.pending.clear(); this.listeners.clear();
  }
}
exports.NativeClient = NativeClient;
