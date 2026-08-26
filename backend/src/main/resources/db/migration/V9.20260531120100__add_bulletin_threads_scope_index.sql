-- F08.7.1 / 01 連絡機能: スコープ別スレッド一覧の主クエリ用 (scope_type, scope_id) 複合 index を追加する。
--
-- 設計書 §2.2 / §3.4 / README Y-2: bulletin_threads は V5.002 で PRIMARY KEY / category FK /
-- author FK / FULLTEXT のみ持ち、(scope_type, scope_id) 複合 index が未存在。
-- 大会・ディビジョン（および既存 ORGANIZATION/TEAM/PERSONAL/VILLAGE）のスコープ別スレッド一覧の
-- 主クエリを支えるため、ここで根治的に追加する（症状を隠さず index を新設）。
ALTER TABLE bulletin_threads
    ADD KEY idx_bt_scope (scope_type, scope_id);
