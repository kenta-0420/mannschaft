# F20.2 信任（信頼の輪）— 02. API設計

> **ステータス**: 🟢 設計完了（要裁可論点 §11 のマスター裁可待ち）
> 親: [README.md](README.md) ／ 関連: [01_data_model.md](01_data_model.md) / [03_security.md](03_security.md)

---

## 0. サービス構成（`com.mannschaft.app.trust`）

| クラス | 役割 |
|---|---|
| `TrustEndorsementController` | 信任の付与/取消・信任関係一覧（団体管理者・公開） |
| `TrustCertificationController` | 認証状態取得（公開） |
| `SystemAdminTrustController` | 運営 API（アンカー付与/解除・REVOKE・再審査キュー・再審査 OK/NG） |
| `TrustEndorsementService` | 信任付与/取消のトランザクション・資格判定・状態遷移の呼び出し |
| `TrustCertificationService` | 状態機械（`recalculateState`）・アンカー・REVOKE 連鎖（1 段） |
| `TrustEligibilityService` | 信任資格の最低条件判定（README §3.3・設立/メンバー数/年間上限） |
| `TrustCertificationQueryService` | 読み取り（認証状態・信任一覧・`isCertified()` F20.1 フック・README §7） |
| `TrustScopeResolver` | scope 所有権検証（IDOR 対策）・`TEAM/ORG → memberships.scope_type` マッピング（README §1.4） |
| `TrustBadgeVisibility` | `isBadgeVisible(state)`（`CERTIFIED`/`UNDER_REVIEW` → true・README §6）＋ **`publicState(state)`**（公開 DTO 用に `UNDER_REVIEW`→`CERTIFIED` に丸める・値域 3 値・§6.1・[03 §4.2](03_security.md)）を一元提供 |
| `TrustErrorCode` | エラーコード enum（§8） |

### 0.1 日時シリアライズ規約（全 DTO 共通・origin/main 実設定に準拠）

- **DB 保存は `LocalDateTime`（UTC）**（Entity 列・01 §3）。応答 DTO も `LocalDateTime` フィールドで保持する。
- **JSON シリアライズは既定 Jackson 設定（`config/JacksonConfig.java`）に従う**（trust 独自の `@JsonFormat` を付けない）。実設定は `WRITE_DATES_AS_TIMESTAMPS` 無効＋`JavaTimeModule`＋**`LocalDateTimeTimezoneSerializer`（`LocalDateTime` をリクエスト元ユーザーの TZ に変換して出力）**。したがって `LocalDateTime` は**数値でなく、ユーザー TZ に変換された offset 付き ISO-8601 文字列**で返る（例: JST ユーザーには `"2026-07-08T12:00:00+09:00"`・別 TZ ユーザーにはその TZ のオフセット）。
- 本設計の応答例に現れる `+09:00` はこの既定設定で JST ユーザーが受け取る形（**固定 offset を約束するものではなく viewer TZ 依存**）。API 契約テストは「ISO-8601 文字列で offset 付き・`WRITE_DATES_AS_TIMESTAMPS` 無効」を検証し、offset 値そのものは viewer TZ 前提で確認する。
- **公開 API（#3/#4・未ログイン）**は viewer TZ が取れないため既定 TZ（UTC 相当 or アプリ既定）で出力される（`LocalDateTimeTimezoneSerializer` の未認証時フォールバックに従う・独自分岐を作らない）。

---

## 1. エンドポイント一覧

| # | メソッド/パス | 用途 | 認可 |
|---|---|---|---|
| 1 | `POST /api/v1/trust/endorsements` | 信任の付与（endorser → endorsee） | **endorser 団体の scope ADMIN**（scopeId 所有権検証・§9） |
| 2 | `DELETE /api/v1/trust/endorsements/{endorsementId}` | 信任の取消（endorser 側操作） | **endorser 団体の scope ADMIN** |
| 3 | `GET /api/v1/trust/certifications?scopeKind=&scopeId=` | 認証状態取得 | **公開**（未ログイン可・対象 scope が F00 で PUBLIC 可視のときのみ・PRIVATE は 404 秘匿） |
| 4 | `GET /api/v1/trust/endorsements?scopeKind=&scopeId=&direction=` | 信任関係一覧（incoming/outgoing・公開） | **公開**（同上） |
| 5 | `GET /api/v1/trust/eligibility?scopeKind=&scopeId=` | 自団体の信任発行資格の事前確認（UI 活性制御用） | 対象団体の scope ADMIN |
| 6 | `POST /api/v1/system-admin/trust/anchors` | アンカー付与 | SYSTEM_ADMIN |
| 7 | `DELETE /api/v1/system-admin/trust/anchors?scopeKind=&scopeId=` | アンカー解除（通常団体として再評価） | SYSTEM_ADMIN |
| 8 | `POST /api/v1/system-admin/trust/certifications/revoke` | 認証取消（REVOKED・連鎖 1 段） | SYSTEM_ADMIN |
| 9 | `GET /api/v1/system-admin/trust/review-queue` | 再審査キュー一覧（`UNDER_REVIEW`） | SYSTEM_ADMIN |
| 10 | `POST /api/v1/system-admin/trust/review-queue/{certificationId}/approve` | 再審査 OK（`CERTIFIED` 復帰） | SYSTEM_ADMIN |
| 11 | `POST /api/v1/system-admin/trust/review-queue/{certificationId}/reject` | 再審査 NG（`REVOKED` へ） | SYSTEM_ADMIN |

