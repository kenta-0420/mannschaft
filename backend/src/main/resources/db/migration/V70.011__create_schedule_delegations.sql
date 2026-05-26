-- F03.10 代理出席: スケジュール代理出席委任状テーブルを作成する。
-- 設計書: docs/features/F03.10_proxy_attendance.md §3.3
--
-- UUIDv7 主キー（CLAUDE.md 原則6）。delegator_id / delegate_id はクロスドメイン参照のため
-- FK なし・インデックスのみ（原則1）。organization_id / team_id は親スケジュールのスコープから
-- 非正規化して保持（XOR: いずれか一方が NOT NULL。原則7）。
--
-- アクティブ（PENDING/ACCEPTED）委任の一意性は生成カラム active_delegator_marker + UNIQUE KEY で
-- DB レベル保証する。REJECTED / CANCELLED 遷移後はマーカーが NULL になり、MySQL の UNIQUE 制約上
-- NULL は重複可のため、同一 (schedule_id, delegator_id) で別の代理人を再指定できる。
--
-- deleted_at は AbstractTenantAwareRepository（原則7）が要求する property を満たすために設ける。
-- 本機能の委任ライフサイクルは status（PENDING/ACCEPTED/REJECTED/CANCELLED）で表現し、論理削除は使わない
-- （設計書 §3 テーブル一覧では論理削除=なし）。deleted_at は常に NULL のまま運用する。
CREATE TABLE schedule_delegations (
  id              BINARY(16)      NOT NULL                 COMMENT 'UUIDv7 主キー',
  schedule_id     BIGINT UNSIGNED NOT NULL                 COMMENT 'FK → schedules.id',
  delegator_id    BIGINT UNSIGNED NOT NULL                 COMMENT '委任者 user_id（クロスドメイン、FK なし）',
  delegate_id     BIGINT UNSIGNED NOT NULL                 COMMENT '代理人 user_id（クロスドメイン、FK なし）',
  organization_id BIGINT UNSIGNED NULL                     COMMENT '親スケジュールから非正規化（組織スコープ時。team_id と XOR）',
  team_id         BIGINT UNSIGNED NULL                     COMMENT '親スケジュールから非正規化（チームスコープ時。organization_id と XOR）',
  status          VARCHAR(20)     NOT NULL DEFAULT 'PENDING'
                  COMMENT 'PENDING / ACCEPTED / REJECTED / CANCELLED',
  reason          VARCHAR(500)                             COMMENT '委任理由（任意）',
  reviewed_at     DATETIME                                 COMMENT '承認/拒否/取消日時',
  created_at      DATETIME        NOT NULL,
  updated_at      DATETIME        NOT NULL,
  deleted_at      DATETIME        NULL                     COMMENT 'AbstractTenantAwareRepository 互換用（運用上は常に NULL。ライフサイクルは status で表現）',
  -- アクティブ（PENDING/ACCEPTED）な委任のみ delegator_id を保持する生成カラム。
  -- REJECTED / CANCELLED では NULL になり、NULL は UNIQUE 制約の重複対象外のため再作成可能。
  active_delegator_marker BIGINT UNSIGNED
    AS (CASE WHEN status IN ('PENDING','ACCEPTED') THEN delegator_id END) STORED
    COMMENT 'アクティブ委任の一意性を DB 強制するための生成カラム',
  PRIMARY KEY (id),
  INDEX idx_sched_deleg_schedule  (schedule_id),
  INDEX idx_sched_deleg_delegator (schedule_id, delegator_id),
  INDEX idx_sched_deleg_delegate  (schedule_id, delegate_id),
  INDEX idx_sched_deleg_status    (schedule_id, status),
  INDEX idx_sched_deleg_org       (organization_id),
  -- 「1 スケジュール × 1 委任者 につきアクティブ委任は 1 件」を DB レベルで強制。
  -- MySQL は部分ユニークインデックス非対応のため、生成カラム + UNIQUE で代替する。
  UNIQUE KEY uq_active_delegation (schedule_id, active_delegator_marker),
  CONSTRAINT fk_sched_deleg_schedule
    FOREIGN KEY (schedule_id) REFERENCES schedules(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='スケジュール代理出席';
