-- F07.4 カルテ機能テーブル群

-- 1. カルテ本体
CREATE TABLE chart_records (
    id                          BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    team_id                     BIGINT UNSIGNED  NOT NULL COMMENT 'FK → teams',
    customer_user_id            BIGINT UNSIGNED  NOT NULL COMMENT 'FK → users（顧客）',
    staff_user_id               BIGINT UNSIGNED  NULL     COMMENT 'FK → users（担当スタッフ）',
    visit_date                  DATE             NOT NULL COMMENT '来店日',
    chief_complaint             TEXT             NULL     COMMENT '主訴・要望',
    treatment_note              TEXT             NULL     COMMENT '施術内容・メモ',
    next_recommendation         TEXT             NULL     COMMENT '次回推奨メモ',
    next_visit_recommended_date DATE             NULL     COMMENT '次回来店推奨日',
    allergy_info                TEXT             NULL     COMMENT 'アレルギー・禁忌情報',
    is_shared_to_customer       BOOLEAN          NOT NULL DEFAULT FALSE COMMENT '顧客共有フラグ',
    is_pinned                   BOOLEAN          NOT NULL DEFAULT FALSE COMMENT 'ピン留めフラグ',
    version                     BIGINT           NOT NULL DEFAULT 0     COMMENT '楽観的ロックバージョン',
    deleted_at                  DATETIME(6)      NULL     COMMENT '論理削除日時',
    created_at                  DATETIME(6)      NOT NULL,
    updated_at                  DATETIME(6)      NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_cr_team_id        (team_id),
    INDEX idx_cr_customer       (customer_user_id),
    INDEX idx_cr_team_customer  (team_id, customer_user_id),
    INDEX idx_cr_visit_date     (team_id, visit_date DESC),
    INDEX idx_cr_staff          (staff_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='カルテ本体';

-- 2. 問診票・同意書
CREATE TABLE chart_intake_forms (
    id                  BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    chart_record_id     BIGINT UNSIGNED  NOT NULL COMMENT 'FK → chart_records（ON DELETE CASCADE）',
    form_type           VARCHAR(20)      NOT NULL COMMENT '種別（QUESTIONNAIRE / CONSENT）',
    content             JSON             NOT NULL COMMENT 'フォーム内容（質問と回答の構造化データ）',
    electronic_seal_id  BIGINT UNSIGNED  NULL     COMMENT 'FK → electronic_seals',
    signed_at           DATETIME(6)      NULL     COMMENT '署名日時',
    is_initial          BOOLEAN          NOT NULL DEFAULT TRUE COMMENT '初回問診フラグ',
    created_at          DATETIME(6)      NOT NULL,
    updated_at          DATETIME(6)      NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_cif_chart      (chart_record_id),
    INDEX idx_cif_type       (chart_record_id, form_type),
    CONSTRAINT fk_cif_chart_record FOREIGN KEY (chart_record_id) REFERENCES chart_records(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='問診票・同意書';

-- 3. 問診票テンプレート定義
CREATE TABLE chart_intake_form_templates (
    id              BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    team_id         BIGINT UNSIGNED  NOT NULL COMMENT 'FK → teams',
    form_type       VARCHAR(20)      NOT NULL COMMENT '種別（QUESTIONNAIRE / CONSENT）',
    template_name   VARCHAR(100)     NOT NULL COMMENT 'テンプレート名',
    template_json   JSON             NOT NULL COMMENT '質問定義',
    is_default      BOOLEAN          NOT NULL DEFAULT FALSE COMMENT 'デフォルトテンプレートフラグ',
    created_at      DATETIME(6)      NOT NULL,
    updated_at      DATETIME(6)      NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_cift_team      (team_id),
    INDEX idx_cift_team_type (team_id, form_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='問診票テンプレート定義';

-- 4. ビフォーアフター写真
CREATE TABLE chart_photos (
    id                      BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    chart_record_id         BIGINT UNSIGNED  NOT NULL COMMENT 'FK → chart_records（ON DELETE CASCADE）',
    photo_type              VARCHAR(20)      NOT NULL COMMENT '写真種別（BEFORE / AFTER / DURING / REFERENCE）',
    s3_key                  VARCHAR(500)     NOT NULL COMMENT 'S3 オブジェクトキー',
    original_filename       VARCHAR(300)     NOT NULL COMMENT 'アップロード時のファイル名',
    file_size_bytes         INT UNSIGNED     NOT NULL COMMENT 'ファイルサイズ（バイト）',
    content_type            VARCHAR(50)      NOT NULL COMMENT 'MIME タイプ',
    sort_order              INT              NOT NULL DEFAULT 0 COMMENT '表示順',
    note                    VARCHAR(300)     NULL     COMMENT '写真メモ',
    is_shared_to_customer   BOOLEAN          NOT NULL DEFAULT FALSE COMMENT '顧客個別共有フラグ',
    created_at              DATETIME(6)      NOT NULL,
    updated_at              DATETIME(6)      NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_cp_chart       (chart_record_id),
    INDEX idx_cp_chart_type  (chart_record_id, photo_type),
    CONSTRAINT fk_cp_chart_record FOREIGN KEY (chart_record_id) REFERENCES chart_records(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ビフォーアフター写真';

-- 5. 身体チャートマーク
CREATE TABLE chart_body_marks (
    id              BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    chart_record_id BIGINT UNSIGNED  NOT NULL COMMENT 'FK → chart_records（ON DELETE CASCADE）',
    body_part       VARCHAR(20)      NOT NULL COMMENT '身体面（FRONT / BACK / LEFT / RIGHT / HEAD / HAND_LEFT / HAND_RIGHT / FOOT_LEFT / FOOT_RIGHT）',
    x_position      DECIMAL(5,2)     NOT NULL COMMENT 'X座標（0.00〜100.00、相対位置%）',
    y_position      DECIMAL(5,2)     NOT NULL COMMENT 'Y座標（0.00〜100.00）',
    mark_type       VARCHAR(20)      NOT NULL COMMENT 'マーク種別（PAIN / NUMBNESS / STIFFNESS / SWELLING / OTHER）',
    severity        TINYINT UNSIGNED NOT NULL COMMENT '重症度（1〜5）',
    note            VARCHAR(300)     NULL     COMMENT 'マーク個別メモ',
    created_at      DATETIME(6)      NOT NULL,
    updated_at      DATETIME(6)      NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_cbm_chart (chart_record_id),
    CONSTRAINT fk_cbm_chart_record FOREIGN KEY (chart_record_id) REFERENCES chart_records(id) ON DELETE CASCADE,
    CONSTRAINT chk_cbm_severity CHECK (severity BETWEEN 1 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='身体チャートマーク';

-- 6. カラー・薬剤レシピ
CREATE TABLE chart_formulas (
    id                      BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    chart_record_id         BIGINT UNSIGNED  NOT NULL COMMENT 'FK → chart_records（ON DELETE CASCADE）',
    product_name            VARCHAR(200)     NOT NULL COMMENT '薬剤・製品名',
    ratio                   VARCHAR(100)     NULL     COMMENT '配合比率（例: 1:1.5）',
    processing_time_minutes SMALLINT UNSIGNED NULL    COMMENT '放置時間（分）',
    temperature             VARCHAR(50)      NULL     COMMENT '加温条件',
    patch_test_date         DATE             NULL     COMMENT 'パッチテスト実施日',
    patch_test_result       VARCHAR(20)      NULL     COMMENT 'パッチテスト結果（POSITIVE / NEGATIVE / NOT_DONE）',
    note                    VARCHAR(500)     NULL     COMMENT '備考',
    sort_order              INT              NOT NULL DEFAULT 0 COMMENT '表示順',
    created_at              DATETIME(6)      NOT NULL,
    updated_at              DATETIME(6)      NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_cf_chart (chart_record_id),
    CONSTRAINT fk_cf_chart_record FOREIGN KEY (chart_record_id) REFERENCES chart_records(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='カラー・薬剤レシピ';

-- 7. セクション設定
CREATE TABLE chart_section_settings (
    id              BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    team_id         BIGINT UNSIGNED  NOT NULL COMMENT 'FK → teams',
    section_type    VARCHAR(30)      NOT NULL COMMENT 'セクション種別（INTAKE_FORM / ALLERGY / PHOTOS / STAFF / BODY_CHART / FORMULA / PATCH_TEST / PROGRESS_GRAPH / NEXT_MEMO）',
    is_enabled      BOOLEAN          NOT NULL DEFAULT TRUE COMMENT '有効/無効',
    created_at      DATETIME(6)      NOT NULL,
    updated_at      DATETIME(6)      NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_css_team_section (team_id, section_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='チームごとのカルテセクション設定';

-- 8. カスタムフィールド定義
CREATE TABLE chart_custom_fields (
    id          BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    team_id     BIGINT UNSIGNED  NOT NULL COMMENT 'FK → teams',
    field_name  VARCHAR(100)     NOT NULL COMMENT 'フィールド名',
    field_type  VARCHAR(20)      NOT NULL COMMENT 'フィールド型（TEXT / NUMBER / DATE / SELECT / CHECKBOX）',
    options     JSON             NULL     COMMENT 'SELECT型の選択肢リスト',
    sort_order  INT              NOT NULL DEFAULT 0 COMMENT '表示順',
    is_active   BOOLEAN          NOT NULL DEFAULT TRUE COMMENT 'アクティブフラグ（FALSE=論理無効化）',
    created_at  DATETIME(6)      NOT NULL,
    updated_at  DATETIME(6)      NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_ccf_team_sort (team_id, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='カスタム項目定義';

-- 9. カスタムフィールド値
CREATE TABLE chart_custom_values (
    id              BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    chart_record_id BIGINT UNSIGNED  NOT NULL COMMENT 'FK → chart_records（ON DELETE CASCADE）',
    field_id        BIGINT UNSIGNED  NOT NULL COMMENT 'FK → chart_custom_fields（ON DELETE RESTRICT）',
    value           TEXT             NULL     COMMENT 'フィールド値',
    created_at      DATETIME(6)      NOT NULL,
    updated_at      DATETIME(6)      NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_ccv_chart_field (chart_record_id, field_id),
    CONSTRAINT fk_ccv_chart_record FOREIGN KEY (chart_record_id) REFERENCES chart_records(id) ON DELETE CASCADE,
    CONSTRAINT fk_ccv_field        FOREIGN KEY (field_id)        REFERENCES chart_custom_fields(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='カスタム項目値';

-- 10. カルテ作成テンプレート
CREATE TABLE chart_record_templates (
    id                    BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    team_id               BIGINT UNSIGNED  NOT NULL COMMENT 'FK → teams',
    template_name         VARCHAR(100)     NOT NULL COMMENT 'テンプレート名',
    chief_complaint       TEXT             NULL     COMMENT '主訴・要望のデフォルト値',
    treatment_note        TEXT             NULL     COMMENT '施術内容のデフォルト値',
    allergy_info          TEXT             NULL     COMMENT 'アレルギー情報のデフォルト値',
    default_custom_fields JSON             NULL     COMMENT 'カスタムフィールドのデフォルト値',
    sort_order            INT              NOT NULL DEFAULT 0 COMMENT '表示順',
    created_at            DATETIME(6)      NOT NULL,
    updated_at            DATETIME(6)      NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_crt_team_sort (team_id, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='カルテ作成テンプレート';
