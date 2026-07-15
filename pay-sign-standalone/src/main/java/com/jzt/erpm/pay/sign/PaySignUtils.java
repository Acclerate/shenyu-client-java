package com.jzt.erpm.pay.sign;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * 支付请求加签工具类.
 *
 * <p>纯 JDK 实现，不依赖 Spring，提供以下核心功能：
 * <ul>
 *   <li>构造 5 行请求签名串</li>
 *   <li>SHA256withRSA 签名</li>
 *   <li>时间戳和随机串生成</li>
 *   <li>PKCS#8 私钥加载</li>
 * </ul>
 *
 * <p>业务系统推荐使用 {@link PayRequestSigner}（Spring Bean 风格）配合
 * {@link PaySignInterceptor}（自动拦截）进行加签。
 */
public final class PaySignUtils {

    private static final String SIGN_ALGORITHM = "SHA256withRSA";

    private PaySignUtils() {
    }

    /**
     * 构造 5 行请求签名串.
     *
     * <pre>
     * HTTP请求方法\n
     * URL\n
     * 请求时间戳\n
     * 请求随机串\n
     * 请求报文主体\n
     * </pre>
     *
     * @param method    HTTP 请求方法（POST / GET）
     * @param url       请求 URL 绝对路径，GET 须含完整 query string
     * @param timestamp 请求时间戳（毫秒级字符串）
     * @param nonce     请求随机串
     * @param body      请求报文主体；GET 传 {@code null} 或空串
     * @return 待签名串，5 行均以 {@code \n} 结尾
     */
    public static String buildRequestSignString(String method, String url, String timestamp,
                                                String nonce, String body) {
        String safeBody = body == null ? "" : body;
        StringBuilder sb = new StringBuilder(64 + safeBody.length());
        sb.append(method).append('\n')
                .append(url).append('\n')
                .append(timestamp).append('\n')
                .append(nonce).append('\n')
                .append(safeBody).append('\n');
        return sb.toString();
    }

