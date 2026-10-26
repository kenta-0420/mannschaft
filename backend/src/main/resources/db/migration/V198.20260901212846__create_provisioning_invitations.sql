-- 柱②: 販促プロビジョニング②-1（DDL/エンティティ骨格のみ・挙動不変）
-- 事前作成（PROVISIONED）した組織/チームを、招待メールの承諾によって正式な ACTIVE へ
-- 引き上げるためのトークンハッシュ式招待テーブル。
--
-- 本 PR では作成 API・承諾 API・lifecycle_status を書き換えるコードは一切含まない
-- （テーブルを新設するのみで、行を生成する経路が存在しないため挙動不変）。
-- 後続 PR で作成 API・承諾 API・ゲートを同時に実装する（.claude/campaigns/2026-09-01-org-governance.md 柱②）。
--
-- 金型: V191.20260828204929__create_ownership_transfer_offers.sql（承諾型オファーの体裁）
-- - 主キーは UUIDv7（原則6・新規テーブル）→ BINARY(16)
-- - team_id / organization_id の XOR（chk_provisioning_invitations_scope）。FK は張らない（クロスドメインFK禁止・原則1）
-- - トークンは平文非保存。token_hash（SHA-256 hex）のみ保存し、照合はハッシュ化した値で行う
--   （village_invitations / AuthTokenService と同じ方式）
-- - status は VARCHAR + アプリ層検証（ENUM にしない）

CREATE TABLE provisioning_invitations (
    id               BINARY(16)      NOT NULL                                COMMENT 'UUIDv7 PK（原則6）',
    team_id          BIGINT UNSIGNED NULL                                    COMMENT 'プロビジョニング対象がチームの場合（FK 張らない・原則1）',
    organization_id  BIGINT UNSIGNED NULL                                    COMMENT 'プロビジョニング対象が組織の場合（FK 張らない・原則1）',
    invite_email     VARCHAR(255)    NOT NULL                                COMMENT '招待先メールアドレス',
    token_hash       VARCHAR(64)     NOT NULL                                COMMENT 'トークンのSHA-256ハッシュ(hex)。平文トークンは保存しない',
    status           VARCHAR(20)     NOT NULL DEFAULT 'PENDING'              COMMENT 'PENDING/ACCEPTED/CANCELLED/EXPIRED',
    expires_at       TIMESTAMP(6)    NOT NULL                                COMMENT '有効期限',
    accepted_at      TIMESTAMP(6)    NULL                                    COMMENT '承諾日時（ACCEPTED 時のみ）',
    accepted_by      BIGINT UNSIGNED NULL                                    COMMENT '承諾した user ID（FK 張らない）',
    resolved_at      TIMESTAMP(6)    NULL                                    COMMENT '取消/期限確定の処理日時',
    issued_by        BIGINT UNSIGNED NOT NULL                                COMMENT '発行者の user ID（FK 張らない）',
    created_at       TIMESTAMP(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at       TIMESTAMP(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_pi_token_hash (token_hash),
    KEY idx_pi_org_status (organization_id, status)                         COMMENT '組織別 PENDING 招待一覧',
    KEY idx_pi_team_status (team_id, status)                                COMMENT 'チーム別 PENDING 招待一覧',
    CONSTRAINT chk_provisioning_invitations_scope CHECK ((team_id IS NULL) <> (organization_id IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='販促プロビジョニング招待（トークンハッシュ式・柱②-1）';
