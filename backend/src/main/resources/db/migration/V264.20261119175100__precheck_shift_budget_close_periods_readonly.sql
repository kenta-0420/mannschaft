-- CMP-260909-2152 α: 組織の締め日・締め期間（shift_budget_close_periods ほか）導入の事前検査（読み取りのみ・永続書込なし）。
-- 実際の検査は FlywayRepairConfig の事前検査（repair より前）が行う。本ファイルは α の先頭を示す印で、スキーマ・データは変えない。
SELECT 1;
