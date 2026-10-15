-- F22.1 Phase2 E: 都道府県47件 × 5言語（en/zh/ko/es/de）の確定訳を投入する。
--
-- ja は元マスタ（prefectures.name）が正のため格納しない（fallback で日本語名を返す）。
-- 市区町村（約1,900件）の訳は本陣では投入しない。正確な固有名詞訳の調達は別タスクとし、
-- 未訳の市区町村はアプリ側で日本語名にフォールバックする（誤訳の機械翻訳は入れない）。
--
-- 訳の出典・方針:
--   en/es/de … ヘボン式ローマ字（国際的に通用する英字表記。県=Prefecture 等の接尾辞は付けない地域名のみ）
--   zh        … 中国語簡体字の標準表記（日本の都道府県名の慣用中国語表記）
--   ko        … 韓国語の標準音訳（国立国語院の外来語表記に準拠）

-- ── 英語（en）: ヘボン式ローマ字 ──────────────────────────────
INSERT INTO region_translations (code, lang, name) VALUES
('01', 'en', 'Hokkaido'),  ('02', 'en', 'Aomori'),    ('03', 'en', 'Iwate'),
('04', 'en', 'Miyagi'),    ('05', 'en', 'Akita'),     ('06', 'en', 'Yamagata'),
('07', 'en', 'Fukushima'), ('08', 'en', 'Ibaraki'),   ('09', 'en', 'Tochigi'),
('10', 'en', 'Gunma'),     ('11', 'en', 'Saitama'),   ('12', 'en', 'Chiba'),
('13', 'en', 'Tokyo'),     ('14', 'en', 'Kanagawa'),  ('15', 'en', 'Niigata'),
('16', 'en', 'Toyama'),    ('17', 'en', 'Ishikawa'),  ('18', 'en', 'Fukui'),
('19', 'en', 'Yamanashi'), ('20', 'en', 'Nagano'),    ('21', 'en', 'Gifu'),
('22', 'en', 'Shizuoka'),  ('23', 'en', 'Aichi'),     ('24', 'en', 'Mie'),
('25', 'en', 'Shiga'),     ('26', 'en', 'Kyoto'),     ('27', 'en', 'Osaka'),
('28', 'en', 'Hyogo'),     ('29', 'en', 'Nara'),      ('30', 'en', 'Wakayama'),
('31', 'en', 'Tottori'),   ('32', 'en', 'Shimane'),   ('33', 'en', 'Okayama'),
('34', 'en', 'Hiroshima'), ('35', 'en', 'Yamaguchi'), ('36', 'en', 'Tokushima'),
('37', 'en', 'Kagawa'),    ('38', 'en', 'Ehime'),     ('39', 'en', 'Kochi'),
('40', 'en', 'Fukuoka'),   ('41', 'en', 'Saga'),      ('42', 'en', 'Nagasaki'),
('43', 'en', 'Kumamoto'),  ('44', 'en', 'Oita'),      ('45', 'en', 'Miyazaki'),
('46', 'en', 'Kagoshima'), ('47', 'en', 'Okinawa');

