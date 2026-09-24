-- CMP-260920-1040 F04.9 確認通知「宛先指定」戦役
-- 軍議第8版確定稿 §3.1 / §9.1 / §10.1 のデータモデルを反映する。
-- クロスドメイン FK は張らない（CLAUDE.md 原則1）。CASCADE は同一ドメイン内のみ（原則2）。

-- ---------------------------------------------------------------------------
-- 1. confirmable_notifications への列追加（§3.1・§9.1・§10.1）
-- ---------------------------------------------------------------------------
ALTER TABLE confirmable_notifications
    ADD COLUMN delivery_status ENUM('QUEUED', 'DELIVERING', 'DELIVERED', 'PARTIALLY_FAILED', 'STOPPED')
        NOT NULL DEFAULT 'DELIVERED'
        COMMENT '配信状態。既存行と同期経路はDELIVERED（§3.1）。非同期経路はQUEUEDから開始する'
        AFTER status,
    ADD COLUMN delivered_count INT NOT NULL DEFAULT 0
        COMMENT 'ワーカーが作った受信者行の数（§3.1）'
        AFTER total_recipient_count,
    ADD COLUMN unconfirmed_count INT NOT NULL DEFAULT 0
        COMMENT '未確認件数のカウンタ（§10.1）。親の行をFOR UPDATEでロックしたトランザクションのみ更新してよい';

-- 既存行の unconfirmed_count を、受信者表から「除外されておらず未確認」の件数で埋める（§10.1）
UPDATE confirmable_notifications cn
SET cn.unconfirmed_count = (
    SELECT COUNT(*)
    FROM confirmable_notification_recipients r
    WHERE r.confirmable_notification_id = cn.id
      AND r.is_confirmed = FALSE
      AND r.excluded_at IS NULL
);

-- ---------------------------------------------------------------------------
-- 2. confirmable_notification_targets（新設・UuidV7Entity。§3.1）
--    送信時点の宛先指定を凍結して保存する（ワーカーの再開・監査用）
-- ---------------------------------------------------------------------------
CREATE TABLE confirmable_notification_targets (
    id                          BINARY(16) NOT NULL,
    confirmable_notification_id BIGINT UNSIGNED NOT NULL COMMENT 'confirmable_notifications.id（同一ドメイン内FK・CASCADE）',
    target_type                 ENUM('ORGANIZATION', 'TEAM') NOT NULL,
    target_id                   BIGINT UNSIGNED NOT NULL,
    created_at                  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_cnt_notification (confirmable_notification_id),
    CONSTRAINT fk_cnt_notification FOREIGN KEY (confirmable_notification_id)
        REFERENCES confirmable_notifications (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='確認通知の送信時点の宛先ターゲット（凍結）';

-- ---------------------------------------------------------------------------
-- 3. confirmable_recipient_groups（新設・UuidV7Entity。§3.1）
-- ---------------------------------------------------------------------------
CREATE TABLE confirmable_recipient_groups (
    id          BINARY(16) NOT NULL,
    scope_type  ENUM('TEAM', 'ORGANIZATION') NOT NULL,
    scope_id    BIGINT UNSIGNED NOT NULL,
    name        VARCHAR(100) NOT NULL,
    created_by  BIGINT UNSIGNED NULL,
    deleted_at  DATETIME NULL,
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_crg_scope (scope_type, scope_id, deleted_at),
    CONSTRAINT fk_crg_created_by FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='確認通知の宛先グループ';

-- 同じスコープ・同じ名前の非削除グループは1件に限る（§3.1「同じスコープで名前を一意にする（削除されていない行の中で）」）
-- MySQL は NULL を複数許容する UNIQUE の性質を使い、deleted_at を複合キーへ含める設計は取れないため、
-- アプリ層（Service）で「同スコープ・deleted_at IS NULL・同名」の重複を検証する（AC-31 409）。
-- （生成列 + UNIQUE にする案は Flyway からの一撃移行を避けるため見送り。試練・出陣時の申し送り事項とする）

-- ---------------------------------------------------------------------------
-- 4. confirmable_recipient_group_targets（新設。§3.1）
-- ---------------------------------------------------------------------------
CREATE TABLE confirmable_recipient_group_targets (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    group_id    BINARY(16) NOT NULL COMMENT 'confirmable_recipient_groups.id（同一ドメイン内FK・CASCADE）',
    target_type ENUM('ORGANIZATION', 'TEAM') NOT NULL,
    target_id   BIGINT UNSIGNED NOT NULL,
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_crgt_group_target (group_id, target_type, target_id),
    CONSTRAINT fk_crgt_group FOREIGN KEY (group_id)
        REFERENCES confirmable_recipient_groups (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='確認通知の宛先グループのターゲット';

-- ---------------------------------------------------------------------------
-- 5. confirmable_notification_templates に既定の宛先グループを追加（§3.1）
--    グループが論理削除されたら、参照しても無視して「既定＝配下すべて」に戻す（アプリ層で対応。FK は張らない）
-- ---------------------------------------------------------------------------
ALTER TABLE confirmable_notification_templates
    ADD COLUMN default_recipient_group_id BINARY(16) NULL
        COMMENT '既定の宛先グループ（confirmable_recipient_groups.id）。クロスドメインではなく同一ドメイン内参照だがFKは張らない（削除済みグループの無視処理をアプリ層で行うため）'
        AFTER default_priority;
