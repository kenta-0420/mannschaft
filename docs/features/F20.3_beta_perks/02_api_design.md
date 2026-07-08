# F20.3 — 02 API設計

> **ステータス**: 🟢 設計完了（要裁可論点 R/B のマスター裁可待ち）
> 付与・取消・審査・照会の API と活動実績評価の擬似コードを定義する。認可は [03_security](03_security.md)、DDL は [01_data_model](01_data_model.md)。権利判定 API は F20.1（`isEntitled`/check）を再利用し**本機能では作らない**。

---

## 0. 共通

- ベースパス: `/api/v1`。レスポンス封筒: `ApiResponse<T>`（既存規約）。
- 付与・取消・延長は**冪等に設計**（uk_bg_scope_phase／uk_ent_grant が二重実行の backstop・決済を伴わないため `Idempotency-Key` ヘッダは不要）。
- シスアド API は `@PreAuthorize("hasRole('SYSTEM_ADMIN')")`（03 §1）。

---

## 1. 本人・団体向け照会

### 1.1 自分のベータ特典

```
GET /api/v1/me/beta-perks
認可: 認証ユーザー（scopeId を受けない・本人固定）
```

レスポンス `MyBetaPerksResponse`:

| フィールド | 型 | null | 例 |
|---|---|---|---|
| `grants` | `BetaGrantItem[]` | 不可（空配列可） | — |
| `grants[].grantId` | string(UUID) | 不可 | `"0198..."` |
| `grants[].betaPhase` | number | 不可 | `2` |
| `grants[].grantKind` | string | 不可 | `"INDIVIDUAL"` |
| `grants[].grantedAt` | string(ISO-8601) | 不可 | `"2026-07-08T03:00:00"` |
| `grants[].validUntil` | string(ISO-8601) | **可**（個人特典=null。**表示は「サービス提供期間中無償」・「永久」禁止**） | `null` |
| `grants[].revokedAt` | string(ISO-8601) | **可** | `null` |
| `grants[].featureKeys` | string[] | 不可 | `["ads.hide", ...]` |
| `eligibility` | `EligibilityStatus` | **可**（現行フェーズの criteria 未定義/enabled=false 時 null） | — |
| `eligibility.betaPhase` | number | 不可 | `2` |
| `eligibility.eligible` | boolean | 不可 | `false` |
| `eligibility.metrics` | `MetricProgress[]` | 不可 | — |
| `eligibility.metrics[].metricKey` | string | 不可 | `"activeDays"` \| `"membershipTenureDays"` |
| `eligibility.metrics[].actual` | number | 不可 | `9` |
| `eligibility.metrics[].required` | number | 不可 | `14` |

- `eligibility` は ADHD フレンドリーな進捗開示（「あと 5 日で特典条件達成」表示・04 §1）。**他人の eligibility は照会不可**（AC-17・03 §2）。

### 1.2 チーム/組織の特典

```
GET /api/v1/teams/{teamId}/beta-perks
GET /api/v1/organizations/{orgId}/beta-perks
認可: @accessGuard.isScopeMember(authentication, #teamId, 'TEAM') 等（メンバー閲覧可・03 §1）
```

- レスポンス: `BetaGrantItem[]`（§1.1 と同型＋`activeMemberCountSnapshot: number|null`）。`review_flag` 系の内部運用列は**返さない**（審査中であることを利用者に晒さない・03 §3）。

---

## 2. 活動実績評価（機構の正準・擬似コード）

