-- F03.4+ 臨時休業確認追跡: 患者向け3時間前リマインダー送信日時カラム追加
ALTER TABLE emergency_closure_confirmations
    ADD COLUMN patient_reminder_sent_at DATETIME AFTER reminder_sent_at;
