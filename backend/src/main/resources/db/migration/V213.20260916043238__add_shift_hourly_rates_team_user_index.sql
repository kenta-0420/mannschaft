-- CMP-260912-1525 / Codex 検分 P2: 時給のチーム単位検索にインデックスを与える。
--
-- 背景:
--   一括取得 API (GET /api/v1/shifts/hourly-rates) は
--   WHERE team_id = ? AND user_id IN (...) AND effective_from <= ?
--   で検索し、ユーザーごとの最新 effective_from を相関副問い合わせで選ぶ。
--   既存の一意インデックス uq_shr_user_team_from は (user_id, team_id, effective_from) で
--   左端が user_id のため team_id を絞れず、履歴が複数チームに蓄積すると
--   shift_hourly_rates 全体の走査になる。一括表示のたびに全テナントの履歴件数に
--   比例して劣化するため、team_id を先頭にしたインデックスを足す。
--
--   列順は検索条件の形に合わせて (team_id, user_id, effective_from) とする。
--   相関副問い合わせ側の MAX(effective_from) も同じ並びで走るため、
--   外側・内側の両方をこの 1 本で支えられる。
--
-- 注意: MySQL は隣接する文字列リテラルを連結しない。COMMENT を改行で割らないこと。

CREATE INDEX idx_shr_team_user_from
    ON shift_hourly_rates (team_id, user_id, effective_from);
