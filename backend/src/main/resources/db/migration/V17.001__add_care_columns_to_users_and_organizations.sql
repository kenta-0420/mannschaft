-- F03.12 ケア対象者見守り通知: users / organizations へのケア属性追加
ALTER TABLE users
  ADD COLUMN birth_date VARBINARY(255) NULL
    COMMENT '生年月日（AES-256-GCM 暗号化、任意）',
  ADD COLUMN care_category ENUM('MINOR','ELDERLY','DISABILITY_SUPPORT','GENERAL_FAMILY') NULL
    COMMENT 'ケア区分（NULL=ケア対象外、設定時はbirth_dateより優先）',
  ADD COLUMN care_notification_enabled BOOLEAN NOT NULL DEFAULT TRUE
    COMMENT '見守り通知有効フラグ（本人または見守り者が無効化可能）',
  ADD COLUMN account_created_by_watcher_user_id BIGINT NULL
    COMMENT '見守り者代理作成の場合の見守り者users.id（自動リンク作成のため）';

ALTER TABLE users
  ADD CONSTRAINT fk_users_watcher FOREIGN KEY (account_created_by_watcher_user_id) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE organizations
  ADD COLUMN elderly_care_threshold_age TINYINT NOT NULL DEFAULT 75
    COMMENT '高齢ケア提案のしきい値年齢（組織別カスタマイズ可）';
