-- F09.17 Phase 11-a: direct_mail_logs に sender_type 列を追加
-- 設計書 §5 4チャネル委譲シーケンスより EMAIL は sender_type='SYSTEM_AD' で配信する。
-- 元テーブル (V9.010) には sender_type 列が無いため、ENUM ではなく VARCHAR(20) として
-- 追加し、'USER' / 'SYSTEM' / 'SYSTEM_AD' を許容値とする (アプリ層で検証)。
-- 既存行は NULL 不可とし、デフォルト 'USER' で埋める。
ALTER TABLE direct_mail_logs
    ADD COLUMN sender_type VARCHAR(20) NOT NULL DEFAULT 'USER'
        COMMENT '送信者種別: USER / SYSTEM / SYSTEM_AD (F09.17 広告キャンペーン由来)',
    ADD INDEX idx_dml_sender_type (sender_type);
