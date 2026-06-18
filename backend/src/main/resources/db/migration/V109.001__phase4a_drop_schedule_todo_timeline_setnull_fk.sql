-- Phase 4-A（第四陣A）: クロスドメインFK撤廃 — schedules / todos / timeline_posts を「参照先テーブル」とする SET NULL 構造FK8件を撤廃（撤廃only・孤児保持）
--
-- 1000万ユーザー耐久DB再構築 クロスドメインFK撤廃キャンペーン Phase 4-A。
-- CLAUDE.md §1「クロスドメインFKは作らない」/ §2「CASCADE/SET NULL のクロスドメイン削除連鎖は禁止」
-- 原則に従い、他ドメインの「テーブル」（users ではない）を ON DELETE SET NULL で参照する
-- 群2＝構造参照のクロスドメインFK 8件を撤廃する。
--
-- ━━━ 群2（本陣の対象）の定義 ━━━
-- 第三陣までの群1（users 親の監査列 SET NULL）と異なり、本陣の群2は「他ドメインの実テーブル
-- （schedules / todos / timeline_posts）の行が削除された時に SET NULL される構造参照」である。
-- 退会フローとは無関係で、参照先テーブルの行削除がトリガになる。
--
-- ━━━ 対象一覧（8件・すべて明示名・すべて SET NULL → 他ドメインのテーブル）━━━
--  ◆ schedules（schedule ドメイン）を参照する 4件:
--   1. activity_results        / fk_ar_schedule                 (schedule_id        → schedules SET NULL)
--   2. performance_records     / fk_pr_schedule                 (schedule_id        → schedules SET NULL)
--   3. todos                   / fk_todos_schedules             (linked_schedule_id → schedules SET NULL)
--   4. tournament_matches      / fk_tmatch_schedule             (schedule_id        → schedules SET NULL)
--  ◆ todos（todo ドメイン）を参照する 2件:
--   5. action_memos            / fk_action_memos_related_todo   (related_todo_id    → todos     SET NULL)
--   6. schedules               / fk_schedules_todos             (linked_todo_id     → todos     SET NULL)
--  ◆ timeline_posts（timeline ドメイン）を参照する 2件:
--   7. action_memos            / fk_action_memos_timeline_post  (timeline_post_id   → timeline_posts SET NULL)
--   8. property_work_packages  / fk_pwp_timeline                (timeline_post_id   → timeline_posts SET NULL)
--
-- ━━━ なぜ「撤廃only・孤児値保持・リスナー/データ操作なし」が無条件に安全か ━━━
--
-- これら8件はすべて ON DELETE SET NULL であり、参照先テーブルの行が「物理削除」された場合にのみ
-- 参照元の外部キー列が NULL 化される構造である。
-- ところが本陣の参照先 3 テーブル（schedules / todos / timeline_posts）はいずれも論理削除のみで運用される:
--   ・schedules     : deleted_at 列を持ち、ScheduleEntity は @SQLRestriction("deleted_at IS NULL") で論理削除。
--   ・todos         : deleted_at 列を持ち、TodoEntity も論理削除（deleted_at セット）のみ。
--   ・timeline_posts: deleted_at 列を持ち、@SQLRestriction("deleted_at IS NULL") で論理削除。
-- いずれも DELETE FROM による行の物理削除は運用上発生しない（家老偵察で裏取り済）。
-- → ON DELETE SET NULL が発火する契機がそもそも存在しない＝撤廃は無条件に安全であり、
--   第一陣の team / organization 親（同じく論理削除）と同じ論理で、孤児化リスクも行消失リスクも無い。
--
-- したがって本 migration は「純粋な FK 撤廃のみ」である。リスナー・データ操作・NULL 化処理は一切伴わない。
-- 参照整合性は今後アプリ層で保証する（CLAUDE.md §1）。番人テストが守る不変条件は
-- 「参照先テーブルの行を（テスト内で意図的に）物理 DELETE しても、参照元の外部キー列が NULL 化されず
--  孤児値を保持し続ける」ことであり、SET NULL 撤廃only の肝を直接検証する。
--
-- ━━━ index 判定（FK 撤廃で暗黙バッキング index が消えても CREATE INDEX 追加が必要か）━━━
--
-- 結論: 8件とも CREATE INDEX 追加は不要。
--  ・既に当該列を先頭に持つ非FK index / UNIQUE があり FK の暗黙 index に依存しないもの（5件）:
--    1. activity_results.schedule_id     : INDEX idx_ar_schedule (schedule_id) 既存（先頭=schedule_id）。
--         finder ActivityResultRepository.findByScheduleId あり → 既存 index で充足 → 追加不要。
--    2. performance_records.schedule_id  : INDEX idx_pr_schedule (schedule_id) 既存（先頭=schedule_id）。
--         finder PerformanceRecordRepository.findByScheduleIdOrderBy... あり → 既存 index で充足 → 追加不要。
--    3. todos.linked_schedule_id         : UNIQUE KEY uq_todos_linked_schedule (linked_schedule_id) 既存。
--         finder TodoRepository.findByLinkedScheduleIdAndDeletedAtIsNull あり → UNIQUE で充足 → 追加不要。
--    5. action_memos.related_todo_id     : INDEX idx_am_related_todo (related_todo_id) 既存（先頭=related_todo_id）。
--         冷たい列だが既存 index があるため → 追加不要。
--    6. schedules.linked_todo_id         : INDEX idx_schedules_linked_todo + UNIQUE uq_schedules_linked_todo 既存。
--         逆引きクエリは TodoRepository 側で済むため当列の finder は無いが、既存 index で充足 → 追加不要。
--  ・冷たい監査列で当該列を先頭にした非FK index も逆引き finder も存在しないもの（3件）:
--    4. tournament_matches.schedule_id   : schedule_id 先頭の専用 index なし・finder なし（冷たい関連列）→ クエリ証跡なし → 追加不要。
--    7. action_memos.timeline_post_id    : timeline_post_id 先頭の専用 index なし・finder なし（publish 成功時のみ埋まる冷たい列）→ クエリ証跡なし → 追加不要。
--    8. property_work_packages.timeline_post_id : timeline_post_id 先頭の専用 index なし・finder なし（冷たい関連列）→ クエリ証跡なし → 追加不要。
--
-- ━━━ 対象外（本 migration では一切触らない）━━━
--   ・各テーブルの scope（scope_type/scope_id）・user・親（parent_*）・同一ドメインFK 等は対象外。
--   ・schedules → teams/organizations/users/committees（fk_sch_org/fk_schedules_committee 等・スコープFK）。
--     ※ schedules → users（fk_sch_user）は第二陣 V96.001 で既に撤廃済。
--   ・activity_results → venues（fk_ar_venue RESTRICT）。
--   ・performance_records → users（fk_pr_user RESTRICT）/ → activity_results（fk_pr_activity SET NULL・同一活動ドメイン扱い）。
--   ・todos → projects/project_milestones（fk_todo_project/fk_todo_milestone SET NULL・同一プロジェクトドメイン）。
--   ・tournament_matches の対戦/大会同一ドメインFK。
--   ・action_memos → users（fk_action_memos_user）は第二陣 V99.001 で既に撤廃済。
--   ・property_work_packages → dwelling_units/incidents/budget_transactions（fk_pwp_dwelling/incident/budget_tx SET NULL）。
--   ・timeline_posts 自体への自己参照（parent/repost）はドメイン内。
--   上記8つの「SET NULL → schedules/todos/timeline_posts」のみを DROP する。

