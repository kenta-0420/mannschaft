-- チームテンプレートシードデータ（10種 + カスタム）
INSERT INTO team_templates (name, slug, description, category, is_active, created_at, updated_at) VALUES
('スポーツチーム', 'sports', 'スポーツクラブ・チーム向けテンプレート', 'スポーツ', 1, NOW(), NOW()),
('クリニック', 'clinic', '病院・クリニック向けテンプレート', '医療', 1, NOW(), NOW()),
('学校', 'school', '学校・教育機関向けテンプレート', '教育', 1, NOW(), NOW()),
('企業', 'company', '企業・法人向けテンプレート', 'ビジネス', 1, NOW(), NOW()),
('レストラン', 'restaurant', '飲食店向けテンプレート', '飲食', 1, NOW(), NOW()),
('サロン', 'salon', '美容サロン向けテンプレート', '美容', 1, NOW(), NOW()),
('ジム', 'gym', 'フィットネスジム向けテンプレート', 'フィットネス', 1, NOW(), NOW()),
('コミュニティ', 'community', '地域コミュニティ向けテンプレート', 'コミュニティ', 1, NOW(), NOW()),
('町内会', 'neighborhood', '町内会・自治会向けテンプレート', '自治', 1, NOW(), NOW()),
('マンション', 'apartment', 'マンション管理組合向けテンプレート', '住居', 1, NOW(), NOW()),
('その他', 'custom', 'カスタムテンプレート', 'その他', 1, NOW(), NOW());
