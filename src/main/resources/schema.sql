-- wanderchain 库的建表脚本
-- 规范来源：docs/db-design-spec.md §4（公共字段）、§5.3（软删除唯一）、§7（外键策略）
-- 执行方式：spring.sql.init.mode=always，应用启动时幂等执行；本阶段不引入迁移框架。
-- 幂等要求：每条建表都用 IF NOT EXISTS，重复启动不得报错、不得改变既有结构。

CREATE TABLE IF NOT EXISTS accounts (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  public_id      CHAR(26)     CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  email          VARCHAR(255) NOT NULL,
  password_hash  VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  display_name   VARCHAR(64)  NOT NULL DEFAULT '',
  status         VARCHAR(16)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'active',
  token_version  INT          NOT NULL DEFAULT 0,
  created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at     DATETIME(3)  NULL,
  active_email   VARCHAR(255) GENERATED ALWAYS AS (IF(deleted_at IS NULL, email, NULL)) STORED,
  version        INT          NOT NULL DEFAULT 0,
  created_by     BIGINT       NULL,
  updated_by     BIGINT       NULL,
  PRIMARY KEY (id),
  -- 对外标识与有效邮箱都唯一；已注销行的 active_email 为 NULL，MySQL 视为互不相等，
  -- 因此同一邮箱可以在注销后重新注册。
  UNIQUE KEY uk_accounts_public_id (public_id),
  UNIQUE KEY uk_accounts_active_email (active_email),
  CONSTRAINT ck_accounts_status CHECK (status IN ('active', 'email_unverified', 'deleted')),
  CONSTRAINT ck_accounts_token_version CHECK (token_version >= 0),
  CONSTRAINT ck_accounts_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS auth_sessions (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  -- JWT 的 jti，只用于吊销判定；令牌明文不落库。
  jti         CHAR(36)    CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  account_id  BIGINT      NOT NULL,
  issued_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  expires_at  DATETIME(3) NOT NULL,
  revoked_at  DATETIME(3) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_auth_sessions_jti (jti),
  KEY idx_auth_sessions_account (account_id),
  KEY idx_auth_sessions_expires (expires_at),
  CONSTRAINT fk_auth_sessions_account FOREIGN KEY (account_id) REFERENCES accounts (id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_auth_sessions_expires CHECK (expires_at > issued_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS posts (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  public_id    CHAR(26)     CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  author_id    BIGINT       NOT NULL,
  title        VARCHAR(120) NOT NULL,
  -- 正文是正文不是元数据，按 §6 用 MEDIUMTEXT；应用层另卡 65535 字节上限。
  content      MEDIUMTEXT   NOT NULL,
  cover_image  VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
  -- 标签是元数据用 JSON；按 §8.1 不对 JSON 做函数过滤，本阶段不做按标签筛选。
  tags         JSON         NULL,
  status       VARCHAR(16)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'draft',
  -- 排序键独立成列：先存草稿后发布时，created_at 会把新发布的帖排到老帖前面。
  published_at DATETIME(3)  NULL,
  created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at   DATETIME(3)  NULL,
  version      INT          NOT NULL DEFAULT 0,
  created_by   BIGINT       NULL,
  updated_by   BIGINT       NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_posts_public_id (public_id),
  -- 首页 feed 的形态：(等值列…, deleted_at, 排序列 DESC, id DESC)
  KEY idx_posts_feed (status, deleted_at, published_at DESC, id DESC),
  KEY idx_posts_author (author_id, deleted_at, created_at DESC),
  CONSTRAINT fk_posts_author FOREIGN KEY (author_id) REFERENCES accounts (id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_posts_status CHECK (status IN ('draft', 'published')),
  CONSTRAINT ck_posts_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
