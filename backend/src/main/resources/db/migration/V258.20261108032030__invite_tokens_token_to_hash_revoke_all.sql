-- CMP-260826-1457 W1 P1: 招待トークン（共有リンク型・承諾型）の平文保存をハッシュ保存へ統一する。
-- 平文トークンは保存せず SHA-256 の hex(64字)だけを token_hash に持つ（village_invitations と同じ流儀）。
-- 既存の平文は捨てる（御裁可: 既存の招待はすべて失効・猶予なし）。ハッシュ化しても平文は復元できないため、
-- 既存行は乱数から作ったハッシュで埋めて「どの平文でも照合に当たらない」状態にし、行は削除せず全件を失効させる。
--   - 行・ID・スコープ・宛先・利用回数・上限・発行者は残す（監査・chat_messages.invite_token_id の参照を壊さない）
--   - revoked_at が既にある行は日時を保つ（COALESCE）。未失効の行だけ UTC 現在時刻で失効させる
-- MySQL は隣接文字列リテラルを連結しないため COMMENT は1リテラルで書く。

ALTER TABLE invite_tokens ADD COLUMN token_hash VARCHAR(64) NULL COMMENT '招待トークンのSHA-256ハッシュ(hex)。平文は保存しない' AFTER id;

UPDATE invite_tokens
   SET token_hash = SHA2(CONCAT(UUID(), UUID(), RAND(), id), 256),
       revoked_at = COALESCE(revoked_at, UTC_TIMESTAMP());

ALTER TABLE invite_tokens DROP INDEX uq_invite_tokens_token;
ALTER TABLE invite_tokens DROP COLUMN token;
ALTER TABLE invite_tokens MODIFY COLUMN token_hash VARCHAR(64) NOT NULL COMMENT '招待トークンのSHA-256ハッシュ(hex)。平文は保存しない';
ALTER TABLE invite_tokens ADD CONSTRAINT uq_invite_tokens_token_hash UNIQUE (token_hash);
