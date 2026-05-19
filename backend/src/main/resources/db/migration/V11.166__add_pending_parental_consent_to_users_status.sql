-- F01.9 年齢確認・保護者同意機能: users.statusにPENDING_PARENTAL_CONSENTを追加
-- 未成年ユーザーが保護者の同意待ち状態を表すステータス
ALTER TABLE users
    DROP CHECK chk_users_status;

ALTER TABLE users
    ADD CONSTRAINT chk_users_status CHECK (
        status IN (
            'PENDING_VERIFICATION',
            'PENDING_PARENTAL_CONSENT',
            'ACTIVE',
            'FROZEN',
            'ARCHIVED',
            'DECEASED',
            'RELOCATED'
        )
    );
