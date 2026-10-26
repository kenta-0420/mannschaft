-- F01.4: ファミリーテンプレート初期データ
-- team_templates への seed は F01.3 で管理されるため、ここでは壁紙の seed のみ行う
-- ファミリーテンプレート壁紙（春・夏・秋・冬）
INSERT INTO template_wallpapers (template_slug, name, image_url, thumbnail_url, category, sort_order, is_active) VALUES
    ('family', 'ファミリーイラスト - 春', '/wallpapers/family/spring.webp', '/wallpapers/family/spring_thumb.webp', 'DEFAULT', 1, TRUE),
    ('family', 'ファミリーイラスト - 夏', '/wallpapers/family/summer.webp', '/wallpapers/family/summer_thumb.webp', 'DEFAULT', 2, TRUE),
    ('family', 'ファミリーイラスト - 秋', '/wallpapers/family/autumn.webp', '/wallpapers/family/autumn_thumb.webp', 'DEFAULT', 3, TRUE),
    ('family', 'ファミリーイラスト - 冬', '/wallpapers/family/winter.webp', '/wallpapers/family/winter_thumb.webp', 'DEFAULT', 4, TRUE);
