package com.luyi.myfirst.service.auth;

import com.luyi.myfirst.dao.auth.entity.AuthSession;
import com.luyi.myfirst.dao.auth.repository.AuthSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 登出：只吊销当前这一张令牌。
 *
 * 重复登出不报错——用户连点两次、前端失败重试都不该变成 401 或 500。
 * 同账户的其他会话各有自己的 jti，这里碰不到它们。
 */
@Service
public class LogoutService {

    private final AuthSessionRepository sessions;

    public LogoutService(AuthSessionRepository sessions) {
        this.sessions = sessions;
    }

    @Transactional
    public void logout(VerifiedToken verified) {
        AuthSession session = sessions.findByJti(verified.jti()).orElse(null);
        if (session == null || session.isRevoked()) {
            return;
        }
        session.revoke(Instant.now());
        sessions.save(session);
    }
}