> - パス接頭辞は新規ドメイン `trust` に合わせ `/api/v1/trust/...`・運営は既存 SystemAdmin 系と同型の `/api/v1/system-admin/trust/...`（`@PreAuthorize("hasRole('SYSTEM_ADMIN')")`）。
> - #3/#4 は未ログイン可の公開 API。SecurityConfig の permitAll は **GET のみ・1 階層 `*` パターン**（`/**` 再帰禁止・[03 §2](03_security.md)）。
> - 用語厳守: パス・DTO・フィールド名に `guarantee` / `approval` / `mutual` を使わない（NG語・README §1.2）。英語は `endorsement` / `certification` で統一。

---

## 2. 信任の付与 `POST /api/v1/trust/endorsements`

### 2.1 Request（`TrustEndorsementCreateRequest`）

| フィールド | 型 | null | 説明 | 例 |
|---|---|---|---|---|
| `endorserScopeKind` | String（enum `TEAM`/`ORG`） | 不可 | 信任元の種別 | `"TEAM"` |
| `endorserScopeId` | Long | 不可 | 信任元団体 ID（**操作者が ADMIN であることを検証**・§9） | `123` |
| `endorseeScopeKind` | String（enum `TEAM`/`ORG`） | 不可 | 信任先の種別 | `"ORG"` |
| `endorseeScopeId` | Long | 不可 | 信任先団体 ID | `45` |

```json
{ "endorserScopeKind": "TEAM", "endorserScopeId": 123, "endorseeScopeKind": "ORG", "endorseeScopeId": 45 }
```

> DTO は全 final フィールド＋単一コンストラクタなら `@JsonCreator` 必須（`feedback_dto_all_final_multi_constructor_jackson_no_creators`）。record を推奨。

### 2.2 Response 201（`TrustEndorsementResponse`）

| フィールド | 型 | null | 説明 | 例 |
|---|---|---|---|---|
| `endorsementId` | UUID(String) | 不可 | 作成された信任 ID | `"018f6a2e-..."` |
| `endorserScopeKind` | String | 不可 | | `"TEAM"` |
| `endorserScopeId` | Long | 不可 | | `123` |
| `endorserName` | String | 不可 | 信任元団体名（表示用） | `"FCマンシャフト"` |
| `endorseeScopeKind` | String | 不可 | | `"ORG"` |
| `endorseeScopeId` | Long | 不可 | | `45` |
| `endorseeName` | String | 不可 | 信任先団体名（表示用） | `"県サッカー協会"` |
| `grantedAt` | LocalDateTime（§0.1） | 不可 | 付与日時 | `"2026-07-08T12:00:00+09:00"` |
| `endorseeState` | String（enum） | 不可 | 付与後の endorsee の状態（3 件目なら `CERTIFIED`） | `"CERTIFIED"` |
| `endorseeValidEndorsementCount` | Integer | 不可 | 付与後の有効信任件数 | `3` |

### 2.3 処理（擬似コード・状態遷移 T1〜T5 を内包）

```
@Transactional（trust ドメイン内に閉じる・原則5）
1. 認可: TrustScopeResolver.requireScopeAdmin(currentUserId, endorserScopeKind, endorserScopeId)
     - 操作者が endorser 団体の ADMIN でなければ TRUST_009（403）。無関係 scope（存在秘匿要）は TRUST_007（404）
     - ⚠ getCurrentUserId() を scopeId に流用しない（userID→teamID IDOR 前科・docs/security/03_role_authority_model.md・03 §3）
2. 入力検証:
     - scope_kind が TEAM/ORG 以外（USER 等）→ TRUST_006（422）
     - endorser == endorsee（kind と id が両方一致）→ TRUST_002（422）
     - endorsee の実在＋F00 可視性確認（存在オラクル封鎖・03 §3）:
         ContentVisibilityChecker.canView(ReferenceType.TEAM|ORGANIZATION, endorseeScopeId, currentUserId)
         → 不在・削除済み・「endorser 管理者（操作者）から不可視」のいずれも**同一応答 TRUST_007（404）**
           （実在チェックだけにすると PRIVATE 団体の ID 総当り列挙オラクルになる・README §11-6 関連脅威）
3. endorser 資格判定（TrustEligibilityService.checkEligibility・§4）:
     - state != CERTIFIED（is_anchor 含む・UNDER_REVIEW は不可＝README §11-3 推奨(b)）→ TRUST_001（422）
     - 設立 N ヶ月未達 or established_date/precision NULL → TRUST_003（422・details に不足条件）
     - アクティブメンバー M 人未満 → TRUST_003（422）
     - 年間発行数 >= cap（直近12ヶ月・revoked_at IS NULL の COUNT・README §3.5 案B）→ TRUST_004（429）
4. 重複チェック: existsBy(endorser, endorsee, revokedAtIsNull) → あれば TRUST_005（409）
     （DB 生成列 UNIQUE uk_te_active が二重防御・並行 INSERT は一意制約違反を TRUST_005 に変換）
5. trust_endorsements INSERT（granted_at=now, granted_by_user_id=currentUserId）
6. endorsee の trust_certifications 行を SELECT ... FOR UPDATE（無ければ UNCERTIFIED で upsert）
7. TrustCertificationService.recalculateState(endorsee)（§5.1）:
     n = countByEndorseeAndRevokedAtIsNull(endorsee)
     UNCERTIFIED かつ n >= T(3) → CERTIFIED（certified_at=now・T2）
     UNDER_REVIEW かつ n >= T   → CERTIFIED（under_review_since=NULL クリア・T4）
     それ以外 → 状態不変（T1/T3/T5）
     valid_endorsement_count = n を同期
8. 通知イベント発火（AFTER_COMMIT・§7）: TrustEndorsementGrantedEvent（＋状態遷移時 TrustCertificationStateChangedEvent）
9. 監査ログ: audit_logs（TRUST_ENDORSEMENT_GRANTED / TRUST_STATE_CHANGED）
```

