-- F10.7: chat_channels に問い合わせチャンネルフラグを追加
-- 業務アラートにおいて予約・問い合わせをADMINダッシュボードに通知するための基盤
ALTER TABLE chat_channels
  ADD COLUMN is_inquiry_channel BOOLEAN NOT NULL DEFAULT FALSE
  AFTER is_archived;

CREATE INDEX idx_cc_inquiry_channel
  ON chat_channels (team_id, is_inquiry_channel);
