package com.luyi.myfirst.web.auth.dto;

/**
 * POST /api/auth/register-code 的请求体。
 *
 * 图形码答案与邮箱一起提交：换码的代价必须是「过一次图形码」，否则这个端点就成了
 * 任何人可用的邮件轰炸入口。
 */
public record RegisterCodeRequest(String email, String captchaId, String captchaAnswer) {
}
