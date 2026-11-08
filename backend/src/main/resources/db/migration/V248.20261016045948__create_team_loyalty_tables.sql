-- F18.1 チームポイント。既存カード・既存残高を変更しない。
CREATE TABLE team_loyalty_programs (
 id BINARY(16) NOT NULL PRIMARY KEY,
 team_id BIGINT UNSIGNED NOT NULL,
 is_enabled BOOLEAN NOT NULL DEFAULT FALSE,
 target_audience VARCHAR(30) NOT NULL,
 self_award_mode VARCHAR(20) NOT NULL DEFAULT 'STAFF_APPROVAL',
 version BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME NOT NULL, updated_at DATETIME NOT NULL,
 UNIQUE KEY uk_tlp_team (team_id),
 CONSTRAINT ck_tlp_audience CHECK (target_audience IN ('ALL_AUTHENTICATED','SUPPORTERS_AND_ABOVE','MEMBERS_AND_ABOVE')),
 CONSTRAINT ck_tlp_mode CHECK (self_award_mode IN ('STAFF_APPROVAL','AUTO')),
 CONSTRAINT ck_tlp_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE team_loyalty_rules (
 id BINARY(16) NOT NULL PRIMARY KEY,
 team_id BIGINT UNSIGNED NOT NULL,
 name VARCHAR(100) NOT NULL, points INT NOT NULL,
 frequency VARCHAR(30) NOT NULL,
 is_self_award_enabled BOOLEAN NOT NULL DEFAULT FALSE,
 is_active BOOLEAN NOT NULL DEFAULT TRUE,
 version BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME NOT NULL, updated_at DATETIME NOT NULL,
 UNIQUE KEY uk_tlr_team (team_id), UNIQUE KEY uk_tlr_team_id (team_id,id),
 CONSTRAINT ck_tlr_points CHECK (points BETWEEN 1 AND 10000),
 CONSTRAINT ck_tlr_frequency CHECK (frequency IN ('ONCE_PER_JST_DAY','EACH_STAFF_CONFIRMATION')),
 CONSTRAINT ck_tlr_self CHECK (NOT is_self_award_enabled OR frequency='ONCE_PER_JST_DAY'),
 CONSTRAINT ck_tlr_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE team_loyalty_rewards (
 id BINARY(16) NOT NULL PRIMARY KEY,
 team_id BIGINT UNSIGNED NOT NULL,
 name VARCHAR(100) NOT NULL, description VARCHAR(500) NULL,
 required_points INT NOT NULL, is_active BOOLEAN NOT NULL DEFAULT TRUE,
 version BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME NOT NULL, updated_at DATETIME NOT NULL,
 UNIQUE KEY uk_tlw_team_id (team_id,id), KEY idx_tlw_active (team_id,is_active,id),
 CONSTRAINT ck_tlw_points CHECK (required_points BETWEEN 1 AND 1000000),
 CONSTRAINT ck_tlw_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE team_loyalty_accounts (
 id BINARY(16) NOT NULL PRIMARY KEY,
 team_id BIGINT UNSIGNED NOT NULL, user_id BIGINT UNSIGNED NOT NULL,
 balance INT NOT NULL DEFAULT 0, lifetime_earned BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME NOT NULL, updated_at DATETIME NOT NULL,
 UNIQUE KEY uk_tla_owner (team_id,user_id), UNIQUE KEY uk_tla_team_id (team_id,id),
 KEY idx_tla_user (user_id,id),
 CONSTRAINT ck_tla_balance CHECK (balance >= 0),
 CONSTRAINT ck_tla_lifetime CHECK (lifetime_earned >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE team_loyalty_transactions (
 id BINARY(16) NOT NULL PRIMARY KEY,
 team_id BIGINT UNSIGNED NOT NULL, account_id BINARY(16) NOT NULL,
 user_id BIGINT UNSIGNED NOT NULL, actor_user_id BIGINT UNSIGNED NOT NULL,
 kind VARCHAR(10) NOT NULL, channel VARCHAR(20) NOT NULL, request_operation VARCHAR(20) NOT NULL,
 delta INT NOT NULL, balance_after INT NOT NULL,
 rule_id BINARY(16) NULL, reward_id BINARY(16) NULL, source_location_id BIGINT UNSIGNED NULL,
 award_on DATE NULL, label VARCHAR(100) NOT NULL,
 rule_or_reward_version BIGINT NOT NULL, program_version BIGINT NOT NULL,
 request_key BINARY(16) NOT NULL, payload_hash BINARY(32) NOT NULL,
 created_at DATETIME NOT NULL, updated_at DATETIME NOT NULL,
 UNIQUE KEY uk_tlt_team_id (team_id,id),
 UNIQUE KEY uk_tlt_receipt (team_id,actor_user_id,request_operation,request_key),
 UNIQUE KEY uk_tlt_daily (team_id,user_id,rule_id,award_on),
 KEY idx_tlt_user (team_id,user_id,id DESC), KEY idx_tlt_account (team_id,account_id,id DESC),
 CONSTRAINT fk_tlt_account FOREIGN KEY (team_id,account_id) REFERENCES team_loyalty_accounts(team_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT fk_tlt_rule FOREIGN KEY (team_id,rule_id) REFERENCES team_loyalty_rules(team_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT fk_tlt_reward FOREIGN KEY (team_id,reward_id) REFERENCES team_loyalty_rewards(team_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT ck_tlt_balance CHECK (balance_after >= 0),
 CONSTRAINT ck_tlt_versions CHECK (rule_or_reward_version >= 0 AND program_version >= 0),
 CONSTRAINT ck_tlt_kind CHECK (kind IN ('AWARD','REDEEM')),
 CONSTRAINT ck_tlt_channel CHECK (channel IN ('STAFF','SELF_AUTO','SELF_APPROVED')),
 CONSTRAINT ck_tlt_item CHECK (
   (kind='AWARD' AND delta>0 AND rule_id IS NOT NULL AND reward_id IS NULL)
   OR (kind='REDEEM' AND delta<0 AND rule_id IS NULL AND reward_id IS NOT NULL AND award_on IS NULL AND channel='STAFF' AND source_location_id IS NULL)),
 CONSTRAINT ck_tlt_operation CHECK (
   (channel='SELF_AUTO' AND kind='AWARD' AND actor_user_id=user_id AND request_operation='SELF_SUBMIT' AND source_location_id IS NOT NULL AND award_on IS NOT NULL)
   OR (channel='SELF_APPROVED' AND kind='AWARD' AND actor_user_id<>user_id AND request_operation='APPROVE' AND source_location_id IS NOT NULL AND award_on IS NOT NULL)
   OR (channel='STAFF' AND ((kind='AWARD' AND source_location_id IS NULL AND request_operation='STAFF_AWARD') OR (kind='REDEEM' AND request_operation='REDEEM'))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE team_loyalty_award_requests (
 id BINARY(16) NOT NULL PRIMARY KEY,
 team_id BIGINT UNSIGNED NOT NULL, user_id BIGINT UNSIGNED NOT NULL,
 rule_id BINARY(16) NOT NULL, source_location_id BIGINT UNSIGNED NOT NULL,
 program_version BIGINT NOT NULL, rule_version BIGINT NOT NULL, points INT NOT NULL,
 rule_name VARCHAR(100) NOT NULL, location_name VARCHAR(100) NOT NULL,
 award_on DATE NOT NULL, expires_at DATETIME NOT NULL,
 status VARCHAR(20) NOT NULL, reason VARCHAR(30) NULL,
 version BIGINT NOT NULL DEFAULT 0, verification_code CHAR(8) NOT NULL,
 submit_key BINARY(16) NOT NULL, submit_payload_hash BINARY(32) NOT NULL,
 terminal_actor_user_id BIGINT UNSIGNED NULL, terminal_action VARCHAR(10) NULL,
 action_key BINARY(16) NULL, action_payload_hash BINARY(32) NULL,
 transaction_id BINARY(16) NULL, closed_at DATETIME NULL,
 pending_unique_key TINYINT GENERATED ALWAYS AS (IF(status='PENDING',1,NULL)) VIRTUAL,
 created_at DATETIME NOT NULL, updated_at DATETIME NOT NULL,
 UNIQUE KEY uk_tlar_team_id (team_id,id),
 UNIQUE KEY uk_tlar_submit (team_id,user_id,submit_key),
 UNIQUE KEY uk_tlar_code (team_id,verification_code),
 UNIQUE KEY uk_tlar_pending (team_id,user_id,rule_id,award_on,pending_unique_key),
 UNIQUE KEY uk_tlar_action (team_id,terminal_actor_user_id,terminal_action,action_key),
 UNIQUE KEY uk_tlar_transaction (team_id,transaction_id),
 KEY idx_tlar_user (user_id,id DESC), KEY idx_tlar_status (team_id,status,id DESC),
 KEY idx_tlar_pending_current (team_id,status,program_version,rule_version,award_on,expires_at,id),
 CONSTRAINT fk_tlar_rule FOREIGN KEY (team_id,rule_id) REFERENCES team_loyalty_rules(team_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT fk_tlar_transaction FOREIGN KEY (team_id,transaction_id) REFERENCES team_loyalty_transactions(team_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT ck_tlar_status CHECK (status IN ('PENDING','APPROVED','CANCELLED','REJECTED','EXPIRED','INVALIDATED','SUPERSEDED')),
 CONSTRAINT ck_tlar_points CHECK (points BETWEEN 1 AND 10000),
 CONSTRAINT ck_tlar_versions CHECK (version>=0 AND program_version>=0 AND rule_version>=0),
 CONSTRAINT ck_tlar_expiry CHECK (expires_at>created_at),
 CONSTRAINT ck_tlar_code CHECK (REGEXP_LIKE(verification_code,'^[A-Z2-7]{8}$','c')),
 CONSTRAINT ck_tlar_terminal CHECK ((
   (status='PENDING' AND closed_at IS NULL AND reason IS NULL AND transaction_id IS NULL AND terminal_actor_user_id IS NULL AND terminal_action IS NULL AND action_key IS NULL AND action_payload_hash IS NULL)
   OR (status='APPROVED' AND transaction_id IS NOT NULL AND closed_at IS NOT NULL AND reason IS NULL AND terminal_actor_user_id IS NOT NULL AND terminal_actor_user_id<>user_id AND terminal_action='APPROVE' AND action_key IS NOT NULL AND action_payload_hash IS NOT NULL)
   OR (status='CANCELLED' AND transaction_id IS NULL AND closed_at IS NOT NULL AND reason='USER_CANCELLED' AND terminal_actor_user_id=user_id AND terminal_action='CANCEL' AND action_key IS NOT NULL AND action_payload_hash IS NOT NULL)
   OR (status='REJECTED' AND transaction_id IS NULL AND closed_at IS NOT NULL AND reason='STAFF_REJECTED' AND terminal_actor_user_id IS NOT NULL AND terminal_action='REJECT' AND action_key IS NOT NULL AND action_payload_hash IS NOT NULL)
   OR (status IN ('EXPIRED','INVALIDATED','SUPERSEDED') AND transaction_id IS NULL AND closed_at IS NOT NULL AND reason IS NOT NULL AND terminal_actor_user_id IS NULL AND terminal_action IS NULL AND action_key IS NULL AND action_payload_hash IS NULL
       AND ((status='EXPIRED' AND reason IN ('TTL_EXPIRED','DAY_CHANGED'))
         OR (status='INVALIDATED' AND reason IN ('VERSION_CHANGED','LOCATION_INACTIVE','TEAM_INACTIVE','USER_INACTIVE','INELIGIBLE','POINT_LIMIT'))
         OR (status='SUPERSEDED' AND reason='DAILY_AWARDED')))) IS TRUE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
