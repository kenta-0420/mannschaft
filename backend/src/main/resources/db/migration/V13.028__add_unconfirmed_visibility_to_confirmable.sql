-- F04.9 Phase D: 未確認者一覧の可視化機能（公開範囲制御）
-- confirmable_notifications: 通知単位の公開範囲（送信時にスナップショット）
-- confirmable_notification_settings: スコープ単位のデフォルト公開範囲
--
-- 値: HIDDEN / CREATOR_AND_ADMIN / ALL_MEMBERS
--   - HIDDEN          : 受信者リストはメンバーに公開しない（ADMIN+ のみ閲覧可）
--   - CREATOR_AND_ADMIN: 既存挙動。送信者本人 + ADMIN/DEPUTY_ADMIN のみ閲覧可（デフォルト）
--   - ALL_MEMBERS     : スコープ内の受信者全員が未確認者リストを閲覧可（相互声掛け文化向け）
--
-- ENUM ではなく VARCHAR + アプリ側 enum で表現する（既存パターン踏襲）

ALTER TABLE confirmable_notifications
  ADD COLUMN unconfirmed_visibility VARCHAR(30) NOT NULL DEFAULT 'CREATOR_AND_ADMIN'
    COMMENT '未確認者リストの公開範囲（HIDDEN / CREATOR_AND_ADMIN / ALL_MEMBERS）。送信時にスナップショット' AFTER total_recipient_count;

ALTER TABLE confirmable_notification_settings
  ADD COLUMN default_unconfirmed_visibility VARCHAR(30) NOT NULL DEFAULT 'CREATOR_AND_ADMIN'
    COMMENT 'スコープのデフォルト公開範囲。通知作成時にリクエスト省略されたら本値を採用' AFTER sender_alert_threshold_percent;