```java
@Service
public class BetaPerkEligibilityService {

    /** 付与条件の評価（README §2 のメトリクス定義が正準・Clock 注入で date-pin テスト可能） */
    public EligibilityResult evaluate(GrantKind grantKind, EntitlementScopeKind scopeKind,
                                      Long scopeId, int betaPhase) {
        BetaPerkCriteriaEntity c = criteriaRepository.findById(new BetaPerkCriteriaId(betaPhase, grantKind))
                .filter(BetaPerkCriteriaEntity::isEnabled)
                .orElseThrow(() -> new BusinessException(BetaPerkErrorCode.CRITERIA_NOT_FOUND)); // 404
        List<MetricProgress> metrics = new ArrayList<>();
        if (c.getMinActiveDays() != null) {          // 計測源が確定するまで NULL 運用＝スキップ（README §7・§9）
            // ★USER の activeDays は F10.8 では取れない（TEAM/ORG 限定）。実装前確定条件で選ぶ計測源を呼ぶ。
            //   第一候補: audit_logs の LOGIN_SUCCESS を COUNT(DISTINCT DATE(created_at)) で数える。
            long actual = loginActivityQueryService.countDistinctActiveDays(  // 実装源は §9 で確定（audit_logs LOGIN_SUCCESS 等）
                    resolveUserId(scopeKind, scopeId),                        // INDIVIDUAL のみ。TEAM_ORG はこの指標を使わない
                    now.minusDays(c.getEvaluationWindowDays()));
            metrics.add(new MetricProgress("activeDays", actual, c.getMinActiveDays()));
        }
        if (c.getMinMembershipTenureDays() != null) {
            long actual = membershipQueryService.tenureDays(scopeKind, scopeId, now);
            // INDIVIDUAL: 本人の最古有効所属（left_at IS NULL）の joined_at からの経過日数
            // TEAM_ORG : スコープ自体の作成日（teams/organizations.created_at）からの経過日数
            metrics.add(new MetricProgress("membershipTenureDays", actual, c.getMinMembershipTenureDays()));
        }
        if (grantKind == TEAM_ORG && c.getMinActiveMembers() != null) {
            long actual = membershipQueryService.activeMemberCount(scopeKind, scopeId);  // F20.1 01 §3.4 と同一定義
            metrics.add(new MetricProgress("activeMembers", actual, c.getMinActiveMembers()));
        }
        boolean eligible = metrics.stream().allMatch(m -> m.actual() >= m.required());   // 定義済み指標の AND
        return new EligibilityResult(eligible, metrics, c);
    }
}
```

- **AND 判定・境界は「以上」**（`actual >= required`）。指標が 1 つも定義されない criteria はマスタ CRUD で保存不可（`BETA_PERK_009`・01 §2）。
- **新設サービス（L4・実装者向けシグネチャ明示）**:
  - `LoginActivityQueryService.countDistinctActiveDays(Long userId, LocalDateTime since) : long` — `audit_logs` の `LOGIN_SUCCESS` を `SELECT COUNT(DISTINCT DATE(created_at)) WHERE user_id=:userId AND event_type='LOGIN_SUCCESS' AND created_at >= :since` で数える（billing.beta ドメインに新設・audit ドメインの Repository を read-only 参照 or 専用クエリメソッド追加）。**個人の activeDays 唯一の源**（F10.8 は USER 非対応・README §7）。
  - `MembershipQueryService.tenureDays(EntitlementScopeKind, Long scopeId, LocalDateTime now) : long` — INDIVIDUAL=本人の最古有効所属 `joined_at`（`left_at IS NULL`）／TEAM_ORG=`teams`/`organizations.created_at` からの経過日数（README §2 両建て）。
  - `MembershipQueryService.activeMemberCount(...)` は F20.1 01 §3.4 の `countActiveDistinctUsersByScope` 再利用（新規メソッドを足さない）。

---

## 3. 自動付与バッチ（個人特典・P2）

- `BetaPerkAutoGrantBatchService`・**毎日 04:00 JST**（`@Scheduled(cron="0 0 4 * * *", zone="Asia/Tokyo")`）・`@SchedulerLock` 多重起動防止・`Clock` 注入・ページング走査（F08.9 の進学予告バッチと同型の作法）。
- **★本番有効化の前提条件（③・活動実績の担保）**: 対象フェーズの `beta_perk_criteria.min_active_days` が **NULL（activeDays 未計測）の間はバッチを本番有効化しない**（tenure-only では無活動ユーザーに付与され主原則違反・README §2）。`activeDays` 計測源（`LoginActivityQueryService`・§2）の結線後に `min_active_days` を設定してから本番有効化する。それまでの付与は**シスアド審査付き手動付与のみ**（§4.1・`skipCriteriaCheck` は使わず criteria 充足を審査で確認）。運用フラグ `mannschaft.beta.auto-grant.enabled`（既定 false）でバッチ実行自体をゲートする。
- 処理（擬似コード）:

