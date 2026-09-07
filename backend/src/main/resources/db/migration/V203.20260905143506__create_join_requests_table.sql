-- 柱③-A「MEMBER 参加申請（join request）」テーブル（CMP-260901-1538）
-- PUBLIC な TEAM/ORGANIZATION（lifecycle_status=ACTIVE のみ）への MEMBER としての参加申請を管理する。
-- team_id / organization_id はどちらか一方のみ非 NULL（invite_tokens と同じ流儀）。
-- クロスドメイン FK は張らない（原則1）。teams/organizations は各ドメインのため FK なし・インデックスのみ。

CREATE TABLE join_requests (
    id                  BINARY(16)      NOT NULL                                COMMENT 'UUIDv7 PK',
    team_id             BIGINT UNSIGNED NULL                                    COMMENT 'チームスコープ時の対象チームID（organization_idと排他）',
    organization_id     BIGINT UNSIGNED NULL                                    COMMENT '組織スコープ時の対象組織ID（team_idと排他）',
    requester_user_id   BIGINT UNSIGNED NOT NULL                                COMMENT '申請者ユーザーID',
    message             VARCHAR(500)    NULL                                    COMMENT '申請時の任意の一言メッセージ',
    status              VARCHAR(20)     NOT NULL DEFAULT 'PENDING'              COMMENT 'PENDING/APPROVED/REJECTED',
    reviewer_user_id    BIGINT UNSIGNED NULL                                    COMMENT '審査したADMIN/DEPUTY_ADMINのユーザーID',
    reviewed_at         TIMESTAMP(6)    NULL,
    review_comment      VARCHAR(500)    NULL,
    created_at          TIMESTAMP(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          TIMESTAMP(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    -- PENDING 中の重複申請を DB 層でも拒否する（サービス層の冪等応答と二重防御）。
    -- status を含めるため APPROVED/REJECTED 後の再申請（新規 PENDING 行）は別キーとして許容される。
    UNIQUE KEY uk_jr_team_pending (team_id, requester_user_id, status),
    UNIQUE KEY uk_jr_org_pending (organization_id, requester_user_id, status),
    KEY idx_jr_team_status (team_id, status),
    KEY idx_jr_org_status (organization_id, status),
    KEY idx_jr_requester (requester_user_id),
    CONSTRAINT chk_jr_scope_exclusive CHECK (
        (team_id IS NOT NULL AND organization_id IS NULL) OR
        (team_id IS NULL AND organization_id IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='MEMBER参加申請（柱③-A・CMP-260901-1538）';
