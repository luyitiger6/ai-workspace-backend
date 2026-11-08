package com.luyi.myfirst.service.profile.impl;

import com.luyi.myfirst.common.exception.AuthErrorEnum;
import com.luyi.myfirst.common.exception.BizException;
import com.luyi.myfirst.dao.auth.entity.Account;
import com.luyi.myfirst.dao.auth.repository.AccountRepository;
import com.luyi.myfirst.service.auth.model.VerifiedToken;
import com.luyi.myfirst.service.profile.ProfileService;
import com.luyi.myfirst.service.profile.model.ProfileCard;
import com.luyi.myfirst.service.profile.model.ProfileUpdate;
import com.luyi.myfirst.service.profile.model.ProfileView;
import com.luyi.myfirst.service.profile.support.InterestCatalog;
import com.luyi.myfirst.service.profile.support.ProfileRules;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

/**
 * 资料服务实现。
 *
 * 写路径先归一后落库：ProfileRules 是唯一的校验点，这里不重复判断，只把归一结果
 * 交给实体的 updateProfile（null 保持原值）。批量名片不校验单个标识的形状——不合法
 * 的取值与不存在、已注销的取值一样自然查不到、被省略，不把这个公开端点变成枚举信道。
 */
@Service
public class ProfileServiceImpl implements ProfileService {

    private static final int MAX_CARDS = 50;

    private final AccountRepository accounts;

    public ProfileServiceImpl(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    @Transactional(readOnly = true)
    public ProfileView read(VerifiedToken verified) {
        return toView(verified.account());
    }

    @Override
    @Transactional
    public ProfileView update(VerifiedToken verified, ProfileUpdate command) {
        ProfileUpdate normalized = ProfileRules.normalize(command);
        Account account = verified.account();
        account.updateProfile(
                normalized.displayName(),
                normalized.avatarUrl(),
                normalized.bio(),
                serializeInterests(normalized.interests()));
        return toView(accounts.saveAndFlush(account));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProfileCard> cards(List<String> publicIds) {
        if (publicIds == null || publicIds.isEmpty() || publicIds.size() > MAX_CARDS) {
            throw new BizException(AuthErrorEnum.VALIDATION_FAILED);
        }
        return accounts.findByPublicIdInAndDeletedAtIsNull(publicIds).stream()
                .map(ProfileServiceImpl::toCard)
                .toList();
    }

    @Override
    public List<String> interests() {
        return InterestCatalog.INTERESTS;
    }

    private static ProfileView toView(Account account) {
        return new ProfileView(
                account.publicId(),
                account.displayName(),
                account.avatarUrl(),
                account.bio(),
                splitInterests(account.interests()));
    }

    private static ProfileCard toCard(Account account) {
        return new ProfileCard(account.publicId(), account.displayName(), account.avatarUrl());
    }

    /** 列表转存储：null 表示未提交（保持原值），空列表 join 成空串（清空）。 */
    private static String serializeInterests(List<String> interests) {
        if (interests == null) {
            return null;
        }
        return String.join(",", interests);
    }

    private static List<String> splitInterests(String stored) {
        if (stored == null || stored.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(stored.split(",")).toList();
    }
}
