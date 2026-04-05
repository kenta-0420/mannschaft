-- F01.4: 全テンプレート共通壁紙の初期データ
INSERT INTO template_wallpapers (template_slug, name, image_url, thumbnail_url, category, sort_order, is_active) VALUES
    ('*', 'シンプルホワイト',   '/wallpapers/common/white.webp',   '/wallpapers/common/white_thumb.webp',   'DEFAULT', 1, TRUE),
    ('*', 'シンプルダーク',     '/wallpapers/common/dark.webp',    '/wallpapers/common/dark_thumb.webp',    'DEFAULT', 2, TRUE),
    ('*', 'パステルブルー',     '/wallpapers/common/pastel.webp',  '/wallpapers/common/pastel_thumb.webp',  'DEFAULT', 3, TRUE);
