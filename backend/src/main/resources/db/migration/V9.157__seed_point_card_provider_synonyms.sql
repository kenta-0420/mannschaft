-- F18 Phase 4 第一陣: プロバイダー同義語辞書 初期データ
-- 設計書: docs/features/F18_point_card_wallet.md §7.6
--
-- 既知の口語・改称・略称をシードして、リリース直後から fuzzy match の効能を発揮させる。
--
-- normalized 値は ProviderMatchService.normalize() の規則と完全一致させること:
--   1) NFKC 正規化
--   2) カタカナ → ひらがな
--   3) 記号削除 [\s\-_./:]
--   4) lower 化
-- ※「ー」（長音記号 U+30FC）は記号削除対象外であることに注意。
--
-- 投入対象は V9.141 / V9.155 で投入済みの 20 社の code に対する同義語のみ。
-- 各 provider_id は code から逆引き JOIN で動的解決する（UUID ハードコード回避）。

INSERT INTO point_card_provider_synonyms
  (id, provider_id, synonym_display, synonym_normalized, memo, created_at, updated_at)
SELECT UUID(), p.id, syn.synonym_display, syn.synonym_normalized, syn.memo, NOW(6), NOW(6)
FROM point_card_providers p
JOIN (
    -- code, synonym_display, synonym_normalized, memo
              SELECT 'dpoint'      AS code, 'ドコモポイント'         AS synonym_display, 'どこもぽいんと'         AS synonym_normalized, '旧称'                       AS memo
    UNION ALL SELECT 'dpoint',           'Dポイ',                   'dぽい',                   '略称'
    UNION ALL SELECT 'vpoint',           'Tポイント',               'tぽいんと',               '旧称（T→V 改称前）'
    UNION ALL SELECT 'vpoint',           'ティーポイント',          'てぃーぽいんと',          '旧称口語'
    UNION ALL SELECT 'rakuten',          '楽天スーパーポイント',    '楽天すーぱーぽいんと',    '旧称'
    UNION ALL SELECT 'rakuten',          '楽ポ',                    '楽ぽ',                    '略称'
    UNION ALL SELECT 'paypay',           'ペイペイ',                'ぺいぺい',                '口語'
    UNION ALL SELECT 'paypay',           'PayPayポイント',          'paypayぽいんと',          '正式表記'
    UNION ALL SELECT 'ponta',            'ロッピー',                'ろっぴー',                '口語（ローソン店頭機）'
    UNION ALL SELECT 'ponta',            'Pontaカード',             'pontaかーど',             '正式名'
    UNION ALL SELECT 'tokyu_point',      '東急ポイ',                '東急ぽい',                '略称'
    UNION ALL SELECT 'yodobashi',        'ヨドバシポイント',        'よどばしぽいんと',        '略称'
    UNION ALL SELECT 'biccamera',        'ビックポイント',          'びっくぽいんと',          '略称'
    UNION ALL SELECT 'matsukiyo',        'マツキヨ',                'まつきよ',                '略称'
    UNION ALL SELECT 'matsukiyo',        'マツキヨポイント',        'まつきよぽいんと',        '略称'
    UNION ALL SELECT 'tsutaya',          'ツタヤ',                  'つたや',                  'カナ表記'
    UNION ALL SELECT 'nanaco',           'ナナコ',                  'ななこ',                  'カナ表記'
    UNION ALL SELECT 'nanaco',           'セブンポイント',          'せぶんぽいんと',          '関連名'
    UNION ALL SELECT 'waon',             'ワオン',                  'わおん',                  'カナ表記'
    UNION ALL SELECT 'waon',             'イオンポイント',          'いおんぽいんと',          '関連名'
    UNION ALL SELECT 'mcdonalds',        'マック',                  'まっく',                  '略称'
    UNION ALL SELECT 'mcdonalds',        'マクド',                  'まくど',                  '関西略称'
    UNION ALL SELECT 'starbucks',        'スタバ',                  'すたば',                  '略称'
    UNION ALL SELECT 'uniqlo',           'ユニクロアプリ',          'ゆにくろあぷり',          '正式名'
    UNION ALL SELECT 'familymart',       'ファミマ',                'ふぁみま',                '略称'
    UNION ALL SELECT 'familymart',       'ファミペイ',              'ふぁみぺい',              '決済機能'
) AS syn ON p.code = syn.code;
