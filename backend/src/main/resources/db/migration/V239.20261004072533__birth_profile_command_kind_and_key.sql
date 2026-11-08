-- 成功履歴に明示種別とRAW入力の鍵識別を追加する。V237は変更しない。
-- 旧種別NULLは不明履歴として拒否し、JSON形や現在鍵での推測UPDATEを行わない。
ALTER TABLE birth_profile_commands
    ADD COLUMN command_type VARCHAR(30) NULL,
    ADD COLUMN request_key_id VARCHAR(64) NULL,
    ADD CONSTRAINT chk_birth_profile_commands_command_type CHECK (
        command_type IS NULL OR command_type IN ('RAW_PROFILE_PUT', 'CONFIRM_BIRTH_STYLE')
    );
