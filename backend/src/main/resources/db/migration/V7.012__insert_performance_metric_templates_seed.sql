-- F07.2 パフォーマンス管理: 指標テンプレート初期データ
INSERT INTO performance_metric_templates (sport_category, group_name, name, unit, data_type, aggregation_type, sort_order, min_value, max_value, is_self_recordable) VALUES
-- サッカー
('サッカー', '攻撃', '得点', '点', 'INTEGER', 'SUM', 1, 0, NULL, FALSE),
('サッカー', '攻撃', 'アシスト', '回', 'INTEGER', 'SUM', 2, 0, NULL, FALSE),
('サッカー', '体力', '出場時間', '分', 'TIME', 'SUM', 3, 0, NULL, FALSE),
('サッカー', '体力', '走行距離', 'km', 'DECIMAL', 'SUM', 4, 0, NULL, TRUE),
('サッカー', '攻撃', 'パス成功率', '%', 'DECIMAL', 'AVG', 5, 0, 100, FALSE),
-- バスケットボール
('バスケットボール', '攻撃', '得点', '点', 'INTEGER', 'SUM', 1, 0, NULL, FALSE),
('バスケットボール', '守備', 'リバウンド', '回', 'INTEGER', 'SUM', 2, 0, NULL, FALSE),
('バスケットボール', '攻撃', 'アシスト', '回', 'INTEGER', 'SUM', 3, 0, NULL, FALSE),
('バスケットボール', '守備', 'スティール', '回', 'INTEGER', 'SUM', 4, 0, NULL, FALSE),
('バスケットボール', '攻撃', 'フリースロー成功率', '%', 'DECIMAL', 'AVG', 5, 0, 100, FALSE),
-- 陸上
('陸上', NULL, 'タイム', '分', 'TIME', 'MIN', 1, 0, NULL, FALSE),
('陸上', NULL, '距離', 'm', 'DECIMAL', 'MAX', 2, 0, NULL, FALSE),
-- 水泳
('水泳', NULL, 'タイム', '分', 'TIME', 'MIN', 1, 0, NULL, FALSE),
('水泳', NULL, 'ラップタイム', '秒', 'DECIMAL', 'MIN', 2, 0, NULL, FALSE),
-- 汎用
('汎用', NULL, '出席回数', '回', 'INTEGER', 'SUM', 1, 0, NULL, FALSE),
('汎用', NULL, '練習時間', '分', 'TIME', 'SUM', 2, 0, NULL, FALSE);
