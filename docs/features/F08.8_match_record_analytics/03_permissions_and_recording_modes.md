# F08.8 / 03: 記録モード・編集権限・セキュリティ・IDOR

> **ステータス**: 🟡 設計中
> **最終更新**: 2026-06-08
> **関連機能番号**: F08.8（試合記録・分析）／ F00（コンテンツ可視性・ロール基盤）
> **関連ドキュメント**:
> - [01_domain_and_ddl.md](./01_domain_and_ddl.md) — `recorded_by_team_id` / `owning_team_id` / `scorekeeper_user_id` / `has_scorekeeper`
> - [02_playing_time_and_aggregation.md](./02_playing_time_and_aggregation.md) — 集計 API の認可対象
> - [docs/security/03_role_authority_model.md](../../security/03_role_authority_model.md) — ロール・権限モデル
> - 実装基盤: `com.mannschaft.app.common.AccessControlService`（メンバーシップ検証・ロール判定）

本書は **C（記録モードと権限）** を具体化する。最重要章。

---

## C. 記録モードと権限

### C.1 2 つの記録モード

`matches.has_scorekeeper`（および `scorekeeper_user_id`）で記録モードを表す。

| モード | フラグ | 説明 | 実運用 |
|--------|--------|------|--------|
| **公式戦（記録係あり）** | `has_scorekeeper=TRUE` | ホーム/主催が試合を作成し、**記録係**（会場運営・記録担当のユーザー）がタイムラインを単独入力 | 大会・リーグの会場記録係に一致 |
| **記録係なし（共同記録）** | `has_scorekeeper=FALSE` | 練習・親善・記録係不在の試合。**どちらのチームもタイムラインに記録可**（共同記録） | 練習試合・親善試合 |

- モードは試合作成時に決定し、作成者（ホーム/主催）が後から切替可能（権限は C.3）。
- 公式戦では `scorekeeper_user_id` に記録係を指定する。記録係は作成者本人でも別ユーザーでもよい。

### C.2 編集・訂正権限の分界

| 対象 | 権限 | 判定列 |
|------|------|--------|
| 試合メタ情報（日時・会場・**最終スコア** `home_score`/`away_score`・`status`・`duration_minutes`・モード切替） | **作成者（ホーム/主催/記録係）のみ** | `matches.created_by` ／ `scorekeeper_user_id` ／ 主体チーム ADMIN/DEPUTY |
| タイムラインイベント記録（公式戦） | **記録係のみ** | `scorekeeper_user_id` |
| タイムラインイベント記録（共同記録） | **両チームの ADMIN/DEPUTY**（自チーム分） | `match_events.recorded_by_team_id` |
| 自チーム選手の出場・交代・スタッツ訂正 | **当該チーム ADMIN/DEPUTY のみ（自チーム分）** | `player_appearances.owning_team_id` ／ `match_events.recorded_by_team_id` |
| **相手チーム分・スコア**の訂正 | **不可**（記録係/主催へ依頼） | — |
| 閲覧（試合統合表示・スタッツ） | 両チーム MEMBER 以上＋（公開設定時）SUPPORTER/GUEST | F00 可視性に従う |

#### 権限ルールの要点

- 各チームは**自チーム分のみ**訂正可。`owning_team_id` / `recorded_by_team_id` が自チームでない行への UPDATE/DELETE/INSERT は **403**。
- **スコア（`home_score`/`away_score`）は作成者/記録係のみが確定**する。相手チームはスコアを書き換えられない（依頼ベース）。
- イベントの得点（GOAL）を相手チームが入れたい場合（共同記録で相手の得点を記録）も、`recorded_by_team_id` は記録した側のチームを保持し、訂正権限は記録した側＋作成者に限定する。
- 閲覧は 1 試合として統合表示し、両チーム・各選手にスタッツを共有する。**DB の所有（owning_team_id 等）はユーザーに不可視**（UI には「自分が編集できるか」だけを露出）。

### C.3 認可基盤への実装方針

既存 `AccessControlService`（`com.mannschaft.app.common`）を活用する。

- **メンバーシップ判定**: `accessControlService.isMember(userId, teamId, "TEAM")`（memberships テーブル参照・F00.5 Phase 3）。
- **ADMIN/DEPUTY 判定**: `AccessControlService` の `isAdminOrAbove` 系（user_roles 参照）でチーム ADMIN/DEPUTY を確認。
- **記録係判定**: `matches.scorekeeper_user_id == 認証ユーザー`。
- **作成者判定**: `matches.created_by == 認証ユーザー` または 主体チーム ADMIN。
- 認可は `MatchAccessService`（match ドメイン）に集約し、Controller から委譲する。F00 の `ContentVisibilityResolver` を**閲覧可視性**に用い、編集権限は本書の分界ロジックを `MatchAccessService` が判定する（独自 visibility 述語を作らず F00 正準へ寄せる）。