> **並行付与の直列化**: 手順 6 の endorsee 認証行 `FOR UPDATE` により、同一 endorsee への並行付与（2 件目と 3 件目の同時到着）でも状態遷移判定が直列化され、「3 件目で必ず 1 回だけ CERTIFIED 遷移」（AC-03）が保証される。

---

## 3. 信任の取消 `DELETE /api/v1/trust/endorsements/{endorsementId}`

### 3.1 Request

Path param のみ: `endorsementId`（UUID）。Body なし。

### 3.2 Response 200（`TrustEndorsementRevokeResponse`）

| フィールド | 型 | null | 説明 | 例 |
|---|---|---|---|---|
| `endorsementId` | UUID(String) | 不可 | 取消した信任 ID | `"018f6a2e-..."` |
| `revokedAt` | LocalDateTime（§0.1） | 不可 | 取消日時 | `"2026-07-08T12:34:56+09:00"` |
| `endorseeState` | String（enum） | 不可 | 取消後の endorsee の状態（降格すれば `UNDER_REVIEW`） | `"UNDER_REVIEW"` |
| `endorseeValidEndorsementCount` | Integer | 不可 | 取消後の有効信任件数 | `2` |

### 3.3 処理（擬似コード・T6〜T8）

```
@Transactional
1. endorsement を id で取得
     - 非存在 ID → TRUST_007（404 秘匿・存在を漏らさない）
2. 認可（存在オラクル封鎖のため状態判定より前に置く・03 §3.2）:
     requireScopeAdmin(currentUserId, endorsement.endorserScopeKind, endorsement.endorserScopeId)
     - 無権限（操作者が当該 endorser 団体の ADMIN でない）→ 一律 TRUST_007（404 秘匿）
       （※ endorsement は viewer に紐づかない他団体資産のため、権限差分を漏らさず 404 に統一する）
3. 状態判定（認可を通過した後にのみ実行）:
     - endorsement.revoked_at が既セット（存在するが取消済み）→ TRUST_008（409 ALREADY_REVOKED・AC-32）
4. endorsement.revoked_at=now, revoked_by_user_id=currentUserId, revoke_reason='MANUAL'
5. endorsee の trust_certifications 行を FOR UPDATE → recalculateState（§5.1）:
     CERTIFIED かつ非アンカー かつ n < T → UNDER_REVIEW（under_review_since=now・T6・マーク維持）
     CERTIFIED（アンカー）→ 不変（A2）
     UNCERTIFIED → 不変（T8）
     それ以外 → 不変（T7）
6. 通知イベント（AFTER_COMMIT）: 降格時 TrustCertificationStateChangedEvent（UNDER_REVIEW）
7. 監査ログ: TRUST_ENDORSEMENT_REVOKED（＋降格時 TRUST_STATE_CHANGED）
```

> **返却コードの分離（自己矛盾の解消）**: 「非存在 ID」と「存在するが取消済み」を明確に分ける — **非存在 ID → `TRUST_007`（404 秘匿）／存在するが `revoked_at` 既セット → `TRUST_008`（409）**（AC-32 は後者に対応）。認可（手順 2）を状態判定（手順 3）より**前**に置き、無権限は状態を漏らさず一律 404 秘匿とする（§3.2 の 404 秘匿思想と整合）。

---

## 4. 資格判定 `TrustEligibilityService`（内部・#5 の事前確認 API でも公開）

### 4.1 判定擬似コード（README §3.3/§3.4 の実装形）

```
checkEligibility(endorserScopeKind, endorserScopeId, clock):
  cert = trust_certifications.findByScope(endorser)          // 無ければ UNCERTIFIED 扱い
  if cert == null || cert.state != CERTIFIED:
      return NG(TRUST_001)                                    // UNDER_REVIEW も不可（README §11-3 (b)）

  // 設立 N ヶ月（precision 保守側丸め・README §3.4）
  (estDate, precision) = loadEstablished(endorser)            // teams/organizations の established_date(+precision)
  if estDate == null || precision == null:
      return NG(TRUST_003, reason=ESTABLISHED_DATE_UNVERIFIABLE)
  effective = switch (precision):
      FULL       -> estDate
      YEAR_MONTH -> estDate.with(TemporalAdjusters.lastDayOfMonth())
      YEAR       -> LocalDate.of(estDate.getYear(), 12, 31)
  if effective.plusMonths(minEstablishedMonths) > today(clock):
      return NG(TRUST_003, reason=ESTABLISHED_TOO_RECENT)

  // アクティブメンバー M 人（README §1.4 マッピング厳守・§3.3）
  // 既存 MembershipRepository.countActiveDistinctUsersByScope を再利用（新規 count メソッドを作らない）。
  // 「アクティブ」= 在籍（left_at IS NULL）ベースの DISTINCT user 数（同一 user の複数行を二重計上しない・
  //  role_kind 横断）。users.status との連動は行わない（実装 JavaDoc どおり user ドメインに委ねる）。
  scopeType = (endorserScopeKind == ORG) ? ScopeType.ORGANIZATION : ScopeType.TEAM
  activeCount = membershipRepository.countActiveDistinctUsersByScope(scopeType, endorserScopeId)
  if activeCount < minActiveMembers:
      return NG(TRUST_003, reason=INSUFFICIENT_ACTIVE_MEMBERS)

  // 年間発行上限（ローリング12ヶ月・案B・README §3.5）
  annual = trust_endorsements.countByEndorserAndRevokedAtIsNullAndGrantedAtAfter(
               endorser, now(clock).minusMonths(12))
  if annual >= annualEndorsementCap:
      return NG(TRUST_004)

  return OK(remainingAnnualQuota = annualEndorsementCap - annual)
```

