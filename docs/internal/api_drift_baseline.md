# API 乖離ベースライン報告書（2026-05-17 時点・v4 スキャナ）

> 本報告書は `backend/scripts/scan_api_drift.py` (v4) により自動生成された。
> 設計書 `docs/features/F*.md` のテーブル/見出し/インラインコード記載と、
> 実装 `backend/src/main/java/**/controller/*Controller.java` の
> Spring MVC アノテーション（新形式 + 旧 @RequestMapping(method=) 形式）を突合した結果である。

## 改訂履歴

- v1 (2026-05-16): 初回ベースライン
- v2 (2026-05-17): {scope}/{scopeId} 展開・旧 RequestMapping 強化・末尾スラッシュ吸収・インラインコード補助対応・ドメイン別サマリ表追加
- v3 (2026-05-17): 6 バグ集合根治（query 切捨・重複排除・末尾スラッシュ取りこぼし・スコープ展開拡張・文字化け read 念押し・除外パターン適用）
- v4 (2026-05-17): V4-1 スコープ階層プレフィックス逆引きマッチ + V4-5 🔵 将来機能タグ認識

## サマリ

- 設計あり・実装なし: **1223 件**（v3: 1,214 件 / v2: 1,256 件 / v1: 1,187 件）
- 実装あり・設計なし: **925 件**（v3: 1,106 件 / v2: 1,147 件 / v1: 931 件）
- 一致: **1514 件**（v3: 1,341 件 / v2: 1,322 件 / v1: 1,310 件）
- V4-1 スコープ逆引き準一致: **0 件**（一致側に繰入）
- V4-5 🔵 将来機能: **28 件**（メイン集計外）／うち実装済: 0 件
- 設計記載 ユニーク (method, path) 総数（main）: 2737
- 実装 ユニーク (method, path) 総数: 2439
- 除外（実装側）: 42 件 / 除外（設計側）: 5 件 / パターン数: 30
- スコープ展開: ON

---

## ドメイン別サマリ表

| ドメイン | 設計あり・実装なし | 実装あり・設計なし | 一致 | 合計乖離 |
|---|---:|---:|---:|---:|
| /api/v1/teams/* | 273 | 219 | 505 | 492 |
| /api/v1/organizations/* | 86 | 369 | 150 | 455 |
| /api/v1/system-admin/* | 104 | 28 | 80 | 132 |
| /api/v1/users/* | 23 | 99 | 48 | 122 |
| /api/v1/{_}/* | 68 | 0 | 0 | 68 |
| /api/v1/me/* | 36 | 20 | 70 | 56 |
| /api/v1/shifts/* | 28 | 19 | 25 | 47 |
| /api/v1/files/* | 33 | 7 | 9 | 40 |
| /api/v1/admin/* | 29 | 8 | 61 | 37 |
| /api/v1/timeline/* | 25 | 11 | 2 | 36 |
| /api/v1/chat/* | 18 | 10 | 15 | 28 |
| /api/v1/events/* | 18 | 8 | 11 | 26 |
| /api/v1/incidents/* | 25 | 0 | 0 | 25 |
| /api/v1/bulletin/* | 23 | 1 | 0 | 24 |
| /api/v1/circulation/* | 24 | 0 | 0 | 24 |
| /api/v1/dwelling-units/* | 23 | 0 | 0 | 23 |
| /api/v1/corkboards/* | 17 | 5 | 5 | 22 |
| /api/v1/activities/* | 20 | 0 | 11 | 20 |
| /api/v1/safety-checks/* | 12 | 8 | 4 | 20 |
| /api/v1/succession/* | 18 | 1 | 2 | 19 |
| /api/v1/surveys/* | 16 | 3 | 2 | 19 |
| /api/v1/job-contracts/* | 17 | 0 | 0 | 17 |
| /api/v1/promotions/* | 17 | 0 | 0 | 17 |
| /api/v1/todos/* | 4 | 12 | 1 | 16 |
| /api/v1/workflows/* | 14 | 0 | 0 | 14 |
| /api/v1/forms/* | 13 | 0 | 0 | 13 |
| /api/v1/residence-status/* | 13 | 0 | 0 | 13 |
| /api/v1/jobs/* | 9 | 3 | 6 | 12 |
| /api/v1/line/* | 11 | 0 | 0 | 11 |
| /api/v1/advertiser/* | 8 | 1 | 25 | 9 |
| /api/v1/notifications/* | 5 | 4 | 1 | 9 |
| /api/v1/point-cards/* | 7 | 2 | 7 | 9 |
| /api/v1/property-listings/* | 9 | 0 | 0 | 9 |
| /api/v1/orgs/* | 8 | 0 | 0 | 8 |
| /api/v1/public/* | 0 | 8 | 4 | 8 |
| /api/v1/sns/* | 8 | 0 | 0 | 8 |
| /api/v1/social/* | 0 | 8 | 0 | 8 |
| /api/v1/social-profiles/* | 8 | 0 | 0 | 8 |
| /api/v1/workflow-requests/* | 2 | 6 | 0 | 8 |
| /api/v1/coupons/* | 7 | 0 | 0 | 7 |
| /api/v1/recruitment-listings/* | 2 | 5 | 9 | 7 |
| /api/v1/action-memos/* | 2 | 4 | 10 | 6 |
| /api/v1/contracts/* | 0 | 6 | 0 | 6 |
| /api/v1/quick-memos/* | 5 | 1 | 11 | 6 |
| /api/v1/shift-budget/* | 1 | 5 | 3 | 6 |
| /api/v1/committees/* | 5 | 0 | 11 | 5 |
| /api/v1/follows/* | 5 | 0 | 0 | 5 |
| /api/v1/account/* | 0 | 4 | 0 | 4 |
| /api/v1/applications/* | 0 | 4 | 0 | 4 |
| /api/v1/blog/* | 4 | 0 | 19 | 4 |
| /api/v1/chat-folders/* | 2 | 2 | 5 | 4 |
| /api/v1/circulations/* | 0 | 4 | 0 | 4 |
| /api/v1/confirmable-notifications/* | 4 | 0 | 0 | 4 |
| /api/v1/feedback/* | 4 | 0 | 0 | 4 |
| /api/v1/segment-presets/* | 4 | 0 | 0 | 4 |
| /api/v1/signage/* | 3 | 1 | 0 | 4 |
| /api/v1/team/* | 4 | 0 | 21 | 4 |
| /api/v1/timeline-digest/* | 4 | 0 | 6 | 4 |
| /api/v1/embed/* | 0 | 3 | 0 | 3 |
| /api/v1/feedbacks/* | 0 | 3 | 0 | 3 |
| /api/v1/gallery/* | 2 | 1 | 9 | 3 |
| /api/v1/job-payments/* | 3 | 0 | 0 | 3 |
| /api/v1/memberships/* | 3 | 0 | 0 | 3 |
| /api/v1/mutes/* | 3 | 0 | 0 | 3 |
| /api/v1/notification-preferences/* | 2 | 1 | 1 | 3 |
| /api/v1/queue/* | 3 | 0 | 0 | 3 |
| /api/v1/recruitment/* | 0 | 3 | 0 | 3 |
| /api/v1/seal/* | 3 | 0 | 0 | 3 |
| /api/v1/stripe/* | 3 | 0 | 0 | 3 |
| /api/v1/venues/* | 0 | 3 | 0 | 3 |
| /api/v1/webhooks/* | 3 | 0 | 2 | 3 |
| /api/v1/action-memo-settings/* | 2 | 0 | 0 | 2 |
| /api/v1/action-memo-tags/* | 2 | 0 | 2 | 2 |
| /api/v1/activity-templates/* | 2 | 0 | 5 | 2 |
| /api/v1/ads/* | 1 | 1 | 1 | 2 |
| /api/v1/attendance/* | 2 | 0 | 1 | 2 |
| /api/v1/budget/* | 2 | 0 | 25 | 2 |
| /api/v1/contact-invite-tokens/* | 2 | 0 | 2 | 2 |
| /api/v1/contact-request-blocks/* | 2 | 0 | 1 | 2 |
| /api/v1/dashboard/* | 1 | 1 | 18 | 2 |
| /api/v1/jobber-invitations/* | 2 | 0 | 0 | 2 |
| /api/v1/no-show-records/* | 2 | 0 | 0 | 2 |
| /api/v1/onboarding/* | 2 | 0 | 12 | 2 |
| /api/v1/platform/* | 2 | 0 | 0 | 2 |
| /api/v1/proxy-votes/* | 2 | 0 | 30 | 2 |
| /api/v1/push-subscriptions/* | 2 | 0 | 0 | 2 |
| /api/v1/reports/* | 2 | 0 | 0 | 2 |
| /api/v1/schedules/* | 2 | 0 | 6 | 2 |
| /api/v1/search/* | 2 | 0 | 6 | 2 |
| /api/v1/templates/* | 2 | 0 | 2 | 2 |
| /api/v1/timetable-terms/* | 2 | 0 | 0 | 2 |
| /api/v1/timetables/* | 2 | 0 | 6 | 2 |
| /api/v1/todo-budget/* | 1 | 1 | 0 | 2 |
| /api/v1/villages/* | 2 | 0 | 123 | 2 |
| /api/v1/visibility-templates/* | 2 | 0 | 5 | 2 |
| /api/v1/activity-records/* | 1 | 0 | 0 | 1 |
| /api/v1/blog-posts/* | 1 | 0 | 0 | 1 |
| /api/v1/bulletin-threads/* | 1 | 0 | 0 | 1 |
| /api/v1/care-links/* | 0 | 1 | 2 | 1 |
| /api/v1/circulation-documents/* | 1 | 0 | 0 | 1 |
| /api/v1/contact-requests/* | 1 | 0 | 5 | 1 |
| /api/v1/contacts/* | 1 | 0 | 1 | 1 |
| /api/v1/disclosure-templates/* | 1 | 0 | 1 | 1 |
| /api/v1/families/* | 1 | 0 | 1 | 1 |
| /api/v1/feature-flags/* | 1 | 0 | 0 | 1 |
| /api/v1/file-permissions/* | 0 | 1 | 0 | 1 |
| /api/v1/ical/* | 0 | 1 | 0 | 1 |
| /api/v1/incoming/* | 0 | 1 | 0 | 1 |
| /api/v1/job-disputes/* | 1 | 0 | 0 | 1 |
| /api/v1/kb/* | 1 | 0 | 0 | 1 |
| /api/v1/member-positions/* | 1 | 0 | 0 | 1 |
| /api/v1/mentions/* | 0 | 1 | 0 | 1 |
| /api/v1/modules/* | 1 | 0 | 1 | 1 |
| /api/v1/my/* | 1 | 0 | 3 | 1 |
| /api/v1/permissions/* | 1 | 0 | 0 | 1 |
| /api/v1/positions/* | 1 | 0 | 0 | 1 |
| /api/v1/projects/* | 1 | 0 | 0 | 1 |
| /api/v1/proxy-input/* | 0 | 1 | 0 | 1 |
| /api/v1/proxy-input-consents/* | 1 | 0 | 4 | 1 |
| /api/v1/recruitment-categories/* | 1 | 0 | 0 | 1 |
| /api/v1/recruitment-subcategories/* | 1 | 0 | 0 | 1 |
| /api/v1/recruitment-templates/* | 0 | 1 | 2 | 1 |
| /api/v1/reservations/* | 0 | 1 | 2 | 1 |
| /api/v1/shared/* | 1 | 0 | 0 | 1 |
| /api/v1/shared-links/* | 0 | 1 | 0 | 1 |
| /api/v1/students/* | 0 | 1 | 4 | 1 |
| /api/v1/supported-locales/* | 0 | 1 | 0 | 1 |
| /api/v1/sync/* | 1 | 0 | 4 | 1 |
| /api/v1/timeline-posts/* | 1 | 0 | 0 | 1 |
| /api/v1/tournament-presets/* | 0 | 1 | 0 | 1 |
| /api/v1/user-penalties/* | 1 | 0 | 0 | 1 |
| /api/v1/warnings/* | 1 | 0 | 2 | 1 |
| /api/v1/active-incidents/* | 0 | 0 | 1 | 0 |
| /api/v1/announcements/* | 0 | 0 | 1 | 0 |
| /api/v1/appeals/* | 0 | 0 | 2 | 0 |
| /api/v1/attendance-requirements/* | 0 | 0 | 2 | 0 |
| /api/v1/auth/* | 0 | 0 | 29 | 0 |
| /api/v1/cancellation-policies/* | 0 | 0 | 3 | 0 |
| /api/v1/charts/* | 0 | 0 | 1 | 0 |
| /api/v1/committee-invitations/* | 0 | 0 | 3 | 0 |
| /api/v1/contact-invite/* | 0 | 0 | 2 | 0 |
| /api/v1/equipment/* | 0 | 0 | 1 | 0 |
| /api/v1/error-reports/* | 0 | 0 | 1 | 0 |
| /api/v1/event-categories/* | 0 | 0 | 2 | 0 |
| /api/v1/invite/* | 0 | 0 | 3 | 0 |
| /api/v1/master/* | 0 | 0 | 2 | 0 |
| /api/v1/matching/* | 0 | 0 | 12 | 0 |
| /api/v1/member-cards/* | 0 | 0 | 9 | 0 |
| /api/v1/notification-credits/* | 0 | 0 | 1 | 0 |
| /api/v1/notification-type-preferences/* | 0 | 0 | 2 | 0 |
| /api/v1/payment-items/* | 0 | 0 | 3 | 0 |
| /api/v1/performance/* | 0 | 0 | 2 | 0 |
| /api/v1/proxy-input-records/* | 0 | 0 | 1 | 0 |
| /api/v1/service-records/* | 0 | 0 | 1 | 0 |
| /api/v1/yabai/* | 0 | 0 | 2 | 0 |
| **合計** | **1223** | **925** | **1514** | **2148** |

---

## 1. 🔴 設計あり・実装なし（Phase 1 漏れ系）

### /api/v1/action-memo-settings/* (2 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| GET | `/api/v1/action-memo-settings` | `docs/features/F02.5_action_memo.md` | 230 |
| GET | `/api/v1/action-memo-settings` | `docs/features/F02.5_action_memo.md` | 339 |
| GET | `/api/v1/action-memo-settings` | `docs/features/F02.5_phase3_team_timeline_and_todo_link.md` | 314 |
| PATCH | `/api/v1/action-memo-settings` | `docs/features/F02.5_action_memo.md` | 231 |
| PATCH | `/api/v1/action-memo-settings` | `docs/features/F02.5_action_memo.md` | 353 |
| PATCH | `/api/v1/action-memo-settings` | `docs/features/F02.5_action_memo.md` | 612 |
| PATCH | `/api/v1/action-memo-settings` | `docs/features/F02.5_phase3_team_timeline_and_todo_link.md` | 315 |

### /api/v1/action-memo-tags/* (2 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| GET | `/api/v1/action-memo-tags` | `docs/features/F02.5_action_memo.md` | 226 |
| POST | `/api/v1/action-memo-tags` | `docs/features/F02.5_action_memo.md` | 227 |
| POST | `/api/v1/action-memo-tags` | `docs/features/F02.5_action_memo.md` | 611 |

### /api/v1/action-memos/* (2 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| GET | `/api/v1/action-memos` | `docs/features/F02.5_action_memo.md` | 218 |
| GET | `/api/v1/action-memos` | `docs/features/F02.5_action_memo.md` | 377 |
| GET | `/api/v1/action-memos` | `docs/features/F02.5_phase3_team_timeline_and_todo_link.md` | 311 |
| POST | `/api/v1/action-memos` | `docs/features/F02.5_action_memo.md` | 217 |
| POST | `/api/v1/action-memos` | `docs/features/F02.5_action_memo.md` | 239 |
| POST | `/api/v1/action-memos` | `docs/features/F02.5_action_memo.md` | 609 |
| POST | `/api/v1/action-memos` | `docs/features/F02.5_action_memo.md` | 640 |
| POST | `/api/v1/action-memos` | `docs/features/F02.5_phase3_team_timeline_and_todo_link.md` | 229 |
| POST | `/api/v1/action-memos` | `docs/features/F02.5_phase3_team_timeline_and_todo_link.md` | 309 |
| POST | `/api/v1/action-memos` | `docs/features/F02.5_phase3_team_timeline_and_todo_link.md` | 465 |

### /api/v1/activities/* (20 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| DELETE | `/api/v1/activities/custom-fields/{_}` | `docs/features/F06.1_cms_blog.md` | 741 |
| DELETE | `/api/v1/activities/templates/{_}` | `docs/features/F06.1_cms_blog.md` | 754 |
| DELETE | `/api/v1/activities/templates/{_}/share` | `docs/features/F06.1_cms_blog.md` | 756 |
| DELETE | `/api/v1/activities/templates/{_}/share` | `docs/features/F06.1_cms_blog.md` | 2024 |
| GET | `/api/v1/activities` | `docs/features/F06.1_cms_blog.md` | 729 |
| GET | `/api/v1/activities` | `docs/features/F06.1_cms_blog.md` | 1680 |
| GET | `/api/v1/activities` | `docs/features/F06.4_activity_records.md` | 316 |
| GET | `/api/v1/activities` | `docs/features/F06.4_activity_records.md` | 647 |
| GET | `/api/v1/activities` | `docs/features/F06.4_activity_records.md` | 903 |
| GET | `/api/v1/activities/custom-fields` | `docs/features/F06.1_cms_blog.md` | 738 |
| GET | `/api/v1/activities/stats/members/{_}` | `docs/features/F06.1_cms_blog.md` | 743 |
| GET | `/api/v1/activities/stats/members/{_}` | `docs/features/F06.1_cms_blog.md` | 1789 |
| GET | `/api/v1/activities/stats/ranking` | `docs/features/F06.1_cms_blog.md` | 744 |
| GET | `/api/v1/activities/stats/ranking` | `docs/features/F06.1_cms_blog.md` | 1845 |
| GET | `/api/v1/activities/templates` | `docs/features/F06.1_cms_blog.md` | 750 |
| GET | `/api/v1/activities/templates/official` | `docs/features/F06.1_cms_blog.md` | 757 |
| GET | `/api/v1/activities/templates/official` | `docs/features/F06.1_cms_blog.md` | 1917 |
| GET | `/api/v1/activities/templates/{_}` | `docs/features/F06.1_cms_blog.md` | 752 |
| GET | `/api/v1/activities/{_}/comments` | `docs/features/F06.4_activity_records.md` | 332 |
| POST | `/api/v1/activities` | `docs/features/F06.1_cms_blog.md` | 730 |
| POST | `/api/v1/activities` | `docs/features/F06.1_cms_blog.md` | 1458 |
| POST | `/api/v1/activities` | `docs/features/F06.4_activity_records.md` | 317 |
| POST | `/api/v1/activities` | `docs/features/F06.4_activity_records.md` | 467 |
| POST | `/api/v1/activities` | `docs/features/F06.4_activity_records.md` | 562 |
| POST | `/api/v1/activities` | `docs/features/F06.4_activity_records.md` | 578 |
| POST | `/api/v1/activities/custom-fields` | `docs/features/F06.1_cms_blog.md` | 739 |
| POST | `/api/v1/activities/generate-from-schedule` | `docs/features/F06.1_cms_blog.md` | 735 |
| POST | `/api/v1/activities/generate-from-schedule` | `docs/features/F06.1_cms_blog.md` | 1524 |
| POST | `/api/v1/activities/generate-from-schedule` | `docs/features/F06.1_cms_blog.md` | 2480 |
| POST | `/api/v1/activities/templates` | `docs/features/F06.1_cms_blog.md` | 751 |
| POST | `/api/v1/activities/templates/import` | `docs/features/F06.1_cms_blog.md` | 758 |
| POST | `/api/v1/activities/templates/import` | `docs/features/F06.1_cms_blog.md` | 1961 |
| POST | `/api/v1/activities/templates/{_}/share` | `docs/features/F06.1_cms_blog.md` | 755 |
| POST | `/api/v1/activities/templates/{_}/share` | `docs/features/F06.1_cms_blog.md` | 2006 |
| POST | `/api/v1/activities/{_}/comments` | `docs/features/F06.4_activity_records.md` | 333 |
| PUT | `/api/v1/activities/custom-fields/{_}` | `docs/features/F06.1_cms_blog.md` | 740 |
| PUT | `/api/v1/activities/templates/{_}` | `docs/features/F06.1_cms_blog.md` | 753 |

### /api/v1/activity-records/* (1 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| POST | `/api/v1/activity-records` | `docs/features/F04.10_committee.md` | 381 |

### /api/v1/activity-templates/* (2 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| GET | `/api/v1/activity-templates` | `docs/features/F06.4_activity_records.md` | 306 |
| POST | `/api/v1/activity-templates` | `docs/features/F06.4_activity_records.md` | 307 |
| POST | `/api/v1/activity-templates` | `docs/features/F06.4_activity_records.md` | 363 |
| POST | `/api/v1/activity-templates` | `docs/features/F06.4_activity_records.md` | 450 |

### /api/v1/admin/* (29 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| DELETE | `/api/v1/admin/team-friends/{_}` | `docs/features/F01.5_team_friend_relationships.md` | 1265 |
| GET | `/api/v1/admin/action-templates` | `docs/features/F10.1_admin_dashboard.md` | 457 |
| GET | `/api/v1/admin/action-templates` | `docs/features/F10.1_admin_dashboard.md` | 1060 |
| GET | `/api/v1/admin/dashboard` | `docs/features/F10.1_admin_dashboard.md` | 469 |
| GET | `/api/v1/admin/dashboard` | `docs/features/F10.1_admin_dashboard.md` | 1165 |
| GET | `/api/v1/admin/dashboard-stats` | `docs/features/F04.10_committee.md` | 1039 |
| GET | `/api/v1/admin/feedbacks` | `docs/features/F10.1_admin_dashboard.md` | 461 |
| GET | `/api/v1/admin/form-presets` | `docs/features/F05.7_form_builder.md` | 346 |
| GET | `/api/v1/admin/moderation/reports` | `docs/features/F04.5_moderation.md` | 56 |
| GET | `/api/v1/admin/moderation/reports` | `docs/features/F10.1_admin_dashboard.md` | 443 |
| GET | `/api/v1/admin/onboarding/presets` | `docs/features/F02.4_onboarding.md` | 336 |
| GET | `/api/v1/admin/permission-groups` | `docs/features/F10.1_admin_dashboard.md` | 472 |
| GET | `/api/v1/admin/receipt-presets` | `docs/features/F08.4_receipt.md` | 343 |
| GET | `/api/v1/admin/receipt-queue` | `docs/features/F08.4_receipt.md` | 351 |
| GET | `/api/v1/admin/receipt-settings` | `docs/features/F08.4_receipt.md` | 307 |
| GET | `/api/v1/admin/receipt-settings` | `docs/features/F08.4_receipt.md` | 364 |
| GET | `/api/v1/admin/receipts` | `docs/features/F08.4_receipt.md` | 326 |
| GET | `/api/v1/admin/receipts` | `docs/features/F08.4_receipt.md` | 857 |
| GET | `/api/v1/admin/reports` | `docs/features/F04.5_moderation.md` | 55 |
| GET | `/api/v1/admin/reports` | `docs/features/F04.5_moderation.md` | 120 |
| GET | `/api/v1/admin/reports` | `docs/features/F10.1_admin_dashboard.md` | 442 |
| GET | `/api/v1/admin/reports` | `docs/features/F10.1_admin_dashboard.md` | 608 |
| GET | `/api/v1/admin/reports/{_}` | `docs/features/F04.5_moderation.md` | 180 |
| GET | `/api/v1/admin/reports/{_}` | `docs/features/F10.1_admin_dashboard.md` | 650 |
| GET | `/api/v1/admin/seals/regenerate-all/{_}/status` | `docs/features/F05.3_digital_seal.md` | 527 |
| GET | `/api/v1/admin/seals/ungenerated` | `docs/features/F05.3_digital_seal.md` | 558 |
| GET | `/api/v1/admin/users/{_}/violation-history` | `docs/features/F10.1_admin_dashboard.md` | 933 |
| PATCH | `/api/v1/admin/platform/settings` | `docs/features/F04.1_timeline.md` | 1816 |
| PATCH | `/api/v1/admin/reports/{_}` | `docs/features/F04.5_moderation.md` | 229 |
| PATCH | `/api/v1/admin/social-profiles/{_}/freeze` | `docs/features/F04.4_social_profiles.md` | 97 |
| PATCH | `/api/v1/admin/social-profiles/{_}/freeze` | `docs/features/F04.4_social_profiles.md` | 282 |
| POST | `/api/v1/admin/action-templates` | `docs/features/F10.1_admin_dashboard.md` | 458 |
| POST | `/api/v1/admin/action-templates` | `docs/features/F10.1_admin_dashboard.md` | 1095 |
| POST | `/api/v1/admin/form-presets` | `docs/features/F05.7_form_builder.md` | 347 |
| POST | `/api/v1/admin/onboarding/presets` | `docs/features/F02.4_onboarding.md` | 337 |
| POST | `/api/v1/admin/permission-groups` | `docs/features/F10.1_admin_dashboard.md` | 473 |
| POST | `/api/v1/admin/receipt-presets` | `docs/features/F08.4_receipt.md` | 344 |
| POST | `/api/v1/admin/receipts` | `docs/features/F08.4_receipt.md` | 315 |
| POST | `/api/v1/admin/receipts` | `docs/features/F08.4_receipt.md` | 432 |
| POST | `/api/v1/admin/users/{_}/care-links` | `docs/features/F03.12_care_recipient_event_watch_notification.md` | 457 |
| PUT | `/api/v1/admin/receipt-settings` | `docs/features/F08.4_receipt.md` | 308 |
| PUT | `/api/v1/admin/receipt-settings` | `docs/features/F08.4_receipt.md` | 386 |

### /api/v1/ads/* (1 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| GET | `/api/v1/ads/unsubscribe` | `docs/features/F09.17_advertiser_targeted_campaign.md` | 441 |
| GET | `/api/v1/ads/unsubscribe` | `docs/features/F09.17_advertiser_targeted_campaign.md` | 662 |

### /api/v1/advertiser/* (8 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| GET | `/api/v1/advertiser/campaigns/messaging` | `docs/features/F09.17_advertiser_targeted_campaign.md` | 424 |
| GET | `/api/v1/advertiser/campaigns/messaging` | `docs/features/F09.17_advertiser_targeted_campaign.md` | 454 |
| GET | `/api/v1/advertiser/campaigns/messaging/{_}/report` | `docs/features/F09.17_advertiser_targeted_campaign.md` | 437 |
| GET | `/api/v1/advertiser/campaigns/messaging/{_}/report` | `docs/features/F09.17_advertiser_targeted_campaign.md` | 602 |
| GET | `/api/v1/advertiser/campaigns/messaging/{_}/report/export.csv` | `docs/features/F09.17_advertiser_targeted_campaign.md` | 438 |
| GET | `/api/v1/advertiser/campaigns/messaging/{_}/report/export.csv` | `docs/features/F09.17_advertiser_targeted_campaign.md` | 627 |
| POST | `/api/v1/advertiser/campaigns/messaging` | `docs/features/F09.17_advertiser_targeted_campaign.md` | 425 |
| POST | `/api/v1/advertiser/campaigns/messaging` | `docs/features/F09.17_advertiser_targeted_campaign.md` | 479 |
| POST | `/api/v1/advertiser/campaigns/messaging/{_}/launch` | `docs/features/F09.17_advertiser_targeted_campaign.md` | 435 |
| POST | `/api/v1/advertiser/campaigns/messaging/{_}/launch` | `docs/features/F09.17_advertiser_targeted_campaign.md` | 588 |
| POST | `/api/v1/advertiser/campaigns/messaging/{_}/pause` | `docs/features/F09.17_advertiser_targeted_campaign.md` | 436 |
| POST | `/api/v1/advertiser/campaigns/messaging/{_}/pause` | `docs/features/F09.17_advertiser_targeted_campaign.md` | 598 |
| POST | `/api/v1/advertiser/campaigns/messaging/{_}/preview` | `docs/features/F09.17_advertiser_targeted_campaign.md` | 433 |
| POST | `/api/v1/advertiser/campaigns/messaging/{_}/preview` | `docs/features/F09.17_advertiser_targeted_campaign.md` | 554 |
| POST | `/api/v1/advertiser/campaigns/messaging/{_}/submit` | `docs/features/F09.17_advertiser_targeted_campaign.md` | 434 |
| POST | `/api/v1/advertiser/campaigns/messaging/{_}/submit` | `docs/features/F09.17_advertiser_targeted_campaign.md` | 577 |

### /api/v1/attendance/* (2 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| POST | `/api/v1/attendance/requirements/evaluations/{_}/disclose` | `docs/features/F03.13_school_daily_subject_attendance.md` | 969 |
| POST | `/api/v1/attendance/requirements/evaluations/{_}/withhold` | `docs/features/F03.13_school_daily_subject_attendance.md` | 970 |

### /api/v1/blog/* (4 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| GET | `/api/v1/blog/series` | `docs/features/F06.1_cms_blog.md` | 700 |
| GET | `/api/v1/blog/tags` | `docs/features/F06.1_cms_blog.md` | 693 |
| GET | `/api/v1/blog/tags` | `docs/features/F06.1_cms_blog.md` | 982 |
| POST | `/api/v1/blog/series` | `docs/features/F06.1_cms_blog.md` | 701 |
| POST | `/api/v1/blog/tags` | `docs/features/F06.1_cms_blog.md` | 694 |
| POST | `/api/v1/blog/tags` | `docs/features/F06.1_cms_blog.md` | 1038 |

### /api/v1/blog-posts/* (1 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| GET | `/api/v1/blog-posts` | `docs/features/F02.5_action_memo.md` | 235 |
| GET | `/api/v1/blog-posts` | `docs/features/F02.5_action_memo.md` | 766 |

### /api/v1/budget/* (2 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| GET | `/api/v1/budget/fiscal-years/{_}/allocations` | `docs/features/F08.6_budget_accounting.md` | 370 |
| PUT | `/api/v1/budget/fiscal-years/{_}/allocations` | `docs/features/F08.6_budget_accounting.md` | 371 |
| PUT | `/api/v1/budget/fiscal-years/{_}/allocations` | `docs/features/F08.6_budget_accounting.md` | 501 |

### /api/v1/bulletin/* (23 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| DELETE | `/api/v1/bulletin/categories/{_}` | `docs/features/F05.1_bulletin_board.md` | 260 |
| DELETE | `/api/v1/bulletin/categories/{_}` | `docs/features/F05.1_bulletin_board.md` | 393 |
| DELETE | `/api/v1/bulletin/replies/{_}` | `docs/features/F05.1_bulletin_board.md` | 274 |
| DELETE | `/api/v1/bulletin/replies/{_}` | `docs/features/F05.1_bulletin_board.md` | 943 |
| DELETE | `/api/v1/bulletin/threads/{_}` | `docs/features/F05.1_bulletin_board.md` | 265 |
| DELETE | `/api/v1/bulletin/threads/{_}` | `docs/features/F05.1_bulletin_board.md` | 658 |
| DELETE | `/api/v1/bulletin/{_}/{_}/reactions/{_}` | `docs/features/F05.1_bulletin_board.md` | 280 |
| DELETE | `/api/v1/bulletin/{_}/{_}/reactions/{_}` | `docs/features/F05.1_bulletin_board.md` | 1103 |
| GET | `/api/v1/bulletin/categories` | `docs/features/F05.1_bulletin_board.md` | 257 |
| GET | `/api/v1/bulletin/categories` | `docs/features/F05.1_bulletin_board.md` | 284 |
| GET | `/api/v1/bulletin/threads` | `docs/features/F05.1_bulletin_board.md` | 261 |
| GET | `/api/v1/bulletin/threads` | `docs/features/F05.1_bulletin_board.md` | 416 |
| GET | `/api/v1/bulletin/threads/updates` | `docs/features/F05.1_bulletin_board.md` | 275 |
| GET | `/api/v1/bulletin/threads/updates` | `docs/features/F05.1_bulletin_board.md` | 965 |
| GET | `/api/v1/bulletin/threads/{_}` | `docs/features/F05.1_bulletin_board.md` | 263 |
| GET | `/api/v1/bulletin/threads/{_}` | `docs/features/F05.1_bulletin_board.md` | 540 |
| GET | `/api/v1/bulletin/threads/{_}/readers` | `docs/features/F05.1_bulletin_board.md` | 268 |
| GET | `/api/v1/bulletin/threads/{_}/readers` | `docs/features/F05.1_bulletin_board.md` | 278 |
| GET | `/api/v1/bulletin/threads/{_}/readers` | `docs/features/F05.1_bulletin_board.md` | 732 |
| PATCH | `/api/v1/bulletin/threads/{_}/archive` | `docs/features/F05.1_bulletin_board.md` | 277 |
| PATCH | `/api/v1/bulletin/threads/{_}/archive` | `docs/features/F05.1_bulletin_board.md` | 1030 |
| PATCH | `/api/v1/bulletin/threads/{_}/lock` | `docs/features/F05.1_bulletin_board.md` | 270 |
| PATCH | `/api/v1/bulletin/threads/{_}/lock` | `docs/features/F05.1_bulletin_board.md` | 804 |
| PATCH | `/api/v1/bulletin/threads/{_}/pin` | `docs/features/F05.1_bulletin_board.md` | 269 |
| PATCH | `/api/v1/bulletin/threads/{_}/pin` | `docs/features/F05.1_bulletin_board.md` | 775 |
| PATCH | `/api/v1/bulletin/threads/{_}/priority` | `docs/features/F05.1_bulletin_board.md` | 266 |
| PATCH | `/api/v1/bulletin/threads/{_}/priority` | `docs/features/F05.1_bulletin_board.md` | 680 |
| POST | `/api/v1/bulletin/categories` | `docs/features/F05.1_bulletin_board.md` | 258 |
| POST | `/api/v1/bulletin/categories` | `docs/features/F05.1_bulletin_board.md` | 321 |
| POST | `/api/v1/bulletin/replies/{_}/replies` | `docs/features/F05.1_bulletin_board.md` | 272 |
| POST | `/api/v1/bulletin/replies/{_}/replies` | `docs/features/F05.1_bulletin_board.md` | 874 |
| POST | `/api/v1/bulletin/threads` | `docs/features/F05.1_bulletin_board.md` | 262 |
| POST | `/api/v1/bulletin/threads` | `docs/features/F05.1_bulletin_board.md` | 477 |
| POST | `/api/v1/bulletin/threads/read-all` | `docs/features/F05.1_bulletin_board.md` | 276 |
| POST | `/api/v1/bulletin/threads/read-all` | `docs/features/F05.1_bulletin_board.md` | 998 |
| POST | `/api/v1/bulletin/threads/{_}/read` | `docs/features/F05.1_bulletin_board.md` | 267 |
| POST | `/api/v1/bulletin/threads/{_}/read` | `docs/features/F05.1_bulletin_board.md` | 713 |
| POST | `/api/v1/bulletin/threads/{_}/replies` | `docs/features/F05.1_bulletin_board.md` | 271 |
| POST | `/api/v1/bulletin/threads/{_}/replies` | `docs/features/F05.1_bulletin_board.md` | 833 |
| POST | `/api/v1/bulletin/{_}/{_}/reactions` | `docs/features/F05.1_bulletin_board.md` | 279 |
| POST | `/api/v1/bulletin/{_}/{_}/reactions` | `docs/features/F05.1_bulletin_board.md` | 1059 |
| PUT | `/api/v1/bulletin/categories/{_}` | `docs/features/F05.1_bulletin_board.md` | 259 |
| PUT | `/api/v1/bulletin/categories/{_}` | `docs/features/F05.1_bulletin_board.md` | 366 |
| PUT | `/api/v1/bulletin/replies/{_}` | `docs/features/F05.1_bulletin_board.md` | 273 |
| PUT | `/api/v1/bulletin/replies/{_}` | `docs/features/F05.1_bulletin_board.md` | 915 |
| PUT | `/api/v1/bulletin/threads/{_}` | `docs/features/F05.1_bulletin_board.md` | 264 |
| PUT | `/api/v1/bulletin/threads/{_}` | `docs/features/F05.1_bulletin_board.md` | 626 |

### /api/v1/bulletin-threads/* (1 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| GET | `/api/v1/bulletin-threads/{_}/context` | `docs/features/F09.8_corkboard.md` | 277 |

### /api/v1/chat/* (18 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| DELETE | `/api/v1/chat/messages/{_}/bookmark` | `docs/features/F04.2_chat.md` | 304 |
| DELETE | `/api/v1/chat/messages/{_}/bookmark` | `docs/features/F04.2_chat.md` | 1202 |
| DELETE | `/api/v1/chat/messages/{_}/reactions/{_}` | `docs/features/F04.2_chat.md` | 295 |
| DELETE | `/api/v1/chat/messages/{_}/reactions/{_}` | `docs/features/F04.2_chat.md` | 885 |
| GET | `/api/v1/chat/bookmarks` | `docs/features/F04.2_chat.md` | 305 |
| GET | `/api/v1/chat/bookmarks` | `docs/features/F04.2_chat.md` | 1215 |
| GET | `/api/v1/chat/channels` | `docs/features/F04.2_chat.md` | 277 |
| GET | `/api/v1/chat/channels` | `docs/features/F04.2_chat.md` | 313 |
| GET | `/api/v1/chat/messages/{_}/context` | `docs/features/F09.8_corkboard.md` | 275 |
| PATCH | `/api/v1/chat/channels/{_}/archive` | `docs/features/F04.2_chat.md` | 282 |
| PATCH | `/api/v1/chat/channels/{_}/archive` | `docs/features/F04.2_chat.md` | 1041 |
| PATCH | `/api/v1/chat/channels/{_}/members/me` | `docs/features/F04.2_chat.md` | 287 |
| PATCH | `/api/v1/chat/channels/{_}/members/me` | `docs/features/F04.2_chat.md` | 1077 |
| PATCH | `/api/v1/chat/messages/{_}/pin` | `docs/features/F04.2_chat.md` | 299 |
| PATCH | `/api/v1/chat/messages/{_}/pin` | `docs/features/F04.2_chat.md` | 899 |
| POST | `/api/v1/chat/channels` | `docs/features/F04.2_chat.md` | 278 |
| POST | `/api/v1/chat/channels` | `docs/features/F04.2_chat.md` | 380 |
| POST | `/api/v1/chat/channels/dm` | `docs/features/F04.2_chat.md` | 298 |
| POST | `/api/v1/chat/channels/dm` | `docs/features/F04.2_chat.md` | 1017 |
| POST | `/api/v1/chat/channels/{_}/icon/upload-url` | `docs/features/F04.2_chat.md` | 301 |
| POST | `/api/v1/chat/channels/{_}/messages/upload-url` | `docs/features/F04.2_chat.md` | 300 |
| POST | `/api/v1/chat/channels/{_}/messages/upload-url` | `docs/features/F04.2_chat.md` | 1112 |
| POST | `/api/v1/chat/channels/{_}/read` | `docs/features/F04.2_chat.md` | 296 |
| POST | `/api/v1/chat/channels/{_}/read` | `docs/features/F04.2_chat.md` | 926 |
| POST | `/api/v1/chat/conversations` | `docs/features/F04.2_chat.md` | 308 |
| POST | `/api/v1/chat/conversations` | `docs/features/F04.2_chat.md` | 971 |
| POST | `/api/v1/chat/conversations` | `docs/features/F04.2_chat.md` | 1729 |
| POST | `/api/v1/chat/messages/{_}/bookmark` | `docs/features/F04.2_chat.md` | 303 |
| POST | `/api/v1/chat/messages/{_}/bookmark` | `docs/features/F04.2_chat.md` | 1179 |
| POST | `/api/v1/chat/messages/{_}/reactions` | `docs/features/F04.2_chat.md` | 294 |
| POST | `/api/v1/chat/messages/{_}/reactions` | `docs/features/F04.2_chat.md` | 860 |
| PUT | `/api/v1/chat/channels/{_}` | `docs/features/F04.2_chat.md` | 280 |
| PUT | `/api/v1/chat/channels/{_}` | `docs/features/F04.2_chat.md` | 459 |
| PUT | `/api/v1/chat/messages/{_}` | `docs/features/F04.2_chat.md` | 290 |
| PUT | `/api/v1/chat/messages/{_}` | `docs/features/F04.2_chat.md` | 708 |

### /api/v1/chat-folders/* (2 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| GET | `/api/v1/chat-folders` | `docs/features/F02.2_dashboard.md` | 271 |
| POST | `/api/v1/chat-folders` | `docs/features/F02.2_dashboard.md` | 272 |
| POST | `/api/v1/chat-folders` | `docs/features/F02.2_dashboard.md` | 816 |

### /api/v1/circulation/* (24 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| DELETE | `/api/v1/circulation/{_}` | `docs/features/F05.2_circular.md` | 217 |
| DELETE | `/api/v1/circulation/{_}` | `docs/features/F05.2_circular.md` | 524 |
| DELETE | `/api/v1/circulation/{_}/attachments/{_}` | `docs/features/F05.2_circular.md` | 230 |
| DELETE | `/api/v1/circulation/{_}/attachments/{_}` | `docs/features/F05.2_circular.md` | 998 |
| DELETE | `/api/v1/circulation/{_}/comments/{_}` | `docs/features/F05.2_circular.md` | 233 |
| DELETE | `/api/v1/circulation/{_}/comments/{_}` | `docs/features/F05.2_circular.md` | 1105 |
| GET | `/api/v1/circulation` | `docs/features/F05.2_circular.md` | 213 |
| GET | `/api/v1/circulation` | `docs/features/F05.2_circular.md` | 240 |
| GET | `/api/v1/circulation/my` | `docs/features/F05.2_circular.md` | 228 |
| GET | `/api/v1/circulation/my` | `docs/features/F05.2_circular.md` | 855 |
| GET | `/api/v1/circulation/{_}` | `docs/features/F05.2_circular.md` | 215 |
| GET | `/api/v1/circulation/{_}` | `docs/features/F05.2_circular.md` | 371 |
| GET | `/api/v1/circulation/{_}/comments` | `docs/features/F05.2_circular.md` | 231 |
| GET | `/api/v1/circulation/{_}/comments` | `docs/features/F05.2_circular.md` | 1017 |
| GET | `/api/v1/circulation/{_}/export` | `docs/features/F05.2_circular.md` | 226 |
| GET | `/api/v1/circulation/{_}/export` | `docs/features/F05.2_circular.md` | 876 |
| GET | `/api/v1/circulation/{_}/export/status` | `docs/features/F05.2_circular.md` | 227 |
| GET | `/api/v1/circulation/{_}/export/status` | `docs/features/F05.2_circular.md` | 916 |
| GET | `/api/v1/circulation/{_}/status` | `docs/features/F05.2_circular.md` | 225 |
| GET | `/api/v1/circulation/{_}/status` | `docs/features/F05.2_circular.md` | 803 |
| PATCH | `/api/v1/circulation/{_}/recipients/{_}/skip` | `docs/features/F05.2_circular.md` | 223 |
| PATCH | `/api/v1/circulation/{_}/recipients/{_}/skip` | `docs/features/F05.2_circular.md` | 705 |
| POST | `/api/v1/circulation` | `docs/features/F05.2_circular.md` | 214 |
| POST | `/api/v1/circulation` | `docs/features/F05.2_circular.md` | 305 |
| POST | `/api/v1/circulation/batch/force-complete` | `docs/features/F05.2_circular.md` | 236 |
| POST | `/api/v1/circulation/batch/force-complete` | `docs/features/F05.2_circular.md` | 1190 |
| POST | `/api/v1/circulation/{_}/attachments` | `docs/features/F05.2_circular.md` | 229 |
| POST | `/api/v1/circulation/{_}/attachments` | `docs/features/F05.2_circular.md` | 961 |
| POST | `/api/v1/circulation/{_}/cancel` | `docs/features/F05.2_circular.md` | 219 |
| POST | `/api/v1/circulation/{_}/cancel` | `docs/features/F05.2_circular.md` | 564 |
| POST | `/api/v1/circulation/{_}/comments` | `docs/features/F05.2_circular.md` | 232 |
| POST | `/api/v1/circulation/{_}/comments` | `docs/features/F05.2_circular.md` | 1064 |
| POST | `/api/v1/circulation/{_}/duplicate` | `docs/features/F05.2_circular.md` | 234 |
| POST | `/api/v1/circulation/{_}/duplicate` | `docs/features/F05.2_circular.md` | 1125 |
| POST | `/api/v1/circulation/{_}/force-complete` | `docs/features/F05.2_circular.md` | 220 |
| POST | `/api/v1/circulation/{_}/force-complete` | `docs/features/F05.2_circular.md` | 737 |
| POST | `/api/v1/circulation/{_}/remind` | `docs/features/F05.2_circular.md` | 224 |
| POST | `/api/v1/circulation/{_}/remind` | `docs/features/F05.2_circular.md` | 771 |
| POST | `/api/v1/circulation/{_}/stamp` | `docs/features/F05.2_circular.md` | 221 |
| POST | `/api/v1/circulation/{_}/stamp` | `docs/features/F05.2_circular.md` | 589 |
| POST | `/api/v1/circulation/{_}/stamp/correct` | `docs/features/F05.2_circular.md` | 222 |
| POST | `/api/v1/circulation/{_}/stamp/correct` | `docs/features/F05.2_circular.md` | 649 |
| POST | `/api/v1/circulation/{_}/stamp/delegate` | `docs/features/F05.2_circular.md` | 235 |
| POST | `/api/v1/circulation/{_}/stamp/delegate` | `docs/features/F05.2_circular.md` | 1148 |
| POST | `/api/v1/circulation/{_}/start` | `docs/features/F05.2_circular.md` | 218 |
| POST | `/api/v1/circulation/{_}/start` | `docs/features/F05.2_circular.md` | 545 |
| PUT | `/api/v1/circulation/{_}` | `docs/features/F05.2_circular.md` | 216 |
| PUT | `/api/v1/circulation/{_}` | `docs/features/F05.2_circular.md` | 478 |

### /api/v1/circulation-documents/* (1 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| POST | `/api/v1/circulation-documents` | `docs/features/F04.10_committee.md` | 379 |

### /api/v1/committees/* (5 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| DELETE | `/api/v1/committees/{_}` | `docs/features/F04.10_committee.md` | 345 |
| GET | `/api/v1/committees/{_}/activity-records/{_}/pdf` | `docs/features/F04.10_committee.md` | 1043 |
| GET | `/api/v1/committees/{_}/distributions` | `docs/features/F04.10_committee.md` | 367 |
| GET | `/api/v1/committees/{_}/distributions/{_}/pdf` | `docs/features/F04.10_committee.md` | 1044 |
| POST | `/api/v1/committees/{_}/distributions` | `docs/features/F04.10_committee.md` | 366 |
| POST | `/api/v1/committees/{_}/distributions` | `docs/features/F04.10_committee.md` | 474 |

### /api/v1/confirmable-notifications/* (4 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| GET | `/api/v1/confirmable-notifications/confirm-by-token` | `docs/features/F04.9_confirmable_notification.md` | 296 |
| GET | `/api/v1/confirmable-notifications/confirm-by-token` | `docs/features/F04.9_confirmable_notification.md` | 394 |
| GET | `/api/v1/confirmable-notifications/pending` | `docs/features/F04.9_confirmable_notification.md` | 297 |
| GET | `/api/v1/confirmable-notifications/pending` | `docs/features/F04.9_confirmable_notification.md` | 425 |
| POST | `/api/v1/confirmable-notifications` | `docs/features/F04.10_committee.md` | 380 |
| POST | `/api/v1/confirmable-notifications/{_}/confirm` | `docs/features/F04.9_confirmable_notification.md` | 295 |

### /api/v1/contact-invite-tokens/* (2 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| GET | `/api/v1/contact-invite-tokens` | `docs/features/F04.8_contact.md` | 554 |
| POST | `/api/v1/contact-invite-tokens` | `docs/features/F04.8_contact.md` | 525 |
| POST | `/api/v1/contact-invite-tokens` | `docs/features/F04.8_contact.md` | 992 |

### /api/v1/contact-request-blocks/* (2 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| GET | `/api/v1/contact-request-blocks` | `docs/features/F04.8_contact.md` | 473 |
| POST | `/api/v1/contact-request-blocks` | `docs/features/F04.8_contact.md` | 477 |

### /api/v1/contact-requests/* (1 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| POST | `/api/v1/contact-requests` | `docs/features/F04.8_contact.md` | 410 |
| POST | `/api/v1/contact-requests` | `docs/features/F04.8_contact.md` | 723 |
| POST | `/api/v1/contact-requests` | `docs/features/F04.8_contact.md` | 989 |

### /api/v1/contacts/* (1 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| GET | `/api/v1/contacts` | `docs/features/F04.8_contact.md` | 379 |

### /api/v1/corkboards/* (17 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| DELETE | `/api/v1/corkboards/{_}` | `docs/features/F09.8_corkboard.md` | 242 |
| DELETE | `/api/v1/corkboards/{_}/sections/{_}` | `docs/features/F09.8_corkboard.md` | 267 |
| DELETE | `/api/v1/corkboards/{_}/sections/{_}/cards/{_}` | `docs/features/F09.8_corkboard.md` | 269 |
| GET | `/api/v1/corkboards/me` | `docs/features/F09.8_corkboard.md` | 234 |
| GET | `/api/v1/corkboards/me` | `docs/features/F09.8_corkboard.md` | 283 |
| GET | `/api/v1/corkboards/{_}/cards` | `docs/features/F09.8_corkboard.md` | 248 |
| GET | `/api/v1/corkboards/{_}/cards` | `docs/features/F09.8_corkboard.md` | 714 |
| GET | `/api/v1/corkboards/{_}/sections` | `docs/features/F09.8_corkboard.md` | 264 |
| PATCH | `/api/v1/corkboards/{_}/cards/batch` | `docs/features/F09.8_corkboard.md` | 257 |
| PATCH | `/api/v1/corkboards/{_}/cards/batch` | `docs/features/F09.8_corkboard.md` | 655 |
| PATCH | `/api/v1/corkboards/{_}/cards/{_}/position` | `docs/features/F09.8_corkboard.md` | 252 |
| PATCH | `/api/v1/corkboards/{_}/cards/{_}/position` | `docs/features/F09.8_corkboard.md` | 576 |
| POST | `/api/v1/corkboards/me` | `docs/features/F09.8_corkboard.md` | 237 |
| POST | `/api/v1/corkboards/me` | `docs/features/F09.8_corkboard.md` | 426 |
| POST | `/api/v1/corkboards/{_}/cards` | `docs/features/F09.8_corkboard.md` | 249 |
| POST | `/api/v1/corkboards/{_}/cards` | `docs/features/F09.8_corkboard.md` | 469 |
| POST | `/api/v1/corkboards/{_}/cards/{_}/archive` | `docs/features/F09.8_corkboard.md` | 253 |
| POST | `/api/v1/corkboards/{_}/cards/{_}/duplicate` | `docs/features/F09.8_corkboard.md` | 256 |
| POST | `/api/v1/corkboards/{_}/cards/{_}/duplicate` | `docs/features/F09.8_corkboard.md` | 640 |
| POST | `/api/v1/corkboards/{_}/cards/{_}/unarchive` | `docs/features/F09.8_corkboard.md` | 254 |
| POST | `/api/v1/corkboards/{_}/sections` | `docs/features/F09.8_corkboard.md` | 265 |
| POST | `/api/v1/corkboards/{_}/sections/{_}/cards/{_}` | `docs/features/F09.8_corkboard.md` | 268 |
| PUT | `/api/v1/corkboards/{_}` | `docs/features/F09.8_corkboard.md` | 241 |
| PUT | `/api/v1/corkboards/{_}/sections/{_}` | `docs/features/F09.8_corkboard.md` | 266 |

### /api/v1/coupons/* (7 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| DELETE | `/api/v1/coupons/{_}` | `docs/features/F09.2_promotion_targeting.md` | 396 |
| GET | `/api/v1/coupons` | `docs/features/F09.2_promotion_targeting.md` | 392 |
| GET | `/api/v1/coupons` | `docs/features/F09.2_promotion_targeting.md` | 642 |
| GET | `/api/v1/coupons/my` | `docs/features/F09.2_promotion_targeting.md` | 398 |
| GET | `/api/v1/coupons/my` | `docs/features/F09.2_promotion_targeting.md` | 754 |
| GET | `/api/v1/coupons/{_}` | `docs/features/F09.2_promotion_targeting.md` | 394 |
| POST | `/api/v1/coupons` | `docs/features/F09.2_promotion_targeting.md` | 393 |
| POST | `/api/v1/coupons` | `docs/features/F09.2_promotion_targeting.md` | 660 |
| POST | `/api/v1/coupons/{_}/redeem` | `docs/features/F09.2_promotion_targeting.md` | 397 |
| POST | `/api/v1/coupons/{_}/redeem` | `docs/features/F09.2_promotion_targeting.md` | 716 |
| PUT | `/api/v1/coupons/{_}` | `docs/features/F09.2_promotion_targeting.md` | 395 |

### /api/v1/dashboard/* (1 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| GET | `/api/v1/dashboard` | `docs/features/F02.10_weather_widget.md` | 724 |
| GET | `/api/v1/dashboard` | `docs/features/F02.2_dashboard.md` | 258 |
| GET | `/api/v1/dashboard` | `docs/features/F02.2_dashboard.md` | 294 |

### /api/v1/disclosure-templates/* (1 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| GET | `/api/v1/disclosure-templates` | `docs/features/F09.14_real_estate_disclosure.md` | 274 |

### /api/v1/dwelling-units/* (23 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| DELETE | `/api/v1/dwelling-units/residents/documents/{_}` | `docs/features/F09.1_resident_registry.md` | 326 |
| DELETE | `/api/v1/dwelling-units/{_}` | `docs/features/F09.1_resident_registry.md` | 309 |
| DELETE | `/api/v1/dwelling-units/{_}/residents/{_}` | `docs/features/F09.1_resident_registry.md` | 314 |
| GET | `/api/v1/dwelling-units` | `docs/features/F09.1_resident_registry.md` | 305 |
| GET | `/api/v1/dwelling-units` | `docs/features/F09.1_resident_registry.md` | 375 |
| GET | `/api/v1/dwelling-units/export` | `docs/features/F09.1_resident_registry.md` | 322 |
| GET | `/api/v1/dwelling-units/export` | `docs/features/F09.1_resident_registry.md` | 891 |
| GET | `/api/v1/dwelling-units/my` | `docs/features/F09.1_resident_registry.md` | 321 |
| GET | `/api/v1/dwelling-units/my` | `docs/features/F09.1_resident_registry.md` | 627 |
| GET | `/api/v1/dwelling-units/residents/search` | `docs/features/F09.1_resident_registry.md` | 323 |
| GET | `/api/v1/dwelling-units/residents/search` | `docs/features/F09.1_resident_registry.md` | 917 |
| GET | `/api/v1/dwelling-units/stats` | `docs/features/F09.1_resident_registry.md` | 336 |
| GET | `/api/v1/dwelling-units/stats` | `docs/features/F09.1_resident_registry.md` | 808 |
| GET | `/api/v1/dwelling-units/{_}` | `docs/features/F09.1_resident_registry.md` | 307 |
| GET | `/api/v1/dwelling-units/{_}` | `docs/features/F09.1_resident_registry.md` | 431 |
| GET | `/api/v1/dwelling-units/{_}/residents` | `docs/features/F09.1_resident_registry.md` | 311 |
| GET | `/api/v1/dwelling-units/{_}/residents` | `docs/features/F09.1_resident_registry.md` | 512 |
| PATCH | `/api/v1/dwelling-units/bulk-privacy` | `docs/features/F09.1_resident_registry.md` | 324 |
| PATCH | `/api/v1/dwelling-units/bulk-privacy` | `docs/features/F09.1_resident_registry.md` | 960 |
| PATCH | `/api/v1/dwelling-units/{_}/move-out-all` | `docs/features/F09.1_resident_registry.md` | 316 |
| PATCH | `/api/v1/dwelling-units/{_}/move-out-all` | `docs/features/F09.1_resident_registry.md` | 1168 |
| PATCH | `/api/v1/dwelling-units/{_}/residents/{_}/move-out` | `docs/features/F09.1_resident_registry.md` | 315 |
| PATCH | `/api/v1/dwelling-units/{_}/residents/{_}/move-out` | `docs/features/F09.1_resident_registry.md` | 576 |
| PATCH | `/api/v1/dwelling-units/{_}/residents/{_}/renew-lease` | `docs/features/F09.1_resident_registry.md` | 318 |
| PATCH | `/api/v1/dwelling-units/{_}/residents/{_}/renew-lease` | `docs/features/F09.1_resident_registry.md` | 1208 |
| PATCH | `/api/v1/dwelling-units/{_}/residents/{_}/verify` | `docs/features/F09.1_resident_registry.md` | 317 |
| PATCH | `/api/v1/dwelling-units/{_}/residents/{_}/verify` | `docs/features/F09.1_resident_registry.md` | 606 |
| POST | `/api/v1/dwelling-units` | `docs/features/F09.1_resident_registry.md` | 306 |
| POST | `/api/v1/dwelling-units` | `docs/features/F09.1_resident_registry.md` | 340 |
| POST | `/api/v1/dwelling-units/import` | `docs/features/F09.1_resident_registry.md` | 310 |
| POST | `/api/v1/dwelling-units/import` | `docs/features/F09.1_resident_registry.md` | 845 |
| POST | `/api/v1/dwelling-units/self-register` | `docs/features/F09.1_resident_registry.md` | 320 |
| POST | `/api/v1/dwelling-units/self-register` | `docs/features/F09.1_resident_registry.md` | 1133 |
| POST | `/api/v1/dwelling-units/{_}/invite` | `docs/features/F09.1_resident_registry.md` | 319 |
| POST | `/api/v1/dwelling-units/{_}/invite` | `docs/features/F09.1_resident_registry.md` | 1094 |
| POST | `/api/v1/dwelling-units/{_}/residents` | `docs/features/F09.1_resident_registry.md` | 312 |
| POST | `/api/v1/dwelling-units/{_}/residents` | `docs/features/F09.1_resident_registry.md` | 534 |
| POST | `/api/v1/dwelling-units/{_}/residents/{_}/documents` | `docs/features/F09.1_resident_registry.md` | 325 |
| POST | `/api/v1/dwelling-units/{_}/residents/{_}/documents` | `docs/features/F09.1_resident_registry.md` | 993 |
| PUT | `/api/v1/dwelling-units/{_}` | `docs/features/F09.1_resident_registry.md` | 308 |
| PUT | `/api/v1/dwelling-units/{_}/residents/{_}` | `docs/features/F09.1_resident_registry.md` | 313 |

### /api/v1/events/* (18 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| DELETE | `/api/v1/events/{_}/invite-tokens/{_}` | `docs/features/F03.8_event_management.md` | 455 |
| DELETE | `/api/v1/events/{_}/ticket-types/{_}` | `docs/features/F03.8_event_management.md` | 411 |
| GET | `/api/v1/events/{_}/checkins/live` | `docs/features/F03.8_event_management.md` | 439 |
| GET | `/api/v1/events/{_}/checkins/live` | `docs/features/F03.8_event_management.md` | 699 |
| GET | `/api/v1/events/{_}/invite-tokens` | `docs/features/F03.8_event_management.md` | 454 |
| GET | `/api/v1/events/{_}/registrations` | `docs/features/F03.8_event_management.md` | 418 |
| GET | `/api/v1/events/{_}/registrations/export` | `docs/features/F03.8_event_management.md` | 423 |
| GET | `/api/v1/events/{_}/registrations/me` | `docs/features/F03.8_event_management.md` | 419 |
| GET | `/api/v1/events/{_}/ticket-types` | `docs/features/F03.8_event_management.md` | 409 |
| GET | `/api/v1/events/{_}/tickets` | `docs/features/F03.8_event_management.md` | 429 |
| GET | `/api/v1/events/{_}/tickets/me` | `docs/features/F03.8_event_management.md` | 428 |
| GET | `/api/v1/events/{_}/timetable` | `docs/features/F03.8_event_management.md` | 445 |
| PATCH | `/api/v1/events/{_}/registrations/{_}/approve` | `docs/features/F03.8_event_management.md` | 420 |
| PATCH | `/api/v1/events/{_}/registrations/{_}/reject` | `docs/features/F03.8_event_management.md` | 421 |
| POST | `/api/v1/events/{_}/invite-tokens` | `docs/features/F03.8_event_management.md` | 453 |
| POST | `/api/v1/events/{_}/registrations` | `docs/features/F03.8_event_management.md` | 416 |
| POST | `/api/v1/events/{_}/registrations` | `docs/features/F03.8_event_management.md` | 555 |
| POST | `/api/v1/events/{_}/ticket-types` | `docs/features/F03.8_event_management.md` | 408 |
| POST | `/api/v1/events/{_}/timetable` | `docs/features/F03.8_event_management.md` | 444 |
| PUT | `/api/v1/events/{_}/timetable/order` | `docs/features/F03.8_event_management.md` | 448 |

### /api/v1/families/* (1 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| GET | `/api/v1/families/{_}/members/{_}/personal-timetables` | `docs/features/F03.15_personal_timetable.md` | 395 |

### /api/v1/feature-flags/* (1 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| GET | `/api/v1/feature-flags/me` | `docs/features/F12.2_feature_flag.md` | 28 |
| GET | `/api/v1/feature-flags/me` | `docs/features/F12.2_feature_flag.md` | 120 |
| GET | `/api/v1/feature-flags/me` | `docs/features/F12.2_feature_flag.md` | 151 |

### /api/v1/feedback/* (4 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| DELETE | `/api/v1/feedback/{_}/vote` | `docs/features/F10.1_admin_dashboard.md` | 563 |
| DELETE | `/api/v1/feedback/{_}/vote` | `docs/features/F10.1_admin_dashboard.md` | 1037 |
| GET | `/api/v1/feedback` | `docs/features/F10.1_admin_dashboard.md` | 561 |
| POST | `/api/v1/feedback` | `docs/features/F10.1_admin_dashboard.md` | 560 |
| POST | `/api/v1/feedback/{_}/vote` | `docs/features/F10.1_admin_dashboard.md` | 562 |
| POST | `/api/v1/feedback/{_}/vote` | `docs/features/F10.1_admin_dashboard.md` | 1010 |

### /api/v1/files/* (33 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| DELETE | `/api/v1/files/comments/{_}` | `docs/features/F05.5_file_sharing.md` | 353 |
| DELETE | `/api/v1/files/folders/{_}` | `docs/features/F05.5_file_sharing.md` | 322 |
| DELETE | `/api/v1/files/folders/{_}` | `docs/features/F05.5_file_sharing.md` | 504 |
| DELETE | `/api/v1/files/links/{_}` | `docs/features/F05.5_file_sharing.md` | 356 |
| DELETE | `/api/v1/files/{_}/star` | `docs/features/F05.5_file_sharing.md` | 349 |
| GET | `/api/v1/files` | `docs/features/F05.5_file_sharing.md` | 323 |
| GET | `/api/v1/files/folders` | `docs/features/F05.5_file_sharing.md` | 318 |
| GET | `/api/v1/files/folders` | `docs/features/F05.5_file_sharing.md` | 366 |
| GET | `/api/v1/files/folders/{_}` | `docs/features/F05.5_file_sharing.md` | 320 |
| GET | `/api/v1/files/folders/{_}` | `docs/features/F05.5_file_sharing.md` | 414 |
| GET | `/api/v1/files/folders/{_}/permissions` | `docs/features/F05.5_file_sharing.md` | 341 |
| GET | `/api/v1/files/recent` | `docs/features/F05.5_file_sharing.md` | 343 |
| GET | `/api/v1/files/recent` | `docs/features/F05.5_file_sharing.md` | 640 |
| GET | `/api/v1/files/search` | `docs/features/F05.5_file_sharing.md` | 342 |
| GET | `/api/v1/files/search` | `docs/features/F05.5_file_sharing.md` | 959 |
| GET | `/api/v1/files/starred` | `docs/features/F05.5_file_sharing.md` | 350 |
| GET | `/api/v1/files/tags/suggest` | `docs/features/F05.5_file_sharing.md` | 360 |
| GET | `/api/v1/files/{_}/comments` | `docs/features/F05.5_file_sharing.md` | 351 |
| GET | `/api/v1/files/{_}/download-url` | `docs/features/F05.5_file_sharing.md` | 333 |
| GET | `/api/v1/files/{_}/download-url` | `docs/features/F05.5_file_sharing.md` | 871 |
| GET | `/api/v1/files/{_}/permissions` | `docs/features/F05.5_file_sharing.md` | 339 |
| GET | `/api/v1/files/{_}/versions` | `docs/features/F05.5_file_sharing.md` | 335 |
| GET | `/api/v1/files/{_}/versions/{_}/download-url` | `docs/features/F05.5_file_sharing.md` | 336 |
| POST | `/api/v1/files` | `docs/features/F05.5_file_sharing.md` | 329 |
| POST | `/api/v1/files` | `docs/features/F05.5_file_sharing.md` | 814 |
| POST | `/api/v1/files` | `docs/features/F05.5_file_sharing.md` | 836 |
| POST | `/api/v1/files/bulk-delete` | `docs/features/F05.5_file_sharing.md` | 347 |
| POST | `/api/v1/files/bulk-delete` | `docs/features/F05.5_file_sharing.md` | 1110 |
| POST | `/api/v1/files/bulk-move` | `docs/features/F05.5_file_sharing.md` | 346 |
| POST | `/api/v1/files/bulk-move` | `docs/features/F05.5_file_sharing.md` | 1071 |
| POST | `/api/v1/files/folders` | `docs/features/F05.5_file_sharing.md` | 319 |
| POST | `/api/v1/files/folders` | `docs/features/F05.5_file_sharing.md` | 386 |
| POST | `/api/v1/files/folders/{_}/restore` | `docs/features/F05.5_file_sharing.md` | 345 |
| POST | `/api/v1/files/folders/{_}/restore` | `docs/features/F05.5_file_sharing.md` | 1167 |
| POST | `/api/v1/files/upload-url` | `docs/features/F05.5_file_sharing.md` | 324 |
| POST | `/api/v1/files/upload-url` | `docs/features/F05.5_file_sharing.md` | 662 |
| POST | `/api/v1/files/{_}/comments` | `docs/features/F05.5_file_sharing.md` | 352 |
| POST | `/api/v1/files/{_}/restore` | `docs/features/F05.5_file_sharing.md` | 344 |
| POST | `/api/v1/files/{_}/restore` | `docs/features/F05.5_file_sharing.md` | 1150 |
| POST | `/api/v1/files/{_}/star` | `docs/features/F05.5_file_sharing.md` | 348 |
| POST | `/api/v1/files/{_}/tags` | `docs/features/F05.5_file_sharing.md` | 358 |
| POST | `/api/v1/files/{_}/versions` | `docs/features/F05.5_file_sharing.md` | 334 |
| POST | `/api/v1/files/{_}/versions` | `docs/features/F05.5_file_sharing.md` | 898 |
| POST | `/api/v1/files/{_}/versions/{_}/restore` | `docs/features/F05.5_file_sharing.md` | 337 |
| POST | `/api/v1/files/{_}/versions/{_}/restore` | `docs/features/F05.5_file_sharing.md` | 933 |
| PUT | `/api/v1/files/folders/{_}` | `docs/features/F05.5_file_sharing.md` | 321 |
| PUT | `/api/v1/files/folders/{_}` | `docs/features/F05.5_file_sharing.md` | 473 |
| PUT | `/api/v1/files/folders/{_}/permissions` | `docs/features/F05.5_file_sharing.md` | 340 |
| PUT | `/api/v1/files/{_}` | `docs/features/F05.5_file_sharing.md` | 331 |
| PUT | `/api/v1/files/{_}` | `docs/features/F05.5_file_sharing.md` | 580 |
| PUT | `/api/v1/files/{_}/permissions` | `docs/features/F05.5_file_sharing.md` | 338 |
| PUT | `/api/v1/files/{_}/permissions` | `docs/features/F05.5_file_sharing.md` | 989 |

### /api/v1/follows/* (5 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| DELETE | `/api/v1/follows/{_}/{_}` | `docs/features/F04.4_social_profiles.md` | 99 |
| DELETE | `/api/v1/follows/{_}/{_}` | `docs/features/F04.4_social_profiles.md` | 373 |
| GET | `/api/v1/follows/check` | `docs/features/F01.5_team_friend_relationships.md` | 1382 |
| GET | `/api/v1/follows/check` | `docs/features/F04.4_social_profiles.md` | 102 |
| GET | `/api/v1/follows/check` | `docs/features/F04.4_social_profiles.md` | 461 |
| GET | `/api/v1/follows/followers` | `docs/features/F04.4_social_profiles.md` | 101 |
| GET | `/api/v1/follows/followers` | `docs/features/F04.4_social_profiles.md` | 439 |
| GET | `/api/v1/follows/following` | `docs/features/F04.4_social_profiles.md` | 100 |
| GET | `/api/v1/follows/following` | `docs/features/F04.4_social_profiles.md` | 395 |
| GET | `/api/v1/follows/following` | `docs/features/F04.4_social_profiles.md` | 501 |
| POST | `/api/v1/follows` | `docs/features/F01.5_team_friend_relationships.md` | 1381 |
| POST | `/api/v1/follows` | `docs/features/F04.4_social_profiles.md` | 98 |
| POST | `/api/v1/follows` | `docs/features/F04.4_social_profiles.md` | 320 |

### /api/v1/forms/* (13 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| DELETE | `/api/v1/forms/submissions/{_}` | `docs/features/F05.7_form_builder.md` | 386 |
| GET | `/api/v1/forms/presets` | `docs/features/F05.7_form_builder.md` | 355 |
| GET | `/api/v1/forms/submissions/me` | `docs/features/F05.7_form_builder.md` | 381 |
| GET | `/api/v1/forms/submissions/{_}` | `docs/features/F05.7_form_builder.md` | 383 |
| GET | `/api/v1/forms/submissions/{_}/pdf/download-url` | `docs/features/F05.7_form_builder.md` | 392 |
| GET | `/api/v1/forms/submissions/{_}/pdf/download-url` | `docs/features/F05.7_form_builder.md` | 640 |
| GET | `/api/v1/forms/templates/{_}/submissions` | `docs/features/F05.7_form_builder.md` | 380 |
| GET | `/api/v1/forms/templates/{_}/submissions` | `docs/features/F05.7_form_builder.md` | 685 |
| GET | `/api/v1/forms/templates/{_}/submissions/export` | `docs/features/F05.7_form_builder.md` | 393 |
| GET | `/api/v1/forms/templates/{_}/submissions/export` | `docs/features/F05.7_form_builder.md` | 662 |
| PATCH | `/api/v1/forms/submissions/{_}` | `docs/features/F05.7_form_builder.md` | 384 |
| POST | `/api/v1/forms/submissions/{_}/pdf` | `docs/features/F05.7_form_builder.md` | 391 |
| POST | `/api/v1/forms/submissions/{_}/pdf` | `docs/features/F05.7_form_builder.md` | 614 |
| POST | `/api/v1/forms/submissions/{_}/submit` | `docs/features/F05.7_form_builder.md` | 385 |
| POST | `/api/v1/forms/submissions/{_}/submit` | `docs/features/F05.7_form_builder.md` | 583 |
| POST | `/api/v1/forms/submissions/{_}/upload-url` | `docs/features/F05.7_form_builder.md` | 398 |
| POST | `/api/v1/forms/submissions/{_}/upload-url` | `docs/features/F05.7_form_builder.md` | 753 |
| POST | `/api/v1/forms/templates/{_}/remind` | `docs/features/F05.7_form_builder.md` | 727 |
| POST | `/api/v1/forms/templates/{_}/submissions` | `docs/features/F05.7_form_builder.md` | 382 |
| POST | `/api/v1/forms/templates/{_}/submissions` | `docs/features/F05.7_form_builder.md` | 523 |

### /api/v1/gallery/* (2 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| GET | `/api/v1/gallery/albums` | `docs/features/F06.2_member_gallery.md` | 350 |
| GET | `/api/v1/gallery/albums` | `docs/features/F06.2_member_gallery.md` | 921 |
| POST | `/api/v1/gallery/albums` | `docs/features/F06.2_member_gallery.md` | 351 |
| POST | `/api/v1/gallery/albums` | `docs/features/F06.2_member_gallery.md` | 603 |

### /api/v1/incidents/* (25 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| DELETE | `/api/v1/incidents/categories/{_}` | `docs/features/F07.6_incident_management.md` | 440 |
| DELETE | `/api/v1/incidents/maintenance-schedules/{_}` | `docs/features/F07.6_incident_management.md` | 499 |
| DELETE | `/api/v1/incidents/{_}` | `docs/features/F07.6_incident_management.md` | 453 |
| DELETE | `/api/v1/incidents/{_}/assignments/{_}` | `docs/features/F07.6_incident_management.md` | 469 |
| DELETE | `/api/v1/incidents/{_}/comments/{_}` | `docs/features/F07.6_incident_management.md` | 476 |
| GET | `/api/v1/incidents/assigned` | `docs/features/F07.6_incident_management.md` | 448 |
| GET | `/api/v1/incidents/maintenance-schedules/{_}` | `docs/features/F07.6_incident_management.md` | 497 |
| GET | `/api/v1/incidents/me` | `docs/features/F07.6_incident_management.md` | 447 |
| GET | `/api/v1/incidents/{_}` | `docs/features/F07.6_incident_management.md` | 451 |
| GET | `/api/v1/incidents/{_}/comments` | `docs/features/F07.6_incident_management.md` | 474 |
| GET | `/api/v1/incidents/{_}/expense-status` | `docs/features/F07.6_incident_management.md` | 488 |
| PATCH | `/api/v1/incidents/{_}` | `docs/features/F07.6_incident_management.md` | 452 |
| POST | `/api/v1/incidents/{_}/acknowledge` | `docs/features/F07.6_incident_management.md` | 458 |
| POST | `/api/v1/incidents/{_}/assignments` | `docs/features/F07.6_incident_management.md` | 468 |
| POST | `/api/v1/incidents/{_}/assignments` | `docs/features/F07.6_incident_management.md` | 691 |
| POST | `/api/v1/incidents/{_}/close` | `docs/features/F07.6_incident_management.md` | 463 |
| POST | `/api/v1/incidents/{_}/comments` | `docs/features/F07.6_incident_management.md` | 475 |
| POST | `/api/v1/incidents/{_}/comments` | `docs/features/F07.6_incident_management.md` | 964 |
| POST | `/api/v1/incidents/{_}/comments/{_}/upload-url` | `docs/features/F07.6_incident_management.md` | 482 |
| POST | `/api/v1/incidents/{_}/confirm` | `docs/features/F07.6_incident_management.md` | 461 |
| POST | `/api/v1/incidents/{_}/expense-request` | `docs/features/F07.6_incident_management.md` | 487 |
| POST | `/api/v1/incidents/{_}/expense-request` | `docs/features/F07.6_incident_management.md` | 746 |
| POST | `/api/v1/incidents/{_}/reopen` | `docs/features/F07.6_incident_management.md` | 462 |
| POST | `/api/v1/incidents/{_}/reopen` | `docs/features/F07.6_incident_management.md` | 659 |
| POST | `/api/v1/incidents/{_}/resolve` | `docs/features/F07.6_incident_management.md` | 460 |
| POST | `/api/v1/incidents/{_}/resolve` | `docs/features/F07.6_incident_management.md` | 623 |
| POST | `/api/v1/incidents/{_}/start` | `docs/features/F07.6_incident_management.md` | 459 |
| POST | `/api/v1/incidents/{_}/upload-url` | `docs/features/F07.6_incident_management.md` | 481 |
| PUT | `/api/v1/incidents/categories/{_}` | `docs/features/F07.6_incident_management.md` | 439 |
| PUT | `/api/v1/incidents/maintenance-schedules/{_}` | `docs/features/F07.6_incident_management.md` | 498 |

### /api/v1/job-contracts/* (17 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| GET | `/api/v1/job-contracts` | `docs/features/F13.1_short_term_job_matching.md` | 1628 |
| GET | `/api/v1/job-contracts/{_}` | `docs/features/F13.1_short_term_job_matching.md` | 1629 |
| GET | `/api/v1/job-contracts/{_}/qr-tokens/current` | `docs/features/F13.1_short_term_job_matching.md` | 1632 |
| GET | `/api/v1/job-contracts/{_}/reviews` | `docs/features/F13.1_short_term_job_matching.md` | 1639 |
| GET | `/api/v1/job-contracts/{_}/time-confirmations` | `docs/features/F13.1_short_term_job_matching.md` | 1665 |
| POST | `/api/v1/job-contracts/{_}/admin-override-checkin` | `docs/features/F13.1_short_term_job_matching.md` | 3554 |
| POST | `/api/v1/job-contracts/{_}/approve` | `docs/features/F13.1_short_term_job_matching.md` | 1635 |
| POST | `/api/v1/job-contracts/{_}/cancel` | `docs/features/F13.1_short_term_job_matching.md` | 1637 |
| POST | `/api/v1/job-contracts/{_}/disputes` | `docs/features/F13.1_short_term_job_matching.md` | 1644 |
| POST | `/api/v1/job-contracts/{_}/qr-tokens` | `docs/features/F13.1_short_term_job_matching.md` | 1631 |
| POST | `/api/v1/job-contracts/{_}/qr-tokens` | `docs/features/F13.1_short_term_job_matching.md` | 1744 |
| POST | `/api/v1/job-contracts/{_}/reject-completion` | `docs/features/F13.1_short_term_job_matching.md` | 1636 |
| POST | `/api/v1/job-contracts/{_}/report-completion` | `docs/features/F13.1_short_term_job_matching.md` | 1634 |
| POST | `/api/v1/job-contracts/{_}/reviews` | `docs/features/F13.1_short_term_job_matching.md` | 1638 |
| POST | `/api/v1/job-contracts/{_}/start` | `docs/features/F13.1_short_term_job_matching.md` | 1630 |
| POST | `/api/v1/job-contracts/{_}/time-confirmations` | `docs/features/F13.1_short_term_job_matching.md` | 640 |
| POST | `/api/v1/job-contracts/{_}/time-confirmations` | `docs/features/F13.1_short_term_job_matching.md` | 1664 |
| POST | `/api/v1/job-contracts/{_}/time-confirmations` | `docs/features/F13.1_short_term_job_matching.md` | 1982 |
| POST | `/api/v1/job-contracts/{_}/time-confirmations/{_}/approve` | `docs/features/F13.1_short_term_job_matching.md` | 1666 |
| POST | `/api/v1/job-contracts/{_}/time-confirmations/{_}/approve` | `docs/features/F13.1_short_term_job_matching.md` | 2016 |
| POST | `/api/v1/job-contracts/{_}/time-confirmations/{_}/dispute` | `docs/features/F13.1_short_term_job_matching.md` | 1667 |

### /api/v1/job-disputes/* (1 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| POST | `/api/v1/job-disputes/{_}/resolve` | `docs/features/F13.1_short_term_job_matching.md` | 1645 |

### /api/v1/job-payments/* (3 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| GET | `/api/v1/job-payments/{_}/escrow-status` | `docs/features/F13.1_short_term_job_matching.md` | 1670 |
| POST | `/api/v1/job-payments/{_}/dispute` | `docs/features/F13.1_short_term_job_matching.md` | 355 |
| POST | `/api/v1/job-payments/{_}/dispute` | `docs/features/F13.1_short_term_job_matching.md` | 1669 |
| POST | `/api/v1/job-payments/{_}/dispute` | `docs/features/F13.1_short_term_job_matching.md` | 2067 |
| POST | `/api/v1/job-payments/{_}/early-release` | `docs/features/F13.1_short_term_job_matching.md` | 345 |
| POST | `/api/v1/job-payments/{_}/early-release` | `docs/features/F13.1_short_term_job_matching.md` | 1668 |
| POST | `/api/v1/job-payments/{_}/early-release` | `docs/features/F13.1_short_term_job_matching.md` | 2030 |

### /api/v1/jobber-invitations/* (2 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| POST | `/api/v1/jobber-invitations/{_}/accept` | `docs/features/F13.1_short_term_job_matching.md` | 1657 |
| POST | `/api/v1/jobber-invitations/{_}/accept` | `docs/features/F13.1_short_term_job_matching.md` | 1924 |
| POST | `/api/v1/jobber-invitations/{_}/decline` | `docs/features/F13.1_short_term_job_matching.md` | 1658 |

### /api/v1/jobs/* (9 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| DELETE | `/api/v1/jobs/{_}/applications/me` | `docs/features/F13.1_short_term_job_matching.md` | 1624 |
| GET | `/api/v1/jobs` | `docs/features/F13.1_short_term_job_matching.md` | 1615 |
| GET | `/api/v1/jobs/public-board` | `docs/features/F13.1_short_term_job_matching.md` | 1663 |
| GET | `/api/v1/jobs/public-board` | `docs/features/F13.1_short_term_job_matching.md` | 1940 |
| POST | `/api/v1/jobs` | `docs/features/F13.1_short_term_job_matching.md` | 1617 |
| POST | `/api/v1/jobs` | `docs/features/F13.1_short_term_job_matching.md` | 2547 |
| POST | `/api/v1/jobs/check-ins` | `docs/features/F13.1_short_term_job_matching.md` | 116 |
| POST | `/api/v1/jobs/check-ins` | `docs/features/F13.1_short_term_job_matching.md` | 1633 |
| POST | `/api/v1/jobs/check-ins` | `docs/features/F13.1_short_term_job_matching.md` | 1766 |
| POST | `/api/v1/jobs/check-ins` | `docs/features/F13.1_short_term_job_matching.md` | 2487 |
| POST | `/api/v1/jobs/fee-preview` | `docs/features/F13.1_short_term_job_matching.md` | 595 |
| POST | `/api/v1/jobs/fee-preview` | `docs/features/F13.1_short_term_job_matching.md` | 1622 |
| POST | `/api/v1/jobs/fee-preview` | `docs/features/F13.1_short_term_job_matching.md` | 1676 |
| POST | `/api/v1/jobs/{_}/applications` | `docs/features/F13.1_short_term_job_matching.md` | 1623 |
| POST | `/api/v1/jobs/{_}/applications` | `docs/features/F13.1_short_term_job_matching.md` | 2546 |
| POST | `/api/v1/jobs/{_}/applications/{_}/accept` | `docs/features/F13.1_short_term_job_matching.md` | 1626 |
| POST | `/api/v1/jobs/{_}/applications/{_}/accept` | `docs/features/F13.1_short_term_job_matching.md` | 1707 |
| POST | `/api/v1/jobs/{_}/applications/{_}/reject` | `docs/features/F13.1_short_term_job_matching.md` | 1627 |

### /api/v1/kb/* (1 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| GET | `/api/v1/kb/pages/{_}` | `docs/features/F11.2_multilingual_content.md` | 533 |

### /api/v1/line/* (11 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| DELETE | `/api/v1/line/configs/{_}` | `docs/features/F09.4_line_sns.md` | 225 |
| DELETE | `/api/v1/line/configs/{_}` | `docs/features/F09.4_line_sns.md` | 371 |
| DELETE | `/api/v1/line/link/{_}` | `docs/features/F09.4_line_sns.md` | 228 |
| DELETE | `/api/v1/line/link/{_}` | `docs/features/F09.4_line_sns.md` | 459 |
| GET | `/api/v1/line/configs` | `docs/features/F09.4_line_sns.md` | 222 |
| GET | `/api/v1/line/configs` | `docs/features/F09.4_line_sns.md` | 327 |
| GET | `/api/v1/line/configs/{_}/logs` | `docs/features/F09.4_line_sns.md` | 230 |
| GET | `/api/v1/line/configs/{_}/logs` | `docs/features/F09.4_line_sns.md` | 504 |
| GET | `/api/v1/line/configs/{_}/stats` | `docs/features/F09.4_line_sns.md` | 231 |
| GET | `/api/v1/line/configs/{_}/stats` | `docs/features/F09.4_line_sns.md` | 543 |
| GET | `/api/v1/line/link/status` | `docs/features/F09.4_line_sns.md` | 229 |
| GET | `/api/v1/line/link/status` | `docs/features/F09.4_line_sns.md` | 479 |
| POST | `/api/v1/line/configs` | `docs/features/F09.4_line_sns.md` | 223 |
| POST | `/api/v1/line/configs` | `docs/features/F09.4_line_sns.md` | 280 |
| POST | `/api/v1/line/configs/{_}/broadcast` | `docs/features/F09.4_line_sns.md` | 232 |
| POST | `/api/v1/line/configs/{_}/broadcast` | `docs/features/F09.4_line_sns.md` | 584 |
| POST | `/api/v1/line/configs/{_}/test` | `docs/features/F09.4_line_sns.md` | 226 |
| POST | `/api/v1/line/configs/{_}/test` | `docs/features/F09.4_line_sns.md` | 392 |
| POST | `/api/v1/line/link` | `docs/features/F09.4_line_sns.md` | 227 |
| POST | `/api/v1/line/link` | `docs/features/F09.4_line_sns.md` | 420 |
| PUT | `/api/v1/line/configs/{_}` | `docs/features/F09.4_line_sns.md` | 224 |
| PUT | `/api/v1/line/configs/{_}` | `docs/features/F09.4_line_sns.md` | 343 |

### /api/v1/me/* (36 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| DELETE | `/api/v1/me/ad-deliveries` | `docs/features/F09.17_advertiser_targeted_campaign.md` | 444 |
| DELETE | `/api/v1/me/ad-deliveries` | `docs/features/F09.17_advertiser_targeted_campaign.md` | 684 |
| DELETE | `/api/v1/me/ad-deliveries` | `docs/features/F09.17_advertiser_targeted_campaign.md` | 936 |
| GET | `/api/v1/me/ad-deliveries` | `docs/features/F09.17_advertiser_targeted_campaign.md` | 443 |
| GET | `/api/v1/me/ad-deliveries` | `docs/features/F09.17_advertiser_targeted_campaign.md` | 680 |
| GET | `/api/v1/me/ad-preferences` | `docs/features/F09.17_advertiser_targeted_campaign.md` | 439 |
| GET | `/api/v1/me/ad-preferences` | `docs/features/F09.17_advertiser_targeted_campaign.md` | 632 |
| GET | `/api/v1/me/favorites` | `docs/features/F02.2_dashboard.md` | 212 |
| GET | `/api/v1/me/favorites` | `docs/features/F02.9_favorites_widget.md` | 558 |
| GET | `/api/v1/me/favorites` | `docs/features/F02.9_favorites_widget.md` | 610 |
| GET | `/api/v1/me/jobber-profile` | `docs/features/F13.1_short_term_job_matching.md` | 1661 |
| GET | `/api/v1/me/jobs/history` | `docs/features/F13.1_short_term_job_matching.md` | 1643 |
| GET | `/api/v1/me/jobs/history` | `docs/features/F13.1_short_term_job_matching.md` | 1879 |
| GET | `/api/v1/me/no-show-history` | `docs/features/F03.11_recruitment_listing.md` | 1465 |
| GET | `/api/v1/me/penalties` | `docs/features/F03.11_recruitment_listing.md` | 1475 |
| GET | `/api/v1/me/personal-timetable-settings` | `docs/features/F03.15_personal_timetable.md` | 436 |
| GET | `/api/v1/me/personal-timetables` | `docs/features/F03.15_personal_timetable.md` | 359 |
| GET | `/api/v1/me/personal-timetables/{_}/periods` | `docs/features/F03.15_personal_timetable.md` | 372 |
| GET | `/api/v1/me/personal-timetables/{_}/share-targets` | `docs/features/F03.15_personal_timetable.md` | 388 |
| GET | `/api/v1/me/schedules` | `docs/features/F03.2_schedule_personal.md` | 121 |
| GET | `/api/v1/me/schedules` | `docs/features/F03.2_schedule_personal.md` | 183 |
| GET | `/api/v1/me/scope-folders` | `docs/features/F15.2_team_folder.md` | 172 |
| GET | `/api/v1/me/scope-folders` | `docs/features/F15.3_scope_folder_integration.md` | 160 |
| GET | `/api/v1/me/scope-folders` | `docs/features/F15.3_scope_folder_integration.md` | 279 |
| GET | `/api/v1/me/timetable-slot-note-fields` | `docs/features/F03.15_personal_timetable.md` | 420 |
| GET | `/api/v1/me/timetable-slot-notes` | `docs/features/F03.15_personal_timetable.md` | 412 |
| PATCH | `/api/v1/me/care-category` | `docs/features/F03.12_care_recipient_event_watch_notification.md` | 435 |
| PATCH | `/api/v1/me/favorites/order` | `docs/features/F02.9_favorites_widget.md` | 394 |
| PATCH | `/api/v1/me/favorites/order` | `docs/features/F02.9_favorites_widget.md` | 557 |
| PATCH | `/api/v1/me/profile` | `docs/features/F02.9_favorites_widget.md` | 319 |
| PATCH | `/api/v1/me/profile` | `docs/features/F02.9_favorites_widget.md` | 414 |
| POST | `/api/v1/me/ad-reports` | `docs/features/F09.17_advertiser_targeted_campaign.md` | 442 |
| POST | `/api/v1/me/ad-reports` | `docs/features/F09.17_advertiser_targeted_campaign.md` | 666 |
| POST | `/api/v1/me/care-links/accept` | `docs/features/F03.12_care_recipient_event_watch_notification.md` | 430 |
| POST | `/api/v1/me/care-links/reject` | `docs/features/F03.12_care_recipient_event_watch_notification.md` | 431 |
| POST | `/api/v1/me/care-recipient-account` | `docs/features/F03.12_care_recipient_event_watch_notification.md` | 434 |
| POST | `/api/v1/me/favorites` | `docs/features/F02.9_favorites_widget.md` | 555 |
| POST | `/api/v1/me/personal-timetables` | `docs/features/F03.15_personal_timetable.md` | 360 |
| POST | `/api/v1/me/personal-timetables/{_}/share-targets` | `docs/features/F03.15_personal_timetable.md` | 389 |
| POST | `/api/v1/me/personal-timetables/{_}/slots/import-from-team` | `docs/features/F03.15_personal_timetable.md` | 648 |
| POST | `/api/v1/me/schedules` | `docs/features/F03.2_schedule_personal.md` | 120 |
| POST | `/api/v1/me/schedules` | `docs/features/F03.2_schedule_personal.md` | 131 |
| POST | `/api/v1/me/scope-folders` | `docs/features/F15.2_team_folder.md` | 178 |
| POST | `/api/v1/me/timetable-slot-note-fields` | `docs/features/F03.15_personal_timetable.md` | 421 |
| POST | `/api/v1/me/voice-input-consents` | `docs/features/F02.5_quick_memo.md` | 551 |
| PUT | `/api/v1/me/ad-preferences` | `docs/features/F09.17_advertiser_targeted_campaign.md` | 440 |
| PUT | `/api/v1/me/ad-preferences` | `docs/features/F09.17_advertiser_targeted_campaign.md` | 648 |
| PUT | `/api/v1/me/jobber-profile` | `docs/features/F13.1_short_term_job_matching.md` | 1662 |
| PUT | `/api/v1/me/personal-timetable-settings` | `docs/features/F03.15_personal_timetable.md` | 437 |
| PUT | `/api/v1/me/personal-timetables/{_}/periods` | `docs/features/F03.15_personal_timetable.md` | 373 |
| PUT | `/api/v1/me/timetable-slot-notes` | `docs/features/F03.15_personal_timetable.md` | 413 |
| PUT | `/api/v1/me/timetable-slot-notes` | `docs/features/F03.15_personal_timetable.md` | 514 |

### /api/v1/member-positions/* (1 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| POST | `/api/v1/member-positions/{_}/end` | `docs/features/F00.5_membership_basis.md` | 816 |
| POST | `/api/v1/member-positions/{_}/end` | `docs/features/F00.5_membership_basis.md` | 820 |

### /api/v1/memberships/* (3 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| POST | `/api/v1/memberships` | `docs/features/F00.5_membership_basis.md` | 755 |
| POST | `/api/v1/memberships` | `docs/features/F00.5_membership_basis.md` | 802 |
| POST | `/api/v1/memberships` | `docs/features/F00.5_membership_basis.md` | 1003 |
| POST | `/api/v1/memberships` | `docs/features/F00.5_membership_basis.md` | 1010 |
| POST | `/api/v1/memberships/{_}/leave` | `docs/features/F00.5_membership_basis.md` | 773 |
| POST | `/api/v1/memberships/{_}/leave` | `docs/features/F00.5_membership_basis.md` | 785 |
| POST | `/api/v1/memberships/{_}/leave` | `docs/features/F00.5_membership_basis.md` | 928 |
| POST | `/api/v1/memberships/{_}/positions` | `docs/features/F00.5_membership_basis.md` | 812 |

### /api/v1/modules/* (1 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| GET | `/api/v1/modules` | `docs/features/F01.3_template_module.md` | 371 |
| GET | `/api/v1/modules` | `docs/features/F01.3_template_module.md` | 503 |

### /api/v1/mutes/* (3 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| DELETE | `/api/v1/mutes/{_}/{_}` | `docs/features/F04.1_timeline.md` | 516 |
| DELETE | `/api/v1/mutes/{_}/{_}` | `docs/features/F04.1_timeline.md` | 1281 |
| GET | `/api/v1/mutes` | `docs/features/F04.1_timeline.md` | 517 |
| GET | `/api/v1/mutes` | `docs/features/F04.1_timeline.md` | 1296 |
| POST | `/api/v1/mutes` | `docs/features/F04.1_timeline.md` | 515 |
| POST | `/api/v1/mutes` | `docs/features/F04.1_timeline.md` | 1246 |

### /api/v1/my/* (1 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| GET | `/api/v1/my/receipts` | `docs/features/F08.4_receipt.md` | 336 |
| GET | `/api/v1/my/receipts` | `docs/features/F08.4_receipt.md` | 933 |

### /api/v1/no-show-records/* (2 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| POST | `/api/v1/no-show-records/{_}/dispute` | `docs/features/F03.11_recruitment_listing.md` | 1463 |
| POST | `/api/v1/no-show-records/{_}/dispute/resolve` | `docs/features/F03.11_recruitment_listing.md` | 1464 |

### /api/v1/notification-preferences/* (2 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| PUT | `/api/v1/notification-preferences/organizations/{_}` | `docs/features/F04.3_push_notification.md` | 211 |
| PUT | `/api/v1/notification-preferences/organizations/{_}` | `docs/features/F04.3_push_notification.md` | 510 |
| PUT | `/api/v1/notification-preferences/teams/{_}` | `docs/features/F04.3_push_notification.md` | 210 |
| PUT | `/api/v1/notification-preferences/teams/{_}` | `docs/features/F04.3_push_notification.md` | 477 |

### /api/v1/notifications/* (5 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| GET | `/api/v1/notifications` | `docs/features/F04.3_push_notification.md` | 203 |
| GET | `/api/v1/notifications` | `docs/features/F04.3_push_notification.md` | 221 |
| GET | `/api/v1/notifications` | `docs/features/F15.3_scope_folder_integration.md` | 199 |
| GET | `/api/v1/notifications` | `docs/features/F15.3_scope_folder_integration.md` | 253 |
| GET | `/api/v1/notifications` | `docs/features/F15.3_scope_folder_integration.md` | 283 |
| GET | `/api/v1/notifications` | `docs/features/F15.3_scope_folder_integration.md` | 623 |
| PATCH | `/api/v1/notifications/read-all` | `docs/features/F04.3_push_notification.md` | 208 |
| PATCH | `/api/v1/notifications/read-all` | `docs/features/F04.3_push_notification.md` | 414 |
| PATCH | `/api/v1/notifications/{_}/read` | `docs/features/F04.3_push_notification.md` | 205 |
| PATCH | `/api/v1/notifications/{_}/read` | `docs/features/F04.3_push_notification.md` | 307 |
| PATCH | `/api/v1/notifications/{_}/snooze` | `docs/features/F04.3_push_notification.md` | 207 |
| PATCH | `/api/v1/notifications/{_}/snooze` | `docs/features/F04.3_push_notification.md` | 367 |
| PATCH | `/api/v1/notifications/{_}/unread` | `docs/features/F04.3_push_notification.md` | 206 |
| PATCH | `/api/v1/notifications/{_}/unread` | `docs/features/F04.3_push_notification.md` | 336 |

### /api/v1/onboarding/* (2 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| GET | `/api/v1/onboarding/presets` | `docs/features/F02.4_onboarding.md` | 345 |
| GET | `/api/v1/onboarding/progresses/me` | `docs/features/F02.4_onboarding.md` | 376 |
| GET | `/api/v1/onboarding/progresses/me` | `docs/features/F02.4_onboarding.md` | 566 |

### /api/v1/organizations/* (86 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| DELETE | `/api/v1/organizations/{_}/signage/screens/{_}` | `docs/features/F09.10_digital_signage.md` | 304 |
| DELETE | `/api/v1/organizations/{_}/signage/screens/{_}/emergency` | `docs/features/F09.10_digital_signage.md` | 351 |
| DELETE | `/api/v1/organizations/{_}/signage/screens/{_}/schedules/{_}` | `docs/features/F09.10_digital_signage.md` | 330 |
| DELETE | `/api/v1/organizations/{_}/signage/screens/{_}/slots/{_}` | `docs/features/F09.10_digital_signage.md` | 317 |
| DELETE | `/api/v1/organizations/{_}/signage/screens/{_}/tokens/{_}` | `docs/features/F09.10_digital_signage.md` | 342 |
| DELETE | `/api/v1/organizations/{_}/team-invites/{_}` | `docs/features/F01.2_org_team_member_role.md` | 729 |
| DELETE | `/api/v1/organizations/{_}/teams/{_}` | `docs/features/F01.2_org_team_member_role.md` | 730 |
| GET | `/api/v1/organizations/{_}/access-requirements` | `docs/features/F08.2_payments_access_control.md` | 361 |
| GET | `/api/v1/organizations/{_}/announcements` | `docs/features/F02.2_dashboard.md` | 235 |
| GET | `/api/v1/organizations/{_}/budget/config` | `docs/features/F08.6_budget_accounting.md` | 414 |
| GET | `/api/v1/organizations/{_}/budget/fiscal-years` | `docs/features/F08.6_budget_accounting.md` | 349 |
| GET | `/api/v1/organizations/{_}/budget/transactions` | `docs/features/F08.6_budget_accounting.md` | 377 |
| GET | `/api/v1/organizations/{_}/content-payment-gates` | `docs/features/F08.2_payments_access_control.md` | 365 |
| GET | `/api/v1/organizations/{_}/corkboards` | `docs/features/F09.8_corkboard.md` | 236 |
| GET | `/api/v1/organizations/{_}/disclosure-drafts` | `docs/features/F09.14_real_estate_disclosure.md` | 284 |
| GET | `/api/v1/organizations/{_}/equipment` | `docs/features/F07.3_equipment.md` | 150 |
| GET | `/api/v1/organizations/{_}/event-categories` | `docs/features/F03.10_annual_event_plan.md` | 207 |
| GET | `/api/v1/organizations/{_}/events` | `docs/features/F03.8_event_management.md` | 396 |
| GET | `/api/v1/organizations/{_}/events/{_}/care-participants` | `docs/features/F03.12_care_recipient_event_watch_notification.md` | 465 |
| GET | `/api/v1/organizations/{_}/events/{_}/stats` | `docs/features/F03.8_event_management.md` | 403 |
| GET | `/api/v1/organizations/{_}/form-templates` | `docs/features/F05.7_form_builder.md` | 366 |
| GET | `/api/v1/organizations/{_}/gamification/config` | `docs/features/F04.7_gamification.md` | 370 |
| GET | `/api/v1/organizations/{_}/incidents` | `docs/features/F07.6_incident_management.md` | 446 |
| GET | `/api/v1/organizations/{_}/incidents/categories` | `docs/features/F07.6_incident_management.md` | 436 |
| GET | `/api/v1/organizations/{_}/incidents/maintenance-schedules` | `docs/features/F07.6_incident_management.md` | 494 |
| GET | `/api/v1/organizations/{_}/incidents/stats` | `docs/features/F07.6_incident_management.md` | 505 |
| GET | `/api/v1/organizations/{_}/invite-tokens/{_}/pdf` | `docs/features/F01.8_team_invite_qr_pdf.md` | 580 |
| GET | `/api/v1/organizations/{_}/modules` | `docs/features/F16.1_organization_sidebar_navigation.md` | 56 |
| GET | `/api/v1/organizations/{_}/onboarding/templates` | `docs/features/F02.4_onboarding.md` | 351 |
| GET | `/api/v1/organizations/{_}/payment-items` | `docs/features/F08.2_payments_access_control.md` | 349 |
| GET | `/api/v1/organizations/{_}/schedules` | `docs/features/F03.1_schedule_shared.md` | 437 |
| GET | `/api/v1/organizations/{_}/schedules` | `docs/features/F03.1_schedule_shared.md` | 818 |
| GET | `/api/v1/organizations/{_}/schedules/annual` | `docs/features/F03.10_annual_event_plan.md` | 214 |
| GET | `/api/v1/organizations/{_}/signage/screens` | `docs/features/F09.10_digital_signage.md` | 300 |
| GET | `/api/v1/organizations/{_}/signage/screens/{_}` | `docs/features/F09.10_digital_signage.md` | 302 |
| GET | `/api/v1/organizations/{_}/signage/screens/{_}/emergency/history` | `docs/features/F09.10_digital_signage.md` | 352 |
| GET | `/api/v1/organizations/{_}/signage/screens/{_}/schedules` | `docs/features/F09.10_digital_signage.md` | 327 |
| GET | `/api/v1/organizations/{_}/signage/screens/{_}/slots` | `docs/features/F09.10_digital_signage.md` | 314 |
| GET | `/api/v1/organizations/{_}/signage/screens/{_}/tokens` | `docs/features/F09.10_digital_signage.md` | 339 |
| GET | `/api/v1/organizations/{_}/stats` | `docs/features/F02.2.1_dashboard_widget_role_visibility.md` | 572 |
| GET | `/api/v1/organizations/{_}/storage` | `docs/features/F05.5_file_sharing.md` | 362 |
| GET | `/api/v1/organizations/{_}/storage` | `docs/features/F05.5_file_sharing.md` | 1058 |
| GET | `/api/v1/organizations/{_}/team-invites` | `docs/features/F01.2_org_team_member_role.md` | 728 |
| GET | `/api/v1/organizations/{_}/timetable-periods` | `docs/features/F03.9_timetable.md` | 288 |
| GET | `/api/v1/organizations/{_}/timetable-terms` | `docs/features/F03.9_timetable.md` | 294 |
| GET | `/api/v1/organizations/{_}/todo-status-labels` | `docs/features/F02.3.1_todo_status_labels_and_handoff.md` | 212 |
| GET | `/api/v1/organizations/{_}/todos` | `docs/features/F02.2.1_dashboard_widget_role_visibility.md` | 571 |
| GET | `/api/v1/organizations/{_}/workflow-requests` | `docs/features/F05.6_workflow_approval.md` | 384 |
| GET | `/api/v1/organizations/{_}/workflow-templates` | `docs/features/F05.6_workflow_approval.md` | 371 |
| PATCH | `/api/v1/organizations/{_}/budget/config` | `docs/features/F08.6_budget_accounting.md` | 416 |
| POST | `/api/v1/organizations` | `docs/features/F01.2_org_team_member_role.md` | 669 |
| POST | `/api/v1/organizations/{_}/budget/fiscal-years` | `docs/features/F08.6_budget_accounting.md` | 351 |
| POST | `/api/v1/organizations/{_}/corkboards` | `docs/features/F09.8_corkboard.md` | 239 |
| POST | `/api/v1/organizations/{_}/disclosure-drafts` | `docs/features/F09.14_real_estate_disclosure.md` | 286 |
| POST | `/api/v1/organizations/{_}/disclosure-templates` | `docs/features/F09.14_real_estate_disclosure.md` | 276 |
| POST | `/api/v1/organizations/{_}/equipment` | `docs/features/F07.3_equipment.md` | 152 |
| POST | `/api/v1/organizations/{_}/event-categories` | `docs/features/F03.10_annual_event_plan.md` | 208 |
| POST | `/api/v1/organizations/{_}/events` | `docs/features/F03.8_event_management.md` | 395 |
| POST | `/api/v1/organizations/{_}/events/{_}/care-participants/{_}/notify-watcher` | `docs/features/F03.12_care_recipient_event_watch_notification.md` | 466 |
| POST | `/api/v1/organizations/{_}/events/{_}/complete` | `docs/features/F03.8_event_management.md` | 402 |
| POST | `/api/v1/organizations/{_}/form-templates` | `docs/features/F05.7_form_builder.md` | 368 |
| POST | `/api/v1/organizations/{_}/incidents` | `docs/features/F07.6_incident_management.md` | 450 |
| POST | `/api/v1/organizations/{_}/incidents/categories` | `docs/features/F07.6_incident_management.md` | 438 |
| POST | `/api/v1/organizations/{_}/incidents/maintenance-schedules` | `docs/features/F07.6_incident_management.md` | 496 |
| POST | `/api/v1/organizations/{_}/onboarding/templates` | `docs/features/F02.4_onboarding.md` | 353 |
| POST | `/api/v1/organizations/{_}/payment-items` | `docs/features/F08.2_payments_access_control.md` | 350 |
| POST | `/api/v1/organizations/{_}/recruitment-listings` | `docs/features/F03.11_recruitment_listing.md` | 1362 |
| POST | `/api/v1/organizations/{_}/schedules` | `docs/features/F03.10_annual_event_plan.md` | 129 |
| POST | `/api/v1/organizations/{_}/schedules` | `docs/features/F03.1_schedule_shared.md` | 438 |
| POST | `/api/v1/organizations/{_}/schedules` | `docs/features/F03.1_schedule_shared.md` | 824 |
| POST | `/api/v1/organizations/{_}/signage/screens` | `docs/features/F09.10_digital_signage.md` | 301 |
| POST | `/api/v1/organizations/{_}/signage/screens/{_}/emergency` | `docs/features/F09.10_digital_signage.md` | 350 |
| POST | `/api/v1/organizations/{_}/signage/screens/{_}/schedules` | `docs/features/F09.10_digital_signage.md` | 328 |
| POST | `/api/v1/organizations/{_}/signage/screens/{_}/slots` | `docs/features/F09.10_digital_signage.md` | 315 |
| POST | `/api/v1/organizations/{_}/signage/screens/{_}/tokens` | `docs/features/F09.10_digital_signage.md` | 340 |
| POST | `/api/v1/organizations/{_}/signage/upload-url` | `docs/features/F09.10_digital_signage.md` | 358 |
| POST | `/api/v1/organizations/{_}/team-invites` | `docs/features/F01.2_org_team_member_role.md` | 727 |
| POST | `/api/v1/organizations/{_}/timetable-terms` | `docs/features/F03.9_timetable.md` | 295 |
| POST | `/api/v1/organizations/{_}/todo-status-labels` | `docs/features/F02.3.1_todo_status_labels_and_handoff.md` | 213 |
| POST | `/api/v1/organizations/{_}/todos` | `docs/features/F02.8_dashboard_announcement.md` | 184 |
| POST | `/api/v1/organizations/{_}/workflow-templates` | `docs/features/F05.6_workflow_approval.md` | 373 |
| PUT | `/api/v1/organizations/{_}/access-requirements` | `docs/features/F08.2_payments_access_control.md` | 362 |
| PUT | `/api/v1/organizations/{_}/content-payment-gates` | `docs/features/F08.2_payments_access_control.md` | 366 |
| PUT | `/api/v1/organizations/{_}/gamification/config` | `docs/features/F04.7_gamification.md` | 371 |
| PUT | `/api/v1/organizations/{_}/signage/screens/{_}` | `docs/features/F09.10_digital_signage.md` | 303 |
| PUT | `/api/v1/organizations/{_}/signage/screens/{_}/schedules/{_}` | `docs/features/F09.10_digital_signage.md` | 329 |
| PUT | `/api/v1/organizations/{_}/signage/screens/{_}/slots/reorder` | `docs/features/F09.10_digital_signage.md` | 318 |
| PUT | `/api/v1/organizations/{_}/signage/screens/{_}/slots/{_}` | `docs/features/F09.10_digital_signage.md` | 316 |
| PUT | `/api/v1/organizations/{_}/signage/screens/{_}/tokens/{_}` | `docs/features/F09.10_digital_signage.md` | 341 |
| PUT | `/api/v1/organizations/{_}/timetable-periods` | `docs/features/F03.9_timetable.md` | 289 |

### /api/v1/orgs/* (8 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| DELETE | `/api/v1/orgs/{_}/point-cards/providers/{_}` | `docs/features/F18_point_card_wallet.md` | 1453 |
| GET | `/api/v1/orgs/{_}/point-cards/providers` | `docs/features/F18_point_card_wallet.md` | 1450 |
| GET | `/api/v1/orgs/{_}/point-cards/providers/{_}/qr` | `docs/features/F18_point_card_wallet.md` | 1454 |
| PATCH | `/api/v1/orgs/{_}/point-cards/providers/{_}` | `docs/features/F18_point_card_wallet.md` | 1452 |
| POST | `/api/v1/orgs/{_}/point-cards` | `docs/features/F18_point_card_wallet.md` | 572 |
| POST | `/api/v1/orgs/{_}/point-cards` | `docs/features/F18_point_card_wallet.md` | 973 |
| POST | `/api/v1/orgs/{_}/point-cards` | `docs/features/F18_point_card_wallet.md` | 1103 |
| POST | `/api/v1/orgs/{_}/point-cards/providers` | `docs/features/F18_point_card_wallet.md` | 1451 |
| POST | `/api/v1/orgs/{_}/point-cards/{_}/balance-events` | `docs/features/F18_point_card_wallet.md` | 572 |
| POST | `/api/v1/orgs/{_}/point-cards/{_}/balance-events` | `docs/features/F18_point_card_wallet.md` | 1456 |
| POST | `/api/v1/orgs/{_}/point-cards/{_}/stamps` | `docs/features/F18_point_card_wallet.md` | 162 |
| POST | `/api/v1/orgs/{_}/point-cards/{_}/stamps` | `docs/features/F18_point_card_wallet.md` | 572 |
| POST | `/api/v1/orgs/{_}/point-cards/{_}/stamps` | `docs/features/F18_point_card_wallet.md` | 1455 |

### /api/v1/permissions/* (1 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| GET | `/api/v1/permissions` | `docs/features/F01.2_org_team_member_role.md` | 705 |

### /api/v1/platform/* (2 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| GET | `/api/v1/platform/announcements` | `docs/features/F10.1_admin_dashboard.md` | 558 |
| GET | `/api/v1/platform/settings` | `docs/features/F04.1_timeline.md` | 1816 |

### /api/v1/point-cards/* (7 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| GET | `/api/v1/point-cards` | `docs/features/F18_point_card_wallet.md` | 558 |
| GET | `/api/v1/point-cards/groups` | `docs/features/F18_point_card_wallet.md` | 564 |
| GET | `/api/v1/point-cards/providers` | `docs/features/F18_point_card_wallet.md` | 89 |
| GET | `/api/v1/point-cards/providers` | `docs/features/F18_point_card_wallet.md` | 557 |
| GET | `/api/v1/point-cards/settings` | `docs/features/F18_point_card_wallet.md` | 569 |
| POST | `/api/v1/point-cards` | `docs/features/F10.3_audit_logs.md` | 279 |
| POST | `/api/v1/point-cards` | `docs/features/F18_point_card_wallet.md` | 104 |
| POST | `/api/v1/point-cards` | `docs/features/F18_point_card_wallet.md` | 559 |
| POST | `/api/v1/point-cards` | `docs/features/F18_point_card_wallet.md` | 985 |
| POST | `/api/v1/point-cards` | `docs/features/F18_point_card_wallet.md` | 1115 |
| POST | `/api/v1/point-cards/groups` | `docs/features/F10.3_audit_logs.md` | 282 |
| POST | `/api/v1/point-cards/groups` | `docs/features/F18_point_card_wallet.md` | 565 |
| PUT | `/api/v1/point-cards/settings` | `docs/features/F10.3_audit_logs.md` | 284 |
| PUT | `/api/v1/point-cards/settings` | `docs/features/F18_point_card_wallet.md` | 80 |
| PUT | `/api/v1/point-cards/settings` | `docs/features/F18_point_card_wallet.md` | 570 |

### /api/v1/positions/* (1 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| POST | `/api/v1/positions` | `docs/features/F00.5_membership_basis.md` | 808 |

### /api/v1/projects/* (1 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| POST | `/api/v1/projects` | `docs/features/F04.10_committee.md` | 376 |

### /api/v1/promotions/* (17 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| DELETE | `/api/v1/promotions/{_}` | `docs/features/F09.2_promotion_targeting.md` | 375 |
| DELETE | `/api/v1/promotions/{_}/segments/{_}` | `docs/features/F09.2_promotion_targeting.md` | 382 |
| GET | `/api/v1/promotions` | `docs/features/F09.2_promotion_targeting.md` | 371 |
| GET | `/api/v1/promotions` | `docs/features/F09.2_promotion_targeting.md` | 409 |
| GET | `/api/v1/promotions` | `docs/features/F09.2_promotion_targeting.md` | 1186 |
| GET | `/api/v1/promotions/{_}` | `docs/features/F09.2_promotion_targeting.md` | 373 |
| GET | `/api/v1/promotions/{_}/segments` | `docs/features/F09.2_promotion_targeting.md` | 379 |
| GET | `/api/v1/promotions/{_}/stats` | `docs/features/F09.2_promotion_targeting.md` | 378 |
| GET | `/api/v1/promotions/{_}/stats` | `docs/features/F09.2_promotion_targeting.md` | 555 |
| GET | `/api/v1/promotions/{_}/stats/export` | `docs/features/F09.2_promotion_targeting.md` | 387 |
| GET | `/api/v1/promotions/{_}/stats/export` | `docs/features/F09.2_promotion_targeting.md` | 944 |
| PATCH | `/api/v1/promotions/{_}/approve` | `docs/features/F09.2_promotion_targeting.md` | 385 |
| PATCH | `/api/v1/promotions/{_}/approve` | `docs/features/F09.2_promotion_targeting.md` | 874 |
| PATCH | `/api/v1/promotions/{_}/cancel` | `docs/features/F09.2_promotion_targeting.md` | 377 |
| PATCH | `/api/v1/promotions/{_}/cancel` | `docs/features/F09.2_promotion_targeting.md` | 532 |
| PATCH | `/api/v1/promotions/{_}/reject` | `docs/features/F09.2_promotion_targeting.md` | 386 |
| PATCH | `/api/v1/promotions/{_}/reject` | `docs/features/F09.2_promotion_targeting.md` | 912 |
| POST | `/api/v1/promotions` | `docs/features/F09.2_promotion_targeting.md` | 372 |
| POST | `/api/v1/promotions` | `docs/features/F09.2_promotion_targeting.md` | 427 |
| POST | `/api/v1/promotions/{_}/clone` | `docs/features/F09.2_promotion_targeting.md` | 384 |
| POST | `/api/v1/promotions/{_}/clone` | `docs/features/F09.2_promotion_targeting.md` | 844 |
| POST | `/api/v1/promotions/{_}/preview` | `docs/features/F09.2_promotion_targeting.md` | 383 |
| POST | `/api/v1/promotions/{_}/preview` | `docs/features/F09.2_promotion_targeting.md` | 603 |
| POST | `/api/v1/promotions/{_}/publish` | `docs/features/F09.2_promotion_targeting.md` | 376 |
| POST | `/api/v1/promotions/{_}/publish` | `docs/features/F09.2_promotion_targeting.md` | 472 |
| POST | `/api/v1/promotions/{_}/segments` | `docs/features/F09.2_promotion_targeting.md` | 380 |
| PUT | `/api/v1/promotions/{_}` | `docs/features/F09.2_promotion_targeting.md` | 374 |
| PUT | `/api/v1/promotions/{_}/segments/{_}` | `docs/features/F09.2_promotion_targeting.md` | 381 |

### /api/v1/property-listings/* (9 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| DELETE | `/api/v1/property-listings/{_}` | `docs/features/F09.1_resident_registry.md` | 331 |
| GET | `/api/v1/property-listings` | `docs/features/F09.1_resident_registry.md` | 327 |
| GET | `/api/v1/property-listings` | `docs/features/F09.1_resident_registry.md` | 662 |
| GET | `/api/v1/property-listings/{_}` | `docs/features/F09.1_resident_registry.md` | 329 |
| GET | `/api/v1/property-listings/{_}/inquiries` | `docs/features/F09.1_resident_registry.md` | 335 |
| GET | `/api/v1/property-listings/{_}/inquiries` | `docs/features/F09.1_resident_registry.md` | 1066 |
| PATCH | `/api/v1/property-listings/{_}/close` | `docs/features/F09.1_resident_registry.md` | 332 |
| PATCH | `/api/v1/property-listings/{_}/close` | `docs/features/F09.1_resident_registry.md` | 772 |
| PATCH | `/api/v1/property-listings/{_}/withdraw` | `docs/features/F09.1_resident_registry.md` | 333 |
| PATCH | `/api/v1/property-listings/{_}/withdraw` | `docs/features/F09.1_resident_registry.md` | 790 |
| POST | `/api/v1/property-listings` | `docs/features/F09.1_resident_registry.md` | 328 |
| POST | `/api/v1/property-listings` | `docs/features/F09.1_resident_registry.md` | 717 |
| POST | `/api/v1/property-listings/{_}/inquire` | `docs/features/F09.1_resident_registry.md` | 334 |
| POST | `/api/v1/property-listings/{_}/inquire` | `docs/features/F09.1_resident_registry.md` | 1028 |
| PUT | `/api/v1/property-listings/{_}` | `docs/features/F09.1_resident_registry.md` | 330 |
| PUT | `/api/v1/property-listings/{_}` | `docs/features/F09.1_resident_registry.md` | 749 |

### /api/v1/proxy-input-consents/* (1 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| POST | `/api/v1/proxy-input-consents/{_}/scan-upload-url` | `docs/features/F14.1_proxy_input_for_offline_residents.md` | 205 |

### /api/v1/proxy-votes/* (2 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| GET | `/api/v1/proxy-votes` | `docs/features/F08.3_voting_proxy.md` | 319 |
| GET | `/api/v1/proxy-votes` | `docs/features/F08.3_voting_proxy.md` | 354 |
| POST | `/api/v1/proxy-votes` | `docs/features/F08.3_voting_proxy.md` | 320 |
| POST | `/api/v1/proxy-votes` | `docs/features/F08.3_voting_proxy.md` | 377 |

### /api/v1/push-subscriptions/* (2 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| DELETE | `/api/v1/push-subscriptions` | `docs/features/F04.3_push_notification.md` | 215 |
| DELETE | `/api/v1/push-subscriptions` | `docs/features/F04.3_push_notification.md` | 1180 |
| DELETE | `/api/v1/push-subscriptions` | `docs/features/F04.3_push_notification.md` | 1216 |
| POST | `/api/v1/push-subscriptions` | `docs/features/F04.3_push_notification.md` | 214 |
| POST | `/api/v1/push-subscriptions` | `docs/features/F04.3_push_notification.md` | 1179 |
| POST | `/api/v1/push-subscriptions` | `docs/features/F04.3_push_notification.md` | 1182 |

### /api/v1/queue/* (3 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| DELETE | `/api/v1/queue/tickets/{_}` | `docs/features/F03.7_queue.md` | 344 |
| GET | `/api/v1/queue/tickets/{_}` | `docs/features/F03.7_queue.md` | 343 |
| GET | `/api/v1/queue/tickets/{_}` | `docs/features/F03.7_queue.md` | 502 |
| POST | `/api/v1/queue/join/{_}` | `docs/features/F03.7_queue.md` | 342 |
| POST | `/api/v1/queue/join/{_}` | `docs/features/F03.7_queue.md` | 428 |

### /api/v1/quick-memos/* (5 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| GET | `/api/v1/quick-memos` | `docs/features/F02.5_quick_memo.md` | 636 |
| GET | `/api/v1/quick-memos` | `docs/features/F02.5_quick_memo.md` | 942 |
| GET | `/api/v1/quick-memos` | `docs/features/F02.5_quick_memo.md` | 1061 |
| GET | `/api/v1/quick-memos/settings` | `docs/features/F02.5_quick_memo.md` | 683 |
| GET | `/api/v1/quick-memos/trash/{_}` | `docs/features/F02.5_quick_memo.md` | 645 |
| POST | `/api/v1/quick-memos` | `docs/features/F02.5_quick_memo.md` | 637 |
| POST | `/api/v1/quick-memos` | `docs/features/F02.5_quick_memo.md` | 688 |
| PUT | `/api/v1/quick-memos/settings` | `docs/features/F02.5_quick_memo.md` | 449 |
| PUT | `/api/v1/quick-memos/settings` | `docs/features/F02.5_quick_memo.md` | 684 |

### /api/v1/recruitment-categories/* (1 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| GET | `/api/v1/recruitment-categories` | `docs/features/F03.11_recruitment_listing.md` | 1428 |

### /api/v1/recruitment-listings/* (2 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| PATCH | `/api/v1/recruitment-listings/{_}/participants/{_}` | `docs/features/F03.11_recruitment_listing.md` | 1377 |
| PATCH | `/api/v1/recruitment-listings/{_}/participants/{_}/mark-no-show` | `docs/features/F03.11_recruitment_listing.md` | 1462 |

### /api/v1/recruitment-subcategories/* (1 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| POST | `/api/v1/recruitment-subcategories/{_}/archive` | `docs/features/F03.11_recruitment_listing.md` | 1422 |

### /api/v1/reports/* (2 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| DELETE | `/api/v1/reports/{_}` | `docs/features/F10.2_moderation.md` | 356 |
| DELETE | `/api/v1/reports/{_}` | `docs/features/F10.2_moderation.md` | 389 |
| POST | `/api/v1/reports` | `docs/features/F04.5_moderation.md` | 54 |
| POST | `/api/v1/reports` | `docs/features/F04.5_moderation.md` | 64 |
| POST | `/api/v1/reports` | `docs/features/F10.1_admin_dashboard.md` | 441 |
| POST | `/api/v1/reports` | `docs/features/F10.1_admin_dashboard.md` | 567 |

### /api/v1/residence-status/* (13 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| GET | `/api/v1/residence-status/activity-snapshots/{_}` | `docs/features/F09.16_residence_status_management.md` | 372 |
| GET | `/api/v1/residence-status/annual-reviews` | `docs/features/F09.16_residence_status_management.md` | 366 |
| GET | `/api/v1/residence-status/annual-reviews/my` | `docs/features/F09.16_residence_status_management.md` | 370 |
| GET | `/api/v1/residence-status/annual-reviews/{_}` | `docs/features/F09.16_residence_status_management.md` | 367 |
| GET | `/api/v1/residence-status/annual-reviews/{_}/responses` | `docs/features/F09.16_residence_status_management.md` | 369 |
| GET | `/api/v1/residence-status/dashboard` | `docs/features/F09.16_residence_status_management.md` | 377 |
| GET | `/api/v1/residence-status/monitoring-visits` | `docs/features/F09.16_residence_status_management.md` | 374 |
| POST | `/api/v1/residence-status/annual-reviews` | `docs/features/F09.16_residence_status_management.md` | 365 |
| POST | `/api/v1/residence-status/annual-reviews/{_}/close` | `docs/features/F09.16_residence_status_management.md` | 368 |
| POST | `/api/v1/residence-status/monitoring-visits` | `docs/features/F09.16_residence_status_management.md` | 373 |
| POST | `/api/v1/residence-status/org-wide-safety-checks` | `docs/features/F09.16_residence_status_management.md` | 376 |
| PUT | `/api/v1/residence-status/annual-reviews/{_}/responses/me` | `docs/features/F09.16_residence_status_management.md` | 371 |
| PUT | `/api/v1/residence-status/monitoring-visits/{_}` | `docs/features/F09.16_residence_status_management.md` | 375 |

### /api/v1/safety-checks/* (12 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| DELETE | `/api/v1/safety-checks/templates/{_}` | `docs/features/F03.6_safety_check.md` | 273 |
| DELETE | `/api/v1/safety-checks/templates/{_}` | `docs/features/F03.6_safety_check.md` | 892 |
| GET | `/api/v1/safety-checks` | `docs/features/F03.6_safety_check.md` | 260 |
| GET | `/api/v1/safety-checks` | `docs/features/F03.6_safety_check.md` | 347 |
| GET | `/api/v1/safety-checks/message-presets` | `docs/features/F03.6_safety_check.md` | 268 |
| GET | `/api/v1/safety-checks/message-presets` | `docs/features/F03.6_safety_check.md` | 762 |
| GET | `/api/v1/safety-checks/message-presets` | `docs/features/F04.3_push_notification.md` | 774 |
| GET | `/api/v1/safety-checks/my` | `docs/features/F03.6_safety_check.md` | 267 |
| GET | `/api/v1/safety-checks/my` | `docs/features/F03.6_safety_check.md` | 717 |
| GET | `/api/v1/safety-checks/pending` | `docs/features/F03.6_safety_check.md` | 266 |
| GET | `/api/v1/safety-checks/pending` | `docs/features/F03.6_safety_check.md` | 681 |
| GET | `/api/v1/safety-checks/pending` | `docs/features/F04.3_push_notification.md` | 702 |
| GET | `/api/v1/safety-checks/templates` | `docs/features/F03.6_safety_check.md` | 270 |
| GET | `/api/v1/safety-checks/templates` | `docs/features/F03.6_safety_check.md` | 809 |
| PATCH | `/api/v1/safety-checks/{_}/close` | `docs/features/F03.6_safety_check.md` | 262 |
| PATCH | `/api/v1/safety-checks/{_}/close` | `docs/features/F03.6_safety_check.md` | 449 |
| POST | `/api/v1/safety-checks` | `docs/features/F03.6_safety_check.md` | 259 |
| POST | `/api/v1/safety-checks` | `docs/features/F03.6_safety_check.md` | 286 |
| POST | `/api/v1/safety-checks/bulk-respond` | `docs/features/F03.6_safety_check.md` | 264 |
| POST | `/api/v1/safety-checks/bulk-respond` | `docs/features/F03.6_safety_check.md` | 541 |
| POST | `/api/v1/safety-checks/templates` | `docs/features/F03.6_safety_check.md` | 271 |
| POST | `/api/v1/safety-checks/templates` | `docs/features/F03.6_safety_check.md` | 847 |
| PUT | `/api/v1/safety-checks/templates/{_}` | `docs/features/F03.6_safety_check.md` | 272 |
| PUT | `/api/v1/safety-checks/templates/{_}` | `docs/features/F03.6_safety_check.md` | 876 |
| PUT | `/api/v1/safety-checks/{_}/results/followups/{_}` | `docs/features/F03.6_safety_check.md` | 274 |
| PUT | `/api/v1/safety-checks/{_}/results/followups/{_}` | `docs/features/F03.6_safety_check.md` | 906 |

### /api/v1/schedules/* (2 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| GET | `/api/v1/schedules/{_}/media` | `docs/features/F03.14_schedule_media.md` | 100 |
| GET | `/api/v1/schedules/{_}/media` | `docs/features/F03.14_schedule_media.md` | 140 |
| POST | `/api/v1/schedules` | `docs/features/F04.10_committee.md` | 377 |

### /api/v1/seal/* (3 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| GET | `/api/v1/seal/stamps/verify-batch` | `docs/features/F05.3_digital_seal.md` | 161 |
| GET | `/api/v1/seal/stamps/verify-batch` | `docs/features/F05.3_digital_seal.md` | 449 |
| GET | `/api/v1/seal/stamps/{_}/verify` | `docs/features/F05.3_digital_seal.md` | 160 |
| GET | `/api/v1/seal/stamps/{_}/verify` | `docs/features/F05.3_digital_seal.md` | 402 |
| POST | `/api/v1/seal/stamps/{_}/revoke` | `docs/features/F05.3_digital_seal.md` | 159 |
| POST | `/api/v1/seal/stamps/{_}/revoke` | `docs/features/F05.3_digital_seal.md` | 362 |

### /api/v1/search/* (2 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| DELETE | `/api/v1/search/saved/{_}` | `docs/features/F04.6_search.md` | 66 |
| GET | `/api/v1/search` | `docs/features/F04.6_search.md` | 59 |
| GET | `/api/v1/search` | `docs/features/F04.6_search.md` | 70 |

### /api/v1/segment-presets/* (4 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| DELETE | `/api/v1/segment-presets/{_}` | `docs/features/F09.2_promotion_targeting.md` | 391 |
| GET | `/api/v1/segment-presets` | `docs/features/F09.2_promotion_targeting.md` | 388 |
| GET | `/api/v1/segment-presets` | `docs/features/F09.2_promotion_targeting.md` | 973 |
| POST | `/api/v1/segment-presets` | `docs/features/F09.2_promotion_targeting.md` | 389 |
| POST | `/api/v1/segment-presets` | `docs/features/F09.2_promotion_targeting.md` | 1000 |
| PUT | `/api/v1/segment-presets/{_}` | `docs/features/F09.2_promotion_targeting.md` | 390 |

### /api/v1/shared/* (1 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| GET | `/api/v1/shared/{_}` | `docs/features/F05.5_file_sharing.md` | 357 |

### /api/v1/shift-budget/* (1 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| POST | `/api/v1/shift-budget/allocations` | `docs/features/F08.7_shift_budget_integration.md` | 759 |

### /api/v1/shifts/* (28 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| DELETE | `/api/v1/shifts/work-constraints/{_}` | `docs/features/F03.5_shift.md` | 608 |
| DELETE | `/api/v1/shifts/work-constraints/{_}` | `docs/features/F03.5_shift.md` | 2351 |
| GET | `/api/v1/shifts/change-requests` | `docs/features/F03.5_shift.md` | 610 |
| GET | `/api/v1/shifts/change-requests` | `docs/features/F03.5_shift.md` | 2413 |
| GET | `/api/v1/shifts/hourly-rates` | `docs/features/F03.5_shift.md` | 597 |
| GET | `/api/v1/shifts/hourly-rates` | `docs/features/F03.5_shift.md` | 1903 |
| GET | `/api/v1/shifts/my` | `docs/features/F03.5_shift.md` | 581 |
| GET | `/api/v1/shifts/my` | `docs/features/F03.5_shift.md` | 1282 |
| GET | `/api/v1/shifts/positions` | `docs/features/F03.5_shift.md` | 584 |
| GET | `/api/v1/shifts/positions` | `docs/features/F03.5_shift.md` | 1436 |
| GET | `/api/v1/shifts/schedules` | `docs/features/F03.5_shift.md` | 565 |
| GET | `/api/v1/shifts/schedules` | `docs/features/F03.5_shift.md` | 621 |
| GET | `/api/v1/shifts/schedules/{_}/pdf` | `docs/features/F03.5_shift.md` | 52 |
| GET | `/api/v1/shifts/schedules/{_}/pdf` | `docs/features/F03.5_shift.md` | 617 |
| GET | `/api/v1/shifts/schedules/{_}/pdf` | `docs/features/F03.5_shift.md` | 2576 |
| GET | `/api/v1/shifts/schedules/{_}/pdf` | `docs/features/F03.5_shift.md` | 3411 |
| GET | `/api/v1/shifts/schedules/{_}/pdf` | `docs/features/F03.5_shift.md` | 3948 |
| GET | `/api/v1/shifts/schedules/{_}/requests` | `docs/features/F03.5_shift.md` | 577 |
| GET | `/api/v1/shifts/schedules/{_}/requests` | `docs/features/F03.5_shift.md` | 1129 |
| GET | `/api/v1/shifts/schedules/{_}/summary` | `docs/features/F03.5_shift.md` | 582 |
| GET | `/api/v1/shifts/schedules/{_}/summary` | `docs/features/F03.5_shift.md` | 1348 |
| GET | `/api/v1/shifts/work-constraints` | `docs/features/F03.5_shift.md` | 605 |
| GET | `/api/v1/shifts/work-constraints` | `docs/features/F03.5_shift.md` | 2257 |
| PATCH | `/api/v1/shifts/schedules/{_}/publish` | `docs/features/F03.5_shift.md` | 571 |
| PATCH | `/api/v1/shifts/schedules/{_}/publish` | `docs/features/F03.5_shift.md` | 898 |
| PATCH | `/api/v1/shifts/schedules/{_}/status` | `docs/features/F03.5_shift.md` | 570 |
| PATCH | `/api/v1/shifts/schedules/{_}/status` | `docs/features/F03.5_shift.md` | 849 |
| PATCH | `/api/v1/shifts/swap-requests/{_}/accept` | `docs/features/F03.5_shift.md` | 589 |
| PATCH | `/api/v1/shifts/swap-requests/{_}/accept` | `docs/features/F03.5_shift.md` | 1578 |
| PATCH | `/api/v1/shifts/swap-requests/{_}/approve` | `docs/features/F03.5_shift.md` | 590 |
| PATCH | `/api/v1/shifts/swap-requests/{_}/approve` | `docs/features/F03.5_shift.md` | 1605 |
| PATCH | `/api/v1/shifts/swap-requests/{_}/reject` | `docs/features/F03.5_shift.md` | 591 |
| PATCH | `/api/v1/shifts/swap-requests/{_}/reject` | `docs/features/F03.5_shift.md` | 1643 |
| POST | `/api/v1/shifts/change-requests` | `docs/features/F03.5_shift.md` | 609 |
| POST | `/api/v1/shifts/change-requests` | `docs/features/F03.5_shift.md` | 2365 |
| POST | `/api/v1/shifts/positions` | `docs/features/F03.5_shift.md` | 585 |
| POST | `/api/v1/shifts/positions` | `docs/features/F03.5_shift.md` | 1464 |
| POST | `/api/v1/shifts/schedules` | `docs/features/F03.5_shift.md` | 566 |
| POST | `/api/v1/shifts/schedules` | `docs/features/F03.5_shift.md` | 674 |
| POST | `/api/v1/shifts/schedules/{_}/remind` | `docs/features/F03.5_shift.md` | 583 |
| POST | `/api/v1/shifts/schedules/{_}/remind` | `docs/features/F03.5_shift.md` | 1406 |
| POST | `/api/v1/shifts/swap-requests` | `docs/features/F03.5_shift.md` | 588 |
| POST | `/api/v1/shifts/swap-requests` | `docs/features/F03.5_shift.md` | 1533 |
| PUT | `/api/v1/shifts/hourly-rate` | `docs/features/F03.5_shift.md` | 596 |
| PUT | `/api/v1/shifts/hourly-rate` | `docs/features/F03.5_shift.md` | 1876 |
| PUT | `/api/v1/shifts/hourly-rates/{_}` | `docs/features/F03.5_shift.md` | 598 |
| PUT | `/api/v1/shifts/hourly-rates/{_}` | `docs/features/F03.5_shift.md` | 1941 |
| PUT | `/api/v1/shifts/positions/{_}` | `docs/features/F03.5_shift.md` | 586 |
| PUT | `/api/v1/shifts/positions/{_}` | `docs/features/F03.5_shift.md` | 1490 |
| PUT | `/api/v1/shifts/requests/{_}` | `docs/features/F03.5_shift.md` | 579 |
| PUT | `/api/v1/shifts/requests/{_}` | `docs/features/F03.5_shift.md` | 1243 |
| PUT | `/api/v1/shifts/schedules/{_}` | `docs/features/F03.5_shift.md` | 568 |
| PUT | `/api/v1/shifts/schedules/{_}` | `docs/features/F03.5_shift.md` | 797 |
| PUT | `/api/v1/shifts/slots/{_}` | `docs/features/F03.5_shift.md` | 575 |
| PUT | `/api/v1/shifts/slots/{_}` | `docs/features/F03.5_shift.md` | 1081 |
| PUT | `/api/v1/shifts/work-constraints` | `docs/features/F03.5_shift.md` | 606 |
| PUT | `/api/v1/shifts/work-constraints` | `docs/features/F03.5_shift.md` | 2304 |
| PUT | `/api/v1/shifts/work-constraints` | `docs/features/F03.5_shift.md` | 2338 |
| PUT | `/api/v1/shifts/work-constraints/{_}` | `docs/features/F03.5_shift.md` | 607 |
| PUT | `/api/v1/shifts/work-constraints/{_}` | `docs/features/F03.5_shift.md` | 2334 |

### /api/v1/signage/* (3 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| GET | `/api/v1/signage/{_}/content/{_}` | `docs/features/F09.10_digital_signage.md` | 364 |
| GET | `/api/v1/signage/{_}/content/{_}` | `docs/features/F09.10_digital_signage.md` | 629 |
| GET | `/api/v1/signage/{_}/screen` | `docs/features/F09.10_digital_signage.md` | 363 |
| GET | `/api/v1/signage/{_}/screen` | `docs/features/F09.10_digital_signage.md` | 560 |
| GET | `/api/v1/signage/{_}/weather` | `docs/features/F09.10_digital_signage.md` | 365 |

### /api/v1/sns/* (8 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| DELETE | `/api/v1/sns/configs/{_}` | `docs/features/F09.4_line_sns.md` | 241 |
| DELETE | `/api/v1/sns/configs/{_}` | `docs/features/F09.4_line_sns.md` | 751 |
| GET | `/api/v1/sns/configs` | `docs/features/F09.4_line_sns.md` | 238 |
| GET | `/api/v1/sns/feeds` | `docs/features/F09.4_line_sns.md` | 237 |
| GET | `/api/v1/sns/feeds` | `docs/features/F09.4_line_sns.md` | 785 |
| GET | `/api/v1/sns/instagram/oauth/callback` | `docs/features/F09.4_line_sns.md` | 244 |
| GET | `/api/v1/sns/instagram/oauth/callback` | `docs/features/F09.4_line_sns.md` | 673 |
| GET | `/api/v1/sns/instagram/oauth/start` | `docs/features/F09.4_line_sns.md` | 243 |
| GET | `/api/v1/sns/instagram/oauth/start` | `docs/features/F09.4_line_sns.md` | 643 |
| POST | `/api/v1/sns/configs` | `docs/features/F09.4_line_sns.md` | 239 |
| POST | `/api/v1/sns/configs` | `docs/features/F09.4_line_sns.md` | 702 |
| POST | `/api/v1/sns/configs/{_}/refresh` | `docs/features/F09.4_line_sns.md` | 242 |
| POST | `/api/v1/sns/configs/{_}/refresh` | `docs/features/F09.4_line_sns.md` | 761 |
| PUT | `/api/v1/sns/configs/{_}` | `docs/features/F09.4_line_sns.md` | 240 |
| PUT | `/api/v1/sns/configs/{_}` | `docs/features/F09.4_line_sns.md` | 735 |

### /api/v1/social-profiles/* (8 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| DELETE | `/api/v1/social-profiles/{_}` | `docs/features/F04.4_social_profiles.md` | 96 |
| DELETE | `/api/v1/social-profiles/{_}` | `docs/features/F04.4_social_profiles.md` | 261 |
| GET | `/api/v1/social-profiles/handle/{_}` | `docs/features/F04.4_social_profiles.md` | 94 |
| GET | `/api/v1/social-profiles/handle/{_}` | `docs/features/F04.4_social_profiles.md` | 191 |
| GET | `/api/v1/social-profiles/handle/{_}` | `docs/features/F04.4_social_profiles.md` | 550 |
| GET | `/api/v1/social-profiles/me` | `docs/features/F04.4_social_profiles.md` | 93 |
| GET | `/api/v1/social-profiles/me` | `docs/features/F04.4_social_profiles.md` | 164 |
| GET | `/api/v1/social-profiles/me` | `docs/features/F04.4_social_profiles.md` | 549 |
| GET | `/api/v1/social-profiles/{_}/followers` | `docs/features/F04.4_social_profiles.md` | 107 |
| GET | `/api/v1/social-profiles/{_}/followers` | `docs/features/F04.4_social_profiles.md` | 109 |
| GET | `/api/v1/social-profiles/{_}/following` | `docs/features/F04.4_social_profiles.md` | 106 |
| GET | `/api/v1/social-profiles/{_}/followings` | `docs/features/F04.4_social_profiles.md` | 108 |
| POST | `/api/v1/social-profiles` | `docs/features/F04.4_social_profiles.md` | 92 |
| POST | `/api/v1/social-profiles` | `docs/features/F04.4_social_profiles.md` | 117 |
| PUT | `/api/v1/social-profiles/{_}` | `docs/features/F04.4_social_profiles.md` | 95 |
| PUT | `/api/v1/social-profiles/{_}` | `docs/features/F04.4_social_profiles.md` | 226 |

### /api/v1/stripe/* (3 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| GET | `/api/v1/stripe/connect/me` | `docs/features/F13.1_short_term_job_matching.md` | 1647 |
| POST | `/api/v1/stripe/connect/login-link` | `docs/features/F13.1_short_term_job_matching.md` | 1648 |
| POST | `/api/v1/stripe/connect/onboarding-link` | `docs/features/F13.1_short_term_job_matching.md` | 1646 |
| POST | `/api/v1/stripe/connect/onboarding-link` | `docs/features/F13.1_short_term_job_matching.md` | 2548 |

### /api/v1/succession/* (18 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| GET | `/api/v1/succession/covenant-templates` | `docs/features/F09.15_resident_succession_support.md` | 498 |
| GET | `/api/v1/succession/delinquency-escalations` | `docs/features/F09.15_resident_succession_support.md` | 510 |
| GET | `/api/v1/succession/frozen-account-guidance` | `docs/features/F09.15_resident_succession_support.md` | 516 |
| GET | `/api/v1/succession/legal-filings/{_}/evidence-zip` | `docs/features/F09.15_resident_succession_support.md` | 514 |
| GET | `/api/v1/succession/pre-registrations/me` | `docs/features/F09.15_resident_succession_support.md` | 501 |
| GET | `/api/v1/succession/pre-registrations/{_}` | `docs/features/F09.15_resident_succession_support.md` | 503 |
| GET | `/api/v1/succession/unseal-requests/{_}/audit-views` | `docs/features/F09.15_resident_succession_support.md` | 509 |
| POST | `/api/v1/succession/covenants/{_}/verify` | `docs/features/F12.1_pdf_generation.md` | 406 |
| POST | `/api/v1/succession/delinquency-escalations/{_}/freeze` | `docs/features/F09.15_resident_succession_support.md` | 511 |
| POST | `/api/v1/succession/delinquency-escalations/{_}/resolve` | `docs/features/F09.15_resident_succession_support.md` | 512 |
| POST | `/api/v1/succession/legal-filings` | `docs/features/F09.15_resident_succession_support.md` | 513 |
| POST | `/api/v1/succession/legal-filings/{_}/evidence-rebuild` | `docs/features/F09.15_resident_succession_support.md` | 515 |
| POST | `/api/v1/succession/residents/{_}/death-status` | `docs/features/F09.15_resident_succession_support.md` | 504 |
| POST | `/api/v1/succession/unseal-requests` | `docs/features/F09.15_resident_succession_support.md` | 505 |
| POST | `/api/v1/succession/unseal-requests/{_}/first-approve` | `docs/features/F09.15_resident_succession_support.md` | 506 |
| POST | `/api/v1/succession/unseal-requests/{_}/reject` | `docs/features/F09.15_resident_succession_support.md` | 508 |
| POST | `/api/v1/succession/unseal-requests/{_}/second-approve` | `docs/features/F09.15_resident_succession_support.md` | 507 |
| PUT | `/api/v1/succession/pre-registrations/me` | `docs/features/F09.15_resident_succession_support.md` | 502 |

### /api/v1/surveys/* (16 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| DELETE | `/api/v1/surveys/{_}` | `docs/features/F05.4_survey_vote.md` | 287 |
| DELETE | `/api/v1/surveys/{_}` | `docs/features/F05.4_survey_vote.md` | 556 |
| GET | `/api/v1/surveys` | `docs/features/F05.4_survey_vote.md` | 283 |
| GET | `/api/v1/surveys` | `docs/features/F05.4_survey_vote.md` | 308 |
| GET | `/api/v1/surveys/series/{_}/comparison` | `docs/features/F05.4_survey_vote.md` | 300 |
| GET | `/api/v1/surveys/series/{_}/comparison` | `docs/features/F05.4_survey_vote.md` | 1097 |
| GET | `/api/v1/surveys/{_}` | `docs/features/F05.4_survey_vote.md` | 285 |
| GET | `/api/v1/surveys/{_}` | `docs/features/F05.4_survey_vote.md` | 469 |
| GET | `/api/v1/surveys/{_}/respondents` | `docs/features/F05.4_survey_vote.md` | 294 |
| GET | `/api/v1/surveys/{_}/respondents` | `docs/features/F05.4_survey_vote.md` | 849 |
| GET | `/api/v1/surveys/{_}/responses/my` | `docs/features/F05.4_survey_vote.md` | 291 |
| GET | `/api/v1/surveys/{_}/responses/my` | `docs/features/F05.4_survey_vote.md` | 713 |
| GET | `/api/v1/surveys/{_}/responses/{_}` | `docs/features/F05.4_survey_vote.md` | 299 |
| GET | `/api/v1/surveys/{_}/responses/{_}` | `docs/features/F05.4_survey_vote.md` | 1045 |
| GET | `/api/v1/surveys/{_}/results/export` | `docs/features/F05.4_survey_vote.md` | 293 |
| GET | `/api/v1/surveys/{_}/results/export` | `docs/features/F05.4_survey_vote.md` | 819 |
| PATCH | `/api/v1/surveys/{_}/close` | `docs/features/F05.4_survey_vote.md` | 289 |
| PATCH | `/api/v1/surveys/{_}/close` | `docs/features/F05.4_survey_vote.md` | 614 |
| PATCH | `/api/v1/surveys/{_}/extend` | `docs/features/F05.4_survey_vote.md` | 297 |
| PATCH | `/api/v1/surveys/{_}/extend` | `docs/features/F05.4_survey_vote.md` | 971 |
| PATCH | `/api/v1/surveys/{_}/publish` | `docs/features/F05.4_survey_vote.md` | 288 |
| PATCH | `/api/v1/surveys/{_}/publish` | `docs/features/F05.4_survey_vote.md` | 579 |
| POST | `/api/v1/surveys` | `docs/features/F04.10_committee.md` | 378 |
| POST | `/api/v1/surveys` | `docs/features/F05.4_survey_vote.md` | 284 |
| POST | `/api/v1/surveys` | `docs/features/F05.4_survey_vote.md` | 363 |
| POST | `/api/v1/surveys/{_}/duplicate` | `docs/features/F05.4_survey_vote.md` | 296 |
| POST | `/api/v1/surveys/{_}/duplicate` | `docs/features/F05.4_survey_vote.md` | 939 |
| POST | `/api/v1/surveys/{_}/generate-blog-draft` | `docs/features/F05.4_survey_vote.md` | 295 |
| POST | `/api/v1/surveys/{_}/generate-blog-draft` | `docs/features/F05.4_survey_vote.md` | 881 |
| POST | `/api/v1/surveys/{_}/responses` | `docs/features/F05.4_survey_vote.md` | 290 |
| POST | `/api/v1/surveys/{_}/responses` | `docs/features/F05.4_survey_vote.md` | 648 |
| PUT | `/api/v1/surveys/{_}` | `docs/features/F05.4_survey_vote.md` | 286 |
| PUT | `/api/v1/surveys/{_}` | `docs/features/F05.4_survey_vote.md` | 537 |

### /api/v1/sync/* (1 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| POST | `/api/v1/sync` | `docs/features/F11.1_offline_pwa.md` | 105 |
| POST | `/api/v1/sync` | `docs/features/F11.1_offline_pwa.md` | 113 |
| POST | `/api/v1/sync` | `docs/features/F11.1_offline_pwa.md` | 891 |
| POST | `/api/v1/sync` | `docs/features/F11.1_offline_pwa.md` | 895 |

### /api/v1/system-admin/* (104 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| DELETE | `/api/v1/system-admin/activity-templates/{_}` | `docs/features/F06.1_cms_blog.md` | 762 |
| DELETE | `/api/v1/system-admin/discount-campaigns/{_}` | `docs/features/F10.1_admin_dashboard.md` | 505 |
| DELETE | `/api/v1/system-admin/feature-flags/{_}/overrides/{_}` | `docs/features/F12.2_feature_flag.md` | 119 |
| DELETE | `/api/v1/system-admin/modules/{_}` | `docs/features/F01.3_template_module.md` | 398 |
| DELETE | `/api/v1/system-admin/packages/{_}` | `docs/features/F10.1_admin_dashboard.md` | 501 |
| DELETE | `/api/v1/system-admin/seasonal-themes/{_}` | `docs/features/F10.1_admin_dashboard.md` | 522 |
| DELETE | `/api/v1/system-admin/storage-plans/{_}` | `docs/features/F10.1_admin_dashboard.md` | 512 |
| GET | `/api/v1/system-admin/activity-template-presets` | `docs/features/F06.4_activity_records.md` | 340 |
| GET | `/api/v1/system-admin/activity-templates` | `docs/features/F06.1_cms_blog.md` | 759 |
| GET | `/api/v1/system-admin/ad-user-reports` | `docs/features/F09.17_advertiser_targeted_campaign.md` | 448 |
| GET | `/api/v1/system-admin/ad-user-reports` | `docs/features/F09.17_advertiser_targeted_campaign.md` | 704 |
| GET | `/api/v1/system-admin/affiliate-configs` | `docs/features/F09.7_advertising.md` | 97 |
| GET | `/api/v1/system-admin/affiliate-configs` | `docs/features/F09.7_advertising.md` | 227 |
| GET | `/api/v1/system-admin/affiliate-configs` | `docs/features/F10.1_admin_dashboard.md` | 525 |
| GET | `/api/v1/system-admin/affiliate-configs/preview` | `docs/features/F09.7_advertising.md` | 103 |
| GET | `/api/v1/system-admin/affiliate-configs/preview` | `docs/features/F09.7_advertising.md` | 370 |
| GET | `/api/v1/system-admin/announcements` | `docs/features/F10.1_admin_dashboard.md` | 541 |
| GET | `/api/v1/system-admin/batch-jobs` | `docs/features/F10.1_admin_dashboard.md` | 548 |
| GET | `/api/v1/system-admin/batch-jobs/{_}/history` | `docs/features/F10.1_admin_dashboard.md` | 549 |
| GET | `/api/v1/system-admin/beta-restriction` | `docs/features/F00.6_beta_restriction.md` | 34 |
| GET | `/api/v1/system-admin/dashboard` | `docs/features/F10.1_admin_dashboard.md` | 490 |
| GET | `/api/v1/system-admin/dashboard` | `docs/features/F10.1_admin_dashboard.md` | 1220 |
| GET | `/api/v1/system-admin/dashboard` | `docs/features/F10.4_business_analytics.md` | 15 |
| GET | `/api/v1/system-admin/data-export-requests` | `docs/features/F10.1_admin_dashboard.md` | 555 |
| GET | `/api/v1/system-admin/discount-campaigns` | `docs/features/F10.1_admin_dashboard.md` | 502 |
| GET | `/api/v1/system-admin/discount-campaigns/{_}/usages` | `docs/features/F10.1_admin_dashboard.md` | 506 |
| GET | `/api/v1/system-admin/error-reports` | `docs/features/F10.1_admin_dashboard.md` | 523 |
| GET | `/api/v1/system-admin/error-reports` | `docs/features/F10.6_error_monitoring.md` | 155 |
| GET | `/api/v1/system-admin/error-reports` | `docs/features/F12.5_frontend_error_tracking.md` | 351 |
| GET | `/api/v1/system-admin/error-reports` | `docs/features/F12.5_frontend_error_tracking.md` | 930 |
| GET | `/api/v1/system-admin/feature-flags` | `docs/features/F10.1_admin_dashboard.md` | 534 |
| GET | `/api/v1/system-admin/feature-flags` | `docs/features/F12.2_feature_flag.md` | 111 |
| GET | `/api/v1/system-admin/feature-flags/{_}/overrides` | `docs/features/F12.2_feature_flag.md` | 117 |
| GET | `/api/v1/system-admin/feedback` | `docs/features/F10.1_admin_dashboard.md` | 547 |
| GET | `/api/v1/system-admin/health` | `docs/features/F10.1_admin_dashboard.md` | 551 |
| GET | `/api/v1/system-admin/maintenance-schedules` | `docs/features/F10.1_admin_dashboard.md` | 538 |
| GET | `/api/v1/system-admin/moderation-settings` | `docs/features/F10.1_admin_dashboard.md` | 531 |
| GET | `/api/v1/system-admin/module-prices` | `docs/features/F10.1_admin_dashboard.md` | 496 |
| GET | `/api/v1/system-admin/module-usage-stats` | `docs/features/F10.1_admin_dashboard.md` | 533 |
| GET | `/api/v1/system-admin/module-usage-stats` | `docs/features/F10.1_admin_dashboard.md` | 1123 |
| GET | `/api/v1/system-admin/modules` | `docs/features/F01.3_template_module.md` | 395 |
| GET | `/api/v1/system-admin/modules/level-availability` | `docs/features/F01.3_template_module.md` | 399 |
| GET | `/api/v1/system-admin/modules/level-settings` | `docs/features/F10.1_admin_dashboard.md` | 527 |
| GET | `/api/v1/system-admin/modules/{_}/deactivation-impact` | `docs/features/F01.3_template_module.md` | 405 |
| GET | `/api/v1/system-admin/modules/{_}/deactivation-impact` | `docs/features/F01.3_template_module.md` | 1305 |
| GET | `/api/v1/system-admin/modules/{_}/usage-stats` | `docs/features/F01.3_template_module.md` | 401 |
| GET | `/api/v1/system-admin/modules/{_}/usage-stats` | `docs/features/F01.3_template_module.md` | 889 |
| GET | `/api/v1/system-admin/notification-stats` | `docs/features/F10.1_admin_dashboard.md` | 552 |
| GET | `/api/v1/system-admin/org-count-billing-tiers` | `docs/features/F10.1_admin_dashboard.md` | 514 |
| GET | `/api/v1/system-admin/org-count-billing/overview` | `docs/features/F10.1_admin_dashboard.md` | 516 |
| GET | `/api/v1/system-admin/org-type-change-requests` | `docs/features/F10.1_admin_dashboard.md` | 517 |
| GET | `/api/v1/system-admin/organizations` | `docs/features/F10.1_admin_dashboard.md` | 491 |
| GET | `/api/v1/system-admin/packages` | `docs/features/F10.1_admin_dashboard.md` | 498 |
| GET | `/api/v1/system-admin/promotions/billing` | `docs/features/F09.2_promotion_targeting.md` | 399 |
| GET | `/api/v1/system-admin/promotions/billing` | `docs/features/F09.2_promotion_targeting.md` | 797 |
| GET | `/api/v1/system-admin/promotions/billing/settings` | `docs/features/F09.2_promotion_targeting.md` | 400 |
| GET | `/api/v1/system-admin/reports` | `docs/features/F10.1_admin_dashboard.md` | 464 |
| GET | `/api/v1/system-admin/reports/monthly` | `docs/features/F10.1_admin_dashboard.md` | 554 |
| GET | `/api/v1/system-admin/reports/weekly` | `docs/features/F10.1_admin_dashboard.md` | 553 |
| GET | `/api/v1/system-admin/role-permissions` | `docs/features/F10.1_admin_dashboard.md` | 529 |
| GET | `/api/v1/system-admin/safety-checks/message-presets` | `docs/features/F03.6_safety_check.md` | 275 |
| GET | `/api/v1/system-admin/safety-checks/message-presets` | `docs/features/F03.6_safety_check.md` | 950 |
| GET | `/api/v1/system-admin/seasonal-themes` | `docs/features/F10.1_admin_dashboard.md` | 519 |
| GET | `/api/v1/system-admin/storage-plans` | `docs/features/F10.1_admin_dashboard.md` | 509 |
| GET | `/api/v1/system-admin/storage-usage` | `docs/features/F10.1_admin_dashboard.md` | 513 |
| GET | `/api/v1/system-admin/tax-settings` | `docs/features/F10.1_admin_dashboard.md` | 507 |
| GET | `/api/v1/system-admin/teams` | `docs/features/F10.1_admin_dashboard.md` | 492 |
| GET | `/api/v1/system-admin/template-wallpapers` | `docs/features/F01.4_family_team.md` | 440 |
| GET | `/api/v1/system-admin/templates` | `docs/features/F01.3_template_module.md` | 390 |
| GET | `/api/v1/system-admin/templates/{_}/usage-stats` | `docs/features/F01.3_template_module.md` | 402 |
| GET | `/api/v1/system-admin/templates/{_}/usage-stats` | `docs/features/F01.3_template_module.md` | 1011 |
| GET | `/api/v1/system-admin/users` | `docs/features/F10.1_admin_dashboard.md` | 493 |
| PATCH | `/api/v1/system-admin/affiliate-configs/toggle-all` | `docs/features/F09.7_advertising.md` | 101 |
| PATCH | `/api/v1/system-admin/affiliate-configs/toggle-all` | `docs/features/F09.7_advertising.md` | 336 |
| PATCH | `/api/v1/system-admin/announcements/{_}/unpin` | `docs/features/F10.1_admin_dashboard.md` | 546 |
| PATCH | `/api/v1/system-admin/error-reports/{_}/status` | `docs/features/F10.1_admin_dashboard.md` | 524 |
| PATCH | `/api/v1/system-admin/org-type-change-requests/{_}` | `docs/features/F10.1_admin_dashboard.md` | 518 |
| PATCH | `/api/v1/system-admin/promotions/{_}/suspend` | `docs/features/F09.2_promotion_targeting.md` | 402 |
| PATCH | `/api/v1/system-admin/promotions/{_}/suspend` | `docs/features/F09.2_promotion_targeting.md` | 1038 |
| PATCH | `/api/v1/system-admin/safety-checks/message-presets/{_}/active` | `docs/features/F03.6_safety_check.md` | 278 |
| PATCH | `/api/v1/system-admin/safety-checks/message-presets/{_}/active` | `docs/features/F03.6_safety_check.md` | 1024 |
| PATCH | `/api/v1/system-admin/users/{_}/freeze` | `docs/features/F10.1_admin_dashboard.md` | 494 |
| PATCH | `/api/v1/system-admin/users/{_}/unfreeze` | `docs/features/F10.1_admin_dashboard.md` | 495 |
| POST | `/api/v1/system-admin/activity-template-presets` | `docs/features/F06.4_activity_records.md` | 341 |
| POST | `/api/v1/system-admin/activity-templates` | `docs/features/F06.1_cms_blog.md` | 760 |
| POST | `/api/v1/system-admin/affiliate-configs` | `docs/features/F09.7_advertising.md` | 98 |
| POST | `/api/v1/system-admin/affiliate-configs` | `docs/features/F09.7_advertising.md` | 161 |
| POST | `/api/v1/system-admin/announcements` | `docs/features/F10.1_admin_dashboard.md` | 542 |
| POST | `/api/v1/system-admin/batch-jobs/{_}/retry` | `docs/features/F10.1_admin_dashboard.md` | 550 |
| POST | `/api/v1/system-admin/batch-jobs/{_}/retry` | `docs/features/F10.1_admin_dashboard.md` | 978 |
| POST | `/api/v1/system-admin/discount-campaigns` | `docs/features/F10.1_admin_dashboard.md` | 503 |
| POST | `/api/v1/system-admin/error-reports/{_}/ai-analyze` | `docs/features/F10.6_error_monitoring.md` | 159 |
| POST | `/api/v1/system-admin/feature-flags` | `docs/features/F10.1_admin_dashboard.md` | 535 |
| POST | `/api/v1/system-admin/maintenance-schedules` | `docs/features/F10.1_admin_dashboard.md` | 539 |
| POST | `/api/v1/system-admin/modules` | `docs/features/F01.3_template_module.md` | 396 |
| POST | `/api/v1/system-admin/packages` | `docs/features/F10.1_admin_dashboard.md` | 499 |
| POST | `/api/v1/system-admin/safety-checks/message-presets` | `docs/features/F03.6_safety_check.md` | 276 |
| POST | `/api/v1/system-admin/safety-checks/message-presets` | `docs/features/F03.6_safety_check.md` | 968 |
| POST | `/api/v1/system-admin/seasonal-themes` | `docs/features/F10.1_admin_dashboard.md` | 520 |
| POST | `/api/v1/system-admin/storage-plans` | `docs/features/F10.1_admin_dashboard.md` | 510 |
| POST | `/api/v1/system-admin/template-wallpapers` | `docs/features/F01.4_family_team.md` | 441 |
| POST | `/api/v1/system-admin/templates` | `docs/features/F01.3_template_module.md` | 391 |
| POST | `/api/v1/system-admin/templates` | `docs/features/F01.3_template_module.md` | 801 |
| POST | `/api/v1/system-admin/templates/{_}/duplicate` | `docs/features/F01.3_template_module.md` | 404 |
| POST | `/api/v1/system-admin/templates/{_}/duplicate` | `docs/features/F01.3_template_module.md` | 1276 |
| PUT | `/api/v1/system-admin/activity-templates/{_}` | `docs/features/F06.1_cms_blog.md` | 761 |
| PUT | `/api/v1/system-admin/affiliate-configs` | `docs/features/F10.1_admin_dashboard.md` | 526 |
| PUT | `/api/v1/system-admin/beta-restriction` | `docs/features/F00.6_beta_restriction.md` | 50 |
| PUT | `/api/v1/system-admin/discount-campaigns/{_}` | `docs/features/F10.1_admin_dashboard.md` | 504 |
| PUT | `/api/v1/system-admin/feature-flags/{_}/overrides` | `docs/features/F12.2_feature_flag.md` | 118 |
| PUT | `/api/v1/system-admin/feature-flags/{_}/overrides` | `docs/features/F12.2_feature_flag.md` | 124 |
| PUT | `/api/v1/system-admin/maintenance-mode` | `docs/features/F10.1_admin_dashboard.md` | 537 |
| PUT | `/api/v1/system-admin/moderation-settings` | `docs/features/F10.1_admin_dashboard.md` | 532 |
| PUT | `/api/v1/system-admin/module-prices/{_}` | `docs/features/F10.1_admin_dashboard.md` | 497 |
| PUT | `/api/v1/system-admin/modules/level-settings/{_}` | `docs/features/F10.1_admin_dashboard.md` | 528 |
| PUT | `/api/v1/system-admin/modules/{_}` | `docs/features/F01.3_template_module.md` | 397 |
| PUT | `/api/v1/system-admin/modules/{_}/level-availability` | `docs/features/F01.3_template_module.md` | 400 |
| PUT | `/api/v1/system-admin/modules/{_}/level-availability` | `docs/features/F01.3_template_module.md` | 856 |
| PUT | `/api/v1/system-admin/modules/{_}/recommendations` | `docs/features/F01.3_template_module.md` | 403 |
| PUT | `/api/v1/system-admin/modules/{_}/recommendations` | `docs/features/F01.3_template_module.md` | 1042 |
| PUT | `/api/v1/system-admin/org-count-billing-tiers` | `docs/features/F10.1_admin_dashboard.md` | 515 |
| PUT | `/api/v1/system-admin/packages/{_}` | `docs/features/F10.1_admin_dashboard.md` | 500 |
| PUT | `/api/v1/system-admin/promotions/billing/settings` | `docs/features/F09.2_promotion_targeting.md` | 401 |
| PUT | `/api/v1/system-admin/role-permissions` | `docs/features/F10.1_admin_dashboard.md` | 530 |
| PUT | `/api/v1/system-admin/safety-checks/message-presets/{_}` | `docs/features/F03.6_safety_check.md` | 277 |
| PUT | `/api/v1/system-admin/safety-checks/message-presets/{_}` | `docs/features/F03.6_safety_check.md` | 1001 |
| PUT | `/api/v1/system-admin/safety-checks/templates/{_}` | `docs/features/F03.6_safety_check.md` | 281 |
| PUT | `/api/v1/system-admin/safety-checks/templates/{_}` | `docs/features/F03.6_safety_check.md` | 1095 |
| PUT | `/api/v1/system-admin/seasonal-themes/{_}` | `docs/features/F10.1_admin_dashboard.md` | 521 |
| PUT | `/api/v1/system-admin/storage-plans/{_}` | `docs/features/F10.1_admin_dashboard.md` | 511 |
| PUT | `/api/v1/system-admin/tax-settings` | `docs/features/F10.1_admin_dashboard.md` | 508 |
| PUT | `/api/v1/system-admin/templates/{_}` | `docs/features/F01.3_template_module.md` | 392 |
| PUT | `/api/v1/system-admin/templates/{_}/modules` | `docs/features/F01.3_template_module.md` | 394 |

### /api/v1/team/* (4 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| GET | `/api/v1/team/member-fields` | `docs/features/F06.2_member_gallery.md` | 337 |
| GET | `/api/v1/team/pages` | `docs/features/F06.2_member_gallery.md` | 321 |
| POST | `/api/v1/team/member-fields` | `docs/features/F06.2_member_gallery.md` | 338 |
| POST | `/api/v1/team/pages` | `docs/features/F06.2_member_gallery.md` | 322 |
| POST | `/api/v1/team/pages` | `docs/features/F06.2_member_gallery.md` | 365 |

### /api/v1/teams/* (273 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| DELETE | `/api/v1/teams/{_}/api-keys/{_}` | `docs/features/F09.9_webhook_api.md` | 283 |
| DELETE | `/api/v1/teams/{_}/api-keys/{_}` | `docs/features/F09.9_webhook_api.md` | 829 |
| DELETE | `/api/v1/teams/{_}/direct-mails/{_}` | `docs/features/F09.6_direct_mail.md` | 305 |
| DELETE | `/api/v1/teams/{_}/direct-mails/{_}` | `docs/features/F09.6_direct_mail.md` | 520 |
| DELETE | `/api/v1/teams/{_}/friend-feed/{_}/forward/{_}` | `docs/features/F01.5_team_friend_relationships.md` | 401 |
| DELETE | `/api/v1/teams/{_}/friend-feed/{_}/forward/{_}` | `docs/features/F01.5_team_friend_relationships.md` | 826 |
| DELETE | `/api/v1/teams/{_}/jobbers/invitations/{_}` | `docs/features/F13.1_short_term_job_matching.md` | 1656 |
| DELETE | `/api/v1/teams/{_}/jobbers/me` | `docs/features/F13.1_short_term_job_matching.md` | 263 |
| DELETE | `/api/v1/teams/{_}/jobbers/{_}` | `docs/features/F13.1_short_term_job_matching.md` | 638 |
| DELETE | `/api/v1/teams/{_}/jobbers/{_}` | `docs/features/F13.1_short_term_job_matching.md` | 1660 |
| DELETE | `/api/v1/teams/{_}/members/{_}/care-overrides/{_}` | `docs/features/F03.12_care_recipient_event_watch_notification.md` | 450 |
| DELETE | `/api/v1/teams/{_}/modules/{_}/schedule` | `docs/features/F01.3_template_module.md` | 383 |
| DELETE | `/api/v1/teams/{_}/modules/{_}/schedule` | `docs/features/F01.3_template_module.md` | 1202 |
| DELETE | `/api/v1/teams/{_}/organizations/{_}` | `docs/features/F01.2_org_team_member_role.md` | 738 |
| DELETE | `/api/v1/teams/{_}/queue/categories/{_}` | `docs/features/F03.7_queue.md` | 329 |
| DELETE | `/api/v1/teams/{_}/queue/counters/{_}` | `docs/features/F03.7_queue.md` | 333 |
| DELETE | `/api/v1/teams/{_}/reservation-blocked-times/{_}` | `docs/features/F03.4_reservation.md` | 1588 |
| DELETE | `/api/v1/teams/{_}/signage/screens/{_}` | `docs/features/F09.10_digital_signage.md` | 299 |
| DELETE | `/api/v1/teams/{_}/signage/screens/{_}/emergency` | `docs/features/F09.10_digital_signage.md` | 348 |
| DELETE | `/api/v1/teams/{_}/signage/screens/{_}/schedules/{_}` | `docs/features/F09.10_digital_signage.md` | 326 |
| DELETE | `/api/v1/teams/{_}/signage/screens/{_}/slots/{_}` | `docs/features/F09.10_digital_signage.md` | 312 |
| DELETE | `/api/v1/teams/{_}/signage/screens/{_}/tokens/{_}` | `docs/features/F09.10_digital_signage.md` | 338 |
| DELETE | `/api/v1/teams/{_}/webhooks/endpoints/{_}` | `docs/features/F09.9_webhook_api.md` | 263 |
| DELETE | `/api/v1/teams/{_}/webhooks/endpoints/{_}` | `docs/features/F09.9_webhook_api.md` | 425 |
| DELETE | `/api/v1/teams/{_}/webhooks/incoming/{_}` | `docs/features/F09.9_webhook_api.md` | 275 |
| DELETE | `/api/v1/teams/{_}/webhooks/incoming/{_}` | `docs/features/F09.9_webhook_api.md` | 694 |
| GET | `/api/v1/teams/{_}/access-requirements` | `docs/features/F08.2_payments_access_control.md` | 347 |
| GET | `/api/v1/teams/{_}/anniversaries` | `docs/features/F01.4_family_team.md` | 418 |
| GET | `/api/v1/teams/{_}/announcements` | `docs/features/F02.2_dashboard.md` | 219 |
| GET | `/api/v1/teams/{_}/announcements` | `docs/features/F02.6_announcement_widget.md` | 242 |
| GET | `/api/v1/teams/{_}/announcements` | `docs/features/F02.6_announcement_widget.md` | 261 |
| GET | `/api/v1/teams/{_}/announcements` | `docs/features/F05.1_bulletin_board.md` | 1374 |
| GET | `/api/v1/teams/{_}/announcements` | `docs/features/F06.1_cms_blog.md` | 2450 |
| GET | `/api/v1/teams/{_}/api-keys` | `docs/features/F09.9_webhook_api.md` | 280 |
| GET | `/api/v1/teams/{_}/api-keys` | `docs/features/F09.9_webhook_api.md` | 765 |
| GET | `/api/v1/teams/{_}/budget/config` | `docs/features/F08.6_budget_accounting.md` | 413 |
| GET | `/api/v1/teams/{_}/budget/fiscal-years` | `docs/features/F08.6_budget_accounting.md` | 348 |
| GET | `/api/v1/teams/{_}/budget/transactions` | `docs/features/F08.6_budget_accounting.md` | 376 |
| GET | `/api/v1/teams/{_}/budget/transactions` | `docs/features/F08.6_budget_accounting.md` | 854 |
| GET | `/api/v1/teams/{_}/bulletins/unread` | `docs/features/F02.2.1_dashboard_widget_role_visibility.md` | 568 |
| GET | `/api/v1/teams/{_}/cancellation-policies` | `docs/features/F03.11_recruitment_listing.md` | 1443 |
| GET | `/api/v1/teams/{_}/charts` | `docs/features/F07.4_chart.md` | 401 |
| GET | `/api/v1/teams/{_}/charts` | `docs/features/F07.4_chart.md` | 435 |
| GET | `/api/v1/teams/{_}/charts/{_}/intake-form` | `docs/features/F07.4_chart.md` | 408 |
| GET | `/api/v1/teams/{_}/confirmable-notification-settings` | `docs/features/F04.9_confirmable_notification.md` | 277 |
| GET | `/api/v1/teams/{_}/confirmable-notification-templates` | `docs/features/F04.9_confirmable_notification.md` | 285 |
| GET | `/api/v1/teams/{_}/confirmable-notification-templates/{_}` | `docs/features/F04.9_confirmable_notification.md` | 287 |
| GET | `/api/v1/teams/{_}/confirmable-notifications` | `docs/features/F04.9_confirmable_notification.md` | 280 |
| GET | `/api/v1/teams/{_}/content-payment-gates` | `docs/features/F08.2_payments_access_control.md` | 363 |
| GET | `/api/v1/teams/{_}/content-payment-gates` | `docs/features/F08.2_payments_access_control.md` | 635 |
| GET | `/api/v1/teams/{_}/corkboards` | `docs/features/F09.8_corkboard.md` | 235 |
| GET | `/api/v1/teams/{_}/direct-mail-templates` | `docs/features/F09.6_direct_mail.md` | 329 |
| GET | `/api/v1/teams/{_}/direct-mails` | `docs/features/F09.6_direct_mail.md` | 302 |
| GET | `/api/v1/teams/{_}/direct-mails` | `docs/features/F09.6_direct_mail.md` | 351 |
| GET | `/api/v1/teams/{_}/direct-mails/quota` | `docs/features/F09.6_direct_mail.md` | 317 |
| GET | `/api/v1/teams/{_}/direct-mails/stats` | `docs/features/F09.6_direct_mail.md` | 316 |
| GET | `/api/v1/teams/{_}/direct-mails/stats` | `docs/features/F09.6_direct_mail.md` | 831 |
| GET | `/api/v1/teams/{_}/direct-mails/{_}/preview` | `docs/features/F09.6_direct_mail.md` | 311 |
| GET | `/api/v1/teams/{_}/direct-mails/{_}/preview` | `docs/features/F09.6_direct_mail.md` | 647 |
| GET | `/api/v1/teams/{_}/duties` | `docs/features/F01.4_family_team.md` | 409 |
| GET | `/api/v1/teams/{_}/equipment` | `docs/features/F07.3_equipment.md` | 149 |
| GET | `/api/v1/teams/{_}/equipment` | `docs/features/F07.3_equipment.md` | 318 |
| GET | `/api/v1/teams/{_}/equipment/trending` | `docs/features/F09.12_team_equipment_ranking.md` | 176 |
| GET | `/api/v1/teams/{_}/equipment/trending` | `docs/features/F09.12_team_equipment_ranking.md` | 191 |
| GET | `/api/v1/teams/{_}/event-categories` | `docs/features/F03.10_annual_event_plan.md` | 203 |
| GET | `/api/v1/teams/{_}/events` | `docs/features/F03.8_event_management.md` | 383 |
| GET | `/api/v1/teams/{_}/events/{_}/care-participants` | `docs/features/F03.12_care_recipient_event_watch_notification.md` | 463 |
| GET | `/api/v1/teams/{_}/events/{_}/stats` | `docs/features/F03.8_event_management.md` | 390 |
| GET | `/api/v1/teams/{_}/facilities` | `docs/features/F09.5_facility_booking.md` | 468 |
| GET | `/api/v1/teams/{_}/facilities` | `docs/features/F09.5_facility_booking.md` | 502 |
| GET | `/api/v1/teams/{_}/facilities/bookings` | `docs/features/F09.5_facility_booking.md` | 483 |
| GET | `/api/v1/teams/{_}/facilities/bookings` | `docs/features/F09.5_facility_booking.md` | 1326 |
| GET | `/api/v1/teams/{_}/form-templates` | `docs/features/F05.7_form_builder.md` | 365 |
| GET | `/api/v1/teams/{_}/friend-feed` | `docs/features/F01.5_team_friend_relationships.md` | 398 |
| GET | `/api/v1/teams/{_}/friend-feed` | `docs/features/F01.5_team_friend_relationships.md` | 701 |
| GET | `/api/v1/teams/{_}/friend-folders` | `docs/features/F01.5_team_friend_relationships.md` | 392 |
| GET | `/api/v1/teams/{_}/friend-folders` | `docs/features/F01.5_team_friend_relationships.md` | 578 |
| GET | `/api/v1/teams/{_}/friend-notifications` | `docs/features/F01.5_team_friend_relationships.md` | 399 |
| GET | `/api/v1/teams/{_}/friend-notifications` | `docs/features/F01.5_team_friend_relationships.md` | 756 |
| GET | `/api/v1/teams/{_}/friends` | `docs/features/F01.5_team_friend_relationships.md` | 389 |
| GET | `/api/v1/teams/{_}/friends` | `docs/features/F01.5_team_friend_relationships.md` | 514 |
| GET | `/api/v1/teams/{_}/friends/pending` | `docs/features/F01.5_team_friend_relationships.md` | 390 |
| GET | `/api/v1/teams/{_}/gamification/badges` | `docs/features/F04.7_gamification.md` | 384 |
| GET | `/api/v1/teams/{_}/gamification/config` | `docs/features/F04.7_gamification.md` | 368 |
| GET | `/api/v1/teams/{_}/gamification/point-rules` | `docs/features/F04.7_gamification.md` | 376 |
| GET | `/api/v1/teams/{_}/gamification/rankings` | `docs/features/F04.7_gamification.md` | 400 |
| GET | `/api/v1/teams/{_}/gamification/rankings` | `docs/features/F04.7_gamification.md` | 607 |
| GET | `/api/v1/teams/{_}/incidents` | `docs/features/F07.6_incident_management.md` | 445 |
| GET | `/api/v1/teams/{_}/incidents` | `docs/features/F07.6_incident_management.md` | 900 |
| GET | `/api/v1/teams/{_}/incidents/categories` | `docs/features/F07.6_incident_management.md` | 435 |
| GET | `/api/v1/teams/{_}/incidents/maintenance-schedules` | `docs/features/F07.6_incident_management.md` | 493 |
| GET | `/api/v1/teams/{_}/incidents/stats` | `docs/features/F07.6_incident_management.md` | 504 |
| GET | `/api/v1/teams/{_}/incidents/stats` | `docs/features/F07.6_incident_management.md` | 837 |
| GET | `/api/v1/teams/{_}/jobbers` | `docs/features/F13.1_short_term_job_matching.md` | 1659 |
| GET | `/api/v1/teams/{_}/jobbers/invitations` | `docs/features/F13.1_short_term_job_matching.md` | 1655 |
| GET | `/api/v1/teams/{_}/jobs` | `docs/features/F13.1_short_term_job_matching.md` | 2645 |
| GET | `/api/v1/teams/{_}/jobs` | `docs/features/F13.1_short_term_job_matching.md` | 2645 |
| GET | `/api/v1/teams/{_}/jobs/history` | `docs/features/F13.1_short_term_job_matching.md` | 1640 |
| GET | `/api/v1/teams/{_}/jobs/history` | `docs/features/F13.1_short_term_job_matching.md` | 1827 |
| GET | `/api/v1/teams/{_}/jobs/history/export.csv` | `docs/features/F13.1_short_term_job_matching.md` | 1641 |
| GET | `/api/v1/teams/{_}/matching/ng-teams` | `docs/features/F08.1_matching.md` | 348 |
| GET | `/api/v1/teams/{_}/matching/ng-teams` | `docs/features/F08.1_matching.md` | 854 |
| GET | `/api/v1/teams/{_}/matching/notification-preferences` | `docs/features/F08.1_matching.md` | 354 |
| GET | `/api/v1/teams/{_}/matching/notification-preferences` | `docs/features/F08.1_matching.md` | 960 |
| GET | `/api/v1/teams/{_}/matching/templates` | `docs/features/F08.1_matching.md` | 356 |
| GET | `/api/v1/teams/{_}/matching/templates` | `docs/features/F08.1_matching.md` | 998 |
| GET | `/api/v1/teams/{_}/members/care-recipients` | `docs/features/F03.12_care_recipient_event_watch_notification.md` | 451 |
| GET | `/api/v1/teams/{_}/members/{_}/care-overrides` | `docs/features/F03.12_care_recipient_event_watch_notification.md` | 448 |
| GET | `/api/v1/teams/{_}/module-change-history` | `docs/features/F01.3_template_module.md` | 379 |
| GET | `/api/v1/teams/{_}/modules` | `docs/features/F01.3_template_module.md` | 373 |
| GET | `/api/v1/teams/{_}/modules` | `docs/features/F01.3_template_module.md` | 584 |
| GET | `/api/v1/teams/{_}/modules/snapshots` | `docs/features/F01.3_template_module.md` | 385 |
| GET | `/api/v1/teams/{_}/modules/snapshots` | `docs/features/F01.3_template_module.md` | 1254 |
| GET | `/api/v1/teams/{_}/modules/{_}/impact` | `docs/features/F01.3_template_module.md` | 378 |
| GET | `/api/v1/teams/{_}/modules/{_}/impact` | `docs/features/F01.3_template_module.md` | 915 |
| GET | `/api/v1/teams/{_}/my-tickets` | `docs/features/F08.5_ticket_book.md` | 240 |
| GET | `/api/v1/teams/{_}/my-tickets` | `docs/features/F08.5_ticket_book.md` | 614 |
| GET | `/api/v1/teams/{_}/onboarding/templates` | `docs/features/F02.4_onboarding.md` | 350 |
| GET | `/api/v1/teams/{_}/org-invites` | `docs/features/F01.2_org_team_member_role.md` | 735 |
| GET | `/api/v1/teams/{_}/parking/applications` | `docs/features/F09.3_parking.md` | 642 |
| GET | `/api/v1/teams/{_}/parking/applications` | `docs/features/F09.3_parking.md` | 1424 |
| GET | `/api/v1/teams/{_}/parking/listings` | `docs/features/F09.3_parking.md` | 648 |
| GET | `/api/v1/teams/{_}/parking/listings` | `docs/features/F09.3_parking.md` | 1688 |
| GET | `/api/v1/teams/{_}/parking/subleases` | `docs/features/F09.3_parking.md` | 674 |
| GET | `/api/v1/teams/{_}/parking/watchlist` | `docs/features/F09.3_parking.md` | 664 |
| GET | `/api/v1/teams/{_}/payment-items` | `docs/features/F08.2_payments_access_control.md` | 335 |
| GET | `/api/v1/teams/{_}/penalty-settings` | `docs/features/F03.11_recruitment_listing.md` | 1471 |
| GET | `/api/v1/teams/{_}/performance/metrics` | `docs/features/F07.2_performance.md` | 238 |
| GET | `/api/v1/teams/{_}/presence/icons` | `docs/features/F01.4_family_team.md` | 427 |
| GET | `/api/v1/teams/{_}/projects` | `docs/features/F02.2.1_dashboard_widget_role_visibility.md` | 567 |
| GET | `/api/v1/teams/{_}/projects` | `docs/features/F02.3_todo_project.md` | 264 |
| GET | `/api/v1/teams/{_}/projects` | `docs/features/F02.3_todo_project.md` | 298 |
| GET | `/api/v1/teams/{_}/queue/categories` | `docs/features/F03.7_queue.md` | 326 |
| GET | `/api/v1/teams/{_}/queue/categories/{_}/qr-code` | `docs/features/F03.7_queue.md` | 335 |
| GET | `/api/v1/teams/{_}/queue/counters` | `docs/features/F03.7_queue.md` | 330 |
| GET | `/api/v1/teams/{_}/queue/counters/{_}/qr-code` | `docs/features/F03.7_queue.md` | 337 |
| GET | `/api/v1/teams/{_}/queue/display-board` | `docs/features/F03.7_queue.md` | 357 |
| GET | `/api/v1/teams/{_}/queue/settings` | `docs/features/F03.7_queue.md` | 353 |
| GET | `/api/v1/teams/{_}/queue/stats` | `docs/features/F03.7_queue.md` | 355 |
| GET | `/api/v1/teams/{_}/queue/status` | `docs/features/F03.7_queue.md` | 338 |
| GET | `/api/v1/teams/{_}/queue/status` | `docs/features/F03.7_queue.md` | 361 |
| GET | `/api/v1/teams/{_}/queue/tickets/history` | `docs/features/F03.7_queue.md` | 356 |
| GET | `/api/v1/teams/{_}/recruitment-listings` | `docs/features/F03.11_recruitment_listing.md` | 1363 |
| GET | `/api/v1/teams/{_}/recruitment-subcategories` | `docs/features/F03.11_recruitment_listing.md` | 1421 |
| GET | `/api/v1/teams/{_}/reservation-blocked-times` | `docs/features/F03.4_reservation.md` | 1501 |
| GET | `/api/v1/teams/{_}/reservation-business-hours` | `docs/features/F03.4_reservation.md` | 1440 |
| GET | `/api/v1/teams/{_}/reservation-lines` | `docs/features/F03.4_reservation.md` | 332 |
| GET | `/api/v1/teams/{_}/reservation-lines` | `docs/features/F03.4_reservation.md` | 385 |
| GET | `/api/v1/teams/{_}/reservation-settings` | `docs/features/F03.4_reservation.md` | 365 |
| GET | `/api/v1/teams/{_}/reservation-settings` | `docs/features/F03.4_reservation.md` | 1612 |
| GET | `/api/v1/teams/{_}/reservation-slots` | `docs/features/F03.4_reservation.md` | 336 |
| GET | `/api/v1/teams/{_}/reservation-slots` | `docs/features/F03.4_reservation.md` | 496 |
| GET | `/api/v1/teams/{_}/reservation-slots/monthly-summary` | `docs/features/F03.4_reservation.md` | 344 |
| GET | `/api/v1/teams/{_}/reservation-slots/monthly-summary` | `docs/features/F03.4_reservation.md` | 2071 |
| GET | `/api/v1/teams/{_}/reservations` | `docs/features/F03.4_reservation.md` | 345 |
| GET | `/api/v1/teams/{_}/reservations` | `docs/features/F03.4_reservation.md` | 919 |
| GET | `/api/v1/teams/{_}/role-aliases` | `docs/features/F01.4_family_team.md` | 375 |
| GET | `/api/v1/teams/{_}/schedules` | `docs/features/F03.1_schedule_shared.md` | 421 |
| GET | `/api/v1/teams/{_}/schedules` | `docs/features/F03.1_schedule_shared.md` | 547 |
| GET | `/api/v1/teams/{_}/schedules` | `docs/features/F03.1_schedule_shared.md` | 820 |
| GET | `/api/v1/teams/{_}/schedules/annual` | `docs/features/F03.10_annual_event_plan.md` | 213 |
| GET | `/api/v1/teams/{_}/schedules/annual` | `docs/features/F03.10_annual_event_plan.md` | 228 |
| GET | `/api/v1/teams/{_}/shopping-lists` | `docs/features/F01.4_family_team.md` | 388 |
| GET | `/api/v1/teams/{_}/signage/screens` | `docs/features/F09.10_digital_signage.md` | 295 |
| GET | `/api/v1/teams/{_}/signage/screens/{_}` | `docs/features/F09.10_digital_signage.md` | 297 |
| GET | `/api/v1/teams/{_}/signage/screens/{_}/emergency/history` | `docs/features/F09.10_digital_signage.md` | 349 |
| GET | `/api/v1/teams/{_}/signage/screens/{_}/schedules` | `docs/features/F09.10_digital_signage.md` | 323 |
| GET | `/api/v1/teams/{_}/signage/screens/{_}/slots` | `docs/features/F09.10_digital_signage.md` | 309 |
| GET | `/api/v1/teams/{_}/signage/screens/{_}/tokens` | `docs/features/F09.10_digital_signage.md` | 335 |
| GET | `/api/v1/teams/{_}/storage` | `docs/features/F05.5_file_sharing.md` | 361 |
| GET | `/api/v1/teams/{_}/storage` | `docs/features/F05.5_file_sharing.md` | 1016 |
| GET | `/api/v1/teams/{_}/summary` | `docs/features/F01.5_team_friend_relationships.md` | 348 |
| GET | `/api/v1/teams/{_}/summary` | `docs/features/F04.4_social_profiles.md` | 61 |
| GET | `/api/v1/teams/{_}/template-diff` | `docs/features/F01.3_template_module.md` | 377 |
| GET | `/api/v1/teams/{_}/template-diff` | `docs/features/F01.3_template_module.md` | 940 |
| GET | `/api/v1/teams/{_}/ticket-books` | `docs/features/F08.5_ticket_book.md` | 250 |
| GET | `/api/v1/teams/{_}/ticket-products` | `docs/features/F08.5_ticket_book.md` | 231 |
| GET | `/api/v1/teams/{_}/timeline` | `docs/features/F02.2.1_dashboard_widget_role_visibility.md` | 570 |
| GET | `/api/v1/teams/{_}/timetable-terms` | `docs/features/F03.9_timetable.md` | 296 |
| GET | `/api/v1/teams/{_}/timetables` | `docs/features/F03.9_timetable.md` | 304 |
| GET | `/api/v1/teams/{_}/timetables/{_}/export/pdf` | `docs/features/F03.9_timetable.md` | 335 |
| GET | `/api/v1/teams/{_}/todo-status-labels` | `docs/features/F02.3.1_todo_status_labels_and_handoff.md` | 203 |
| GET | `/api/v1/teams/{_}/todo-status-labels` | `docs/features/F02.3.1_todo_status_labels_and_handoff.md` | 238 |
| GET | `/api/v1/teams/{_}/todos` | `docs/features/F02.2.1_dashboard_widget_role_visibility.md` | 566 |
| GET | `/api/v1/teams/{_}/todos` | `docs/features/F02.3_todo_project.md` | 281 |
| GET | `/api/v1/teams/{_}/user-penalties` | `docs/features/F03.11_recruitment_listing.md` | 1473 |
| GET | `/api/v1/teams/{_}/webhooks/endpoints` | `docs/features/F09.9_webhook_api.md` | 259 |
| GET | `/api/v1/teams/{_}/webhooks/endpoints` | `docs/features/F09.9_webhook_api.md` | 344 |
| GET | `/api/v1/teams/{_}/webhooks/endpoints/{_}` | `docs/features/F09.9_webhook_api.md` | 261 |
| GET | `/api/v1/teams/{_}/webhooks/endpoints/{_}` | `docs/features/F09.9_webhook_api.md` | 374 |
| GET | `/api/v1/teams/{_}/webhooks/endpoints/{_}/logs` | `docs/features/F09.9_webhook_api.md` | 265 |
| GET | `/api/v1/teams/{_}/webhooks/endpoints/{_}/logs` | `docs/features/F09.9_webhook_api.md` | 474 |
| GET | `/api/v1/teams/{_}/webhooks/incoming` | `docs/features/F09.9_webhook_api.md` | 272 |
| GET | `/api/v1/teams/{_}/webhooks/incoming` | `docs/features/F09.9_webhook_api.md` | 597 |
| GET | `/api/v1/teams/{_}/workers/{_}/history` | `docs/features/F13.1_short_term_job_matching.md` | 1642 |
| GET | `/api/v1/teams/{_}/workers/{_}/history` | `docs/features/F13.1_short_term_job_matching.md` | 1855 |
| GET | `/api/v1/teams/{_}/workers/{_}/history` | `docs/features/F13.1_short_term_job_matching.md` | 2143 |
| GET | `/api/v1/teams/{_}/workflow-requests` | `docs/features/F05.6_workflow_approval.md` | 383 |
| GET | `/api/v1/teams/{_}/workflow-templates` | `docs/features/F05.6_workflow_approval.md` | 370 |
| PATCH | `/api/v1/teams/{_}/api-keys/{_}` | `docs/features/F09.9_webhook_api.md` | 282 |
| PATCH | `/api/v1/teams/{_}/api-keys/{_}` | `docs/features/F09.9_webhook_api.md` | 796 |
| PATCH | `/api/v1/teams/{_}/budget/config` | `docs/features/F08.6_budget_accounting.md` | 415 |
| PATCH | `/api/v1/teams/{_}/budget/config` | `docs/features/F08.6_budget_accounting.md` | 908 |
| PATCH | `/api/v1/teams/{_}/confirmable-notification-templates/{_}` | `docs/features/F04.9_confirmable_notification.md` | 288 |
| PATCH | `/api/v1/teams/{_}/direct-mails/{_}/schedule` | `docs/features/F09.6_direct_mail.md` | 314 |
| PATCH | `/api/v1/teams/{_}/direct-mails/{_}/schedule` | `docs/features/F09.6_direct_mail.md` | 751 |
| PATCH | `/api/v1/teams/{_}/queue/counters/{_}/accepting` | `docs/features/F03.7_queue.md` | 351 |
| PATCH | `/api/v1/teams/{_}/queue/tickets/call-next` | `docs/features/F03.7_queue.md` | 352 |
| PATCH | `/api/v1/teams/{_}/queue/tickets/call-next` | `docs/features/F03.7_queue.md` | 566 |
| PATCH | `/api/v1/teams/{_}/queue/tickets/{_}/call` | `docs/features/F03.7_queue.md` | 345 |
| PATCH | `/api/v1/teams/{_}/queue/tickets/{_}/call` | `docs/features/F03.7_queue.md` | 540 |
| PATCH | `/api/v1/teams/{_}/queue/tickets/{_}/complete` | `docs/features/F03.7_queue.md` | 347 |
| PATCH | `/api/v1/teams/{_}/queue/tickets/{_}/hold` | `docs/features/F03.7_queue.md` | 349 |
| PATCH | `/api/v1/teams/{_}/queue/tickets/{_}/serve` | `docs/features/F03.7_queue.md` | 346 |
| PATCH | `/api/v1/teams/{_}/queue/tickets/{_}/skip` | `docs/features/F03.7_queue.md` | 348 |
| PATCH | `/api/v1/teams/{_}/queue/tickets/{_}/transfer` | `docs/features/F03.7_queue.md` | 350 |
| PATCH | `/api/v1/teams/{_}/reservation-slots/{_}/close` | `docs/features/F03.4_reservation.md` | 740 |
| PATCH | `/api/v1/teams/{_}/reservation-slots/{_}/reopen` | `docs/features/F03.4_reservation.md` | 777 |
| PATCH | `/api/v1/teams/{_}/reservations/{_}/cancel` | `docs/features/F03.4_reservation.md` | 1050 |
| PATCH | `/api/v1/teams/{_}/reservations/{_}/complete` | `docs/features/F03.4_reservation.md` | 1081 |
| PATCH | `/api/v1/teams/{_}/reservations/{_}/confirm` | `docs/features/F03.4_reservation.md` | 1118 |
| PATCH | `/api/v1/teams/{_}/reservations/{_}/no-show` | `docs/features/F03.4_reservation.md` | 1204 |
| PATCH | `/api/v1/teams/{_}/reservations/{_}/reject` | `docs/features/F03.4_reservation.md` | 1162 |
| PATCH | `/api/v1/teams/{_}/reservations/{_}/reschedule` | `docs/features/F03.4_reservation.md` | 1243 |
| PATCH | `/api/v1/teams/{_}/webhooks/endpoints/{_}` | `docs/features/F09.9_webhook_api.md` | 262 |
| PATCH | `/api/v1/teams/{_}/webhooks/endpoints/{_}` | `docs/features/F09.9_webhook_api.md` | 390 |
| PATCH | `/api/v1/teams/{_}/webhooks/incoming/{_}` | `docs/features/F09.9_webhook_api.md` | 274 |
| PATCH | `/api/v1/teams/{_}/webhooks/incoming/{_}` | `docs/features/F09.9_webhook_api.md` | 676 |
| POST | `/api/v1/teams` | `docs/features/F01.2_org_team_member_role.md` | 679 |
| POST | `/api/v1/teams` | `docs/features/F01.2_org_team_member_role.md` | 765 |
| POST | `/api/v1/teams/{_}/anniversaries` | `docs/features/F01.4_family_team.md` | 419 |
| POST | `/api/v1/teams/{_}/announcements` | `docs/features/F02.6_announcement_widget.md` | 243 |
| POST | `/api/v1/teams/{_}/announcements` | `docs/features/F02.6_announcement_widget.md` | 364 |
| POST | `/api/v1/teams/{_}/api-keys` | `docs/features/F09.9_webhook_api.md` | 281 |
| POST | `/api/v1/teams/{_}/api-keys` | `docs/features/F09.9_webhook_api.md` | 710 |
| POST | `/api/v1/teams/{_}/budget/fiscal-years` | `docs/features/F08.6_budget_accounting.md` | 350 |
| POST | `/api/v1/teams/{_}/budget/fiscal-years` | `docs/features/F08.6_budget_accounting.md` | 420 |
| POST | `/api/v1/teams/{_}/cancellation-policies` | `docs/features/F03.11_recruitment_listing.md` | 1442 |
| POST | `/api/v1/teams/{_}/charts` | `docs/features/F07.4_chart.md` | 402 |
| POST | `/api/v1/teams/{_}/charts` | `docs/features/F07.4_chart.md` | 454 |
| POST | `/api/v1/teams/{_}/coin-toss` | `docs/features/F01.4_family_team.md` | 381 |
| POST | `/api/v1/teams/{_}/coin-toss` | `docs/features/F01.4_family_team.md` | 633 |
| POST | `/api/v1/teams/{_}/confirmable-notification-templates` | `docs/features/F04.9_confirmable_notification.md` | 286 |
| POST | `/api/v1/teams/{_}/confirmable-notifications` | `docs/features/F04.9_confirmable_notification.md` | 279 |
| POST | `/api/v1/teams/{_}/confirmable-notifications` | `docs/features/F04.9_confirmable_notification.md` | 301 |
| POST | `/api/v1/teams/{_}/corkboards` | `docs/features/F09.8_corkboard.md` | 238 |
| POST | `/api/v1/teams/{_}/direct-mail-templates` | `docs/features/F09.6_direct_mail.md` | 330 |
| POST | `/api/v1/teams/{_}/direct-mails` | `docs/features/F09.6_direct_mail.md` | 301 |
| POST | `/api/v1/teams/{_}/direct-mails` | `docs/features/F09.6_direct_mail.md` | 389 |
| POST | `/api/v1/teams/{_}/direct-mails/images` | `docs/features/F09.6_direct_mail.md` | 337 |
| POST | `/api/v1/teams/{_}/direct-mails/preview-recipients` | `docs/features/F09.6_direct_mail.md` | 315 |
| POST | `/api/v1/teams/{_}/direct-mails/preview-recipients` | `docs/features/F09.6_direct_mail.md` | 780 |
| POST | `/api/v1/teams/{_}/direct-mails/{_}/duplicate` | `docs/features/F09.6_direct_mail.md` | 310 |
| POST | `/api/v1/teams/{_}/direct-mails/{_}/duplicate` | `docs/features/F09.6_direct_mail.md` | 639 |
| POST | `/api/v1/teams/{_}/direct-mails/{_}/resend-to-unopened` | `docs/features/F09.6_direct_mail.md` | 313 |
| POST | `/api/v1/teams/{_}/direct-mails/{_}/resend-to-unopened` | `docs/features/F09.6_direct_mail.md` | 702 |
| POST | `/api/v1/teams/{_}/direct-mails/{_}/test-send` | `docs/features/F09.6_direct_mail.md` | 309 |
| POST | `/api/v1/teams/{_}/direct-mails/{_}/test-send` | `docs/features/F09.6_direct_mail.md` | 595 |
| POST | `/api/v1/teams/{_}/duties` | `docs/features/F01.4_family_team.md` | 410 |
| POST | `/api/v1/teams/{_}/equipment` | `docs/features/F07.3_equipment.md` | 151 |
| POST | `/api/v1/teams/{_}/equipment` | `docs/features/F07.3_equipment.md` | 187 |
| POST | `/api/v1/teams/{_}/event-categories` | `docs/features/F03.10_annual_event_plan.md` | 204 |
| POST | `/api/v1/teams/{_}/events` | `docs/features/F03.8_event_management.md` | 382 |
| POST | `/api/v1/teams/{_}/events` | `docs/features/F03.8_event_management.md` | 466 |
| POST | `/api/v1/teams/{_}/events/{_}/care-participants/{_}/notify-watcher` | `docs/features/F03.12_care_recipient_event_watch_notification.md` | 464 |
| POST | `/api/v1/teams/{_}/events/{_}/complete` | `docs/features/F03.8_event_management.md` | 389 |
| POST | `/api/v1/teams/{_}/events/{_}/roll-call` | `docs/features/F03.12_care_recipient_event_watch_notification.md` | 960 |
| POST | `/api/v1/teams/{_}/facilities` | `docs/features/F09.5_facility_booking.md` | 469 |
| POST | `/api/v1/teams/{_}/facilities` | `docs/features/F09.5_facility_booking.md` | 557 |
| POST | `/api/v1/teams/{_}/facilities/bookings` | `docs/features/F09.5_facility_booking.md` | 484 |
| POST | `/api/v1/teams/{_}/facilities/bookings` | `docs/features/F09.5_facility_booking.md` | 1404 |
| POST | `/api/v1/teams/{_}/form-templates` | `docs/features/F05.7_form_builder.md` | 367 |
| POST | `/api/v1/teams/{_}/forms/templates` | `docs/features/F05.7_form_builder.md` | 402 |
| POST | `/api/v1/teams/{_}/friend-folders` | `docs/features/F01.5_team_friend_relationships.md` | 393 |
| POST | `/api/v1/teams/{_}/friend-folders` | `docs/features/F01.5_team_friend_relationships.md` | 605 |
| POST | `/api/v1/teams/{_}/gamification/badges` | `docs/features/F04.7_gamification.md` | 385 |
| POST | `/api/v1/teams/{_}/gamification/badges` | `docs/features/F04.7_gamification.md` | 515 |
| POST | `/api/v1/teams/{_}/gamification/badges/upload-icon` | `docs/features/F04.7_gamification.md` | 389 |
| POST | `/api/v1/teams/{_}/gamification/point-rules` | `docs/features/F04.7_gamification.md` | 377 |
| POST | `/api/v1/teams/{_}/gamification/point-rules` | `docs/features/F04.7_gamification.md` | 468 |
| POST | `/api/v1/teams/{_}/incidents` | `docs/features/F07.6_incident_management.md` | 449 |
| POST | `/api/v1/teams/{_}/incidents` | `docs/features/F07.6_incident_management.md` | 554 |
| POST | `/api/v1/teams/{_}/incidents/categories` | `docs/features/F07.6_incident_management.md` | 437 |
| POST | `/api/v1/teams/{_}/incidents/categories` | `docs/features/F07.6_incident_management.md` | 509 |
| POST | `/api/v1/teams/{_}/incidents/maintenance-schedules` | `docs/features/F07.6_incident_management.md` | 495 |
| POST | `/api/v1/teams/{_}/incidents/maintenance-schedules` | `docs/features/F07.6_incident_management.md` | 781 |
| POST | `/api/v1/teams/{_}/jobbers/invite` | `docs/features/F13.1_short_term_job_matching.md` | 638 |
| POST | `/api/v1/teams/{_}/jobbers/invite` | `docs/features/F13.1_short_term_job_matching.md` | 1654 |
| POST | `/api/v1/teams/{_}/jobbers/invite` | `docs/features/F13.1_short_term_job_matching.md` | 1883 |
| POST | `/api/v1/teams/{_}/matching/ng-teams` | `docs/features/F08.1_matching.md` | 349 |
| POST | `/api/v1/teams/{_}/matching/ng-teams` | `docs/features/F08.1_matching.md` | 873 |
| POST | `/api/v1/teams/{_}/matching/templates` | `docs/features/F08.1_matching.md` | 357 |
| POST | `/api/v1/teams/{_}/matching/templates` | `docs/features/F08.1_matching.md` | 1026 |
| POST | `/api/v1/teams/{_}/modules/copy-from` | `docs/features/F01.3_template_module.md` | 380 |
| POST | `/api/v1/teams/{_}/modules/copy-from` | `docs/features/F01.3_template_module.md` | 1105 |
| POST | `/api/v1/teams/{_}/modules/rollback` | `docs/features/F01.3_template_module.md` | 384 |
| POST | `/api/v1/teams/{_}/modules/rollback` | `docs/features/F01.3_template_module.md` | 1219 |
| POST | `/api/v1/teams/{_}/modules/{_}/schedule` | `docs/features/F01.3_template_module.md` | 382 |
| POST | `/api/v1/teams/{_}/modules/{_}/schedule` | `docs/features/F01.3_template_module.md` | 1169 |
| POST | `/api/v1/teams/{_}/modules/{_}/trial` | `docs/features/F01.3_template_module.md` | 381 |
| POST | `/api/v1/teams/{_}/modules/{_}/trial` | `docs/features/F01.3_template_module.md` | 1140 |
| POST | `/api/v1/teams/{_}/onboarding/templates` | `docs/features/F02.4_onboarding.md` | 352 |
| POST | `/api/v1/teams/{_}/onboarding/templates` | `docs/features/F02.4_onboarding.md` | 382 |
| POST | `/api/v1/teams/{_}/org-invites/{_}/accept` | `docs/features/F01.2_org_team_member_role.md` | 736 |
| POST | `/api/v1/teams/{_}/org-invites/{_}/reject` | `docs/features/F01.2_org_team_member_role.md` | 737 |
| POST | `/api/v1/teams/{_}/parking/applications` | `docs/features/F09.3_parking.md` | 643 |
| POST | `/api/v1/teams/{_}/parking/applications` | `docs/features/F09.3_parking.md` | 1478 |
| POST | `/api/v1/teams/{_}/parking/listings` | `docs/features/F09.3_parking.md` | 649 |
| POST | `/api/v1/teams/{_}/parking/listings` | `docs/features/F09.3_parking.md` | 1736 |
| POST | `/api/v1/teams/{_}/parking/subleases` | `docs/features/F09.3_parking.md` | 675 |
| POST | `/api/v1/teams/{_}/parking/subleases` | `docs/features/F09.3_parking.md` | 2347 |
| POST | `/api/v1/teams/{_}/parking/watchlist` | `docs/features/F09.3_parking.md` | 665 |
| POST | `/api/v1/teams/{_}/parking/watchlist` | `docs/features/F09.3_parking.md` | 2231 |
| POST | `/api/v1/teams/{_}/payment-items` | `docs/features/F08.2_payments_access_control.md` | 336 |
| POST | `/api/v1/teams/{_}/payment-items` | `docs/features/F08.2_payments_access_control.md` | 380 |
| POST | `/api/v1/teams/{_}/performance/metrics` | `docs/features/F07.2_performance.md` | 239 |
| POST | `/api/v1/teams/{_}/performance/metrics` | `docs/features/F07.2_performance.md` | 261 |
| POST | `/api/v1/teams/{_}/performance/records` | `docs/features/F07.2_performance.md` | 242 |
| POST | `/api/v1/teams/{_}/projects` | `docs/features/F02.3_todo_project.md` | 265 |
| POST | `/api/v1/teams/{_}/queue/categories` | `docs/features/F03.7_queue.md` | 327 |
| POST | `/api/v1/teams/{_}/queue/categories/{_}/qr-code` | `docs/features/F03.7_queue.md` | 334 |
| POST | `/api/v1/teams/{_}/queue/counters` | `docs/features/F03.7_queue.md` | 331 |
| POST | `/api/v1/teams/{_}/queue/counters/{_}/qr-code` | `docs/features/F03.7_queue.md` | 336 |
| POST | `/api/v1/teams/{_}/queue/counters/{_}/tickets/admin` | `docs/features/F03.7_queue.md` | 341 |
| POST | `/api/v1/teams/{_}/recruitment-listings` | `docs/features/F03.11_recruitment_listing.md` | 1361 |
| POST | `/api/v1/teams/{_}/recruitment-subcategories` | `docs/features/F03.11_recruitment_listing.md` | 1420 |
| POST | `/api/v1/teams/{_}/reservation-blocked-times` | `docs/features/F03.4_reservation.md` | 1539 |
| POST | `/api/v1/teams/{_}/reservation-lines` | `docs/features/F03.4_reservation.md` | 333 |
| POST | `/api/v1/teams/{_}/reservation-lines` | `docs/features/F03.4_reservation.md` | 408 |
| POST | `/api/v1/teams/{_}/reservation-slots` | `docs/features/F03.4_reservation.md` | 337 |
| POST | `/api/v1/teams/{_}/reservation-slots` | `docs/features/F03.4_reservation.md` | 549 |
| POST | `/api/v1/teams/{_}/reservation-slots/bulk` | `docs/features/F03.4_reservation.md` | 355 |
| POST | `/api/v1/teams/{_}/reservations` | `docs/features/F03.4_reservation.md` | 346 |
| POST | `/api/v1/teams/{_}/reservations` | `docs/features/F03.4_reservation.md` | 842 |
| POST | `/api/v1/teams/{_}/reservations/{_}/reject` | `docs/features/F03.4_reservation.md` | 351 |
| POST | `/api/v1/teams/{_}/schedules` | `docs/features/F03.10_annual_event_plan.md` | 129 |
| POST | `/api/v1/teams/{_}/schedules` | `docs/features/F03.1_schedule_shared.md` | 422 |
| POST | `/api/v1/teams/{_}/schedules` | `docs/features/F03.1_schedule_shared.md` | 464 |
| POST | `/api/v1/teams/{_}/schedules` | `docs/features/F03.1_schedule_shared.md` | 826 |
| POST | `/api/v1/teams/{_}/shopping-lists` | `docs/features/F01.4_family_team.md` | 389 |
| POST | `/api/v1/teams/{_}/signage/screens` | `docs/features/F09.10_digital_signage.md` | 296 |
| POST | `/api/v1/teams/{_}/signage/screens` | `docs/features/F09.10_digital_signage.md` | 369 |
| POST | `/api/v1/teams/{_}/signage/screens/{_}/emergency` | `docs/features/F09.10_digital_signage.md` | 347 |
| POST | `/api/v1/teams/{_}/signage/screens/{_}/emergency` | `docs/features/F09.10_digital_signage.md` | 520 |
| POST | `/api/v1/teams/{_}/signage/screens/{_}/schedules` | `docs/features/F09.10_digital_signage.md` | 324 |
| POST | `/api/v1/teams/{_}/signage/screens/{_}/slots` | `docs/features/F09.10_digital_signage.md` | 310 |
| POST | `/api/v1/teams/{_}/signage/screens/{_}/slots` | `docs/features/F09.10_digital_signage.md` | 427 |
| POST | `/api/v1/teams/{_}/signage/screens/{_}/tokens` | `docs/features/F09.10_digital_signage.md` | 336 |
| POST | `/api/v1/teams/{_}/signage/screens/{_}/tokens` | `docs/features/F09.10_digital_signage.md` | 478 |
| POST | `/api/v1/teams/{_}/signage/upload-url` | `docs/features/F09.10_digital_signage.md` | 357 |
| POST | `/api/v1/teams/{_}/signage/upload-url` | `docs/features/F09.10_digital_signage.md` | 687 |
| POST | `/api/v1/teams/{_}/skills` | `docs/features/F07.5_skill_certification.md` | 201 |
| POST | `/api/v1/teams/{_}/skills` | `docs/features/F07.5_skill_certification.md` | 314 |
| POST | `/api/v1/teams/{_}/ticket-products` | `docs/features/F08.5_ticket_book.md` | 232 |
| POST | `/api/v1/teams/{_}/ticket-products` | `docs/features/F08.5_ticket_book.md` | 264 |
| POST | `/api/v1/teams/{_}/timetable-terms` | `docs/features/F03.9_timetable.md` | 297 |
| POST | `/api/v1/teams/{_}/timetables` | `docs/features/F03.9_timetable.md` | 305 |
| POST | `/api/v1/teams/{_}/timetables` | `docs/features/F03.9_timetable.md` | 339 |
| POST | `/api/v1/teams/{_}/todo-status-labels` | `docs/features/F02.3.1_todo_status_labels_and_handoff.md` | 204 |
| POST | `/api/v1/teams/{_}/todo-status-labels` | `docs/features/F02.3.1_todo_status_labels_and_handoff.md` | 306 |
| POST | `/api/v1/teams/{_}/todos` | `docs/features/F02.3_todo_project.md` | 282 |
| POST | `/api/v1/teams/{_}/todos` | `docs/features/F02.8_dashboard_announcement.md` | 184 |
| POST | `/api/v1/teams/{_}/webhooks/endpoints` | `docs/features/F09.9_webhook_api.md` | 260 |
| POST | `/api/v1/teams/{_}/webhooks/endpoints` | `docs/features/F09.9_webhook_api.md` | 289 |
| POST | `/api/v1/teams/{_}/webhooks/endpoints/{_}/logs/{_}/retry` | `docs/features/F09.9_webhook_api.md` | 266 |
| POST | `/api/v1/teams/{_}/webhooks/endpoints/{_}/logs/{_}/retry` | `docs/features/F09.9_webhook_api.md` | 514 |
| POST | `/api/v1/teams/{_}/webhooks/endpoints/{_}/test` | `docs/features/F09.9_webhook_api.md` | 264 |
| POST | `/api/v1/teams/{_}/webhooks/endpoints/{_}/test` | `docs/features/F09.9_webhook_api.md` | 441 |
| POST | `/api/v1/teams/{_}/webhooks/incoming` | `docs/features/F09.9_webhook_api.md` | 273 |
| POST | `/api/v1/teams/{_}/webhooks/incoming` | `docs/features/F09.9_webhook_api.md` | 625 |
| POST | `/api/v1/teams/{_}/workflow-templates` | `docs/features/F05.6_workflow_approval.md` | 372 |
| POST | `/api/v1/teams/{_}/workflows/templates` | `docs/features/F05.6_workflow_approval.md` | 423 |
| PUT | `/api/v1/teams/{_}/access-requirements` | `docs/features/F08.2_payments_access_control.md` | 348 |
| PUT | `/api/v1/teams/{_}/access-requirements` | `docs/features/F08.2_payments_access_control.md` | 609 |
| PUT | `/api/v1/teams/{_}/charts/{_}/body-marks` | `docs/features/F07.4_chart.md` | 410 |
| PUT | `/api/v1/teams/{_}/charts/{_}/body-marks` | `docs/features/F07.4_chart.md` | 590 |
| PUT | `/api/v1/teams/{_}/charts/{_}/intake-form` | `docs/features/F07.4_chart.md` | 409 |
| PUT | `/api/v1/teams/{_}/confirmable-notification-settings` | `docs/features/F04.9_confirmable_notification.md` | 278 |
| PUT | `/api/v1/teams/{_}/content-payment-gates` | `docs/features/F08.2_payments_access_control.md` | 364 |
| PUT | `/api/v1/teams/{_}/content-payment-gates` | `docs/features/F08.2_payments_access_control.md` | 677 |
| PUT | `/api/v1/teams/{_}/gamification/config` | `docs/features/F04.7_gamification.md` | 369 |
| PUT | `/api/v1/teams/{_}/gamification/config` | `docs/features/F04.7_gamification.md` | 424 |
| PUT | `/api/v1/teams/{_}/matching/notification-preferences` | `docs/features/F08.1_matching.md` | 355 |
| PUT | `/api/v1/teams/{_}/matching/notification-preferences` | `docs/features/F08.1_matching.md` | 979 |
| PUT | `/api/v1/teams/{_}/members/{_}/care-overrides/{_}` | `docs/features/F03.12_care_recipient_event_watch_notification.md` | 449 |
| PUT | `/api/v1/teams/{_}/modules` | `docs/features/F01.3_template_module.md` | 374 |
| PUT | `/api/v1/teams/{_}/modules` | `docs/features/F01.3_template_module.md` | 708 |
| PUT | `/api/v1/teams/{_}/modules` | `docs/features/F01.3_template_module.md` | 984 |
| PUT | `/api/v1/teams/{_}/modules` | `docs/features/F01.3_template_module.md` | 988 |
| PUT | `/api/v1/teams/{_}/penalty-settings` | `docs/features/F03.11_recruitment_listing.md` | 1472 |
| PUT | `/api/v1/teams/{_}/presence/icons` | `docs/features/F01.4_family_team.md` | 428 |
| PUT | `/api/v1/teams/{_}/queue/categories/{_}` | `docs/features/F03.7_queue.md` | 328 |
| PUT | `/api/v1/teams/{_}/queue/counters/{_}` | `docs/features/F03.7_queue.md` | 332 |
| PUT | `/api/v1/teams/{_}/queue/settings` | `docs/features/F03.7_queue.md` | 354 |
| PUT | `/api/v1/teams/{_}/reservation-business-hours` | `docs/features/F03.4_reservation.md` | 1459 |
| PUT | `/api/v1/teams/{_}/reservation-lines/{_}` | `docs/features/F03.4_reservation.md` | 334 |
| PUT | `/api/v1/teams/{_}/reservation-lines/{_}` | `docs/features/F03.4_reservation.md` | 451 |
| PUT | `/api/v1/teams/{_}/reservation-settings` | `docs/features/F03.4_reservation.md` | 366 |
| PUT | `/api/v1/teams/{_}/reservation-settings` | `docs/features/F03.4_reservation.md` | 1640 |
| PUT | `/api/v1/teams/{_}/reservation-slots/{_}` | `docs/features/F03.4_reservation.md` | 339 |
| PUT | `/api/v1/teams/{_}/reservation-slots/{_}` | `docs/features/F03.4_reservation.md` | 622 |
| PUT | `/api/v1/teams/{_}/reservations/{_}` | `docs/features/F03.4_reservation.md` | 348 |
| PUT | `/api/v1/teams/{_}/reservations/{_}` | `docs/features/F03.4_reservation.md` | 1016 |
| PUT | `/api/v1/teams/{_}/role-aliases` | `docs/features/F01.4_family_team.md` | 376 |
| PUT | `/api/v1/teams/{_}/role-aliases` | `docs/features/F01.4_family_team.md` | 601 |
| PUT | `/api/v1/teams/{_}/settings/wallpaper` | `docs/features/F01.4_family_team.md` | 439 |
| PUT | `/api/v1/teams/{_}/signage/screens/{_}` | `docs/features/F09.10_digital_signage.md` | 298 |
| PUT | `/api/v1/teams/{_}/signage/screens/{_}/schedules/{_}` | `docs/features/F09.10_digital_signage.md` | 325 |
| PUT | `/api/v1/teams/{_}/signage/screens/{_}/slots/reorder` | `docs/features/F09.10_digital_signage.md` | 313 |
| PUT | `/api/v1/teams/{_}/signage/screens/{_}/slots/reorder` | `docs/features/F09.10_digital_signage.md` | 722 |
| PUT | `/api/v1/teams/{_}/signage/screens/{_}/slots/{_}` | `docs/features/F09.10_digital_signage.md` | 311 |
| PUT | `/api/v1/teams/{_}/signage/screens/{_}/tokens/{_}` | `docs/features/F09.10_digital_signage.md` | 337 |
| PUT | `/api/v1/teams/{_}/template` | `docs/features/F01.3_template_module.md` | 376 |
| PUT | `/api/v1/teams/{_}/template` | `docs/features/F01.3_template_module.md` | 745 |

### /api/v1/templates/* (2 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| GET | `/api/v1/templates` | `docs/features/F01.3_template_module.md` | 367 |
| GET | `/api/v1/templates` | `docs/features/F01.3_template_module.md` | 409 |
| GET | `/api/v1/templates/{_}/preview` | `docs/features/F01.3_template_module.md` | 370 |
| GET | `/api/v1/templates/{_}/preview` | `docs/features/F01.3_template_module.md` | 1072 |

### /api/v1/timeline/* (25 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| DELETE | `/api/v1/timeline/{_}` | `docs/features/F04.1_timeline.md` | 500 |
| DELETE | `/api/v1/timeline/{_}` | `docs/features/F04.1_timeline.md` | 857 |
| DELETE | `/api/v1/timeline/{_}/bookmark` | `docs/features/F04.1_timeline.md` | 508 |
| DELETE | `/api/v1/timeline/{_}/bookmark` | `docs/features/F04.1_timeline.md` | 1108 |
| DELETE | `/api/v1/timeline/{_}/poll/vote` | `docs/features/F04.1_timeline.md` | 522 |
| DELETE | `/api/v1/timeline/{_}/poll/vote` | `docs/features/F04.1_timeline.md` | 1438 |
| DELETE | `/api/v1/timeline/{_}/reactions/{_}` | `docs/features/F04.1_timeline.md` | 504 |
| DELETE | `/api/v1/timeline/{_}/reactions/{_}` | `docs/features/F04.1_timeline.md` | 980 |
| DELETE | `/api/v1/timeline/{_}/repost` | `docs/features/F04.1_timeline.md` | 520 |
| DELETE | `/api/v1/timeline/{_}/repost` | `docs/features/F04.1_timeline.md` | 1388 |
| GET | `/api/v1/timeline` | `docs/features/F04.1_timeline.md` | 495 |
| GET | `/api/v1/timeline` | `docs/features/F04.1_timeline.md` | 527 |
| GET | `/api/v1/timeline/bookmarks` | `docs/features/F04.1_timeline.md` | 509 |
| GET | `/api/v1/timeline/bookmarks` | `docs/features/F04.1_timeline.md` | 1123 |
| GET | `/api/v1/timeline/drafts` | `docs/features/F04.1_timeline.md` | 512 |
| GET | `/api/v1/timeline/drafts` | `docs/features/F04.1_timeline.md` | 1182 |
| GET | `/api/v1/timeline/my` | `docs/features/F04.1_timeline.md` | 510 |
| GET | `/api/v1/timeline/my` | `docs/features/F04.1_timeline.md` | 1139 |
| GET | `/api/v1/timeline/scheduled` | `docs/features/F04.1_timeline.md` | 513 |
| GET | `/api/v1/timeline/scheduled` | `docs/features/F04.1_timeline.md` | 1198 |
| GET | `/api/v1/timeline/stats` | `docs/features/F04.1_timeline.md` | 523 |
| GET | `/api/v1/timeline/stats` | `docs/features/F04.1_timeline.md` | 1454 |
| GET | `/api/v1/timeline/{_}` | `docs/features/F04.1_timeline.md` | 498 |
| GET | `/api/v1/timeline/{_}` | `docs/features/F04.1_timeline.md` | 769 |
| GET | `/api/v1/timeline/{_}/edits` | `docs/features/F04.1_timeline.md` | 514 |
| GET | `/api/v1/timeline/{_}/edits` | `docs/features/F04.1_timeline.md` | 1216 |
| GET | `/api/v1/timeline/{_}/reactions` | `docs/features/F04.1_timeline.md` | 505 |
| GET | `/api/v1/timeline/{_}/reactions` | `docs/features/F04.1_timeline.md` | 1005 |
| GET | `/api/v1/timeline/{_}/replies` | `docs/features/F04.1_timeline.md` | 502 |
| GET | `/api/v1/timeline/{_}/replies` | `docs/features/F04.1_timeline.md` | 914 |
| PATCH | `/api/v1/timeline/{_}/pin` | `docs/features/F04.1_timeline.md` | 506 |
| PATCH | `/api/v1/timeline/{_}/pin` | `docs/features/F04.1_timeline.md` | 1046 |
| POST | `/api/v1/timeline` | `docs/features/F04.1_timeline.md` | 168 |
| POST | `/api/v1/timeline` | `docs/features/F04.1_timeline.md` | 496 |
| POST | `/api/v1/timeline` | `docs/features/F04.1_timeline.md` | 642 |
| POST | `/api/v1/timeline` | `docs/features/F04.1_timeline.md` | 714 |
| POST | `/api/v1/timeline` | `docs/features/F04.1_timeline.md` | 763 |
| POST | `/api/v1/timeline/posts` | `docs/features/F02.8_dashboard_announcement.md` | 182 |
| POST | `/api/v1/timeline/{_}/bookmark` | `docs/features/F04.1_timeline.md` | 507 |
| POST | `/api/v1/timeline/{_}/bookmark` | `docs/features/F04.1_timeline.md` | 1083 |
| POST | `/api/v1/timeline/{_}/poll/vote` | `docs/features/F04.1_timeline.md` | 521 |
| POST | `/api/v1/timeline/{_}/poll/vote` | `docs/features/F04.1_timeline.md` | 1398 |
| POST | `/api/v1/timeline/{_}/reactions` | `docs/features/F04.1_timeline.md` | 503 |
| POST | `/api/v1/timeline/{_}/reactions` | `docs/features/F04.1_timeline.md` | 941 |
| POST | `/api/v1/timeline/{_}/replies` | `docs/features/F04.1_timeline.md` | 501 |
| POST | `/api/v1/timeline/{_}/replies` | `docs/features/F04.1_timeline.md` | 881 |
| POST | `/api/v1/timeline/{_}/repost` | `docs/features/F04.1_timeline.md` | 519 |
| POST | `/api/v1/timeline/{_}/repost` | `docs/features/F04.1_timeline.md` | 1351 |
| PUT | `/api/v1/timeline/{_}` | `docs/features/F04.1_timeline.md` | 499 |
| PUT | `/api/v1/timeline/{_}` | `docs/features/F04.1_timeline.md` | 831 |
| PUT | `/api/v1/timeline/{_}/read` | `docs/features/F04.1_timeline.md` | 518 |
| PUT | `/api/v1/timeline/{_}/read` | `docs/features/F04.1_timeline.md` | 1332 |

### /api/v1/timeline-digest/* (4 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| DELETE | `/api/v1/timeline-digest/config` | `docs/features/F06.3_timeline_digest.md` | 196 |
| DELETE | `/api/v1/timeline-digest/config` | `docs/features/F06.3_timeline_digest.md` | 544 |
| GET | `/api/v1/timeline-digest` | `docs/features/F06.3_timeline_digest.md` | 190 |
| GET | `/api/v1/timeline-digest` | `docs/features/F06.3_timeline_digest.md` | 318 |
| GET | `/api/v1/timeline-digest/config` | `docs/features/F06.3_timeline_digest.md` | 194 |
| GET | `/api/v1/timeline-digest/config` | `docs/features/F06.3_timeline_digest.md` | 473 |
| PUT | `/api/v1/timeline-digest/config` | `docs/features/F06.3_timeline_digest.md` | 195 |
| PUT | `/api/v1/timeline-digest/config` | `docs/features/F06.3_timeline_digest.md` | 519 |

### /api/v1/timeline-posts/* (1 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| GET | `/api/v1/timeline-posts/{_}/context` | `docs/features/F09.8_corkboard.md` | 276 |

### /api/v1/timetable-terms/* (2 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| DELETE | `/api/v1/timetable-terms/{_}` | `docs/features/F03.9_timetable.md` | 299 |
| PATCH | `/api/v1/timetable-terms/{_}` | `docs/features/F03.9_timetable.md` | 298 |

### /api/v1/timetables/* (2 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| GET | `/api/v1/timetables/{_}/changes` | `docs/features/F03.9_timetable.md` | 326 |
| GET | `/api/v1/timetables/{_}/changes` | `docs/features/F03.9_timetable.md` | 556 |
| POST | `/api/v1/timetables/{_}/changes` | `docs/features/F03.9_timetable.md` | 327 |
| POST | `/api/v1/timetables/{_}/changes` | `docs/features/F03.9_timetable.md` | 567 |

### /api/v1/todo-budget/* (1 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| POST | `/api/v1/todo-budget/links` | `docs/features/F08.7_shift_budget_integration.md` | 123 |
| POST | `/api/v1/todo-budget/links` | `docs/features/F08.7_shift_budget_integration.md` | 890 |

### /api/v1/todos/* (4 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| GET | `/api/v1/todos` | `docs/features/F02.5_quick_memo.md` | 1327 |
| PATCH | `/api/v1/todos/{_}/jobber-flag` | `docs/features/F13.1_short_term_job_matching.md` | 1672 |
| PATCH | `/api/v1/todos/{_}/jobber-flag` | `docs/features/F13.1_short_term_job_matching.md` | 2121 |
| PATCH | `/api/v1/todos/{_}/status` | `docs/features/F02.3.1_todo_status_labels_and_handoff.md` | 221 |
| POST | `/api/v1/todos/{_}/convert-to-job-posting` | `docs/features/F13.1_short_term_job_matching.md` | 462 |
| POST | `/api/v1/todos/{_}/convert-to-job-posting` | `docs/features/F13.1_short_term_job_matching.md` | 639 |
| POST | `/api/v1/todos/{_}/convert-to-job-posting` | `docs/features/F13.1_short_term_job_matching.md` | 1671 |
| POST | `/api/v1/todos/{_}/convert-to-job-posting` | `docs/features/F13.1_short_term_job_matching.md` | 2082 |
| POST | `/api/v1/todos/{_}/convert-to-job-posting` | `docs/features/F13.1_short_term_job_matching.md` | 3591 |

### /api/v1/user-penalties/* (1 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| POST | `/api/v1/user-penalties/{_}/lift` | `docs/features/F03.11_recruitment_listing.md` | 1474 |

### /api/v1/users/* (23 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| GET | `/api/v1/users/blocks` | `docs/features/F04.8_contact.md` | 519 |
| GET | `/api/v1/users/me/contact-privacy` | `docs/features/F04.8_contact.md` | 634 |
| GET | `/api/v1/users/me/corkboards` | `docs/features/F09.8.1_corkboard_pin_dashboard.md` | 119 |
| GET | `/api/v1/users/me/data-export/{_}` | `docs/features/F10.1_admin_dashboard.md` | 557 |
| GET | `/api/v1/users/me/job-notification-preferences` | `docs/features/F13.1_short_term_job_matching.md` | 1651 |
| GET | `/api/v1/users/me/reports` | `docs/features/F10.2_moderation.md` | 375 |
| GET | `/api/v1/users/me/reports` | `docs/features/F10.2_moderation.md` | 1017 |
| GET | `/api/v1/users/me/todo-status-labels` | `docs/features/F02.3.1_todo_status_labels_and_handoff.md` | 194 |
| GET | `/api/v1/users/me/vehicles` | `docs/features/F09.3_parking.md` | 623 |
| GET | `/api/v1/users/me/vehicles` | `docs/features/F09.3_parking.md` | 688 |
| GET | `/api/v1/users/{_}` | `docs/features/F01.5_team_friend_relationships.md` | 349 |
| GET | `/api/v1/users/{_}` | `docs/features/F04.4_social_profiles.md` | 61 |
| GET | `/api/v1/users/{_}` | `docs/features/F04.4_social_profiles.md` | 552 |
| GET | `/api/v1/users/{_}` | `docs/features/F17.1_village_community.md` | 989 |
| GET | `/api/v1/users/{_}/followings` | `docs/features/F04.4_social_profiles.md` | 105 |
| GET | `/api/v1/users/{_}/seals` | `docs/features/F05.3_digital_seal.md` | 153 |
| GET | `/api/v1/users/{_}/seals` | `docs/features/F05.3_digital_seal.md` | 168 |
| GET | `/api/v1/users/{_}/seals/preview` | `docs/features/F05.3_digital_seal.md` | 154 |
| GET | `/api/v1/users/{_}/seals/preview` | `docs/features/F05.3_digital_seal.md` | 216 |
| GET | `/api/v1/users/{_}/seals/scope-defaults` | `docs/features/F05.3_digital_seal.md` | 156 |
| GET | `/api/v1/users/{_}/seals/scope-defaults` | `docs/features/F05.3_digital_seal.md` | 278 |
| GET | `/api/v1/users/{_}/seals/stamps` | `docs/features/F05.3_digital_seal.md` | 158 |
| GET | `/api/v1/users/{_}/seals/stamps` | `docs/features/F05.3_digital_seal.md` | 322 |
| POST | `/api/v1/users/blocks` | `docs/features/F04.8_contact.md` | 491 |
| POST | `/api/v1/users/me/avatar` | `docs/features/F01.1_auth.md` | 429 |
| POST | `/api/v1/users/me/data-export` | `docs/features/F10.1_admin_dashboard.md` | 556 |
| POST | `/api/v1/users/me/todo-status-labels` | `docs/features/F02.3.1_todo_status_labels_and_handoff.md` | 195 |
| POST | `/api/v1/users/me/vehicles` | `docs/features/F09.3_parking.md` | 624 |
| POST | `/api/v1/users/me/vehicles` | `docs/features/F09.3_parking.md` | 722 |
| POST | `/api/v1/users/{_}/seals/regenerate` | `docs/features/F05.3_digital_seal.md` | 155 |
| POST | `/api/v1/users/{_}/seals/regenerate` | `docs/features/F05.3_digital_seal.md` | 247 |
| PUT | `/api/v1/users/me/contact-privacy` | `docs/features/F04.8_contact.md` | 647 |
| PUT | `/api/v1/users/me/job-notification-preferences` | `docs/features/F13.1_short_term_job_matching.md` | 1652 |
| PUT | `/api/v1/users/{_}/seals/scope-defaults` | `docs/features/F05.3_digital_seal.md` | 157 |
| PUT | `/api/v1/users/{_}/seals/scope-defaults` | `docs/features/F05.3_digital_seal.md` | 297 |

### /api/v1/villages/* (2 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| GET | `/api/v1/villages/{_}/calendar-events` | `docs/features/F17.1_village_community_phase2_3_api_addendum.md` | 87 |
| POST | `/api/v1/villages/{_}/calendar-events` | `docs/features/F17.1_village_community_phase2_3_api_addendum.md` | 87 |

### /api/v1/visibility-templates/* (2 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| GET | `/api/v1/visibility-templates` | `docs/features/F01.7_custom_visibility_templates.md` | 238 |
| POST | `/api/v1/visibility-templates` | `docs/features/F01.7_custom_visibility_templates.md` | 240 |

### /api/v1/warnings/* (1 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| PATCH | `/api/v1/warnings/re-reviews/{_}/escalate` | `docs/features/F10.2_moderation.md` | 371 |
| PATCH | `/api/v1/warnings/re-reviews/{_}/escalate` | `docs/features/F10.2_moderation.md` | 889 |

### /api/v1/webhooks/* (3 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| POST | `/api/v1/webhooks/ses` | `docs/features/F09.6_direct_mail.md` | 347 |
| POST | `/api/v1/webhooks/stripe/connect` | `docs/features/F13.1_short_term_job_matching.md` | 1649 |
| POST | `/api/v1/webhooks/stripe/platform` | `docs/features/F13.1_short_term_job_matching.md` | 1650 |

### /api/v1/workflow-requests/* (2 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| GET | `/api/v1/workflow-requests/me` | `docs/features/F05.6_workflow_approval.md` | 385 |
| GET | `/api/v1/workflow-requests/pending` | `docs/features/F05.6_workflow_approval.md` | 386 |

### /api/v1/workflows/* (14 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| DELETE | `/api/v1/workflows/requests/{_}/attachments/{_}` | `docs/features/F05.6_workflow_approval.md` | 413 |
| DELETE | `/api/v1/workflows/requests/{_}/comments/{_}` | `docs/features/F05.6_workflow_approval.md` | 406 |
| GET | `/api/v1/workflows/requests/by-source` | `docs/features/F05.6_workflow_approval.md` | 419 |
| GET | `/api/v1/workflows/requests/by-source` | `docs/features/F05.6_workflow_approval.md` | 829 |
| GET | `/api/v1/workflows/requests/pending` | `docs/features/F05.6_workflow_approval.md` | 754 |
| GET | `/api/v1/workflows/requests/{_}/comments` | `docs/features/F05.6_workflow_approval.md` | 404 |
| POST | `/api/v1/workflows/requests/{_}/approve` | `docs/features/F05.6_workflow_approval.md` | 397 |
| POST | `/api/v1/workflows/requests/{_}/approve` | `docs/features/F05.6_workflow_approval.md` | 634 |
| POST | `/api/v1/workflows/requests/{_}/attachments` | `docs/features/F05.6_workflow_approval.md` | 412 |
| POST | `/api/v1/workflows/requests/{_}/comments` | `docs/features/F05.6_workflow_approval.md` | 405 |
| POST | `/api/v1/workflows/requests/{_}/reject` | `docs/features/F05.6_workflow_approval.md` | 398 |
| POST | `/api/v1/workflows/requests/{_}/reject` | `docs/features/F05.6_workflow_approval.md` | 702 |
| POST | `/api/v1/workflows/requests/{_}/return` | `docs/features/F05.6_workflow_approval.md` | 399 |
| POST | `/api/v1/workflows/requests/{_}/return` | `docs/features/F05.6_workflow_approval.md` | 728 |
| POST | `/api/v1/workflows/requests/{_}/submit` | `docs/features/F05.6_workflow_approval.md` | 587 |
| POST | `/api/v1/workflows/requests/{_}/upload-url` | `docs/features/F05.6_workflow_approval.md` | 411 |
| POST | `/api/v1/workflows/templates/{_}/requests` | `docs/features/F05.6_workflow_approval.md` | 536 |
| POST | `/api/v1/workflows/templates/{_}/requests/external` | `docs/features/F05.6_workflow_approval.md` | 418 |
| POST | `/api/v1/workflows/templates/{_}/requests/external` | `docs/features/F05.6_workflow_approval.md` | 797 |

### /api/v1/{_}/* (68 件)

| メソッド | パス | 設計書 | 行 |
|---|---|---|---|
| DELETE | `/api/v1/{_}/{_}/form-templates/{_}` | `docs/features/F05.7_form_builder.md` | 373 |
| DELETE | `/api/v1/{_}/{_}/property-history/{_}` | `docs/features/F09.13_property_history.md` | 284 |
| DELETE | `/api/v1/{_}/{_}/property-history/{_}/documents/{_}` | `docs/features/F09.13_property_history.md` | 286 |
| DELETE | `/api/v1/{_}/{_}/repair-plan/delegations/{_}` | `docs/features/F08.8_repair_longterm_dashboard.md` | 554 |
| DELETE | `/api/v1/{_}/{_}/repair-plan/items/{_}` | `docs/features/F08.8_repair_longterm_dashboard.md` | 532 |
| DELETE | `/api/v1/{_}/{_}/vendors/{_}` | `docs/features/F09.13_property_history.md` | 268 |
| DELETE | `/api/v1/{_}/{_}/workflow-requests/{_}` | `docs/features/F05.6_workflow_approval.md` | 392 |
| DELETE | `/api/v1/{_}/{_}/workflow-templates/{_}` | `docs/features/F05.6_workflow_approval.md` | 376 |
| GET | `/api/v1/{_}/{_}/direct-mails/quota` | `docs/features/F09.6_direct_mail.md` | 483 |
| GET | `/api/v1/{_}/{_}/direct-mails/{_}` | `docs/features/F09.6_direct_mail.md` | 449 |
| GET | `/api/v1/{_}/{_}/form-templates/{_}` | `docs/features/F05.7_form_builder.md` | 369 |
| GET | `/api/v1/{_}/{_}/property-history` | `docs/features/F09.13_property_history.md` | 277 |
| GET | `/api/v1/{_}/{_}/property-history/gantt` | `docs/features/F09.13_property_history.md` | 279 |
| GET | `/api/v1/{_}/{_}/property-history/timeline` | `docs/features/F09.13_property_history.md` | 278 |
| GET | `/api/v1/{_}/{_}/property-history/{_}` | `docs/features/F09.13_property_history.md` | 280 |
| GET | `/api/v1/{_}/{_}/repair-plan/dashboard` | `docs/features/F08.8_repair_longterm_dashboard.md` | 528 |
| GET | `/api/v1/{_}/{_}/repair-plan/delegations` | `docs/features/F08.8_repair_longterm_dashboard.md` | 552 |
| GET | `/api/v1/{_}/{_}/repair-plan/handover-packs/{_}/download` | `docs/features/F08.8_repair_longterm_dashboard.md` | 549 |
| GET | `/api/v1/{_}/{_}/repair-plan/items` | `docs/features/F08.8_repair_longterm_dashboard.md` | 529 |
| GET | `/api/v1/{_}/{_}/repair-plan/quote-kanbans` | `docs/features/F08.8_repair_longterm_dashboard.md` | 542 |
| GET | `/api/v1/{_}/{_}/repair-plan/quote-kanbans/{_}` | `docs/features/F08.8_repair_longterm_dashboard.md` | 544 |
| GET | `/api/v1/{_}/{_}/repair-plan/scenarios` | `docs/features/F08.8_repair_longterm_dashboard.md` | 535 |
| GET | `/api/v1/{_}/{_}/repair-plan/scenarios/{_}` | `docs/features/F08.8_repair_longterm_dashboard.md` | 537 |
| GET | `/api/v1/{_}/{_}/repair-plan/templates` | `docs/features/F08.8_repair_longterm_dashboard.md` | 550 |
| GET | `/api/v1/{_}/{_}/repair-plan/timeline` | `docs/features/F08.8_repair_longterm_dashboard.md` | 541 |
| GET | `/api/v1/{_}/{_}/vendors` | `docs/features/F09.13_property_history.md` | 264 |
| GET | `/api/v1/{_}/{_}/vendors/search` | `docs/features/F09.13_property_history.md` | 269 |
| GET | `/api/v1/{_}/{_}/vendors/{_}` | `docs/features/F09.13_property_history.md` | 265 |
| GET | `/api/v1/{_}/{_}/workflow-requests/{_}` | `docs/features/F05.6_workflow_approval.md` | 388 |
| GET | `/api/v1/{_}/{_}/workflow-templates/{_}` | `docs/features/F05.6_workflow_approval.md` | 374 |
| PATCH | `/api/v1/{_}/{_}/property-history/{_}/status` | `docs/features/F09.13_property_history.md` | 283 |
| PATCH | `/api/v1/{_}/{_}/repair-plan/items/{_}` | `docs/features/F08.8_repair_longterm_dashboard.md` | 531 |
| PATCH | `/api/v1/{_}/{_}/repair-plan/quote-kanbans/{_}` | `docs/features/F08.8_repair_longterm_dashboard.md` | 545 |
| PATCH | `/api/v1/{_}/{_}/workflow-requests/{_}` | `docs/features/F05.6_workflow_approval.md` | 389 |
| POST | `/api/v1/{_}/{_}/bulletin/threads` | `docs/features/F02.8_dashboard_announcement.md` | 181 |
| POST | `/api/v1/{_}/{_}/form-templates/{_}/close` | `docs/features/F05.7_form_builder.md` | 372 |
| POST | `/api/v1/{_}/{_}/form-templates/{_}/duplicate` | `docs/features/F05.7_form_builder.md` | 374 |
| POST | `/api/v1/{_}/{_}/form-templates/{_}/publish` | `docs/features/F05.7_form_builder.md` | 371 |
| POST | `/api/v1/{_}/{_}/form-templates/{_}/remind` | `docs/features/F05.7_form_builder.md` | 375 |
| POST | `/api/v1/{_}/{_}/property-history` | `docs/features/F09.13_property_history.md` | 281 |
| POST | `/api/v1/{_}/{_}/property-history/export` | `docs/features/F09.13_property_history.md` | 288 |
| POST | `/api/v1/{_}/{_}/property-history/{_}/documents` | `docs/features/F09.13_property_history.md` | 285 |
| POST | `/api/v1/{_}/{_}/property-history/{_}/export` | `docs/features/F09.13_property_history.md` | 287 |
| POST | `/api/v1/{_}/{_}/repair-plan/delegations` | `docs/features/F08.8_repair_longterm_dashboard.md` | 553 |
| POST | `/api/v1/{_}/{_}/repair-plan/handover-packs` | `docs/features/F08.8_repair_longterm_dashboard.md` | 548 |
| POST | `/api/v1/{_}/{_}/repair-plan/items` | `docs/features/F08.8_repair_longterm_dashboard.md` | 530 |
| POST | `/api/v1/{_}/{_}/repair-plan/items/import-csv` | `docs/features/F08.8_repair_longterm_dashboard.md` | 533 |
| POST | `/api/v1/{_}/{_}/repair-plan/items/import-csv` | `docs/features/F08.8_repair_longterm_dashboard.md` | 622 |
| POST | `/api/v1/{_}/{_}/repair-plan/items/import-csv/confirm` | `docs/features/F08.8_repair_longterm_dashboard.md` | 534 |
| POST | `/api/v1/{_}/{_}/repair-plan/quote-cards/{_}/move` | `docs/features/F08.8_repair_longterm_dashboard.md` | 547 |
| POST | `/api/v1/{_}/{_}/repair-plan/quote-kanbans` | `docs/features/F08.8_repair_longterm_dashboard.md` | 543 |
| POST | `/api/v1/{_}/{_}/repair-plan/quote-kanbans/{_}/cards` | `docs/features/F08.8_repair_longterm_dashboard.md` | 546 |
| POST | `/api/v1/{_}/{_}/repair-plan/scenarios` | `docs/features/F08.8_repair_longterm_dashboard.md` | 536 |
| POST | `/api/v1/{_}/{_}/repair-plan/scenarios/simulate` | `docs/features/F08.8_repair_longterm_dashboard.md` | 538 |
| POST | `/api/v1/{_}/{_}/repair-plan/scenarios/simulate` | `docs/features/F08.8_repair_longterm_dashboard.md` | 569 |
| POST | `/api/v1/{_}/{_}/repair-plan/scenarios/{_}/pin-to-corkboard` | `docs/features/F08.8_repair_longterm_dashboard.md` | 540 |
| POST | `/api/v1/{_}/{_}/repair-plan/scenarios/{_}/publish-as-announcement` | `docs/features/F08.8_repair_longterm_dashboard.md` | 539 |
| POST | `/api/v1/{_}/{_}/repair-plan/scenarios/{_}/publish-as-announcement` | `docs/features/F08.8_repair_longterm_dashboard.md` | 648 |
| POST | `/api/v1/{_}/{_}/repair-plan/templates/override` | `docs/features/F08.8_repair_longterm_dashboard.md` | 551 |
| POST | `/api/v1/{_}/{_}/schedules` | `docs/features/F02.8_dashboard_announcement.md` | 185 |
| POST | `/api/v1/{_}/{_}/surveys` | `docs/features/F02.8_dashboard_announcement.md` | 186 |
| POST | `/api/v1/{_}/{_}/vendors` | `docs/features/F09.13_property_history.md` | 266 |
| POST | `/api/v1/{_}/{_}/workflow-requests/{_}/submit` | `docs/features/F05.6_workflow_approval.md` | 390 |
| POST | `/api/v1/{_}/{_}/workflow-requests/{_}/withdraw` | `docs/features/F05.6_workflow_approval.md` | 391 |
| POST | `/api/v1/{_}/{_}/workflow-templates/{_}/activate` | `docs/features/F05.6_workflow_approval.md` | 377 |
| POST | `/api/v1/{_}/{_}/workflow-templates/{_}/deactivate` | `docs/features/F05.6_workflow_approval.md` | 378 |
| POST | `/api/v1/{_}/{_}/workflow-templates/{_}/requests` | `docs/features/F05.6_workflow_approval.md` | 387 |
| PUT | `/api/v1/{_}/{_}/form-templates/{_}` | `docs/features/F05.7_form_builder.md` | 370 |
| PUT | `/api/v1/{_}/{_}/property-history/{_}` | `docs/features/F09.13_property_history.md` | 282 |
| PUT | `/api/v1/{_}/{_}/vendors/{_}` | `docs/features/F09.13_property_history.md` | 267 |
| PUT | `/api/v1/{_}/{_}/workflow-templates/{_}` | `docs/features/F05.6_workflow_approval.md` | 375 |

---

## 2. 🟡 実装あり・設計なし（設計書整備候補）

#### /api/v1/organizations/* (369 件)

| メソッド | パス | Controller | 行 |
|---|---|---|---|
| DELETE | `/api/v1/organizations/{_}/announcement-templates/{_}` | `AnnouncementRangeTemplateController#deleteOrgTemplate` (backend/src/main/java/com/mannschaft/app/social/announcement/controller/AnnouncementRangeTemplateController.java) | 256 |
| DELETE | `/api/v1/organizations/{_}/announcements/{_}` | `AnnouncementFeedOrgController#deleteAnnouncement` (backend/src/main/java/com/mannschaft/app/social/announcement/controller/AnnouncementFeedOrgController.java) | 152 |
| DELETE | `/api/v1/organizations/{_}/bulletin/categories/{_}` | `BulletinCategoryController#deleteCategory` (backend/src/main/java/com/mannschaft/app/bulletin/controller/BulletinCategoryController.java) | 102 |
| DELETE | `/api/v1/organizations/{_}/bulletin/threads/{_}` | `BulletinThreadController#deleteThread` (backend/src/main/java/com/mannschaft/app/bulletin/controller/BulletinThreadController.java) | 133 |
| DELETE | `/api/v1/organizations/{_}/bulletin/threads/{_}/replies/{_}` | `BulletinReplyController#deleteReply` (backend/src/main/java/com/mannschaft/app/bulletin/controller/BulletinReplyController.java) | 96 |
| DELETE | `/api/v1/organizations/{_}/confirmable-notification-templates/{_}` | `OrgConfirmableNotificationTemplateController#delete` (backend/src/main/java/com/mannschaft/app/notification/confirmable/controller/OrgConfirmableNotificationTemplateController.java) | 102 |
| DELETE | `/api/v1/organizations/{_}/coupons/{_}` | `OrgCouponController#delete` (backend/src/main/java/com/mannschaft/app/promotion/controller/OrgCouponController.java) | 73 |
| DELETE | `/api/v1/organizations/{_}/direct-mail-templates/{_}` | `OrganizationDirectMailTemplateController#deleteTemplate` (backend/src/main/java/com/mannschaft/app/directmail/controller/OrganizationDirectMailTemplateController.java) | 78 |
| DELETE | `/api/v1/organizations/{_}/dwelling-units/{_}` | `OrgDwellingUnitController#delete` (backend/src/main/java/com/mannschaft/app/resident/controller/OrgDwellingUnitController.java) | 73 |
| DELETE | `/api/v1/organizations/{_}/facilities/bookings/{_}` | `OrgFacilityBookingController#cancelBooking` (backend/src/main/java/com/mannschaft/app/facility/controller/OrgFacilityBookingController.java) | 98 |
| DELETE | `/api/v1/organizations/{_}/facilities/{_}` | `OrgFacilityController#deleteFacility` (backend/src/main/java/com/mannschaft/app/facility/controller/OrgFacilityController.java) | 114 |
| DELETE | `/api/v1/organizations/{_}/facilities/{_}/equipment/{_}` | `OrgFacilityController#deleteEquipment` (backend/src/main/java/com/mannschaft/app/facility/controller/OrgFacilityController.java) | 206 |
| DELETE | `/api/v1/organizations/{_}/form-submissions/{_}` | `FormSubmissionController#deleteSubmission` (backend/src/main/java/com/mannschaft/app/forms/controller/FormSubmissionController.java) | 106 |
| DELETE | `/api/v1/organizations/{_}/form-templates/{_}` | `FormTemplateController#deleteTemplate` (backend/src/main/java/com/mannschaft/app/forms/controller/FormTemplateController.java) | 135 |
| DELETE | `/api/v1/organizations/{_}/line/config` | `LineBotConfigController#deleteForOrg` (backend/src/main/java/com/mannschaft/app/line/controller/LineBotConfigController.java) | 142 |
| DELETE | `/api/v1/organizations/{_}/parking/applications/{_}` | `OrgParkingApplicationController#cancel` (backend/src/main/java/com/mannschaft/app/parking/controller/OrgParkingApplicationController.java) | 81 |
| DELETE | `/api/v1/organizations/{_}/parking/listings/{_}` | `OrgParkingListingController#delete` (backend/src/main/java/com/mannschaft/app/parking/controller/OrgParkingListingController.java) | 81 |
| DELETE | `/api/v1/organizations/{_}/parking/spaces/{_}` | `OrgParkingSpaceController#deleteSpace` (backend/src/main/java/com/mannschaft/app/parking/controller/OrgParkingSpaceController.java) | 90 |
| DELETE | `/api/v1/organizations/{_}/parking/subleases/{_}` | `OrgParkingSubleaseController#delete` (backend/src/main/java/com/mannschaft/app/parking/controller/OrgParkingSubleaseController.java) | 81 |
| DELETE | `/api/v1/organizations/{_}/parking/visitor-recurring/{_}` | `OrgParkingVisitorController#deleteRecurring` (backend/src/main/java/com/mannschaft/app/parking/controller/OrgParkingVisitorController.java) | 147 |
| DELETE | `/api/v1/organizations/{_}/parking/visitor-reservations/{_}` | `OrgParkingVisitorController#cancelReservation` (backend/src/main/java/com/mannschaft/app/parking/controller/OrgParkingVisitorController.java) | 115 |
| DELETE | `/api/v1/organizations/{_}/parking/watchlist/{_}` | `OrgParkingWatchlistController#delete` (backend/src/main/java/com/mannschaft/app/parking/controller/OrgParkingWatchlistController.java) | 48 |
| DELETE | `/api/v1/organizations/{_}/point-cards/providers/{_}` | `OrgPointCardProviderController#deactivateProvider` (backend/src/main/java/com/mannschaft/app/pointcard/controller/OrgPointCardProviderController.java) | 151 |
| DELETE | `/api/v1/organizations/{_}/promotions/{_}` | `OrgPromotionController#delete` (backend/src/main/java/com/mannschaft/app/promotion/controller/OrgPromotionController.java) | 78 |
| DELETE | `/api/v1/organizations/{_}/property-history/{_}` | `PropertyWorkPackageController#deletePackage` (backend/src/main/java/com/mannschaft/app/property/controller/PropertyWorkPackageController.java) | 236 |
| DELETE | `/api/v1/organizations/{_}/property-history/{_}/documents/{_}` | `PropertyWorkPackageController#detachDocument` (backend/src/main/java/com/mannschaft/app/property/controller/PropertyWorkPackageController.java) | 271 |
| DELETE | `/api/v1/organizations/{_}/property-listings/{_}` | `OrgPropertyListingController#delete` (backend/src/main/java/com/mannschaft/app/resident/controller/OrgPropertyListingController.java) | 79 |
| DELETE | `/api/v1/organizations/{_}/repair-plan/handover-packs/{_}` | `BoardHandoverPackController#deletePack` (backend/src/main/java/com/mannschaft/app/repairplan/controller/BoardHandoverPackController.java) | 101 |
| DELETE | `/api/v1/organizations/{_}/repair-plan/items/{_}` | `RepairPlanItemController#delete` (backend/src/main/java/com/mannschaft/app/repairplan/controller/RepairPlanItemController.java) | 137 |
| DELETE | `/api/v1/organizations/{_}/residents/{_}` | `OrgResidentController#delete` (backend/src/main/java/com/mannschaft/app/resident/controller/OrgResidentController.java) | 60 |
| DELETE | `/api/v1/organizations/{_}/residents/{_}/documents/{_}` | `OrgResidentDocumentController#delete` (backend/src/main/java/com/mannschaft/app/resident/controller/OrgResidentDocumentController.java) | 49 |
| DELETE | `/api/v1/organizations/{_}/segment-presets/{_}` | `OrgSegmentPresetController#delete` (backend/src/main/java/com/mannschaft/app/promotion/controller/OrgSegmentPresetController.java) | 57 |
| DELETE | `/api/v1/organizations/{_}/sns/feeds/{_}` | `SnsFeedConfigController#deleteForOrg` (backend/src/main/java/com/mannschaft/app/line/controller/SnsFeedConfigController.java) | 123 |
| DELETE | `/api/v1/organizations/{_}/surveys/{_}` | `SurveyController#deleteSurvey` (backend/src/main/java/com/mannschaft/app/survey/controller/SurveyController.java) | 141 |
| DELETE | `/api/v1/organizations/{_}/surveys/{_}/questions/{_}` | `SurveyQuestionController#deleteQuestion` (backend/src/main/java/com/mannschaft/app/survey/controller/SurveyQuestionController.java) | 49 |
| DELETE | `/api/v1/organizations/{_}/teams/{_}/entry-templates/{_}` | `TournamentEntryTemplateController#deleteTemplate` (backend/src/main/java/com/mannschaft/app/tournament/entry/TournamentEntryTemplateController.java) | 127 |
| DELETE | `/api/v1/organizations/{_}/todos/{_}` | `OrgTodoController#deleteTodo` (backend/src/main/java/com/mannschaft/app/todo/controller/OrgTodoController.java) | 169 |
| DELETE | `/api/v1/organizations/{_}/todos/{_}/assignees/{_}` | `OrgTodoController#removeAssignee` (backend/src/main/java/com/mannschaft/app/todo/controller/OrgTodoController.java) | 230 |
| DELETE | `/api/v1/organizations/{_}/todos/{_}/comments/{_}` | `OrgTodoController#deleteComment` (backend/src/main/java/com/mannschaft/app/todo/controller/OrgTodoController.java) | 290 |
| DELETE | `/api/v1/organizations/{_}/todos/{_}/link-schedule` | `OrgTodoController#unlinkSchedule` (backend/src/main/java/com/mannschaft/app/todo/controller/OrgTodoController.java) | 321 |
| DELETE | `/api/v1/organizations/{_}/todos/{_}/memos/{_}` | `OrgTodoController#deleteSharedMemo` (backend/src/main/java/com/mannschaft/app/todo/controller/OrgTodoController.java) | 428 |
| DELETE | `/api/v1/organizations/{_}/todos/{_}/my-memo` | `OrgTodoController#deletePersonalMemo` (backend/src/main/java/com/mannschaft/app/todo/controller/OrgTodoController.java) | 469 |
| DELETE | `/api/v1/organizations/{_}/tournament-templates/{_}` | `TournamentTemplateController#deleteTemplate` (backend/src/main/java/com/mannschaft/app/tournament/controller/TournamentTemplateController.java) | 89 |
| DELETE | `/api/v1/organizations/{_}/tournaments/{_}` | `TournamentController#deleteTournament` (backend/src/main/java/com/mannschaft/app/tournament/controller/TournamentController.java) | 81 |
| DELETE | `/api/v1/organizations/{_}/tournaments/{_}/divisions/{_}` | `DivisionController#deleteDivision` (backend/src/main/java/com/mannschaft/app/tournament/controller/DivisionController.java) | 66 |
| DELETE | `/api/v1/organizations/{_}/tournaments/{_}/divisions/{_}/participants/{_}` | `DivisionController#removeParticipant` (backend/src/main/java/com/mannschaft/app/tournament/controller/DivisionController.java) | 101 |
| DELETE | `/api/v1/organizations/{_}/tournaments/{_}/divisions/{_}/participants/{_}/entry-members/{_}` | `TournamentEntryMemberController#deleteEntryMember` (backend/src/main/java/com/mannschaft/app/tournament/entry/TournamentEntryMemberController.java) | 125 |
| DELETE | `/api/v1/organizations/{_}/tournaments/{_}/matches/{_}/rosters/{_}` | `MatchController#deleteRoster` (backend/src/main/java/com/mannschaft/app/tournament/controller/MatchController.java) | 189 |
| DELETE | `/api/v1/organizations/{_}/vendors/{_}` | `VendorController#deleteVendor` (backend/src/main/java/com/mannschaft/app/property/controller/VendorController.java) | 151 |
| DELETE | `/api/v1/organizations/{_}/workflow-requests/{_}` | `WorkflowRequestController#deleteRequest` (backend/src/main/java/com/mannschaft/app/workflow/controller/WorkflowRequestController.java) | 134 |
| DELETE | `/api/v1/organizations/{_}/workflow-templates/{_}` | `WorkflowTemplateController#deleteTemplate` (backend/src/main/java/com/mannschaft/app/workflow/controller/WorkflowTemplateController.java) | 105 |
| GET | `/api/v1/organizations/{_}/announcement-templates` | `AnnouncementRangeTemplateController#listOrgTemplates` (backend/src/main/java/com/mannschaft/app/social/announcement/controller/AnnouncementRangeTemplateController.java) | 174 |
| GET | `/api/v1/organizations/{_}/bulletin/categories/{_}` | `BulletinCategoryController#getCategory` (backend/src/main/java/com/mannschaft/app/bulletin/controller/BulletinCategoryController.java) | 56 |
| GET | `/api/v1/organizations/{_}/bulletin/threads/search` | `BulletinThreadController#searchThreads` (backend/src/main/java/com/mannschaft/app/bulletin/controller/BulletinThreadController.java) | 83 |
| GET | `/api/v1/organizations/{_}/bulletin/threads/{_}` | `BulletinThreadController#getThread` (backend/src/main/java/com/mannschaft/app/bulletin/controller/BulletinThreadController.java) | 68 |
| GET | `/api/v1/organizations/{_}/circulations/{_}` | `OrgCirculationDocumentController#getDocument` (backend/src/main/java/com/mannschaft/app/circulation/controller/OrgCirculationDocumentController.java) | 60 |
| GET | `/api/v1/organizations/{_}/confirmable-notifications/{_}` | `OrgConfirmableNotificationController#getDetail` (backend/src/main/java/com/mannschaft/app/notification/confirmable/controller/OrgConfirmableNotificationController.java) | 112 |
| GET | `/api/v1/organizations/{_}/confirmable-notifications/{_}/recipients` | `OrgConfirmableNotificationController#getRecipients` (backend/src/main/java/com/mannschaft/app/notification/confirmable/controller/OrgConfirmableNotificationController.java) | 174 |
| GET | `/api/v1/organizations/{_}/corkboards/{_}` | `OrganizationCorkboardController#getBoard` (backend/src/main/java/com/mannschaft/app/corkboard/controller/OrganizationCorkboardController.java) | 61 |
| GET | `/api/v1/organizations/{_}/coupons` | `OrgCouponController#list` (backend/src/main/java/com/mannschaft/app/promotion/controller/OrgCouponController.java) | 37 |
| GET | `/api/v1/organizations/{_}/coupons/{_}` | `OrgCouponController#get` (backend/src/main/java/com/mannschaft/app/promotion/controller/OrgCouponController.java) | 58 |
| GET | `/api/v1/organizations/{_}/direct-mails/{_}` | `OrganizationDirectMailController#getMail` (backend/src/main/java/com/mannschaft/app/directmail/controller/OrganizationDirectMailController.java) | 72 |
| GET | `/api/v1/organizations/{_}/direct-mails/{_}/recipients` | `OrganizationDirectMailController#listRecipients` (backend/src/main/java/com/mannschaft/app/directmail/controller/OrganizationDirectMailController.java) | 134 |
| GET | `/api/v1/organizations/{_}/direct-mails/{_}/stats` | `OrganizationDirectMailController#getStats` (backend/src/main/java/com/mannschaft/app/directmail/controller/OrganizationDirectMailController.java) | 148 |
| GET | `/api/v1/organizations/{_}/dwelling-units` | `OrgDwellingUnitController#list` (backend/src/main/java/com/mannschaft/app/resident/controller/OrgDwellingUnitController.java) | 38 |
| GET | `/api/v1/organizations/{_}/dwelling-units/{_}` | `OrgDwellingUnitController#get` (backend/src/main/java/com/mannschaft/app/resident/controller/OrgDwellingUnitController.java) | 58 |
| GET | `/api/v1/organizations/{_}/dwelling-units/{_}/residents` | `OrgResidentController#listByUnit` (backend/src/main/java/com/mannschaft/app/resident/controller/OrgResidentController.java) | 37 |
| GET | `/api/v1/organizations/{_}/facilities/bookings/calendar` | `OrgFacilityBookingController#getCalendar` (backend/src/main/java/com/mannschaft/app/facility/controller/OrgFacilityBookingController.java) | 171 |
| GET | `/api/v1/organizations/{_}/facilities/bookings/{_}` | `OrgFacilityBookingController#getBooking` (backend/src/main/java/com/mannschaft/app/facility/controller/OrgFacilityBookingController.java) | 77 |
| GET | `/api/v1/organizations/{_}/facilities/bookings/{_}/confirmation-pdf` | `OrgFacilityBookingController#getConfirmationPdf` (backend/src/main/java/com/mannschaft/app/facility/controller/OrgFacilityBookingController.java) | 182 |
| GET | `/api/v1/organizations/{_}/facilities/bookings/{_}/payment` | `OrgFacilityBookingController#getPayment` (backend/src/main/java/com/mannschaft/app/facility/controller/OrgFacilityBookingController.java) | 151 |
| GET | `/api/v1/organizations/{_}/facilities/settings` | `OrgFacilitySettingsController#getSettings` (backend/src/main/java/com/mannschaft/app/facility/controller/OrgFacilitySettingsController.java) | 35 |
| GET | `/api/v1/organizations/{_}/facilities/stats` | `OrgFacilitySettingsController#getStats` (backend/src/main/java/com/mannschaft/app/facility/controller/OrgFacilitySettingsController.java) | 54 |
| GET | `/api/v1/organizations/{_}/facilities/{_}` | `OrgFacilityController#getFacility` (backend/src/main/java/com/mannschaft/app/facility/controller/OrgFacilityController.java) | 93 |
| GET | `/api/v1/organizations/{_}/facilities/{_}/availability` | `OrgFacilityController#getAvailability` (backend/src/main/java/com/mannschaft/app/facility/controller/OrgFacilityController.java) | 218 |
| GET | `/api/v1/organizations/{_}/facilities/{_}/equipment` | `OrgFacilityController#listEquipment` (backend/src/main/java/com/mannschaft/app/facility/controller/OrgFacilityController.java) | 170 |
| GET | `/api/v1/organizations/{_}/facilities/{_}/rates` | `OrgFacilityController#getTimeRates` (backend/src/main/java/com/mannschaft/app/facility/controller/OrgFacilityController.java) | 147 |
| GET | `/api/v1/organizations/{_}/facilities/{_}/rules` | `OrgFacilityController#getUsageRule` (backend/src/main/java/com/mannschaft/app/facility/controller/OrgFacilityController.java) | 124 |
| GET | `/api/v1/organizations/{_}/follow/status` | `OrganizationController#getFollowStatus` (backend/src/main/java/com/mannschaft/app/organization/controller/OrganizationController.java) | 195 |
| GET | `/api/v1/organizations/{_}/form-submissions/my` | `FormSubmissionController#listMySubmissions` (backend/src/main/java/com/mannschaft/app/forms/controller/FormSubmissionController.java) | 43 |
| GET | `/api/v1/organizations/{_}/form-submissions/{_}` | `FormSubmissionController#getSubmission` (backend/src/main/java/com/mannschaft/app/forms/controller/FormSubmissionController.java) | 61 |
| GET | `/api/v1/organizations/{_}/form-templates/{_}` | `FormTemplateController#getTemplate` (backend/src/main/java/com/mannschaft/app/forms/controller/FormTemplateController.java) | 62 |
| GET | `/api/v1/organizations/{_}/line/config` | `LineBotConfigController#getForOrg` (backend/src/main/java/com/mannschaft/app/line/controller/LineBotConfigController.java) | 134 |
| GET | `/api/v1/organizations/{_}/line/logs` | `LineBotConfigController#logsForOrg` (backend/src/main/java/com/mannschaft/app/line/controller/LineBotConfigController.java) | 162 |
| GET | `/api/v1/organizations/{_}/parking/listings/{_}` | `OrgParkingListingController#getDetail` (backend/src/main/java/com/mannschaft/app/parking/controller/OrgParkingListingController.java) | 62 |
| GET | `/api/v1/organizations/{_}/parking/settings` | `OrgParkingSpaceController#getSettings` (backend/src/main/java/com/mannschaft/app/parking/controller/OrgParkingSpaceController.java) | 191 |
| GET | `/api/v1/organizations/{_}/parking/spaces` | `OrgParkingSpaceController#listSpaces` (backend/src/main/java/com/mannschaft/app/parking/controller/OrgParkingSpaceController.java) | 39 |
| GET | `/api/v1/organizations/{_}/parking/spaces/vacant` | `OrgParkingSpaceController#listVacant` (backend/src/main/java/com/mannschaft/app/parking/controller/OrgParkingSpaceController.java) | 106 |
| GET | `/api/v1/organizations/{_}/parking/spaces/{_}` | `OrgParkingSpaceController#getSpace` (backend/src/main/java/com/mannschaft/app/parking/controller/OrgParkingSpaceController.java) | 73 |
| GET | `/api/v1/organizations/{_}/parking/spaces/{_}/history` | `OrgParkingSpaceController#getHistory` (backend/src/main/java/com/mannschaft/app/parking/controller/OrgParkingSpaceController.java) | 149 |
| GET | `/api/v1/organizations/{_}/parking/spaces/{_}/price-history` | `OrgParkingSpaceController#getPriceHistory` (backend/src/main/java/com/mannschaft/app/parking/controller/OrgParkingSpaceController.java) | 171 |
| GET | `/api/v1/organizations/{_}/parking/stats` | `OrgParkingSpaceController#getStats` (backend/src/main/java/com/mannschaft/app/parking/controller/OrgParkingSpaceController.java) | 184 |
| GET | `/api/v1/organizations/{_}/parking/subleases/{_}` | `OrgParkingSubleaseController#getDetail` (backend/src/main/java/com/mannschaft/app/parking/controller/OrgParkingSubleaseController.java) | 62 |
| GET | `/api/v1/organizations/{_}/parking/subleases/{_}/payments` | `OrgParkingSubleaseController#getPayments` (backend/src/main/java/com/mannschaft/app/parking/controller/OrgParkingSubleaseController.java) | 118 |
| GET | `/api/v1/organizations/{_}/parking/visitor-recurring` | `OrgParkingVisitorController#listRecurring` (backend/src/main/java/com/mannschaft/app/parking/controller/OrgParkingVisitorController.java) | 122 |
| GET | `/api/v1/organizations/{_}/parking/visitor-reservations` | `OrgParkingVisitorController#listReservations` (backend/src/main/java/com/mannschaft/app/parking/controller/OrgParkingVisitorController.java) | 41 |
| GET | `/api/v1/organizations/{_}/parking/visitor-reservations/availability` | `OrgParkingVisitorController#getAvailability` (backend/src/main/java/com/mannschaft/app/parking/controller/OrgParkingVisitorController.java) | 65 |
| GET | `/api/v1/organizations/{_}/parking/visitor-reservations/{_}` | `OrgParkingVisitorController#getReservation` (backend/src/main/java/com/mannschaft/app/parking/controller/OrgParkingVisitorController.java) | 74 |
| GET | `/api/v1/organizations/{_}/point-cards/balance-events` | `OrgPointCardBalanceController#listOrgEvents` (backend/src/main/java/com/mannschaft/app/pointcard/controller/OrgPointCardBalanceController.java) | 97 |
| GET | `/api/v1/organizations/{_}/point-cards/providers/{_}` | `OrgPointCardProviderController#getProvider` (backend/src/main/java/com/mannschaft/app/pointcard/controller/OrgPointCardProviderController.java) | 111 |
| GET | `/api/v1/organizations/{_}/point-cards/providers/{_}/customer-qr` | `OrgPointCardProviderController#getCustomerQr` (backend/src/main/java/com/mannschaft/app/pointcard/controller/OrgPointCardProviderController.java) | 171 |
| GET | `/api/v1/organizations/{_}/point-cards/stamps` | `OrgPointCardStampController#listOrgStamps` (backend/src/main/java/com/mannschaft/app/pointcard/controller/OrgPointCardStampController.java) | 81 |
| GET | `/api/v1/organizations/{_}/point-cards/{_}/balance-events` | `OrgPointCardBalanceController#listCardEvents` (backend/src/main/java/com/mannschaft/app/pointcard/controller/OrgPointCardBalanceController.java) | 113 |
| GET | `/api/v1/organizations/{_}/point-cards/{_}/stamps` | `OrgPointCardStampController#listCardStamps` (backend/src/main/java/com/mannschaft/app/pointcard/controller/OrgPointCardStampController.java) | 97 |
| GET | `/api/v1/organizations/{_}/profile` | `OrganizationExtendedProfileController#getProfile` (backend/src/main/java/com/mannschaft/app/organization/controller/OrganizationExtendedProfileController.java) | 53 |
| GET | `/api/v1/organizations/{_}/promotions` | `OrgPromotionController#list` (backend/src/main/java/com/mannschaft/app/promotion/controller/OrgPromotionController.java) | 41 |
| GET | `/api/v1/organizations/{_}/promotions/{_}` | `OrgPromotionController#get` (backend/src/main/java/com/mannschaft/app/promotion/controller/OrgPromotionController.java) | 63 |
| GET | `/api/v1/organizations/{_}/promotions/{_}/stats` | `OrgPromotionController#getStats` (backend/src/main/java/com/mannschaft/app/promotion/controller/OrgPromotionController.java) | 114 |
| GET | `/api/v1/organizations/{_}/property-history/categories/suggestions` | `PropertyWorkPackageController#categorySuggestions` (backend/src/main/java/com/mannschaft/app/property/controller/PropertyWorkPackageController.java) | 329 |
| GET | `/api/v1/organizations/{_}/property-history/gantt` | `PropertyWorkPackageController#gantt` (backend/src/main/java/com/mannschaft/app/property/controller/PropertyWorkPackageController.java) | 163 |
| GET | `/api/v1/organizations/{_}/property-history/timeline` | `PropertyWorkPackageController#timeline` (backend/src/main/java/com/mannschaft/app/property/controller/PropertyWorkPackageController.java) | 141 |
| GET | `/api/v1/organizations/{_}/property-history/{_}` | `PropertyWorkPackageController#getPackage` (backend/src/main/java/com/mannschaft/app/property/controller/PropertyWorkPackageController.java) | 183 |
| GET | `/api/v1/organizations/{_}/property-listings` | `OrgPropertyListingController#list` (backend/src/main/java/com/mannschaft/app/resident/controller/OrgPropertyListingController.java) | 41 |
| GET | `/api/v1/organizations/{_}/property-listings/{_}` | `OrgPropertyListingController#get` (backend/src/main/java/com/mannschaft/app/resident/controller/OrgPropertyListingController.java) | 64 |
| GET | `/api/v1/organizations/{_}/property-listings/{_}/inquiries` | `OrgPropertyListingController#listInquiries` (backend/src/main/java/com/mannschaft/app/resident/controller/OrgPropertyListingController.java) | 95 |
| GET | `/api/v1/organizations/{_}/recruitment-templates` | `RecruitmentTemplateController#listForOrg` (backend/src/main/java/com/mannschaft/app/recruitment/controller/RecruitmentTemplateController.java) | 96 |
| GET | `/api/v1/organizations/{_}/repair-plan/dashboard` | `RepairPlanDashboardController#getDashboard` (backend/src/main/java/com/mannschaft/app/repairplan/controller/RepairPlanDashboardController.java) | 38 |
| GET | `/api/v1/organizations/{_}/repair-plan/handover-packs/{_}/download` | `BoardHandoverPackController#getDownloadUrl` (backend/src/main/java/com/mannschaft/app/repairplan/controller/BoardHandoverPackController.java) | 86 |
| GET | `/api/v1/organizations/{_}/repair-plan/items/{_}` | `RepairPlanItemController#get` (backend/src/main/java/com/mannschaft/app/repairplan/controller/RepairPlanItemController.java) | 109 |
| GET | `/api/v1/organizations/{_}/repair-plan/quote-kanbans` | `RepairPlanQuoteKanbanController#listKanbans` (backend/src/main/java/com/mannschaft/app/repairplan/controller/RepairPlanQuoteKanbanController.java) | 67 |
| GET | `/api/v1/organizations/{_}/repair-plan/quote-kanbans/{_}` | `RepairPlanQuoteKanbanController#getKanban` (backend/src/main/java/com/mannschaft/app/repairplan/controller/RepairPlanQuoteKanbanController.java) | 97 |
| GET | `/api/v1/organizations/{_}/repair-plan/scenarios/{_}` | `RepairPlanScenarioController#getScenario` (backend/src/main/java/com/mannschaft/app/repairplan/controller/RepairPlanScenarioController.java) | 114 |
| GET | `/api/v1/organizations/{_}/repair-plan/timeline` | `RepairPlanTimelineController#getTimeline` (backend/src/main/java/com/mannschaft/app/repairplan/controller/RepairPlanTimelineController.java) | 41 |
| GET | `/api/v1/organizations/{_}/residence-status/activity-snapshots/{_}` | `ResidenceStatusController#getSnapshots` (backend/src/main/java/com/mannschaft/app/residencestatus/controller/ResidenceStatusController.java) | 45 |
| GET | `/api/v1/organizations/{_}/residence-status/annual-reviews/my` | `AnnualReviewController#listMyReviews` (backend/src/main/java/com/mannschaft/app/residencestatus/controller/AnnualReviewController.java) | 91 |
| GET | `/api/v1/organizations/{_}/residence-status/annual-reviews/{_}` | `AnnualReviewController#getReview` (backend/src/main/java/com/mannschaft/app/residencestatus/controller/AnnualReviewController.java) | 65 |
| GET | `/api/v1/organizations/{_}/residence-status/dashboard` | `ResidenceStatusController#getDashboard` (backend/src/main/java/com/mannschaft/app/residencestatus/controller/ResidenceStatusController.java) | 64 |
| GET | `/api/v1/organizations/{_}/residence-status/monitoring-visits/by-watcher/{_}` | `MonitoringCommitteeVisitController#getVisitsByWatcher` (backend/src/main/java/com/mannschaft/app/residencestatus/controller/MonitoringCommitteeVisitController.java) | 92 |
| GET | `/api/v1/organizations/{_}/residence-status/org-wide-safety-checks/active` | `OrgWideSafetyCheckController#getActiveChecks` (backend/src/main/java/com/mannschaft/app/residencestatus/controller/OrgWideSafetyCheckController.java) | 55 |
| GET | `/api/v1/organizations/{_}/residents/{_}/documents` | `OrgResidentDocumentController#list` (backend/src/main/java/com/mannschaft/app/resident/controller/OrgResidentDocumentController.java) | 42 |
| GET | `/api/v1/organizations/{_}/segment-presets` | `OrgSegmentPresetController#list` (backend/src/main/java/com/mannschaft/app/promotion/controller/OrgSegmentPresetController.java) | 34 |
| GET | `/api/v1/organizations/{_}/sns/feeds` | `SnsFeedConfigController#listForOrg` (backend/src/main/java/com/mannschaft/app/line/controller/SnsFeedConfigController.java) | 91 |
| GET | `/api/v1/organizations/{_}/sns/feeds/{_}/preview` | `SnsFeedConfigController#previewForOrg` (backend/src/main/java/com/mannschaft/app/line/controller/SnsFeedConfigController.java) | 132 |
| GET | `/api/v1/organizations/{_}/succession/covenants` | `SuccessionCovenantController#listOrgCovenants` (backend/src/main/java/com/mannschaft/app/succession/controller/SuccessionCovenantController.java) | 93 |
| GET | `/api/v1/organizations/{_}/succession/covenants/{_}` | `SuccessionCovenantController#getCovenant` (backend/src/main/java/com/mannschaft/app/succession/controller/SuccessionCovenantController.java) | 82 |
| GET | `/api/v1/organizations/{_}/succession/delinquency-escalations` | `DelinquencyEscalationController#listActive` (backend/src/main/java/com/mannschaft/app/succession/controller/DelinquencyEscalationController.java) | 51 |
| GET | `/api/v1/organizations/{_}/succession/delinquency-escalations/{_}` | `DelinquencyEscalationController#getById` (backend/src/main/java/com/mannschaft/app/succession/controller/DelinquencyEscalationController.java) | 73 |
| GET | `/api/v1/organizations/{_}/succession/legal-filings` | `LegalFilingController#listByOrganization` (backend/src/main/java/com/mannschaft/app/succession/controller/LegalFilingController.java) | 53 |
| GET | `/api/v1/organizations/{_}/succession/legal-filings/by-resident/{_}` | `LegalFilingController#listByResident` (backend/src/main/java/com/mannschaft/app/succession/controller/LegalFilingController.java) | 73 |
| GET | `/api/v1/organizations/{_}/succession/legal-filings/{_}` | `LegalFilingController#getById` (backend/src/main/java/com/mannschaft/app/succession/controller/LegalFilingController.java) | 122 |
| GET | `/api/v1/organizations/{_}/succession/legal-filings/{_}/evidence-package/download-url` | `LegalFilingController#getEvidenceDownloadUrl` (backend/src/main/java/com/mannschaft/app/succession/controller/LegalFilingController.java) | 164 |
| GET | `/api/v1/organizations/{_}/succession/unseal-requests` | `UnsealRequestController#listRequests` (backend/src/main/java/com/mannschaft/app/succession/controller/UnsealRequestController.java) | 134 |
| GET | `/api/v1/organizations/{_}/succession/unseal-requests/{_}` | `UnsealRequestController#getRequest` (backend/src/main/java/com/mannschaft/app/succession/controller/UnsealRequestController.java) | 154 |
| GET | `/api/v1/organizations/{_}/supporter-applications` | `OrganizationController#getSupporterApplications` (backend/src/main/java/com/mannschaft/app/organization/controller/OrganizationController.java) | 215 |
| GET | `/api/v1/organizations/{_}/supporter-settings` | `OrganizationController#getSupporterSettings` (backend/src/main/java/com/mannschaft/app/organization/controller/OrganizationController.java) | 250 |
| GET | `/api/v1/organizations/{_}/supporters` | `OrganizationController#getSupporters` (backend/src/main/java/com/mannschaft/app/organization/controller/OrganizationController.java) | 207 |
| GET | `/api/v1/organizations/{_}/surveys/stats` | `SurveyController#getStats` (backend/src/main/java/com/mannschaft/app/survey/controller/SurveyController.java) | 174 |
| GET | `/api/v1/organizations/{_}/surveys/{_}` | `SurveyController#getSurvey` (backend/src/main/java/com/mannschaft/app/survey/controller/SurveyController.java) | 69 |
| GET | `/api/v1/organizations/{_}/surveys/{_}/respondents` | `SurveyController#getRespondents` (backend/src/main/java/com/mannschaft/app/survey/controller/SurveyController.java) | 158 |
| GET | `/api/v1/organizations/{_}/teams/{_}/entry-templates` | `TournamentEntryTemplateController#getTemplates` (backend/src/main/java/com/mannschaft/app/tournament/entry/TournamentEntryTemplateController.java) | 50 |
| GET | `/api/v1/organizations/{_}/teams/{_}/entry-templates/{_}` | `TournamentEntryTemplateController#getTemplate` (backend/src/main/java/com/mannschaft/app/tournament/entry/TournamentEntryTemplateController.java) | 87 |
| GET | `/api/v1/organizations/{_}/todos/gantt` | `OrgTodoController#getGanttTodos` (backend/src/main/java/com/mannschaft/app/todo/controller/OrgTodoController.java) | 337 |
| GET | `/api/v1/organizations/{_}/todos/{_}` | `OrgTodoController#getTodo` (backend/src/main/java/com/mannschaft/app/todo/controller/OrgTodoController.java) | 112 |
| GET | `/api/v1/organizations/{_}/todos/{_}/children` | `OrgTodoController#getChildTodos` (backend/src/main/java/com/mannschaft/app/todo/controller/OrgTodoController.java) | 126 |
| GET | `/api/v1/organizations/{_}/todos/{_}/comments` | `OrgTodoController#listComments` (backend/src/main/java/com/mannschaft/app/todo/controller/OrgTodoController.java) | 248 |
| GET | `/api/v1/organizations/{_}/todos/{_}/memos` | `OrgTodoController#listSharedMemos` (backend/src/main/java/com/mannschaft/app/todo/controller/OrgTodoController.java) | 384 |
| GET | `/api/v1/organizations/{_}/todos/{_}/my-memo` | `OrgTodoController#getPersonalMemo` (backend/src/main/java/com/mannschaft/app/todo/controller/OrgTodoController.java) | 444 |
| GET | `/api/v1/organizations/{_}/tournament-templates` | `TournamentTemplateController#listTemplates` (backend/src/main/java/com/mannschaft/app/tournament/controller/TournamentTemplateController.java) | 43 |
| GET | `/api/v1/organizations/{_}/tournament-templates/{_}` | `TournamentTemplateController#getTemplate` (backend/src/main/java/com/mannschaft/app/tournament/controller/TournamentTemplateController.java) | 72 |
| GET | `/api/v1/organizations/{_}/tournaments/{_}` | `TournamentController#getTournament` (backend/src/main/java/com/mannschaft/app/tournament/controller/TournamentController.java) | 64 |
| GET | `/api/v1/organizations/{_}/tournaments/{_}/bracket/pdf` | `TournamentPdfController#getBracketPdf` (backend/src/main/java/com/mannschaft/app/tournament/controller/TournamentPdfController.java) | 73 |
| GET | `/api/v1/organizations/{_}/tournaments/{_}/divisions` | `DivisionController#listDivisions` (backend/src/main/java/com/mannschaft/app/tournament/controller/DivisionController.java) | 42 |
| GET | `/api/v1/organizations/{_}/tournaments/{_}/divisions/{_}/entry-summary` | `TournamentEntryMemberController#getEntrySummary` (backend/src/main/java/com/mannschaft/app/tournament/entry/TournamentEntryMemberController.java) | 171 |
| GET | `/api/v1/organizations/{_}/tournaments/{_}/divisions/{_}/matchdays` | `MatchController#listMatchdays` (backend/src/main/java/com/mannschaft/app/tournament/controller/MatchController.java) | 55 |
| GET | `/api/v1/organizations/{_}/tournaments/{_}/divisions/{_}/matrix` | `StandingsController#getMatrix` (backend/src/main/java/com/mannschaft/app/tournament/controller/StandingsController.java) | 48 |
| GET | `/api/v1/organizations/{_}/tournaments/{_}/divisions/{_}/matrix/pdf` | `TournamentPdfController#getMatrixPdf` (backend/src/main/java/com/mannschaft/app/tournament/controller/TournamentPdfController.java) | 126 |
| GET | `/api/v1/organizations/{_}/tournaments/{_}/divisions/{_}/participants` | `DivisionController#listParticipants` (backend/src/main/java/com/mannschaft/app/tournament/controller/DivisionController.java) | 76 |
| GET | `/api/v1/organizations/{_}/tournaments/{_}/divisions/{_}/participants/{_}/entry-members` | `TournamentEntryMemberController#getEntryMembers` (backend/src/main/java/com/mannschaft/app/tournament/entry/TournamentEntryMemberController.java) | 53 |
| GET | `/api/v1/organizations/{_}/tournaments/{_}/divisions/{_}/participants/{_}/entry-members/pdf` | `TournamentEntryMemberController#generateEntryPdf` (backend/src/main/java/com/mannschaft/app/tournament/entry/TournamentEntryMemberController.java) | 148 |
| GET | `/api/v1/organizations/{_}/tournaments/{_}/divisions/{_}/standings` | `StandingsController#getStandings` (backend/src/main/java/com/mannschaft/app/tournament/controller/StandingsController.java) | 41 |
| GET | `/api/v1/organizations/{_}/tournaments/{_}/divisions/{_}/standings/pdf` | `TournamentPdfController#getStandingsPdf` (backend/src/main/java/com/mannschaft/app/tournament/controller/TournamentPdfController.java) | 48 |
| GET | `/api/v1/organizations/{_}/tournaments/{_}/matches/{_}` | `MatchController#getMatch` (backend/src/main/java/com/mannschaft/app/tournament/controller/MatchController.java) | 81 |
| GET | `/api/v1/organizations/{_}/tournaments/{_}/matches/{_}/rosters` | `MatchController#listRosters` (backend/src/main/java/com/mannschaft/app/tournament/controller/MatchController.java) | 173 |
| GET | `/api/v1/organizations/{_}/tournaments/{_}/rankings` | `StandingsController#getRankingSummary` (backend/src/main/java/com/mannschaft/app/tournament/controller/StandingsController.java) | 67 |
| GET | `/api/v1/organizations/{_}/tournaments/{_}/rankings/{_}` | `StandingsController#getRankings` (backend/src/main/java/com/mannschaft/app/tournament/controller/StandingsController.java) | 55 |
| GET | `/api/v1/organizations/{_}/tournaments/{_}/rankings/{_}/pdf` | `TournamentPdfController#getRankingsPdf` (backend/src/main/java/com/mannschaft/app/tournament/controller/TournamentPdfController.java) | 96 |
| GET | `/api/v1/organizations/{_}/translations/assignments/me` | `TranslationAssignmentController#listMyOrgAssignments` (backend/src/main/java/com/mannschaft/app/translation/controller/TranslationAssignmentController.java) | 173 |
| GET | `/api/v1/organizations/{_}/translations/content` | `ContentTranslationController#getOrgTranslationForContent` (backend/src/main/java/com/mannschaft/app/translation/controller/ContentTranslationController.java) | 261 |
| GET | `/api/v1/organizations/{_}/translations/content/all` | `ContentTranslationController#listOrgTranslationsForContent` (backend/src/main/java/com/mannschaft/app/translation/controller/ContentTranslationController.java) | 274 |
| GET | `/api/v1/organizations/{_}/vendors/search` | `VendorController#searchVendors` (backend/src/main/java/com/mannschaft/app/property/controller/VendorController.java) | 103 |
| GET | `/api/v1/organizations/{_}/vendors/{_}` | `VendorController#getVendor` (backend/src/main/java/com/mannschaft/app/property/controller/VendorController.java) | 117 |
| GET | `/api/v1/organizations/{_}/workflow-requests/{_}` | `WorkflowRequestController#getRequest` (backend/src/main/java/com/mannschaft/app/workflow/controller/WorkflowRequestController.java) | 62 |
| GET | `/api/v1/organizations/{_}/workflow-templates/{_}` | `WorkflowTemplateController#getTemplate` (backend/src/main/java/com/mannschaft/app/workflow/controller/WorkflowTemplateController.java) | 61 |
| PATCH | `/api/v1/organizations/{_}/announcements/{_}/pin` | `AnnouncementFeedOrgController#togglePin` (backend/src/main/java/com/mannschaft/app/social/announcement/controller/AnnouncementFeedOrgController.java) | 182 |
| PATCH | `/api/v1/organizations/{_}/confirmable-notifications/{_}/cancel` | `OrgConfirmableNotificationController#cancel` (backend/src/main/java/com/mannschaft/app/notification/confirmable/controller/OrgConfirmableNotificationController.java) | 137 |
| PATCH | `/api/v1/organizations/{_}/coupons/{_}/toggle` | `OrgCouponController#toggle` (backend/src/main/java/com/mannschaft/app/promotion/controller/OrgCouponController.java) | 80 |
| PATCH | `/api/v1/organizations/{_}/facilities/bookings/{_}` | `OrgFacilityBookingController#updateBooking` (backend/src/main/java/com/mannschaft/app/facility/controller/OrgFacilityBookingController.java) | 87 |
| PATCH | `/api/v1/organizations/{_}/facilities/bookings/{_}/approve` | `OrgFacilityBookingController#approveBooking` (backend/src/main/java/com/mannschaft/app/facility/controller/OrgFacilityBookingController.java) | 109 |
| PATCH | `/api/v1/organizations/{_}/facilities/bookings/{_}/check-in` | `OrgFacilityBookingController#checkIn` (backend/src/main/java/com/mannschaft/app/facility/controller/OrgFacilityBookingController.java) | 131 |
| PATCH | `/api/v1/organizations/{_}/facilities/bookings/{_}/complete` | `OrgFacilityBookingController#completeBooking` (backend/src/main/java/com/mannschaft/app/facility/controller/OrgFacilityBookingController.java) | 141 |
| PATCH | `/api/v1/organizations/{_}/facilities/bookings/{_}/payment/confirm` | `OrgFacilityBookingController#confirmPayment` (backend/src/main/java/com/mannschaft/app/facility/controller/OrgFacilityBookingController.java) | 161 |
| PATCH | `/api/v1/organizations/{_}/facilities/bookings/{_}/reject` | `OrgFacilityBookingController#rejectBooking` (backend/src/main/java/com/mannschaft/app/facility/controller/OrgFacilityBookingController.java) | 120 |
| PATCH | `/api/v1/organizations/{_}/parking/applications/{_}/approve` | `OrgParkingApplicationController#approve` (backend/src/main/java/com/mannschaft/app/parking/controller/OrgParkingApplicationController.java) | 64 |
| PATCH | `/api/v1/organizations/{_}/parking/applications/{_}/reject` | `OrgParkingApplicationController#reject` (backend/src/main/java/com/mannschaft/app/parking/controller/OrgParkingApplicationController.java) | 72 |
| PATCH | `/api/v1/organizations/{_}/parking/listings/{_}/transfer` | `OrgParkingListingController#transfer` (backend/src/main/java/com/mannschaft/app/parking/controller/OrgParkingListingController.java) | 99 |
| PATCH | `/api/v1/organizations/{_}/parking/spaces/{_}/accept-applications` | `OrgParkingSpaceController#acceptApplications` (backend/src/main/java/com/mannschaft/app/parking/controller/OrgParkingSpaceController.java) | 140 |
| PATCH | `/api/v1/organizations/{_}/parking/spaces/{_}/maintenance` | `OrgParkingSpaceController#toggleMaintenance` (backend/src/main/java/com/mannschaft/app/parking/controller/OrgParkingSpaceController.java) | 131 |
| PATCH | `/api/v1/organizations/{_}/parking/subleases/{_}/approve` | `OrgParkingSubleaseController#approve` (backend/src/main/java/com/mannschaft/app/parking/controller/OrgParkingSubleaseController.java) | 99 |
| PATCH | `/api/v1/organizations/{_}/parking/subleases/{_}/terminate` | `OrgParkingSubleaseController#terminate` (backend/src/main/java/com/mannschaft/app/parking/controller/OrgParkingSubleaseController.java) | 109 |
| PATCH | `/api/v1/organizations/{_}/parking/visitor-reservations/{_}/approve` | `OrgParkingVisitorController#approveReservation` (backend/src/main/java/com/mannschaft/app/parking/controller/OrgParkingVisitorController.java) | 82 |
| PATCH | `/api/v1/organizations/{_}/parking/visitor-reservations/{_}/check-in` | `OrgParkingVisitorController#checkIn` (backend/src/main/java/com/mannschaft/app/parking/controller/OrgParkingVisitorController.java) | 99 |
| PATCH | `/api/v1/organizations/{_}/parking/visitor-reservations/{_}/complete` | `OrgParkingVisitorController#complete` (backend/src/main/java/com/mannschaft/app/parking/controller/OrgParkingVisitorController.java) | 107 |
| PATCH | `/api/v1/organizations/{_}/parking/visitor-reservations/{_}/reject` | `OrgParkingVisitorController#rejectReservation` (backend/src/main/java/com/mannschaft/app/parking/controller/OrgParkingVisitorController.java) | 90 |
| PATCH | `/api/v1/organizations/{_}/point-cards/providers/{_}` | `OrgPointCardProviderController#updateProvider` (backend/src/main/java/com/mannschaft/app/pointcard/controller/OrgPointCardProviderController.java) | 130 |
| PATCH | `/api/v1/organizations/{_}/property-history/{_}/status` | `PropertyWorkPackageController#changeStatus` (backend/src/main/java/com/mannschaft/app/property/controller/PropertyWorkPackageController.java) | 222 |
| PATCH | `/api/v1/organizations/{_}/repair-plan/items/{_}` | `RepairPlanItemController#update` (backend/src/main/java/com/mannschaft/app/repairplan/controller/RepairPlanItemController.java) | 121 |
| PATCH | `/api/v1/organizations/{_}/repair-plan/quote-kanbans/{_}` | `RepairPlanQuoteKanbanController#updateKanban` (backend/src/main/java/com/mannschaft/app/repairplan/controller/RepairPlanQuoteKanbanController.java) | 111 |
| PATCH | `/api/v1/organizations/{_}/residents/{_}/move-out` | `OrgResidentController#moveOut` (backend/src/main/java/com/mannschaft/app/resident/controller/OrgResidentController.java) | 74 |
| PATCH | `/api/v1/organizations/{_}/residents/{_}/verify` | `OrgResidentController#verify` (backend/src/main/java/com/mannschaft/app/resident/controller/OrgResidentController.java) | 67 |
| PATCH | `/api/v1/organizations/{_}/surveys/{_}` | `SurveyController#updateSurvey` (backend/src/main/java/com/mannschaft/app/survey/controller/SurveyController.java) | 98 |
| PATCH | `/api/v1/organizations/{_}/todos/bulk-status` | `OrgTodoController#bulkChangeStatus` (backend/src/main/java/com/mannschaft/app/todo/controller/OrgTodoController.java) | 199 |
| PATCH | `/api/v1/organizations/{_}/todos/{_}` | `OrgTodoController#patchTodo` (backend/src/main/java/com/mannschaft/app/todo/controller/OrgTodoController.java) | 153 |
| PATCH | `/api/v1/organizations/{_}/todos/{_}/progress` | `OrgTodoController#setProgressRate` (backend/src/main/java/com/mannschaft/app/todo/controller/OrgTodoController.java) | 356 |
| PATCH | `/api/v1/organizations/{_}/todos/{_}/progress-mode` | `OrgTodoController#setProgressMode` (backend/src/main/java/com/mannschaft/app/todo/controller/OrgTodoController.java) | 369 |
| PATCH | `/api/v1/organizations/{_}/tournament-templates/{_}` | `TournamentTemplateController#updateTemplate` (backend/src/main/java/com/mannschaft/app/tournament/controller/TournamentTemplateController.java) | 80 |
| PATCH | `/api/v1/organizations/{_}/tournaments/{_}` | `TournamentController#updateTournament` (backend/src/main/java/com/mannschaft/app/tournament/controller/TournamentController.java) | 72 |
| PATCH | `/api/v1/organizations/{_}/tournaments/{_}/divisions/{_}` | `DivisionController#updateDivision` (backend/src/main/java/com/mannschaft/app/tournament/controller/DivisionController.java) | 58 |
| PATCH | `/api/v1/organizations/{_}/tournaments/{_}/divisions/{_}/participants/{_}` | `DivisionController#updateParticipant` (backend/src/main/java/com/mannschaft/app/tournament/controller/DivisionController.java) | 92 |
| PATCH | `/api/v1/organizations/{_}/tournaments/{_}/matches/{_}/player-stats` | `MatchController#updatePlayerStats` (backend/src/main/java/com/mannschaft/app/tournament/controller/MatchController.java) | 96 |
| PATCH | `/api/v1/organizations/{_}/tournaments/{_}/matches/{_}/score` | `MatchController#updateScore` (backend/src/main/java/com/mannschaft/app/tournament/controller/MatchController.java) | 88 |
| PATCH | `/api/v1/organizations/{_}/tournaments/{_}/matches/{_}/status` | `MatchController#changeMatchStatus` (backend/src/main/java/com/mannschaft/app/tournament/controller/MatchController.java) | 104 |
| PATCH | `/api/v1/organizations/{_}/tournaments/{_}/status` | `TournamentController#changeStatus` (backend/src/main/java/com/mannschaft/app/tournament/controller/TournamentController.java) | 90 |
| PATCH | `/api/v1/organizations/{_}/translations/{_}/publish` | `ContentTranslationController#publishOrgTranslation` (backend/src/main/java/com/mannschaft/app/translation/controller/ContentTranslationController.java) | 326 |
| POST | `/api/v1/organizations/{_}/announcement-templates` | `AnnouncementRangeTemplateController#createOrgTemplate` (backend/src/main/java/com/mannschaft/app/social/announcement/controller/AnnouncementRangeTemplateController.java) | 201 |
| POST | `/api/v1/organizations/{_}/announcements/read-all` | `AnnouncementFeedOrgController#markAllAsRead` (backend/src/main/java/com/mannschaft/app/social/announcement/controller/AnnouncementFeedOrgController.java) | 242 |
| POST | `/api/v1/organizations/{_}/announcements/{_}/read` | `AnnouncementFeedOrgController#markAsRead` (backend/src/main/java/com/mannschaft/app/social/announcement/controller/AnnouncementFeedOrgController.java) | 216 |
| POST | `/api/v1/organizations/{_}/broadcast` | `AnnouncementBroadcastController#broadcastToOrg` (backend/src/main/java/com/mannschaft/app/social/announcement/controller/AnnouncementBroadcastController.java) | 91 |
| POST | `/api/v1/organizations/{_}/bulletin/threads/{_}/archive` | `BulletinThreadController#archive` (backend/src/main/java/com/mannschaft/app/bulletin/controller/BulletinThreadController.java) | 178 |
| POST | `/api/v1/organizations/{_}/bulletin/threads/{_}/lock` | `BulletinThreadController#toggleLock` (backend/src/main/java/com/mannschaft/app/bulletin/controller/BulletinThreadController.java) | 163 |
| POST | `/api/v1/organizations/{_}/bulletin/threads/{_}/pin` | `BulletinThreadController#togglePin` (backend/src/main/java/com/mannschaft/app/bulletin/controller/BulletinThreadController.java) | 148 |
| POST | `/api/v1/organizations/{_}/circulations/{_}/activate` | `OrgCirculationDocumentController#activateDocument` (backend/src/main/java/com/mannschaft/app/circulation/controller/OrgCirculationDocumentController.java) | 87 |
| POST | `/api/v1/organizations/{_}/confirmable-notifications/{_}/confirm` | `OrgConfirmableNotificationController#confirm` (backend/src/main/java/com/mannschaft/app/notification/confirmable/controller/OrgConfirmableNotificationController.java) | 202 |
| POST | `/api/v1/organizations/{_}/confirmable-notifications/{_}/resend-reminder` | `OrgConfirmableNotificationController#resendReminder` (backend/src/main/java/com/mannschaft/app/notification/confirmable/controller/OrgConfirmableNotificationController.java) | 153 |
| POST | `/api/v1/organizations/{_}/coupons` | `OrgCouponController#create` (backend/src/main/java/com/mannschaft/app/promotion/controller/OrgCouponController.java) | 49 |
| POST | `/api/v1/organizations/{_}/direct-mails/estimate-recipients` | `OrganizationDirectMailController#estimateRecipients` (backend/src/main/java/com/mannschaft/app/directmail/controller/OrganizationDirectMailController.java) | 172 |
| POST | `/api/v1/organizations/{_}/direct-mails/preview` | `OrganizationDirectMailController#preview` (backend/src/main/java/com/mannschaft/app/directmail/controller/OrganizationDirectMailController.java) | 160 |
| POST | `/api/v1/organizations/{_}/direct-mails/{_}/cancel` | `OrganizationDirectMailController#cancelMail` (backend/src/main/java/com/mannschaft/app/directmail/controller/OrganizationDirectMailController.java) | 122 |
| POST | `/api/v1/organizations/{_}/direct-mails/{_}/schedule` | `OrganizationDirectMailController#scheduleMail` (backend/src/main/java/com/mannschaft/app/directmail/controller/OrganizationDirectMailController.java) | 109 |
| POST | `/api/v1/organizations/{_}/direct-mails/{_}/send` | `OrganizationDirectMailController#sendMail` (backend/src/main/java/com/mannschaft/app/directmail/controller/OrganizationDirectMailController.java) | 97 |
| POST | `/api/v1/organizations/{_}/dwelling-units` | `OrgDwellingUnitController#create` (backend/src/main/java/com/mannschaft/app/resident/controller/OrgDwellingUnitController.java) | 50 |
| POST | `/api/v1/organizations/{_}/dwelling-units/batch` | `OrgDwellingUnitController#batchCreate` (backend/src/main/java/com/mannschaft/app/resident/controller/OrgDwellingUnitController.java) | 80 |
| POST | `/api/v1/organizations/{_}/dwelling-units/{_}/residents` | `OrgResidentController#create` (backend/src/main/java/com/mannschaft/app/resident/controller/OrgResidentController.java) | 44 |
| POST | `/api/v1/organizations/{_}/events/{_}/close-registration` | `OrgEventController#closeRegistration` (backend/src/main/java/com/mannschaft/app/event/controller/OrgEventController.java) | 129 |
| POST | `/api/v1/organizations/{_}/events/{_}/open-registration` | `OrgEventController#openRegistration` (backend/src/main/java/com/mannschaft/app/event/controller/OrgEventController.java) | 116 |
| POST | `/api/v1/organizations/{_}/facilities/bulk-create` | `OrgFacilityController#bulkCreateFacilities` (backend/src/main/java/com/mannschaft/app/facility/controller/OrgFacilityController.java) | 82 |
| POST | `/api/v1/organizations/{_}/facilities/{_}/equipment` | `OrgFacilityController#createEquipment` (backend/src/main/java/com/mannschaft/app/facility/controller/OrgFacilityController.java) | 181 |
| POST | `/api/v1/organizations/{_}/form-templates/{_}/close` | `FormTemplateController#closeTemplate` (backend/src/main/java/com/mannschaft/app/forms/controller/FormTemplateController.java) | 121 |
| POST | `/api/v1/organizations/{_}/form-templates/{_}/publish` | `FormTemplateController#publishTemplate` (backend/src/main/java/com/mannschaft/app/forms/controller/FormTemplateController.java) | 107 |
| POST | `/api/v1/organizations/{_}/form-templates/{_}/submissions/{_}/approve` | `FormSubmissionAdminController#approveSubmission` (backend/src/main/java/com/mannschaft/app/forms/controller/FormSubmissionAdminController.java) | 54 |
| POST | `/api/v1/organizations/{_}/form-templates/{_}/submissions/{_}/reject` | `FormSubmissionAdminController#rejectSubmission` (backend/src/main/java/com/mannschaft/app/forms/controller/FormSubmissionAdminController.java) | 69 |
| POST | `/api/v1/organizations/{_}/form-templates/{_}/submissions/{_}/return` | `FormSubmissionAdminController#returnSubmission` (backend/src/main/java/com/mannschaft/app/forms/controller/FormSubmissionAdminController.java) | 84 |
| POST | `/api/v1/organizations/{_}/line/config` | `LineBotConfigController#createForOrg` (backend/src/main/java/com/mannschaft/app/line/controller/LineBotConfigController.java) | 110 |
| POST | `/api/v1/organizations/{_}/line/test` | `LineBotConfigController#sendTestForOrg` (backend/src/main/java/com/mannschaft/app/line/controller/LineBotConfigController.java) | 151 |
| POST | `/api/v1/organizations/{_}/parking/applications/lottery` | `OrgParkingApplicationController#lottery` (backend/src/main/java/com/mannschaft/app/parking/controller/OrgParkingApplicationController.java) | 88 |
| POST | `/api/v1/organizations/{_}/parking/listings/{_}/apply` | `OrgParkingListingController#apply` (backend/src/main/java/com/mannschaft/app/parking/controller/OrgParkingListingController.java) | 89 |
| POST | `/api/v1/organizations/{_}/parking/spaces` | `OrgParkingSpaceController#createSpace` (backend/src/main/java/com/mannschaft/app/parking/controller/OrgParkingSpaceController.java) | 55 |
| POST | `/api/v1/organizations/{_}/parking/spaces/bulk-assign` | `OrgParkingSpaceController#bulkAssign` (backend/src/main/java/com/mannschaft/app/parking/controller/OrgParkingSpaceController.java) | 97 |
| POST | `/api/v1/organizations/{_}/parking/spaces/bulk-create` | `OrgParkingSpaceController#bulkCreateSpaces` (backend/src/main/java/com/mannschaft/app/parking/controller/OrgParkingSpaceController.java) | 64 |
| POST | `/api/v1/organizations/{_}/parking/spaces/swap` | `OrgParkingSpaceController#swap` (backend/src/main/java/com/mannschaft/app/parking/controller/OrgParkingSpaceController.java) | 162 |
| POST | `/api/v1/organizations/{_}/parking/spaces/{_}/assign` | `OrgParkingSpaceController#assign` (backend/src/main/java/com/mannschaft/app/parking/controller/OrgParkingSpaceController.java) | 113 |
| POST | `/api/v1/organizations/{_}/parking/spaces/{_}/release` | `OrgParkingSpaceController#release` (backend/src/main/java/com/mannschaft/app/parking/controller/OrgParkingSpaceController.java) | 122 |
| POST | `/api/v1/organizations/{_}/parking/subleases/{_}/apply` | `OrgParkingSubleaseController#apply` (backend/src/main/java/com/mannschaft/app/parking/controller/OrgParkingSubleaseController.java) | 89 |
| POST | `/api/v1/organizations/{_}/parking/visitor-recurring` | `OrgParkingVisitorController#createRecurring` (backend/src/main/java/com/mannschaft/app/parking/controller/OrgParkingVisitorController.java) | 129 |
| POST | `/api/v1/organizations/{_}/parking/visitor-reservations` | `OrgParkingVisitorController#createReservation` (backend/src/main/java/com/mannschaft/app/parking/controller/OrgParkingVisitorController.java) | 56 |
| POST | `/api/v1/organizations/{_}/point-cards/resolve-by-token` | `OrgPointCardResolveController#resolveByToken` (backend/src/main/java/com/mannschaft/app/pointcard/controller/OrgPointCardResolveController.java) | 59 |
| POST | `/api/v1/organizations/{_}/point-cards/{_}/balance-events` | `OrgPointCardBalanceController#recordEvent` (backend/src/main/java/com/mannschaft/app/pointcard/controller/OrgPointCardBalanceController.java) | 68 |
| POST | `/api/v1/organizations/{_}/point-cards/{_}/stamps` | `OrgPointCardStampController#stamp` (backend/src/main/java/com/mannschaft/app/pointcard/controller/OrgPointCardStampController.java) | 58 |
| POST | `/api/v1/organizations/{_}/promotions` | `OrgPromotionController#create` (backend/src/main/java/com/mannschaft/app/promotion/controller/OrgPromotionController.java) | 54 |
| POST | `/api/v1/organizations/{_}/promotions/estimate-audience` | `OrgPromotionController#estimateAudience` (backend/src/main/java/com/mannschaft/app/promotion/controller/OrgPromotionController.java) | 121 |
| POST | `/api/v1/organizations/{_}/promotions/{_}/approve` | `OrgPromotionController#approve` (backend/src/main/java/com/mannschaft/app/promotion/controller/OrgPromotionController.java) | 107 |
| POST | `/api/v1/organizations/{_}/promotions/{_}/cancel` | `OrgPromotionController#cancel` (backend/src/main/java/com/mannschaft/app/promotion/controller/OrgPromotionController.java) | 100 |
| POST | `/api/v1/organizations/{_}/promotions/{_}/publish` | `OrgPromotionController#publish` (backend/src/main/java/com/mannschaft/app/promotion/controller/OrgPromotionController.java) | 85 |
| POST | `/api/v1/organizations/{_}/promotions/{_}/schedule` | `OrgPromotionController#schedule` (backend/src/main/java/com/mannschaft/app/promotion/controller/OrgPromotionController.java) | 92 |
| POST | `/api/v1/organizations/{_}/property-history/export` | `PropertyWorkPackageController#exportList` (backend/src/main/java/com/mannschaft/app/property/controller/PropertyWorkPackageController.java) | 300 |
| POST | `/api/v1/organizations/{_}/property-history/{_}/documents` | `PropertyWorkPackageController#attachDocument` (backend/src/main/java/com/mannschaft/app/property/controller/PropertyWorkPackageController.java) | 249 |
| POST | `/api/v1/organizations/{_}/property-history/{_}/export` | `PropertyWorkPackageController#exportSingle` (backend/src/main/java/com/mannschaft/app/property/controller/PropertyWorkPackageController.java) | 287 |
| POST | `/api/v1/organizations/{_}/property-listings` | `OrgPropertyListingController#create` (backend/src/main/java/com/mannschaft/app/resident/controller/OrgPropertyListingController.java) | 55 |
| POST | `/api/v1/organizations/{_}/property-listings/{_}/inquiries` | `OrgPropertyListingController#createInquiry` (backend/src/main/java/com/mannschaft/app/resident/controller/OrgPropertyListingController.java) | 86 |
| POST | `/api/v1/organizations/{_}/proxy-input-consents/scan-upload-url` | `ProxyInputConsentController#generateScanUploadUrl` (backend/src/main/java/com/mannschaft/app/proxy/controller/ProxyInputConsentController.java) | 136 |
| POST | `/api/v1/organizations/{_}/recruitment-listings/from-template` | `RecruitmentTemplateController#createFromTemplateForOrg` (backend/src/main/java/com/mannschaft/app/recruitment/controller/RecruitmentTemplateController.java) | 110 |
| POST | `/api/v1/organizations/{_}/recruitment-templates` | `RecruitmentTemplateController#createForOrg` (backend/src/main/java/com/mannschaft/app/recruitment/controller/RecruitmentTemplateController.java) | 86 |
| POST | `/api/v1/organizations/{_}/repair-plan/items/import-csv` | `RepairPlanItemCsvController#preview` (backend/src/main/java/com/mannschaft/app/repairplan/controller/RepairPlanItemCsvController.java) | 39 |
| POST | `/api/v1/organizations/{_}/repair-plan/items/import-csv/confirm` | `RepairPlanItemCsvController#confirm` (backend/src/main/java/com/mannschaft/app/repairplan/controller/RepairPlanItemCsvController.java) | 55 |
| POST | `/api/v1/organizations/{_}/repair-plan/quote-cards/{_}/move` | `RepairPlanQuoteKanbanController#moveCard` (backend/src/main/java/com/mannschaft/app/repairplan/controller/RepairPlanQuoteKanbanController.java) | 147 |
| POST | `/api/v1/organizations/{_}/repair-plan/quote-kanbans` | `RepairPlanQuoteKanbanController#createKanban` (backend/src/main/java/com/mannschaft/app/repairplan/controller/RepairPlanQuoteKanbanController.java) | 81 |
| POST | `/api/v1/organizations/{_}/repair-plan/quote-kanbans/{_}/cards` | `RepairPlanQuoteKanbanController#addCard` (backend/src/main/java/com/mannschaft/app/repairplan/controller/RepairPlanQuoteKanbanController.java) | 129 |
| POST | `/api/v1/organizations/{_}/repair-plan/scenarios/simulate` | `RepairPlanScenarioController#simulate` (backend/src/main/java/com/mannschaft/app/repairplan/controller/RepairPlanScenarioController.java) | 69 |
| POST | `/api/v1/organizations/{_}/repair-plan/scenarios/{_}/pin-to-corkboard` | `RepairPlanScenarioController#pinToCorkboard` (backend/src/main/java/com/mannschaft/app/repairplan/controller/RepairPlanScenarioController.java) | 145 |
| POST | `/api/v1/organizations/{_}/repair-plan/scenarios/{_}/publish-as-announcement` | `RepairPlanScenarioController#publishAsAnnouncement` (backend/src/main/java/com/mannschaft/app/repairplan/controller/RepairPlanScenarioController.java) | 128 |
| POST | `/api/v1/organizations/{_}/residence-status/annual-reviews/{_}/close` | `AnnualReviewController#closeReview` (backend/src/main/java/com/mannschaft/app/residencestatus/controller/AnnualReviewController.java) | 78 |
| POST | `/api/v1/organizations/{_}/residents/{_}/documents` | `OrgResidentDocumentController#upload` (backend/src/main/java/com/mannschaft/app/resident/controller/OrgResidentDocumentController.java) | 33 |
| POST | `/api/v1/organizations/{_}/segment-presets` | `OrgSegmentPresetController#create` (backend/src/main/java/com/mannschaft/app/promotion/controller/OrgSegmentPresetController.java) | 40 |
| POST | `/api/v1/organizations/{_}/sns/feeds` | `SnsFeedConfigController#createForOrg` (backend/src/main/java/com/mannschaft/app/line/controller/SnsFeedConfigController.java) | 99 |
| POST | `/api/v1/organizations/{_}/succession/delinquency-escalations/{_}/freeze` | `DelinquencyEscalationController#freeze` (backend/src/main/java/com/mannschaft/app/succession/controller/DelinquencyEscalationController.java) | 97 |
| POST | `/api/v1/organizations/{_}/succession/delinquency-escalations/{_}/resolve` | `DelinquencyEscalationController#resolve` (backend/src/main/java/com/mannschaft/app/succession/controller/DelinquencyEscalationController.java) | 121 |
| POST | `/api/v1/organizations/{_}/succession/legal-filings` | `LegalFilingController#createLegalFiling` (backend/src/main/java/com/mannschaft/app/succession/controller/LegalFilingController.java) | 96 |
| POST | `/api/v1/organizations/{_}/succession/legal-filings/{_}/evidence-package` | `LegalFilingController#buildEvidencePackage` (backend/src/main/java/com/mannschaft/app/succession/controller/LegalFilingController.java) | 144 |
| POST | `/api/v1/organizations/{_}/succession/unseal-requests` | `UnsealRequestController#createRequest` (backend/src/main/java/com/mannschaft/app/succession/controller/UnsealRequestController.java) | 51 |
| POST | `/api/v1/organizations/{_}/succession/unseal-requests/{_}/approve` | `UnsealRequestController#approve` (backend/src/main/java/com/mannschaft/app/succession/controller/UnsealRequestController.java) | 74 |
| POST | `/api/v1/organizations/{_}/succession/unseal-requests/{_}/cancel` | `UnsealRequestController#cancel` (backend/src/main/java/com/mannschaft/app/succession/controller/UnsealRequestController.java) | 118 |
| POST | `/api/v1/organizations/{_}/succession/unseal-requests/{_}/second-approve` | `UnsealRequestController#secondApprove` (backend/src/main/java/com/mannschaft/app/succession/controller/UnsealRequestController.java) | 97 |
| POST | `/api/v1/organizations/{_}/supporter-applications/bulk-approve` | `OrganizationController#bulkApproveSupporterApplications` (backend/src/main/java/com/mannschaft/app/organization/controller/OrganizationController.java) | 241 |
| POST | `/api/v1/organizations/{_}/supporter-applications/{_}/approve` | `OrganizationController#approveSupporterApplication` (backend/src/main/java/com/mannschaft/app/organization/controller/OrganizationController.java) | 223 |
| POST | `/api/v1/organizations/{_}/supporter-applications/{_}/reject` | `OrganizationController#rejectSupporterApplication` (backend/src/main/java/com/mannschaft/app/organization/controller/OrganizationController.java) | 232 |
| POST | `/api/v1/organizations/{_}/surveys/{_}/close` | `SurveyController#closeSurvey` (backend/src/main/java/com/mannschaft/app/survey/controller/SurveyController.java) | 127 |
| POST | `/api/v1/organizations/{_}/surveys/{_}/publish` | `SurveyController#publishSurvey` (backend/src/main/java/com/mannschaft/app/survey/controller/SurveyController.java) | 113 |
| POST | `/api/v1/organizations/{_}/teams/{_}/entry-templates` | `TournamentEntryTemplateController#createTemplate` (backend/src/main/java/com/mannschaft/app/tournament/entry/TournamentEntryTemplateController.java) | 68 |
| POST | `/api/v1/organizations/{_}/todos/{_}/assignees` | `OrgTodoController#addAssignee` (backend/src/main/java/com/mannschaft/app/todo/controller/OrgTodoController.java) | 214 |
| POST | `/api/v1/organizations/{_}/todos/{_}/comments` | `OrgTodoController#addComment` (backend/src/main/java/com/mannschaft/app/todo/controller/OrgTodoController.java) | 262 |
| POST | `/api/v1/organizations/{_}/todos/{_}/link-schedule` | `OrgTodoController#linkSchedule` (backend/src/main/java/com/mannschaft/app/todo/controller/OrgTodoController.java) | 306 |
| POST | `/api/v1/organizations/{_}/todos/{_}/memos` | `OrgTodoController#addSharedMemo` (backend/src/main/java/com/mannschaft/app/todo/controller/OrgTodoController.java) | 399 |
| POST | `/api/v1/organizations/{_}/tournament-templates` | `TournamentTemplateController#createTemplate` (backend/src/main/java/com/mannschaft/app/tournament/controller/TournamentTemplateController.java) | 54 |
| POST | `/api/v1/organizations/{_}/tournament-templates/clone/{_}` | `TournamentTemplateController#cloneFromPreset` (backend/src/main/java/com/mannschaft/app/tournament/controller/TournamentTemplateController.java) | 63 |
| POST | `/api/v1/organizations/{_}/tournaments/continue/{_}` | `TournamentController#continueTournament` (backend/src/main/java/com/mannschaft/app/tournament/controller/TournamentController.java) | 100 |
| POST | `/api/v1/organizations/{_}/tournaments/{_}/divisions` | `DivisionController#createDivision` (backend/src/main/java/com/mannschaft/app/tournament/controller/DivisionController.java) | 49 |
| POST | `/api/v1/organizations/{_}/tournaments/{_}/divisions/{_}/matchdays` | `MatchController#createMatchday` (backend/src/main/java/com/mannschaft/app/tournament/controller/MatchController.java) | 62 |
| POST | `/api/v1/organizations/{_}/tournaments/{_}/divisions/{_}/matchdays/generate` | `MatchController#generateMatchdays` (backend/src/main/java/com/mannschaft/app/tournament/controller/MatchController.java) | 71 |
| POST | `/api/v1/organizations/{_}/tournaments/{_}/divisions/{_}/matchdays/{_}/scores/batch` | `MatchController#batchUpdateScores` (backend/src/main/java/com/mannschaft/app/tournament/controller/MatchController.java) | 113 |
| POST | `/api/v1/organizations/{_}/tournaments/{_}/divisions/{_}/matchdays/{_}/scores/import` | `MatchController#importScores` (backend/src/main/java/com/mannschaft/app/tournament/controller/MatchController.java) | 123 |
| POST | `/api/v1/organizations/{_}/tournaments/{_}/divisions/{_}/participants` | `DivisionController#addParticipant` (backend/src/main/java/com/mannschaft/app/tournament/controller/DivisionController.java) | 83 |
| POST | `/api/v1/organizations/{_}/tournaments/{_}/divisions/{_}/participants/{_}/entry-members/load-from-team` | `TournamentEntryMemberController#loadFromTeam` (backend/src/main/java/com/mannschaft/app/tournament/entry/TournamentEntryMemberController.java) | 77 |
| POST | `/api/v1/organizations/{_}/tournaments/{_}/divisions/{_}/standings/recalculate` | `StandingsController#recalculate` (backend/src/main/java/com/mannschaft/app/tournament/controller/StandingsController.java) | 74 |
| POST | `/api/v1/organizations/{_}/tournaments/{_}/matches/{_}/rosters` | `MatchController#createRosters` (backend/src/main/java/com/mannschaft/app/tournament/controller/MatchController.java) | 180 |
| POST | `/api/v1/organizations/{_}/tournaments/{_}/promotions/preview` | `PromotionController#getPromotionPreview` (backend/src/main/java/com/mannschaft/app/tournament/controller/PromotionController.java) | 53 |
| POST | `/api/v1/organizations/{_}/translations/mark-stale` | `ContentTranslationController#markOrgTranslationsAsStale` (backend/src/main/java/com/mannschaft/app/translation/controller/ContentTranslationController.java) | 337 |
| POST | `/api/v1/organizations/{_}/workflow-requests/{_}/submit` | `WorkflowRequestController#submitRequest` (backend/src/main/java/com/mannschaft/app/workflow/controller/WorkflowRequestController.java) | 106 |
| POST | `/api/v1/organizations/{_}/workflow-requests/{_}/withdraw` | `WorkflowRequestController#withdrawRequest` (backend/src/main/java/com/mannschaft/app/workflow/controller/WorkflowRequestController.java) | 120 |
| POST | `/api/v1/organizations/{_}/workflow-templates/{_}/activate` | `WorkflowTemplateStatusController#activateTemplate` (backend/src/main/java/com/mannschaft/app/workflow/controller/WorkflowTemplateStatusController.java) | 29 |
| POST | `/api/v1/organizations/{_}/workflow-templates/{_}/deactivate` | `WorkflowTemplateStatusController#deactivateTemplate` (backend/src/main/java/com/mannschaft/app/workflow/controller/WorkflowTemplateStatusController.java) | 43 |
| PUT | `/api/v1/organizations/{_}/announcement-templates/{_}` | `AnnouncementRangeTemplateController#updateOrgTemplate` (backend/src/main/java/com/mannschaft/app/social/announcement/controller/AnnouncementRangeTemplateController.java) | 229 |
| PUT | `/api/v1/organizations/{_}/bulletin/categories/{_}` | `BulletinCategoryController#updateCategory` (backend/src/main/java/com/mannschaft/app/bulletin/controller/BulletinCategoryController.java) | 86 |
| PUT | `/api/v1/organizations/{_}/bulletin/threads/{_}` | `BulletinThreadController#updateThread` (backend/src/main/java/com/mannschaft/app/bulletin/controller/BulletinThreadController.java) | 117 |
| PUT | `/api/v1/organizations/{_}/bulletin/threads/{_}/replies/{_}` | `BulletinReplyController#updateReply` (backend/src/main/java/com/mannschaft/app/bulletin/controller/BulletinReplyController.java) | 79 |
| PUT | `/api/v1/organizations/{_}/confirmable-notification-templates/{_}` | `OrgConfirmableNotificationTemplateController#update` (backend/src/main/java/com/mannschaft/app/notification/confirmable/controller/OrgConfirmableNotificationTemplateController.java) | 81 |
| PUT | `/api/v1/organizations/{_}/coupons/{_}` | `OrgCouponController#update` (backend/src/main/java/com/mannschaft/app/promotion/controller/OrgCouponController.java) | 65 |
| PUT | `/api/v1/organizations/{_}/direct-mail-templates/{_}` | `OrganizationDirectMailTemplateController#updateTemplate` (backend/src/main/java/com/mannschaft/app/directmail/controller/OrganizationDirectMailTemplateController.java) | 65 |
| PUT | `/api/v1/organizations/{_}/direct-mails/{_}` | `OrganizationDirectMailController#updateMail` (backend/src/main/java/com/mannschaft/app/directmail/controller/OrganizationDirectMailController.java) | 84 |
| PUT | `/api/v1/organizations/{_}/dwelling-units/{_}` | `OrgDwellingUnitController#update` (backend/src/main/java/com/mannschaft/app/resident/controller/OrgDwellingUnitController.java) | 65 |
| PUT | `/api/v1/organizations/{_}/facilities/settings` | `OrgFacilitySettingsController#updateSettings` (backend/src/main/java/com/mannschaft/app/facility/controller/OrgFacilitySettingsController.java) | 44 |
| PUT | `/api/v1/organizations/{_}/facilities/{_}` | `OrgFacilityController#updateFacility` (backend/src/main/java/com/mannschaft/app/facility/controller/OrgFacilityController.java) | 103 |
| PUT | `/api/v1/organizations/{_}/facilities/{_}/equipment/{_}` | `OrgFacilityController#updateEquipment` (backend/src/main/java/com/mannschaft/app/facility/controller/OrgFacilityController.java) | 193 |
| PUT | `/api/v1/organizations/{_}/facilities/{_}/rates` | `OrgFacilityController#replaceTimeRates` (backend/src/main/java/com/mannschaft/app/facility/controller/OrgFacilityController.java) | 158 |
| PUT | `/api/v1/organizations/{_}/facilities/{_}/rules` | `OrgFacilityController#updateUsageRule` (backend/src/main/java/com/mannschaft/app/facility/controller/OrgFacilityController.java) | 135 |
| PUT | `/api/v1/organizations/{_}/form-submissions/{_}` | `FormSubmissionController#updateSubmission` (backend/src/main/java/com/mannschaft/app/forms/controller/FormSubmissionController.java) | 90 |
| PUT | `/api/v1/organizations/{_}/form-templates/{_}` | `FormTemplateController#updateTemplate` (backend/src/main/java/com/mannschaft/app/forms/controller/FormTemplateController.java) | 91 |
| PUT | `/api/v1/organizations/{_}/line/config` | `LineBotConfigController#updateForOrg` (backend/src/main/java/com/mannschaft/app/line/controller/LineBotConfigController.java) | 123 |
| PUT | `/api/v1/organizations/{_}/parking/listings/{_}` | `OrgParkingListingController#update` (backend/src/main/java/com/mannschaft/app/parking/controller/OrgParkingListingController.java) | 71 |
| PUT | `/api/v1/organizations/{_}/parking/settings` | `OrgParkingSpaceController#updateSettings` (backend/src/main/java/com/mannschaft/app/parking/controller/OrgParkingSpaceController.java) | 198 |
| PUT | `/api/v1/organizations/{_}/parking/spaces/{_}` | `OrgParkingSpaceController#updateSpace` (backend/src/main/java/com/mannschaft/app/parking/controller/OrgParkingSpaceController.java) | 81 |
| PUT | `/api/v1/organizations/{_}/parking/subleases/{_}` | `OrgParkingSubleaseController#update` (backend/src/main/java/com/mannschaft/app/parking/controller/OrgParkingSubleaseController.java) | 71 |
| PUT | `/api/v1/organizations/{_}/parking/visitor-recurring/{_}` | `OrgParkingVisitorController#updateRecurring` (backend/src/main/java/com/mannschaft/app/parking/controller/OrgParkingVisitorController.java) | 138 |
| PUT | `/api/v1/organizations/{_}/promotions/{_}` | `OrgPromotionController#update` (backend/src/main/java/com/mannschaft/app/promotion/controller/OrgPromotionController.java) | 70 |
| PUT | `/api/v1/organizations/{_}/property-history/{_}` | `PropertyWorkPackageController#updatePackage` (backend/src/main/java/com/mannschaft/app/property/controller/PropertyWorkPackageController.java) | 208 |
| PUT | `/api/v1/organizations/{_}/property-listings/{_}` | `OrgPropertyListingController#update` (backend/src/main/java/com/mannschaft/app/resident/controller/OrgPropertyListingController.java) | 71 |
| PUT | `/api/v1/organizations/{_}/residence-status/annual-reviews/{_}/responses/me` | `AnnualReviewResponseController#submitMyResponse` (backend/src/main/java/com/mannschaft/app/residencestatus/controller/AnnualReviewResponseController.java) | 55 |
| PUT | `/api/v1/organizations/{_}/residence-status/monitoring-visits/{_}` | `MonitoringCommitteeVisitController#updateVisit` (backend/src/main/java/com/mannschaft/app/residencestatus/controller/MonitoringCommitteeVisitController.java) | 105 |
| PUT | `/api/v1/organizations/{_}/residents/{_}` | `OrgResidentController#update` (backend/src/main/java/com/mannschaft/app/resident/controller/OrgResidentController.java) | 52 |
| PUT | `/api/v1/organizations/{_}/segment-presets/{_}` | `OrgSegmentPresetController#update` (backend/src/main/java/com/mannschaft/app/promotion/controller/OrgSegmentPresetController.java) | 49 |
| PUT | `/api/v1/organizations/{_}/sns/feeds/{_}` | `SnsFeedConfigController#updateForOrg` (backend/src/main/java/com/mannschaft/app/line/controller/SnsFeedConfigController.java) | 112 |
| PUT | `/api/v1/organizations/{_}/supporter-settings` | `OrganizationController#updateSupporterSettings` (backend/src/main/java/com/mannschaft/app/organization/controller/OrganizationController.java) | 257 |
| PUT | `/api/v1/organizations/{_}/teams/{_}/entry-templates/{_}` | `TournamentEntryTemplateController#updateTemplate` (backend/src/main/java/com/mannschaft/app/tournament/entry/TournamentEntryTemplateController.java) | 107 |
| PUT | `/api/v1/organizations/{_}/todos/{_}` | `OrgTodoController#updateTodo` (backend/src/main/java/com/mannschaft/app/todo/controller/OrgTodoController.java) | 138 |
| PUT | `/api/v1/organizations/{_}/todos/{_}/comments/{_}` | `OrgTodoController#updateComment` (backend/src/main/java/com/mannschaft/app/todo/controller/OrgTodoController.java) | 276 |
| PUT | `/api/v1/organizations/{_}/todos/{_}/memos/{_}` | `OrgTodoController#updateSharedMemo` (backend/src/main/java/com/mannschaft/app/todo/controller/OrgTodoController.java) | 414 |
| PUT | `/api/v1/organizations/{_}/todos/{_}/my-memo` | `OrgTodoController#upsertPersonalMemo` (backend/src/main/java/com/mannschaft/app/todo/controller/OrgTodoController.java) | 456 |
| PUT | `/api/v1/organizations/{_}/tournaments/{_}/divisions/{_}/participants/{_}/entry-members` | `TournamentEntryMemberController#upsertEntryMembers` (backend/src/main/java/com/mannschaft/app/tournament/entry/TournamentEntryMemberController.java) | 101 |
| PUT | `/api/v1/organizations/{_}/vendors/{_}` | `VendorController#updateVendor` (backend/src/main/java/com/mannschaft/app/property/controller/VendorController.java) | 140 |
| PUT | `/api/v1/organizations/{_}/workflow-requests/{_}` | `WorkflowRequestController#updateRequest` (backend/src/main/java/com/mannschaft/app/workflow/controller/WorkflowRequestController.java) | 91 |
| PUT | `/api/v1/organizations/{_}/workflow-templates/{_}` | `WorkflowTemplateController#updateTemplate` (backend/src/main/java/com/mannschaft/app/workflow/controller/WorkflowTemplateController.java) | 90 |

#### /api/v1/teams/* (219 件)

| メソッド | パス | Controller | 行 |
|---|---|---|---|
| DELETE | `/api/v1/teams/{_}/announcement-templates/{_}` | `AnnouncementRangeTemplateController#deleteTeamTemplate` (backend/src/main/java/com/mannschaft/app/social/announcement/controller/AnnouncementRangeTemplateController.java) | 146 |
| DELETE | `/api/v1/teams/{_}/bulletin/categories/{_}` | `BulletinCategoryController#deleteCategory` (backend/src/main/java/com/mannschaft/app/bulletin/controller/BulletinCategoryController.java) | 102 |
| DELETE | `/api/v1/teams/{_}/bulletin/threads/{_}` | `BulletinThreadController#deleteThread` (backend/src/main/java/com/mannschaft/app/bulletin/controller/BulletinThreadController.java) | 133 |
| DELETE | `/api/v1/teams/{_}/bulletin/threads/{_}/replies/{_}` | `BulletinReplyController#deleteReply` (backend/src/main/java/com/mannschaft/app/bulletin/controller/BulletinReplyController.java) | 96 |
| DELETE | `/api/v1/teams/{_}/care-overrides/{_}` | `TeamCareOverrideController#deleteTeamOverride` (backend/src/main/java/com/mannschaft/app/family/controller/TeamCareOverrideController.java) | 65 |
| DELETE | `/api/v1/teams/{_}/circulations/{_}` | `TeamCirculationDocumentController#deleteDocument` (backend/src/main/java/com/mannschaft/app/circulation/controller/TeamCirculationDocumentController.java) | 131 |
| DELETE | `/api/v1/teams/{_}/corkboards/{_}` | `TeamCorkboardController#deleteBoard` (backend/src/main/java/com/mannschaft/app/corkboard/controller/TeamCorkboardController.java) | 91 |
| DELETE | `/api/v1/teams/{_}/coupons/{_}` | `TeamCouponController#delete` (backend/src/main/java/com/mannschaft/app/promotion/controller/TeamCouponController.java) | 73 |
| DELETE | `/api/v1/teams/{_}/dwelling-units/{_}` | `TeamDwellingUnitController#delete` (backend/src/main/java/com/mannschaft/app/resident/controller/TeamDwellingUnitController.java) | 74 |
| DELETE | `/api/v1/teams/{_}/folders/{_}` | `TeamFolderController#deleteFolder` (backend/src/main/java/com/mannschaft/app/filesharing/controller/TeamFolderController.java) | 106 |
| DELETE | `/api/v1/teams/{_}/form-submissions/{_}` | `FormSubmissionController#deleteSubmission` (backend/src/main/java/com/mannschaft/app/forms/controller/FormSubmissionController.java) | 106 |
| DELETE | `/api/v1/teams/{_}/form-templates/{_}` | `FormTemplateController#deleteTemplate` (backend/src/main/java/com/mannschaft/app/forms/controller/FormTemplateController.java) | 135 |
| DELETE | `/api/v1/teams/{_}/friend-feed/forwards/{_}` | `FriendContentForwardController#revoke` (backend/src/main/java/com/mannschaft/app/social/controller/FriendContentForwardController.java) | 102 |
| DELETE | `/api/v1/teams/{_}/line/config` | `LineBotConfigController#deleteForTeam` (backend/src/main/java/com/mannschaft/app/line/controller/LineBotConfigController.java) | 71 |
| DELETE | `/api/v1/teams/{_}/promotions/{_}` | `TeamPromotionController#delete` (backend/src/main/java/com/mannschaft/app/promotion/controller/TeamPromotionController.java) | 78 |
| DELETE | `/api/v1/teams/{_}/property-history/{_}` | `PropertyWorkPackageController#deletePackage` (backend/src/main/java/com/mannschaft/app/property/controller/PropertyWorkPackageController.java) | 236 |
| DELETE | `/api/v1/teams/{_}/property-history/{_}/documents/{_}` | `PropertyWorkPackageController#detachDocument` (backend/src/main/java/com/mannschaft/app/property/controller/PropertyWorkPackageController.java) | 271 |
| DELETE | `/api/v1/teams/{_}/property-listings/{_}` | `TeamPropertyListingController#delete` (backend/src/main/java/com/mannschaft/app/resident/controller/TeamPropertyListingController.java) | 79 |
| DELETE | `/api/v1/teams/{_}/queue/qr-codes/{_}` | `QueueQrCodeController#deactivateQrCode` (backend/src/main/java/com/mannschaft/app/queue/controller/QueueQrCodeController.java) | 78 |
| DELETE | `/api/v1/teams/{_}/queue/tickets/{_}` | `QueueTicketController#cancelMyTicket` (backend/src/main/java/com/mannschaft/app/queue/controller/QueueTicketController.java) | 109 |
| DELETE | `/api/v1/teams/{_}/repair-plan/handover-packs/{_}` | `BoardHandoverPackController#deletePack` (backend/src/main/java/com/mannschaft/app/repairplan/controller/BoardHandoverPackController.java) | 101 |
| DELETE | `/api/v1/teams/{_}/repair-plan/items/{_}` | `RepairPlanItemController#delete` (backend/src/main/java/com/mannschaft/app/repairplan/controller/RepairPlanItemController.java) | 137 |
| DELETE | `/api/v1/teams/{_}/repair-plan/terms/{_}` | `TeamMemberTermController#deleteTerm` (backend/src/main/java/com/mannschaft/app/repairplan/controller/TeamMemberTermController.java) | 91 |
| DELETE | `/api/v1/teams/{_}/residents/{_}` | `TeamResidentController#delete` (backend/src/main/java/com/mannschaft/app/resident/controller/TeamResidentController.java) | 61 |
| DELETE | `/api/v1/teams/{_}/residents/{_}/documents/{_}` | `TeamResidentDocumentController#delete` (backend/src/main/java/com/mannschaft/app/resident/controller/TeamResidentDocumentController.java) | 49 |
| DELETE | `/api/v1/teams/{_}/segment-presets/{_}` | `TeamSegmentPresetController#delete` (backend/src/main/java/com/mannschaft/app/promotion/controller/TeamSegmentPresetController.java) | 57 |
| DELETE | `/api/v1/teams/{_}/sns/feeds/{_}` | `SnsFeedConfigController#deleteForTeam` (backend/src/main/java/com/mannschaft/app/line/controller/SnsFeedConfigController.java) | 71 |
| DELETE | `/api/v1/teams/{_}/surveys/{_}` | `SurveyController#deleteSurvey` (backend/src/main/java/com/mannschaft/app/survey/controller/SurveyController.java) | 141 |
| DELETE | `/api/v1/teams/{_}/surveys/{_}/questions/{_}` | `SurveyQuestionController#deleteQuestion` (backend/src/main/java/com/mannschaft/app/survey/controller/SurveyQuestionController.java) | 49 |
| DELETE | `/api/v1/teams/{_}/todos/{_}/link-schedule` | `TeamTodoController#unlinkSchedule` (backend/src/main/java/com/mannschaft/app/todo/controller/TeamTodoController.java) | 300 |
| DELETE | `/api/v1/teams/{_}/todos/{_}/memos/{_}` | `TeamTodoController#deleteSharedMemo` (backend/src/main/java/com/mannschaft/app/todo/controller/TeamTodoController.java) | 407 |
| DELETE | `/api/v1/teams/{_}/todos/{_}/my-memo` | `TeamTodoController#deletePersonalMemo` (backend/src/main/java/com/mannschaft/app/todo/controller/TeamTodoController.java) | 448 |
| DELETE | `/api/v1/teams/{_}/vendors/{_}` | `VendorController#deleteVendor` (backend/src/main/java/com/mannschaft/app/property/controller/VendorController.java) | 151 |
| DELETE | `/api/v1/teams/{_}/workflow-requests/{_}` | `WorkflowRequestController#deleteRequest` (backend/src/main/java/com/mannschaft/app/workflow/controller/WorkflowRequestController.java) | 134 |
| DELETE | `/api/v1/teams/{_}/workflow-templates/{_}` | `WorkflowTemplateController#deleteTemplate` (backend/src/main/java/com/mannschaft/app/workflow/controller/WorkflowTemplateController.java) | 105 |
| GET | `/api/v1/teams/{_}/announcement-templates` | `AnnouncementRangeTemplateController#listTeamTemplates` (backend/src/main/java/com/mannschaft/app/social/announcement/controller/AnnouncementRangeTemplateController.java) | 64 |
| GET | `/api/v1/teams/{_}/attendance/requirements/evaluations/{_}/disclosure-history` | `AttendanceDisclosureController#getDisclosureHistory` (backend/src/main/java/com/mannschaft/app/school/controller/AttendanceDisclosureController.java) | 82 |
| GET | `/api/v1/teams/{_}/bulletin/categories/{_}` | `BulletinCategoryController#getCategory` (backend/src/main/java/com/mannschaft/app/bulletin/controller/BulletinCategoryController.java) | 56 |
| GET | `/api/v1/teams/{_}/bulletin/threads/search` | `BulletinThreadController#searchThreads` (backend/src/main/java/com/mannschaft/app/bulletin/controller/BulletinThreadController.java) | 83 |
| GET | `/api/v1/teams/{_}/bulletin/threads/{_}` | `BulletinThreadController#getThread` (backend/src/main/java/com/mannschaft/app/bulletin/controller/BulletinThreadController.java) | 68 |
| GET | `/api/v1/teams/{_}/care-overrides/{_}` | `TeamCareOverrideController#getTeamOverride` (backend/src/main/java/com/mannschaft/app/family/controller/TeamCareOverrideController.java) | 37 |
| GET | `/api/v1/teams/{_}/circulations/stats` | `TeamCirculationDocumentController#getStats` (backend/src/main/java/com/mannschaft/app/circulation/controller/TeamCirculationDocumentController.java) | 144 |
| GET | `/api/v1/teams/{_}/circulations/{_}` | `TeamCirculationDocumentController#getDocument` (backend/src/main/java/com/mannschaft/app/circulation/controller/TeamCirculationDocumentController.java) | 64 |
| GET | `/api/v1/teams/{_}/corkboards/{_}` | `TeamCorkboardController#getBoard` (backend/src/main/java/com/mannschaft/app/corkboard/controller/TeamCorkboardController.java) | 64 |
| GET | `/api/v1/teams/{_}/coupons` | `TeamCouponController#list` (backend/src/main/java/com/mannschaft/app/promotion/controller/TeamCouponController.java) | 37 |
| GET | `/api/v1/teams/{_}/coupons/{_}` | `TeamCouponController#get` (backend/src/main/java/com/mannschaft/app/promotion/controller/TeamCouponController.java) | 58 |
| GET | `/api/v1/teams/{_}/direct-mails/{_}/stats` | `TeamDirectMailController#getStats` (backend/src/main/java/com/mannschaft/app/directmail/controller/TeamDirectMailController.java) | 148 |
| GET | `/api/v1/teams/{_}/dwelling-units` | `TeamDwellingUnitController#list` (backend/src/main/java/com/mannschaft/app/resident/controller/TeamDwellingUnitController.java) | 38 |
| GET | `/api/v1/teams/{_}/dwelling-units/{_}` | `TeamDwellingUnitController#get` (backend/src/main/java/com/mannschaft/app/resident/controller/TeamDwellingUnitController.java) | 59 |
| GET | `/api/v1/teams/{_}/dwelling-units/{_}/residents` | `TeamResidentController#listByUnit` (backend/src/main/java/com/mannschaft/app/resident/controller/TeamResidentController.java) | 38 |
| GET | `/api/v1/teams/{_}/emergency-closures/preview` | `TeamEmergencyClosureController#previewClosure` (backend/src/main/java/com/mannschaft/app/reservation/controller/TeamEmergencyClosureController.java) | 46 |
| GET | `/api/v1/teams/{_}/emergency-closures/{_}/confirmations` | `TeamEmergencyClosureController#getConfirmations` (backend/src/main/java/com/mannschaft/app/reservation/controller/TeamEmergencyClosureController.java) | 102 |
| GET | `/api/v1/teams/{_}/events/{_}/rsvp-responses` | `EventRsvpController#listTeamRsvp` (backend/src/main/java/com/mannschaft/app/event/controller/EventRsvpController.java) | 107 |
| GET | `/api/v1/teams/{_}/events/{_}/rsvp-responses/summary` | `EventRsvpController#getTeamRsvpSummary` (backend/src/main/java/com/mannschaft/app/event/controller/EventRsvpController.java) | 150 |
| GET | `/api/v1/teams/{_}/folders/{_}` | `TeamFolderController#getFolder` (backend/src/main/java/com/mannschaft/app/filesharing/controller/TeamFolderController.java) | 66 |
| GET | `/api/v1/teams/{_}/folders/{_}/children` | `TeamFolderController#listChildFolders` (backend/src/main/java/com/mannschaft/app/filesharing/controller/TeamFolderController.java) | 53 |
| GET | `/api/v1/teams/{_}/follow/status` | `TeamController#getFollowStatus` (backend/src/main/java/com/mannschaft/app/team/controller/TeamController.java) | 194 |
| GET | `/api/v1/teams/{_}/form-submissions/my` | `FormSubmissionController#listMySubmissions` (backend/src/main/java/com/mannschaft/app/forms/controller/FormSubmissionController.java) | 43 |
| GET | `/api/v1/teams/{_}/form-submissions/{_}` | `FormSubmissionController#getSubmission` (backend/src/main/java/com/mannschaft/app/forms/controller/FormSubmissionController.java) | 61 |
| GET | `/api/v1/teams/{_}/form-templates/{_}` | `FormTemplateController#getTemplate` (backend/src/main/java/com/mannschaft/app/forms/controller/FormTemplateController.java) | 62 |
| GET | `/api/v1/teams/{_}/line/config` | `LineBotConfigController#getForTeam` (backend/src/main/java/com/mannschaft/app/line/controller/LineBotConfigController.java) | 63 |
| GET | `/api/v1/teams/{_}/line/logs` | `LineBotConfigController#logsForTeam` (backend/src/main/java/com/mannschaft/app/line/controller/LineBotConfigController.java) | 91 |
| GET | `/api/v1/teams/{_}/profile` | `TeamExtendedProfileController#getProfile` (backend/src/main/java/com/mannschaft/app/team/controller/TeamExtendedProfileController.java) | 53 |
| GET | `/api/v1/teams/{_}/promotions` | `TeamPromotionController#list` (backend/src/main/java/com/mannschaft/app/promotion/controller/TeamPromotionController.java) | 41 |
| GET | `/api/v1/teams/{_}/promotions/{_}` | `TeamPromotionController#get` (backend/src/main/java/com/mannschaft/app/promotion/controller/TeamPromotionController.java) | 63 |
| GET | `/api/v1/teams/{_}/promotions/{_}/stats` | `TeamPromotionController#getStats` (backend/src/main/java/com/mannschaft/app/promotion/controller/TeamPromotionController.java) | 114 |
| GET | `/api/v1/teams/{_}/property-history/categories/suggestions` | `PropertyWorkPackageController#categorySuggestions` (backend/src/main/java/com/mannschaft/app/property/controller/PropertyWorkPackageController.java) | 329 |
| GET | `/api/v1/teams/{_}/property-history/gantt` | `PropertyWorkPackageController#gantt` (backend/src/main/java/com/mannschaft/app/property/controller/PropertyWorkPackageController.java) | 163 |
| GET | `/api/v1/teams/{_}/property-history/timeline` | `PropertyWorkPackageController#timeline` (backend/src/main/java/com/mannschaft/app/property/controller/PropertyWorkPackageController.java) | 141 |
| GET | `/api/v1/teams/{_}/property-history/{_}` | `PropertyWorkPackageController#getPackage` (backend/src/main/java/com/mannschaft/app/property/controller/PropertyWorkPackageController.java) | 183 |
| GET | `/api/v1/teams/{_}/property-listings` | `TeamPropertyListingController#list` (backend/src/main/java/com/mannschaft/app/resident/controller/TeamPropertyListingController.java) | 41 |
| GET | `/api/v1/teams/{_}/property-listings/{_}` | `TeamPropertyListingController#get` (backend/src/main/java/com/mannschaft/app/resident/controller/TeamPropertyListingController.java) | 64 |
| GET | `/api/v1/teams/{_}/property-listings/{_}/inquiries` | `TeamPropertyListingController#listInquiries` (backend/src/main/java/com/mannschaft/app/resident/controller/TeamPropertyListingController.java) | 95 |
| GET | `/api/v1/teams/{_}/queue/categories/{_}` | `QueueCategoryController#getCategory` (backend/src/main/java/com/mannschaft/app/queue/controller/QueueCategoryController.java) | 52 |
| GET | `/api/v1/teams/{_}/queue/categories/{_}/tickets` | `QueueTicketController#listCategoryTickets` (backend/src/main/java/com/mannschaft/app/queue/controller/QueueTicketController.java) | 149 |
| GET | `/api/v1/teams/{_}/queue/counters/{_}` | `QueueCounterController#getCounter` (backend/src/main/java/com/mannschaft/app/queue/controller/QueueCounterController.java) | 53 |
| GET | `/api/v1/teams/{_}/queue/counters/{_}/tickets/all` | `QueueTicketController#listAllTickets` (backend/src/main/java/com/mannschaft/app/queue/controller/QueueTicketController.java) | 71 |
| GET | `/api/v1/teams/{_}/queue/qr-codes/token/{_}` | `QueueQrCodeController#getByToken` (backend/src/main/java/com/mannschaft/app/queue/controller/QueueQrCodeController.java) | 51 |
| GET | `/api/v1/teams/{_}/queue/tickets/me` | `QueueTicketController#listMyTickets` (backend/src/main/java/com/mannschaft/app/queue/controller/QueueTicketController.java) | 97 |
| GET | `/api/v1/teams/{_}/queue/tickets/{_}` | `QueueTicketController#getTicket` (backend/src/main/java/com/mannschaft/app/queue/controller/QueueTicketController.java) | 84 |
| GET | `/api/v1/teams/{_}/repair-plan/dashboard` | `RepairPlanDashboardController#getDashboard` (backend/src/main/java/com/mannschaft/app/repairplan/controller/RepairPlanDashboardController.java) | 38 |
| GET | `/api/v1/teams/{_}/repair-plan/handover-packs/{_}/download` | `BoardHandoverPackController#getDownloadUrl` (backend/src/main/java/com/mannschaft/app/repairplan/controller/BoardHandoverPackController.java) | 86 |
| GET | `/api/v1/teams/{_}/repair-plan/items/{_}` | `RepairPlanItemController#get` (backend/src/main/java/com/mannschaft/app/repairplan/controller/RepairPlanItemController.java) | 109 |
| GET | `/api/v1/teams/{_}/repair-plan/quote-kanbans` | `RepairPlanQuoteKanbanController#listKanbans` (backend/src/main/java/com/mannschaft/app/repairplan/controller/RepairPlanQuoteKanbanController.java) | 67 |
| GET | `/api/v1/teams/{_}/repair-plan/quote-kanbans/{_}` | `RepairPlanQuoteKanbanController#getKanban` (backend/src/main/java/com/mannschaft/app/repairplan/controller/RepairPlanQuoteKanbanController.java) | 97 |
| GET | `/api/v1/teams/{_}/repair-plan/scenarios/{_}` | `RepairPlanScenarioController#getScenario` (backend/src/main/java/com/mannschaft/app/repairplan/controller/RepairPlanScenarioController.java) | 114 |
| GET | `/api/v1/teams/{_}/repair-plan/terms/{_}` | `TeamMemberTermController#getTerm` (backend/src/main/java/com/mannschaft/app/repairplan/controller/TeamMemberTermController.java) | 78 |
| GET | `/api/v1/teams/{_}/repair-plan/timeline` | `RepairPlanTimelineController#getTimeline` (backend/src/main/java/com/mannschaft/app/repairplan/controller/RepairPlanTimelineController.java) | 41 |
| GET | `/api/v1/teams/{_}/residents/{_}/documents` | `TeamResidentDocumentController#list` (backend/src/main/java/com/mannschaft/app/resident/controller/TeamResidentDocumentController.java) | 42 |
| GET | `/api/v1/teams/{_}/segment-presets` | `TeamSegmentPresetController#list` (backend/src/main/java/com/mannschaft/app/promotion/controller/TeamSegmentPresetController.java) | 34 |
| GET | `/api/v1/teams/{_}/sns/feeds` | `SnsFeedConfigController#listForTeam` (backend/src/main/java/com/mannschaft/app/line/controller/SnsFeedConfigController.java) | 39 |
| GET | `/api/v1/teams/{_}/sns/feeds/{_}/preview` | `SnsFeedConfigController#previewForTeam` (backend/src/main/java/com/mannschaft/app/line/controller/SnsFeedConfigController.java) | 80 |
| GET | `/api/v1/teams/{_}/supporter-applications` | `TeamController#getSupporterApplications` (backend/src/main/java/com/mannschaft/app/team/controller/TeamController.java) | 214 |
| GET | `/api/v1/teams/{_}/supporter-settings` | `TeamController#getSupporterSettings` (backend/src/main/java/com/mannschaft/app/team/controller/TeamController.java) | 249 |
| GET | `/api/v1/teams/{_}/supporters` | `TeamController#getSupporters` (backend/src/main/java/com/mannschaft/app/team/controller/TeamController.java) | 206 |
| GET | `/api/v1/teams/{_}/surveys/stats` | `SurveyController#getStats` (backend/src/main/java/com/mannschaft/app/survey/controller/SurveyController.java) | 174 |
| GET | `/api/v1/teams/{_}/surveys/{_}` | `SurveyController#getSurvey` (backend/src/main/java/com/mannschaft/app/survey/controller/SurveyController.java) | 69 |
| GET | `/api/v1/teams/{_}/surveys/{_}/respondents` | `SurveyController#getRespondents` (backend/src/main/java/com/mannschaft/app/survey/controller/SurveyController.java) | 158 |
| GET | `/api/v1/teams/{_}/todos/gantt` | `TeamTodoController#getGanttTodos` (backend/src/main/java/com/mannschaft/app/todo/controller/TeamTodoController.java) | 316 |
| GET | `/api/v1/teams/{_}/todos/{_}/children` | `TeamTodoController#getChildTodos` (backend/src/main/java/com/mannschaft/app/todo/controller/TeamTodoController.java) | 121 |
| GET | `/api/v1/teams/{_}/todos/{_}/memos` | `TeamTodoController#listSharedMemos` (backend/src/main/java/com/mannschaft/app/todo/controller/TeamTodoController.java) | 363 |
| GET | `/api/v1/teams/{_}/todos/{_}/my-memo` | `TeamTodoController#getPersonalMemo` (backend/src/main/java/com/mannschaft/app/todo/controller/TeamTodoController.java) | 423 |
| GET | `/api/v1/teams/{_}/tournament-history` | `StandingsController#getTeamHistory` (backend/src/main/java/com/mannschaft/app/tournament/controller/StandingsController.java) | 82 |
| GET | `/api/v1/teams/{_}/tournament-stats` | `StandingsController#getTeamStats` (backend/src/main/java/com/mannschaft/app/tournament/controller/StandingsController.java) | 89 |
| GET | `/api/v1/teams/{_}/translations/content` | `ContentTranslationController#getTeamTranslationForContent` (backend/src/main/java/com/mannschaft/app/translation/controller/ContentTranslationController.java) | 86 |
| GET | `/api/v1/teams/{_}/translations/content/all` | `ContentTranslationController#listTeamTranslationsForContent` (backend/src/main/java/com/mannschaft/app/translation/controller/ContentTranslationController.java) | 103 |
| GET | `/api/v1/teams/{_}/vendors/search` | `VendorController#searchVendors` (backend/src/main/java/com/mannschaft/app/property/controller/VendorController.java) | 103 |
| GET | `/api/v1/teams/{_}/vendors/{_}` | `VendorController#getVendor` (backend/src/main/java/com/mannschaft/app/property/controller/VendorController.java) | 117 |
| GET | `/api/v1/teams/{_}/workflow-requests/{_}` | `WorkflowRequestController#getRequest` (backend/src/main/java/com/mannschaft/app/workflow/controller/WorkflowRequestController.java) | 62 |
| GET | `/api/v1/teams/{_}/workflow-templates/{_}` | `WorkflowTemplateController#getTemplate` (backend/src/main/java/com/mannschaft/app/workflow/controller/WorkflowTemplateController.java) | 61 |
| PATCH | `/api/v1/teams/{_}/circulations/{_}` | `TeamCirculationDocumentController#updateDocument` (backend/src/main/java/com/mannschaft/app/circulation/controller/TeamCirculationDocumentController.java) | 91 |
| PATCH | `/api/v1/teams/{_}/coupons/{_}/toggle` | `TeamCouponController#toggle` (backend/src/main/java/com/mannschaft/app/promotion/controller/TeamCouponController.java) | 80 |
| PATCH | `/api/v1/teams/{_}/folders/{_}` | `TeamFolderController#updateFolder` (backend/src/main/java/com/mannschaft/app/filesharing/controller/TeamFolderController.java) | 92 |
| PATCH | `/api/v1/teams/{_}/property-history/{_}/status` | `PropertyWorkPackageController#changeStatus` (backend/src/main/java/com/mannschaft/app/property/controller/PropertyWorkPackageController.java) | 222 |
| PATCH | `/api/v1/teams/{_}/queue/categories/{_}` | `QueueCategoryController#updateCategory` (backend/src/main/java/com/mannschaft/app/queue/controller/QueueCategoryController.java) | 80 |
| PATCH | `/api/v1/teams/{_}/queue/counters/{_}` | `QueueCounterController#updateCounter` (backend/src/main/java/com/mannschaft/app/queue/controller/QueueCounterController.java) | 79 |
| PATCH | `/api/v1/teams/{_}/queue/tickets/{_}/action` | `QueueTicketController#adminAction` (backend/src/main/java/com/mannschaft/app/queue/controller/QueueTicketController.java) | 122 |
| PATCH | `/api/v1/teams/{_}/repair-plan/items/{_}` | `RepairPlanItemController#update` (backend/src/main/java/com/mannschaft/app/repairplan/controller/RepairPlanItemController.java) | 121 |
| PATCH | `/api/v1/teams/{_}/repair-plan/quote-kanbans/{_}` | `RepairPlanQuoteKanbanController#updateKanban` (backend/src/main/java/com/mannschaft/app/repairplan/controller/RepairPlanQuoteKanbanController.java) | 111 |
| PATCH | `/api/v1/teams/{_}/reservation-lines/{_}` | `TeamReservationLineController#updateLine` (backend/src/main/java/com/mannschaft/app/reservation/controller/TeamReservationLineController.java) | 64 |
| PATCH | `/api/v1/teams/{_}/reservation-slots/{_}` | `TeamReservationSlotController#updateSlot` (backend/src/main/java/com/mannschaft/app/reservation/controller/TeamReservationSlotController.java) | 98 |
| PATCH | `/api/v1/teams/{_}/residents/{_}/move-out` | `TeamResidentController#moveOut` (backend/src/main/java/com/mannschaft/app/resident/controller/TeamResidentController.java) | 75 |
| PATCH | `/api/v1/teams/{_}/residents/{_}/verify` | `TeamResidentController#verify` (backend/src/main/java/com/mannschaft/app/resident/controller/TeamResidentController.java) | 68 |
| PATCH | `/api/v1/teams/{_}/surveys/{_}` | `SurveyController#updateSurvey` (backend/src/main/java/com/mannschaft/app/survey/controller/SurveyController.java) | 98 |
| PATCH | `/api/v1/teams/{_}/todos/{_}/progress` | `TeamTodoController#setProgressRate` (backend/src/main/java/com/mannschaft/app/todo/controller/TeamTodoController.java) | 335 |
| PATCH | `/api/v1/teams/{_}/todos/{_}/progress-mode` | `TeamTodoController#setProgressMode` (backend/src/main/java/com/mannschaft/app/todo/controller/TeamTodoController.java) | 348 |
| PATCH | `/api/v1/teams/{_}/translations/{_}/publish` | `ContentTranslationController#publishTeamTranslation` (backend/src/main/java/com/mannschaft/app/translation/controller/ContentTranslationController.java) | 171 |
| POST | `/api/v1/teams/{_}/announcement-templates` | `AnnouncementRangeTemplateController#createTeamTemplate` (backend/src/main/java/com/mannschaft/app/social/announcement/controller/AnnouncementRangeTemplateController.java) | 91 |
| POST | `/api/v1/teams/{_}/attendance/requirements/evaluations/{_}/disclose` | `AttendanceDisclosureController#disclose` (backend/src/main/java/com/mannschaft/app/school/controller/AttendanceDisclosureController.java) | 43 |
| POST | `/api/v1/teams/{_}/attendance/requirements/evaluations/{_}/withhold` | `AttendanceDisclosureController#withhold` (backend/src/main/java/com/mannschaft/app/school/controller/AttendanceDisclosureController.java) | 63 |
| POST | `/api/v1/teams/{_}/broadcast` | `AnnouncementBroadcastController#broadcastToTeam` (backend/src/main/java/com/mannschaft/app/social/announcement/controller/AnnouncementBroadcastController.java) | 57 |
| POST | `/api/v1/teams/{_}/bulletin/threads/{_}/archive` | `BulletinThreadController#archive` (backend/src/main/java/com/mannschaft/app/bulletin/controller/BulletinThreadController.java) | 178 |
| POST | `/api/v1/teams/{_}/bulletin/threads/{_}/lock` | `BulletinThreadController#toggleLock` (backend/src/main/java/com/mannschaft/app/bulletin/controller/BulletinThreadController.java) | 163 |
| POST | `/api/v1/teams/{_}/bulletin/threads/{_}/pin` | `BulletinThreadController#togglePin` (backend/src/main/java/com/mannschaft/app/bulletin/controller/BulletinThreadController.java) | 148 |
| POST | `/api/v1/teams/{_}/circulations/{_}/activate` | `TeamCirculationDocumentController#activateDocument` (backend/src/main/java/com/mannschaft/app/circulation/controller/TeamCirculationDocumentController.java) | 105 |
| POST | `/api/v1/teams/{_}/circulations/{_}/cancel` | `TeamCirculationDocumentController#cancelDocument` (backend/src/main/java/com/mannschaft/app/circulation/controller/TeamCirculationDocumentController.java) | 118 |
| POST | `/api/v1/teams/{_}/confirmable-notifications/{_}/confirm` | `TeamConfirmableNotificationController#confirm` (backend/src/main/java/com/mannschaft/app/notification/confirmable/controller/TeamConfirmableNotificationController.java) | 208 |
| POST | `/api/v1/teams/{_}/coupons` | `TeamCouponController#create` (backend/src/main/java/com/mannschaft/app/promotion/controller/TeamCouponController.java) | 49 |
| POST | `/api/v1/teams/{_}/direct-mails/estimate-recipients` | `TeamDirectMailController#estimateRecipients` (backend/src/main/java/com/mannschaft/app/directmail/controller/TeamDirectMailController.java) | 172 |
| POST | `/api/v1/teams/{_}/direct-mails/preview` | `TeamDirectMailController#preview` (backend/src/main/java/com/mannschaft/app/directmail/controller/TeamDirectMailController.java) | 160 |
| POST | `/api/v1/teams/{_}/dwelling-units` | `TeamDwellingUnitController#create` (backend/src/main/java/com/mannschaft/app/resident/controller/TeamDwellingUnitController.java) | 50 |
| POST | `/api/v1/teams/{_}/dwelling-units/batch` | `TeamDwellingUnitController#batchCreate` (backend/src/main/java/com/mannschaft/app/resident/controller/TeamDwellingUnitController.java) | 81 |
| POST | `/api/v1/teams/{_}/dwelling-units/{_}/residents` | `TeamResidentController#create` (backend/src/main/java/com/mannschaft/app/resident/controller/TeamResidentController.java) | 45 |
| POST | `/api/v1/teams/{_}/emergency-closures/{_}/confirm` | `TeamEmergencyClosureController#confirmClosure` (backend/src/main/java/com/mannschaft/app/reservation/controller/TeamEmergencyClosureController.java) | 90 |
| POST | `/api/v1/teams/{_}/events/{_}/close-registration` | `TeamEventController#closeRegistration` (backend/src/main/java/com/mannschaft/app/event/controller/TeamEventController.java) | 129 |
| POST | `/api/v1/teams/{_}/events/{_}/open-registration` | `TeamEventController#openRegistration` (backend/src/main/java/com/mannschaft/app/event/controller/TeamEventController.java) | 116 |
| POST | `/api/v1/teams/{_}/events/{_}/rsvp-responses` | `EventRsvpController#submitTeamRsvp` (backend/src/main/java/com/mannschaft/app/event/controller/EventRsvpController.java) | 120 |
| POST | `/api/v1/teams/{_}/form-templates/{_}/close` | `FormTemplateController#closeTemplate` (backend/src/main/java/com/mannschaft/app/forms/controller/FormTemplateController.java) | 121 |
| POST | `/api/v1/teams/{_}/form-templates/{_}/publish` | `FormTemplateController#publishTemplate` (backend/src/main/java/com/mannschaft/app/forms/controller/FormTemplateController.java) | 107 |
| POST | `/api/v1/teams/{_}/form-templates/{_}/submissions/{_}/approve` | `FormSubmissionAdminController#approveSubmission` (backend/src/main/java/com/mannschaft/app/forms/controller/FormSubmissionAdminController.java) | 54 |
| POST | `/api/v1/teams/{_}/form-templates/{_}/submissions/{_}/reject` | `FormSubmissionAdminController#rejectSubmission` (backend/src/main/java/com/mannschaft/app/forms/controller/FormSubmissionAdminController.java) | 69 |
| POST | `/api/v1/teams/{_}/form-templates/{_}/submissions/{_}/return` | `FormSubmissionAdminController#returnSubmission` (backend/src/main/java/com/mannschaft/app/forms/controller/FormSubmissionAdminController.java) | 84 |
| POST | `/api/v1/teams/{_}/line/config` | `LineBotConfigController#createForTeam` (backend/src/main/java/com/mannschaft/app/line/controller/LineBotConfigController.java) | 41 |
| POST | `/api/v1/teams/{_}/line/test` | `LineBotConfigController#sendTestForTeam` (backend/src/main/java/com/mannschaft/app/line/controller/LineBotConfigController.java) | 80 |
| POST | `/api/v1/teams/{_}/promotions` | `TeamPromotionController#create` (backend/src/main/java/com/mannschaft/app/promotion/controller/TeamPromotionController.java) | 54 |
| POST | `/api/v1/teams/{_}/promotions/estimate-audience` | `TeamPromotionController#estimateAudience` (backend/src/main/java/com/mannschaft/app/promotion/controller/TeamPromotionController.java) | 121 |
| POST | `/api/v1/teams/{_}/promotions/{_}/approve` | `TeamPromotionController#approve` (backend/src/main/java/com/mannschaft/app/promotion/controller/TeamPromotionController.java) | 107 |
| POST | `/api/v1/teams/{_}/promotions/{_}/cancel` | `TeamPromotionController#cancel` (backend/src/main/java/com/mannschaft/app/promotion/controller/TeamPromotionController.java) | 100 |
| POST | `/api/v1/teams/{_}/promotions/{_}/publish` | `TeamPromotionController#publish` (backend/src/main/java/com/mannschaft/app/promotion/controller/TeamPromotionController.java) | 85 |
| POST | `/api/v1/teams/{_}/promotions/{_}/schedule` | `TeamPromotionController#schedule` (backend/src/main/java/com/mannschaft/app/promotion/controller/TeamPromotionController.java) | 92 |
| POST | `/api/v1/teams/{_}/property-history/export` | `PropertyWorkPackageController#exportList` (backend/src/main/java/com/mannschaft/app/property/controller/PropertyWorkPackageController.java) | 300 |
| POST | `/api/v1/teams/{_}/property-history/{_}/documents` | `PropertyWorkPackageController#attachDocument` (backend/src/main/java/com/mannschaft/app/property/controller/PropertyWorkPackageController.java) | 249 |
| POST | `/api/v1/teams/{_}/property-history/{_}/export` | `PropertyWorkPackageController#exportSingle` (backend/src/main/java/com/mannschaft/app/property/controller/PropertyWorkPackageController.java) | 287 |
| POST | `/api/v1/teams/{_}/property-listings` | `TeamPropertyListingController#create` (backend/src/main/java/com/mannschaft/app/resident/controller/TeamPropertyListingController.java) | 55 |
| POST | `/api/v1/teams/{_}/property-listings/{_}/inquiries` | `TeamPropertyListingController#createInquiry` (backend/src/main/java/com/mannschaft/app/resident/controller/TeamPropertyListingController.java) | 86 |
| POST | `/api/v1/teams/{_}/queue/counters/{_}/tickets/call-next` | `QueueTicketController#callNext` (backend/src/main/java/com/mannschaft/app/queue/controller/QueueTicketController.java) | 136 |
| POST | `/api/v1/teams/{_}/queue/counters/{_}/tickets/guest` | `QueueTicketController#issueGuestTicket` (backend/src/main/java/com/mannschaft/app/queue/controller/QueueTicketController.java) | 162 |
| POST | `/api/v1/teams/{_}/queue/counters/{_}/tickets/qr` | `QueueTicketController#issueQrTicket` (backend/src/main/java/com/mannschaft/app/queue/controller/QueueTicketController.java) | 177 |
| POST | `/api/v1/teams/{_}/recruitment-listings/from-template` | `RecruitmentTemplateController#createFromTemplateForTeam` (backend/src/main/java/com/mannschaft/app/recruitment/controller/RecruitmentTemplateController.java) | 72 |
| POST | `/api/v1/teams/{_}/recruitment-subcategories/{_}/archive` | `TeamRecruitmentSubcategoryController#archive` (backend/src/main/java/com/mannschaft/app/recruitment/controller/TeamRecruitmentSubcategoryController.java) | 55 |
| POST | `/api/v1/teams/{_}/repair-plan/items/import-csv` | `RepairPlanItemCsvController#preview` (backend/src/main/java/com/mannschaft/app/repairplan/controller/RepairPlanItemCsvController.java) | 39 |
| POST | `/api/v1/teams/{_}/repair-plan/items/import-csv/confirm` | `RepairPlanItemCsvController#confirm` (backend/src/main/java/com/mannschaft/app/repairplan/controller/RepairPlanItemCsvController.java) | 55 |
| POST | `/api/v1/teams/{_}/repair-plan/quote-cards/{_}/move` | `RepairPlanQuoteKanbanController#moveCard` (backend/src/main/java/com/mannschaft/app/repairplan/controller/RepairPlanQuoteKanbanController.java) | 147 |
| POST | `/api/v1/teams/{_}/repair-plan/quote-kanbans` | `RepairPlanQuoteKanbanController#createKanban` (backend/src/main/java/com/mannschaft/app/repairplan/controller/RepairPlanQuoteKanbanController.java) | 81 |
| POST | `/api/v1/teams/{_}/repair-plan/quote-kanbans/{_}/cards` | `RepairPlanQuoteKanbanController#addCard` (backend/src/main/java/com/mannschaft/app/repairplan/controller/RepairPlanQuoteKanbanController.java) | 129 |
| POST | `/api/v1/teams/{_}/repair-plan/scenarios/simulate` | `RepairPlanScenarioController#simulate` (backend/src/main/java/com/mannschaft/app/repairplan/controller/RepairPlanScenarioController.java) | 69 |
| POST | `/api/v1/teams/{_}/repair-plan/scenarios/{_}/pin-to-corkboard` | `RepairPlanScenarioController#pinToCorkboard` (backend/src/main/java/com/mannschaft/app/repairplan/controller/RepairPlanScenarioController.java) | 145 |
| POST | `/api/v1/teams/{_}/repair-plan/scenarios/{_}/publish-as-announcement` | `RepairPlanScenarioController#publishAsAnnouncement` (backend/src/main/java/com/mannschaft/app/repairplan/controller/RepairPlanScenarioController.java) | 128 |
| POST | `/api/v1/teams/{_}/residents/{_}/documents` | `TeamResidentDocumentController#upload` (backend/src/main/java/com/mannschaft/app/resident/controller/TeamResidentDocumentController.java) | 33 |
| POST | `/api/v1/teams/{_}/segment-presets` | `TeamSegmentPresetController#create` (backend/src/main/java/com/mannschaft/app/promotion/controller/TeamSegmentPresetController.java) | 40 |
| POST | `/api/v1/teams/{_}/sns/feeds` | `SnsFeedConfigController#createForTeam` (backend/src/main/java/com/mannschaft/app/line/controller/SnsFeedConfigController.java) | 47 |
| POST | `/api/v1/teams/{_}/supporter-applications/bulk-approve` | `TeamController#bulkApproveSupporterApplications` (backend/src/main/java/com/mannschaft/app/team/controller/TeamController.java) | 240 |
| POST | `/api/v1/teams/{_}/supporter-applications/{_}/approve` | `TeamController#approveSupporterApplication` (backend/src/main/java/com/mannschaft/app/team/controller/TeamController.java) | 222 |
| POST | `/api/v1/teams/{_}/supporter-applications/{_}/reject` | `TeamController#rejectSupporterApplication` (backend/src/main/java/com/mannschaft/app/team/controller/TeamController.java) | 231 |
| POST | `/api/v1/teams/{_}/surveys/{_}/close` | `SurveyController#closeSurvey` (backend/src/main/java/com/mannschaft/app/survey/controller/SurveyController.java) | 127 |
| POST | `/api/v1/teams/{_}/surveys/{_}/publish` | `SurveyController#publishSurvey` (backend/src/main/java/com/mannschaft/app/survey/controller/SurveyController.java) | 113 |
| POST | `/api/v1/teams/{_}/todos/{_}/link-schedule` | `TeamTodoController#linkSchedule` (backend/src/main/java/com/mannschaft/app/todo/controller/TeamTodoController.java) | 285 |
| POST | `/api/v1/teams/{_}/todos/{_}/memos` | `TeamTodoController#addSharedMemo` (backend/src/main/java/com/mannschaft/app/todo/controller/TeamTodoController.java) | 378 |
| POST | `/api/v1/teams/{_}/translations/mark-stale` | `ContentTranslationController#markTeamTranslationsAsStale` (backend/src/main/java/com/mannschaft/app/translation/controller/ContentTranslationController.java) | 186 |
| POST | `/api/v1/teams/{_}/workflow-requests/{_}/submit` | `WorkflowRequestController#submitRequest` (backend/src/main/java/com/mannschaft/app/workflow/controller/WorkflowRequestController.java) | 106 |
| POST | `/api/v1/teams/{_}/workflow-requests/{_}/withdraw` | `WorkflowRequestController#withdrawRequest` (backend/src/main/java/com/mannschaft/app/workflow/controller/WorkflowRequestController.java) | 120 |
| POST | `/api/v1/teams/{_}/workflow-templates/{_}/activate` | `WorkflowTemplateStatusController#activateTemplate` (backend/src/main/java/com/mannschaft/app/workflow/controller/WorkflowTemplateStatusController.java) | 29 |
| POST | `/api/v1/teams/{_}/workflow-templates/{_}/deactivate` | `WorkflowTemplateStatusController#deactivateTemplate` (backend/src/main/java/com/mannschaft/app/workflow/controller/WorkflowTemplateStatusController.java) | 43 |
| PUT | `/api/v1/teams/{_}/announcement-templates/{_}` | `AnnouncementRangeTemplateController#updateTeamTemplate` (backend/src/main/java/com/mannschaft/app/social/announcement/controller/AnnouncementRangeTemplateController.java) | 119 |
| PUT | `/api/v1/teams/{_}/bulletin/categories/{_}` | `BulletinCategoryController#updateCategory` (backend/src/main/java/com/mannschaft/app/bulletin/controller/BulletinCategoryController.java) | 86 |
| PUT | `/api/v1/teams/{_}/bulletin/threads/{_}` | `BulletinThreadController#updateThread` (backend/src/main/java/com/mannschaft/app/bulletin/controller/BulletinThreadController.java) | 117 |
| PUT | `/api/v1/teams/{_}/bulletin/threads/{_}/replies/{_}` | `BulletinReplyController#updateReply` (backend/src/main/java/com/mannschaft/app/bulletin/controller/BulletinReplyController.java) | 79 |
| PUT | `/api/v1/teams/{_}/care-overrides/{_}` | `TeamCareOverrideController#upsertTeamOverride` (backend/src/main/java/com/mannschaft/app/family/controller/TeamCareOverrideController.java) | 50 |
| PUT | `/api/v1/teams/{_}/confirmable-notification-templates/{_}` | `TeamConfirmableNotificationTemplateController#update` (backend/src/main/java/com/mannschaft/app/notification/confirmable/controller/TeamConfirmableNotificationTemplateController.java) | 81 |
| PUT | `/api/v1/teams/{_}/corkboards/{_}` | `TeamCorkboardController#updateBoard` (backend/src/main/java/com/mannschaft/app/corkboard/controller/TeamCorkboardController.java) | 78 |
| PUT | `/api/v1/teams/{_}/coupons/{_}` | `TeamCouponController#update` (backend/src/main/java/com/mannschaft/app/promotion/controller/TeamCouponController.java) | 65 |
| PUT | `/api/v1/teams/{_}/dwelling-units/{_}` | `TeamDwellingUnitController#update` (backend/src/main/java/com/mannschaft/app/resident/controller/TeamDwellingUnitController.java) | 66 |
| PUT | `/api/v1/teams/{_}/events/{_}/rsvp-responses/me` | `EventRsvpController#updateTeamRsvp` (backend/src/main/java/com/mannschaft/app/event/controller/EventRsvpController.java) | 135 |
| PUT | `/api/v1/teams/{_}/form-submissions/{_}` | `FormSubmissionController#updateSubmission` (backend/src/main/java/com/mannschaft/app/forms/controller/FormSubmissionController.java) | 90 |
| PUT | `/api/v1/teams/{_}/form-templates/{_}` | `FormTemplateController#updateTemplate` (backend/src/main/java/com/mannschaft/app/forms/controller/FormTemplateController.java) | 91 |
| PUT | `/api/v1/teams/{_}/line/config` | `LineBotConfigController#updateForTeam` (backend/src/main/java/com/mannschaft/app/line/controller/LineBotConfigController.java) | 53 |
| PUT | `/api/v1/teams/{_}/modules/template` | `TeamModuleController#applyTemplate` (backend/src/main/java/com/mannschaft/app/template/controller/TeamModuleController.java) | 64 |
| PUT | `/api/v1/teams/{_}/promotions/{_}` | `TeamPromotionController#update` (backend/src/main/java/com/mannschaft/app/promotion/controller/TeamPromotionController.java) | 70 |
| PUT | `/api/v1/teams/{_}/property-history/{_}` | `PropertyWorkPackageController#updatePackage` (backend/src/main/java/com/mannschaft/app/property/controller/PropertyWorkPackageController.java) | 208 |
| PUT | `/api/v1/teams/{_}/property-listings/{_}` | `TeamPropertyListingController#update` (backend/src/main/java/com/mannschaft/app/resident/controller/TeamPropertyListingController.java) | 71 |
| PUT | `/api/v1/teams/{_}/residents/{_}` | `TeamResidentController#update` (backend/src/main/java/com/mannschaft/app/resident/controller/TeamResidentController.java) | 53 |
| PUT | `/api/v1/teams/{_}/segment-presets/{_}` | `TeamSegmentPresetController#update` (backend/src/main/java/com/mannschaft/app/promotion/controller/TeamSegmentPresetController.java) | 49 |
| PUT | `/api/v1/teams/{_}/sns/feeds/{_}` | `SnsFeedConfigController#updateForTeam` (backend/src/main/java/com/mannschaft/app/line/controller/SnsFeedConfigController.java) | 60 |
| PUT | `/api/v1/teams/{_}/supporter-settings` | `TeamController#updateSupporterSettings` (backend/src/main/java/com/mannschaft/app/team/controller/TeamController.java) | 256 |
| PUT | `/api/v1/teams/{_}/todos/{_}/memos/{_}` | `TeamTodoController#updateSharedMemo` (backend/src/main/java/com/mannschaft/app/todo/controller/TeamTodoController.java) | 393 |
| PUT | `/api/v1/teams/{_}/todos/{_}/my-memo` | `TeamTodoController#upsertPersonalMemo` (backend/src/main/java/com/mannschaft/app/todo/controller/TeamTodoController.java) | 435 |
| PUT | `/api/v1/teams/{_}/vendors/{_}` | `VendorController#updateVendor` (backend/src/main/java/com/mannschaft/app/property/controller/VendorController.java) | 140 |
| PUT | `/api/v1/teams/{_}/workflow-requests/{_}` | `WorkflowRequestController#updateRequest` (backend/src/main/java/com/mannschaft/app/workflow/controller/WorkflowRequestController.java) | 91 |
| PUT | `/api/v1/teams/{_}/workflow-templates/{_}` | `WorkflowTemplateController#updateTemplate` (backend/src/main/java/com/mannschaft/app/workflow/controller/WorkflowTemplateController.java) | 90 |

#### /api/v1/users/* (99 件)

| メソッド | パス | Controller | 行 |
|---|---|---|---|
| DELETE | `/api/v1/users/me/corkboards/{_}` | `MyCorkboardController#deleteBoard` (backend/src/main/java/com/mannschaft/app/corkboard/controller/MyCorkboardController.java) | 88 |
| DELETE | `/api/v1/users/me/line/link` | `UserLineController#unlink` (backend/src/main/java/com/mannschaft/app/line/controller/UserLineController.java) | 52 |
| DELETE | `/api/v1/users/{_}/bulletin/categories/{_}` | `BulletinCategoryController#deleteCategory` (backend/src/main/java/com/mannschaft/app/bulletin/controller/BulletinCategoryController.java) | 102 |
| DELETE | `/api/v1/users/{_}/bulletin/threads/{_}` | `BulletinThreadController#deleteThread` (backend/src/main/java/com/mannschaft/app/bulletin/controller/BulletinThreadController.java) | 133 |
| DELETE | `/api/v1/users/{_}/bulletin/threads/{_}/replies/{_}` | `BulletinReplyController#deleteReply` (backend/src/main/java/com/mannschaft/app/bulletin/controller/BulletinReplyController.java) | 96 |
| DELETE | `/api/v1/users/{_}/form-submissions/{_}` | `FormSubmissionController#deleteSubmission` (backend/src/main/java/com/mannschaft/app/forms/controller/FormSubmissionController.java) | 106 |
| DELETE | `/api/v1/users/{_}/form-templates/{_}` | `FormTemplateController#deleteTemplate` (backend/src/main/java/com/mannschaft/app/forms/controller/FormTemplateController.java) | 135 |
| DELETE | `/api/v1/users/{_}/property-history/{_}` | `PropertyWorkPackageController#deletePackage` (backend/src/main/java/com/mannschaft/app/property/controller/PropertyWorkPackageController.java) | 236 |
| DELETE | `/api/v1/users/{_}/property-history/{_}/documents/{_}` | `PropertyWorkPackageController#detachDocument` (backend/src/main/java/com/mannschaft/app/property/controller/PropertyWorkPackageController.java) | 271 |
| DELETE | `/api/v1/users/{_}/repair-plan/handover-packs/{_}` | `BoardHandoverPackController#deletePack` (backend/src/main/java/com/mannschaft/app/repairplan/controller/BoardHandoverPackController.java) | 101 |
| DELETE | `/api/v1/users/{_}/repair-plan/items/{_}` | `RepairPlanItemController#delete` (backend/src/main/java/com/mannschaft/app/repairplan/controller/RepairPlanItemController.java) | 137 |
| DELETE | `/api/v1/users/{_}/seals/{_}` | `SealController#deleteSeal` (backend/src/main/java/com/mannschaft/app/seal/controller/SealController.java) | 91 |
| DELETE | `/api/v1/users/{_}/surveys/{_}` | `SurveyController#deleteSurvey` (backend/src/main/java/com/mannschaft/app/survey/controller/SurveyController.java) | 141 |
| DELETE | `/api/v1/users/{_}/surveys/{_}/questions/{_}` | `SurveyQuestionController#deleteQuestion` (backend/src/main/java/com/mannschaft/app/survey/controller/SurveyQuestionController.java) | 49 |
| DELETE | `/api/v1/users/{_}/vendors/{_}` | `VendorController#deleteVendor` (backend/src/main/java/com/mannschaft/app/property/controller/VendorController.java) | 151 |
| DELETE | `/api/v1/users/{_}/workflow-requests/{_}` | `WorkflowRequestController#deleteRequest` (backend/src/main/java/com/mannschaft/app/workflow/controller/WorkflowRequestController.java) | 134 |
| DELETE | `/api/v1/users/{_}/workflow-templates/{_}` | `WorkflowTemplateController#deleteTemplate` (backend/src/main/java/com/mannschaft/app/workflow/controller/WorkflowTemplateController.java) | 105 |
| GET | `/api/v1/users/me/blog/posts` | `PersonalBlogController#listMyPosts` (backend/src/main/java/com/mannschaft/app/cms/controller/PersonalBlogController.java) | 87 |
| GET | `/api/v1/users/me/coupons` | `UserPromotionController#listCoupons` (backend/src/main/java/com/mannschaft/app/promotion/controller/UserPromotionController.java) | 57 |
| GET | `/api/v1/users/me/dwelling-unit` | `UserResidentController#getMyUnit` (backend/src/main/java/com/mannschaft/app/resident/controller/UserResidentController.java) | 25 |
| GET | `/api/v1/users/me/line/status` | `UserLineController#getStatus` (backend/src/main/java/com/mannschaft/app/line/controller/UserLineController.java) | 32 |
| GET | `/api/v1/users/me/projects/{_}/gates` | `MilestoneGateController#getPersonalGatesSummary` (backend/src/main/java/com/mannschaft/app/todo/controller/MilestoneGateController.java) | 146 |
| GET | `/api/v1/users/me/promotions` | `UserPromotionController#listPromotions` (backend/src/main/java/com/mannschaft/app/promotion/controller/UserPromotionController.java) | 38 |
| GET | `/api/v1/users/me/resident-info` | `UserResidentController#getMyResidentInfo` (backend/src/main/java/com/mannschaft/app/resident/controller/UserResidentController.java) | 31 |
| GET | `/api/v1/users/{_}/bulletin/categories/{_}` | `BulletinCategoryController#getCategory` (backend/src/main/java/com/mannschaft/app/bulletin/controller/BulletinCategoryController.java) | 56 |
| GET | `/api/v1/users/{_}/bulletin/threads/search` | `BulletinThreadController#searchThreads` (backend/src/main/java/com/mannschaft/app/bulletin/controller/BulletinThreadController.java) | 83 |
| GET | `/api/v1/users/{_}/bulletin/threads/{_}` | `BulletinThreadController#getThread` (backend/src/main/java/com/mannschaft/app/bulletin/controller/BulletinThreadController.java) | 68 |
| GET | `/api/v1/users/{_}/form-submissions/my` | `FormSubmissionController#listMySubmissions` (backend/src/main/java/com/mannschaft/app/forms/controller/FormSubmissionController.java) | 43 |
| GET | `/api/v1/users/{_}/form-submissions/{_}` | `FormSubmissionController#getSubmission` (backend/src/main/java/com/mannschaft/app/forms/controller/FormSubmissionController.java) | 61 |
| GET | `/api/v1/users/{_}/form-templates/{_}` | `FormTemplateController#getTemplate` (backend/src/main/java/com/mannschaft/app/forms/controller/FormTemplateController.java) | 62 |
| GET | `/api/v1/users/{_}/property-history/categories/suggestions` | `PropertyWorkPackageController#categorySuggestions` (backend/src/main/java/com/mannschaft/app/property/controller/PropertyWorkPackageController.java) | 329 |
| GET | `/api/v1/users/{_}/property-history/gantt` | `PropertyWorkPackageController#gantt` (backend/src/main/java/com/mannschaft/app/property/controller/PropertyWorkPackageController.java) | 163 |
| GET | `/api/v1/users/{_}/property-history/timeline` | `PropertyWorkPackageController#timeline` (backend/src/main/java/com/mannschaft/app/property/controller/PropertyWorkPackageController.java) | 141 |
| GET | `/api/v1/users/{_}/property-history/{_}` | `PropertyWorkPackageController#getPackage` (backend/src/main/java/com/mannschaft/app/property/controller/PropertyWorkPackageController.java) | 183 |
| GET | `/api/v1/users/{_}/repair-plan/dashboard` | `RepairPlanDashboardController#getDashboard` (backend/src/main/java/com/mannschaft/app/repairplan/controller/RepairPlanDashboardController.java) | 38 |
| GET | `/api/v1/users/{_}/repair-plan/handover-packs/{_}/download` | `BoardHandoverPackController#getDownloadUrl` (backend/src/main/java/com/mannschaft/app/repairplan/controller/BoardHandoverPackController.java) | 86 |
| GET | `/api/v1/users/{_}/repair-plan/items/{_}` | `RepairPlanItemController#get` (backend/src/main/java/com/mannschaft/app/repairplan/controller/RepairPlanItemController.java) | 109 |
| GET | `/api/v1/users/{_}/repair-plan/quote-kanbans` | `RepairPlanQuoteKanbanController#listKanbans` (backend/src/main/java/com/mannschaft/app/repairplan/controller/RepairPlanQuoteKanbanController.java) | 67 |
| GET | `/api/v1/users/{_}/repair-plan/quote-kanbans/{_}` | `RepairPlanQuoteKanbanController#getKanban` (backend/src/main/java/com/mannschaft/app/repairplan/controller/RepairPlanQuoteKanbanController.java) | 97 |
| GET | `/api/v1/users/{_}/repair-plan/scenarios/{_}` | `RepairPlanScenarioController#getScenario` (backend/src/main/java/com/mannschaft/app/repairplan/controller/RepairPlanScenarioController.java) | 114 |
| GET | `/api/v1/users/{_}/repair-plan/timeline` | `RepairPlanTimelineController#getTimeline` (backend/src/main/java/com/mannschaft/app/repairplan/controller/RepairPlanTimelineController.java) | 41 |
| GET | `/api/v1/users/{_}/seals/{_}` | `SealController#getSeal` (backend/src/main/java/com/mannschaft/app/seal/controller/SealController.java) | 51 |
| GET | `/api/v1/users/{_}/stamps/{_}/verify` | `SealStampController#verifyStamp` (backend/src/main/java/com/mannschaft/app/seal/controller/SealStampController.java) | 65 |
| GET | `/api/v1/users/{_}/surveys/stats` | `SurveyController#getStats` (backend/src/main/java/com/mannschaft/app/survey/controller/SurveyController.java) | 174 |
| GET | `/api/v1/users/{_}/surveys/{_}` | `SurveyController#getSurvey` (backend/src/main/java/com/mannschaft/app/survey/controller/SurveyController.java) | 69 |
| GET | `/api/v1/users/{_}/surveys/{_}/respondents` | `SurveyController#getRespondents` (backend/src/main/java/com/mannschaft/app/survey/controller/SurveyController.java) | 158 |
| GET | `/api/v1/users/{_}/vendors/search` | `VendorController#searchVendors` (backend/src/main/java/com/mannschaft/app/property/controller/VendorController.java) | 103 |
| GET | `/api/v1/users/{_}/vendors/{_}` | `VendorController#getVendor` (backend/src/main/java/com/mannschaft/app/property/controller/VendorController.java) | 117 |
| GET | `/api/v1/users/{_}/workflow-requests/{_}` | `WorkflowRequestController#getRequest` (backend/src/main/java/com/mannschaft/app/workflow/controller/WorkflowRequestController.java) | 62 |
| GET | `/api/v1/users/{_}/workflow-templates/{_}` | `WorkflowTemplateController#getTemplate` (backend/src/main/java/com/mannschaft/app/workflow/controller/WorkflowTemplateController.java) | 61 |
| PATCH | `/api/v1/users/me/projects/{_}/milestones/{_}/completion-mode` | `MilestoneGateController#changePersonalCompletionMode` (backend/src/main/java/com/mannschaft/app/todo/controller/MilestoneGateController.java) | 158 |
| PATCH | `/api/v1/users/me/projects/{_}/milestones/{_}/force-unlock` | `MilestoneGateController#forceUnlockPersonalMilestone` (backend/src/main/java/com/mannschaft/app/todo/controller/MilestoneGateController.java) | 173 |
| PATCH | `/api/v1/users/me/projects/{_}/milestones/{_}/initialize-gate` | `MilestoneGateController#initializePersonalGate` (backend/src/main/java/com/mannschaft/app/todo/controller/MilestoneGateController.java) | 188 |
| PATCH | `/api/v1/users/me/projects/{_}/milestones/{_}/todos/reorder` | `MilestoneGateController#reorderPersonalMilestoneTodos` (backend/src/main/java/com/mannschaft/app/todo/controller/MilestoneGateController.java) | 202 |
| PATCH | `/api/v1/users/me/promotions/{_}/read` | `UserPromotionController#markAsRead` (backend/src/main/java/com/mannschaft/app/promotion/controller/UserPromotionController.java) | 50 |
| PATCH | `/api/v1/users/{_}/property-history/{_}/status` | `PropertyWorkPackageController#changeStatus` (backend/src/main/java/com/mannschaft/app/property/controller/PropertyWorkPackageController.java) | 222 |
| PATCH | `/api/v1/users/{_}/repair-plan/items/{_}` | `RepairPlanItemController#update` (backend/src/main/java/com/mannschaft/app/repairplan/controller/RepairPlanItemController.java) | 121 |
| PATCH | `/api/v1/users/{_}/repair-plan/quote-kanbans/{_}` | `RepairPlanQuoteKanbanController#updateKanban` (backend/src/main/java/com/mannschaft/app/repairplan/controller/RepairPlanQuoteKanbanController.java) | 111 |
| PATCH | `/api/v1/users/{_}/surveys/{_}` | `SurveyController#updateSurvey` (backend/src/main/java/com/mannschaft/app/survey/controller/SurveyController.java) | 98 |
| POST | `/api/v1/users/me/coupons/{_}/redeem` | `UserPromotionController#redeemCoupon` (backend/src/main/java/com/mannschaft/app/promotion/controller/UserPromotionController.java) | 63 |
| POST | `/api/v1/users/me/line/link` | `UserLineController#link` (backend/src/main/java/com/mannschaft/app/line/controller/UserLineController.java) | 41 |
| POST | `/api/v1/users/{_}/bulletin/threads/{_}/archive` | `BulletinThreadController#archive` (backend/src/main/java/com/mannschaft/app/bulletin/controller/BulletinThreadController.java) | 178 |
| POST | `/api/v1/users/{_}/bulletin/threads/{_}/lock` | `BulletinThreadController#toggleLock` (backend/src/main/java/com/mannschaft/app/bulletin/controller/BulletinThreadController.java) | 163 |
| POST | `/api/v1/users/{_}/bulletin/threads/{_}/pin` | `BulletinThreadController#togglePin` (backend/src/main/java/com/mannschaft/app/bulletin/controller/BulletinThreadController.java) | 148 |
| POST | `/api/v1/users/{_}/form-templates/{_}/close` | `FormTemplateController#closeTemplate` (backend/src/main/java/com/mannschaft/app/forms/controller/FormTemplateController.java) | 121 |
| POST | `/api/v1/users/{_}/form-templates/{_}/publish` | `FormTemplateController#publishTemplate` (backend/src/main/java/com/mannschaft/app/forms/controller/FormTemplateController.java) | 107 |
| POST | `/api/v1/users/{_}/form-templates/{_}/submissions/{_}/approve` | `FormSubmissionAdminController#approveSubmission` (backend/src/main/java/com/mannschaft/app/forms/controller/FormSubmissionAdminController.java) | 54 |
| POST | `/api/v1/users/{_}/form-templates/{_}/submissions/{_}/reject` | `FormSubmissionAdminController#rejectSubmission` (backend/src/main/java/com/mannschaft/app/forms/controller/FormSubmissionAdminController.java) | 69 |
| POST | `/api/v1/users/{_}/form-templates/{_}/submissions/{_}/return` | `FormSubmissionAdminController#returnSubmission` (backend/src/main/java/com/mannschaft/app/forms/controller/FormSubmissionAdminController.java) | 84 |
| POST | `/api/v1/users/{_}/property-history/export` | `PropertyWorkPackageController#exportList` (backend/src/main/java/com/mannschaft/app/property/controller/PropertyWorkPackageController.java) | 300 |
| POST | `/api/v1/users/{_}/property-history/{_}/documents` | `PropertyWorkPackageController#attachDocument` (backend/src/main/java/com/mannschaft/app/property/controller/PropertyWorkPackageController.java) | 249 |
| POST | `/api/v1/users/{_}/property-history/{_}/export` | `PropertyWorkPackageController#exportSingle` (backend/src/main/java/com/mannschaft/app/property/controller/PropertyWorkPackageController.java) | 287 |
| POST | `/api/v1/users/{_}/repair-plan/items/import-csv` | `RepairPlanItemCsvController#preview` (backend/src/main/java/com/mannschaft/app/repairplan/controller/RepairPlanItemCsvController.java) | 39 |
| POST | `/api/v1/users/{_}/repair-plan/items/import-csv/confirm` | `RepairPlanItemCsvController#confirm` (backend/src/main/java/com/mannschaft/app/repairplan/controller/RepairPlanItemCsvController.java) | 55 |
| POST | `/api/v1/users/{_}/repair-plan/quote-cards/{_}/move` | `RepairPlanQuoteKanbanController#moveCard` (backend/src/main/java/com/mannschaft/app/repairplan/controller/RepairPlanQuoteKanbanController.java) | 147 |
| POST | `/api/v1/users/{_}/repair-plan/quote-kanbans` | `RepairPlanQuoteKanbanController#createKanban` (backend/src/main/java/com/mannschaft/app/repairplan/controller/RepairPlanQuoteKanbanController.java) | 81 |
| POST | `/api/v1/users/{_}/repair-plan/quote-kanbans/{_}/cards` | `RepairPlanQuoteKanbanController#addCard` (backend/src/main/java/com/mannschaft/app/repairplan/controller/RepairPlanQuoteKanbanController.java) | 129 |
| POST | `/api/v1/users/{_}/repair-plan/scenarios/simulate` | `RepairPlanScenarioController#simulate` (backend/src/main/java/com/mannschaft/app/repairplan/controller/RepairPlanScenarioController.java) | 69 |
| POST | `/api/v1/users/{_}/repair-plan/scenarios/{_}/pin-to-corkboard` | `RepairPlanScenarioController#pinToCorkboard` (backend/src/main/java/com/mannschaft/app/repairplan/controller/RepairPlanScenarioController.java) | 145 |
| POST | `/api/v1/users/{_}/repair-plan/scenarios/{_}/publish-as-announcement` | `RepairPlanScenarioController#publishAsAnnouncement` (backend/src/main/java/com/mannschaft/app/repairplan/controller/RepairPlanScenarioController.java) | 128 |
| POST | `/api/v1/users/{_}/stamps/scope-defaults` | `SealStampController#setScopeDefault` (backend/src/main/java/com/mannschaft/app/seal/controller/SealStampController.java) | 78 |
| POST | `/api/v1/users/{_}/stamps/{_}/revoke` | `SealStampController#revokeStamp` (backend/src/main/java/com/mannschaft/app/seal/controller/SealStampController.java) | 52 |
| POST | `/api/v1/users/{_}/surveys/{_}/close` | `SurveyController#closeSurvey` (backend/src/main/java/com/mannschaft/app/survey/controller/SurveyController.java) | 127 |
| POST | `/api/v1/users/{_}/surveys/{_}/publish` | `SurveyController#publishSurvey` (backend/src/main/java/com/mannschaft/app/survey/controller/SurveyController.java) | 113 |
| POST | `/api/v1/users/{_}/workflow-requests/{_}/submit` | `WorkflowRequestController#submitRequest` (backend/src/main/java/com/mannschaft/app/workflow/controller/WorkflowRequestController.java) | 106 |
| POST | `/api/v1/users/{_}/workflow-requests/{_}/withdraw` | `WorkflowRequestController#withdrawRequest` (backend/src/main/java/com/mannschaft/app/workflow/controller/WorkflowRequestController.java) | 120 |
| POST | `/api/v1/users/{_}/workflow-templates/{_}/activate` | `WorkflowTemplateStatusController#activateTemplate` (backend/src/main/java/com/mannschaft/app/workflow/controller/WorkflowTemplateStatusController.java) | 29 |
| POST | `/api/v1/users/{_}/workflow-templates/{_}/deactivate` | `WorkflowTemplateStatusController#deactivateTemplate` (backend/src/main/java/com/mannschaft/app/workflow/controller/WorkflowTemplateStatusController.java) | 43 |
| PUT | `/api/v1/users/me/corkboards/{_}` | `MyCorkboardController#updateBoard` (backend/src/main/java/com/mannschaft/app/corkboard/controller/MyCorkboardController.java) | 76 |
| PUT | `/api/v1/users/{_}/bulletin/categories/{_}` | `BulletinCategoryController#updateCategory` (backend/src/main/java/com/mannschaft/app/bulletin/controller/BulletinCategoryController.java) | 86 |
| PUT | `/api/v1/users/{_}/bulletin/threads/{_}` | `BulletinThreadController#updateThread` (backend/src/main/java/com/mannschaft/app/bulletin/controller/BulletinThreadController.java) | 117 |
| PUT | `/api/v1/users/{_}/bulletin/threads/{_}/replies/{_}` | `BulletinReplyController#updateReply` (backend/src/main/java/com/mannschaft/app/bulletin/controller/BulletinReplyController.java) | 79 |
| PUT | `/api/v1/users/{_}/form-submissions/{_}` | `FormSubmissionController#updateSubmission` (backend/src/main/java/com/mannschaft/app/forms/controller/FormSubmissionController.java) | 90 |
| PUT | `/api/v1/users/{_}/form-templates/{_}` | `FormTemplateController#updateTemplate` (backend/src/main/java/com/mannschaft/app/forms/controller/FormTemplateController.java) | 91 |
| PUT | `/api/v1/users/{_}/property-history/{_}` | `PropertyWorkPackageController#updatePackage` (backend/src/main/java/com/mannschaft/app/property/controller/PropertyWorkPackageController.java) | 208 |
| PUT | `/api/v1/users/{_}/seals/{_}` | `SealController#updateSeal` (backend/src/main/java/com/mannschaft/app/seal/controller/SealController.java) | 77 |
| PUT | `/api/v1/users/{_}/vendors/{_}` | `VendorController#updateVendor` (backend/src/main/java/com/mannschaft/app/property/controller/VendorController.java) | 140 |
| PUT | `/api/v1/users/{_}/workflow-requests/{_}` | `WorkflowRequestController#updateRequest` (backend/src/main/java/com/mannschaft/app/workflow/controller/WorkflowRequestController.java) | 91 |
| PUT | `/api/v1/users/{_}/workflow-templates/{_}` | `WorkflowTemplateController#updateTemplate` (backend/src/main/java/com/mannschaft/app/workflow/controller/WorkflowTemplateController.java) | 90 |

#### /api/v1/system-admin/* (28 件)

| メソッド | パス | Controller | 行 |
|---|---|---|---|
| DELETE | `/api/v1/system-admin/safety-checks/presets/{_}` | `SafetyAdminController#deletePreset` (backend/src/main/java/com/mannschaft/app/safetycheck/controller/SafetyAdminController.java) | 84 |
| DELETE | `/api/v1/system-admin/tournament-presets/{_}` | `SystemPresetController#deletePreset` (backend/src/main/java/com/mannschaft/app/tournament/controller/SystemPresetController.java) | 71 |
| GET | `/api/v1/system-admin/dashboard/organizations` | `SystemAdminDashboardController#getOrganizations` (backend/src/main/java/com/mannschaft/app/admin/controller/SystemAdminDashboardController.java) | 54 |
| GET | `/api/v1/system-admin/dashboard/teams` | `SystemAdminDashboardController#getTeams` (backend/src/main/java/com/mannschaft/app/admin/controller/SystemAdminDashboardController.java) | 65 |
| GET | `/api/v1/system-admin/dashboard/users` | `SystemAdminDashboardController#getUsers` (backend/src/main/java/com/mannschaft/app/admin/controller/SystemAdminDashboardController.java) | 76 |
| GET | `/api/v1/system-admin/error-reports/config` | `SystemAdminErrorReportController#config` (backend/src/main/java/com/mannschaft/app/errorreport/controller/SystemAdminErrorReportController.java) | 290 |
| GET | `/api/v1/system-admin/error-reports/kanban` | `SystemAdminErrorReportController#kanban` (backend/src/main/java/com/mannschaft/app/errorreport/controller/SystemAdminErrorReportController.java) | 157 |
| GET | `/api/v1/system-admin/maintenance-schedules/{_}` | `SystemAdminMaintenanceController#getSchedule` (backend/src/main/java/com/mannschaft/app/admin/controller/SystemAdminMaintenanceController.java) | 53 |
| GET | `/api/v1/system-admin/moderation/settings` | `SystemAdminModerationController#getSettings` (backend/src/main/java/com/mannschaft/app/moderation/controller/SystemAdminModerationController.java) | 271 |
| GET | `/api/v1/system-admin/modules/{_}` | `SystemAdminModuleController#getModule` (backend/src/main/java/com/mannschaft/app/template/controller/SystemAdminModuleController.java) | 47 |
| GET | `/api/v1/system-admin/promotion-billing` | `SystemAdminBillingController#list` (backend/src/main/java/com/mannschaft/app/promotion/controller/SystemAdminBillingController.java) | 26 |
| GET | `/api/v1/system-admin/safety-checks/presets` | `SafetyAdminController#listPresets` (backend/src/main/java/com/mannschaft/app/safetycheck/controller/SafetyAdminController.java) | 48 |
| GET | `/api/v1/system-admin/storage-migration/status` | `StorageMigrationAdminController#getStatus` (backend/src/main/java/com/mannschaft/app/admin/controller/StorageMigrationAdminController.java) | 47 |
| GET | `/api/v1/system-admin/tournament-presets/{_}` | `SystemPresetController#getPreset` (backend/src/main/java/com/mannschaft/app/tournament/controller/SystemPresetController.java) | 57 |
| PATCH | `/api/v1/system-admin/dashboard/organizations/{_}/freeze` | `SystemAdminDashboardController#freezeOrganization` (backend/src/main/java/com/mannschaft/app/admin/controller/SystemAdminDashboardController.java) | 87 |
| PATCH | `/api/v1/system-admin/dashboard/organizations/{_}/unfreeze` | `SystemAdminDashboardController#unfreezeOrganization` (backend/src/main/java/com/mannschaft/app/admin/controller/SystemAdminDashboardController.java) | 98 |
| PATCH | `/api/v1/system-admin/maintenance-schedules/{_}/complete` | `SystemAdminMaintenanceController#completeSchedule` (backend/src/main/java/com/mannschaft/app/admin/controller/SystemAdminMaintenanceController.java) | 112 |
| PATCH | `/api/v1/system-admin/modules/{_}/level-availability` | `SystemAdminModuleController#updateLevelAvailability` (backend/src/main/java/com/mannschaft/app/template/controller/SystemAdminModuleController.java) | 57 |
| PATCH | `/api/v1/system-admin/safety-checks/presets/{_}` | `SafetyAdminController#updatePreset` (backend/src/main/java/com/mannschaft/app/safetycheck/controller/SafetyAdminController.java) | 71 |
| PATCH | `/api/v1/system-admin/safety-checks/templates/{_}` | `SafetyAdminController#updateTemplate` (backend/src/main/java/com/mannschaft/app/safetycheck/controller/SafetyAdminController.java) | 120 |
| PATCH | `/api/v1/system-admin/templates/{_}` | `SystemAdminTemplateController#updateTemplate` (backend/src/main/java/com/mannschaft/app/template/controller/SystemAdminTemplateController.java) | 50 |
| PATCH | `/api/v1/system-admin/tournament-presets/{_}` | `SystemPresetController#updatePreset` (backend/src/main/java/com/mannschaft/app/tournament/controller/SystemPresetController.java) | 63 |
| PATCH | `/api/v1/system-admin/warnings/re-reviews/{_}/escalate` | `SystemAdminModerationController#escalateReReview` (backend/src/main/java/com/mannschaft/app/moderation/controller/SystemAdminModerationController.java) | 213 |
| POST | `/api/v1/system-admin/maintenance-schedules/{_}/activate` | `SystemAdminMaintenanceController#activateSchedule` (backend/src/main/java/com/mannschaft/app/admin/controller/SystemAdminMaintenanceController.java) | 101 |
| POST | `/api/v1/system-admin/safety-checks/presets` | `SafetyAdminController#createPreset` (backend/src/main/java/com/mannschaft/app/safetycheck/controller/SafetyAdminController.java) | 59 |
| POST | `/api/v1/system-admin/storage-migration/run` | `StorageMigrationAdminController#runMigration` (backend/src/main/java/com/mannschaft/app/admin/controller/StorageMigrationAdminController.java) | 63 |
| PUT | `/api/v1/system-admin/maintenance-schedules/{_}` | `SystemAdminMaintenanceController#updateSchedule` (backend/src/main/java/com/mannschaft/app/admin/controller/SystemAdminMaintenanceController.java) | 77 |
| PUT | `/api/v1/system-admin/moderation/settings/{_}` | `SystemAdminModerationController#updateSetting` (backend/src/main/java/com/mannschaft/app/moderation/controller/SystemAdminModerationController.java) | 282 |

#### /api/v1/me/* (20 件)

| メソッド | パス | Controller | 行 |
|---|---|---|---|
| DELETE | `/api/v1/me/scope-folders/{_}` | `MyScopeFolderController#deleteFolder` (backend/src/main/java/com/mannschaft/app/scopefolder/controller/MyScopeFolderController.java) | 153 |
| DELETE | `/api/v1/me/scope-folders/{_}/items/{_}` | `MyScopeFolderController#removeItem` (backend/src/main/java/com/mannschaft/app/scopefolder/controller/MyScopeFolderController.java) | 180 |
| DELETE | `/api/v1/me/village-pins/{_}` | `VillagePinController#unpin` (backend/src/main/java/com/mannschaft/app/village/controller/VillagePinController.java) | 73 |
| GET | `/api/v1/me/applications` | `JobApplicationController#listMyApplications` (backend/src/main/java/com/mannschaft/app/jobmatching/controller/JobApplicationController.java) | 121 |
| GET | `/api/v1/me/attendance/notices` | `FamilyAttendanceNoticeController#getMyNotices` (backend/src/main/java/com/mannschaft/app/school/controller/FamilyAttendanceNoticeController.java) | 105 |
| GET | `/api/v1/me/circulations/created` | `MyCirculationController#listCreatedDocuments` (backend/src/main/java/com/mannschaft/app/circulation/controller/MyCirculationController.java) | 33 |
| GET | `/api/v1/me/confirmable-notifications/pending` | `ConfirmableNotificationRecipientController#listPending` (backend/src/main/java/com/mannschaft/app/notification/confirmable/controller/ConfirmableNotificationRecipientController.java) | 38 |
| GET | `/api/v1/me/contracts` | `JobContractController#listMyContracts` (backend/src/main/java/com/mannschaft/app/jobmatching/controller/JobContractController.java) | 68 |
| GET | `/api/v1/me/favorites/{_}` | `FavoriteController#getFavorite` (backend/src/main/java/com/mannschaft/app/favorite/controller/FavoriteController.java) | 146 |
| GET | `/api/v1/me/pilgrimage/history` | `VillagePilgrimageController#history` (backend/src/main/java/com/mannschaft/app/village/controller/VillagePilgrimageController.java) | 74 |
| GET | `/api/v1/me/pilgrimage/today` | `VillagePilgrimageController#getToday` (backend/src/main/java/com/mannschaft/app/village/controller/VillagePilgrimageController.java) | 52 |
| GET | `/api/v1/me/timetable-slot-notes/{_}/attachments` | `TimetableSlotUserNoteAttachmentController#list` (backend/src/main/java/com/mannschaft/app/timetable/notes/controller/TimetableSlotUserNoteAttachmentController.java) | 40 |
| GET | `/api/v1/me/village-creation-requests` | `VillageCreationRequestController#listMine` (backend/src/main/java/com/mannschaft/app/village/controller/VillageCreationRequestController.java) | 64 |
| PATCH | `/api/v1/me/favorites/reorder` | `FavoriteController#reorderFavorites` (backend/src/main/java/com/mannschaft/app/favorite/controller/FavoriteController.java) | 185 |
| PATCH | `/api/v1/me/village-pins/order` | `VillagePinController#reorder` (backend/src/main/java/com/mannschaft/app/village/controller/VillagePinController.java) | 87 |
| POST | `/api/v1/me/pilgrimage/{_}/visit` | `VillagePilgrimageController#recordVisit` (backend/src/main/java/com/mannschaft/app/village/controller/VillagePilgrimageController.java) | 63 |
| POST | `/api/v1/me/scope-folders/{_}/items` | `MyScopeFolderController#addItem` (backend/src/main/java/com/mannschaft/app/scopefolder/controller/MyScopeFolderController.java) | 166 |
| POST | `/api/v1/me/village-pins/{_}` | `VillagePinController#pin` (backend/src/main/java/com/mannschaft/app/village/controller/VillagePinController.java) | 59 |
| PUT | `/api/v1/me/scope-folders/reorder` | `MyScopeFolderController#reorderFolders` (backend/src/main/java/com/mannschaft/app/scopefolder/controller/MyScopeFolderController.java) | 126 |
| PUT | `/api/v1/me/scope-folders/{_}` | `MyScopeFolderController#updateFolder` (backend/src/main/java/com/mannschaft/app/scopefolder/controller/MyScopeFolderController.java) | 140 |

#### /api/v1/shifts/* (19 件)

| メソッド | パス | Controller | 行 |
|---|---|---|---|
| DELETE | `/api/v1/shifts/availability` | `ShiftAvailabilityController#deleteAvailabilityDefaults` (backend/src/main/java/com/mannschaft/app/shift/controller/ShiftAvailabilityController.java) | 72 |
| DELETE | `/api/v1/shifts/teams/{_}/work-constraints/default` | `MemberWorkConstraintController#deleteTeamDefault` (backend/src/main/java/com/mannschaft/app/shift/controller/MemberWorkConstraintController.java) | 136 |
| DELETE | `/api/v1/shifts/teams/{_}/work-constraints/members/{_}` | `MemberWorkConstraintController#deleteConstraint` (backend/src/main/java/com/mannschaft/app/shift/controller/MemberWorkConstraintController.java) | 92 |
| GET | `/api/v1/shifts/my/requests` | `ShiftRequestController#listMyRequests` (backend/src/main/java/com/mannschaft/app/shift/controller/ShiftRequestController.java) | 55 |
| GET | `/api/v1/shifts/requests` | `ShiftRequestController#listRequests` (backend/src/main/java/com/mannschaft/app/shift/controller/ShiftRequestController.java) | 43 |
| GET | `/api/v1/shifts/requests/summary` | `ShiftRequestController#getRequestSummary` (backend/src/main/java/com/mannschaft/app/shift/controller/ShiftRequestController.java) | 103 |
| GET | `/api/v1/shifts/teams/{_}/work-constraints/default` | `MemberWorkConstraintController#getTeamDefault` (backend/src/main/java/com/mannschaft/app/shift/controller/MemberWorkConstraintController.java) | 109 |
| GET | `/api/v1/shifts/teams/{_}/work-constraints/members/{_}` | `MemberWorkConstraintController#getConstraint` (backend/src/main/java/com/mannschaft/app/shift/controller/MemberWorkConstraintController.java) | 63 |
| PATCH | `/api/v1/shifts/positions/{_}` | `ShiftPositionController#updatePosition` (backend/src/main/java/com/mannschaft/app/shift/controller/ShiftPositionController.java) | 65 |
| PATCH | `/api/v1/shifts/requests/{_}` | `ShiftRequestController#updateRequest` (backend/src/main/java/com/mannschaft/app/shift/controller/ShiftRequestController.java) | 78 |
| PATCH | `/api/v1/shifts/schedules/{_}` | `ShiftScheduleController#updateSchedule` (backend/src/main/java/com/mannschaft/app/shift/controller/ShiftScheduleController.java) | 88 |
| PATCH | `/api/v1/shifts/slots/{_}` | `ShiftSlotController#updateSlot` (backend/src/main/java/com/mannschaft/app/shift/controller/ShiftSlotController.java) | 79 |
| POST | `/api/v1/shifts/hourly-rate` | `ShiftAvailabilityController#createHourlyRate` (backend/src/main/java/com/mannschaft/app/shift/controller/ShiftAvailabilityController.java) | 84 |
| POST | `/api/v1/shifts/schedules/{_}/duplicate` | `ShiftScheduleController#duplicateSchedule` (backend/src/main/java/com/mannschaft/app/shift/controller/ShiftScheduleController.java) | 126 |
| POST | `/api/v1/shifts/schedules/{_}/transition` | `ShiftScheduleController#transitionStatus` (backend/src/main/java/com/mannschaft/app/shift/controller/ShiftScheduleController.java) | 113 |
| POST | `/api/v1/shifts/swap-requests/{_}/accept` | `ShiftSwapController#acceptSwapRequest` (backend/src/main/java/com/mannschaft/app/shift/controller/ShiftSwapController.java) | 65 |
| POST | `/api/v1/shifts/swap-requests/{_}/resolve` | `ShiftSwapController#resolveSwapRequest` (backend/src/main/java/com/mannschaft/app/shift/controller/ShiftSwapController.java) | 77 |
| PUT | `/api/v1/shifts/teams/{_}/work-constraints/default` | `MemberWorkConstraintController#upsertTeamDefault` (backend/src/main/java/com/mannschaft/app/shift/controller/MemberWorkConstraintController.java) | 122 |
| PUT | `/api/v1/shifts/teams/{_}/work-constraints/members/{_}` | `MemberWorkConstraintController#upsertConstraint` (backend/src/main/java/com/mannschaft/app/shift/controller/MemberWorkConstraintController.java) | 77 |

#### /api/v1/todos/* (12 件)

| メソッド | パス | Controller | 行 |
|---|---|---|---|
| DELETE | `/api/v1/todos/{_}/link-schedule` | `PersonalTodoController#unlinkSchedule` (backend/src/main/java/com/mannschaft/app/todo/controller/PersonalTodoController.java) | 160 |
| DELETE | `/api/v1/todos/{_}/memo` | `PersonalTodoController#deletePersonalMemo` (backend/src/main/java/com/mannschaft/app/todo/controller/PersonalTodoController.java) | 240 |
| GET | `/api/v1/todos/gantt` | `PersonalTodoController#getGanttTodos` (backend/src/main/java/com/mannschaft/app/todo/controller/PersonalTodoController.java) | 173 |
| GET | `/api/v1/todos/{_}` | `PersonalTodoController#getPersonalTodo` (backend/src/main/java/com/mannschaft/app/todo/controller/PersonalTodoController.java) | 88 |
| GET | `/api/v1/todos/{_}/children` | `PersonalTodoController#getChildTodos` (backend/src/main/java/com/mannschaft/app/todo/controller/PersonalTodoController.java) | 133 |
| GET | `/api/v1/todos/{_}/memo` | `PersonalTodoController#getPersonalMemo` (backend/src/main/java/com/mannschaft/app/todo/controller/PersonalTodoController.java) | 218 |
| PATCH | `/api/v1/todos/{_}` | `PersonalTodoController#patchTodo` (backend/src/main/java/com/mannschaft/app/todo/controller/PersonalTodoController.java) | 110 |
| PATCH | `/api/v1/todos/{_}/progress` | `PersonalTodoController#setProgressRate` (backend/src/main/java/com/mannschaft/app/todo/controller/PersonalTodoController.java) | 192 |
| PATCH | `/api/v1/todos/{_}/progress-mode` | `PersonalTodoController#setProgressMode` (backend/src/main/java/com/mannschaft/app/todo/controller/PersonalTodoController.java) | 204 |
| POST | `/api/v1/todos/{_}/link-schedule` | `PersonalTodoController#linkSchedule` (backend/src/main/java/com/mannschaft/app/todo/controller/PersonalTodoController.java) | 146 |
| PUT | `/api/v1/todos/{_}` | `PersonalTodoController#updatePersonalTodo` (backend/src/main/java/com/mannschaft/app/todo/controller/PersonalTodoController.java) | 98 |
| PUT | `/api/v1/todos/{_}/memo` | `PersonalTodoController#upsertPersonalMemo` (backend/src/main/java/com/mannschaft/app/todo/controller/PersonalTodoController.java) | 228 |

#### /api/v1/timeline/* (11 件)

| メソッド | パス | Controller | 行 |
|---|---|---|---|
| DELETE | `/api/v1/timeline/bookmarks/{_}` | `TimelineBookmarkController#removeBookmark` (backend/src/main/java/com/mannschaft/app/timeline/controller/TimelineBookmarkController.java) | 48 |
| DELETE | `/api/v1/timeline/posts/{_}` | `TimelinePostController#deletePost` (backend/src/main/java/com/mannschaft/app/timeline/controller/TimelinePostController.java) | 79 |
| GET | `/api/v1/timeline/feed` | `TimelineFeedController#getFeed` (backend/src/main/java/com/mannschaft/app/timeline/controller/TimelineFeedController.java) | 32 |
| GET | `/api/v1/timeline/pinned` | `TimelineFeedController#getPinnedPosts` (backend/src/main/java/com/mannschaft/app/timeline/controller/TimelineFeedController.java) | 59 |
| GET | `/api/v1/timeline/posts/{_}` | `TimelinePostController#getPost` (backend/src/main/java/com/mannschaft/app/timeline/controller/TimelinePostController.java) | 55 |
| GET | `/api/v1/timeline/posts/{_}/replies` | `TimelinePostController#getReplies` (backend/src/main/java/com/mannschaft/app/timeline/controller/TimelinePostController.java) | 90 |
| GET | `/api/v1/timeline/users/{_}/posts` | `TimelineFeedController#getUserPosts` (backend/src/main/java/com/mannschaft/app/timeline/controller/TimelineFeedController.java) | 46 |
| PATCH | `/api/v1/timeline/posts/{_}` | `TimelinePostController#updatePost` (backend/src/main/java/com/mannschaft/app/timeline/controller/TimelinePostController.java) | 66 |
| POST | `/api/v1/timeline/bookmarks/{_}` | `TimelineBookmarkController#addBookmark` (backend/src/main/java/com/mannschaft/app/timeline/controller/TimelineBookmarkController.java) | 37 |
| POST | `/api/v1/timeline/posts/{_}/pin` | `TimelinePostController#togglePin` (backend/src/main/java/com/mannschaft/app/timeline/controller/TimelinePostController.java) | 103 |
| POST | `/api/v1/timeline/posts/{_}/poll/vote` | `TimelinePollController#vote` (backend/src/main/java/com/mannschaft/app/timeline/controller/TimelinePollController.java) | 35 |

#### /api/v1/chat/* (10 件)

| メソッド | パス | Controller | 行 |
|---|---|---|---|
| DELETE | `/api/v1/chat/channels/{_}/archive` | `ChatChannelController#unarchiveChannel` (backend/src/main/java/com/mannschaft/app/chat/controller/ChatChannelController.java) | 124 |
| GET | `/api/v1/chat/files/{_}/download-url` | `ChatUploadController#generateDownloadUrl` (backend/src/main/java/com/mannschaft/app/chat/controller/ChatUploadController.java) | 83 |
| PATCH | `/api/v1/chat/channels/{_}` | `ChatChannelController#updateChannel` (backend/src/main/java/com/mannschaft/app/chat/controller/ChatChannelController.java) | 89 |
| PATCH | `/api/v1/chat/channels/{_}/settings` | `ChatChannelController#updateSettings` (backend/src/main/java/com/mannschaft/app/chat/controller/ChatChannelController.java) | 248 |
| PATCH | `/api/v1/chat/messages/{_}` | `ChatMessageController#editMessage` (backend/src/main/java/com/mannschaft/app/chat/controller/ChatMessageController.java) | 80 |
| POST | `/api/v1/chat/channels/conversations` | `ChatChannelController#startConversation` (backend/src/main/java/com/mannschaft/app/chat/controller/ChatChannelController.java) | 190 |
| POST | `/api/v1/chat/channels/{_}/archive` | `ChatChannelController#archiveChannel` (backend/src/main/java/com/mannschaft/app/chat/controller/ChatChannelController.java) | 113 |
| POST | `/api/v1/chat/files/upload-url` | `ChatUploadController#generateUploadUrl` (backend/src/main/java/com/mannschaft/app/chat/controller/ChatUploadController.java) | 52 |
| POST | `/api/v1/chat/messages/{_}/migrate-to-board` | `ChatBoardMigrationController#migrateToBoard` (backend/src/main/java/com/mannschaft/app/chat/controller/ChatBoardMigrationController.java) | 41 |
| POST | `/api/v1/chat/messages/{_}/pin` | `ChatMessageController#togglePin` (backend/src/main/java/com/mannschaft/app/chat/controller/ChatMessageController.java) | 118 |

#### /api/v1/admin/* (8 件)

| メソッド | パス | Controller | 行 |
|---|---|---|---|
| DELETE | `/api/v1/admin/point-cards/synonyms/{_}` | `AdminPointCardSynonymController#delete` (backend/src/main/java/com/mannschaft/app/pointcard/controller/AdminPointCardSynonymController.java) | 98 |
| GET | `/api/v1/admin/village-creation-requests` | `VillageCreationRequestController#listForAdmin` (backend/src/main/java/com/mannschaft/app/village/controller/VillageCreationRequestController.java) | 76 |
| PATCH | `/api/v1/admin/point-cards/synonyms/{_}` | `AdminPointCardSynonymController#update` (backend/src/main/java/com/mannschaft/app/pointcard/controller/AdminPointCardSynonymController.java) | 85 |
| POST | `/api/v1/admin/batch/attendance/run-daily-evaluation` | `AttendanceBatchController#runDailyEvaluation` (backend/src/main/java/com/mannschaft/app/school/controller/AttendanceBatchController.java) | 37 |
| POST | `/api/v1/admin/batch/attendance/send-weekly-digest` | `AttendanceBatchController#sendWeeklyDigest` (backend/src/main/java/com/mannschaft/app/school/controller/AttendanceBatchController.java) | 52 |
| POST | `/api/v1/admin/village-creation-requests/{_}/approve` | `VillageCreationRequestController#approve` (backend/src/main/java/com/mannschaft/app/village/controller/VillageCreationRequestController.java) | 86 |
| POST | `/api/v1/admin/village-creation-requests/{_}/reject` | `VillageCreationRequestController#reject` (backend/src/main/java/com/mannschaft/app/village/controller/VillageCreationRequestController.java) | 97 |
| POST | `/api/v1/admin/village-creation-requests/{_}/withdraw` | `VillageCreationRequestController#withdraw` (backend/src/main/java/com/mannschaft/app/village/controller/VillageCreationRequestController.java) | 108 |

#### /api/v1/events/* (8 件)

| メソッド | パス | Controller | 行 |
|---|---|---|---|
| GET | `/api/v1/events/{_}/checkins/count` | `EventCheckinController#getCheckinCount` (backend/src/main/java/com/mannschaft/app/event/controller/EventCheckinController.java) | 81 |
| GET | `/api/v1/events/{_}/registrations/{_}` | `EventRegistrationController#getRegistration` (backend/src/main/java/com/mannschaft/app/event/controller/EventRegistrationController.java) | 59 |
| GET | `/api/v1/events/{_}/ticket-types/{_}` | `EventTicketTypeController#getTicketType` (backend/src/main/java/com/mannschaft/app/event/controller/EventTicketTypeController.java) | 50 |
| GET | `/api/v1/events/{_}/tickets/by-qr` | `EventTicketController#getTicketByQrToken` (backend/src/main/java/com/mannschaft/app/event/controller/EventTicketController.java) | 63 |
| POST | `/api/v1/events/{_}/invite-tokens/{_}/deactivate` | `EventInviteTokenController#deactivateToken` (backend/src/main/java/com/mannschaft/app/event/controller/EventInviteTokenController.java) | 64 |
| POST | `/api/v1/events/{_}/registrations/{_}/approve` | `EventRegistrationController#approveRegistration` (backend/src/main/java/com/mannschaft/app/event/controller/EventRegistrationController.java) | 99 |
| POST | `/api/v1/events/{_}/registrations/{_}/reject` | `EventRegistrationController#rejectRegistration` (backend/src/main/java/com/mannschaft/app/event/controller/EventRegistrationController.java) | 113 |
| PUT | `/api/v1/events/{_}/timetable/reorder` | `EventTimetableController#reorderTimetableItems` (backend/src/main/java/com/mannschaft/app/event/controller/EventTimetableController.java) | 93 |

#### /api/v1/public/* (8 件)

| メソッド | パス | Controller | 行 |
|---|---|---|---|
| GET | `/api/v1/public/activities/{_}` | `ActivityPublicController#getPublicActivityById` (backend/src/main/java/com/mannschaft/app/activity/controller/ActivityPublicController.java) | 47 |
| GET | `/api/v1/public/organizations/{_}/tournaments/{_}` | `PublicTournamentController#getPublicTournament` (backend/src/main/java/com/mannschaft/app/tournament/controller/PublicTournamentController.java) | 54 |
| GET | `/api/v1/public/organizations/{_}/tournaments/{_}/bracket` | `PublicTournamentController#getPublicBracket` (backend/src/main/java/com/mannschaft/app/tournament/controller/PublicTournamentController.java) | 82 |
| GET | `/api/v1/public/organizations/{_}/tournaments/{_}/divisions/{_}/matrix` | `PublicTournamentController#getPublicMatrix` (backend/src/main/java/com/mannschaft/app/tournament/controller/PublicTournamentController.java) | 91 |
| GET | `/api/v1/public/organizations/{_}/tournaments/{_}/divisions/{_}/standings` | `PublicTournamentController#getPublicStandings` (backend/src/main/java/com/mannschaft/app/tournament/controller/PublicTournamentController.java) | 61 |
| GET | `/api/v1/public/organizations/{_}/tournaments/{_}/rankings/{_}` | `PublicTournamentController#getPublicRankings` (backend/src/main/java/com/mannschaft/app/tournament/controller/PublicTournamentController.java) | 69 |
| GET | `/api/v1/public/stats` | `PublicStatsController#getPublicStats` (backend/src/main/java/com/mannschaft/app/landing/controller/PublicStatsController.java) | 25 |
| POST | `/api/v1/public/confirm/{_}` | `PublicConfirmationController#confirmByToken` (backend/src/main/java/com/mannschaft/app/notification/confirmable/controller/PublicConfirmationController.java) | 40 |

#### /api/v1/safety-checks/* (8 件)

| メソッド | パス | Controller | 行 |
|---|---|---|---|
| GET | `/api/v1/safety-checks/history` | `SafetyCheckController#getHistory` (backend/src/main/java/com/mannschaft/app/safetycheck/controller/SafetyCheckController.java) | 157 |
| GET | `/api/v1/safety-checks/presets` | `SafetyCheckController#listPresets` (backend/src/main/java/com/mannschaft/app/safetycheck/controller/SafetyCheckController.java) | 174 |
| GET | `/api/v1/safety-checks/templates/{_}` | `SafetyTemplateController#getTemplate` (backend/src/main/java/com/mannschaft/app/safetycheck/controller/SafetyTemplateController.java) | 54 |
| GET | `/api/v1/safety-checks/{_}/unresponded` | `SafetyCheckController#getUnrespondedUsers` (backend/src/main/java/com/mannschaft/app/safetycheck/controller/SafetyCheckController.java) | 145 |
| PATCH | `/api/v1/safety-checks/followups/{_}` | `SafetyFollowupController#updateFollowup` (backend/src/main/java/com/mannschaft/app/safetycheck/controller/SafetyFollowupController.java) | 40 |
| PATCH | `/api/v1/safety-checks/templates/{_}` | `SafetyTemplateController#updateTemplate` (backend/src/main/java/com/mannschaft/app/safetycheck/controller/SafetyTemplateController.java) | 78 |
| POST | `/api/v1/safety-checks/{_}/close` | `SafetyCheckController#closeSafetyCheck` (backend/src/main/java/com/mannschaft/app/safetycheck/controller/SafetyCheckController.java) | 94 |
| POST | `/api/v1/safety-checks/{_}/respond/bulk` | `SafetyCheckController#bulkRespond` (backend/src/main/java/com/mannschaft/app/safetycheck/controller/SafetyCheckController.java) | 120 |

#### /api/v1/social/* (8 件)

| メソッド | パス | Controller | 行 |
|---|---|---|---|
| DELETE | `/api/v1/social/profiles/me` | `SocialProfileController#deactivateProfile` (backend/src/main/java/com/mannschaft/app/social/controller/SocialProfileController.java) | 98 |
| GET | `/api/v1/social/follows/check` | `FollowController#isFollowing` (backend/src/main/java/com/mannschaft/app/social/controller/FollowController.java) | 89 |
| GET | `/api/v1/social/follows/followers` | `FollowController#getFollowers` (backend/src/main/java/com/mannschaft/app/social/controller/FollowController.java) | 77 |
| GET | `/api/v1/social/follows/following` | `FollowController#getFollowing` (backend/src/main/java/com/mannschaft/app/social/controller/FollowController.java) | 65 |
| GET | `/api/v1/social/profiles/handle/{_}` | `SocialProfileController#getProfileByHandle` (backend/src/main/java/com/mannschaft/app/social/controller/SocialProfileController.java) | 74 |
| GET | `/api/v1/social/profiles/me` | `SocialProfileController#getMyProfile` (backend/src/main/java/com/mannschaft/app/social/controller/SocialProfileController.java) | 51 |
| GET | `/api/v1/social/profiles/users/{_}` | `SocialProfileController#getProfileByUserId` (backend/src/main/java/com/mannschaft/app/social/controller/SocialProfileController.java) | 86 |
| PATCH | `/api/v1/social/profiles/me` | `SocialProfileController#updateProfile` (backend/src/main/java/com/mannschaft/app/social/controller/SocialProfileController.java) | 62 |

#### /api/v1/files/* (7 件)

| メソッド | パス | Controller | 行 |
|---|---|---|---|
| DELETE | `/api/v1/files/{_}/comments/{_}` | `FileCommentController#deleteComment` (backend/src/main/java/com/mannschaft/app/filesharing/controller/FileCommentController.java) | 80 |
| DELETE | `/api/v1/files/{_}/links/{_}` | `FileLinkController#deleteLink` (backend/src/main/java/com/mannschaft/app/filesharing/controller/FileLinkController.java) | 66 |
| GET | `/api/v1/files/{_}/stars/me` | `FileStarController#listMyStars` (backend/src/main/java/com/mannschaft/app/filesharing/controller/FileStarController.java) | 60 |
| GET | `/api/v1/files/{_}/versions/{_}` | `FileVersionController#getVersion` (backend/src/main/java/com/mannschaft/app/filesharing/controller/FileVersionController.java) | 50 |
| PATCH | `/api/v1/files/{_}` | `SharedFileController#updateFile` (backend/src/main/java/com/mannschaft/app/filesharing/controller/SharedFileController.java) | 85 |
| PATCH | `/api/v1/files/{_}/comments/{_}` | `FileCommentController#updateComment` (backend/src/main/java/com/mannschaft/app/filesharing/controller/FileCommentController.java) | 66 |
| POST | `/api/v1/files/presign-upload` | `SharedFileController#presignUpload` (backend/src/main/java/com/mannschaft/app/filesharing/controller/SharedFileController.java) | 114 |

#### /api/v1/contracts/* (6 件)

| メソッド | パス | Controller | 行 |
|---|---|---|---|
| GET | `/api/v1/contracts/{_}` | `JobContractController#getContract` (backend/src/main/java/com/mannschaft/app/jobmatching/controller/JobContractController.java) | 83 |
| GET | `/api/v1/contracts/{_}/qr-tokens/current` | `JobQrTokenController#getCurrent` (backend/src/main/java/com/mannschaft/app/jobmatching/controller/JobQrTokenController.java) | 109 |
| POST | `/api/v1/contracts/{_}/approve-completion` | `JobContractController#approveCompletion` (backend/src/main/java/com/mannschaft/app/jobmatching/controller/JobContractController.java) | 113 |
| POST | `/api/v1/contracts/{_}/cancel` | `JobContractController#cancelContract` (backend/src/main/java/com/mannschaft/app/jobmatching/controller/JobContractController.java) | 142 |
| POST | `/api/v1/contracts/{_}/reject-completion` | `JobContractController#rejectCompletion` (backend/src/main/java/com/mannschaft/app/jobmatching/controller/JobContractController.java) | 125 |
| POST | `/api/v1/contracts/{_}/report-completion` | `JobContractController#reportCompletion` (backend/src/main/java/com/mannschaft/app/jobmatching/controller/JobContractController.java) | 98 |

#### /api/v1/workflow-requests/* (6 件)

| メソッド | パス | Controller | 行 |
|---|---|---|---|
| DELETE | `/api/v1/workflow-requests/{_}/comments/{_}` | `WorkflowCommentController#deleteComment` (backend/src/main/java/com/mannschaft/app/workflow/controller/WorkflowCommentController.java) | 84 |
| GET | `/api/v1/workflow-requests/{_}/attachments` | `WorkflowCommentController#listAttachments` (backend/src/main/java/com/mannschaft/app/workflow/controller/WorkflowCommentController.java) | 97 |
| GET | `/api/v1/workflow-requests/{_}/comments` | `WorkflowCommentController#listComments` (backend/src/main/java/com/mannschaft/app/workflow/controller/WorkflowCommentController.java) | 45 |
| POST | `/api/v1/workflow-requests/{_}/comments` | `WorkflowCommentController#createComment` (backend/src/main/java/com/mannschaft/app/workflow/controller/WorkflowCommentController.java) | 57 |
| POST | `/api/v1/workflow-requests/{_}/decide` | `WorkflowApprovalController#decide` (backend/src/main/java/com/mannschaft/app/workflow/controller/WorkflowApprovalController.java) | 34 |
| PUT | `/api/v1/workflow-requests/{_}/comments/{_}` | `WorkflowCommentController#updateComment` (backend/src/main/java/com/mannschaft/app/workflow/controller/WorkflowCommentController.java) | 70 |

#### /api/v1/corkboards/* (5 件)

| メソッド | パス | Controller | 行 |
|---|---|---|---|
| DELETE | `/api/v1/corkboards/{_}/groups/{_}` | `CorkboardGroupController#deleteGroup` (backend/src/main/java/com/mannschaft/app/corkboard/controller/CorkboardGroupController.java) | 64 |
| DELETE | `/api/v1/corkboards/{_}/groups/{_}/cards/{_}` | `CorkboardGroupController#removeCardFromGroup` (backend/src/main/java/com/mannschaft/app/corkboard/controller/CorkboardGroupController.java) | 87 |
| PATCH | `/api/v1/corkboards/{_}/cards/{_}/archive` | `CorkboardCardController#archiveCard` (backend/src/main/java/com/mannschaft/app/corkboard/controller/CorkboardCardController.java) | 83 |
| POST | `/api/v1/corkboards/{_}/groups/{_}/cards/{_}` | `CorkboardGroupController#addCardToGroup` (backend/src/main/java/com/mannschaft/app/corkboard/controller/CorkboardGroupController.java) | 75 |
| PUT | `/api/v1/corkboards/{_}/groups/{_}` | `CorkboardGroupController#updateGroup` (backend/src/main/java/com/mannschaft/app/corkboard/controller/CorkboardGroupController.java) | 50 |

#### /api/v1/recruitment-listings/* (5 件)

| メソッド | パス | Controller | 行 |
|---|---|---|---|
| GET | `/api/v1/recruitment-listings/search` | `RecruitmentListingController#searchListings` (backend/src/main/java/com/mannschaft/app/recruitment/controller/RecruitmentListingController.java) | 61 |
| GET | `/api/v1/recruitment-listings/{_}/distribution-targets` | `RecruitmentListingController#getDistributionTargets` (backend/src/main/java/com/mannschaft/app/recruitment/controller/RecruitmentListingController.java) | 144 |
| PATCH | `/api/v1/recruitment-listings/{_}/participants/{_}/attend` | `RecruitmentApplicationController#markAttended` (backend/src/main/java/com/mannschaft/app/recruitment/controller/RecruitmentApplicationController.java) | 72 |
| POST | `/api/v1/recruitment-listings/{_}/participants/{_}/confirm` | `RecruitmentListingController#confirmApplication` (backend/src/main/java/com/mannschaft/app/recruitment/controller/RecruitmentListingController.java) | 165 |
| PUT | `/api/v1/recruitment-listings/{_}/distribution-targets` | `RecruitmentListingController#setDistributionTargets` (backend/src/main/java/com/mannschaft/app/recruitment/controller/RecruitmentListingController.java) | 152 |

#### /api/v1/shift-budget/* (5 件)

| メソッド | パス | Controller | 行 |
|---|---|---|---|
| DELETE | `/api/v1/shift-budget/allocations/{_}` | `ShiftBudgetAllocationController#deleteAllocation` (backend/src/main/java/com/mannschaft/app/shiftbudget/controller/ShiftBudgetAllocationController.java) | 96 |
| GET | `/api/v1/shift-budget/allocations/{_}` | `ShiftBudgetAllocationController#getAllocation` (backend/src/main/java/com/mannschaft/app/shiftbudget/controller/ShiftBudgetAllocationController.java) | 77 |
| POST | `/api/v1/shift-budget/failed-events/{_}/resolve` | `ShiftBudgetFailedEventController#resolve` (backend/src/main/java/com/mannschaft/app/shiftbudget/controller/ShiftBudgetFailedEventController.java) | 62 |
| POST | `/api/v1/shift-budget/failed-events/{_}/retry` | `ShiftBudgetFailedEventController#retry` (backend/src/main/java/com/mannschaft/app/shiftbudget/controller/ShiftBudgetFailedEventController.java) | 54 |
| PUT | `/api/v1/shift-budget/allocations/{_}` | `ShiftBudgetAllocationController#updateAllocation` (backend/src/main/java/com/mannschaft/app/shiftbudget/controller/ShiftBudgetAllocationController.java) | 86 |

#### /api/v1/account/* (4 件)

| メソッド | パス | Controller | 行 |
|---|---|---|---|
| GET | `/api/v1/account/data-export/download` | `GdprController#getDownloadUrl` (backend/src/main/java/com/mannschaft/app/gdpr/controller/GdprController.java) | 78 |
| GET | `/api/v1/account/data-export/status` | `GdprController#getExportStatus` (backend/src/main/java/com/mannschaft/app/gdpr/controller/GdprController.java) | 65 |
| GET | `/api/v1/account/deletion-preview` | `GdprController#getDeletionPreview` (backend/src/main/java/com/mannschaft/app/gdpr/controller/GdprController.java) | 92 |
| POST | `/api/v1/account/data-export` | `GdprController#requestExport` (backend/src/main/java/com/mannschaft/app/gdpr/controller/GdprController.java) | 50 |

#### /api/v1/action-memos/* (4 件)

| メソッド | パス | Controller | 行 |
|---|---|---|---|
| DELETE | `/api/v1/action-memos/{_}/complete-todo` | `ActionMemoController#revertTodoCompletion` (backend/src/main/java/com/mannschaft/app/actionmemo/controller/ActionMemoController.java) | 304 |
| GET | `/api/v1/action-memos/available-orgs` | `ActionMemoController#getAvailableOrgs` (backend/src/main/java/com/mannschaft/app/actionmemo/controller/ActionMemoController.java) | 252 |
| GET | `/api/v1/action-memos/mood-stats` | `ActionMemoController#getMoodStats` (backend/src/main/java/com/mannschaft/app/actionmemo/controller/ActionMemoController.java) | 271 |
| GET | `/api/v1/action-memos/{_}/audit-logs` | `ActionMemoController#getMemoAuditLogs` (backend/src/main/java/com/mannschaft/app/actionmemo/controller/ActionMemoController.java) | 288 |

#### /api/v1/applications/* (4 件)

| メソッド | パス | Controller | 行 |
|---|---|---|---|
| GET | `/api/v1/applications/{_}` | `JobApplicationController#getApplication` (backend/src/main/java/com/mannschaft/app/jobmatching/controller/JobApplicationController.java) | 143 |
| POST | `/api/v1/applications/{_}/accept` | `JobApplicationController#acceptApplication` (backend/src/main/java/com/mannschaft/app/jobmatching/controller/JobApplicationController.java) | 159 |
| POST | `/api/v1/applications/{_}/reject` | `JobApplicationController#rejectApplication` (backend/src/main/java/com/mannschaft/app/jobmatching/controller/JobApplicationController.java) | 171 |
| POST | `/api/v1/applications/{_}/withdraw` | `JobApplicationController#withdrawApplication` (backend/src/main/java/com/mannschaft/app/jobmatching/controller/JobApplicationController.java) | 185 |

#### /api/v1/circulations/* (4 件)

| メソッド | パス | Controller | 行 |
|---|---|---|---|
| DELETE | `/api/v1/circulations/{_}/recipients/{_}` | `CirculationRecipientController#removeRecipient` (backend/src/main/java/com/mannschaft/app/circulation/controller/CirculationRecipientController.java) | 66 |
| POST | `/api/v1/circulations/{_}/attachments/upload-url` | `CirculationAttachmentController#presignUpload` (backend/src/main/java/com/mannschaft/app/circulation/controller/CirculationAttachmentController.java) | 74 |
| POST | `/api/v1/circulations/{_}/stamp/reject` | `CirculationStampController#reject` (backend/src/main/java/com/mannschaft/app/circulation/controller/CirculationStampController.java) | 59 |
| POST | `/api/v1/circulations/{_}/stamp/skip` | `CirculationStampController#skip` (backend/src/main/java/com/mannschaft/app/circulation/controller/CirculationStampController.java) | 47 |

#### /api/v1/notifications/* (4 件)

| メソッド | パス | Controller | 行 |
|---|---|---|---|
| POST | `/api/v1/notifications/read-all` | `NotificationController#markAllAsRead` (backend/src/main/java/com/mannschaft/app/notification/controller/NotificationController.java) | 113 |
| POST | `/api/v1/notifications/{_}/read` | `NotificationController#markAsRead` (backend/src/main/java/com/mannschaft/app/notification/controller/NotificationController.java) | 75 |
| POST | `/api/v1/notifications/{_}/snooze` | `NotificationController#snoozeNotification` (backend/src/main/java/com/mannschaft/app/notification/controller/NotificationController.java) | 99 |
| POST | `/api/v1/notifications/{_}/unread` | `NotificationController#markAsUnread` (backend/src/main/java/com/mannschaft/app/notification/controller/NotificationController.java) | 87 |

#### /api/v1/embed/* (3 件)

| メソッド | パス | Controller | 行 |
|---|---|---|---|
| GET | `/api/v1/embed/organizations/{_}/tournaments/{_}/bracket` | `EmbedController#getEmbedBracket` (backend/src/main/java/com/mannschaft/app/tournament/controller/EmbedController.java) | 43 |
| GET | `/api/v1/embed/organizations/{_}/tournaments/{_}/rankings/{_}` | `EmbedController#getEmbedRankings` (backend/src/main/java/com/mannschaft/app/tournament/controller/EmbedController.java) | 51 |
| GET | `/api/v1/embed/organizations/{_}/tournaments/{_}/standings/{_}` | `EmbedController#getEmbedStandings` (backend/src/main/java/com/mannschaft/app/tournament/controller/EmbedController.java) | 36 |

#### /api/v1/feedbacks/* (3 件)

| メソッド | パス | Controller | 行 |
|---|---|---|---|
| DELETE | `/api/v1/feedbacks/{_}/votes` | `FeedbackController#unvote` (backend/src/main/java/com/mannschaft/app/admin/controller/FeedbackController.java) | 76 |
| GET | `/api/v1/feedbacks/me` | `FeedbackController#getMyFeedbacks` (backend/src/main/java/com/mannschaft/app/admin/controller/FeedbackController.java) | 52 |
| POST | `/api/v1/feedbacks/{_}/votes` | `FeedbackController#vote` (backend/src/main/java/com/mannschaft/app/admin/controller/FeedbackController.java) | 65 |

#### /api/v1/jobs/* (3 件)

| メソッド | パス | Controller | 行 |
|---|---|---|---|
| GET | `/api/v1/jobs/fee-preview` | `JobPostingController#previewFee` (backend/src/main/java/com/mannschaft/app/jobmatching/controller/JobPostingController.java) | 101 |
| POST | `/api/v1/jobs/{_}/apply` | `JobApplicationController#apply` (backend/src/main/java/com/mannschaft/app/jobmatching/controller/JobApplicationController.java) | 102 |
| POST | `/api/v1/jobs/{_}/cancel` | `JobPostingController#cancelJob` (backend/src/main/java/com/mannschaft/app/jobmatching/controller/JobPostingController.java) | 200 |

#### /api/v1/recruitment/* (3 件)

| メソッド | パス | Controller | 行 |
|---|---|---|---|
| GET | `/api/v1/recruitment/no-shows/me` | `RecruitmentNoShowController#getMyNoShows` (backend/src/main/java/com/mannschaft/app/recruitment/controller/RecruitmentNoShowController.java) | 86 |
| GET | `/api/v1/recruitment/penalties/me` | `RecruitmentPenaltyController#getMyPenalties` (backend/src/main/java/com/mannschaft/app/recruitment/controller/RecruitmentPenaltyController.java) | 123 |
| POST | `/api/v1/recruitment/no-shows/{_}/dispute` | `RecruitmentNoShowController#dispute` (backend/src/main/java/com/mannschaft/app/recruitment/controller/RecruitmentNoShowController.java) | 99 |

#### /api/v1/surveys/* (3 件)

| メソッド | パス | Controller | 行 |
|---|---|---|---|
| GET | `/api/v1/surveys/{_}/responses/me` | `SurveyResponseController#getMyResponses` (backend/src/main/java/com/mannschaft/app/survey/controller/SurveyResponseController.java) | 52 |
| POST | `/api/v1/surveys/{_}/result-viewers` | `SurveyResultController#addResultViewers` (backend/src/main/java/com/mannschaft/app/survey/controller/SurveyResultController.java) | 67 |
| POST | `/api/v1/surveys/{_}/targets` | `SurveyResultController#addTargets` (backend/src/main/java/com/mannschaft/app/survey/controller/SurveyResultController.java) | 54 |

#### /api/v1/venues/* (3 件)

| メソッド | パス | Controller | 行 |
|---|---|---|---|
| GET | `/api/v1/venues/suggest` | `VenueController#suggest` (backend/src/main/java/com/mannschaft/app/venue/controller/VenueController.java) | 36 |
| GET | `/api/v1/venues/{_}` | `VenueController#getVenue` (backend/src/main/java/com/mannschaft/app/venue/controller/VenueController.java) | 46 |
| POST | `/api/v1/venues/register-from-google` | `VenueController#registerFromGoogle` (backend/src/main/java/com/mannschaft/app/venue/controller/VenueController.java) | 55 |

#### /api/v1/chat-folders/* (2 件)

| メソッド | パス | Controller | 行 |
|---|---|---|---|
| GET | `/api/v1/chat-folders/{_}/items` | `ChatFolderController#getFolderItems` (backend/src/main/java/com/mannschaft/app/dashboard/controller/ChatFolderController.java) | 95 |
| PATCH | `/api/v1/chat-folders/items/{_}/{_}` | `ChatFolderController#updateItemAttributes` (backend/src/main/java/com/mannschaft/app/dashboard/controller/ChatFolderController.java) | 136 |

#### /api/v1/point-cards/* (2 件)

| メソッド | パス | Controller | 行 |
|---|---|---|---|
| POST | `/api/v1/point-cards/groups/{_}/presentation-start` | `PointCardGroupController#startPresentation` (backend/src/main/java/com/mannschaft/app/pointcard/controller/PointCardGroupController.java) | 126 |
| POST | `/api/v1/point-cards/{_}/share-tokens` | `PointCardController#createShareToken` (backend/src/main/java/com/mannschaft/app/pointcard/controller/PointCardController.java) | 139 |

#### /api/v1/ads/* (1 件)

| メソッド | パス | Controller | 行 |
|---|---|---|---|
| GET | `/api/v1/ads/targeted` | `ActiveAdController#targetedAds` (backend/src/main/java/com/mannschaft/app/advertising/controller/ActiveAdController.java) | 36 |

#### /api/v1/advertiser/* (1 件)

| メソッド | パス | Controller | 行 |
|---|---|---|---|
| GET | `/api/v1/advertiser/campaigns/{_}/conversions/summary` | `AdConversionController#getConversionSummary` (backend/src/main/java/com/mannschaft/app/advertising/controller/AdConversionController.java) | 54 |

#### /api/v1/bulletin/* (1 件)

| メソッド | パス | Controller | 行 |
|---|---|---|---|
| GET | `/api/v1/bulletin/reactions/summary` | `BulletinReactionController#getReactionSummary` (backend/src/main/java/com/mannschaft/app/bulletin/controller/BulletinReactionController.java) | 79 |

#### /api/v1/care-links/* (1 件)

| メソッド | パス | Controller | 行 |
|---|---|---|---|
| POST | `/api/v1/care-links/invitations/{_}/reject` | `PublicCareLinkController#rejectInvitation` (backend/src/main/java/com/mannschaft/app/family/controller/PublicCareLinkController.java) | 55 |

#### /api/v1/dashboard/* (1 件)

| メソッド | パス | Controller | 行 |
|---|---|---|---|
| GET | `/api/v1/dashboard/todos` | `DashboardController#getPersonalTodos` (backend/src/main/java/com/mannschaft/app/dashboard/controller/DashboardController.java) | 185 |

#### /api/v1/file-permissions/* (1 件)

| メソッド | パス | Controller | 行 |
|---|---|---|---|
| DELETE | `/api/v1/file-permissions/{_}` | `FilePermissionController#deletePermission` (backend/src/main/java/com/mannschaft/app/filesharing/controller/FilePermissionController.java) | 63 |

#### /api/v1/gallery/* (1 件)

| メソッド | パス | Controller | 行 |
|---|---|---|---|
| GET | `/api/v1/gallery/albums/{_}/photos` | `PhotoAlbumController#listPhotos` (backend/src/main/java/com/mannschaft/app/gallery/controller/PhotoAlbumController.java) | 137 |

#### /api/v1/ical/* (1 件)

| メソッド | パス | Controller | 行 |
|---|---|---|---|
| GET | `/ical/{_}.ics` | `IcalController#getIcalFeed` (backend/src/main/java/com/mannschaft/app/schedule/controller/IcalController.java) | 75 |

#### /api/v1/incoming/* (1 件)

| メソッド | パス | Controller | 行 |
|---|---|---|---|
| POST | `/incoming/{_}` | `IncomingWebhookController#processIncoming` (backend/src/main/java/com/mannschaft/app/webhook/controller/IncomingWebhookController.java) | 88 |

#### /api/v1/mentions/* (1 件)

| メソッド | パス | Controller | 行 |
|---|---|---|---|
| POST | `/api/v1/mentions/{_}/read` | `MentionController#markAsRead` (backend/src/main/java/com/mannschaft/app/mention/controller/MentionController.java) | 46 |

#### /api/v1/notification-preferences/* (1 件)

| メソッド | パス | Controller | 行 |
|---|---|---|---|
| PUT | `/api/v1/notification-preferences` | `NotificationPreferenceController#updatePreference` (backend/src/main/java/com/mannschaft/app/notification/controller/NotificationPreferenceController.java) | 49 |

#### /api/v1/proxy-input/* (1 件)

| メソッド | パス | Controller | 行 |
|---|---|---|---|
| GET | `/api/v1/proxy-input/monthly-summaries/{_}/{_}/{_}/download-url` | `ProxyMonthlySummaryController#getDownloadUrl` (backend/src/main/java/com/mannschaft/app/proxy/controller/ProxyMonthlySummaryController.java) | 43 |

#### /api/v1/quick-memos/* (1 件)

| メソッド | パス | Controller | 行 |
|---|---|---|---|
| GET | `/api/v1/quick-memos/search` | `QuickMemoController#searchMemos` (backend/src/main/java/com/mannschaft/app/quickmemo/controller/QuickMemoController.java) | 137 |

#### /api/v1/recruitment-templates/* (1 件)

| メソッド | パス | Controller | 行 |
|---|---|---|---|
| GET | `/api/v1/recruitment-templates/{_}` | `RecruitmentTemplateController#getTemplate` (backend/src/main/java/com/mannschaft/app/recruitment/controller/RecruitmentTemplateController.java) | 124 |

#### /api/v1/reservations/* (1 件)

| メソッド | パス | Controller | 行 |
|---|---|---|---|
| POST | `/api/v1/reservations/{_}/cancel` | `ReservationCommonController#cancelMyReservation` (backend/src/main/java/com/mannschaft/app/reservation/controller/ReservationCommonController.java) | 59 |

#### /api/v1/shared-links/* (1 件)

| メソッド | パス | Controller | 行 |
|---|---|---|---|
| POST | `/api/v1/shared-links/{_}/access` | `FileLinkController#accessLink` (backend/src/main/java/com/mannschaft/app/filesharing/controller/FileLinkController.java) | 79 |

#### /api/v1/signage/* (1 件)

| メソッド | パス | Controller | 行 |
|---|---|---|---|
| GET | `/signage/{_}` | `SignageDisplayController#getDisplayConfig` (backend/src/main/java/com/mannschaft/app/signage/controller/SignageDisplayController.java) | 48 |

#### /api/v1/students/* (1 件)

| メソッド | パス | Controller | 行 |
|---|---|---|---|
| POST | `/api/v1/students/{_}/attendance/requirements/{_}/evaluate` | `AttendanceRequirementEvaluationController#evaluate` (backend/src/main/java/com/mannschaft/app/school/controller/AttendanceRequirementEvaluationController.java) | 70 |

#### /api/v1/succession/* (1 件)

| メソッド | パス | Controller | 行 |
|---|---|---|---|
| GET | `/api/v1/succession/covenants/me` | `SuccessionCovenantController#listMyCovenants` (backend/src/main/java/com/mannschaft/app/succession/controller/SuccessionCovenantController.java) | 110 |

#### /api/v1/supported-locales/* (1 件)

| メソッド | パス | Controller | 行 |
|---|---|---|---|
| GET | `/api/i18n/supported-locales` | `SupportedLocalesController#getSupportedLocales` (backend/src/main/java/com/mannschaft/app/common/i18n/SupportedLocalesController.java) | 27 |

#### /api/v1/todo-budget/* (1 件)

| メソッド | パス | Controller | 行 |
|---|---|---|---|
| DELETE | `/api/v1/todo-budget/links/{_}` | `TodoBudgetLinkController#deleteLink` (backend/src/main/java/com/mannschaft/app/shiftbudget/controller/TodoBudgetLinkController.java) | 58 |

#### /api/v1/tournament-presets/* (1 件)

| メソッド | パス | Controller | 行 |
|---|---|---|---|
| GET | `/api/v1/tournament-presets` | `TournamentTemplateController#listPublicPresets` (backend/src/main/java/com/mannschaft/app/tournament/controller/TournamentTemplateController.java) | 98 |

---

## 3. ✅ 一致（件数のみ）

一致したエンドポイント: **1514 件**（詳細リストは省略）

---

## 4. 🟦 スコープ階層プレフィックス逆引き準一致（V4-1）

> 実装側が `/api/v1/teams/{_}/...` 等のスコープ context 付きで定義されているが、設計書側ではコアパス（scope 抜き）で記載されているケース。意味的に同一とみなし、メイン集計の「設計あり・実装なし」「実装あり・設計なし」両方から除外している。

_該当なし。_
---

## 5. 🔵 将来機能（実装ステータス明示）

> 設計書テーブル行で状態列が `🔵`（Phase X 未着工等）と明示されているエンドポイント。意図的に未実装のため、メインの「設計あり・実装なし」には含めない。

将来機能件数: **28 件**

| 状態 | メソッド | パス | 設計書 | 行 | 実装済 |
|---|---|---|---|---:|:---:|
| 🔵 | DELETE | `/api/v1/organizations/{_}/knowledge-base/pages/{_}` | `docs/features/F06.5_knowledge_base.md` | 320 |  |
| 🔵 | DELETE | `/api/v1/organizations/{_}/skill-categories/{_}` | `docs/features/F07.5_skill_certification.md` | 195 |  |
| 🔵 | DELETE | `/api/v1/organizations/{_}/skills/{_}` | `docs/features/F07.5_skill_certification.md` | 219 |  |
| 🔵 | GET | `/api/v1/admin/member-permissions` | `docs/features/F10.1_admin_dashboard.md` | 480 |  |
| 🔵 | GET | `/api/v1/admin/permission-groups/{_}` | `docs/features/F10.1_admin_dashboard.md` | 474 |  |
| 🔵 | GET | `/api/v1/admin/seals/regenerate-all/{_}/status` | `docs/features/F05.3_digital_seal.md` | 163 |  |
| 🔵 | GET | `/api/v1/admin/seals/ungenerated` | `docs/features/F05.3_digital_seal.md` | 164 |  |
| 🔵 | GET | `/api/v1/organizations/{_}/knowledge-base/pages` | `docs/features/F06.5_knowledge_base.md` | 316 |  |
| 🔵 | GET | `/api/v1/organizations/{_}/knowledge-base/pages/{_}` | `docs/features/F06.5_knowledge_base.md` | 317 |  |
| 🔵 | GET | `/api/v1/organizations/{_}/skill-categories` | `docs/features/F07.5_skill_certification.md` | 192 |  |
| 🔵 | GET | `/api/v1/organizations/{_}/skill-matrix` | `docs/features/F07.5_skill_certification.md` | 237 |  |
| 🔵 | GET | `/api/v1/organizations/{_}/skills/export` | `docs/features/F07.5_skill_certification.md` | 239 |  |
| 🔵 | GET | `/api/v1/organizations/{_}/skills/me` | `docs/features/F07.5_skill_certification.md` | 215 |  |
| 🔵 | GET | `/api/v1/organizations/{_}/skills/search` | `docs/features/F07.5_skill_certification.md` | 238 |  |
| 🔵 | GET | `/api/v1/organizations/{_}/skills/{_}` | `docs/features/F07.5_skill_certification.md` | 217 |  |
| 🔵 | GET | `/api/v1/organizations/{_}/skills/{_}/certificate-url` | `docs/features/F07.5_skill_certification.md` | 222 |  |
| 🔵 | PATCH | `/api/v1/organizations/{_}/knowledge-base/pages/{_}` | `docs/features/F06.5_knowledge_base.md` | 319 |  |
| 🔵 | PATCH | `/api/v1/organizations/{_}/knowledge-base/pages/{_}/archive` | `docs/features/F06.5_knowledge_base.md` | 323 |  |
| 🔵 | PATCH | `/api/v1/organizations/{_}/knowledge-base/pages/{_}/move` | `docs/features/F06.5_knowledge_base.md` | 321 |  |
| 🔵 | PATCH | `/api/v1/organizations/{_}/knowledge-base/pages/{_}/publish` | `docs/features/F06.5_knowledge_base.md` | 322 |  |
| 🔵 | PATCH | `/api/v1/organizations/{_}/skills/{_}/verify` | `docs/features/F07.5_skill_certification.md` | 220 |  |
| 🔵 | POST | `/api/v1/organizations/{_}/knowledge-base/pages` | `docs/features/F06.5_knowledge_base.md` | 318 |  |
| 🔵 | POST | `/api/v1/organizations/{_}/skill-categories` | `docs/features/F07.5_skill_certification.md` | 193 |  |
| 🔵 | POST | `/api/v1/organizations/{_}/skills` | `docs/features/F07.5_skill_certification.md` | 216 |  |
| 🔵 | POST | `/api/v1/organizations/{_}/skills/upload-url` | `docs/features/F07.5_skill_certification.md` | 221 |  |
| 🔵 | PUT | `/api/v1/admin/member-permissions` | `docs/features/F10.1_admin_dashboard.md` | 481 |  |
| 🔵 | PUT | `/api/v1/organizations/{_}/skill-categories/{_}` | `docs/features/F07.5_skill_certification.md` | 194 |  |
| 🔵 | PUT | `/api/v1/organizations/{_}/skills/{_}` | `docs/features/F07.5_skill_certification.md` | 218 |  |