```
for user in activeUsers（ページング・凍結除外・**退会申請中（WithdrawalRequestedEvent 受領〜猶予中）も除外**・01 §8）:
  if exists beta_grants(scope_kind='USER', scope_id=user.id, beta_phase=currentPhase): continue
  result = eligibilityService.evaluate(INDIVIDUAL, USER, user.id, currentPhase)
  if !result.eligible: continue
  grantService.grantBetaPerk(INDIVIDUAL, currentPhase, USER, user.id, operator=SYSTEM)
      # 01 §3 の単一トランザクション（grant＋entitlements＋バッジ＋通知＋evict）
      # uk_bg_scope_phase 競合（並行実行）は DataIntegrityViolationException で捕捉しスキップ（冪等）
```

- `currentPhase`（現在のベータ段階）は**アプリ設定値**（`mannschaft.beta.current-phase`・application.yml/環境変数）。段階の切替はデプロイ設定変更（頻度が低くマスタ表に持つ価値なし）。
- 通知: 付与成功時に本人へアプリ内通知（`NotificationHelper.notify` 正準経路）＋文言は 04 §2（「ベータ特典が付与されました」・**「サービス提供期間中無償」文言**）。

---

## 4. シスアド運用 API

```
GET    /api/v1/system-admin/beta-perks/grants?grantKind=&betaPhase=&reviewFlag=&scopeKind=&scopeId=&page=&size=
POST   /api/v1/system-admin/beta-perks/grants                 # 手動付与（TEAM_ORG の正規経路・INDIVIDUAL も可）
POST   /api/v1/system-admin/beta-perks/grants/{grantId}/revoke        # 取消
POST   /api/v1/system-admin/beta-perks/grants/{grantId}/extend        # 延長（TEAM_ORG の 2 年後更新）
POST   /api/v1/system-admin/beta-perks/grants/{grantId}/resolve-review # 審査解決（問題なし）
POST   /api/v1/system-admin/beta-perks/grants/{grantId}/flag-review    # 手動フラグ（MANUAL）
GET    /api/v1/system-admin/beta-perks/candidates?grantKind=&betaPhase=&page=  # 付与候補の dry-run 一覧（充足スコープの抽出・付与はしない）
GET/PUT /api/v1/system-admin/beta-perks/criteria/{betaPhase}/{grantKind}       # 条件マスタ CRUD（複合自然キー PATH）
認可: 全 EP @PreAuthorize("hasRole('SYSTEM_ADMIN')")
```

### 4.1 手動付与 `POST grants`

リクエスト `CreateBetaGrantRequest`:

| フィールド | 型 | 必須 | 例 | 検証 |
|---|---|---|---|---|
| `grantKind` | string | ✔ | `"TEAM_ORG"` | `INDIVIDUAL`/`TEAM_ORG` 以外 400 |
| `betaPhase` | number | ✔ | `2` | 1〜4 以外 `BETA_PERK_004` 400 |
| `scopeKind` | string | ✔ | `"TEAM"` | kind×scope 不整合は `BETA_PERK_007` 422（INDIVIDUAL×USER / TEAM_ORG×TEAM\|ORG のみ可） |
| `scopeId` | number | ✔ | `123` | 実在検証（team/org/user）。無ければ 404 |
| `skipCriteriaCheck` | boolean | 任意（既定 false） | `false` | `true` はマスター運用の例外付与（criteria 未達でも付与・audit_logs に明示記録）。`false` で未達なら `BETA_PERK_003` 422（実測/閾値を details に含める・AC-03） |
| `note` | string(500) | 任意 | `"第2期 パイロット団体"` | 監査用メモ |

- 処理は 01 §3 の発行規約（単一トランザクション）。二重付与は `BETA_PERK_002` 409（AC-10）。
- レスポンス `BetaGrantDetailResponse`: `BetaGrantItem`（§1.1）＋`criteriaSnapshot: object`＋`activeMemberCountSnapshot`＋`reviewFlag`/`reviewReason`/`grantedBy`/`note`。

### 4.2 取消 `POST {grantId}/revoke`

- Body `RevokeBetaGrantRequest { reason: "TERMS_VIOLATION"|"ACCOUNT_TRANSFER"|"OTHER", note?: string }`（`WITHDRAWAL` はシステム専用値のため API からは指定不可・400）。
- 処理: `revoked_at/by/reason` セット＋由来 entitlements 全 revoke＋キャッシュ evict＋対象者へ通知（04 §2）。既に取消済みは `BETA_PERK_005` 409。

### 4.3 延長 `POST {grantId}/extend`（自動更新しない・都度アナウンス→一括操作）

