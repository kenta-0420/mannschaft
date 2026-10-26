-- F18 Phase 4 第一陣: プロバイダーマスタ拡充 (10 社 → 20 社)
-- 設計書: docs/features/F18_point_card_wallet.md §13.2
--
-- V9.141 で投入した 10 社に加え、運営要望の高い 10 社を追加で Seed する。
-- UUID は決定論性確保のため固定値（UUIDv7 風 0190b900... 連番）でハードコードする
-- （V9.141 と同じ方針）。
--
-- 追加内訳:
--   - CONVENIENCE: nanaco / ファミリーマート / 楽天Edy
--   - RETAIL:      WAON / ユニクロ / GU / イトーヨーカドー
--   - FOOD:        マクドナルド / コメダ珈琲店 / スターバックス
INSERT INTO point_card_providers
  (id, code, display_name, category, type, brand_color, default_barcode_format, is_active)
VALUES
  ('0190b900-0000-7000-8000-000000000001', 'nanaco',      'nanaco',            'CONVENIENCE', 'EXTERNAL', '#F2A104', 'EAN13',   1),
  ('0190b900-0000-7000-8000-000000000002', 'waon',        'WAON',              'RETAIL',      'EXTERNAL', '#FF6F00', 'EAN13',   1),
  ('0190b900-0000-7000-8000-000000000003', 'mcdonalds',   'マクドナルド',      'FOOD',        'EXTERNAL', '#FFC72C', 'QR',      1),
  ('0190b900-0000-7000-8000-000000000004', 'komeda',      'コメダ珈琲店',      'FOOD',        'EXTERNAL', '#B7282E', 'CODE128', 1),
  ('0190b900-0000-7000-8000-000000000005', 'starbucks',   'スターバックス',    'FOOD',        'EXTERNAL', '#006241', 'QR',      1),
  ('0190b900-0000-7000-8000-000000000006', 'uniqlo',      'ユニクロ',          'RETAIL',      'EXTERNAL', '#FF0000', 'QR',      1),
  ('0190b900-0000-7000-8000-000000000007', 'gu',          'GU',                'RETAIL',      'EXTERNAL', '#E60012', 'QR',      1),
  ('0190b900-0000-7000-8000-000000000008', 'ito_yokado',  'イトーヨーカドー',  'RETAIL',      'EXTERNAL', '#E60012', 'EAN13',   1),
  ('0190b900-0000-7000-8000-000000000009', 'familymart',  'ファミリーマート',  'CONVENIENCE', 'EXTERNAL', '#00973B', 'CODE128', 1),
  ('0190b900-0000-7000-8000-00000000000a', 'rakuten_edy', '楽天Edy',           'CONVENIENCE', 'EXTERNAL', '#BF0000', 'EAN13',   1);
