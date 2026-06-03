-- F08.7.1 / 06 大会ごとの書類提出受付: 提出枠（form_template と 大会/ディビジョンを結ぶ薄い連結）。
--
-- 既存 F05.6 ワークフロー＋forms（form_templates / form_submissions / workflow_requests /
-- 承認ステップ / workflow_request_attachments）を「大会スコープ」で再利用する。本マイグレーションは
-- 「主催者が提出枠を定義し、各チームが期限までに提出、主催者が受理／差戻しする提出インボックス」を
-- 構成するための薄い連結テーブルのみを新設する。汎用の提出／承認エンジンは新設しない。
--
-- 設計書: docs/features/F08.7.1_tournament_extensions/06_document_submission.md §2 / §2.1
--
-- 原則準拠:
--   - 主キーは UUIDv7（原則6・UuidV7Entity 継承）。id は BINARY(16)。
--   - クロスドメイン FK は張らない（原則1）。form_template_id / tournament_id / division_id / team_id は
--     ID 値のみ保持し、参照整合性はアプリ層で保証する。
--   - 子テーブル tournament_submission_requirement_target は同一ドメイン（requirement の子）のため
--     CASCADE 削除を許可する（原則2）。
--   - 論理削除（deleted_at）で履歴を保持し、クロスドメイン CASCADE は使わない（原則2・3）。

-- 提出枠（どの form_template を、どの大会／ディビジョンの、誰が、いつまでに提出するか）
CREATE TABLE tournament_submission_requirement (
    id               BINARY(16)   NOT NULL COMMENT 'UUIDv7（原則6）',
    tournament_id    BIGINT       NOT NULL COMMENT '対象大会（tournaments.id・FK なし／原則1）',
    division_id      BIGINT       NULL     COMMENT '対象ディビジョン（tournament_divisions.id。NULL=大会全体・FK なし）',
    form_template_id BIGINT       NOT NULL COMMENT 'forms/workflow ドメインの form_templates.id（FK なし／原則1）',
    title            VARCHAR(255) NOT NULL COMMENT '提出枠の表示名（例「参加申込書」「選手登録一覧」）',
    description      TEXT         NULL     COMMENT '補足説明',
    deadline         DATETIME     NULL     COMMENT '提出締切（NULL=締切なし）',
    target_scope     VARCHAR(20)  NOT NULL DEFAULT 'ALL_TEAMS' COMMENT '対象＝全参加チーム(ALL_TEAMS) / 特定チーム(SPECIFIC_TEAMS)',
    requires_payment TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '受理条件に「大会参加費の支払い済み」を課すか（領域⑦連携）',
    organization_id  BIGINT       NOT NULL COMMENT '主催組織（テナント絞り込み・クォータ帰属）',
    created_by       BIGINT       NOT NULL COMMENT '作成した主催組織 ADMIN の user_id（退会時も履歴保持／設計書 §7）',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at       DATETIME     NULL     COMMENT '論理削除（履歴保持・クロスドメイン CASCADE なし）',

    PRIMARY KEY (id),
    KEY idx_submission_req_tournament (tournament_id, division_id),
    KEY idx_submission_req_org (organization_id),
    KEY idx_submission_req_template (form_template_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F08.7.1/06 大会提出枠（form_template と大会/ディビジョンの薄い連結）';

-- 特定チームを対象にする場合の対象チーム明細（target_scope = SPECIFIC_TEAMS のとき）
CREATE TABLE tournament_submission_requirement_target (
    id             BINARY(16) NOT NULL COMMENT 'UUIDv7（原則6）',
    requirement_id BINARY(16) NOT NULL COMMENT '親 tournament_submission_requirement.id（同一ドメイン）',
    team_id        BIGINT     NOT NULL COMMENT '対象チーム（teams.id・FK なし／原則1）',
    created_at     DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    KEY idx_submission_target_req (requirement_id),
    -- 同一提出枠内のチーム重複防止
    UNIQUE KEY uq_submission_target (requirement_id, team_id),
    -- 同一ドメインの親子のため CASCADE 削除を許可（原則2）
    CONSTRAINT fk_submission_target_req FOREIGN KEY (requirement_id)
        REFERENCES tournament_submission_requirement (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F08.7.1/06 大会提出枠の対象チーム明細（SPECIFIC_TEAMS 用）';

-- 提出と大会提出枠の連結（form_submissions へ列追加。設計書 §2.1 B-3 根治）。
-- workflow_requests.source_id は BIGINT のため UUID の requirement_id を入れられない。
-- そこで form_submissions 側に BINARY(16) の ID 参照列を追加して連結する（FK なし／原則1）。
-- workflow ↔ form_submission の native 連結（BIGINT 同士）は一切変更しない（母体無改変）。
ALTER TABLE form_submissions
    ADD COLUMN tournament_submission_requirement_id BINARY(16) NULL
        COMMENT '大会提出枠への ID 参照（F08.7.1/06・FK なし／原則1）',
    ADD INDEX idx_form_submissions_tournament_req (tournament_submission_requirement_id);
