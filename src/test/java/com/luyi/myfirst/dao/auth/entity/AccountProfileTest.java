package com.luyi.myfirst.dao.auth.entity;

import com.luyi.myfirst.common.id.Ulid;
import com.luyi.myfirst.dao.auth.repository.AccountRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 资料三列在 Account 与 AccountRepository 上的落地（任务 1.2）。
 *
 * 部分更新只改被传入的字段；批量查询按 deleted_at 排除软删除行；新账户三列初始为空串。
 * 批量查询连真实 MySQL：软删除的唯一语义用内存替身验不出来。
 */
@SpringBootTest
@Transactional
class AccountProfileTest {

    @Autowired
    private AccountRepository accounts;

    @Test
    @DisplayName("部分更新：只传简介，其余三项保持原值")
    void updateProfileOnlyTouchesProvidedFields() {
        Account account = Account.createActive(Ulid.generate(), "alice@example.com", "hash", "原昵称");
        account.updateProfile("原昵称", "https://a.example/avatar.png", "原简介", "hiking");

        account.updateProfile(null, null, "新简介", null);

        assertThat(account.displayName()).isEqualTo("原昵称");
        assertThat(account.avatarUrl()).isEqualTo("https://a.example/avatar.png");
        assertThat(account.bio()).isEqualTo("新简介");
        assertThat(account.interests()).isEqualTo("hiking");
    }

    @Test
    @DisplayName("批量查询排除已软删除行")
    void batchQueryExcludesSoftDeleted() {
        Account alive = accounts.saveAndFlush(Account.createActive(
                Ulid.generate(), "alive-" + Ulid.generate() + "@example.com", "hash", "Alive"));
        Account deleted = accounts.saveAndFlush(Account.createActive(
                Ulid.generate(), "deleted-" + Ulid.generate() + "@example.com", "hash", "Deleted"));
        deleted.delete(Instant.now());
        accounts.saveAndFlush(deleted);

        List<Account> found = accounts.findByPublicIdInAndDeletedAtIsNull(
                List.of(alive.publicId(), deleted.publicId()));

        assertThat(found).extracting(Account::publicId).containsExactly(alive.publicId());
    }

    @Test
    @DisplayName("新账户三列为空串")
    void newAccountStartsWithEmptyProfile() {
        Account account = Account.createActive(Ulid.generate(), "new@example.com", "hash", "New");

        assertThat(account.avatarUrl()).isEmpty();
        assertThat(account.bio()).isEmpty();
        assertThat(account.interests()).isEmpty();
    }
}
