# F10.1.1 / 03: 管理者向け横断「承認待ち」集約 API 設計

> **ステータス**: 🟢 設計完了
> **最終更新**: 2026-06-17
> **関連**: [README.md](./README.md) / [04_security_authorization.md](./04_security_authorization.md) / [F22.1_swipe_scope_dashboard/02_api_design.md](../F22.1_swipe_scope_dashboard/02_api_design.md)（ScopeActionRequiredFacade の手本）

本書は管理者向けの**横断承認待ち集約 API** を新規設計する。既存のメンバー向け `action-required`（回覧/アンケ/出欠の自分宛の未対応。実体は `com.mannschaft.app.dashboard.service.ScopeActionRequiredFacade`）とは**別物**であり、こちらは「ADMIN/DEPUTY が処理すべき承認タスク」をドメイン横断で集約する。

> **偵察に基づく事実確定（2026-06-17）**: 本書の集約元ドメインは、実コードを偵察して有効なもののみに確定した。先行版が前提とした「固定5ドメイン（reservation/shift/matching/payment/membership_application）が常に team・org 両方で取れる」は **誤りであった**。実際には (a) reservation は team 専用（`organization_id` 列も `OrganizationReservationController` も存在しない）、(b) team/org への「入会申請」ドメインは存在しない（`MembershipApplicationQueryService` は実在せず、参加申請があるのは village の `VillageJoinRequestService` のみ）、(c) payment には「承認待ち（PENDING_APPROVAL）」ワークフローが存在しない。これらを踏まえ、**スコープ別に有効なドメインのみを動的に集約する**設計に全面是正した（§3.2 / §4.4）。

---

## 1. メンバー向け action-required との違い

| 項目 | メンバー向け `action-required`（F22.1 既存） | 管理者向け `admin-action-required`（本書・新規） |
|------|------------------------------------------|--------------------------------------------|
| エンドポイント | `GET /api/v1/dashboard/{team\|org}/{id}/action-required` | `GET /api/v1/dashboard/{team\|org}/{id}/admin-action-required` |
| 視点 | 「私が回答/確認すべきこと」 | 「管理者が承認/処理すべきこと」 |
| 対象 | 回覧（未確認）・アンケート（未回答）・出欠（未回答） | 予約承認待ち・シフトリクエスト・マッチング申込（**スコープ別に有効なドメインのみ**・§3.2） |
| 認可 | スコープメンバー（MEMBER 以上） | **ADMIN / DEPUTY_ADMIN のみ** |
| ファサード | `ScopeActionRequiredFacade`（既存） | `AdminActionRequiredFacade`（新規） |

両者は別エンドポイント・別ファサード・別認可とし、混同しない。

---

## 2. エンドポイント

| 状態 | メソッド | パス | 認証 | 説明 |
|------|---------|-----|------|------|
| 🟢 | GET | `/api/v1/dashboard/team/{teamId}/admin-action-required` | 必要（ADMIN/DEPUTY） | チームの横断承認待ち集約 |
| 🟢 | GET | `/api/v1/dashboard/organization/{orgId}/admin-action-required` | 必要（ADMIN/DEPUTY） | 組織の横断承認待ち集約 |

> **凡例**: 🟢 = 本書で設計確定（実装は P1 フェーズで行う）。🟡 マークは「設計未確定・要検討」を意味するため本書では使わない（全エンドポイントが設計確定済み）。
>
> パスを F22.1 のメンバー向け `action-required` と同じ `/dashboard/{scope}/{id}/` 名前空間に置き、接尾辞を `admin-action-required` とすることで「ダッシュボード集約 API ファミリー」として一貫させる。Controller は新規 `AdminActionRequiredController`。

### 2.1 クエリパラメータ

| パラメータ | 型 | 必須 | 説明 |
|-----------|---|------|------|
| `preview_size` | Integer | No | 各ドメインのプレビュー件数（デフォルト 3、最大 5）。0 を指定すると件数のみ返す（L1 レンズ・ハブのバッジ用） |

