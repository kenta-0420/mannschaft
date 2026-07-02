-- F05.5 PR-D: 公開リンク共有（Google Drive 風）の土台を既存 shared_file_links に追加する。
--   is_active        : 手動失効フラグ。FALSE で発行者がリンクを即時無効化できる（410 Gone）。
--   download_allowed : このリンクで DL URL 発行を許すか。マスター確定仕様で既定 FALSE（＝閲覧のみ）。
--                      公開リンク DL は download_allowed(リンク) かつ NOT download_disabled(ファイル/フォルダ・C 由来) の AND 評価。
-- 期限（expires_at）は既存カラムを流用し、発行時に「必須・最大30日」をアプリ層で強制する（無期限リンク不可）。
ALTER TABLE shared_file_links
    ADD COLUMN is_active        BOOLEAN NOT NULL DEFAULT TRUE  COMMENT '手動失効フラグ(FALSE=失効)',
    ADD COLUMN download_allowed BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'このリンクでDLを許すか。既定FALSE=閲覧のみ(C:download_disabledとAND評価)';

-- 有効リンクの検索（is_active かつ未失効）を支えるインデックス。
CREATE INDEX idx_shared_file_links_active ON shared_file_links (is_active, expires_at);
