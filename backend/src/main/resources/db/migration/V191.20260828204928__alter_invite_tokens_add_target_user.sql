-- F04.12: チャットからチーム/組織への承諾型招待
-- invite_tokens に承諾型招待の宛先ユーザーを追加する。
--
-- 設計書: docs/features/F04.12_chat_membership_invite.md §3 変更②
-- - target_user_id: NULL = 従来の共有リンク型（誰でも参加可）
--                   非 NULL = 宛先付き承諾型（この user のみ承諾可）
--   users は user ドメインのためクロスドメイン FK は張らず INDEX のみ（原則1）。
-- 既存の共有リンク型トークンは NULL のまま影響を受けない（併存設計）。

ALTER TABLE invite_tokens
    ADD COLUMN target_user_id BIGINT UNSIGNED NULL
        COMMENT '承諾型招待の宛先ユーザー（users.id）。NULL=共有リンク型 / 非NULL=宛先付き承諾型（クロスドメイン・FK 張らない）';

-- 宛先ユーザー宛ての PENDING 招待一覧
CREATE INDEX idx_it_target_user ON invite_tokens (target_user_id);
