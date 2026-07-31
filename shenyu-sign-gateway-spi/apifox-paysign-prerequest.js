/***************************************************************
 * Apifox / Postman 前置脚本 —— ShenYu Pay RSA 加签（PayRsaSignService 配套）
 *
 * 签名串（5 行，每行以 \n 结尾，与网关 buildSignString 完全一致）：
 *   METHOD\n
 *   URL\n          ← 带 contextPath 的原始路径，GET 含 query string
 *   TIMESTAMP\n    ← epoch 毫秒（±300s 容差）
 *   NONCE\n
 *   BODY\n         ← GET 强制空串；其余取 raw body
 *
 * 签名算法：SHA256withRSA，结果 Base64 → 写入请求头 X-Pay-Sign
 *
 * 用法：
 *   1) 在 Apifox 的「环境」里加两个变量：
 *        biz_private_key : 私钥 PEM 全文（PKCS#8，-----BEGIN PRIVATE KEY----- 开头）
 *        app_key         : 业务方 appKey，如 biz001 / biz002
 *   2) 把本脚本贴到「前置脚本」（建议贴在目录/项目级，所有请求自动继承）
 *   3) 直接发请求即可，4 个 X-Pay-* 头会自动注入
 ***************************************************************/

// ===== 1. 读取配置 =====
// 私钥（PKCS#8 PEM）。优先取环境变量；未配置时取消下面注释直接粘贴。
let privateKey = pm.environment.get("biz_private_key");
// privateKey = `-----BEGIN PRIVATE KEY-----
// MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQDD4bFkgJTc5L45
// ...（完整私钥内容，含结尾 -----END PRIVATE KEY-----）
// -----END PRIVATE KEY-----`;

// appKey
const appKey = pm.environment.get("app_key") || "006";

// 兜底：把环境变量里用 \n 转义写的 PEM 还原成真实换行
if (privateKey) {
  privateKey = privateKey.replace(/\\n/g, "\n");
}

if (!privateKey) {
  console.error("[PaySign] 未配置私钥：请设置环境变量 biz_private_key，或在脚本中粘贴私钥");
  throw new Error("biz_private_key missing");
}

// ===== 2. 准备待签名参数 =====
const crypto = require("crypto");

const method = pm.request.method.toUpperCase();

// 路径：带 contextPath；GET 含 query string，与网关 request.getURI() 一致
const path = pm.request.url.getPath();
const query = pm.request.url.getQueryString();
const url = query ? path + "?" + query : path;

const timestamp = Date.now().toString();          // epoch 毫秒
const nonce = crypto.randomBytes(16).toString("hex");

// body：GET 强制空串；其余取 raw body（网关对 GET 同样传空串）
let body = "";
if (method !== "GET" && pm.request.body && pm.request.body.raw) {
  body = pm.request.body.raw;
}

// ===== 3. 构造签名串并签名 =====
const signStr = method + "\n" + url + "\n" + timestamp + "\n" + nonce + "\n" + body + "\n";

const signer = crypto.createSign("RSA-SHA256");
signer.update(signStr, "utf8");
const signature = signer.sign(privateKey, "base64");

// ===== 4. 写回请求头 =====
pm.request.headers.upsert({ key: "X-Pay-App-Key", value: appKey });
pm.request.headers.upsert({ key: "X-Pay-Timestamp", value: timestamp });
pm.request.headers.upsert({ key: "X-Pay-Nonce", value: nonce });
pm.request.headers.upsert({ key: "X-Pay-Sign", value: signature });

// 调试信息（可在 Apifox 控制台查看）
console.log("[PaySign] method =", method, "| url =", url);
console.log("[PaySign] signStr =\n" + signStr);
console.log("[PaySign] signature =", signature);

/***************************************************************
 * —— 备用方案：若你的 Apifox 版本不支持 require("crypto") ——
 * 改用 jsrsasign（需 Apifox 支持 require("jsrsasign")，否则联系管理员）：
 *
 *   const jsrsasign = require("jsrsasign");
 *   const method = pm.request.method.toUpperCase();
 *   const path = pm.request.url.getPath();
 *   const query = pm.request.url.getQueryString();
 *   const url = query ? path + "?" + query : path;
 *   const timestamp = Date.now().toString();
 *   const nonce = ("" + Math.random()).slice(2) + ("" + Math.random()).slice(2);
 *   let body = "";
 *   if (method !== "GET" && pm.request.body && pm.request.body.raw) body = pm.request.body.raw;
 *   const signStr = method + "\n" + url + "\n" + timestamp + "\n" + nonce + "\n" + body + "\n";
 *   const privateKey = pm.environment.get("biz_private_key").replace(/\\n/g, "\n");
 *   const sig = new jsrsasign.KJUR.crypto.Signature({ alg: "SHA256withRSA" });
 *   sig.init(privateKey);
 *   sig.updateString(signStr);
 *   const signature = jsrsasign.hextob64(sig.sign());
 *   pm.request.headers.upsert({ key: "X-Pay-App-Key", value: pm.environment.get("app_key") || "biz001" });
 *   pm.request.headers.upsert({ key: "X-Pay-Timestamp", value: timestamp });
 *   pm.request.headers.upsert({ key: "X-Pay-Nonce", value: nonce });
 *   pm.request.headers.upsert({ key: "X-Pay-Sign", value: signature });
 ***************************************************************/