---

## 3. レスポンス DTO

### 3.1 形

スコープによって有効なドメインが異なる（§3.2）。以下はチームスコープの例（team では reservation/shift_request/matching の3ドメインが有効）。

```json
{
  "data": {
    "scope_type": "TEAM",
    "scope_id": 12,
    "total_pending": 6,
    "domains": [
      {
        "domain": "RESERVATION",
        "pending_count": 2,
        "degraded": false,
        "list_route": "/teams/dev-team/admin/reservations?status=PENDING",
        "items": [
          { "id": "33", "title": "コートA 6/20 10:00", "requested_by": "山田太郎", "requested_at": "2026-06-17T09:00:00+09:00", "detail_route": "/teams/dev-team/admin/reservations/33" }
        ]
      },
      {
        "domain": "SHIFT_REQUEST",
        "pending_count": 3,
        "degraded": false,
        "list_route": "/teams/dev-team/admin/shifts?tab=requests",
        "items": [ { "id": "88", "title": "6/22 早番 交代希望", "requested_by": "佐藤花子", "requested_at": "2026-06-16T18:00:00+09:00", "detail_route": "/teams/dev-team/admin/shifts/swap/88" } ]
      },
      {
        "domain": "MATCHING",
        "pending_count": 1,
        "degraded": false,
        "list_route": "/teams/dev-team/admin/matching?tab=received",
        "items": [ { "id": "5", "title": "練習試合の申込", "requested_by": "鈴木一郎", "requested_at": "2026-06-15T12:00:00+09:00", "detail_route": "/teams/dev-team/admin/matching/5" } ]
      }
    ]
  }
}
```

組織スコープのレスポンスは `domains` に **PAYMENT のみ**を含む（§3.2 の表参照。reservation/shift/matching は org スコープに実体がないため配列に含めない）。

### 3.2 スコープ別の有効ドメイン集合（動的）

**先行版の「固定5ドメイン・0件でも常に配列に含める」契約を撤廃する。** 各ドメインの集約元の実体（偵察で確定）に従い、スコープ別に有効なドメインのみを `domains[]` に含める。

| domain | team で有効か | organization で有効か | 根拠（実コード偵察） |
|--------|:-----------:|:--------------------:|-------------------|
| `RESERVATION` | ✅ | ❌ | `ReservationEntity` は `teamId` のみ・`organization_id` 列なし。`TeamReservationController` のみ存在し `OrganizationReservationController` は存在しない。org で承認待ち予約は取得不能 |
| `SHIFT_REQUEST` | ✅ | ❌ | `ShiftChangeRequestEntity`/`ShiftSwapRequestEntity` は schedule→team 経由の team スコープ。org スコープのシフトはない |
| `MATCHING` | ✅ | ❌ | `MatchRequestEntity.teamId` / `MatchProposalEntity.proposingTeamId`。team スコープのみ。org の試合マッチングは実装なし |
| `PAYMENT` | ❌ | ✅ | `PaymentRequestEntity` は `issuer_scope_kind=ORG` が請求を発行し `payer_scope_kind=TEAM` が受信。「組織が発行した請求の処理状況」は org スコープで意味を持つ（§3.4 で承認待ちの定義を限定） |
| `MEMBERSHIP_APPLICATION` | ❌ | ❌ | team/org への「入会申請」ドメインは**存在しない**（招待 `InviteService` のみ）。集約対象から除外。村（village）の入村申請は別スコープ（`/villages/...`）であり本コンソール（team/org）の対象外 |

**結論**:
- **team スコープ**の `domains[]` = `RESERVATION` / `SHIFT_REQUEST` / `MATCHING` の3ドメイン。
- **organization スコープ**の `domains[]` = `PAYMENT` の1ドメイン。
- スコープに対して無効なドメインは `domains[]` に**含めない**（`enabled:false` で空枠を出すこともしない）。FE はレスポンスの `domains` 配列をそのまま描画すればよく、固定順の空枠を仮定しない。

