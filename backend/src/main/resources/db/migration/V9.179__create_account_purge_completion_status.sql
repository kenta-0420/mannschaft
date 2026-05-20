-- account_purge_completion_status: AccountPurgedEvent 処理完了証跡テーブル
-- GDPR Art.17「30日以内削除完了」の per-domain 完了を記録する監査テーブル
-- 元々 V9.172 として作成されたが V9.172 (F05.2) と競合のため V9.179 に採番し直した。
-- IF NOT EXISTS により冪等性を確保（他マイグレーション経由で既に存在する場合も安全）。
--
-- CLAUDE.md 原則 6: 新規テーブルは UUIDv7（BINARY(16)）主キー
-- CLAUDE.md 原則 1: user_id は FK なし（クロスドメイン FK 禁止）
CREATE TABLE IF NOT EXISTS account_purge_completion_status
(
    id           BINARY(16)   NOT NULL,
    user_id      BIGINT UNSIGNED NOT NULL COMMENT 'users.id の参照値（FK 制約なし: クロスドメイン FK 禁止原則）',
    email_hash   CHAR(64)     NOT NULL COMMENT 'SHA-256(email) GDPR証跡。email 本体は保持しない',
    domain_name  VARCHAR(50)  NOT NULL COMMENT 'ドメイン識別子: role/team/payment/chart/proxy/errorreport',
    status       VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT '処理状態: PENDING/SUCCESS',
    attempted_at DATETIME(6)  NOT NULL COMMENT 'PENDING レコード作成日時（AccountPurgeService#purgeUser 実行時刻）',
    completed_at DATETIME(6)  NULL COMMENT 'SUCCESS に更新された日時（*PurgeEventListener 完了時刻）',
    PRIMARY KEY (id),
    INDEX idx_apcs_user_id (user_id),
    INDEX idx_apcs_status_attempted (status, attempted_at),
    INDEX idx_apcs_user_domain (user_id, domain_name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'GDPR Art.17 削除完了証跡: AccountPurgedEvent の per-domain 処理完了を記録する';
