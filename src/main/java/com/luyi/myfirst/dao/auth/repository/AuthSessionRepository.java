package com.luyi.myfirst.dao.auth.repository;

import com.luyi.myfirst.dao.auth.entity.AuthSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 会话查询入口。jti 上有唯一索引，按它查最多一行。 */
public interface AuthSessionRepository extends JpaRepository<AuthSession, Long> {

    Optional<AuthSession> findByJti(String jti);
}
