package com.luyi.myfirst.dao.auth.repository;

import com.luyi.myfirst.dao.auth.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 账户查询入口。令牌里的 sub 是对外标识，所以按 publicId 查而不是主键。 */
public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByPublicId(String publicId);

    /**
     * 按对外标识批量取「仍在」的账户。
     *
     * 判定条件用 deletedAt 而不是 status：注销是软删除，行仍在但 deleted_at 非空，
     * 名片批量端点必须把它当「不存在」一样省略（spec「未知标识被省略」）。
     * 集合里不存在或形状不合的取值自然查不到，与已注销行一样不返回，不单独报错。
     */
    List<Account> findByPublicIdInAndDeletedAtIsNull(Collection<String> publicIds);

    /**
     * 按对外标识取「仍在」的单个账户。
     *
     * 与批量名片同口径：注销是软删除，行仍在但 {@code deleted_at} 非空，
     * 公开用户页必须把它当不存在（spec「公开用户资料的未找到口径」）。
     */
    Optional<Account> findByPublicIdAndDeletedAtIsNull(String publicId);

    /**
     * 按邮箱查「仍占用它的」账户。
     *
     * 判定条件用 deletedAt 而不是 status：唯一键 uk_accounts_active_email 的生成列正是
     * IF(deleted_at IS NULL, email, NULL)。两处口径一旦不一致，就会出现「查询说邮箱空闲、
     * 插入却撞唯一键」——那是最难从日志里看出因果的一类 500。
     */
    Optional<Account> findByEmailAndDeletedAtIsNull(String email);
}
