'use strict';
const dgram = require('node:dgram');
const net = require('node:net');
const http = require('node:http');
const MAX_PACKET = 65535, MAX_JSON = 90000;
function servfail(query) {
  let end = 12, questions = 0;
  if (query.length >= 12) {
    const count = query.readUInt16BE(4);
    if (count <= 16) {
      try {
        for (let n = 0; n < count; n++) {
          let labels = 0;
          while (true) {
            if (end >= query.length || labels++ > 128) throw Error();
            const length = query[end++];
            if ((length & 0xc0) === 0xc0) { if (end >= query.length) throw Error(); end++; break; }
            if (length > 63 || end + length > query.length) throw Error();
            if (!length) break;
            end += length;
          }
          if (end + 4 > query.length) throw Error(); end += 4;
        }
        questions = count;
      } catch { end = 12; }
    }
  }
  const reply = Buffer.alloc(end);
  query.copy(reply, 0, 0, Math.min(query.length, end));
  reply.writeUInt16BE((query.length >= 4 ? query.readUInt16BE(2) & 0x7910 : 0) | 0x8082, 2);
  reply.writeUInt16BE(questions, 4); reply.fill(0, 6, 12);
  return reply;
}
async function startDnsRelay({control, listenHost = '127.0.0.53', port = 53, timeoutMs = 12000, maxInflight = 64} = {}) {
  const udp = dgram.createSocket('udp4'), connections = new Set(), requests = new Set();
  let closing = false, inflight = 0;
  function forward(query) {
    if (closing || query.length < 12 || query.length > MAX_PACKET || inflight >= maxInflight
        || !control || !['10.0.2.2', '127.0.0.1'].includes(control.host)
        || !Number.isInteger(control.port) || control.port < 1 || control.port > 65535 || !/^[a-f0-9]{64}$/.test(control.token || '')) return Promise.resolve(servfail(query));
    inflight++;
    return new Promise(resolve => {
      let complete = false, timer, request;
      const finish = value => {
        if (complete) return; complete = true; clearTimeout(timer); inflight--;
        requests.delete(request); resolve(value || servfail(query));
      };
      const body = Buffer.from(JSON.stringify({message: query.toString('base64')}));
      request = http.request({host: control.host, port: control.port, path: '/dns', method: 'POST',
        headers: {'Content-Type': 'application/json', 'Content-Length': body.length, Authorization: 'Bearer ' + control.token}}, response => {
        const parts = []; let length = 0;
        response.on('data', chunk => { length += chunk.length; if (length > MAX_JSON) { finish(); response.destroy(); } else parts.push(chunk); });
        response.on('error', () => finish()); response.on('aborted', () => finish());
        response.on('end', () => {
          if (response.statusCode !== 200 || complete) return finish();
          try {
            const encoded = JSON.parse(Buffer.concat(parts).toString('utf8')).message;
            if (typeof encoded !== 'string' || encoded.length > 87380 || !/^[A-Za-z0-9+/]+={0,2}$/.test(encoded)) return finish();
            const reply = Buffer.from(encoded, 'base64');
            if (reply.length < 12 || reply.length > MAX_PACKET || reply.readUInt16BE(0) !== query.readUInt16BE(0) || !(reply.readUInt16BE(2) & 0x8000)) return finish();
            finish(reply);
          } catch { finish(); }
        });
      });
      requests.add(request);
      request.on('error', () => finish());
      timer = setTimeout(() => { finish(); request.destroy(); }, timeoutMs);
      request.end(body);
    });
  }
  udp.on('error', () => {});
  udp.on('message', (query, peer) => { void forward(query).then(reply => {
    if (!closing) udp.send(reply, peer.port, peer.address, () => {});
  }); });
  const tcp = net.createServer({allowHalfOpen: true}, socket => {
    if (connections.size >= 32) { socket.destroy(); return; }
    connections.add(socket); let buffered = Buffer.alloc(0), pending = 0, ended = false;
    socket.setTimeout(timeoutMs * 2, () => socket.destroy());
    socket.on('error', () => {}); socket.on('close', () => connections.delete(socket));
    socket.on('end', () => { ended = true; if (buffered.length || !pending) socket.end(); });
    socket.on('data', data => {
      buffered = Buffer.concat([buffered, data]);
      if (buffered.length > 4 * (MAX_PACKET + 2)) { socket.destroy(); return; }
      while (buffered.length >= 2) {
        const length = buffered.readUInt16BE(0);
        if (length < 12) { socket.destroy(); return; }
        if (buffered.length < length + 2) break;
        const query = Buffer.from(buffered.subarray(2, length + 2)); buffered = buffered.subarray(length + 2);
        const answer = pending >= 16 ? Promise.resolve(servfail(query)) : forward(query); pending++;
        void answer.then(reply => {
          pending--;
          if (!socket.destroyed) {
            const frame = Buffer.alloc(reply.length + 2); frame.writeUInt16BE(reply.length); reply.copy(frame, 2);
            if (socket.writableLength > 4 * (MAX_PACKET + 2)) socket.destroy(); else socket.write(frame);
            if (ended && !pending) socket.end();
          }
        });
      }
    });
  });
  tcp.on('error', () => {});
  try {
    await new Promise((resolve, reject) => { udp.once('error', reject); udp.bind(port, listenHost, () => { udp.removeListener('error', reject); resolve(); }); });
    await new Promise((resolve, reject) => { tcp.once('error', reject); tcp.listen(udp.address().port, listenHost, () => { tcp.removeListener('error', reject); resolve(); }); });
  } catch (error) { try { udp.close(); } catch {} try { tcp.close(); } catch {} throw error; }
  return {address: () => udp.address(), close: async () => {
    if (closing) return; closing = true;
    for (const request of requests) request.destroy();
    for (const socket of connections) socket.destroy();
    await Promise.all([new Promise(resolve => { try { udp.close(resolve); } catch { resolve(); } }),
      new Promise(resolve => { try { tcp.close(resolve); } catch { resolve(); } })]);
  }};
}
module.exports = {startDnsRelay, servfail};
