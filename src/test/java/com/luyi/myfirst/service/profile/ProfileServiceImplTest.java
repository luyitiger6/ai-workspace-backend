package com.luyi.myfirst.service.profile;

import com.luyi.myfirst.common.exception.BizException;
import com.luyi.myfirst.common.id.Ulid;
import com.luyi.myfirst.dao.auth.entity.Account;
import com.luyi.myfirst.dao.auth.repository.AccountRepository;
import com.luyi.myfirst.service.auth.model.VerifiedToken;
import com.luyi.myfirst.service.profile.model.ProfileCard;
import com.luyi.myfirst.service.profile.model.ProfileUpdate;
import com.luyi.myfirst.service.profile.model.ProfileView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 资料服务的行为（任务 2.1）：本人读取、部分更新、按对外标识批量取名片。
 *
 * 批量按 1 至 50 收口：50 成功、51 拒绝；不存在与已注销的标识被省略而不使整批失败。
 */
@SpringBootTest
@Transactional
class ProfileServiceImplTest {

    @Autowired
    private ProfileService profileService;

    @Autowired
    private AccountRepository accounts;

    @Test
    @DisplayName("本人读取：四项资料按存储拆分返回")
    void readReturnsOwnProfile() {
        Account account = seed("Alice");
        account.updateProfile("Alice", "https://a.example/a.png", "简介", "hiking,food");
        accounts.saveAndFlush(account);

        ProfileView view = profileService.read(new VerifiedToken(account, "jti"));

        assertThat(view.publicId()).isEqualTo(account.publicId());
        assertThat(view.displayName()).isEqualTo("Alice");
        assertThat(view.avatarUrl()).isEqualTo("https://a.example/a.png");
        assertThat(view.bio()).isEqualTo("简介");
        assertThat(view.interests()).containsExactly("hiking", "food");
    }

    @Test
    @DisplayName("部分更新：只改简介，其余三项保持原值")
    void updateIsPartial() {
        Account account = seed("Alice");
        account.updateProfile("Alice", "https://a.example/a.png", "旧简介", "hiking");
        accounts.saveAndFlush(account);

        ProfileView updated = profileService.update(
                new VerifiedToken(account, "jti"),
                new ProfileUpdate(null, null, "新简介", null));

        assertThat(updated.bio()).isEqualTo("新简介");
        assertThat(updated.displayName()).isEqualTo("Alice");
        assertThat(updated.avatarUrl()).isEqualTo("https://a.example/a.png");
        assertThat(updated.interests()).containsExactly("hiking");
    }

    @Test
    @DisplayName("批量 1 个成功")
    void cardsReturnsSingleExisting() {
        Account account = seed("One");

        List<ProfileCard> cards = profileService.cards(List.of(account.publicId()));

        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).publicId()).isEqualTo(account.publicId());
        assertThat(cards.get(0).displayName()).isEqualTo("One");
    }

    @Test
    @DisplayName("批量 50 个成功")
    void cardsReturnsFifty() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            ids.add(seed("User" + i).publicId());
        }

        List<ProfileCard> cards = profileService.cards(ids);

        assertThat(cards).hasSize(50);
    }

    @Test
    @DisplayName("批量 51 个被拒：VALIDATION_FAILED")
    void cardsRejectsFiftyOne() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            ids.add(Ulid.generate());
        }

        assertThatThrownBy(() -> profileService.cards(ids))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("未知与已注销标识被省略")
    void cardsOmitsUnknownAndDeleted() {
        Account alive = seed("Alive");
        Account deleted = seed("Deleted");
        deleted.delete(Instant.now());
        accounts.saveAndFlush(deleted);

        List<ProfileCard> cards = profileService.cards(
                List.of(alive.publicId(), deleted.publicId(), Ulid.generate()));

        assertThat(cards).extracting(ProfileCard::publicId).containsExactly(alive.publicId());
    }

    @Test
    @DisplayName("公开读：按对外标识返回四项资料")
    void readPublicReturnsFourFields() {
        Account account = seed("Alice");
        account.updateProfile("Alice", "https://a.example/a.png", "简介", "hiking,food");
        accounts.saveAndFlush(account);

        ProfileView view = profileService.readPublic(account.publicId());

        assertThat(view.publicId()).isEqualTo(account.publicId());
        assertThat(view.displayName()).isEqualTo("Alice");
        assertThat(view.avatarUrl()).isEqualTo("https://a.example/a.png");
        assertThat(view.bio()).isEqualTo("简介");
        assertThat(view.interests()).containsExactly("hiking", "food");
    }

    @Test
    @DisplayName("公开读：未填写的资料返回空串与空数组，不是 null")
    void readPublicReturnsEmptyDefaults() {
        Account account = seed("Blank");

        ProfileView view = profileService.readPublic(account.publicId());

        assertThat(view.avatarUrl()).isEmpty();
        assertThat(view.bio()).isEmpty();
        assertThat(view.interests()).isEmpty();
    }

    @Test
    @DisplayName("公开读：已注销的标识按不存在处理")
    void readPublicRejectsDeleted() {
        Account deleted = seed("Deleted");
        deleted.delete(Instant.now());
        accounts.saveAndFlush(deleted);

        assertProfileNotFound(deleted.publicId());
    }

    @Test
    @DisplayName("公开读：不存在与形状不合同一个出口")
    void readPublicRejectsUnknown() {
        assertProfileNotFound(Ulid.generate());
        assertProfileNotFound("not-a-ulid");
    }

    private void assertProfileNotFound(String publicId) {
        assertThatThrownBy(() -> profileService.readPublic(publicId))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo("PROFILE_NOT_FOUND"));
    }

    private Account seed(String displayName) {
        String publicId = Ulid.generate();
        return accounts.saveAndFlush(Account.createActive(
                publicId, publicId.toLowerCase() + "@example.com", "hash", displayName));
    }
}
