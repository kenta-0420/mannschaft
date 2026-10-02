-- F04.12: チャットからチーム/組織への承諾型招待
-- chat_messages に招待カード種別と参照トークンIDを追加する。
--
-- 設計書: docs/features/F04.12_chat_membership_invite.md §3 変更①
-- - message_type: メッセージ種別（TEXT / INVITE_CARD）。将来拡張のため VARCHAR + アプリ層 enum 検証（ENUM にしない）
-- - invite_token_id: INVITE_CARD が参照する招待トークン（invite_tokens.id）。
--   クロスドメイン（chat ドメイン → role ドメイン）のため FK は張らず INDEX のみ（原則1）。
-- 既存行は DEFAULT 'TEXT' / NULL が入り後方互換を保つ。

ALTER TABLE chat_messages
    ADD COLUMN message_type    VARCHAR(20)     NOT NULL DEFAULT 'TEXT'
        COMMENT 'メッセージ種別 (TEXT/INVITE_CARD)。VARCHAR + アプリ層検証',
    ADD COLUMN invite_token_id BIGINT UNSIGNED NULL
        COMMENT '招待カードが参照する invite_tokens.id（クロスドメイン・FK 張らない）';

-- 招待カードの逆引き（トークン失効時にカード状態を再解決）
CREATE INDEX idx_chat_messages_invite_token ON chat_messages (invite_token_id);
