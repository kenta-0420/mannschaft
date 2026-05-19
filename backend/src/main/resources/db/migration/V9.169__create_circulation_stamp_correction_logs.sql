-- F05.2 Phase 11 第三陣 3-B
-- 押印訂正履歴テーブル。受信者本人が押印を訂正した際の証跡を保持する。
-- 既存 circulation_recipients に「訂正済みフラグ」を追加するのではなく、
-- 訂正履歴を別テーブル化することで、将来「訂正→再訂正→…」の N 件履歴にも拡張できるようにする。
--
-- CLAUDE.md 原則 6 適用: 新規テーブルは UUIDv7 主キー（BINARY(16)）を用いる。
-- 親 circulation_recipients は BIGINT FK だが、同一ドメイン内なので CASCADE 削除を許可。

CREATE TABLE circulation_stamp_correction_logs (
    id BINARY(16) NOT NULL COMMENT 'UUIDv7 主キー',
    recipient_id BIGINT NOT NULL COMMENT '対象の circulation_recipients.id（同一ドメイン内）',
    document_id BIGINT NOT NULL COMMENT 'circulation_documents.id（検索効率用に冗長保持）',
    corrected_by BIGINT NOT NULL COMMENT '訂正を行ったユーザーID（押印者本人）',
    original_seal_id BIGINT NULL COMMENT '訂正前の印鑑ID',
    original_seal_variant VARCHAR(20) NULL COMMENT '訂正前のバリアント',
    original_tilt_angle SMALLINT NULL COMMENT '訂正前の傾き角度',
    original_is_flipped TINYINT(1) NOT NULL DEFAULT 0 COMMENT '訂正前の反転フラグ',
    reason VARCHAR(255) NULL COMMENT '訂正理由（任意）',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_csc_recipient (recipient_id, created_at),
    KEY idx_csc_document (document_id),
    CONSTRAINT fk_csc_recipient
        FOREIGN KEY (recipient_id) REFERENCES circulation_recipients(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='F05.2: 押印訂正履歴';