- Body `ExtendBetaGrantRequest { extensionMonths: number }`（1〜24・範囲外 400）。
- 対象: `grant_kind=TEAM_ORG` のみ（INDIVIDUAL は無期限ゆえ `BETA_PERK_008` 422）。取消済みは 409。
- 処理: 現行 entitlements の最大 `valid_until` を起点に、**新 entitlement 行**（`valid_from=旧 valid_until`・`valid_until=起点+extensionMonths`・同一 `source_ref_id`）を feature ごとに発行（01 §3・AC-14）。**既存行の UPDATE はしない**（append-only）。
- 一括延長は FE（シスアド画面）が対象一覧選択→本 EP を並列呼び出し（専用 bulk EP は作らない・件数少）。

### 4.4 審査 `resolve-review` / `flag-review`

- `resolve-review`: `review_flag=true` の grant のみ（それ以外 `BETA_PERK_006` 409）。`review_flag=false`・`review_resolved_at/by` 記録（AC-20）。
- `flag-review`: Body `{ note?: string }`。`review_reason='MANUAL'`・`review_flagged_at=now`。取消済み grant へのフラグは 409。

### 4.5 候補一覧 `GET candidates`（dry-run）

- `eligibilityService.evaluate` を対象走査に適用し、**未付与かつ充足**のスコープを返す（付与はしない）。レスポンス: `[{ scopeKind, scopeId, displayName, metrics: MetricProgress[] }]`。TEAM_ORG の審査前スクリーニング用。

### 4.6 条件マスタ CRUD `criteria`

- `PUT` Body `BetaPerkCriteriaUpsertRequest { evaluationWindowDays, minActiveDays?, minMembershipTenureDays?, minActiveMembers?, enabled }`。
- バリデーション: **最低 1 指標が非 NULL**（全 NULL は `BETA_PERK_009` 400・「無条件付与」防止）。`evaluationWindowDays` 1〜365。

---

## 5. オーナー変更イベント購読（review_flag 自動化・P3）

```java
/** team ドメイン発火（B-4: 現存しない場合は team 側に最小 publish を新設） */
public record TeamOwnershipTransferredEvent(Long teamId, Long fromUserId, Long toUserId) {}

@TransactionalEventListener(phase = AFTER_COMMIT)
@Transactional(propagation = REQUIRES_NEW)   // feedback_transactional_event_listener_requires_new
public void onOwnershipTransferred(TeamOwnershipTransferredEvent ev) {
    betaGrantRepository.findActiveByScope("TEAM", ev.teamId())     // revoked_at IS NULL
        .forEach(g -> {
            g.flagReview("OWNER_CHANGED");                          // review_flag=true・flagged_at=now（AC-07）
            auditLogService.record("BETA_GRANT_REVIEW_FLAGGED", ...);
            notifyOperations(g);                                    // 運営（SYSTEM_ADMIN）へ通知
        });
}
```

- 組織のオーナー変更も同型（`OrganizationOwnershipTransferredEvent`・存在有無を実装時確認）。イベントが未整備の間は**シスアド手動 flag-review が代替経路**（機能は塞がらない）。

### 5.1 退会イベント購読（M-4/M-5・実在イベントに準拠）

```java
@Component
public class BetaPerkPurgeEventListener {

    // 退会申請（猶予開始）: 自動付与のみ抑止・revoke しない（撤回で復活できないため・01 §8）
    // → 抑止は「自動付与バッチが退会申請中ユーザーを除外」で表現（本リスナーで grant 状態は変えない）

    /** 退会確定（物理削除）: この時点で撤回窓は閉じており revoke してよい（AC-19） */
    @TransactionalEventListener(phase = AFTER_COMMIT)
    @Transactional(propagation = REQUIRES_NEW)   // feedback_transactional_event_listener_requires_new
    public void onAccountPurged(AccountPurgedEvent ev) {
        betaGrantService.revokeAllForUser(ev.getUserId(), RevokeReason.WITHDRAWAL);  // grant＋entitlements 失効（AccountPurgedEvent は class＝Lombok getter）
    }
    // WithdrawalCancelledEvent は購読不要（猶予中に権利を維持しているため何もしない）
}
```

> `AccountPurgedEvent`・`WithdrawalRequestedEvent`・`WithdrawalCancelledEvent` は origin/main 実在（`gdpr`/`auth.event` パッケージ・`AccountPurgeService` バッチが発火）。**架空の `UserWithdrawalService`（トランザクション内アトミック失効）ではなくイベント駆動**に合わせる（`AuditLogEventListener`/`WithdrawalStripeHandler` 前例）。

