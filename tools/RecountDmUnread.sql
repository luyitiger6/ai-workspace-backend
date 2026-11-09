-- 私信未读计数对账脚本（design D9 的运维配套，不进应用启动流程）。
--
-- 什么时候用：怀疑 unread_count 与真实未读偏移时——历史数据迁移、有人直接在库里
-- 插过 dm_messages、或旧版本写坏过计数。请求路径不调用它：对账是运维动作，不是
-- 每次读会话列表都要跑一遍的校验。
--
-- 语义与请求路径完全一致：未读 = 会话内「对方发送且 id 大于我方已读位置」的消息数。
-- last_read_message_id 为 NULL 表示从未读过，对方发来的消息全部计为未读。
--
-- 用法（先看第 1 段偏移明细，再执行第 2 段回写）：
--   mysql -h 127.0.0.1 -P 8806 -u root -p wanderchain < backend/tools/RecountDmUnread.sql
--
-- 边界：只 SELECT 与 UPDATE 两个计数列；不 DROP、不 DELETE、不建表、不动 dm_messages。
-- deleted_at 非空的会话不参与——会话列表与总未读的读路径本来就排除它们。

-- ---------- 第 1 段：偏移明细（只读） ----------
-- 只列出「存储值与重算值不一致」的会话；没有输出说明计数是准的。

SELECT *
FROM (
  SELECT c.id,
         c.public_id,
         c.participant_low_id,
         c.participant_high_id,
         c.low_unread_count  AS stored_low_unread,
         (SELECT COUNT(*) FROM dm_messages m
           WHERE m.conversation_id = c.id
             AND m.sender_id <> c.participant_low_id
             AND (c.low_last_read_message_id IS NULL
                  OR m.id > c.low_last_read_message_id)) AS actual_low_unread,
         c.high_unread_count AS stored_high_unread,
         (SELECT COUNT(*) FROM dm_messages m
           WHERE m.conversation_id = c.id
             AND m.sender_id <> c.participant_high_id
             AND (c.high_last_read_message_id IS NULL
                  OR m.id > c.high_last_read_message_id)) AS actual_high_unread
  FROM dm_conversations c
  WHERE c.deleted_at IS NULL
) t
WHERE stored_low_unread <> actual_low_unread
   OR stored_high_unread <> actual_high_unread
ORDER BY id;

-- ---------- 第 2 段：回写（子查询只读 dm_messages，不触碰本表） ----------
-- updated_at = updated_at：与仓储层的计数更新一致，业务上的「最后活跃时间」由
-- last_message_at 表达，对账不需要留下审计痕迹。

UPDATE dm_conversations c
SET c.low_unread_count = (
      SELECT COUNT(*) FROM dm_messages m
       WHERE m.conversation_id = c.id
         AND m.sender_id <> c.participant_low_id
         AND (c.low_last_read_message_id IS NULL
              OR m.id > c.low_last_read_message_id)),
    c.high_unread_count = (
      SELECT COUNT(*) FROM dm_messages m
       WHERE m.conversation_id = c.id
         AND m.sender_id <> c.participant_high_id
         AND (c.high_last_read_message_id IS NULL
              OR m.id > c.high_last_read_message_id)),
    c.updated_at = c.updated_at
WHERE c.deleted_at IS NULL;