- しきい値は `mannschaft.trust.*` config properties（`certification-threshold=3` / `min-established-months=6` / `min-active-members=5` / `annual-endorsement-cap=10`・README §3.3/§11-2/§11-4）。`Clock` 注入で境界テスト可能にする。

### 4.2 `GET /api/v1/trust/eligibility` Response 200（`TrustEligibilityResponse`）

| フィールド | 型 | null | 説明 | 例 |
|---|---|---|---|---|
| `eligible` | Boolean | 不可 | 信任を発行できるか | `true` |
| `state` | String（enum） | 不可 | 自団体の認証状態 | `"CERTIFIED"` |
| `establishedOk` | Boolean | 不可 | 設立 N ヶ月条件 | `true` |
| `activeMembersOk` | Boolean | 不可 | メンバー M 人条件 | `true` |
| `activeMemberCount` | Integer | 不可 | 現在のアクティブメンバー数 | `12` |
| `annualQuotaRemaining` | Integer | 不可 | 年間発行残数（`cap - 直近12ヶ月発行数`・下限 0） | `7` |
| `blockingReasons` | List\<String\>（enum） | 不可（空リスト可） | 未達理由 `NOT_CERTIFIED` / `ESTABLISHED_DATE_UNVERIFIABLE` / `ESTABLISHED_TOO_RECENT` / `INSUFFICIENT_ACTIVE_MEMBERS` / `ANNUAL_CAP_REACHED` の全値 | `[]` |

---

## 5. 状態機械の実装（`TrustCertificationService`）

### 5.1 `recalculateState`（唯一の状態遷移入口・README §5 の表と 1:1）

```
recalculateState(certRow /* FOR UPDATE 済 */, clock):
  n = countValidIncoming(certRow.scope)
  certRow.validEndorsementCount = n
  switch (certRow.state):
    UNCERTIFIED:
      if n >= T: certRow.state=CERTIFIED; certRow.certifiedAt ??= now     // T2（初回のみ記録）
    UNDER_REVIEW:
      if n >= T: certRow.state=CERTIFIED; certRow.underReviewSince=null   // T4
    CERTIFIED:
      if !certRow.isAnchor && n < T:
          certRow.state=UNDER_REVIEW; certRow.underReviewSince=now        // T6（マーク維持）
      // アンカーは降格しない（A2）
    REVOKED:
      // 何もしない（REVOKED からの復帰は運営 API のみ・本メソッドでは遷移させない）
  if 状態が変化した: publish TrustCertificationStateChangedEvent(old, new)  // AFTER_COMMIT
```

### 5.2 アンカー付与/解除（運営 #6/#7・A1/A3）

```
grantAnchor(scope):    upsert 行 FOR UPDATE → is_anchor=TRUE; state=CERTIFIED; certified_at ??= now  // A1
removeAnchor(scope):   FOR UPDATE → is_anchor=FALSE → recalculateState()                             // A3（n<T なら UNDER_REVIEW）
```

### 5.3 REVOKE 連鎖（運営 #8・T9/T11・README §3.7 の 1 段制限）

```
@Transactional
revoke(scope, reason, operatorUserId):
  1. cert = FOR UPDATE; cert.state=REVOKED; revoked_at=now; revoked_by=operator; revoke_reason=reason  // T9
  2. outgoing = trust_endorsements.findByEndorserAndRevokedAtIsNull(scope)
     for e in outgoing:
        e.revoked_at=now; e.revoked_by_user_id=operator; e.revoke_reason='ENDORSER_REVOKED'
  3. affected = outgoing.map(endorsee).distinct()
     for y in affected:                                   // 連鎖はこの 1 段のみ（AC-16）
        yCert = FOR UPDATE(y)（ロック順序: scope の (kind,id) 昇順で取得しデッドロック回避）
        recalculateState(yCert)                           // CERTIFIED→UNDER_REVIEW になりうる（AC-15）
        // ★ y が UNDER_REVIEW に落ちても y の outgoing は無効化しない（1 段で停止）
  4. 監査ログ・通知イベント（X 管理者へ REVOKED・各 y 管理者へ UNDER_REVIEW 変化）
```

### 5.4 団体削除カスケード（T12・AC-27・イベント購読）

団体（TEAM/ORG）の削除フローが発火する**実在のイベント**を trust ドメインが購読し、削除団体の outgoing 有効信任を無効化する（README §5.1 T12・[03 §5](03_security.md)）。

- **購読イベント（origin/main 実在確認済・2026-07-08）**:
  - `com.mannschaft.app.team.event.TeamDeletedEvent`（フィールド: `userId`=削除者・`teamId`）
  - `com.mannschaft.app.organization.event.OrganizationDeletedEvent`（同型）
- リスナ: `TrustEndorsementCascadeListener`（`@TransactionalEventListener(phase = AFTER_COMMIT)` ＋ `@Transactional(propagation = REQUIRES_NEW)`・`feedback_transactional_event_listener_requires_new`。削除 tx に trust 処理を巻き込まない・原則 5）。

