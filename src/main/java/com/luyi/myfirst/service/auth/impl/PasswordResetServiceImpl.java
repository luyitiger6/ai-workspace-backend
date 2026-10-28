package com.luyi.myfirst.service.auth.impl;

import com.luyi.myfirst.common.exception.AuthErrorEnum;
import com.luyi.myfirst.common.exception.BizException;
import com.luyi.myfirst.common.security.PasswordHasher;
import com.luyi.myfirst.common.security.PasswordRules;
import com.luyi.myfirst.dao.auth.entity.Account;
import com.luyi.myfirst.dao.auth.repository.AccountRepository;
import com.luyi.myfirst.service.auth.PasswordResetCodeService;
import com.luyi.myfirst.service.auth.PasswordResetService;
import com.luyi.myfirst.service.auth.support.EmailNormalizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 密码重置：格式 → 验码 → 换哈希 → 作废旧令牌。
 *
 * 顺序与注册同一条道理：格式校验在前，因为「密码太短」是表单错误，不该消耗用户手里那张码；
 * 验码在修改之前，因为码就是这个操作的唯一凭据。
 *
 * 账户查询排在验码之后而不是之前：已注销与未注册的邮箱本来就拿不到码，`verify` 会先落在
 * 「码不存在」上；这里再查一次是兜住「码刚验过、账户被别人删掉」的并发窗口，查不到同样
 * 落 `INVALID_CODE`——两条路径的响应必须一致，否则就成了邮箱存在性预言机。
 *
 * 缓存键在验码成功的同一次往返里被删掉（见 `EmailCodeFlow` 的 `CONSUME_IF_MATCH`）。
 * 与注册一样，Redis 与数据库无法同事务：码删了之后即便落库失败，这个码也不会回来。
 * 代价可接受——重新取码要过的仍然是图形码、冷却与日配额。
 *
 * 改密码与作废旧令牌合成一个领域方法（{@link Account#changePassword}）：只改哈希而留着
 * 旧令牌，用户会以为已经把对方踢下线，实际对方还能继续操作。
 */
@Service
public class PasswordResetServiceImpl implements PasswordResetService {

    private final AccountRepository accounts;
    private final PasswordResetCodeService resetCodes;
    private final PasswordHasher hasher;

    public PasswordResetServiceImpl(AccountRepository accounts,
                                    PasswordResetCodeService resetCodes,
                                    PasswordHasher hasher) {
        this.accounts = accounts;
        this.resetCodes = resetCodes;
        this.hasher = hasher;
    }

    @Override
    @Transactional
    public void reset(String email, String code, String newPassword) {
        String normalized = EmailNormalizer.normalize(email);
        if (normalized == null || normalized.isBlank() || !PasswordRules.isValid(newPassword)) {
            throw new BizException(AuthErrorEnum.VALIDATION_FAILED);
        }

        resetCodes.verify(normalized, code);

        Account account = accounts.findByEmailAndDeletedAtIsNull(normalized)
                .orElseThrow(() -> new BizException(AuthErrorEnum.INVALID_CODE));

        account.changePassword(hasher.hash(newPassword));
        accounts.save(account);
    }
}