---

## 6. F10.8 計測ビーコン（利用イベント 1 種・README §7 の確定方式）

- F10.8 の content type enum（BE 側バインド・**enum 名は F10.8 実装時に確定**＝設計書に確定名が無いため決め打ちしない・README §7）に **`FEATURE`** を 1 値追加（`page_view_logs.content_type` は VARCHAR(20)・DDL 不要）。
- **個人（USER）の `activeDays` は F10.8 では取れない**（F10.8 は TEAM/ORGANIZATION スコープのみ）。個人特典の `activeDays` は `audit_logs` の `LOGIN_SUCCESS` を第一候補に別経路で数える（README §7・§9 実装前確定条件）。本ビーコンは TEAM/ORG の機能利用傾向の計測のみに用いる。
- FE 送信規約（ゲート対象機能の**利用成功時**に送出。`useBilling` コンポーザブルに共通化）:

```jsonc
POST /api/v1/page-views
{ "scope": "TEAM", "scopeId": 123, "contentType": "FEATURE",
  "contentId": 0,                          // BIGINT のため 0 固定（PAGE の既存前例に整合）
  "url": "/teams/foo/reservations",        // 発火元パス
  "title": "reservation.notification_recipients_extended" }   // feature_key（VARCHAR(255) 内）
```

- 集計: ベータ中は生ログのアドホック集計（`WHERE content_type='FEATURE' GROUP BY scope_type, scope_id, title`）。`title` に INDEX が無いためフルパーティションスキャンになるが、**月次パーティション×ベータ規模（第 4 段階 1 万人）では許容**。恒常ダッシュボード化（専用集計テーブル・BE 二重記録）は Phase 2（F20.1 将来拡張）。
- USER スコープの機能利用は F10.8 が TEAM/ORGANIZATION スコープのみ対応のため**計測対象外**（第一弾の割り切り・Phase 2 の BE 記録で回収）。

---

## 6.5 通知の API 面確定（C・NotificationType 実値 × 文言キー 1:1）

アプリ内通知は origin/main 実在の **`NotificationHelper.notify(...)`** を使う。実シグネチャ（実物照合済み）:

```java
// 単一ユーザー（本人向け）
notificationHelper.notify(
    Long userId, String notificationType, String title, String body,   // ★title/body は解決済み String
    String sourceType, Long sourceId,
    NotificationScopeType scopeType, Long scopeId,
    String actionUrl, Long actorId);
// 複数ユーザー（運営向け等）は notifyAll(List<Long> userIds, String notificationType, ...) を使う
```

- **`notificationType` は `NotificationType` enum の `name()` 文字列**（VARCHAR 永続化・後方互換）。ベータ特典・org_type 用の値は**既存 enum に無いため新値を追加**する（実装スコープ・notification ドメイン作業）。
- **`title`/`body` は i18n キーではなく `MessageSource` で解決した String を渡す**（`ConfirmableNotificationService.send` と同様）。文言キーは `messages*.properties` 6 言語。

| notificationType（新値・要追加） | 用途 | 文言キー（messages*.properties・title/body） | priority |
|---|---|---|---|
| `BETA_PERK_GRANTED` | 特典付与（本人・§3） | `notification.beta_perk.granted.{title,body.individual\|body.teamOrg}` | NORMAL |
| `BETA_PERK_REVOKED` | 特典取消（本人・§4.2） | `notification.beta_perk.revoked.{title,body}` | HIGH |
| `BETA_PERK_EXTENDED` | 期間延長（本人・§4.3） | `notification.beta_perk.extended.{title,body}` | NORMAL |
| `BETA_PERK_REVIEW_FLAGGED` | 審査フラグ設定（運営向け・§5・notifyAll） | `notification.beta_perk.review_flagged.operations` | NORMAL |
| `ORG_TYPE_AUTO_UPDATED` | org_type 自動営利化（org ADMIN・F20.1 02 §7.2） | `notification.billing.org_type_auto_updated.{title,body}` | — (confirmable・HIGH) |
| `ORG_TYPE_REVIEW_REQUESTED` | 区分確認要請（org ADMIN・F20.1 02 §7.2） | `notification.billing.org_type_review_requested.{title,body}` | — (confirmable・HIGH) |

