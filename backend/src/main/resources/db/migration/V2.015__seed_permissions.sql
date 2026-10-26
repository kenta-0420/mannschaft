-- パーミッション初期データ投入（scope: TEAM）
INSERT INTO permissions (name, display_name, scope, created_at, updated_at) VALUES
    ('INVITE_MEMBERS', 'メンバー招待', 'TEAM', NOW(), NOW()),
    ('REMOVE_MEMBERS', 'メンバー除外', 'TEAM', NOW(), NOW()),
    ('CHANGE_MEMBER_ROLES', 'メンバーロール変更', 'TEAM', NOW(), NOW()),
    ('MANAGE_INVITE_TOKENS', '招待トークン管理', 'TEAM', NOW(), NOW()),
    ('EDIT_TEAM_SETTINGS', 'チーム設定編集', 'TEAM', NOW(), NOW()),
    ('MANAGE_SCHEDULES', 'スケジュール管理', 'TEAM', NOW(), NOW()),
    ('MANAGE_FILES', 'ファイル管理', 'TEAM', NOW(), NOW()),
    ('MANAGE_POSTS', '投稿管理', 'TEAM', NOW(), NOW()),
    ('DELETE_OTHERS_CONTENT', '他者コンテンツ削除', 'TEAM', NOW(), NOW()),
    ('MANAGE_ANNOUNCEMENTS', 'お知らせ管理', 'TEAM', NOW(), NOW()),
    ('SEND_SAFETY_CONFIRMATION', '安否確認送信', 'TEAM', NOW(), NOW());
