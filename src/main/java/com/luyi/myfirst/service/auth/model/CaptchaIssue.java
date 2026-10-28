package com.luyi.myfirst.service.auth.model;

/**
 * 一次图形码签发的结果。
 *
 * 只有 id 与图，没有答案：答案从不离开服务端进程，进缓存的也只是摘要。
 * image 是可直接放进 img src 的 data URI，客户端不需要再发一次请求取图。
 */
public record CaptchaIssue(String captchaId, String image) {
}