```
@TransactionalEventListener(AFTER_COMMIT) + REQUIRES_NEW
onTeamDeleted(TeamDeletedEvent ev):   cascadeFromDeletedScope(TEAM, ev.teamId, ev.userId)
onOrganizationDeleted(...):           cascadeFromDeletedScope(ORG, ev.organizationId, ev.userId)

cascadeFromDeletedScope(kind, id, actorUserId):
  1. outgoing = trust_endorsements.findByEndorserAndRevokedAtIsNull(kind, id)
     for e in outgoing:
        e.revoked_at=now; e.revoked_by_user_id=actorUserId; e.revoke_reason='ENDORSER_DELETED'
  2. affected = outgoing.map(endorsee).distinct()
     for y in affected:                       // §5.3 と同じ 1 段制限・ロック順序（(kind,id) 昇順）
        yCert = FOR UPDATE(y); recalculateState(yCert)   // n<T なら UNDER_REVIEW（AC-27）
  3. 削除団体自身の trust_certifications 行は現状態のまま残す（団体は F00 不可視化されるため公開面には出ない・
     監査証跡として保持）。incoming（削除団体が受けていた信任）は endorser 側の有効 outgoing として残るが、
     公開一覧は F00 可視性フィルタ（§6.2）で削除団体を表示しない
  4. 監査ログ: TRUST_ENDORSEMENT_REVOKED（ENDORSER_DELETED）×件数 ＋ 各 y の TRUST_STATE_CHANGED
```

> 削除フロー側にイベント発火の追加実装は**不要**（`TeamDeletedEvent`/`OrganizationDeletedEvent` は既存の削除処理が既に発火している）。trust 側のリスナ追加のみで完結する。

#### 5.4.1 カスケードの耐障害性（通知と別格・整合バッチで補償）

信任カスケード（有効件数・認証状態の正しさに直結）は**通知（ベストエフォート）とは別格**に扱う。`AFTER_COMMIT`＋`REQUIRES_NEW` のリスナは、削除 tx コミット後にリスナが失敗すると「**削除済み団体の outgoing 有効信任が残留**」しうる（被信任先の有効件数が過大なまま＝認証の実体が崩れる）。これを次の二重防御で補償する:

1. **一次経路**: `TrustEndorsementCascadeListener`（§5.4）。失敗時はログ＋メトリクス（握り潰さない）。トランザクショナル・アウトボックス方式（削除イベントをアウトボックス表に記録→ワーカーが冪等に消化）を採ってもよい（実装判断）。
2. **補償経路（必須）**: 日次整合バッチ `TrustConsistencyBatch`（[03 §8](03_security.md)）に**新規検出条件**を追加する:
   - **(a) 孤児信任の検出・修復**: `trust_endorsements` の有効行（`revoked_at IS NULL`）のうち、`endorser`（scope_kind+scope_id）が team/org 側で**削除済み**のもの → `revoke_reason='ENDORSER_DELETED'` で無効化し、当該 endorsee の状態を再計算する（一次経路の取りこぼしを回収）。**削除済み判定は team/org の読み取り Service（例 `TeamQueryService.existsActive(id)` / `OrganizationQueryService`）経由で行う**（`teams`/`organizations` を trust から直接 JOIN しない・クロスドメイン FK/直接参照禁止・原則 1／原則 5）。
   - **(b) `valid_endorsement_count` ドリフト検出**（既存条件）: 実集計との突合。
   - 検出・修復はいずれもアラート付きで記録する（症状を隠さない・CLAUDE.md 根治原則）。

> カスケードは冪等（既に `revoked_at` セット済みなら再無効化しない・再計算は現在の有効件数から決定論的）なので、一次経路と補償バッチが同じ削除を二重処理しても結果は同一。

---

## 6. 読み取り API

### 6.1 認証状態取得 `GET /api/v1/trust/certifications?scopeKind=TEAM&scopeId=123`（公開）

**処理**: ① 対象 scope の実在＋F00 可視性確認（**`ContentVisibilityChecker.canView(ReferenceType.TEAM | ReferenceType.ORGANIZATION, scopeId, viewerUserIdOrNull)`**・未ログインは `userId=null`・不可視は TRUST_007/404 秘匿。実体は既存 `TeamVisibilityResolver`/`OrganizationVisibilityResolver` へのディスパッチ）→ ② `trust_certifications` を SELECT（行が無ければ `UNCERTIFIED` を合成して返す）→ ③ **公開 DTO では state を丸める**（下記）。

**Response 200（`TrustCertificationResponse`・公開用）**

| フィールド | 型 | null | 説明 | 例 |
|---|---|---|---|---|
| `scopeKind` | String | 不可 | | `"TEAM"` |
| `scopeId` | Long | 不可 | | `123` |
| `state` | String（enum） | 不可 | **公開用に丸めた状態**: `UNDER_REVIEW` は **`CERTIFIED` として返す**（値域は `UNCERTIFIED`/`CERTIFIED`/`REVOKED` の 3 値。丸めは `TrustBadgeVisibility.publicState(state)` に一元化） | `"CERTIFIED"` |
| `badgeVisible` | Boolean | 不可 | 認証マーク表示可否（`CERTIFIED`/`UNDER_REVIEW`=true・`TrustBadgeVisibility` 一元判定） | `true` |
| `isAnchor` | Boolean | 不可 | アンカーか（公開情報・アンカーは運営認証の証） | `false` |
| `certifiedAt` | LocalDateTime（§0.1） | 可 | 初回認証日時（未認証は null） | `"2026-07-01T09:00:00+09:00"` |
| `validEndorsementCount` | Integer | 不可 | 有効な incoming 信任件数 | `3` |

