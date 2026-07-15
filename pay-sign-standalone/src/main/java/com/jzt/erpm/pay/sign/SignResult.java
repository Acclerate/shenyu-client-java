package com.jzt.erpm.pay.sign;

/**
 * 支付加签结果.
 */
public final class SignResult {

    private final String timestamp;
    private final String nonce;
    private final String sign;
    private final String signString;

    SignResult(String timestamp, String nonce, String sign, String signString) {
        this.timestamp = timestamp;
        this.nonce = nonce;
        this.sign = sign;
        this.signString = signString;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public String getNonce() {
        return nonce;
    }

    public String getSign() {
        return sign;
    }

    public String getSignString() {
        return signString;
    }
}