-- ── スペイン語（es）: 英語と同じローマ字表記を採用 ──────────────
INSERT INTO region_translations (code, lang, name) VALUES
('01', 'es', 'Hokkaido'),  ('02', 'es', 'Aomori'),    ('03', 'es', 'Iwate'),
('04', 'es', 'Miyagi'),    ('05', 'es', 'Akita'),     ('06', 'es', 'Yamagata'),
('07', 'es', 'Fukushima'), ('08', 'es', 'Ibaraki'),   ('09', 'es', 'Tochigi'),
('10', 'es', 'Gunma'),     ('11', 'es', 'Saitama'),   ('12', 'es', 'Chiba'),
('13', 'es', 'Tokio'),     ('14', 'es', 'Kanagawa'),  ('15', 'es', 'Niigata'),
('16', 'es', 'Toyama'),    ('17', 'es', 'Ishikawa'),  ('18', 'es', 'Fukui'),
('19', 'es', 'Yamanashi'), ('20', 'es', 'Nagano'),    ('21', 'es', 'Gifu'),
('22', 'es', 'Shizuoka'),  ('23', 'es', 'Aichi'),     ('24', 'es', 'Mie'),
('25', 'es', 'Shiga'),     ('26', 'es', 'Kioto'),     ('27', 'es', 'Osaka'),
('28', 'es', 'Hyogo'),     ('29', 'es', 'Nara'),      ('30', 'es', 'Wakayama'),
('31', 'es', 'Tottori'),   ('32', 'es', 'Shimane'),   ('33', 'es', 'Okayama'),
('34', 'es', 'Hiroshima'), ('35', 'es', 'Yamaguchi'), ('36', 'es', 'Tokushima'),
('37', 'es', 'Kagawa'),    ('38', 'es', 'Ehime'),     ('39', 'es', 'Kochi'),
('40', 'es', 'Fukuoka'),   ('41', 'es', 'Saga'),      ('42', 'es', 'Nagasaki'),
('43', 'es', 'Kumamoto'),  ('44', 'es', 'Oita'),      ('45', 'es', 'Miyazaki'),
('46', 'es', 'Kagoshima'), ('47', 'es', 'Okinawa');

-- ── ドイツ語（de）: 英語と同じローマ字表記を採用 ────────────────
INSERT INTO region_translations (code, lang, name) VALUES
('01', 'de', 'Hokkaido'),  ('02', 'de', 'Aomori'),    ('03', 'de', 'Iwate'),
('04', 'de', 'Miyagi'),    ('05', 'de', 'Akita'),     ('06', 'de', 'Yamagata'),
('07', 'de', 'Fukushima'), ('08', 'de', 'Ibaraki'),   ('09', 'de', 'Tochigi'),
('10', 'de', 'Gunma'),     ('11', 'de', 'Saitama'),   ('12', 'de', 'Chiba'),
('13', 'de', 'Tokio'),     ('14', 'de', 'Kanagawa'),  ('15', 'de', 'Niigata'),
('16', 'de', 'Toyama'),    ('17', 'de', 'Ishikawa'),  ('18', 'de', 'Fukui'),
('19', 'de', 'Yamanashi'), ('20', 'de', 'Nagano'),    ('21', 'de', 'Gifu'),
('22', 'de', 'Shizuoka'),  ('23', 'de', 'Aichi'),     ('24', 'de', 'Mie'),
('25', 'de', 'Shiga'),     ('26', 'de', 'Kyoto'),     ('27', 'de', 'Osaka'),
('28', 'de', 'Hyogo'),     ('29', 'de', 'Nara'),      ('30', 'de', 'Wakayama'),
('31', 'de', 'Tottori'),   ('32', 'de', 'Shimane'),   ('33', 'de', 'Okayama'),
('34', 'de', 'Hiroshima'), ('35', 'de', 'Yamaguchi'), ('36', 'de', 'Tokushima'),
('37', 'de', 'Kagawa'),    ('38', 'de', 'Ehime'),     ('39', 'de', 'Kochi'),
('40', 'de', 'Fukuoka'),   ('41', 'de', 'Saga'),      ('42', 'de', 'Nagasaki'),
('43', 'de', 'Kumamoto'),  ('44', 'de', 'Oita'),      ('45', 'de', 'Miyazaki'),
('46', 'de', 'Kagoshima'), ('47', 'de', 'Okinawa');