    /**
     * 使用私钥对待签名串做 SHA256withRSA 加签.
     *
     * @param signString 待签名串
     * @param privateKey 业务系统私钥（PKCS#8 格式）
     * @return Base64 编码的签名值
     * @throws IllegalStateException 加签失败
     */
    public static String sign(String signString, PrivateKey privateKey) {
        try {
            Signature signature = Signature.getInstance(SIGN_ALGORITHM);
            signature.initSign(privateKey);
            signature.update(signString.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException("支付请求加签失败", e);
        }
    }

    /**
     * 使用公钥对待签名串与签名值做 SHA256withRSA 验签.
     *
     * <p>业务方收到支付服务的响应/回调时，用<b>支付方公钥</b>验签。
     * 注意：验签不通过返回 {@code false}（签名不匹配），而非抛异常；
     * 仅在签名值非法、算法不支持等异常时抛 {@link IllegalStateException}。
     *
     * @param signString     待签名串（请求 5 行或响应 3 行，由调用方构造）
     * @param base64Sign     Base64 编码的签名值
     * @param publicKey      验签公钥（响应验签用支付方公钥）
     * @return {@code true} 验签通过；{@code false} 签名不匹配
     * @throws IllegalStateException 签名值非法或算法不支持
     */
    public static boolean verify(String signString, String base64Sign, PublicKey publicKey) {
        try {
            byte[] signBytes = Base64.getDecoder().decode(base64Sign);
            Signature signature = Signature.getInstance(SIGN_ALGORITHM);
            signature.initVerify(publicKey);
            signature.update(signString.getBytes(StandardCharsets.UTF_8));
            return signature.verify(signBytes);
        } catch (Exception e) {
            throw new IllegalStateException("支付响应验签失败", e);
        }
    }

    /**
     * 构造<b>响应/回调验签</b>的 3 行待签名串.
     *
     * <p>支付服务返回响应或推送回调时用支付方私钥加签，签名串为：
     * <pre>
     * 应答时间戳\n
     * 应答随机串\n
     * 应答报文主体\n
     * </pre>
     * 每行（含最后一行）以 {@code \n} 结尾。报文主体为空时最后一行仍保留一个 {@code \n}。
     *
     * @param timestamp 应答/回调时间戳
     * @param nonce     应答/回调随机串
     * @param body      应答/回调报文主体；为 {@code null} 视为空串
     * @return 待签名串，3 行均以 {@code \n} 结尾
     */
    public static String buildResponseSignString(String timestamp, String nonce, String body) {
        String safeBody = body == null ? "" : body;
        StringBuilder sb = new StringBuilder(64 + safeBody.length());
        sb.append(timestamp).append('\n')
                .append(nonce).append('\n')
                .append(safeBody).append('\n');
        return sb.toString();
    }

    /**
     * 生成毫秒级时间戳字符串.
     *
     * @return 毫秒级时间戳字符串
     */
    public static String newTimestamp() {
        return String.valueOf(System.currentTimeMillis());
    }

    /**
     * 生成 32 位 hex 随机串（nonce）.
     *
     * @return 32 位 hex nonce
     */
    public static String newNonce() {
        java.security.SecureRandom random = new java.security.SecureRandom();
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        char[] hex = "0123456789abcdef".toCharArray();
        char[] chars = new char[32];
        for (int i = 0; i < 16; i++) {
            int value = bytes[i] & 0xFF;
            chars[i * 2] = hex[value >>> 4];
            chars[i * 2 + 1] = hex[value & 0x0F];
        }
        return new String(chars);
    }

    /**
     * 从 PEM 文本加载 PKCS#8 私钥.
     *
     * @param pem PEM 格式私钥文本（含 BEGIN/END 标记，或纯 Base64）
     * @return 私钥对象
     * @throws IllegalStateException 私钥为空或格式错误
     */
    public static PrivateKey loadPrivateKey(String pem) {
        if (pem == null || pem.trim().isEmpty()) {
            throw new IllegalStateException("pay.sign.private-key未配置");
        }
        try {
            String keyContent = pem
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s+", "");
            byte[] keyBytes = Base64.getDecoder().decode(keyContent);
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
            return KeyFactory.getInstance("RSA").generatePrivate(keySpec);
        } catch (Exception e) {
            throw new IllegalStateException("pay.sign.private-key格式错误", e);
        }
    }

    /**
     * 从 classpath 资源加载 PEM 私钥文本，再解析为私钥.
     *
     * @param classpathResource classpath 路径，如 {@code /keys/biz-private-key.pem}
     * @return 私钥对象
     */
    public static PrivateKey loadPrivateKeyResource(String classpathResource) {
        return loadPrivateKey(readResourceUtf8(classpathResource));
    }

    /**
     * 从 PEM 文本加载 X.509 公钥.
     *
     * @param pem PEM 格式公钥文本（含 BEGIN/END 标记，或纯 Base64）
     * @return 公钥对象
     * @throws IllegalStateException 公钥为空或格式错误
     */
    public static PublicKey loadPublicKey(String pem) {
        if (pem == null || pem.trim().isEmpty()) {
            throw new IllegalStateException("公钥未配置");
        }
        try {
            String keyContent = pem
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s+", "");
            byte[] keyBytes = Base64.getDecoder().decode(keyContent);
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);
            return KeyFactory.getInstance("RSA").generatePublic(keySpec);
        } catch (Exception e) {
            throw new IllegalStateException("公钥格式错误", e);
        }
    }

    /**
     * 从 classpath 资源加载 PEM 公钥文本，再解析为公钥.
     *
     * @param classpathResource classpath 路径，如 {@code /keys/biz-public-key.pem}
     * @return 公钥对象
     */
    public static PublicKey loadPublicKeyResource(String classpathResource) {
        return loadPublicKey(readResourceUtf8(classpathResource));
    }

    /**
     * 读取 classpath 资源为 UTF-8 字符串.
     *
     * @param classpathResource classpath 路径
     * @return 资源文本内容
     */
    public static String readResourceUtf8(String classpathResource) {
        InputStream in = PaySignUtils.class.getResourceAsStream(classpathResource);
        if (in == null) {
            throw new IllegalStateException("classpath资源不存在: " + classpathResource);
        }
        try (InputStream is = in) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取classpath资源失败: " + classpathResource, e);
        }
    }
}
