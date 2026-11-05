'use strict';
// 同Windows信頼済み自己run専用。residentIdはOS本人認証ではない。
// 資格情報はprivate envとpipeメモリだけ。例外objectをログに渡さない。
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const net = require('node:net');
const { randomUUID } = require('node:crypto');
const { TextDecoder } = require('node:util');
const LIMIT = 16384;
const TIMEOUT = 10000;
const CODES = ['system', 'admin-de', 'member'];
const UUID = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
const object = v => v !== null && typeof v === 'object' && !Array.isArray(v);
const keys = (v, names) => object(v) && Object.keys(v).length === names.length && names.every(n => Object.hasOwn(v, n));
const requireValue = ok => { if (!ok) throw new Error('M21_INVALID'); };
const normalized = v => process.platform === 'win32' ? path.resolve(v).toLowerCase() : path.resolve(v);
const text = v => typeof v === 'string' && v.length > 0 && v.length <= 4096 && !/[\x00-\x1f\x7f]/.test(v);
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
async function main() {
  requireValue(process.argv.length === 2 && process.platform === 'win32');
  const dir = fs.realpathSync(process.env.M21_RUN_DIRECTORY || '');
  const runId = path.basename(dir);
  requireValue(normalized(path.dirname(dir)) === normalized(fs.realpathSync(os.tmpdir())) && runId.startsWith('m21-') && UUID.test(runId.slice(4)));
  const raw = process.env.M21_ACTORS_JSON;
  requireValue(typeof raw === 'string' && Buffer.byteLength(raw) <= LIMIT);
  const actors = JSON.parse(raw); delete process.env.M21_ACTORS_JSON;
  requireValue(Array.isArray(actors) && actors.length === 3);
  const byCode = new Map();
  for (const actor of actors) {
    requireValue(keys(actor, ['code', 'userId', 'email', 'password', 'locale']) && CODES.includes(actor.code) && !byCode.has(actor.code));
    requireValue(Number.isSafeInteger(actor.userId) && actor.userId > 0 && text(actor.email) && text(actor.password) && text(actor.locale));
    requireValue(actor.email.includes('@') && actor.locale === (actor.code === 'admin-de' ? 'de' : 'ja'));
    byCode.set(actor.code, actor);
  }
  const ownerPath = path.join(dir, 'resident-helper-owner.json');
  const ownerDeadline = Date.now() + TIMEOUT;
  let owner;
  while (!owner) {
    try {
      const data = fs.readFileSync(ownerPath); requireValue(data.length <= LIMIT);
      owner = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(data));
    } catch (error) {
      if (error.code !== 'ENOENT' || Date.now() >= ownerDeadline) throw new Error('M21_OWNER');
      await pause(20);
    }
  }
  requireValue(keys(owner, ['pid', 'creation', 'runId', 'executable']) && owner.pid === process.pid && owner.runId === runId && text(owner.creation) && Number.isFinite(Date.parse(owner.creation)) && text(owner.executable) && normalized(owner.executable) === normalized(process.execPath));
  const pipe = `\\\\.\\pipe\\m21-${randomUUID()}`;
  const state = { runId, servedCodes: [], pid: process.pid, failed: false };
  const sockets = new Set(), claimed = new Set(), residents = new Set(), requests = new Set();
  function atomic(name, value) {
    const data = Buffer.from(JSON.stringify(value)); requireValue(data.length <= LIMIT);
    const target = path.join(dir, name);
    function diagnostic(stage, error) {
      const allowed = ['EPERM', 'EACCES', 'EBUSY', 'ENOENT', 'ENOSPC', 'EIO', 'EINVAL', 'ENOTDIR', 'EXDEV', 'EMFILE', 'ENFILE'];
      const code = allowed.includes(error?.code) ? error.code : 'UNKNOWN';
      process.stderr.write(`M21_ATOMIC_${stage}_${code}\n`);
      throw new Error('M21_ATOMIC_FAILED');
    }
    try { fs.writeFileSync(`${target}.next`, data, { mode: 0o600 }); }
    catch (error) { diagnostic('WRITE', error); }
    try { fs.renameSync(`${target}.next`, target); }
    catch (error) { diagnostic('RENAME', error); }
  }
  const save = () => atomic('exploration-helper-state.json', state);
  function fail() {
    if (!state.failed) {
      state.failed = true;
      try { save(); } catch { process.stderr.write('M21_STATE_FAILED\n'); process.exitCode = 1; server.close(); }
    }
    for (const socket of sockets) socket.destroy();
  }
  const server = net.createServer(socket => {
    sockets.add(socket);
    if (state.failed || sockets.size > 3) { fail(); return; }
    let pending = Buffer.alloc(0), phase = 'claim', claim;
    const timer = setTimeout(fail, TIMEOUT);
    function send(value, done) {
      socket.write(`${JSON.stringify(value)}\n`, error => { if (error) fail(); else if (done) done(); });
    }
    function receive(message) {
      requireValue(keys(message, ['op', 'runId', 'actorCode', 'requestId', 'residentId']) && message.runId === runId && CODES.includes(message.actorCode) && typeof message.requestId === 'string' && UUID.test(message.requestId) && typeof message.residentId === 'string' && UUID.test(message.residentId));
      if (phase === 'claim') {
        requireValue(message.op === 'claim-actor' && !claimed.has(message.actorCode) && !residents.has(message.residentId) && !requests.has(message.requestId));
        claimed.add(message.actorCode); residents.add(message.residentId); requests.add(message.requestId);
        claim = message; phase = 'sending';
        send({ ...claim, op: 'actor', actor: byCode.get(claim.actorCode) }, () => { if (phase === 'sending') phase = 'ack'; });
      } else {
        requireValue(phase === 'ack' && message.op === 'ack-delivered' && ['runId', 'actorCode', 'requestId', 'residentId'].every(key => message[key] === claim[key]));
        // 完全なack受信後に同期atomic保存。ack後正常closeとの競合で戻さない。
        phase = 'delivered';
        state.servedCodes = CODES.filter(code => code === claim.actorCode || state.servedCodes.includes(code));
        save(); send({ ...claim, op: 'delivered' }); clearTimeout(timer);
      }
    }
    socket.on('data', chunk => {
      if (state.failed) return;
      if (phase === 'delivered') { fail(); return; }
      try {
        // frame単位のバイト上限。改行後の正規ackとtrailing garbageを区別する。
        let start = 0;
        while (start < chunk.length) {
          const newline = chunk.indexOf(10, start), end = newline === -1 ? chunk.length : newline;
          requireValue(pending.length + end - start <= LIMIT);
          pending = Buffer.concat([pending, chunk.subarray(start, end)]);
          if (newline === -1) break;
          const message = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(pending));
          pending = Buffer.alloc(0); receive(message); start = newline + 1;
          if (phase === 'delivered' && start < chunk.length) { fail(); return; }
        }
      } catch { fail(); }
    });
    socket.on('error', fail);
    socket.on('end', () => { if (phase !== 'delivered' || pending.length) fail(); });
    socket.on('close', () => { clearTimeout(timer); sockets.delete(socket); if (phase !== 'delivered' && !state.failed) fail(); });
  });
  server.on('error', () => { fail(); process.stderr.write('M21_PIPE_FAILED\n'); process.exitCode = 1; server.close(); });
  save();
  server.listen(pipe, () => {
    try { atomic('exploration-ready.json', { runId, actorCodes: CODES, pid: process.pid, creation: owner.creation, pipe }); }
    catch { fail(); server.close(); process.stderr.write('M21_READY_FAILED\n'); process.exitCode = 1; }
  });
}
main().catch(() => { process.stderr.write('M21_STARTUP_FAILED\n'); process.exitCode = 1; });
