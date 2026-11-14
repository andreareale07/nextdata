// TLS front for the test WebDAV server. usage: node tls_proxy.js <port> <target-port> <cert> <key>
const https = require('https'), http = require('http'), fs = require('fs');
const [port, target, cert, key] = [parseInt(process.argv[2]), parseInt(process.argv[3]), process.argv[4], process.argv[5]];
https.createServer({ cert: fs.readFileSync(cert), key: fs.readFileSync(key) }, (req, res) => {
  const p = http.request({ host: '127.0.0.1', port: target, method: req.method, path: req.url, headers: req.headers }, (r) => {
    res.writeHead(r.statusCode, r.headers); r.pipe(res);
  });
  p.on('error', () => { res.statusCode = 502; res.end(); });
  req.pipe(p);
}).listen(port, '127.0.0.1', () => console.log('ready'));
