# Phase 1-A クロスドメインFK 撤廃 — 棚卸し

「1000万ユーザー耐久DB再構築」Phase 1（ドメイン境界の物理的徹底）の一環として、
user / team / organization ドメインへの越境 FK を全件撤廃する。

参照整合性はアプリケーション層で保証する（CLAUDE.md L273-349 参照）。

## 全体規模（家老C 偵察結果 / 2026-05-09）

| 参照先 | 件数（推定） |
|---|---|
| `users` (user_id 参照) | 38 |
| `teams` (team_id 参照) | 24 |
| `organizations` (organization_id 参照) | 18 |
| **小計（クロスドメインFK）** | **約 80** |
| その他越境 FK | 約 32 |
| **総計** | **112** |

## 第一波（V62.001）— 本 PR で処理

低リスク（同時に発火する CASCADE 連鎖が小さい・既存 index で吸収可能）な 9 制約。

| # | テーブル | 制約名 | カラム | 元のON DELETE | 所属ドメイン |
|---|---|---|---|---|---|
| 1 | `point_transactions`         | `fk_pt_user`     | `user_id`     | CASCADE | gamification |
| 2 | `user_badges`                | `fk_ub_user`     | `user_id`     | CASCADE | gamification |
| 3 | `ranking_snapshots`          | `fk_rs_user`     | `user_id`     | CASCADE | gamification |
| 4 | `gamification_user_settings` | `fk_gus_user`    | `user_id`     | CASCADE | gamification |
| 5 | `contact_requests`           | `fk_cr_requester`| `requester_id`| CASCADE | contact      |
| 6 | `contact_requests`           | `fk_cr_target`   | `target_id`   | CASCADE | contact      |
| 7 | `contact_request_blocks`     | `fk_crb_user`    | `user_id`     | CASCADE | contact      |
| 8 | `contact_request_blocks`     | `fk_crb_blocked` | `blocked_id`  | CASCADE | contact      |
| 9 | `contact_invite_tokens`      | `fk_cit_user`    | `user_id`     | CASCADE | contact      |

## 第二波（V62.002）— feature/db-phase1a-wave2-user-fk PR で処理

admin / onboarding / timetable / sync / social / todo ドメインから低リスク 9 制約を撤廃。

| # | テーブル | 制約名 | カラム | 元のON DELETE | 所属ドメイン |
|---|---|---|---|---|---|
| 10 | `feedback_votes`          | `fk_fv_user`                    | `user_id`    | CASCADE | admin        |
| 11 | `user_violations`         | `fk_uv_user`                    | `user_id`    | CASCADE | admin        |
| 12 | `onboarding_progresses`   | `fk_op_user`                    | `user_id`    | CASCADE | onboarding   |
| 13 | `data_exports`            | `fk_data_exports_user`          | `user_id`    | CASCADE | data         |
| 14 | `personal_timetables`     | `fk_personal_timetable_user`    | `user_id`    | CASCADE | timetable    |
| 15 | `offline_sync_conflicts`  | `fk_offline_sync_conflicts_user`| `user_id`    | CASCADE | sync         |
| 16 | `user_blocks`             | `fk_ub_blocker`                 | `blocker_id` | CASCADE | social       |
| 17 | `user_blocks`             | `fk_ub_blocked`                 | `blocked_id` | CASCADE | social       |
| 18 | `todo_personal_memos`     | `fk_tpm_user`                   | `user_id`    | CASCADE | todo         |

index 追加:
- `feedback_votes`: `idx_fv_user_id(user_id)` — UNIQUE の第2列のため単独検索用に追加
- `todo_personal_memos`: `idx_tpm_user_id(user_id)` — UNIQUE の第2列のため単独検索用に追加

## 残波の見通し

第三波以降の対象は `PHASE1A_TODO.md` 参照。
波ごとに 5〜10 件で PR を切り、CI を確実に通しながら段階的に進める。

## 注意

- 撤廃した FK の代替として、削除されるユーザーの履歴は **退会匿名化フロー**
  (`UserEntity.anonymize()` + `UserService.withdrawUser()` — Phase 0-α で仕上げ中) で
  PII を消去しつつ統計データを保持する設計に移行している。
- 物理的に user 行が消える経路は将来的に存在しない想定（論理削除徹底）。
- そのため CASCADE 撤廃により孤児行が残る心配は理論上なくなる。