-- ===== schedules（参照先）を参照するクロスドメイン SET NULL FK 4件 =====

-- 1. activity_results.schedule_id → schedules (SET NULL) クロスドメイン構造参照
--    撤廃only。schedules は論理削除のみで SET NULL 発火不能。INDEX idx_ar_schedule 既存・finder あり → index 追加不要。
ALTER TABLE activity_results DROP FOREIGN KEY fk_ar_schedule;

-- 2. performance_records.schedule_id → schedules (SET NULL) クロスドメイン構造参照
--    撤廃only。schedules は論理削除のみで SET NULL 発火不能。INDEX idx_pr_schedule 既存・finder あり → index 追加不要。
ALTER TABLE performance_records DROP FOREIGN KEY fk_pr_schedule;

-- 3. todos.linked_schedule_id → schedules (SET NULL) クロスドメイン構造参照（schedule⇔todo 連携）
--    撤廃only。schedules は論理削除のみで SET NULL 発火不能。UNIQUE uq_todos_linked_schedule 既存・finder あり → index 追加不要。
ALTER TABLE todos DROP FOREIGN KEY fk_todos_schedules;

-- 4. tournament_matches.schedule_id → schedules (SET NULL) クロスドメイン構造参照
--    撤廃only。schedules は論理削除のみで SET NULL 発火不能。冷たい関連列・専用 index/finder なし → クエリ証跡なし → index 追加不要。
ALTER TABLE tournament_matches DROP FOREIGN KEY fk_tmatch_schedule;

-- ===== todos（参照先）を参照するクロスドメイン SET NULL FK 2件 =====

-- 5. action_memos.related_todo_id → todos (SET NULL) クロスドメイン構造参照
--    撤廃only。todos は論理削除のみで SET NULL 発火不能。INDEX idx_am_related_todo 既存 → index 追加不要。
ALTER TABLE action_memos DROP FOREIGN KEY fk_action_memos_related_todo;

-- 6. schedules.linked_todo_id → todos (SET NULL) クロスドメイン構造参照（schedule⇔todo 連携の逆向き）
--    撤廃only。todos は論理削除のみで SET NULL 発火不能。INDEX idx_schedules_linked_todo + UNIQUE 既存 → index 追加不要。
ALTER TABLE schedules DROP FOREIGN KEY fk_schedules_todos;

-- ===== timeline_posts（参照先）を参照するクロスドメイン SET NULL FK 2件 =====

-- 7. action_memos.timeline_post_id → timeline_posts (SET NULL) クロスドメイン構造参照（publish-daily 成功時のみ充填）
--    撤廃only。timeline_posts は論理削除（@SQLRestriction deleted_at）のみで SET NULL 発火不能。
--    冷たい列・専用 index/finder なし → クエリ証跡なし → index 追加不要。
ALTER TABLE action_memos DROP FOREIGN KEY fk_action_memos_timeline_post;

-- 8. property_work_packages.timeline_post_id → timeline_posts (SET NULL) クロスドメイン構造参照
--    撤廃only。timeline_posts は論理削除のみで SET NULL 発火不能。
--    冷たい関連列・専用 index/finder なし → クエリ証跡なし → index 追加不要。
ALTER TABLE property_work_packages DROP FOREIGN KEY fk_pwp_timeline;
