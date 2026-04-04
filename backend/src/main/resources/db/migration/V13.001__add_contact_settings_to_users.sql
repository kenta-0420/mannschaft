-- F04.8: users テーブルに連絡先機能関連カラムを追加
ALTER TABLE users
  ADD COLUMN contact_handle   VARCHAR(30)  NULL UNIQUE
      COMMENT 'アプリ内@ハンドル。英数字・アンダースコア・ハイフン。user_social_profiles.handleとは別物'
      AFTER display_name,

  ADD COLUMN handle_searchable TINYINT(1) NOT NULL DEFAULT 1
      COMMENT '1=@ハンドルで検索可能, 0=検索不可'
      AFTER contact_handle,

  ADD COLUMN contact_approval_required TINYINT(1) NOT NULL DEFAULT 1
      COMMENT '1=追加申請に承認が必要(デフォルト), 0=自動承認'
      AFTER handle_searchable,

  ADD COLUMN online_visibility VARCHAR(20) NOT NULL DEFAULT 'NOBODY'
      COMMENT 'オンライン状態の公開範囲: NOBODY / CONTACTS_ONLY / EVERYONE'
      AFTER contact_approval_required;

CREATE INDEX idx_users_contact_handle ON users(contact_handle);