### 3.3 フィールド定義

- `total_pending`: レスポンスに含まれる（=当該スコープで有効な）全ドメインの `pending_count` の合計。L1 レンズ ③ / L2 ハブの「承認待ち」バッジに表示。**ただし `degraded: true` のドメインの件数は不確定のため `total_pending` には加算しない**（0 件と集計失敗を区別する。§4.3）。
- `domains[]`: 当該スコープで有効なドメインのみ（§3.2）。`pending_count` が 0 のドメインも、有効でありさえすれば配列に含める（FE が枠を描画できる）。無効ドメインは含めない。
- `domain`: enum（`RESERVATION` / `SHIFT_REQUEST` / `MATCHING` / `PAYMENT`）。
- `degraded`: boolean（camelCase ではなく snake_case の `degraded`。API は snake_case 規約・§3.5）。当該ドメインの集計が**一時障害（DB 接続断・タイムアウト）で取得できなかった**場合のみ `true`。FE は当該ドメインを「集計失敗（再試行可）」として 0 件と区別して表示する（§4.3）。認可エラー・プログラミングエラーでは `degraded` は立たず、API 全体が当該ステータスを返す（握りつぶさない・§4.3）。
- `list_route`（DomainSection 単位）: FE が遷移する**一覧ルート**文字列（status / tab 付き）。当該ドメインの承認待ち一覧へ飛ぶ。**BE がスラッグを解決して返す**（FE が ID から再構築しない）。スラッグ解決は他ドメイン Entity を直接参照せず `TeamService.getSlugsByIds(ids): Map<Long,String>` 等のプリミティブ返却 Service メソッド経由（ArchUnit 越境依存 D-1 を避ける。メモリ `project_slug_e2e_open_issues` の教訓）。ルートは §01.4 の実在ルートに整合させる（例: team のシフト承認待ちは `/teams/{slug}/admin/shifts?tab=requests`）。
- `detail_route`（PreviewItem 単位）: その**1 件の個別遷移先**ルート文字列。`list_route` と**別物**で、status 等のクエリではなく**主キー（id）をパスに含めて**個別画面に飛ぶ（例 `/teams/{slug}/admin/reservations/{id}`・`/teams/{slug}/admin/matching/{id}`・`/organizations/{slug}/admin/payments/{id}`）。`list_route` 同様に BE がスラッグ・主キーを解決して返す。
- `items[]`: `preview_size` 件までのプレビュー。`id` は対象ドメインの主キーを**文字列化**して返す（BIGINT/UUID を JSON 数値ではなく文字列で統一）。**合成 id（`change:{id}` 等の種別接頭辞付き）は使わない**。同一ドメイン内に複数種別があるシフトドメインでは、`id` は各種別テーブルの主キー文字列とし、**種別（変更依頼／交代申請）の判別は `detail_route` のパスで吸収する**（変更依頼 → `/teams/{slug}/admin/shifts/change/{id}`、交代申請 → `/teams/{slug}/admin/shifts/swap/{id}`）。これにより id 契約（=主キー文字列）を保ったまま種別ごとの個別遷移を成立させる。

### 3.4 各ドメインの「承認待ち」の定義（実コード準拠）

