-- F01.9 年齢確認・保護者同意機能: 年齢グループ設定の初期データを投入
INSERT IGNORE INTO age_group_settings (age_group, display_name, min_age, max_age, features_enabled, theme_config) VALUES
  ('ELEMENTARY_LOWER',  '小学校低学年', 6,  7,    '{}', '{}'),
  ('ELEMENTARY_MIDDLE', '小学校中学年', 8,  9,    '{}', '{}'),
  ('ELEMENTARY_UPPER',  '小学校高学年', 10, 11,   '{}', '{}'),
  ('JUNIOR_HIGH',       '中学生',       12, 14,   '{}', '{}'),
  ('SENIOR_HIGH',       '高校生',       15, 17,   '{}', '{}'),
  ('ADULT',             '成人',         18, NULL,  '{}', '{}');
