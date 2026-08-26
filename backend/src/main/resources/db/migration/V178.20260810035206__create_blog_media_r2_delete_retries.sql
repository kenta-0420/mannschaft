-- ブログメディア孤立オブジェクト R2 削除リトライ台帳の作成（Issue #2601 別任務）。
--
-- BlogMediaOrphanCleanupRunner は DB 行を先に削除（claim）してから R2 削除を試みるため、
-- R2 削除に失敗すると DB 行は既に無く、そのオブジェクトは以後の走査対象にならない。
-- 本テーブルは R2 削除に失敗したオブジェクトを追跡し、日次バッチで指数バックオフ再試行する。
--
-- クロスドメイン FK は張らない（アーキテクチャ原則1）。scope_type / scope_id は ID 参照のみ。
--
-- object_key は最長 1024 文字（utf8mb4 で最大 4096 byte）であり、MySQL の InnoDB 索引長上限
-- （utf8mb4 で 3072 byte）を超えるため、そのままでは UNIQUE 索引を張れない。
-- SHA-256 のハイフンなし16進文字列（固定64文字）を object_key_hash に格納し、
-- こちらに UNIQUE 制約を張ることで衝突耐性のある一意性を索引長制限内で成立させる。
CREATE TABLE blog_media_r2_delete_retries (
    id               BINARY(16)     NOT NULL,
    object_key       VARCHAR(1024)  NOT NULL,
    object_key_hash  CHAR(64)       NOT NULL COMMENT 'object_key の SHA-256 16進文字列（UNIQUE索引の索引長制限対策）',
    file_size        BIGINT         NOT NULL,
    scope_type       VARCHAR(32)    NOT NULL,
    scope_id         VARCHAR(64)    NOT NULL,
    status           VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    attempt_count    INT            NOT NULL DEFAULT 0,
    next_attempt_at  DATETIME(6)    NOT NULL,
    last_error       VARCHAR(500)   NULL,
    created_at       DATETIME(6)    NOT NULL,
    updated_at       DATETIME(6)    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_blog_media_r2_delete_retries_key_hash UNIQUE (object_key_hash),
    INDEX idx_blog_media_r2_delete_retries_status_next_attempt (status, next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
