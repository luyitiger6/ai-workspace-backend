package com.luyi.myfirst.web.auth.dto;

import com.luyi.myfirst.common.web.AuthResponseBody;

/**
 * POST /api/auth/register 的响应体。
 *
 * 三个字段都是可以出现在页面上的信息。令牌不在其中——它只走 Set-Cookie（D8），
 * 这里连一个能装下它的字段都没有。
 */
public record RegisterResponse(String publicId, String email, String displayName) implements AuthResponseBody {
}
