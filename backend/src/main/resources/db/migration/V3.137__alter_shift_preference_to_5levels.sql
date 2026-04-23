-- F03.5 v2: preference を 3 段階から 5 段階へ拡張
-- 既存値 PREFERRED / AVAILABLE はそのまま、UNAVAILABLE は STRONG_REST に移行
-- 新規値: WEAK_REST（出れなくはない）・STRONG_REST（できれば休み）・ABSOLUTE_REST（絶対休み）
--
-- 備考: V3.073 / V3.075 では CHECK 制約を付与していないため、DROP CHECK は不要。
--       本マイグレーションで初めて CHECK 制約を導入する（設計書 §3・§7 準拠）。

-- ===== shift_requests =====
-- 既存の UNAVAILABLE レコードを STRONG_REST に変換
UPDATE shift_requests
SET preference = 'STRONG_REST'
WHERE preference = 'UNAVAILABLE';

-- 5値の CHECK 制約を付与
ALTER TABLE shift_requests
    ADD CONSTRAINT chk_shift_requests_preference
    CHECK (preference IN ('PREFERRED', 'AVAILABLE', 'WEAK_REST', 'STRONG_REST', 'ABSOLUTE_REST'));

-- ===== member_availability_defaults =====
-- 既存の UNAVAILABLE レコードを STRONG_REST に変換
UPDATE member_availability_defaults
SET preference = 'STRONG_REST'
WHERE preference = 'UNAVAILABLE';

-- 5値の CHECK 制約を付与
ALTER TABLE member_availability_defaults
    ADD CONSTRAINT chk_member_availability_defaults_preference
    CHECK (preference IN ('PREFERRED', 'AVAILABLE', 'WEAK_REST', 'STRONG_REST', 'ABSOLUTE_REST'));