> - **公開 DTO は `UNDER_REVIEW` を生値で返さない**（返すと「UNDER_REVIEW は外形上 CERTIFIED と同一」の目標を破り、外部から再審査中の団体を識別できてしまう）。**生 state（4 値）は当該団体管理者向け（管理タブ・04 §4）と運営向け（§7.4）の認証済み DTO 限定**（[03 §4.2](03_security.md)）。
> - `UNDER_REVIEW`/`REVOKED` の内部事情（`under_review_since`/`revoke_reason` 等）は公開 DTO に含めない（[03 §4](03_security.md) 禁則）。

### 6.2 信任関係一覧 `GET /api/v1/trust/endorsements?scopeKind=&scopeId=&direction=INCOMING|OUTGOING|BOTH`（公開）

**処理**: §6.1 と同じ F00 可視性ゲート（`ContentVisibilityChecker.canView`）→ 有効信任（`revoked_at IS NULL`）のみ返す。**公開面（未ログイン/非関係者）は「相手方が viewer から F00 可視である信任」のみを一覧に含める**（安全側既定・[03 §4.1](03_security.md)・README §11-7。件数 `validEndorsementCount` は全件のまま）。相手方団体名は team/org 読み取り Service で解決し、`counterpartPublicSlug` は相手方が PUBLIC のときのみ返す。

- **`direction` クエリパラメータ（`INCOMING` / `OUTGOING` / `BOTH`）は任意。省略時の既定は `BOTH`**（enum バインド・不正値は 400）。

**Response 200（`TrustEndorsementListResponse`）**

> **配列契約（確定）**: `incoming` / `outgoing` は**常にレスポンスに存在し**、`direction` で対象外にした方向・該当データが無い方向は**空配列 `[]` を返す（`null` にしない）**。これにより FE は方向に関わらず `res.incoming` / `res.outgoing` を無条件で反復でき、null チェックが不要（`feedback_async_ordered_list_gate_render` の描画ゲートとも整合）。例: `direction=INCOMING` でも `outgoing: []` を返す。

| フィールド | 型 | null | 説明 |
|---|---|---|---|
| `incoming` | List\<`TrustEndorsementPublicItem`\> | **不可（常に存在・空時 `[]`）** | この団体を信任している団体（`direction=OUTGOING` 指定時は `[]`） |
| `outgoing` | List\<`TrustEndorsementPublicItem`\> | **不可（常に存在・空時 `[]`）** | この団体が信任している団体（`direction=INCOMING` 指定時は `[]`） |

`TrustEndorsementPublicItem`:

| フィールド | 型 | null | 説明 | 例 |
|---|---|---|---|---|
| `counterpartScopeKind` | String | 不可 | 相手方の種別 | `"ORG"` |
| `counterpartScopeId` | Long | 不可 | 相手方 ID | `45` |
| `counterpartName` | String | 不可 | 相手方団体名 | `"県サッカー協会"` |
| `counterpartBadgeVisible` | Boolean | 不可 | 相手方の認証マーク表示可否 | `true` |
| `counterpartPublicSlug` | String | 可 | 相手方の公開ページ slug（PUBLIC 団体のみ・PRIVATE は null） | `"pref-fa"` |
| `grantedAt` | LocalDateTime（§0.1） | 不可 | 信任日時 | `"2026-06-01T10:00:00+09:00"` |

> `endorsementId`・操作者 user_id は公開 DTO に**含めない**（取消 UI は管理者向け一覧 #5 系画面で別途 ID を得る。公開面には識別子を最小化）。管理者向けには同エンドポイントを認証付きで呼んだ場合のみ `endorsementId` を追加した `TrustEndorsementAdminItem` を返す（endorser ADMIN 判定・[03 §3](03_security.md)）。

---

## 7. 運営 API（`/api/v1/system-admin/trust/...`・SYSTEM_ADMIN）

### 7.1 アンカー付与 `POST /api/v1/system-admin/trust/anchors`

Request（`TrustAnchorGrantRequest`）:

| フィールド | 型 | null | 説明 | 例 |
|---|---|---|---|---|
| `scopeKind` | String（`TEAM`/`ORG`） | 不可 | 対象種別 | `"ORG"` |
| `scopeId` | Long | 不可 | 対象団体 ID | `45` |
| `note` | String(500) | 可 | 付与理由メモ（運営記録） | `"ベータ参加・活動実績確認済"` |

Response 200: `TrustCertificationResponse`（§6.1 と同型・`isAnchor=true`/`state=CERTIFIED`）。USER 指定は `TRUST_006`、団体不在は `TRUST_007`、既にアンカーなら冪等に 200（状態不変）。

### 7.2 アンカー解除 `DELETE /api/v1/system-admin/trust/anchors?scopeKind=&scopeId=`

Response 200: `TrustCertificationResponse`（A3 再評価後の状態）。アンカーでない団体への解除は `TRUST_010`（409 INVALID_STATE）。

### 7.3 REVOKE `POST /api/v1/system-admin/trust/certifications/revoke`

Request（`TrustRevokeRequest`）:

| フィールド | 型 | null | 説明 | 例 |
|---|---|---|---|---|
| `scopeKind` | String | 不可 | | `"TEAM"` |
| `scopeId` | Long | 不可 | | `123` |
| `reason` | String(500) | 不可 | REVOKE 理由（監査必須） | `"虚偽の団体情報を確認"` |

Response 200（`TrustRevokeResponse`）:

| フィールド | 型 | null | 説明 | 例 |
|---|---|---|---|---|
| `scopeKind` / `scopeId` | String / Long | 不可 | 対象 | |
| `state` | String | 不可 | 常に `"REVOKED"` | |
| `invalidatedEndorsementCount` | Integer | 不可 | 無効化した outgoing 信任件数 | `4` |
| `demotedToUnderReview` | List\<`TrustScopeRef`\>（`{scopeKind, scopeId, name}`） | 不可（空可） | 連鎖で `UNDER_REVIEW` になった被信任先（1 段） | |

