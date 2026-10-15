-- F03.10 代理出席: イベント代理出席委任状テーブルを作成する。
-- 設計書: docs/features/F03.10_proxy_attendance.md §3.4
--
-- schedule_delegations（§3.3）と同型 + F08.3 投票代理との任意連携カラム
-- （proxy_vote_session_id / proxy_delegation_id）を持つ。これらはクロスドメイン参照（proxyvote ドメイン）
-- のため FK なし・インデックスのみ（原則1）。proxy_delegations はイベント駆動で別トランザクション作成し、
-- proxy_delegation_id は連携作成後に設定される（原則5）。
--
-- アクティブ委任の一意性・deleted_at の扱いは schedule_delegations（§3.3）と同方針。
CREATE TABLE event_delegations (
  id                    BINARY(16)      NOT NULL           COMMENT 'UUIDv7 主キー',
  event_id              BIGINT UNSIGNED NOT NULL           COMMENT 'FK → events.id',
  delegator_id          BIGINT UNSIGNED NOT NULL           COMMENT '委任者 user_id（クロスドメイン、FK なし）',
  delegate_id           BIGINT UNSIGNED NOT NULL           COMMENT '代理人 user_id（クロスドメイン、FK なし）',
  organization_id       BIGINT UNSIGNED NULL               COMMENT '親イベントから非正規化（組織スコープ時。team_id と XOR）',
  team_id               BIGINT UNSIGNED NULL               COMMENT '親イベントから非正規化（チームスコープ時。organization_id と XOR）',
  status                VARCHAR(20)     NOT NULL DEFAULT 'PENDING'
                        COMMENT 'PENDING / ACCEPTED / REJECTED / CANCELLED',
  reason                VARCHAR(500)                       COMMENT '委任理由（任意）',
  proxy_vote_session_id BIGINT UNSIGNED                    COMMENT 'F08.3 任意連携（クロスドメイン、FK なし）',
  proxy_delegation_id   BIGINT UNSIGNED                    COMMENT 'proxy_delegations.id（連携作成後に設定。クロスドメイン、FK なし）',
  reviewed_at           DATETIME                           COMMENT '承認/拒否/取消日時',
  created_at            DATETIME        NOT NULL,
  updated_at            DATETIME        NOT NULL,
  deleted_at            DATETIME        NULL               COMMENT 'AbstractTenantAwareRepository 互換用（運用上は常に NULL。ライフサイクルは status で表現）',
  -- アクティブ（PENDING/ACCEPTED）な委任のみ delegator_id を保持する生成カラム。
  -- REJECTED / CANCELLED では NULL になり、NULL は UNIQUE 制約の重複対象外のため再作成可能。
  active_delegator_marker BIGINT UNSIGNED
    AS (CASE WHEN status IN ('PENDING','ACCEPTED') THEN delegator_id END) STORED
    COMMENT 'アクティブ委任の一意性を DB 強制するための生成カラム',
  PRIMARY KEY (id),
  INDEX idx_event_deleg_event      (event_id),
  INDEX idx_event_deleg_delegator  (event_id, delegator_id),
  INDEX idx_event_deleg_delegate   (event_id, delegate_id),
  INDEX idx_event_deleg_status     (event_id, status),
  INDEX idx_event_deleg_pv_session (proxy_vote_session_id),
  INDEX idx_event_deleg_org        (organization_id),
  -- 「1 イベント × 1 委任者 につきアクティブ委任は 1 件」を DB レベルで強制。
  -- MySQL は部分ユニークインデックス非対応のため、生成カラム + UNIQUE で代替する。
  UNIQUE KEY uq_active_delegation (event_id, active_delegator_marker),
  CONSTRAINT fk_event_deleg_event
    FOREIGN KEY (event_id) REFERENCES events(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='イベント代理出席';
