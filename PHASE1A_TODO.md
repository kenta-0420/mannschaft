# Phase 1-A クロスドメインFK 撤廃 — 残波 TODO

第一波（V62.001）で 9 件処理。第二波（V62.002）で 9 件処理。
残りは `users` 参照 約 20 件 + `teams` 24 + `organizations` 18 + その他 32。

## 完了済み（user_id 参照）

### 第一波（V62.001）— 9件
- `point_transactions`, `user_badges`, `ranking_snapshots`, `gamification_user_settings` (gamification)
- `contact_requests`(×2), `contact_request_blocks`(×2), `contact_invite_tokens` (contact)

### 第二波（V62.002）— 9件
- `feedback_votes`, `user_violations` (admin)
- `onboarding_progresses` (onboarding)
- `data_exports` (data)
- `personal_timetables` (timetable)
- `offline_sync_conflicts` (sync)
- `user_blocks`(×2) (social)
- `todo_personal_memos` (todo)

## 次波（user_id 参照 / 第三波 想定）

残存する user_id 越境FK の主な候補（grep 結果より抜粋）：

- `kb_page_favorites.fk_kbpf_user` (knowledge)
- `user_google_calendar_connections.fk_ugcc_user` (calendar)
- `user_calendar_sync_settings.fk_ucss_user` (calendar)
- `user_ical_tokens.fk_uit_user` (calendar)
- `personal_timetable_settings.fk_pts_settings_user` (timetable)
- `announcement_read_status.fk_ars_user` (announcement)
- `event_rsvp_responses.fk_rsvp_user` (event)
- `safety_responses.fk_safety_resp_user` (safety)
- その他多数（V3.x, V11.x, V13.x, V14.x, V16.x〜V19.x 系列）

第三波 PR 開始時に、上記に加えて全件 grep で確認し残対象を確定すること。

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
