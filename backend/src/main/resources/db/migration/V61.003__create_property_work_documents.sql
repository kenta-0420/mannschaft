-- F09.13 物件履歴台帳: パッケージ ↔ SharedFile 中間テーブル
-- 設計書 §3 property_work_documents テーブル定義に対応
-- 論理削除なし。パッケージ削除時に CASCADE 削除（SharedFile 本体は残す）
CREATE TABLE property_work_documents (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    package_id      BIGINT UNSIGNED NOT NULL,
    shared_file_id  BIGINT UNSIGNED NOT NULL,
    document_kind   VARCHAR(30)     NOT NULL,
    display_order   INT             NOT NULL DEFAULT 0,
    note            VARCHAR(500)    NULL,
    created_by      BIGINT UNSIGNED NOT NULL,
    created_at      DATETIME        NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_pwd_package    FOREIGN KEY (package_id)     REFERENCES property_work_packages (id) ON DELETE CASCADE,
    CONSTRAINT fk_pwd_file       FOREIGN KEY (shared_file_id) REFERENCES shared_files (id)           ON DELETE CASCADE,
    CONSTRAINT fk_pwd_created_by FOREIGN KEY (created_by)     REFERENCES users (id)                  ON DELETE RESTRICT,
    CONSTRAINT chk_pwd_kind CHECK (
        document_kind IN ('MINUTES','QUOTE','CONTRACT','REPORT','PHOTO','DRAWING','INVOICE','RECEIPT','OTHER')
    ),
    UNIQUE KEY uq_pwd_pkg_file (package_id, shared_file_id),
    INDEX idx_pwd_package_kind (package_id, document_kind, display_order),
    INDEX idx_pwd_file (shared_file_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
