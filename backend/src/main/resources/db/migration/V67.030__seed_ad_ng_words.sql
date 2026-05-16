-- F09.17 Phase 11-b δ: 自動 NG 辞書シード (50 語)
-- 設計書 §5 自動 NG 検知に従い、薬機法・景表法・金融商品・差別・クリックベイトのハイリスク語を投入。
-- UUIDv7 を採用するが、シード用途では UUID() で暫定発番する (時刻順は無視可)。
-- 既に同じ word が登録されている場合はスキップ (UNIQUE 制約)。
INSERT INTO ad_ng_words (id, word, category, severity, note, is_active) VALUES
-- ─────────────────────────────────────────────────────────
-- PHARMA: 薬機法ハイリスク (severity=BLOCK)
-- 効能効果の標榜・医薬品該当表現は薬機法第66条違反
-- ─────────────────────────────────────────────────────────
(UUID_TO_BIN(UUID()), '治る',       'PHARMA', 'BLOCK', '薬機法: 効能効果の標榜', TRUE),
(UUID_TO_BIN(UUID()), '完治',       'PHARMA', 'BLOCK', '薬機法: 治癒保証は医薬品該当', TRUE),
(UUID_TO_BIN(UUID()), '根治',       'PHARMA', 'BLOCK', '薬機法: 治癒保証は医薬品該当', TRUE),
(UUID_TO_BIN(UUID()), '効く',       'PHARMA', 'BLOCK', '薬機法: 効能効果の標榜', TRUE),
(UUID_TO_BIN(UUID()), '効能',       'PHARMA', 'BLOCK', '薬機法: 効能効果の標榜', TRUE),
(UUID_TO_BIN(UUID()), '副作用',     'PHARMA', 'BLOCK', '薬機法: 医薬品該当表現', TRUE),
(UUID_TO_BIN(UUID()), '医薬品',     'PHARMA', 'BLOCK', '薬機法: 医薬品該当表現', TRUE),
(UUID_TO_BIN(UUID()), 'がん',       'PHARMA', 'BLOCK', '薬機法: 重篤疾病への効果暗示', TRUE),
(UUID_TO_BIN(UUID()), '癌',         'PHARMA', 'BLOCK', '薬機法: 重篤疾病への効果暗示', TRUE),
(UUID_TO_BIN(UUID()), '糖尿病',     'PHARMA', 'BLOCK', '薬機法: 重篤疾病への効果暗示', TRUE),
(UUID_TO_BIN(UUID()), '高血圧',     'PHARMA', 'BLOCK', '薬機法: 重篤疾病への効果暗示', TRUE),
(UUID_TO_BIN(UUID()), '認知症',     'PHARMA', 'BLOCK', '薬機法: 重篤疾病への効果暗示', TRUE),
(UUID_TO_BIN(UUID()), 'うつ病',     'PHARMA', 'BLOCK', '薬機法: 精神疾病への効果暗示', TRUE),
(UUID_TO_BIN(UUID()), '不妊',       'PHARMA', 'BLOCK', '薬機法: 効能効果の標榜', TRUE),
(UUID_TO_BIN(UUID()), 'アンチエイジング', 'PHARMA', 'BLOCK', '薬機法: 老化防止標榜は医薬品該当', TRUE),

-- ─────────────────────────────────────────────────────────
-- SUPERLATIVE: 景表法・最上級表現 (severity=WARN)
-- 合理的根拠なき最上級表現は景表法第5条 (優良誤認) 違反
-- ─────────────────────────────────────────────────────────
(UUID_TO_BIN(UUID()), '最高',       'SUPERLATIVE', 'WARN', '景表法: 最上級表現には合理的根拠が必要', TRUE),
(UUID_TO_BIN(UUID()), '最強',       'SUPERLATIVE', 'WARN', '景表法: 最上級表現には合理的根拠が必要', TRUE),
(UUID_TO_BIN(UUID()), '絶対',       'SUPERLATIVE', 'WARN', '景表法: 断定表現は優良誤認の恐れ', TRUE),
(UUID_TO_BIN(UUID()), '100%',       'SUPERLATIVE', 'WARN', '景表法: 100%保証は優良誤認の恐れ', TRUE),
(UUID_TO_BIN(UUID()), '業界No.1',   'SUPERLATIVE', 'WARN', '景表法: No.1表記には調査根拠の明示が必要', TRUE),
(UUID_TO_BIN(UUID()), '日本一',     'SUPERLATIVE', 'WARN', '景表法: No.1表記には調査根拠の明示が必要', TRUE),
(UUID_TO_BIN(UUID()), '世界一',     'SUPERLATIVE', 'WARN', '景表法: No.1表記には調査根拠の明示が必要', TRUE),
(UUID_TO_BIN(UUID()), '唯一',       'SUPERLATIVE', 'WARN', '景表法: 排他的表現には合理的根拠が必要', TRUE),
(UUID_TO_BIN(UUID()), '他社より',   'SUPERLATIVE', 'WARN', '景表法: 比較広告は調査根拠が必要', TRUE),
(UUID_TO_BIN(UUID()), '究極',       'SUPERLATIVE', 'WARN', '景表法: 最上級表現の暗示', TRUE),

