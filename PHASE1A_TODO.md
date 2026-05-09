# Phase 1-A クロスドメインFK 撤廃 — 残波 TODO

第一波（V62.001）で 9 件処理。残りは `users` 参照 約 29 件 + `teams` 24 + `organizations` 18 + その他 32。

## 次波（user_id 参照 / 第二波 想定）

家老C 偵察で代表例として挙がった残対象：

- `feedback_votes` （admin → user）  *user_violations と同 Flyway 群*
- `user_violations` （admin → user）
- `personal_timetables` （timetable → user）
- `onboarding_progresses` （?? → user）
- `data_exports` （?? → user）

第二波 PR 開始時に、worktree 内で
`grep "REFERENCES users" backend/src/main/resources/db/migration/V*.sql`
を全件出して、第一波で処理済みの 9 制約を除外して残対象を確定すること。

## 次々波 (team_id 参照 / 第三波 想定)

家老C 代表例：
- `chat_channels` (chat → team)
- `timetable_terms` (timetable → team)
- `schedule_event_categories` (schedule → team)
- `personal_timetable_share_targets` (timetable → team)
- `job_postings` (jobmatching → team)

## 第四波 (organization_id 参照)

- `chat_channels` (chat → organization)
- `timetable_period_templates` (timetable → organization)
- `shift_budget_allocations` (shift → organization)
…他

## 第五波以降 (Phase 1-B: CASCADE 整理)

CLAUDE.md 原則 §2 に従い、クロスドメイン CASCADE 78 件は
`SET NULL` / `RESTRICT` / アプリ層整合性に置換。
※ Phase 1-A の FK 撤廃で大半の CASCADE は同時消滅するため、
   Phase 1-A 完了後に再棚卸しが必要。

## 参考

- 全体陣立て: `~/.claude/projects/C--Claude-mannschaft/memory/project_db_scalability_10m_users.md`
- 設計原則: `CLAUDE.md` L273-349