| domain | 集約元（既存実装） | 承認待ちの定義 | 新設の要否 |
|--------|------------------|--------------|----------|
| `RESERVATION` | `ReservationRepository.countByTeamIdAndStatus(teamId, PENDING)` ほか（実在） | `status = PENDING` の予約 | 件数は既存メソッドで取得可。プレビュー用の `LIMIT` クエリのみ薄く新設 |
| `SHIFT_REQUEST` | `ShiftChangeRequestService`（`ChangeRequestStatus.OPEN`）+ `ShiftSwapService`（`SwapRequestStatus.PENDING`） | OPEN のシフト変更依頼 + PENDING のシフト交代申請の合算 | **team 単位の集約クエリを新設**（既存は scheduleId 単位 / status 単位）。`ShiftRequestAdminQueryService.pendingForTeam(teamId, previewSize)` を読み取り専用で追加 |
| `MATCHING` | `MatchProposalRepository.findByRequestIdAndStatus(..., PENDING)`（実在・申込単位） | 自チームの募集に届いた `MatchProposalStatus.PENDING` の応募 | **募集側（受け手）視点の team 単位集約クエリを新設**（既存は応募者視点 `proposingTeamId` のみ）。`MatchingAdminQueryService.pendingReceivedForTeam(teamId, previewSize)` を追加 |
| `PAYMENT` | `PaymentRequestRepository.findByIssuerScopeKindAndIssuerScopeIdAndStatusInAndDeletedAtIsNull(ORG, orgId, statuses, pageable)`（実在） | **組織が発行し、まだ支払い完了していない請求**（`status ∈ {SENT, VIEWED, OVERDUE}`）の件数＝「組織が回収状況を追うべき対象」。payment ドメインには「承認・却下」の双方向ワークフローは存在しないため、本ドメインは「承認待ち」ではなく**「処理状況の追跡が必要な未完了請求」**として集約する（名称は UI で「未収の請求」等に i18n） | 件数は **StatusIn 版 count を 1 本薄く新設**（`countByIssuerScopeKindAndIssuerScopeIdAndStatusInAndDeletedAtIsNull`）して 1 COUNT で集計（§4.5）。プレビューは既存 StatusIn 版 find を流用 |

> いずれの新設も**読み取り専用クエリメソッド**であり、承認ロジック・トランザクション・監査ログには一切触れない（CLAUDE.md 原則5・障害対応原則3「未実装は未実装として実装する」）。承認の実行は各ドメインの既存 API を L3 セクションで呼ぶ（§3.5）。

### 3.5 「件数＋導線」が主目的

本 API は**承認の実行 API ではない**。承認・却下はそれぞれのドメインの既存 API（予約 `POST .../confirm`、シフトリクエスト承認、マッチング承認等）を L3 セクションで呼ぶ。本 API は「どこに何件あるか」を集約して L1/L2/L3 ハブに供給するだけ。これにより、承認ロジック・トランザクション・監査ログは各ドメインに閉じ（CLAUDE.md 原則5）、集約 API は読み取り専用に保たれる。

API レスポンスのフィールド命名は **snake_case**（プロジェクトの REST 規約）。FE は受信後に camelCase へ変換し、後述の受信型（[02](./02_admin_lens_widgets.md) §6 で定義する `AdminActionRequiredSummary`）で消費する。

---

## 4. ファサード設計 `AdminActionRequiredFacade`

### 4.1 配置

- パッケージ: `com.mannschaft.app.dashboard.service`（既存 `ScopeActionRequiredFacade` と同居）
- クラス: `AdminActionRequiredFacade`

### 4.2 集約方式

```java
public AdminActionRequiredResponse getAdminActionRequired(
        Long userId, String scopeType, Long scopeId, int previewSize) {

    // ① 入口認可（二重防御の1段目）。認可違反はここで COMMON_002 を投げ、伝播させる（縮退しない）
    accessControlService.checkAdminOrAbove(userId, scopeId, scopeType);

    // ② スコープ別に有効なドメインの Query Service だけを並行呼び出し（§3.2）
    //    各 Service は内部でも WHERE に scope_id を含める（集計バイパス禁止）
    List<DomainTask> tasks = "TEAM".equals(scopeType)
        ? List.of(reservationTask, shiftRequestTask, matchingTask)   // team: 3ドメイン
        : List.of(paymentTask);                                       // org : 1ドメイン

    // ③ 各タスクを並行実行。一時障害（DataAccessException/TimeoutException）のみ縮退対象。
    //    縮退時は当該ドメインを pending_count=0・items=[]・degraded=true で返し、WARN ログ。
    //    認可例外（COMMON_002）・NPE 等のプログラミングエラーは縮退させず再スロー（症状を隠さない）。
    ...
}
```

