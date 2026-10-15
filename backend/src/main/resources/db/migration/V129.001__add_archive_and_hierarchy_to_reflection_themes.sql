-- V129.001__add_archive_and_hierarchy_to_reflection_themes.sql
-- Phase 3: アーカイブ＆分類（学年×学期×教科フォルダ＋横断検索）
-- 設計書: docs/features/F06.5_reflection_active_recall.md §12.5

ALTER TABLE reflection_themes
    ADD COLUMN academic_year    SMALLINT     NULL        AFTER linked_course_code,
    ADD COLUMN term_label       VARCHAR(50)  NULL        AFTER academic_year,
    ADD COLUMN parent_theme_id  BINARY(16)   NULL        AFTER term_label,
    ADD COLUMN archived_at      DATETIME     NULL        AFTER deleted_at;

-- フォルダ集計・検索用複合インデックス（user_id 先頭でテナントスコープ）
CREATE INDEX idx_reflection_themes_folder
    ON reflection_themes (user_id, academic_year, term_label, linked_subject_name, archived_at);

-- 親テーマ参照用インデックス（ON DELETE SET NULL の SET NULL 更新性能向上）
CREATE INDEX idx_reflection_themes_parent
    ON reflection_themes (parent_theme_id);

-- 同一ドメイン自己参照 FK（ON DELETE SET NULL: 親削除時は子をトップレベル昇格）
ALTER TABLE reflection_themes
    ADD CONSTRAINT fk_reflection_themes_parent
    FOREIGN KEY (parent_theme_id) REFERENCES reflection_themes(id) ON DELETE SET NULL;
