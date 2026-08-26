-- =====================================================================
-- queue_tickets.guest_phone の欠落是正
--
-- QueueTicketEntity は 2026-03-23（コミット e84d2680b「TODO一掃(E): AES-256暗号化」）で
-- @Convert(EncryptedStringConverter) 付きの guestPhone フィールドを追加したが、
-- 対応する Flyway migration が伴っていなかった。
-- そのため Flyway でスキーマを構築した環境（本番 / staging / 新規開発環境）では
-- Hibernate が queue_tickets.guest_phone を含む SELECT / INSERT を発行して
-- Unknown column で失敗し、順番待ちチケット系 API が全滅する。
--
-- 型は EncryptedStringConverter（AES-256-GCM → Base64 文字列）の既存前例に合わせて TEXT とする。
-- 前例: V9.053__add_encryption_to_pii_columns.sql
--       （users.phone_number / resident_registry.phone / receipts.issuer_phone などを
--         VARCHAR → TEXT に MODIFY し「（AES-256-GCM暗号化）」を列コメントで明示）
-- Entity 側も @Column(columnDefinition = "TEXT") で TEXT を宣言しており一致する。
--
-- 既存行（ゲスト以外のチケットを含む）が存在するため NULL 許容で追加する。
-- ゲスト以外のチケットでは常に NULL である。
-- =====================================================================

ALTER TABLE queue_tickets
    ADD COLUMN guest_phone TEXT NULL
        COMMENT 'ゲスト電話番号（AES-256-GCM暗号化・Base64。ゲスト以外は NULL）'
        AFTER guest_name;