### 4.3 縮退（degradation）の限定とフェイルオープン禁止範囲【C3 根治】

CLAUDE.md 障害対応原則（症状を隠さない・対処療法禁止）に従い、縮退の範囲を厳密に限定する。

| 例外種別 | 扱い | 理由 |
|---------|------|------|
| `DataAccessException`（DB 接続断・SQL タイムアウト）/ `TimeoutException`（並行集計のタイムアウト） | **当該ドメインのみ縮退**（`pending_count=0`・`items=[]`・`degraded=true`）。WARN ログ。`total_pending` に加算しない | 一時障害で集約 API 全体を落とすと、他ドメインの承認待ちも見えなくなり運用が止まる。当該ドメインだけ「集計失敗」と正直に表示する |
| 認可例外（`BusinessException(COMMON_002)`） | **縮退せず再スロー**（API は 403 を返す） | 認可エラーを握りつぶすと権限のないユーザーにデータ有無を漏らす。各ドメイン Service の per-scope 認可違反は本来起きない（入口で `checkAdminOrAbove` 済）が、起きたなら不正状態であり正直に失敗させる |
| プログラミングエラー（`NullPointerException`・`IllegalStateException` 等） | **縮退せず再スロー**（API は 500 を返す） | バグを縮退で隠すと検知できない。500 で顕在化させ根治する |

- `degraded: true` のドメインは FE で「集計に失敗しました（再試行）」のバッジ＋再読込導線を出す。**0 件（緑のチェック）とは視覚的に区別する**。これにより「壊れているのに 0 件に見える」事故を防ぐ。
- 縮退は「一時障害でも他ドメインの可視性を守る」ための限定的措置であり、恒久的なエラー隠蔽ではない。WARN ログとメトリクス（`admin_action_required_degraded_total{domain}`）で監視する。

### 4.4 各ドメインの集約元 Query Service（実在クラス基準）

| domain | スコープ | Query Service（新設 or 既存） | 集約元の実在クラス |
|--------|---------|----------------------------|------------------|
| `RESERVATION` | team | `ReservationAdminQueryService.pendingForTeam`（プレビュー用に薄く新設） | `ReservationRepository`（`countByTeamIdAndStatus` 実在）/ `TeamReservationController` |
| `SHIFT_REQUEST` | team | `ShiftRequestAdminQueryService.pendingForTeam`（新設） | `ShiftChangeRequestService` / `ShiftSwapService`（実在・team 単位集約は新設） |
| `MATCHING` | team | `MatchingAdminQueryService.pendingReceivedForTeam`（新設） | `MatchProposalService` / `MatchProposalRepository`（実在・募集側視点は新設） |
| `PAYMENT` | organization | `PaymentAdminQueryService.unsettledForOrg`（薄く新設） | `PaymentRequestService.findForOrg` / `PaymentRequestRepository`（実在） |

> 各 Query Service は当該ドメインのパッケージに属し、ファサードはそれらの戻り値をメモリ上で合成するのみ。ファサード自身は SQL を発行しない。team/org への入会申請（MEMBERSHIP_APPLICATION）の集約元は**存在しないため新設しない**（招待のみ・§3.2）。

### 4.5 N+1 回避

- 各 Query Service の `pendingForXxx(scopeId, previewSize)` は **1〜2 クエリで件数とプレビューを返す**実装契約とする（件数は `COUNT`、プレビューは `LIMIT previewSize` の1クエリ）。プレビュー要素の `requested_by`（表示名）は ID リストをまとめて1回の `UserService.getDisplayNames(ids)` でバルク解決し、要素ごとの個別 SELECT を禁止する。
- スラッグ解決も同様にバルク（`TeamService.getSlugsByIds(ids): Map<Long,String>`・プリミティブ返却）。

### 4.6 `@Transactional` 境界

- ファサードは**読み取り集約のためトランザクション不要**（複数ドメインをまたぐ `@Transactional` を張らない・CLAUDE.md 原則5）。各 Query Service 呼び出しに委ねる。

