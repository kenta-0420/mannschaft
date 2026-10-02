-- プライバシーポリシー同意記録フィールドを users テーブルへ追加する。
-- GDPR Art.7 / 個人情報保護法 準拠のため、同意日時とポリシーバージョンを保存する。
-- 設計書: docs/features/F_privacy_policy.md §4
ALTER TABLE users
    ADD COLUMN privacy_policy_accepted_at DATETIME NULL COMMENT '同意日時（NULL=未同意または旧登録）' AFTER deleted_at,
    ADD COLUMN privacy_policy_version     VARCHAR(20) NULL COMMENT '同意時のポリシーバージョン'       AFTER privacy_policy_accepted_at;
