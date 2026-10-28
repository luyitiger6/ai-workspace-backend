package com.luyi.myfirst.service.auth.impl;

import com.luyi.myfirst.config.AuthProperties;
import com.luyi.myfirst.service.auth.CaptchaService;
import com.luyi.myfirst.service.auth.model.CaptchaIssue;
import com.luyi.myfirst.service.auth.support.CodeHasher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 图形验证码（D9）：JDK 手绘，不引第三方组件。
 *
 * 可读性优先于防机器强度——不加重度扭曲、不堆干扰线，靠「一次性 + 答错即作废」控制刷取成本。
 * 难度堆到人也能被挡的程度，换来的只是用户多刷几次。
 *
 * 答案从不离开进程：进缓存的只有摘要，图片里也没有可反推的文本。
 */
@Service
public class CaptchaServiceImpl implements CaptchaService {

    private static final String KEY_PREFIX = "wc:captcha:";
    private static final String PURPOSE = "captcha";

    /**
     * 去掉了 0/O、1/I/L —— 看不清时最容易混的几个。
     * 字符集小一点换来的是「认错一次就重来」的次数少一点，这笔交换对可读性优先的方案是划算的。
     */
    private static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    private static final int ANSWER_LENGTH = 4;

    private static final int WIDTH = 120;
    private static final int HEIGHT = 40;
    private static final int FONT_SIZE = 26;
    private static final int PADDING = 10;
    private static final int SLOT = 25;
    private static final int BASELINE = 30;

    private static final Color BACKGROUND = new Color(0xF3, 0xF5, 0xF8);
    private static final Color INK = new Color(0x1F, 0x29, 0x37);
    private static final Font FONT = new Font(Font.SANS_SERIF, Font.BOLD, FONT_SIZE);

    private final StringRedisTemplate redis;
    private final CodeHasher hasher;
    private final Duration ttl;

    public CaptchaServiceImpl(StringRedisTemplate redis, CodeHasher hasher, AuthProperties properties) {
        this.redis = redis;
        this.hasher = hasher;
        this.ttl = properties.captcha().ttl();
    }

    @Override
    public CaptchaIssue issue() {
        return issue(randomAnswer());
    }

    /**
     * 用指定答案签发 —— 不在接口上暴露，只服务于测试。
     *
     * 生产路径的答案生成后立刻被摘要，图片里也没有可读文本，测试没有别的途径拿到它；
     * 不指定答案就只能穷举全部 4 位组合，才能验证「答对能通过」。
     * 测试须按 {@link CaptchaServiceImpl} 具体类型注入才能调用它，接口上看不到。
     */
    public CaptchaIssue issue(String answer) {
        String captchaId = UUID.randomUUID().toString();
        redis.opsForValue().set(KEY_PREFIX + captchaId, hasher.hash(PURPOSE, answer), ttl);
        return new CaptchaIssue(captchaId, render(answer));
    }

    /**
     * 校验并消费：无论答对答错，这个 captchaId 都不再可用。
     *
     * GETDEL 在一次往返里完成「读 + 删」。拆成 GET 再 DEL 的话，两个并发请求能读到同一个答案，
     * 而「一次性」正是这套图形码唯一的防刷手段。
     */
    @Override
    public boolean verify(String captchaId, String answer) {
        if (captchaId == null || captchaId.isBlank() || answer == null) {
            return false;
        }
        String stored = redis.opsForValue().getAndDelete(KEY_PREFIX + captchaId);
        if (stored == null) {
            return false;
        }
        String candidate = hasher.hash(PURPOSE, normalizeAnswer(answer));
        return MessageDigest.isEqual(
                stored.getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8));
    }

    /** 统一大写并去掉首尾空白：字符集本身就是大写，用户多按了一次 Shift 不该算错。 */
    private static String normalizeAnswer(String answer) {
        return answer.trim().toUpperCase(Locale.ROOT);
    }

    private static String randomAnswer() {
        StringBuilder answer = new StringBuilder(ANSWER_LENGTH);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int index = 0; index < ANSWER_LENGTH; index++) {
            answer.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return answer.toString();
    }

    /**
     * 画图并编成 data URI。
     *
     * 字符逐位轻微上下浮动，让四个字形不像盖章；不做旋转 —— 那是最伤可读性的一种「防机器」。
     * 深浅两色对比度约 12:1，远高于正文所需的 4.5:1。
     */
    private static String render(String answer) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setColor(BACKGROUND);
            graphics.fillRect(0, 0, WIDTH, HEIGHT);
            graphics.setColor(INK);
            graphics.setFont(FONT);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int index = 0; index < answer.length(); index++) {
                int x = PADDING + index * SLOT;
                int y = BASELINE + random.nextInt(-2, 3);
                graphics.drawString(String.valueOf(answer.charAt(index)), x, y);
            }
        } finally {
            graphics.dispose();
        }

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (IOException e) {
            // 写内存字节流不会真的失败；真发生了是 JVM 层面的问题，不是这条链路能兜的
            throw new UncheckedIOException(e);
        }
    }
}
