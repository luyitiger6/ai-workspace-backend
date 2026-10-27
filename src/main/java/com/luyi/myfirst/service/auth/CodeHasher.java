package com.luyi.myfirst.service.auth;

import com.luyi.myfirst.config.AuthProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/**
 * 验证码类短秘密的摘要器（D6）。
 *
 * 图形码 4 位字符、邮箱码 6 位数字，熵都低到裸 SHA-256 在彩虹表面前等于没哈希，
 * 所以统一用带服务端密钥的 HMAC-SHA256。
 *
 * purpose 前缀做域分离：同一个明文在不同用途下得到不同摘要，
 * 使一个用途的摘要挪不到另一个用途上使用。
 */
@Component
public class CodeHasher {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public CodeHasher(AuthProperties properties) {
        this.key = new SecretKeySpec(
                properties.jwtSecret().getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    public String hash(String purpose, String value) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            mac.update(purpose.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("无法计算验证码摘要", e);
        }
    }
}
