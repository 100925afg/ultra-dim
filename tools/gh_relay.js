// gh_relay.js — 本机 HTTPS 直连可用、但 Git 的 Schannel TLS 在本环境不可用时使用。
// 作用：监听 127.0.0.1 上的明文 HTTP，把请求用 Node 的 OpenSSL 以 HTTPS 转发到 github.com，
// 再把响应流式回传。于是 `git push http://127.0.0.1:8848/<owner>/<repo>.git` 就能工作。
//
// 用法： node tools/gh_relay.js [port] [targetHost]
// 默认 127.0.0.1:8848 → github.com
"use strict";
const http = require("http");
const https = require("https");

const PORT = parseInt(process.argv[2] || "8848", 10);
const TARGET = process.argv[3] || "github.com";

const agent = new https.Agent({ keepAlive: false });

function forward(req, res) {
  const headers = Object.assign({}, req.headers);
  delete headers.host;
  delete headers["proxy-connection"];
  headers.host = TARGET;
  headers["user-agent"] = headers["user-agent"] || "git/gh-relay";

  const up = https.request(
    {
      host: TARGET,
      port: 443,
      method: req.method,
      path: req.url,
      headers: headers,
      agent: agent,
      timeout: 120000,
    },
    (ur) => {
      res.writeHead(ur.statusCode || 502, ur.headers);
      ur.pipe(res);
    }
  );
  up.on("timeout", () => up.destroy(new Error("upstream timeout")));
  up.on("error", (e) => {
    process.stderr.write("[relay] upstream error " + req.method + " " + req.url + ": " + e.message + "\n");
    if (!res.headersSent) {
      res.writeHead(502, { "content-type": "text/plain" });
    }
    res.end("relay upstream error: " + e.message);
  });
  req.pipe(up);
  req.on("error", () => up.destroy());
}

const server = http.createServer((req, res) => {
  process.stderr.write("[relay] " + req.method + " " + req.url + "\n");
  forward(req, res);
});

// git 可能用 Expect: 100-continue 发大包
server.on("checkContinue", (req, res) => {
  res.writeContinue();
  process.stderr.write("[relay] " + req.method + " " + req.url + " (continue)\n");
  forward(req, res);
});

server.listen(PORT, "127.0.0.1", () => {
  process.stderr.write("[relay] listening on http://127.0.0.1:" + PORT + " -> https://" + TARGET + "\n");
});