-- ── 中国語簡体字（zh）: 日本都道府県名の慣用中国語表記 ────────────
INSERT INTO region_translations (code, lang, name) VALUES
('01', 'zh', '北海道'),   ('02', 'zh', '青森县'),   ('03', 'zh', '岩手县'),
('04', 'zh', '宫城县'),   ('05', 'zh', '秋田县'),   ('06', 'zh', '山形县'),
('07', 'zh', '福岛县'),   ('08', 'zh', '茨城县'),   ('09', 'zh', '栃木县'),
('10', 'zh', '群马县'),   ('11', 'zh', '埼玉县'),   ('12', 'zh', '千叶县'),
('13', 'zh', '东京都'),   ('14', 'zh', '神奈川县'), ('15', 'zh', '新潟县'),
('16', 'zh', '富山县'),   ('17', 'zh', '石川县'),   ('18', 'zh', '福井县'),
('19', 'zh', '山梨县'),   ('20', 'zh', '长野县'),   ('21', 'zh', '岐阜县'),
('22', 'zh', '静冈县'),   ('23', 'zh', '爱知县'),   ('24', 'zh', '三重县'),
('25', 'zh', '滋贺县'),   ('26', 'zh', '京都府'),   ('27', 'zh', '大阪府'),
('28', 'zh', '兵库县'),   ('29', 'zh', '奈良县'),   ('30', 'zh', '和歌山县'),
('31', 'zh', '鸟取县'),   ('32', 'zh', '岛根县'),   ('33', 'zh', '冈山县'),
('34', 'zh', '广岛县'),   ('35', 'zh', '山口县'),   ('36', 'zh', '德岛县'),
('37', 'zh', '香川县'),   ('38', 'zh', '爱媛县'),   ('39', 'zh', '高知县'),
('40', 'zh', '福冈县'),   ('41', 'zh', '佐贺县'),   ('42', 'zh', '长崎县'),
('43', 'zh', '熊本县'),   ('44', 'zh', '大分县'),   ('45', 'zh', '宫崎县'),
('46', 'zh', '鹿儿岛县'), ('47', 'zh', '冲绳县');

-- ── 韓国語（ko）: 標準音訳 ──────────────────────────────────────
INSERT INTO region_translations (code, lang, name) VALUES
('01', 'ko', '홋카이도'),     ('02', 'ko', '아오모리현'),   ('03', 'ko', '이와테현'),
('04', 'ko', '미야기현'),     ('05', 'ko', '아키타현'),     ('06', 'ko', '야마가타현'),
('07', 'ko', '후쿠시마현'),   ('08', 'ko', '이바라키현'),   ('09', 'ko', '도치기현'),
('10', 'ko', '군마현'),       ('11', 'ko', '사이타마현'),   ('12', 'ko', '지바현'),
('13', 'ko', '도쿄도'),       ('14', 'ko', '가나가와현'),   ('15', 'ko', '니가타현'),
('16', 'ko', '도야마현'),     ('17', 'ko', '이시카와현'),   ('18', 'ko', '후쿠이현'),
('19', 'ko', '야마나시현'),   ('20', 'ko', '나가노현'),     ('21', 'ko', '기후현'),
('22', 'ko', '시즈오카현'),   ('23', 'ko', '아이치현'),     ('24', 'ko', '미에현'),
('25', 'ko', '시가현'),       ('26', 'ko', '교토부'),       ('27', 'ko', '오사카부'),
('28', 'ko', '효고현'),       ('29', 'ko', '나라현'),       ('30', 'ko', '와카야마현'),
('31', 'ko', '돗토리현'),     ('32', 'ko', '시마네현'),     ('33', 'ko', '오카야마현'),
('34', 'ko', '히로시마현'),   ('35', 'ko', '야마구치현'),   ('36', 'ko', '도쿠시마현'),
('37', 'ko', '가가와현'),     ('38', 'ko', '에히메현'),     ('39', 'ko', '고치현'),
('40', 'ko', '후쿠오카현'),   ('41', 'ko', '사가현'),       ('42', 'ko', '나가사키현'),
('43', 'ko', '구마모토현'),   ('44', 'ko', '오이타현'),     ('45', 'ko', '미야자키현'),
('46', 'ko', '가고시마현'),   ('47', 'ko', '오키나와현');
