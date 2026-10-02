-- F06.5 アクティブリコール学習機能: reflection_themes（メインテーマ）
-- 日々の振り返りを束ねる軽量な器。個人所有（user_id）・既定 PRIVATE。
-- クロスドメインFK禁止（原則1）: user_id / linked_slot_id は ID 参照＋INDEX のみ。
CREATE TABLE reflection_themes (
    id                   BINARY(16)   NOT NULL,
    user_id              BIGINT       NOT NULL,                      -- 所有者（users ドメイン・FK なし）
    title                VARCHAR(120) NOT NULL,
    description          VARCHAR(500) NULL,
    source_type          VARCHAR(20)  NOT NULL DEFAULT 'FREE',      -- SUBJECT/PROJECT/DIARY/FREE
    linked_slot_kind     VARCHAR(10)  NULL,                          -- TEAM/PERSONAL/NULL（時間割スロット種別）
    linked_slot_id       BIGINT       NULL,                          -- 時間割スロットID（timetable ドメイン・FK なし・論理参照）
    exam_date            DATE         NULL,                          -- 定期考査日（総まとめリマインド基準・NULL 可）
    visibility           VARCHAR(20)  NOT NULL DEFAULT 'PRIVATE',    -- MVPはPRIVATE固定（FAMILY_SHAREDは別軍議・§6.1/§9.1）
    recall_interval_days VARCHAR(50)  NOT NULL DEFAULT '1,3,7,14',   -- 想起間隔（昇順 CSV・§2.6 設計）
    created_at           DATETIME     NOT NULL,
    updated_at           DATETIME     NOT NULL,
    deleted_at           DATETIME     NULL,
    PRIMARY KEY (id),
    CONSTRAINT chk_reflection_themes_source_type
        CHECK (source_type IN ('SUBJECT','PROJECT','DIARY','FREE')),
    CONSTRAINT chk_reflection_themes_linked_slot_kind
        CHECK (linked_slot_kind IS NULL OR linked_slot_kind IN ('TEAM','PERSONAL')),
    CONSTRAINT chk_reflection_themes_visibility
        CHECK (visibility = 'PRIVATE'),  -- MVP。FAMILY_SHARED追加は別軍議で旧CHECK DROP→新CHECKのmigration
    INDEX idx_reflection_themes_user (user_id, deleted_at),
    INDEX idx_reflection_themes_user_slot (user_id, linked_slot_kind, linked_slot_id),
    INDEX idx_reflection_themes_exam (exam_date)
);
