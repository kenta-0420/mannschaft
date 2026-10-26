-- DM受信制限設定カラム追加。ANYONE=誰からでも / TEAM_MEMBERS_ONLY=チームメンバーのみ / CONTACTS_ONLY=連絡先のみ
ALTER TABLE users
    ADD COLUMN dm_receive_from ENUM('ANYONE', 'TEAM_MEMBERS_ONLY', 'CONTACTS_ONLY')
        NOT NULL DEFAULT 'ANYONE' AFTER is_searchable;