既に `REVOKED` なら `TRUST_010`（409）。

### 7.4 再審査キュー `GET /api/v1/system-admin/trust/review-queue?page=&size=`

Response 200: `PagedResponse<TrustReviewQueueItem>`（`under_review_since` 昇順＝滞留の長い順）。

`TrustReviewQueueItem`:

| フィールド | 型 | null | 説明 |
|---|---|---|---|
| `certificationId` | UUID(String) | 不可 | `trust_certifications.id` |
| `scopeKind` / `scopeId` | String / Long | 不可 | 対象団体 |
| `name` | String | 不可 | 団体名 |
| `validEndorsementCount` | Integer | 不可 | 現在の有効信任件数 |
| `certifiedAt` | LocalDateTime（§0.1） | 不可 | 初回認証日時 |
| `underReviewSince` | LocalDateTime（§0.1） | 不可 | キュー投入日時 |
| `incomingEndorsers` | List\<`TrustScopeRef`\> | 不可 | 現在の有効信任元（レビュー材料） |

### 7.5 再審査 OK/NG `POST .../review-queue/{certificationId}/approve` / `/reject`

- `approve`: `UNDER_REVIEW → CERTIFIED`（T10・`n < T` でも運営裁量で復帰可）。Body 任意 `{ "note": "..." }`。
- `reject`: `UNDER_REVIEW → REVOKED`（T11・§5.3 の連鎖を実行）。Body 必須 `{ "reason": "..." }`（欠落は 400）。
- 対象が `UNDER_REVIEW` でない場合は `TRUST_010`（409）。Response 200: `TrustCertificationResponse` / `TrustRevokeResponse`。

---

## 8. エラーコード（`TrustErrorCode`・新 enum）

> `common.ErrorCode` 実装・`GlobalExceptionHandler` の `ERROR_CODE_STATUS_MAP` に**明示登録**すること（登録漏れは既定 400/500 フォールバックで事故る・#1279 前科）。
> **採番注記**: `TRUST_0xx` は新規系統（既存と衝突しない）。**新規採番は現在の最大+1 で予約し、確定はマージ時に再確認**すること（並行 PR で `TrustErrorCode` 系統を先取りされていないか `grep -r "TRUST_0" backend/` で裏取り）。

| コード | 定数名 | HTTP | Severity | 意味 | AC 対応 |
|---|---|---|---|---|---|
| `TRUST_001` | `ENDORSER_NOT_CERTIFIED` | 422 | WARN | 信任元が未認証（`state != CERTIFIED`・`UNDER_REVIEW` 含め発行不可） | AC-04 |
| `TRUST_002` | `SELF_ENDORSEMENT_FORBIDDEN` | 422 | WARN | 自己信任 | AC-05 |
| `TRUST_003` | `ELIGIBILITY_NOT_MET` | 422 | WARN | 資格未達（設立 N ヶ月／メンバー M 人／設立日検証不能。details に `blockingReasons`） | AC-09/10 |
| `TRUST_004` | `ANNUAL_ENDORSEMENT_CAP_EXCEEDED` | 429 | WARN | 年間信任発行数の上限超過 | AC-07/08 |
| `TRUST_005` | `DUPLICATE_ENDORSEMENT` | 409 | WARN | 重複信任（有効な同一 endorser→endorsee が既存） | AC-06 |
| `TRUST_006` | `INVALID_SCOPE_KIND` | 422 | WARN | 対象スコープ不正（USER 等・TEAM/ORG 以外） | AC-11 |
| `TRUST_007` | `TRUST_RESOURCE_NOT_FOUND` | 404 | WARN | 団体/信任/認証行が存在しない（または scope 不一致・F00 不可視の**秘匿 404**・取消時の無権限秘匿・付与時の存在オラクル封鎖） | AC-12/13/23/34、存在オラクル（§2.3）、取消の非存在/無権限（§3.3） |
| `TRUST_008` | `ENDORSEMENT_ALREADY_REVOKED` | 409 | WARN | 既に取消済みの信任への取消要求 | AC-32 |
| `TRUST_009` | `TRUST_FORBIDDEN` | 403 | WARN | 認可エラー（endorser 管理者でない・運営権限なし。※対象の存在を漏らしてよい面での 403。取消 API は秘匿優先で 404=TRUST_007 を用いる・§3.3） | AC-12/13/21 |
| `TRUST_010` | `INVALID_CERTIFICATION_STATE` | 409 | WARN | 状態不整合の運営操作（非 UNDER_REVIEW への approve/reject・REVOKED 二重化・非アンカー解除等） | AC-33/AC-30 |

**GlobalExceptionHandler への追記（設計に含む）**:

```java
// GlobalExceptionHandler.ERROR_CODE_STATUS_MAP へ追記
Map.entry("TRUST_001", HttpStatus.UNPROCESSABLE_ENTITY),
Map.entry("TRUST_002", HttpStatus.UNPROCESSABLE_ENTITY),
Map.entry("TRUST_003", HttpStatus.UNPROCESSABLE_ENTITY),
Map.entry("TRUST_004", HttpStatus.TOO_MANY_REQUESTS),
Map.entry("TRUST_005", HttpStatus.CONFLICT),
Map.entry("TRUST_006", HttpStatus.UNPROCESSABLE_ENTITY),
Map.entry("TRUST_007", HttpStatus.NOT_FOUND),
Map.entry("TRUST_008", HttpStatus.CONFLICT),
Map.entry("TRUST_009", HttpStatus.FORBIDDEN),
Map.entry("TRUST_010", HttpStatus.CONFLICT)
```

