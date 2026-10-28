package com.luyi.myfirst.service.auth;

import com.luyi.myfirst.service.auth.model.CaptchaIssue;

/**
 * 图形验证码：签发与校验。
 *
 * 只声明生产路径用得上的两个动作。「用指定答案签发」那条重载刻意不在此处——
 * 它只服务于测试，放进接口就等于对外提供「可以指定图形码答案」这个能力。
 */
public interface CaptchaService {

    /** 签发一张新图形码。每次调用都是一张独立的码，互不作废。 */
    CaptchaIssue issue();

    /** 校验并消费：无论答对答错，这个 captchaId 都不再可用。 */
    boolean verify(String captchaId, String answer);
}
