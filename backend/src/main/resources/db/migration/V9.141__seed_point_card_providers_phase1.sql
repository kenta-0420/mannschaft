-- F18 Phase 1 リリース対象プロバイダー Seed（案 B 改修後: 10 社にスリム化）
-- 設計書: docs/features/F18_point_card_wallet.md §13.2
-- UUID は決定論性確保のため固定値（UUIDv7 風タイムスタンプ 01901111... + 連番）をハードコード。
-- マスタ拡充は運営判断で随時 INSERT 追加する運用とし、本機能の必須要件ではない。
INSERT INTO point_card_providers
  (id, code, display_name, category, type, brand_color, default_barcode_format, is_active)
VALUES
  ('01901111-0000-7000-8000-000000000001', 'tokyu_point',  '東急ポイント',                 'RETAIL', 'EXTERNAL', '#E60012', 'CODE128', 1),
  ('01901111-0000-7000-8000-000000000002', 'dpoint',       'dポイント',                    'RETAIL', 'EXTERNAL', '#CC0000', 'CODE128', 1),
  ('01901111-0000-7000-8000-000000000003', 'rakuten',      '楽天ポイント',                 'RETAIL', 'EXTERNAL', '#BF0000', 'CODE128', 1),
  ('01901111-0000-7000-8000-000000000004', 'paypay',       'PayPayポイント',               'RETAIL', 'EXTERNAL', '#FF0033', 'QR',      1),
  ('01901111-0000-7000-8000-000000000005', 'vpoint',       'Vポイント',                    'RETAIL', 'EXTERNAL', '#1E88E5', 'CODE128', 1),
  ('01901111-0000-7000-8000-000000000006', 'ponta',        'Pontaポイント',                'RETAIL', 'EXTERNAL', '#F39800', 'CODE128', 1),
  ('01901111-0000-7000-8000-000000000007', 'yodobashi',    'ヨドバシゴールドポイント',     'RETAIL', 'EXTERNAL', '#000000', 'EAN13',   1),
  ('01901111-0000-7000-8000-000000000008', 'biccamera',    'ビックカメラポイント',         'RETAIL', 'EXTERNAL', '#D31C24', 'CODE128', 1),
  ('01901111-0000-7000-8000-000000000009', 'matsukiyo',    'マツモトキヨシ',               'RETAIL', 'EXTERNAL', '#FAB237', 'CODE128', 1),
  ('01901111-0000-7000-8000-00000000000a', 'tsutaya',      'TSUTAYA',                      'RETAIL', 'EXTERNAL', '#003D78', 'CODE128', 1);

-- TODO（運営マスタ管理ロードマップ）: 後日追加候補
--   セブン-イレブン / ファミマ / ローソン / イオン / コメダ / マクドナルド ...
-- 追加方針: 運営が `is_active` トグル + 必要に応じた INSERT を行う。
-- プリセットに無い事業者はユーザーが自由入力で登録できるため、Seed 件数を急いで増やす必要はない。
