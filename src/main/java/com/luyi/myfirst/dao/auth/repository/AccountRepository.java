package com.luyi.myfirst.dao.auth.repository;

import com.luyi.myfirst.dao.auth.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 账户查询入口。令牌里的 sub 是对外标识，所以按 publicId 查而不是主键。 */
public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByPublicId(String publicId);
}