> `ORG_TYPE_*` は F20.1 側で **`ConfirmableNotificationService.send`（確認必須通知）** を使うため `NotificationType` enum ではなく確認通知の title/body として渡す（enum 追加は不要・messages キーのみ）。`BETA_PERK_*` は通常通知（`NotificationHelper.notify`）ゆえ enum 新値が必要。**この enum 追加は notification ドメイン作業として軍議のタスク分解に含める**。

---

## 7. DTO 一覧（骨子）

- `MyBetaPerksResponse` / `BetaGrantItem` / `EligibilityStatus` / `MetricProgress`（§1）
- `CreateBetaGrantRequest` / `BetaGrantDetailResponse`（§4.1）
- `RevokeBetaGrantRequest` / `ExtendBetaGrantRequest` / `BetaPerkCriteriaUpsertRequest`（§4.2〜4.6）
- Response DTO は `@Builder`・camelCase 1:1。`@Schema(name = "BetaPerk〜")` で OpenAPI 名衝突回避。全 final マルチコンストラクタ Request は `@JsonCreator`。

---

## 8. エラーコード（`BetaPerkErrorCode`・新規 enum）

> **採番注記**: `BETA_PERK_` プレフィックスは新設。**新規採番は現在の最大+1 で予約、確定はマージ時に再確認**（`git grep "BETA_PERK_0"` で並行 PR と照合）。`GlobalExceptionHandler.ERROR_CODE_STATUS_MAP` へ明示登録（登録漏れ 400/500 フォールバックの前科 #1279）。

| enum 値 | コード | HTTP | Severity | 意味 |
|---|---|---|---|---|
| `GRANT_NOT_FOUND` | `BETA_PERK_001` | 404 | WARN | grant が存在しない（IDOR 秘匿含む） |
| `GRANT_ALREADY_EXISTS` | `BETA_PERK_002` | 409 | WARN | 同一 scope×beta_phase に付与済み（uk_bg_scope_phase） |
| `ACTIVITY_CRITERIA_NOT_MET` | `BETA_PERK_003` | 422 | WARN | 付与条件未達（details に実測値/閾値） |
| `BETA_PHASE_INVALID` | `BETA_PERK_004` | 400 | WARN | beta_phase が 1〜4 以外 |
| `GRANT_ALREADY_REVOKED` | `BETA_PERK_005` | 409 | WARN | 取消済み grant への操作（revoke/extend/flag） |
| `REVIEW_NOT_FLAGGED` | `BETA_PERK_006` | 409 | WARN | review_flag=false への resolve-review |
| `GRANT_SCOPE_MISMATCH` | `BETA_PERK_007` | 422 | WARN | grant_kind×scope_kind の不整合（AC-16） |
| `EXTEND_NOT_APPLICABLE` | `BETA_PERK_008` | 422 | WARN | INDIVIDUAL（無期限）への延長操作 |
| `CRITERIA_VALIDATION_FAILED` | `BETA_PERK_009` | 400 | WARN | 条件マスタの全指標 NULL 等（無条件付与の防止） |
| `CRITERIA_NOT_FOUND` | `BETA_PERK_010` | 404 | WARN | 対象フェーズ×種別の criteria 未定義/enabled=false |

`GlobalExceptionHandler` への追記（設計に含む）:

```java
Map.entry("BETA_PERK_001", HttpStatus.NOT_FOUND),
Map.entry("BETA_PERK_002", HttpStatus.CONFLICT),
Map.entry("BETA_PERK_003", HttpStatus.UNPROCESSABLE_ENTITY),
Map.entry("BETA_PERK_004", HttpStatus.BAD_REQUEST),
Map.entry("BETA_PERK_005", HttpStatus.CONFLICT),
Map.entry("BETA_PERK_006", HttpStatus.CONFLICT),
Map.entry("BETA_PERK_007", HttpStatus.UNPROCESSABLE_ENTITY),
Map.entry("BETA_PERK_008", HttpStatus.UNPROCESSABLE_ENTITY),
Map.entry("BETA_PERK_009", HttpStatus.BAD_REQUEST),
Map.entry("BETA_PERK_010", HttpStatus.NOT_FOUND),
```

---

## 9. OpenAPI・生成型

- F20.1 と同じ（`@Schema(name=)` 衝突回避・BE マージ後 `docs/openapi.json` 再生成＋`npm run generate:types` を同一 PR・memory `project_openapi_json_chronic_drift`）。
