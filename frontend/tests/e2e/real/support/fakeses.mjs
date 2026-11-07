// 隔離環境用の偽 SES 受信箱。SES v2 SendEmail を捕捉し GET /inbox で JSON を返す（:4599）。起動: node tests/e2e/real/support/fakeses.mjs
// @ts-nocheck
import http from 'node:http'
const box = []
http.createServer((req, res) => {
  if (req.method === 'GET' && req.url.startsWith('/inbox')) { res.setHeader('content-type','application/json'); return res.end(JSON.stringify(box)) }
  let b = ''
  req.on('data', d => b += d)
  req.on('end', () => {
    try { const j = JSON.parse(b); box.push({ at: new Date().toISOString(), to: j.Destination?.ToAddresses, subject: j.Content?.Simple?.Subject?.Data, html: j.Content?.Simple?.Body?.Html?.Data }) } catch (e) { console.log('parse', e.message, b.slice(0,200)) }
    res.setHeader('content-type','application/json'); res.end(JSON.stringify({ MessageId: 'fake-' + box.length }))
  })
}).listen(4599, '0.0.0.0', () => console.log('fake ses up'))