-- ─────────────────────────────────────────────────────────
-- FINANCIAL_RISK: 金融商品取引法ハイリスク (severity=BLOCK)
-- 元本保証・絶対利益の標榜は金商法第38条違反
-- ─────────────────────────────────────────────────────────
(UUID_TO_BIN(UUID()), '必ず儲かる', 'FINANCIAL_RISK', 'BLOCK', '金商法: 断定的判断の提供禁止', TRUE),
(UUID_TO_BIN(UUID()), '元本保証',   'FINANCIAL_RISK', 'BLOCK', '金商法: 元本保証の標榜禁止 (一部除く)', TRUE),
(UUID_TO_BIN(UUID()), '絶対に勝てる', 'FINANCIAL_RISK', 'BLOCK', '金商法: 断定的判断の提供禁止', TRUE),
(UUID_TO_BIN(UUID()), 'リスクなし', 'FINANCIAL_RISK', 'BLOCK', '金商法: 損失可能性の隠蔽', TRUE),
(UUID_TO_BIN(UUID()), '100%還元',   'FINANCIAL_RISK', 'BLOCK', '金商法: 利益保証の標榜禁止', TRUE),
(UUID_TO_BIN(UUID()), '絶対に儲かる', 'FINANCIAL_RISK', 'BLOCK', '金商法: 断定的判断の提供禁止', TRUE),
(UUID_TO_BIN(UUID()), '損しない',   'FINANCIAL_RISK', 'BLOCK', '金商法: 損失可能性の隠蔽', TRUE),

-- ─────────────────────────────────────────────────────────
-- DISCRIMINATION: 差別・公序良俗 (severity=BLOCK)
-- ヘイトスピーチ・性的・差別表現
-- ─────────────────────────────────────────────────────────
(UUID_TO_BIN(UUID()), '殺す',       'DISCRIMINATION', 'BLOCK', '公序良俗: 暴力扇動', TRUE),
(UUID_TO_BIN(UUID()), '死ね',       'DISCRIMINATION', 'BLOCK', '公序良俗: 暴力扇動', TRUE),
(UUID_TO_BIN(UUID()), 'ブス',       'DISCRIMINATION', 'BLOCK', '公序良俗: 容姿差別', TRUE),
(UUID_TO_BIN(UUID()), 'デブ',       'DISCRIMINATION', 'BLOCK', '公序良俗: 容姿差別', TRUE),
(UUID_TO_BIN(UUID()), 'ハゲ',       'DISCRIMINATION', 'BLOCK', '公序良俗: 容姿差別', TRUE),
(UUID_TO_BIN(UUID()), 'バカ',       'DISCRIMINATION', 'BLOCK', '公序良俗: 侮辱表現', TRUE),
(UUID_TO_BIN(UUID()), 'アホ',       'DISCRIMINATION', 'BLOCK', '公序良俗: 侮辱表現', TRUE),
(UUID_TO_BIN(UUID()), 'クズ',       'DISCRIMINATION', 'BLOCK', '公序良俗: 侮辱表現', TRUE),
(UUID_TO_BIN(UUID()), '在日',       'DISCRIMINATION', 'BLOCK', '公序良俗: 民族差別の温床', TRUE),
(UUID_TO_BIN(UUID()), '童貞',       'DISCRIMINATION', 'BLOCK', '公序良俗: 性的属性差別', TRUE),

-- ─────────────────────────────────────────────────────────
-- OTHER: クリックベイト・雑カテゴリ (severity=WARN)
-- 直ちに違法ではないがユーザー体験を害する表現
-- ─────────────────────────────────────────────────────────
(UUID_TO_BIN(UUID()), '無料モニター', 'OTHER', 'WARN', 'クリックベイト: 無料訴求の濫用', TRUE),
(UUID_TO_BIN(UUID()), '先着',       'OTHER', 'WARN', 'クリックベイト: 過度な希少性訴求', TRUE),
(UUID_TO_BIN(UUID()), '限定',       'OTHER', 'WARN', 'クリックベイト: 過度な希少性訴求', TRUE),
(UUID_TO_BIN(UUID()), '今すぐ',     'OTHER', 'WARN', 'クリックベイト: 過度な緊急性訴求', TRUE),
(UUID_TO_BIN(UUID()), '激安',       'OTHER', 'WARN', 'クリックベイト: 価格優位の過剰訴求', TRUE),
(UUID_TO_BIN(UUID()), '驚愕',       'OTHER', 'WARN', 'クリックベイト: 感情扇動', TRUE),
(UUID_TO_BIN(UUID()), '衝撃',       'OTHER', 'WARN', 'クリックベイト: 感情扇動', TRUE),
(UUID_TO_BIN(UUID()), 'ヤバい',     'OTHER', 'WARN', 'クリックベイト: 過剰な感情訴求', TRUE);
