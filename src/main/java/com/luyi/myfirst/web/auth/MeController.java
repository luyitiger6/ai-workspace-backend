package com.luyi.myfirst.web.auth;

import com.luyi.myfirst.dao.auth.entity.Account;
import com.luyi.myfirst.service.auth.model.VerifiedToken;
import com.luyi.myfirst.web.auth.dto.MeResponse;
import com.luyi.myfirst.web.auth.interceptor.AuthInterceptor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前用户端点。
 *
 * 这里没有一行状态判定：非 {@code active} 的账户在拦截器里就被拒成 401 了，
 * 控制器拿到的 {@link VerifiedToken} 一定是 active。把状态判断再写一遍，
 * 只会多出一个能与拦截器判定不一致的地方。
 *
 * 响应体只做白名单映射：五项字段逐个从实体取，不给「顺手把实体整个序列化出去」
 * 留任何机会——那条路会把 passwordHash 带出站。
 */
@RestController
@RequestMapping("/api/auth")
public class MeController {

    @GetMapping("/me")
    MeResponse me(@RequestAttribute(AuthInterceptor.VERIFIED_ATTRIBUTE) VerifiedToken verified) {
        Account account = verified.account();
        return new MeResponse(
                account.publicId(),
                account.email(),
                account.displayName(),
                account.status().dbValue(),
                account.createdAt());
    }
}
