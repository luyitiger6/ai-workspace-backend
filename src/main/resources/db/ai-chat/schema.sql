-- AI 会话库（ai_chat_gate）的建表脚本
-- 规范来源：docs/db-design-spec.md §9
-- 只建 users -> conversations -> messages -> usage_records 四张核心表。
-- 后续表（attachments / prompt_templates / feedbacks / model_configs / audit_logs）由后续脚本引入。
-- 执行方式：由 harness\checks\06-schema-check.ps1 在 ai_chat_gate 上执行；应用启动不碰这个库。
-- 幂等要求：每条建表都用 IF NOT EXISTS，闸门会跑两遍验证无副作用。

CREATE TABLE IF NOT EXISTS users (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  public_id       CHAR(26)     CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  email           VARCHAR(255) NOT NULL,
  password_hash   VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  display_name    VARCHAR(64)  NOT NULL DEFAULT '',
  status          VARCHAR(16)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'active',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at      DATETIME(3)  NULL,
  active_email    VARCHAR(255) GENERATED ALWAYS AS (IF(deleted_at IS NULL, email, NULL)) STORED,
  version         INT          NOT NULL DEFAULT 0,
  created_by      BIGINT       NULL,
  updated_by      BIGINT       NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_users_public_id (public_id),
  UNIQUE KEY uk_users_active_email (active_email),
  CONSTRAINT ck_users_status CHECK (status IN ('active', 'disabled')),
  CONSTRAINT ck_users_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS conversations (
  id               BIGINT       NOT NULL AUTO_INCREMENT,
  public_id        CHAR(26)     CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  user_id          BIGINT       NOT NULL,
  title            VARCHAR(200) NOT NULL DEFAULT '',
  model            VARCHAR(64)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status           VARCHAR(16)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'active',
  last_message_at  DATETIME(3)  NULL,
  message_count    INT          NOT NULL DEFAULT 0,
  created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at       DATETIME(3)  NULL,
  version          INT          NOT NULL DEFAULT 0,
  created_by       BIGINT       NULL,
  updated_by       BIGINT       NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_conversations_public_id (public_id),
  KEY idx_conversations_user_active (user_id, deleted_at, last_message_at DESC, id DESC),
  CONSTRAINT fk_conversations_user FOREIGN KEY (user_id) REFERENCES users (id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_conversations_status CHECK (status IN ('active', 'archived')),
  CONSTRAINT ck_conversations_message_count CHECK (message_count >= 0),
  CONSTRAINT ck_conversations_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS messages (
  id                  BIGINT       NOT NULL AUTO_INCREMENT,
  public_id           CHAR(26)     CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  conversation_id     BIGINT       NOT NULL,
  seq                 INT          NOT NULL,
  role                VARCHAR(16)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  content             MEDIUMTEXT   NOT NULL,
  content_type        VARCHAR(16)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'text',
  model               VARCHAR(64)  CHARACTER SET ascii COLLATE ascii_bin NULL,
  prompt_tokens       INT          NOT NULL DEFAULT 0,
  completion_tokens   INT          NOT NULL DEFAULT 0,
  parent_message_id   BIGINT       NULL,
  request_id          VARCHAR(64)  CHARACTER SET ascii COLLATE ascii_bin NULL,
  created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_messages_public_id (public_id),
  UNIQUE KEY uk_messages_conv_seq (conversation_id, seq),
  UNIQUE KEY uk_messages_conv_request (conversation_id, request_id),
  KEY idx_messages_conv_id (conversation_id, id),
  KEY idx_messages_parent (parent_message_id),
  CONSTRAINT fk_messages_conversation FOREIGN KEY (conversation_id) REFERENCES conversations (id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_messages_parent FOREIGN KEY (parent_message_id) REFERENCES messages (id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_messages_role CHECK (role IN ('system', 'user', 'assistant', 'tool')),
  CONSTRAINT ck_messages_content_type CHECK (content_type IN ('text', 'markdown', 'json')),
  CONSTRAINT ck_messages_seq CHECK (seq >= 1),
  CONSTRAINT ck_messages_prompt_tokens CHECK (prompt_tokens >= 0),
  CONSTRAINT ck_messages_completion_tokens CHECK (completion_tokens >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS usage_records (
  id                 BIGINT        NOT NULL AUTO_INCREMENT,
  public_id          CHAR(26)      CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  user_id            BIGINT        NOT NULL,
  conversation_id    BIGINT        NOT NULL,
  message_id         BIGINT        NULL,
  model              VARCHAR(64)   CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  prompt_tokens      INT           NOT NULL DEFAULT 0,
  completion_tokens  INT           NOT NULL DEFAULT 0,
  total_tokens       INT           GENERATED ALWAYS AS (prompt_tokens + completion_tokens) STORED,
  cost               DECIMAL(18,6) NOT NULL DEFAULT 0.000000,
  currency           CHAR(3)       CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'CNY',
  request_id         VARCHAR(64)   CHARACTER SET ascii COLLATE ascii_bin NULL,
  created_at         DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_public_id (public_id),
  KEY idx_usage_user_time (user_id, created_at),
  KEY idx_usage_conversation_time (conversation_id, created_at),
  KEY idx_usage_message (message_id),
  CONSTRAINT fk_usage_user FOREIGN KEY (user_id) REFERENCES users (id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_usage_conversation FOREIGN KEY (conversation_id) REFERENCES conversations (id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_usage_message FOREIGN KEY (message_id) REFERENCES messages (id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_usage_prompt_tokens CHECK (prompt_tokens >= 0),
  CONSTRAINT ck_usage_completion_tokens CHECK (completion_tokens >= 0),
  CONSTRAINT ck_usage_cost CHECK (cost >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