```java
// MatchAccessService の判定 API（概念）
boolean canEditMeta(Long userId, MatchEntity match);        // 作成者/記録係/主体チームADMIN
boolean canRecordTimeline(Long userId, MatchEntity match);  // 公式戦=記録係 / 共同=両チームADMIN
boolean canEditTeamData(Long userId, MatchEntity match, Long owningTeamId); // 自チームADMIN かつ owning==自チーム
boolean canView(Long userId, MatchEntity match);            // F00 可視性
```

### C.4 IDOR 対策（match_id → 帰属確認チェーン）

全ての書き込み・読み取り API で、Service 層が次の帰属チェーンを必ず検証する（推測 ID による越境を遮断）。

| 操作 | 検証チェーン |
|------|-------------|
| `GET/PATCH /matches/{matchId}` | `match.organization_id == 認証テナント` → `canView`/`canEditMeta` |
| `POST /matches/{matchId}/events` | `match` 存在＋テナント一致 → `canRecordTimeline` → （共同記録時）`event.recorded_by_team_id` が自チーム |
| `PATCH/DELETE /matches/{matchId}/events/{eventId}` | `event.match_id == matchId` → `match` テナント一致 → `recorded_by_team_id` が自チーム or 記録係 |
| `PATCH /matches/{matchId}/appearances/{apId}` | `appearance.match_id == matchId` → `owning_team_id` が自チーム ADMIN |
| `GET /users/{userId}/match-stats` | `userId == self` or 当該ユーザーと同一チームの ADMIN（他人の統計閲覧は F00 可視性） |
| `GET /teams/{teamId}/match-stats` | `teamId` のメンバー以上 |

- 親子 ID 不一致（`event.match_id != パスの matchId` 等）は **404 で統一**（存在を漏らさない）。
- テナント越境（`match.organization_id != 認証テナント`）も **404**。
- リポジトリは `AbstractTenantAwareRepository`（原則 7）の `findByIdAndOrganizationIdAndDeletedAtIsNull` を用いて、テナント絞り込みを基底で強制する。

### C.5 退会ユーザーの扱い（原則 4・GDPR）

- `match_events.player_user_id` / `related_player_user_id`、`player_appearances.player_user_id`、`matches.created_by`/`scorekeeper_user_id` は**履歴・統計の証跡として ID を保持**（NULL 化しない＝強匿名化対象外）。
- 表示名は既存匿名化（`user.anonymize()`）に追従（集計 DTO の `displayName` は匿名化後の値を返す）。
- `player_name`（手入力の未登録選手名）は当該本人の退会とは独立した運用入力ゆえ匿名化対象外（F08.7.1/05 §9.2 と同方針）。

### C.6 監査ログ

- スコア確定（メタ更新）・status 遷移（COMPLETED/CANCELLED）・モード切替を監査ログに残す。
- `AuditEventType` に `MATCH_SCORE_FINALIZED` / `MATCH_STATUS_CHANGED` / `MATCH_RECORDING_MODE_CHANGED` を追加する想定（既存 tournament 系の監査追加と同パターン）。
- イベントの大量追加（ライブ記録）は監査ログに individ で残すとノイズになるため、**1 試合の記録セッション単位**でのサマリ監査（誰がいつ何件記録したか）に留める。

---

## 未解決事項

1. **共同記録モードの編集競合**: 両チームが同時にタイムライン入力すると `matches.version`（楽観ロック）が衝突する。イベントは行単位（`match_events` 個別行）なので衝突しにくいが、スコアキャッシュ更新で競合し得る。楽観ロックの粒度（match 全体 vs イベント行のみ）と、ライブ入力中の 409 リトライ UX（[04](./04_frontend_and_ux.md)）の確定が必要。
2. **記録係の権限源泉**: `scorekeeper_user_id` を「単なるユーザー指定」とするか、会場運営ロール（組織レベルの記録係ロール）を新設するか。後者なら F00 ロール基盤への追加が必要。当面は前者（作成者が任意ユーザーを記録係指定）とする想定。
3. **公開閲覧（SUPPORTER/GUEST）の範囲**: 大会の公式戦は公開（F08.7 の `TournamentVisibility` 連動）だが、練習試合の公開可否はチーム設定に従うか。F00 可視性レベル（`SCOPE_AFFILIATED` 等）との対応を確定する必要がある。
4. **相手チームが未登録（opponent_team_id=NULL）時**: 共同記録が成立しない（相手チームのメンバーが居ない）。この場合は自動的に「作成チーム単独記録」に縮退する想定でよいか。
