package com.luyi.myfirst.dao.auth.repository;

import com.luyi.myfirst.dao.auth.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 账户查询入口。令牌里的 sub 是对外标识，所以按 publicId 查而不是主键。 */
public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByPublicId(String publicId);

    /**
     * 按邮箱查「仍占用它的」账户。
     *
     * 判定条件用 deletedAt 而不是 status：唯一键 uk_accounts_active_email 的生成列正是
     * IF(deleted_at IS NULL, email, NULL)。两处口径一旦不一致，就会出现「查询说邮箱空闲、
     * 插入却撞唯一键」——那是最难从日志里看出因果的一类 500。
     */
    Optional<Account> findByEmailAndDeletedAtIsNull(String email);
}