---

## 5. DB 設計 — 新規テーブルなし

本 API は既存ドメインの既存テーブル（reservations / shift_change_requests / shift_swap_requests / match_proposals / payment_requests）を**集計参照するのみ**で、新規テーブル・新規カラム・Flyway マイグレーションを伴わない。

- 各ドメインのテーブルは既にスコープ列（`team_id` または `issuer_scope_id`）と status を持つため、`COUNT(*) WHERE scope = ? AND status IN (...)` で件数が取れる（§3.4）。
- テナント絞り込みは各ドメイン Query Service の責務。テナント絞り込みリポジトリの継承状況は §5.1 の表で実態を示し、未対応ドメインは本機能の依存タスクとして列挙する。

### 5.1 集約元リポジトリのテナント絞り込み実態と依存タスク【テナントリポジトリ宙ぶらりん解消】

「`AbstractTenantAwareRepository` を継承済みであることを前提とし、未継承なら是正する」という宙ぶらりんを廃し、偵察した実態を断定的に示す。

| domain（スコープ） | 集約元 Repository | `AbstractTenantAwareRepository` 継承 | テナント絞り込みの現状 | IDOR 防止の担保 |
|-------------------|------------------|:----------------------------------:|---------------------|----------------|
| `RESERVATION`（team） | `ReservationRepository` | 未継承（`JpaRepository`）。`organization_id` 列が無い team 専用ドメインのため、`AbstractTenantAwareRepository`（org 絞り込み基底）の対象外 | `teamId` を全クエリの WHERE に持つ（`findByTeamIdAnd...`） | 入口 `checkAdminOrAbove(userId, teamId, "TEAM")` ＋ 全クエリ `WHERE team_id = ?` で担保（org 基底は不要） |
| `SHIFT_REQUEST`（team） | `ShiftChangeRequestRepository` / `ShiftSwapRequestRepository` | 同上（team 専用） | schedule→team の解決経由で `teamId` 絞り込み | 同上（team 入口認可＋team_id 絞り込み） |
| `MATCHING`（team） | `MatchProposalRepository` | 同上（team 専用） | `proposingTeamId` / request 経由の `teamId` 絞り込み | 同上 |
| `PAYMENT`（org） | `PaymentRequestRepository` | **未継承**。`scope_kind + scope_id` 方式で `organization_id` 単一列ではないため基底メソッド（`findByOrganizationId...`）の形に合致しない | `issuer_scope_kind=ORG AND issuer_scope_id=?` で絞り込み | 入口 `checkAdminOrAbove(userId, orgId, "ORGANIZATION")` ＋ 全クエリ `WHERE issuer_scope_kind='ORG' AND issuer_scope_id=?` で担保 |

> **本機能の依存タスク（前提で済ませない）**: 本 API は新設の各 Query Service で「入口認可（`checkAdminOrAbove`）＋ クエリ WHERE のスコープ絞り込み」の二重でテナント越境（IDOR）を防ぐ。`AbstractTenantAwareRepository` への移行は **本機能の必須前提ではない**（上記の通り、対象ドメインはいずれも `organization_id` 単一列方式ではなく、基底クラスの形に合致しないため）。将来 payment を org シャードキーで分割する際に `scope_kind/scope_id` を基底化する作業は payment ドメインの別タスクとして起票する（本機能のスコープ外・[05](./05_decisions.md) §8）。本機能で新設する Query Service には、入口認可とスコープ絞り込みの両方を必須とする契約テスト（§8）を課す。

---

## 6. キャッシュ

