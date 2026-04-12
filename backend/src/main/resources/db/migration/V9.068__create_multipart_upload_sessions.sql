-- Multipart Upload セッション管理テーブルを作成
-- 大容量ファイル（100MB 超）の Multipart Upload フローで使用するセッション情報を管理する
-- R2 / S3 互換 API の Multipart Upload ID とアップロード先 R2 オブジェクトキーを追跡し、
-- 未完了セッションのクリーンアップ（24時間で自動失効）にも使用する
CREATE TABLE multipart_upload_sessions (
    id                BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    upload_id         VARCHAR(255)     NOT NULL COMMENT 'R2/S3 Multipart Upload ID',
    r2_key            VARCHAR(500)     NOT NULL COMMENT 'アップロード先の R2 オブジェクトキー',
    feature           VARCHAR(30)      NOT NULL COMMENT '呼び出し元機能 (timeline/gallery/blog/files)',
    scope_type        VARCHAR(20)      NOT NULL COMMENT 'スコープ種別: PERSONAL/TEAM/ORGANIZATION',
    scope_id          BIGINT UNSIGNED  NOT NULL COMMENT 'スコープ ID（teams.id / organizations.id / users.id）',
    uploader_id       BIGINT UNSIGNED  NOT NULL COMMENT 'アップロードを開始したユーザーの ID',
    content_type      VARCHAR(100)     NOT NULL COMMENT 'アップロードファイルの MIME タイプ',
    status            VARCHAR(20)      NOT NULL DEFAULT 'IN_PROGRESS'
                          COMMENT 'セッション状態: IN_PROGRESS=アップロード中, COMPLETED=完了, ABORTED=中断',
    expires_at        DATETIME         NOT NULL COMMENT 'セッション有効期限（開始から24時間）',
    created_at        DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mup_upload_id (upload_id),
    INDEX idx_mup_uploader (uploader_id),
    INDEX idx_mup_scope (scope_type, scope_id),
    INDEX idx_mup_expires (expires_at),
    CONSTRAINT fk_mup_uploader FOREIGN KEY (uploader_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Multipart Upload セッション管理テーブル';
