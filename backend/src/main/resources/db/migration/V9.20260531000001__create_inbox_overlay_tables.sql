-- =============================================================
-- F04.11: 統合通知インボックス（あとで見る仕分け）
--   per-user triage オーバーレイ 3 表（状態 / ラベル / リンク）
--   設計書: docs/features/F04.11_notification_inbox/01_data_model.md §6
--
--   B 案（仮想インボックス）採用のため、通知本体テーブルへの
--   カラム追加・行生成は行わない。triage 状態（スヌーズ/アーカイブ/
--   ラベル）のみを本 3 表に保持する。
--   全表 UUIDv7 主キー（CLAUDE.md 原則6）・クロスドメイン FK なし（原則1）。
-- =============================================================

-- -------------------------------------------------------------
-- inbox_item_states: per-user の triage 状態オーバーレイ（遅延生成）
-- -------------------------------------------------------------
CREATE TABLE inbox_item_states (
    id            BINARY(16)  NOT NULL COMMENT 'UUIDv7 主キー（原則6）',
    user_id       BIGINT      NOT NULL COMMENT 'users.id（FK制約なし。クロスドメインFK禁止原則1）',
    source_type   VARCHAR(30) NOT NULL COMMENT '通知ソース種別（NOTIFICATION/ANNOUNCEMENT/MENTION/CONFIRMABLE/TODO_DUE）',
    source_id     BIGINT      NOT NULL COMMENT '各ソーステーブルのPK（FK制約なし・論理参照）',
    snoozed_until DATETIME(6) NULL     COMMENT 'スヌーズ解除予定時刻。NULL=非スヌーズ。now超過で受信箱へ自動復帰',
    archived_at   DATETIME(6) NULL     COMMENT 'アーカイブ退避時刻。NULL=受信箱、非NULL=保管庫',
    created_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '作成日時',
    updated_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新日時',
    PRIMARY KEY (id),
    UNIQUE KEY uq_iis_user_source (user_id, source_type, source_id),
    INDEX idx_iis_user_snooze (user_id, snoozed_until),
    INDEX idx_iis_user_archived (user_id, archived_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知インボックス：per-userのスヌーズ/アーカイブ状態（遅延生成オーバーレイ）';

-- -------------------------------------------------------------
-- notification_labels: ユーザー定義の軽量ラベル（論理削除あり）
-- -------------------------------------------------------------
CREATE TABLE notification_labels (
    id          BINARY(16)  NOT NULL COMMENT 'UUIDv7 主キー（原則6）',
    user_id     BIGINT      NOT NULL COMMENT 'users.id（FK制約なし）',
    name        VARCHAR(50) NOT NULL COMMENT 'ラベル名（ユーザー内で重複不可・サービス層検証）',
    color       CHAR(7)     NULL     COMMENT '表示色 #RRGGBB（任意）',
    icon        VARCHAR(40) NULL     COMMENT 'PrimeIcons 名（任意。例 pi-tag）',
    sort_order  INT         NOT NULL DEFAULT 0 COMMENT '表示順（昇順）',
    deleted_at  DATETIME(6) NULL     COMMENT '論理削除（@SQLRestriction）',
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '作成日時',
    updated_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新日時',
    PRIMARY KEY (id),
    INDEX idx_nl_user_sort (user_id, sort_order),
    UNIQUE KEY uq_nl_user_name (user_id, name, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知インボックス：ユーザー定義の軽量ラベル';

-- -------------------------------------------------------------
-- inbox_label_links: ラベル↔通知 多対多リンク（物理削除）
-- -------------------------------------------------------------
CREATE TABLE inbox_label_links (
    id          BINARY(16)  NOT NULL COMMENT 'UUIDv7 主キー（原則6）',
    label_id    BINARY(16)  NOT NULL COMMENT 'notification_labels.id（同一inboxドメイン内・FK制約なし方針）',
    user_id     BIGINT      NOT NULL COMMENT 'users.id（冗長保持・user絞り込み高速化／所有検証）',
    source_type VARCHAR(30) NOT NULL COMMENT '通知ソース種別',
    source_id   BIGINT      NOT NULL COMMENT '各ソースPK（論理参照）',
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '作成日時',
    PRIMARY KEY (id),
    UNIQUE KEY uq_ill_label_source (label_id, source_type, source_id),
    INDEX idx_ill_user_source (user_id, source_type, source_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知インボックス：ラベルと通知の多対多リンク';
