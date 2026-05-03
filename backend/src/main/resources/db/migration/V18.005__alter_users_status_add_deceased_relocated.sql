-- F14.1 代理入力・非デジタル住民対応: users.statusにDECEASED・RELOCATEDを追加
-- ライフイベント（死亡・転居）時に同意書を自動失効させるため
ALTER TABLE users
    DROP CHECK chk_users_status;

ALTER TABLE users
    ADD CONSTRAINT chk_users_status CHECK (
        status IN (
            'PENDING_VERIFICATION',
            'ACTIVE',
            'FROZEN',
            'ARCHIVED',
            'DECEASED',
            'RELOCATED'
        )
    );
