/***************************************************************
 * Apifox / Postman 前置脚本 —— ShenYu Pay RSA 加签（PayRsaSignService 配套）
 * 双实现版：require("crypto") 优先，不可用时自动回退 require("jsrsasign")
 *
 * 适用场景：部分 Apifox 版本沙箱不支持 Node 内置 crypto，此时本脚本会
 * 自动切到 jsrsasign 继续加签，避免脚本直接报错。
 *
 * 签名串（5 行，每行以 \n 结尾，与网关 PayRsaSignService.buildSignString 完全一致）：
 *   METHOD\n       ← 强制大写（网关 request.getMethodValue().toUpperCase()）
 *   URL\n          ← 带 contextPath 的原始路径，GET 含 query string（path?query）
 *   TIMESTAMP\n    ← epoch 毫秒（网关 ±300s 容差）
 *   NONCE\n
 *   BODY\n         ← GET 强制空串；其余取 raw body（网关 signatureVerify(exchange,"")）
 *
 * 签名算法：SHA256withRSA，结果 Base64 → 写入请求头 X-Pay-Sign
 *
 * 用法：
 *   1) 在 Apifox「环境」里配置两个变量：
 *        biz_private_key : 私钥 PEM 全文（PKCS#8，-----BEGIN PRIVATE KEY----- 开头）
 *                         （若在环境里写成单行带 \n 转义，脚本会自动还原换行）
 *        app_key         : 业务方 appKey，如 biz001 / biz002
 *   2) 把本脚本贴到「前置脚本」（建议贴在目录/项目级，所有请求自动继承）
 *   3) 直接发请求，4 个 X-Pay-* 头会自动注入
 ***************************************************************/

// ===== 1. 读取配置 =====
let privateKey = pm.environment.get("biz_private_key");
const appKey = pm.environment.get("app_key") || "biz001";

if (privateKey) {
  // 兜底：把环境变量里用 \n 转义写的 PEM 还原成真实换行
  privateKey = privateKey.replace(/\\n/g, "\n");
}
if (!privateKey) {
  console.error("[PaySign] 未配置私钥：请设置环境变量 biz_private_key，或在脚本中粘贴私钥");
  throw new Error("biz_private_key missing");
}

// ===== 2. 选择加签引擎：crypto 优先，jsrsasign 兜底 =====
let cryptoEngine = null;   // 加签引擎（Node crypto 或 jsrsasign）
let useNodeCrypto = false; // 引擎类型标记

try {
  cryptoEngine = require("crypto");
  // 仅当真正暴露 createSign 时才视为可用（防止部分沙箱返回桩对象）
  if (cryptoEngine && typeof cryptoEngine.createSign === "function") {
    useNodeCrypto = true;
  } else {
    cryptoEngine = null;
  }
} catch (e1) {
  cryptoEngine = null;
}

if (!useNodeCrypto) {
  try {
    cryptoEngine = require("jsrsasign");
  } catch (e2) {
    console.error("[PaySign] 当前 Apifox 既不支持 require('crypto') 也不支持 require('jsrsasign')，无法加签");
    throw new Error("no crypto engine available");
  }
}
console.log("[PaySign] engine =", useNodeCrypto ? "node:crypto" : "jsrsasign");

// ===== 3. 准备待签名参数（与网关 buildSignString 严格对齐）=====
const method = pm.request.method.toUpperCase(); // ← bug 修正：强制大写

// 路径：带 contextPath；GET 含 query string，与网关 buildRequestUrl 一致
const path = pm.request.url.getPath();
const query = pm.request.url.getQueryString();
const url = query ? path + "?" + query : path;   // ← bug 修正：拼接 query

const timestamp = Date.now().toString();          // epoch 毫秒

// nonce：两种引擎下都生成 32 位 hex
function randHex(n) {
  let s = "";
  const c = "0123456789abcdef";
  for (let i = 0; i < n; i++) s += c[Math.floor(Math.random() * 16)];
  return s;
}
const nonce = useNodeCrypto
  ? cryptoEngine.randomBytes(16).toString("hex")
  : randHex(32);

// body：GET 强制空串（网关对 GET 同样传空串）；其余取 raw body
let body = "";
if (method !== "GET" && pm.request.body && pm.request.body.raw) {
  body = pm.request.body.raw;
}

// ===== 4. 构造签名串并签名 =====
const signStr = method + "\n" + url + "\n" + timestamp + "\n" + nonce + "\n" + body + "\n";

let signature;
if (useNodeCrypto) {
  // 分支 A：Node 内置 crypto
  const signer = cryptoEngine.createSign("RSA-SHA256");
  signer.update(signStr, "utf8");
  signature = signer.sign(privateKey, "base64");
} else {
  // 分支 B：jsrsasign（hex → base64）
  const sig = new cryptoEngine.KJUR.crypto.Signature({ alg: "SHA256withRSA" });
  sig.init(privateKey);
  sig.updateString(signStr);
  signature = cryptoEngine.hextob64(sig.sign());
}

// ===== 5. 写回请求头 =====
pm.request.headers.upsert({ key: "X-Pay-App-Key", value: appKey });
pm.request.headers.upsert({ key: "X-Pay-Timestamp", value: timestamp });
pm.request.headers.upsert({ key: "X-Pay-Nonce", value: nonce });
pm.request.headers.upsert({ key: "X-Pay-Sign", value: signature });

// 调试信息（可在 Apifox 控制台查看）
console.log("[PaySign] method =", method, "| url =", url);
console.log("[PaySign] signStr =\n" + signStr);
console.log("[PaySign] signature =", signature);
