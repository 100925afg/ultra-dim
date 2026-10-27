// gh_release.js — 用 GitHub API 创建 Release 并上传资产（无需 gh CLI）。
// 适用场景：本机 git 的 TLS 不可用 / 没装 gh / 想一条命令完成「建 Release + 传 APK」。
//
// 用法：
//   $env:GH_TOKEN = "github_pat_xxx"        # 或 classic token（勾 repo）
//   node tools/gh_release.js --repo 100925afg/ultra-dim --tag v1.2 ^
//        --name "极暗 UltraDim v1.2" --notes-file release\RELEASE-NOTES-v1.2.md ^
//        --asset release\UltraDim-1.2.apk --asset release\SHA256SUMS.txt
//
// 说明：
//   * 只读 GH_TOKEN 环境变量，不写日志、不回显 token；
//   * Release 已存在时不会报错，改为补传缺失的资产（可重复执行）；
//   * 资产同名已存在则跳过。
"use strict";
const fs = require("fs");
const path = require("path");
const https = require("https");

const TOKEN = process.env.GH_TOKEN || process.env.GITHUB_TOKEN;
if (!TOKEN) {
  console.error("错误：请先设置环境变量 GH_TOKEN（GitHub Personal Access Token）");
  process.exit(2);
}

function parseArgs(argv) {
  const out = { assets: [] };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === "--asset") out.assets.push(argv[++i]);
    else if (a.startsWith("--")) out[a.slice(2)] = argv[++i];
  }
  return out;
}

function request(opts, body) {
  return new Promise((resolve, reject) => {
    const req = https.request(opts, (res) => {
      const chunks = [];
      res.on("data", (c) => chunks.push(c));
      res.on("end", () => {
        const text = Buffer.concat(chunks).toString("utf8");
        let json = null;
        try { json = JSON.parse(text); } catch (e) { /* 非 JSON */ }
        resolve({ status: res.statusCode, headers: res.headers, text, json });
      });
    });
    req.on("error", reject);
    req.setTimeout(180000, () => req.destroy(new Error("请求超时")));
    if (body) req.write(body);
    req.end();
  });
}

function api(method, apiPath, payload) {
  const body = payload ? Buffer.from(JSON.stringify(payload)) : null;
  return request({
    host: "api.github.com",
    port: 443,
    method,
    path: apiPath,
    headers: {
      "user-agent": "ultradim-gh-release",
      authorization: "token " + TOKEN,
      accept: "application/vnd.github+json",
      "content-type": "application/json",
      "content-length": body ? body.length : 0,
    },
  }, body);
}

function uploadAsset(uploadUrl, file) {
  const name = path.basename(file);
  const stat = fs.statSync(file);
  const url = new URL(uploadUrl.replace("{?name,label}", "") + "?name=" + encodeURIComponent(name));
  return new Promise((resolve, reject) => {
    const req = https.request({
      host: url.host,
      port: 443,
      method: "POST",
      path: url.pathname + url.search,
      headers: {
        "user-agent": "ultradim-gh-release",
        authorization: "token " + TOKEN,
        accept: "application/vnd.github+json",
        "content-type": "application/octet-stream",
        "content-length": stat.size,
      },
    }, (res) => {
      const chunks = [];
      res.on("data", (c) => chunks.push(c));
      res.on("end", () => {
        let json = null;
        try { json = JSON.parse(Buffer.concat(chunks).toString("utf8")); } catch (e) { /* ignore */ }
        resolve({ status: res.statusCode, json });
      });
    });
    req.on("error", reject);
    req.setTimeout(300000, () => req.destroy(new Error("上传超时")));
    fs.createReadStream(file).pipe(req);
  });
}

(async () => {
  const args = parseArgs(process.argv.slice(2));
  if (!args.repo || !args.tag) {
    console.error("用法: node tools/gh_release.js --repo owner/repo --tag v1.2 [--name 标题] [--notes-file 文件] [--asset 文件 ...]");
    process.exit(2);
  }
  let body = "";
  if (args["notes-file"]) body = fs.readFileSync(args["notes-file"], "utf8");

  let rel = null;
  const created = await api("POST", "/repos/" + args.repo + "/releases", {
    tag_name: args.tag,
    target_commitish: args["target-commitish"] || "main",
    name: args.name || args.tag,
    body,
    draft: false,
    prerelease: false,
  });
  if (created.status === 201) {
    rel = created.json;
    console.log("已创建 Release: " + rel.html_url);
  } else if (created.status === 422 && created.json && /already_exists/i.test(JSON.stringify(created.json))) {
    const got = await api("GET", "/repos/" + args.repo + "/releases/tags/" + encodeURIComponent(args.tag));
    rel = got.json;
    console.log("Release 已存在，复用: " + rel.html_url);
  } else {
    console.error("创建 Release 失败: HTTP " + created.status + " " + created.text.slice(0, 500));
    process.exit(1);
  }

  const existing = new Set(((rel.assets || []).map ? rel.assets : []).map((a) => a.name));
  for (const file of args.assets) {
    const name = path.basename(file);
    if (existing.has(name)) { console.log("跳过已存在资产: " + name); continue; }
    const up = await uploadAsset(rel.upload_url, file);
    if (up.status === 201) console.log("已上传: " + name + " (" + fs.statSync(file).size + " B)");
    else console.error("上传失败 " + name + ": HTTP " + up.status);
  }
  console.log("完成: " + rel.html_url);
})().catch((e) => {
  console.error("失败: " + e.message);
  process.exit(1);
});
