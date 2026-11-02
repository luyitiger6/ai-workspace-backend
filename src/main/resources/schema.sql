-- wanderchain 库的建表脚本
-- 规范来源：docs/db-design-spec.md §4（公共字段）、§5.3（软删除唯一）、§7（外键策略）
-- 执行方式：spring.sql.init.mode=always，应用启动时幂等执行；本阶段不引入迁移框架。
-- 幂等要求：每条建表都用 IF NOT EXISTS，重复启动不得报错、不得改变既有结构。
-- 本文件同时承载景点种子数据（设计 D3）：06 闸门会 drop 重建本库，种子只有住在建表脚本里
-- 才能随重建一起恢复；种子用固定 public_id + INSERT IGNORE 保证重复执行无副作用。

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

CREATE TABLE IF NOT EXISTS post_comments (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  public_id   CHAR(26)     CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  post_id     BIGINT       NOT NULL,
  account_id  BIGINT       NOT NULL,
  parent_id   BIGINT       NULL,
  content     VARCHAR(2000) NOT NULL,
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at  DATETIME(3)  NULL,
  version     INT          NOT NULL DEFAULT 0,
  created_by  BIGINT       NULL,
  updated_by  BIGINT       NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_post_comments_public_id (public_id),
  KEY idx_post_comments_post (post_id, deleted_at, created_at DESC),
  KEY idx_post_comments_parent (parent_id),
  CONSTRAINT fk_post_comments_post FOREIGN KEY (post_id) REFERENCES posts (id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_post_comments_account FOREIGN KEY (account_id) REFERENCES accounts (id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_post_comments_parent FOREIGN KEY (parent_id) REFERENCES post_comments (id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_post_comments_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS post_votes (
  id                BIGINT       NOT NULL AUTO_INCREMENT,
  public_id         CHAR(26)     CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  post_id           BIGINT       NOT NULL,
  account_id        BIGINT       NOT NULL,
  direction         VARCHAR(8)   CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'up',
  created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at        DATETIME(3)  NULL,
  active_account_id BIGINT       GENERATED ALWAYS AS (IF(deleted_at IS NULL, account_id, NULL)) STORED,
  version           INT          NOT NULL DEFAULT 0,
  created_by        BIGINT       NULL,
  updated_by        BIGINT       NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_post_votes_public_id (public_id),
  UNIQUE KEY uk_post_votes_active (post_id, active_account_id),
  KEY idx_post_votes_post (post_id, deleted_at),
  CONSTRAINT fk_post_votes_post FOREIGN KEY (post_id) REFERENCES posts (id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_post_votes_account FOREIGN KEY (account_id) REFERENCES accounts (id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_post_votes_direction CHECK (direction IN ('up', 'down')),
  CONSTRAINT ck_post_votes_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS post_bookmarks (
  id                BIGINT       NOT NULL AUTO_INCREMENT,
  public_id         CHAR(26)     CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  post_id           BIGINT       NOT NULL,
  account_id        BIGINT       NOT NULL,
  created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at        DATETIME(3)  NULL,
  active_account_id BIGINT       GENERATED ALWAYS AS (IF(deleted_at IS NULL, account_id, NULL)) STORED,
  version           INT          NOT NULL DEFAULT 0,
  created_by        BIGINT       NULL,
  updated_by        BIGINT       NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_post_bookmarks_public_id (public_id),
  UNIQUE KEY uk_post_bookmarks_active (post_id, active_account_id),
  KEY idx_post_bookmarks_post (post_id, deleted_at),
  CONSTRAINT fk_post_bookmarks_post FOREIGN KEY (post_id) REFERENCES posts (id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_post_bookmarks_account FOREIGN KEY (account_id) REFERENCES accounts (id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_post_bookmarks_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 景点：面向境外用户的攻略卡片。城市用 slug 字符串表达，按已确认口径不建城市外键
-- （城市攻略页是后续独立变更，本期连城市表都没有）。
CREATE TABLE IF NOT EXISTS attractions (
  id                       BIGINT       NOT NULL AUTO_INCREMENT,
  public_id                CHAR(26)     CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  -- 白名单不含 all：all 是查询侧语义（不过滤），不是数据取值。
  city_slug                VARCHAR(32)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  name_en                  VARCHAR(120) NOT NULL,
  name_cn                  VARCHAR(120) NOT NULL,
  cover_url                VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
  status                   VARCHAR(16)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'draft',
  -- 排序键独立成列：草稿后发布时，created_at 会把新发布的景点排到老景点前面。
  published_at             DATETIME(3)  NULL,
  -- 评分缺失留 NULL（未知），不写 0 冒充「评分为零」（§5.1）。
  rating                   DECIMAL(2,1) NULL,
  view_count               BIGINT       NOT NULL DEFAULT 0,
  -- 三个约束事实列：榜单成员的事实来源，也是卡片上事实徽标的取值依据。
  free_entry               TINYINT(1)   NOT NULL DEFAULT 0,
  booking_required         TINYINT(1)   NOT NULL DEFAULT 0,
  open_on_monday           TINYINT(1)   NOT NULL DEFAULT 0,
  summary_en               VARCHAR(300) NULL,
  -- 正文按 §6 用 MEDIUMTEXT；种子控制在 300 字符内。
  content_en               MEDIUMTEXT   NULL,
  ticket_info_en           VARCHAR(512) NULL,
  opening_hours_en         VARCHAR(512) NULL,
  booking_info_en          VARCHAR(512) NULL,
  payment_info_en          VARCHAR(512) NULL,
  address_en               VARCHAR(512) NULL,
  -- 中文地址是给出租车司机看的，详情页唯一允许出现中文字符的字段。
  address_cn               VARCHAR(512) NULL,
  transit_en               VARCHAR(512) NULL,
  suggested_duration_en    VARCHAR(128) NULL,
  -- 核实时间：没核实过的景点不进任何响应，由查询侧过滤（spec「核实时间可见」）。
  verified_at              DATETIME(3)  NULL,
  -- 榜单是人工策展集合：NULL = 未收录；列值就是策展顺序，不按热度重排。
  curated_rank_first_trip  INT          NULL,
  curated_rank_no_booking  INT          NULL,
  curated_rank_open_monday INT          NULL,
  created_at               DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at               DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at               DATETIME(3)  NULL,
  version                  INT          NOT NULL DEFAULT 0,
  created_by               BIGINT       NULL,
  updated_by               BIGINT       NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_attractions_public_id (public_id),
  -- 三种排序各自一条：(等值列…, deleted_at, 排序列 DESC, id DESC)
  KEY idx_attractions_hot (status, deleted_at, view_count DESC, id DESC),
  KEY idx_attractions_rating (status, deleted_at, rating DESC, id DESC),
  KEY idx_attractions_new (status, deleted_at, published_at DESC, id DESC),
  -- 三个榜单各自一条：策展列是排序列，NULL 在前不影响 IS NOT NULL 之后的顺序。
  KEY idx_attractions_curated_first_trip (status, deleted_at, curated_rank_first_trip, id),
  KEY idx_attractions_curated_no_booking (status, deleted_at, curated_rank_no_booking, id),
  KEY idx_attractions_curated_open_monday (status, deleted_at, curated_rank_open_monday, id),
  CONSTRAINT ck_attractions_status CHECK (status IN ('draft', 'published')),
  CONSTRAINT ck_attractions_rating CHECK (rating IS NULL OR (rating >= 0 AND rating <= 5)),
  CONSTRAINT ck_attractions_view_count CHECK (view_count >= 0),
  CONSTRAINT ck_attractions_city_slug CHECK (city_slug IN ('beijing', 'shanghai', 'guangzhou', 'chengdu', 'xian', 'hangzhou', 'sanya')),
  CONSTRAINT ck_attractions_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 景点种子：12 条 published（覆盖 7 个城市）+ 1 条 draft（用于验证「只呈现已发布」）。
-- public_id 固定，INSERT IGNORE 让重复启动不插新行；榜单归属由三个 curated_rank_* 列表达，
-- NULL = 未收录，列值就是策展顺序。核实时间缺失的景点不会进任何响应，所以每行都写 verified_at。
INSERT IGNORE INTO attractions (
  public_id, city_slug, name_en, name_cn, cover_url, status, published_at, rating, view_count,
  free_entry, booking_required, open_on_monday,
  summary_en, content_en, ticket_info_en, opening_hours_en, booking_info_en, payment_info_en,
  address_en, address_cn, transit_en, suggested_duration_en, verified_at,
  curated_rank_first_trip, curated_rank_no_booking, curated_rank_open_monday
) VALUES
('01JQATTR000000000000000001','beijing','Forbidden City','故宫博物院','https://images.unsplash.com/photo-1508804185872-d7badad00f7d?auto=format&fit=crop&w=900&q=70','published','2026-08-15 09:00:00.000',4.8,12800,0,1,0,
 'The 600-year-old palace complex at the centre of Beijing, best entered at opening time.',
 'Walk the central axis from the Meridian Gate to the imperial garden. Allow three hours, carry your passport for the security check, and expect crowds after 10am.',
 'CNY 60 in high season, CNY 40 in low season; separate ticket for the Treasure Gallery.',
 '08:30-17:00 April to October, 08:30-16:30 November to March; closed on Mondays.',
 'Timed entry booked online at least one day ahead with your passport number.',
 'WeChat Pay, Alipay or cash at the ticket office; foreign cards are not accepted.',
 '4 Jingshan Front Street, Dongcheng District, Beijing','北京市东城区景山前街4号',
 'Metro Line 1 to Tiananmen East, then ten minutes on foot north.','3-4 hours','2026-08-14 00:00:00.000',
 1,NULL,NULL),
('01JQATTR000000000000000002','beijing','Temple of Heaven','天坛公园','https://images.unsplash.com/photo-1526721966451-2604d50ab5e4?auto=format&fit=crop&w=900&q=70','published','2026-08-22 10:00:00.000',4.6,7600,0,0,1,
 'A Ming-era altar park where locals still practise tai chi at dawn.',
 'The park opens two hours before the halls, so come at 06:30 for morning exercise and empty courtyards. The Hall of Prayer for Good Harvests is the highlight.',
 'CNY 34 combined ticket; CNY 15 for the park only.',
 'Park 06:00-22:00, halls 08:00-17:30, open daily including Mondays.',
 'No booking needed; buy at the gate or in the park app.',
 'Cash or mobile payment at the gate; foreign cards are not accepted.',
 '7 Tiantan Inner East Road, Dongcheng District, Beijing','北京市东城区天坛内东里7号',
 'Metro Line 5 to Tiantan Dongmen, exit A.','2 hours','2026-08-20 00:00:00.000',
 NULL,NULL,NULL),
('01JQATTR000000000000000003','shanghai','The Bund','外滩','https://images.unsplash.com/photo-1519501025264-65ba15a82390?auto=format&fit=crop&w=900&q=70','published','2026-09-03 11:00:00.000',4.7,15400,1,0,1,
 'One kilometre of riverside promenade facing the Pudong skyline.',
 'Arrive an hour before sunset to watch the towers light up across the water, then walk north past the old customs house. Free, open around the clock, busiest on weekend evenings.',
 'Free.',
 'Open 24 hours; the promenade is lit until midnight.',
 'No booking needed.',
 'Not applicable: there is no ticket to buy.',
 'Zhongshan East 1st Road, Huangpu District, Shanghai','上海市黄浦区中山东一路',
 'Metro Line 2 or 10 to East Nanjing Road, exit 7, then five minutes east.','1-2 hours','2026-09-02 00:00:00.000',
 2,1,1),
('01JQATTR000000000000000004','shanghai','Shanghai Museum','上海博物馆','https://images.unsplash.com/photo-1526772662000-3f88f10405ff?auto=format&fit=crop&w=900&q=70','published','2026-09-06 09:30:00.000',4.5,6300,1,1,1,
 'Bronzes, ceramics and calligraphy in a free museum on People Square.',
 'Four floors of Chinese art with English labels. The bronze gallery on the ground floor is the reason to come; weekends need a reserved slot.',
 'Free; special exhibitions charge separately.',
 '09:00-17:00, last entry 16:00, open daily including Mondays.',
 'Reserve a free slot on the museum account up to seven days ahead.',
 'Cash or mobile payment for special exhibitions only.',
 '201 Renmin Avenue, Huangpu District, Shanghai','上海市黄浦区人民大道201号',
 'Metro Line 1, 2 or 8 to People Square, exit 1.','2-3 hours','2026-09-05 00:00:00.000',
 NULL,NULL,NULL),
('01JQATTR000000000000000005','guangzhou','Canton Tower','广州塔','https://images.unsplash.com/photo-1476514525535-07fb3b4ae5f1?auto=format&fit=crop&w=900&q=70','published','2026-07-30 14:00:00.000',4.4,5900,0,0,1,
 'The 600-metre tower on the Pearl River, with an open-air deck near the top.',
 'Buy the deck ticket rather than the full package: the outdoor rim walk adds little for the price. Sunset slots sell out on clear days, so arrive before 17:00.',
 'CNY 150 for the 433m deck, CNY 228 for the 488m outdoor deck.',
 '09:30-22:30 daily.',
 'No booking needed; buy at the counter or in the official mini program.',
 'WeChat Pay, Alipay or cash; international cards are accepted at the main counter.',
 '222 Yuejiang West Road, Haizhu District, Guangzhou','广州市海珠区阅江西路222号',
 'Metro Line 3 or the APM line to Canton Tower, exit B.','2 hours','2026-07-28 00:00:00.000',
 NULL,NULL,NULL),
('01JQATTR000000000000000006','guangzhou','Shamian Island','沙面岛','https://images.unsplash.com/photo-1547983681-a631e89a9b24?auto=format&fit=crop&w=900&q=70','published','2026-08-01 08:00:00.000',4.6,4200,1,0,1,
 'Colonial-era arcades and banyan trees on a car-free island.',
 'A flat 30-minute loop past 19th-century consulates; come early for empty streets and coffee. Combine it with the Cantonese restaurants across the pedestrian bridge.',
 'Free.',
 'Open 24 hours; vehicles are not allowed on the island.',
 'No booking needed.',
 'Not applicable: there is no ticket to buy.',
 'Shamian South Street, Liwan District, Guangzhou','广州市荔湾区沙面南街',
 'Metro Line 1 or 6 to Huangsha, exit F, then five minutes south.','1-2 hours','2026-07-30 00:00:00.000',
 NULL,2,2),
('01JQATTR000000000000000007','chengdu','Chengdu Panda Base','成都大熊猫繁育研究基地','https://images.unsplash.com/photo-1544787219-7f47ccb76574?auto=format&fit=crop&w=900&q=70','published','2026-08-10 07:30:00.000',4.9,14200,0,1,1,
 'Pandas at their most active, before the 09:30 feeding time.',
 'Arrive at opening; the pandas sleep through the afternoon. Walk to the far enclosures first and work back towards the exit as the tour groups arrive.',
 'CNY 55; children under 1.3 metres free.',
 '07:30-18:00 March to October, 08:00-17:00 November to February.',
 'A timed ticket booked online with your passport; summer same-day tickets often sell out.',
 'WeChat Pay or Alipay at the entrance; cash is accepted but slow.',
 '1375 Panda Avenue, Chenghua District, Chengdu','成都市成华区熊猫大道1375号',
 'Metro Line 3 to Panda Avenue, then shuttle 87 or a fifteen-minute taxi.','3 hours','2026-08-08 00:00:00.000',
 3,NULL,NULL),
('01JQATTR000000000000000008','chengdu','Wuhou Shrine','武侯祠','https://images.unsplash.com/photo-1441974231531-c6227db76b6e?auto=format&fit=crop&w=900&q=70','published','2026-08-11 09:00:00.000',4.3,5100,0,0,1,
 'A Three Kingdoms shrine and garden beside Jinli old street.',
 'The red-walled bamboo corridor behind the main halls is the quietest part. Combine it with Jinli next door, but eat elsewhere: the street food is priced for tourists.',
 'CNY 50.',
 '08:00-20:00 in summer, 08:00-18:30 in winter.',
 'No booking needed.',
 'WeChat Pay, Alipay or cash at the ticket window.',
 '231 Wuhouci Street, Wuhou District, Chengdu','成都市武侯区武侯祠大街231号',
 'Metro Line 3 to Gaoshengqiao, exit D, then ten minutes on foot.','2 hours','2026-08-09 00:00:00.000',
 NULL,NULL,NULL),
('01JQATTR000000000000000009','xian','Terracotta Army','秦始皇兵马俑博物馆','https://images.unsplash.com/photo-1508804185872-d7badad00f7d?auto=format&fit=crop&w=900&q=70','published','2026-06-20 08:00:00.000',4.8,11300,0,1,1,
 'Thousands of clay soldiers in three excavation pits outside the city.',
 'See pit one last: it is the most filmed and the least surprising. Hire the official guide at the entrance and take the free shuttle between pits.',
 'CNY 120 including the shuttle between pits.',
 '08:30-18:00 March to November, 08:30-17:30 December to February.',
 'Booked online with your passport; the ticket is valid only for the chosen date.',
 'WeChat Pay, Alipay or cash; foreign cards are not accepted.',
 'Qinling North Road, Lintong District, Xi''an','西安市临潼区秦陵北路',
 'Tourist bus 5 from the railway station, about one hour; or a forty-minute taxi.','3-4 hours','2026-06-18 00:00:00.000',
 4,NULL,NULL),
('01JQATTR000000000000000010','xian','Xi''an City Wall','西安城墙','https://images.unsplash.com/photo-1526772662000-3f88f10405ff?auto=format&fit=crop&w=900&q=70','published','2026-06-22 16:00:00.000',4.5,8100,0,0,1,
 'A 13.7 kilometre Ming wall you can cycle in about ninety minutes.',
 'Rent a bicycle at the South Gate and ride the full loop; the rental clock starts when you leave. Late afternoon is best, with the sun behind you on the west side.',
 'CNY 54 for the wall; bicycle rental CNY 45 for ninety minutes.',
 '08:00-22:00 at the South Gate; other gates close earlier.',
 'No booking needed.',
 'WeChat Pay or Alipay for the bicycle deposit; cash at the ticket window.',
 'South Gate, Nan Dajie, Beilin District, Xi''an','西安市碑林区南大街南门',
 'Metro Line 2 to Yongningmen, exit D.','2 hours','2026-06-20 00:00:00.000',
 NULL,3,NULL),
('01JQATTR000000000000000011','hangzhou','West Lake','西湖','https://images.unsplash.com/photo-1441974231531-c6227db76b6e?auto=format&fit=crop&w=900&q=70','published','2026-09-14 07:00:00.000',4.9,16700,1,0,1,
 'The lake, causeways and tea hills that define Hangzhou.',
 'Start at Broken Bridge before 08:00, walk the Bai Causeway west, then climb to Baochu Pagoda for the view back. Boats are cheap but slow; the walk is the point.',
 'Free; boat trips CNY 55.',
 'Open 24 hours; temples and pagodas charge separately.',
 'No booking needed.',
 'Cash or mobile payment for boats; foreign cards are not accepted.',
 'West Lake Scenic Area, Xihu District, Hangzhou','杭州市西湖区西湖风景名胜区',
 'Metro Line 1 to Longxiangqiao, then fifteen minutes on foot west.','Half a day','2026-09-12 00:00:00.000',
 NULL,4,3),
('01JQATTR000000000000000012','sanya','Yalong Bay','亚龙湾','https://images.unsplash.com/photo-1507525428034-b723cf961d3e?auto=format&fit=crop&w=900&q=70','published','2026-10-05 09:00:00.000',4.7,9800,1,0,1,
 'Seven kilometres of calm, swimmable beach on the southern coast.',
 'The public access point sits between the resort compounds; bring your own shade. The water is calmest before 10:00, and the bay faces east, so mornings are cooler.',
 'Free; sunbeds cost CNY 50 to 100 per day.',
 'Open 24 hours; lifeguards on duty 08:00-18:00.',
 'No booking needed.',
 'Cash or mobile payment for sunbeds and showers.',
 'Yalong Bay National Resort, Jiyang District, Sanya','三亚市吉阳区亚龙湾国家旅游度假区',
 'Bus 25 or 27 from the city centre, about forty-five minutes; a taxi costs around CNY 60.','Half a day','2026-10-04 00:00:00.000',
 NULL,NULL,4),
('01JQATTR000000000000000013','shanghai','Yu Garden','豫园','https://images.unsplash.com/photo-1547983681-a631e89a9b24?auto=format&fit=crop&w=900&q=70','draft',NULL,4.4,300,0,0,0,
 'A Ming-dynasty garden behind the City God Temple bazaar.',
 'Draft entry: confirm the Monday closure and the holiday reservation policy before publishing.',
 'CNY 40.',
 '08:45-16:45; closed on Mondays.',
 'Reserve a slot online during public holidays.',
 'WeChat Pay, Alipay or cash.',
 '218 Anren Street, Huangpu District, Shanghai','上海市黄浦区安仁街218号',
 'Metro Line 10 or 14 to Yuyuan Garden, exit 1.','1-2 hours','2026-09-20 00:00:00.000',
 NULL,NULL,NULL);

-- 景点评论。逐列对齐 post_comments：同一个仓库不在两张评论表上用两套形状，
-- 将来真要接评论功能时，实体 / 仓储 / 服务可以照搬帖子评论那套分层。
-- 归属列换成 attraction_id，指向 attractions.id（帖子那侧指向 posts.id）。
-- 本期只建表：没有消费方，不提前产出实体、仓储与端点。
CREATE TABLE IF NOT EXISTS attraction_comments (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  public_id     CHAR(26)     CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  attraction_id BIGINT       NOT NULL,
  account_id    BIGINT       NOT NULL,
  parent_id     BIGINT       NULL,
  content       VARCHAR(2000) NOT NULL,
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at    DATETIME(3)  NULL,
  version       INT          NOT NULL DEFAULT 0,
  created_by    BIGINT       NULL,
  updated_by    BIGINT       NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_attraction_comments_public_id (public_id),
  KEY idx_attraction_comments_attraction (attraction_id, deleted_at, created_at DESC),
  KEY idx_attraction_comments_parent (parent_id),
  CONSTRAINT fk_attraction_comments_attraction FOREIGN KEY (attraction_id) REFERENCES attractions (id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_attraction_comments_account FOREIGN KEY (account_id) REFERENCES accounts (id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_attraction_comments_parent FOREIGN KEY (parent_id) REFERENCES attraction_comments (id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_attraction_comments_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
