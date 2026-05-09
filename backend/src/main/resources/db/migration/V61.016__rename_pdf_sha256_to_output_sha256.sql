-- F09.14 Phase 3-A: 出力ハッシュカラムの汎用化
-- pdf_sha256 → output_sha256 にリネーム（Word/Excel 出力でも同じカラムを使用するため）
-- 設計書 §6.3 改ざん検出: PDF/Excel/Word いずれの出力でも SHA-256 を記録する方針
ALTER TABLE disclosure_exports
    CHANGE COLUMN pdf_sha256 output_sha256 CHAR(64) NULL;
