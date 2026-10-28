package com.luyi.myfirst.service.auth.impl;

import com.luyi.myfirst.dao.auth.entity.Account;
import com.luyi.myfirst.dao.auth.repository.AccountRepository;
import com.luyi.myfirst.service.auth.AccountService;
import com.luyi.myfirst.service.auth.model.VerifiedToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 注销：直接复用实体的具名迁移 {@link Account#delete(Instant)}。
 *
 * 不在这里自己写 {@code setStatus} 再补 {@code deletedAt}：状态与注销时间必须同时落库，
 * 少写一个，{@code uk_accounts_active_email} 的生成列就仍认为邮箱被占用，
 * 「注销后同一邮箱可重新注册」随之失效。
 */
@Service
public class AccountServiceImpl implements AccountService {

    private final AccountRepository accounts;

    public AccountServiceImpl(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    @Transactional
    public void delete(VerifiedToken verified, Instant now) {
        Account account = verified.account();
        account.delete(now);
        accounts.save(account);
    }
}