---

## 9. 認可・冪等性の要点（詳細は 03）

- **信任付与/取消の認可** = 「操作者が endorser 団体の scope ADMIN」: 既存ガード Bean `@Component("accessGuard")` の `isScopeAdmin(authentication, scopeId, scopeType)`（SYSTEM_ADMIN or 当該 scope の ADMIN/DEPUTY_ADMIN を内包・`AccessControlService` 委譲）を `TrustScopeResolver` 経由で kind 別に呼ぶ（TEAM は `'TEAM'`・ORG は `'ORGANIZATION'` を渡す・**取り違えない**・[03 §3.0](03_security.md)）。`endorserScopeId` はリクエスト値であり、**必ず所有権検証を通す**（`getCurrentUserId()` の値を scopeId に流用する誤りは IDOR 前科・`project_matching_authz_userid_as_teamid_idor`）。取消（#2）は `endorsementId` から **DB の endorser scope を解決**して判定する（子リソース ID を信頼しない一般形）。
- **運営 API** = `@PreAuthorize("hasRole('SYSTEM_ADMIN')")`（クラスレベル・既存 SystemAdmin 系 Controller と同型）。
- **冪等性**: 付与は DB 生成列 UNIQUE（`uk_te_active`）＋アプリ重複チェックの二重防御で「二重付与＝TRUST_005」。取消は `revoked_at` 既セットなら `TRUST_008`（再実行安全）。アンカー付与は冪等（既アンカーで 200 no-op）。状態遷移は endorsee 行 `FOR UPDATE` で直列化（§2.3）。
- **通知**: 状態遷移・付与イベントは `@TransactionalEventListener(phase = AFTER_COMMIT)` ＋ 別 tx（`REQUIRES_NEW`・`feedback_transactional_event_listener_requires_new`）で notification ドメインへ渡す（trust の tx に notification を巻き込まない・原則 5）。

---

## 10. テスト方針（試練・test-first 先行）

- **状態遷移**: AC-01〜03（2 件で不変・3 件目で CERTIFIED・4 件目で不変）・AC-14（取消で UNDER_REVIEW・マーク維持）・AC-17（回復で CERTIFIED・certified_at 不変）・AC-18（未到達は降格対象外）・AC-19（アンカー不降格）・**AC-28（T5: UNDER_REVIEW で 3 未満のままなら不変）**・**AC-29（T7: 4→3 では降格しない・3→2 で降格）**。
- **アンカー**: AC-20（付与 A1）・**AC-30（解除 A3: n≥T で CERTIFIED 維持／n<T で UNDER_REVIEW・非アンカーへの解除は TRUST_010）**・**AC-31（A4: アンカーへの REVOKE 有効・連鎖実行）**。
- **資格・異常系**: AC-04（TRUST_001・state=CERTIFIED 以外の 3 状態を各々検証）・AC-05（TRUST_002・DB CHECK も）・AC-06（TRUST_005・並行 INSERT の UNIQUE も）・AC-07/08（TRUST_004・**Clock 固定の統合テスト**で境界 9→10→11・ローリング 12 ヶ月窓は Clock 注入で時刻制御）・AC-09/10（TRUST_003・precision=YEAR/NULL の保守側丸め境界: `established_date=2026-01-01, precision=YEAR` は 2026-12-31 起点・人数は `countActiveDistinctUsersByScope` の DISTINCT 挙動＝同一 user 複数行を 1 と数える）・AC-11（TRUST_006）・**AC-32（TRUST_008: 取消済みへの再取消は 409・状態不変）**・**AC-33（TRUST_010: 非 UNDER_REVIEW への approve/reject・REVOKED 二重化は 409）**。
- **連鎖**: AC-15（REVOKE→outgoing 全無効化→被信任先 UNDER_REVIEW）・AC-16（1 段停止＝孫は不変）・**AC-27（団体削除: `TeamDeletedEvent`/`OrganizationDeletedEvent` 発火→outgoing が `revoke_reason='ENDORSER_DELETED'` で無効化→被信任先の state 再計算・§5.4）**。
- **認可（契約テスト）**: AC-12/13（非管理者 403・無関係 scope 404 秘匿・scopeId 詐称 IDOR）・AC-21（非 SYSTEM_ADMIN 403）・AC-23（PRIVATE 団体の認証状態は未ログインに 404）・**AC-34（eligibility API: eligible/blockingReasons 全列挙・非管理者 403/404）**・**存在オラクル（付与時: 不在 endorsee と endorser から不可視の PRIVATE endorsee が同一応答 TRUST_007 であること・§2.3）**。
- **並行**: 同一 endorsee への並行 3 件目付与で CERTIFIED 遷移がちょうど 1 回（FOR UPDATE 直列化）。
- **公開 DTO 禁則**: 公開レスポンスに `under_review_since`/`revoke_reason`/`granted_by_user_id`/`endorsementId` を含まないこと。**公開 `state` に `UNDER_REVIEW` の生値が現れないこと**（`UNDER_REVIEW` の団体で公開 API が `CERTIFIED` を返すことを検証・§6.1）（[03 §4](03_security.md)）。
- ポートは `@SpringBootTest(webEnvironment = RANDOM_PORT)` + Testcontainers 自動採番（ポート固定禁止）。年間上限・設立判定など時間依存テストはすべて `Clock` 注入で決定論化する。