- `total_pending`（バッジ用）は短 TTL で Valkey キャッシュ可。
- **キャッシュキーには権限プロファイルを含める**【承認待ちキャッシュの DEPUTY 無視を解消】。承認待ちはスコープ単位で一意だが、本 API の `total_pending` は **ロールによって可視ドメインが変わらない**（team=予約/シフト/マッチング、org=支払。いずれも ADMIN/DEPUTY 共通で可視。DEPUTY 細粒度ゲートは予算・課金など本 API の集約対象外のドメインにのみ掛かる）。したがって `total_pending` 自体は同一スコープの全管理者で共有してよい。ただし将来 DEPUTY に応じて可視ドメインが変わる拡張に備え、キーに role 階層（`admin` か `deputy` か）を含める: `admin:action:{scopeType}:{scopeId}:{roleTier}`。TTL 30〜60 秒。
- `preview_size > 0` の詳細プレビューはキャッシュせず都度集計（鮮度優先）。
- 承認・却下が発生したら当該スコープのキャッシュを evict（各ドメインの承認処理後に該当キーを evict）。fail-open（Valkey 障害時はキャッシュをバイパスして都度集計）。`degraded` のドメインがあるレスポンスはキャッシュしない（集計失敗をキャッシュしない）。

---

## 7. エラーレスポンス

| ステータス | 条件 |
|-----------|------|
| 401 | 未認証 |
| 403 | ADMIN/DEPUTY でない（`checkAdminOrAbove` 違反 = COMMON_002）。他テナントの scope_id も非所属判定で 403 |
| 404 | スコープ（team/org）が存在しない / 論理削除済み |
| 400 | `preview_size` が範囲外（0〜5） |

> **BE と FE のステータス整合（403/404 二枚舌の解消）**【C・要修正 根治】: 本 API（BE）は権限不足・他テナントを **403（COMMON_002）** で返す。これはプロジェクトの API 認可慣習（`AccessControlService.checkAdminOrAbove` が COMMON_002 を投げる）に一致する。一方、FE の存在秘匿（管理ページの URL を非管理者に踏ませない）は**FE ルートのアクセス制御で行う**（[01](./01_console_routes.md) §5）。FE のルート制御と BE の API 応答は層が異なり、矛盾しない（FE は「管理ページを描画しない」、BE は「API を叩かれたら 403」）。R9 偵察のとおりプロジェクトの管理ページは「非権限時に 404 でブラウザを弾く」のではなく「`canAccess` で UI を出し分ける（または描画しない）」慣習であり、本機能もそれに従う（[01](./01_console_routes.md) §5 / [05](./05_decisions.md) §7）。**API レベルで 404 による存在秘匿は行わない**（列挙防止の本丸は scope_id 絞り込みと F00 認可であり、管理 API は元来管理者しか叩かない）。

---

## 8. 契約テスト（P1・test-first）

メモリ `feedback_test_first_be_api` に従い、実装より前に以下の契約テストを書く:

- `admin-action-required` を MEMBER で叩く → 403。
- ADMIN で team を叩く → 200・`domains` に `RESERVATION`/`SHIFT_REQUEST`/`MATCHING` の3ドメインが（0件でも）含まれ、`PAYMENT` は含まれない。
- ADMIN で organization を叩く → 200・`domains` に `PAYMENT` のみ含まれ、team 系3ドメインは含まれない（スコープ別動的集合の検証）。
- `preview_size=0` → `items` 空・`pending_count` のみ。
- 他組織の orgId → 403（IDOR。入口認可＋スコープ絞り込みの二重防御）。
- 1ドメイン Query Service が `DataAccessException` を投げる → 当該ドメインのみ `degraded=true`・`pending_count=0`・他は正常・`total_pending` に縮退分を加算しない（縮退の検証）。
- 1ドメイン Query Service が `NullPointerException` を投げる → **API 全体が 500**（縮退で握りつぶさないことの検証）。
- 各 Query Service が認可違反（COMMON_002）を内部で投げた場合 → **API 全体が 403**（縮退しないことの検証）。
- N+1 検出: 各ドメイン×プレビュー3件で発行 SQL 数が上限以下（Datasource プロキシでカウント）。
- 各 Query Service の WHERE にスコープ絞り込み（`team_id` / `issuer_scope_id`）が含まれること（テナント越境防止の番人テスト）。
