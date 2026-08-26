-- 非公開村への招待機構（トークン式）。
-- 平文トークンは保存せず、SHA-256 ハッシュのみを保存する（token_hash に一意インデックス）。
-- expires_at / max_uses は NOT NULL とし、無期限・無制限の招待を作成不能にする。
-- max_uses を NULL 許容にせず必須とした理由: 「無制限」を型として作れる余地を残すと
-- 上限チェックが漏れた実装に気付けなくなるため、無制限が必要な運用は「NULL 指定は400で拒否」
-- という形で境界値を消し込み、値の指定は常に必須とする。
-- village_id は同一ドメイン外への実FKを張らず、インデックスのみで整合性はアプリ層が保証する。
-- created_by_membership_id は village_memberships.invited_by_membership_id（既存列・BINARY(16)）と
-- 型を揃える。受諾時にそのまま invited_by_membership_id へ引き継ぐため、user_id ではなく
-- 発行者の村メンバーシップIDで保持する。

CREATE TABLE village_invitations (
    id                        BINARY(16)      NOT NULL                                COMMENT 'UUIDv7 PK',
    village_id                BINARY(16)      NOT NULL,
    token_hash                VARCHAR(64)     NOT NULL                                COMMENT 'トークンのSHA-256ハッシュ(hex)。平文は保存しない',
    target_user_id            BIGINT UNSIGNED NULL                                    COMMENT '指名制招待の宛先。NULLならリンク型招待',
    expires_at                DATETIME(6)     NOT NULL                                COMMENT '無期限の招待を作れないようNOT NULL',
    max_uses                  INT             NOT NULL                                COMMENT '無制限の招待を作れないようNOT NULL',
    used_count                INT             NOT NULL DEFAULT 0,
    revoked_at                DATETIME(6)     NULL,
    created_by_membership_id  BINARY(16)      NOT NULL                                COMMENT '発行した村長/長老の村メンバーシップID',
    created_at                DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_village_invitations_token_hash (token_hash),
    KEY idx_village_invitations_village_id (village_id),
    KEY idx_village_invitations_target_user_id (target_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='非公開村への招待（トークンハッシュ式）';
