package com.luyi.myfirst.service.auth;

import com.luyi.myfirst.common.exception.AuthErrorEnum;
import com.luyi.myfirst.common.exception.BizException;
import com.luyi.myfirst.config.AuthProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 注册码换发（spec「注册验证码链路」的第一步）。
 *
 * 顺序是有讲究的：先消费图形码，再发码。反过来的话，一个错误的图形码也能触发投递，
 * 图形码就退化成「随便填个东西」的装饰。
 */
@Service
public class RegisterCodeService {

    private static final String KEY_PREFIX = "wc:code:register:";
    private static final String EMAIL_PURPOSE = "email";
    private static final String CODE_PURPOSE = "register-code";
    private static final int CODE_BOUND = 1_000_000;

    private final StringRedisTemplate redis;
    private final CodeHasher hasher;
    private final CaptchaService captcha;
    private final VerificationCodeSender sender;
    private final Duration ttl;

    public RegisterCodeService(StringRedisTemplate redis, CodeHasher hasher, CaptchaService captcha,
                               VerificationCodeSender sender, AuthProperties properties) {
        this.redis = redis;
        this.hasher = hasher;
        this.captcha = captcha;
        this.sender = sender;
        this.ttl = properties.emailCode().ttl();
    }

    /**
     * 换发注册码：消费图形码 → 生成 6 位数字 → 摘要入缓存 → 投递。
     *
     * 缓存键由邮箱摘要构成，值是码的摘要。绑定邮箱不靠额外的字段，靠「键就是那个邮箱」——
     * A 的码只能从 A 的键里查到，拿去提交 B 必然落空。
     */
    public void issue(String email, String captchaId, String captchaAnswer) {
        String normalized = EmailNormalizer.normalize(email);
        if (normalized == null || normalized.isBlank()) {
            throw new BizException(AuthErrorEnum.VALIDATION_FAILED);
        }
        if (!captcha.verify(captchaId, captchaAnswer)) {
            throw new BizException(AuthErrorEnum.CAPTCHA_INVALID);
        }

        String code = sixDigitCode();
        redis.opsForValue().set(keyOf(normalized), hasher.hash(CODE_PURPOSE, code), ttl);
        sender.send(normalized, code, CodePurpose.REGISTER);
    }

    /** 缓存键：只含邮箱摘要，不含明文。 */
    String keyOf(String normalizedEmail) {
        return KEY_PREFIX + hasher.hash(EMAIL_PURPOSE, normalizedEmail);
    }

    /** 补零到 6 位：前导零是码的一部分，用整型存会让 001234 和 1234 变成两个不同的码。 */
    private static String sixDigitCode() {
        return String.format(Locale.ROOT, "%06d", ThreadLocalRandom.current().nextInt(CODE_BOUND));
    }
}
