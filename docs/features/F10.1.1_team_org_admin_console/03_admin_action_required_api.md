# F10.1.1 / 03: 管理者向け横断「承認待ち」集約 API 設計

> **ステータス**: 🟢 設計確定
> **最終更新**: 2026-06-17
> **関連**: [README.md](./README.md) / [04_security_authorization.md](./04_security_authorization.md) / [F22.1_swipe_scope_dashboard/02_api_design.md](../F22.1_swipe_scope_dashboard/02_api_design.md)（ScopeActionRequiredFacade の手本）

本書は管理者向けの**横断承認待ち集約 API** を新規設計する。既存のメンバー向け `action-required`（回覧/アンケ/出欠の自分宛の未対応）とは**別物**であり、こちらは「ADMIN/DEPUTY が処理すべき承認タスク」をドメイン横断で集約する。

---

## 1. メンバー向け action-required との違い

| 項目 | メンバー向け `action-required`（F22.1 既存） | 管理者向け `admin-action-required`（本書・新規） |
|------|------------------------------------------|--------------------------------------------|
| エンドポイント | `GET /api/v1/dashboard/{team\|org}/{id}/action-required` | `GET /api/v1/dashboard/{team\|org}/{id}/admin-action-required` |
| 視点 | 「私が回答/確認すべきこと」 | 「管理者が承認/処理すべきこと」 |
| 対象 | 回覧（未確認）・アンケート（未回答）・出欠（未回答） | 予約承認待ち・シフトリクエスト・マッチング申込・支払承認・入会/入村申請 |
| 認可 | スコープメンバー（MEMBER 以上） | **ADMIN / DEPUTY_ADMIN のみ** |
| ファサード | `ScopeActionRequiredFacade`（既存） | `AdminActionRequiredFacade`（新規） |

両者は別エンドポイント・別ファサード・別認可とし、混同しない。

---

## 2. エンドポイント

| 状態 | メソッド | パス | 認証 | 説明 |
|------|---------|-----|------|------|
| 🟡 | GET | `/api/v1/dashboard/team/{teamId}/admin-action-required` | 必要（ADMIN/DEPUTY） | チームの横断承認待ち集約 |
| 🟡 | GET | `/api/v1/dashboard/organization/{orgId}/admin-action-required` | 必要（ADMIN/DEPUTY） | 組織の横断承認待ち集約 |

> パスを F22.1 のメンバー向け `action-required` と同じ `/dashboard/{scope}/{id}/` 名前空間に置き、接尾辞を `admin-action-required` とすることで「ダッシュボード集約 API ファミリー」として一貫させる。Controller は新規 `AdminActionRequiredController`。

### 2.1 クエリパラメータ

| パラメータ | 型 | 必須 | 説明 |
|-----------|---|------|------|
| `preview_size` | Integer | No | 各ドメインのプレビュー件数（デフォルト 3、最大 5）。0 を指定すると件数のみ返す（L1 レンズ・ハブのバッジ用） |

---

## 3. レスポンス DTO

### 3.1 形

```json
{
  "data": {
    "scope_type": "TEAM",
    "scope_id": 12,
    "total_pending": 9,
    "domains": [
      {
        "domain": "RESERVATION",
        "pending_count": 2,
        "list_route": "/teams/dev-team/admin/reservations?status=PENDING",
        "items": [
          { "id": "uuid-...", "title": "コートA 6/20 10:00", "requested_by": "山田太郎", "requested_at": "2026-06-17T09:00:00+09:00", "detail_route": "/teams/dev-team/admin/reservations" }
        ]
      },
      {
        "domain": "SHIFT_REQUEST",
        "pending_count": 3,
        "list_route": "/teams/dev-team/admin/shift/requests",
        "items": [ { "id": 88, "title": "6/22 早番 交代希望", "requested_by": "佐藤花子", "requested_at": "2026-06-16T18:00:00+09:00", "detail_route": "/teams/dev-team/admin/shift/requests/88" } ]
      },
      {
        "domain": "MATCHING",
        "pending_count": 1,
        "list_route": "/teams/dev-team/admin/matching/applications",
        "items": [ { "id": 5, "title": "練習試合の申込", "requested_by": "鈴木一郎", "requested_at": "2026-06-15T12:00:00+09:00", "detail_route": "/teams/dev-team/admin/matching/applications/5" } ]
      },
      {
        "domain": "PAYMENT",
        "pending_count": 2,
        "list_route": "/teams/dev-team/admin/payments?status=PENDING_APPROVAL",
        "items": [ { "id": 33, "title": "備品購入 ¥12,000", "requested_by": "高橋健", "requested_at": "2026-06-14T15:00:00+09:00", "detail_route": "/teams/dev-team/admin/payments/33" } ]
      },
      {
        "domain": "MEMBERSHIP_APPLICATION",
        "pending_count": 1,
        "list_route": "/teams/dev-team/admin/members?tab=applications",
        "items": [ { "id": 77, "title": "入会申請", "requested_by": "田中愛", "requested_at": "2026-06-17T08:00:00+09:00", "detail_route": "/teams/dev-team/admin/members/applications/77" } ]
      }
    ]
  }
}
```

### 3.2 フィールド定義

- `total_pending`: 全ドメインの `pending_count` の合計。L1 レンズ ③ / L2 ハブの「承認待ち」バッジに表示。
- `domains[]`: ドメインごとのセクション。`pending_count` が 0 のドメインも**配列に含める**（FE が固定順で枠を描画できるよう、欠落させない）。
- `domain`: enum（`RESERVATION` / `SHIFT_REQUEST` / `MATCHING` / `PAYMENT` / `MEMBERSHIP_APPLICATION`）。組織スコープでは入村申請も `MEMBERSHIP_APPLICATION` に含める（村は組織の一種）。
- `list_route` / `detail_route`: FE が遷移するルート文字列。**BE がスラッグを解決して返す**（FE が ID から再構築しない）。スラッグ解決は他ドメイン Entity を直接参照せず `TeamService.getSlugById` 等の Service メソッド経由（ArchUnit 越境依存を避ける。メモリ `project_slug_e2e_open_issues` の教訓）。
- `items[]`: `preview_size` 件までのプレビュー。`id` は対象ドメインの主キー（UUID または BIGINT を文字列化）。

### 3.3 「件数＋導線」が主目的

本 API は**承認の実行 API ではない**。承認・却下はそれぞれのドメインの既存 API（予約 `POST .../confirm`、シフトリクエスト承認、支払承認等）を L3 セクションで呼ぶ。本 API は「どこに何件あるか」を集約して L1/L2/L3 ハブに供給するだけ。これにより、承認ロジック・トランザクション・監査ログは各ドメインに閉じ（CLAUDE.md 原則5）、集約 API は読み取り専用に保たれる。

---

## 4. ファサード設計 `AdminActionRequiredFacade`

### 4.1 配置

- パッケージ: `com.mannschaft.app.dashboard.service`（既存 `ScopeActionRequiredFacade` と同居）
- クラス: `AdminActionRequiredFacade`

### 4.2 集約方式

```java
public AdminActionRequiredResponse getAdminActionRequired(
        Long userId, String scopeType, Long scopeId, int previewSize) {

    // ① 入口認可（二重防御の1段目）
    accessControlService.checkAdminOrAbove(userId, scopeId, scopeType);

    // ② 各ドメインの「承認待ち件数＋プレビュー」を取得する Service を並行呼び出し
    //    各 Service は内部でも per-scope 認可を通す（集計バイパス禁止）
    var futures = List.of(
        async(() -> reservationAdminQueryService.pendingForScope(scopeType, scopeId, previewSize)),
        async(() -> shiftRequestAdminQueryService.pendingForScope(scopeType, scopeId, previewSize)),
        async(() -> matchingAdminQueryService.pendingForScope(scopeType, scopeId, previewSize)),
        async(() -> paymentAdminQueryService.pendingForScope(scopeType, scopeId, previewSize)),
        async(() -> membershipApplicationQueryService.pendingForScope(scopeType, scopeId, previewSize))
    );
    // ③ いずれかのドメインが例外 → 当該ドメインのみ pending_count=0・items=[] にフォールバック
    //    （症状を隠すのではなく「集約 API は全滅させない」縮退。例外は WARN ログに残す）
    ...
}
```

### 4.3 N+1 回避

- 各ドメインの `pendingForScope(scopeType, scopeId, previewSize)` は **1〜2 クエリで件数とプレビューを返す**実装契約とする（件数は `COUNT`、プレビューは `LIMIT previewSize` の1クエリ）。プレビュー要素の `requested_by`（表示名）は ID リストをまとめて1回の `UserService.getDisplayNames(ids)` でバルク解決し、要素ごとの個別 SELECT を禁止する。
- スラッグ解決も同様にバルク（`TeamService.getSlugsByIds`）。
- ファサードはドメイン Service の戻り値をメモリ上で合成するのみ。ファサード自身は SQL を発行しない。

### 4.4 各ドメインの集約元（既存 Service を流用）

| domain | 集約元 Service（既存ドメイン） | 承認待ちの定義 |
|--------|------------------------------|--------------|
| `RESERVATION` | reservation ドメイン（`TeamReservationController` の裏の Service） | status = PENDING の予約 |
| `SHIFT_REQUEST` | shift ドメイン | 承認待ちのシフト交代/希望リクエスト |
| `MATCHING` | matching/recruitment ドメイン | 受信した未承認の申込 |
| `PAYMENT` | billing/payment ドメイン | 承認待ちの支払・精算 |
| `MEMBERSHIP_APPLICATION` | membership ドメイン（村は village 含む） | 未処理の入会/入村申請 |

> 各ドメインに `pendingForScope` 相当の読み取りメソッドが未実装の場合は、当該ドメインに**読み取り専用クエリメソッドを新設**する（承認ロジックには触れない）。これは「未実装は未実装として実装する」（CLAUDE.md 障害対応原則3）に従い、集約側でエラーを握りつぶさない。モジュールが無効なスコープでは当該ドメインを `pending_count=0` として返す（縮退ではなく正常な0件）。

### 4.5 `@Transactional` 境界

- ファサードは**読み取り集約のためトランザクション不要**（`@Transactional(readOnly = true)` も付けず、各ドメイン Service 呼び出しに委ねる）。複数ドメインをまたぐ `@Transactional` を張らない（CLAUDE.md 原則5）。

---

## 5. DB 設計 — 新規テーブルなし

本 API は既存ドメインの既存テーブル（reservations / shift_requests / matching_applications / payments / membership_applications 等）を**集計参照するのみ**で、新規テーブル・新規カラム・Flyway マイグレーションを伴わない。

- 各ドメインのテーブルは既に `scope_type + scope_id`（または team_id/organization_id）と status を持つため、`COUNT(*) WHERE scope = ? AND status = 'PENDING'` で件数が取れる。
- テナント絞り込みは各ドメイン Service の責務。`organization_id` で絞るリポジトリは `AbstractTenantAwareRepository` を継承済みであることを前提とし、未継承なら当該ドメイン側で是正する。

---

## 6. キャッシュ

- `total_pending`（バッジ用）は短 TTL で Valkey キャッシュ可。キー: `admin:action:{scopeType}:{scopeId}`（メンバー向けと異なり**ユーザー非依存**＝同一スコープの全管理者で共有可能。承認待ちはスコープ単位で一意のため）。TTL は 30〜60 秒。
- `preview_size > 0` の詳細プレビューはキャッシュせず都度集計（鮮度優先）。
- 承認・却下が発生したら当該スコープのキャッシュを evict（各ドメインの承認処理後に `cacheManager.evict("admin:action:" + scope)`）。fail-open（Valkey 障害時はキャッシュをバイパスして都度集計）。

---

## 7. エラーレスポンス

| ステータス | 条件 |
|-----------|------|
| 401 | 未認証 |
| 403 | ADMIN/DEPUTY でない（`checkAdminOrAbove` 違反 = COMMON_002） |
| 404 | スコープが存在しない / 他組織の ID（IDOR・存在隠蔽は FE ミドルウェアの 404 で先行遮断、BE は 403） |
| 400 | `preview_size` が範囲外（0〜5） |

> **BE の 403 と FE の 404 の使い分け**: BE API は権限不足を 403（COMMON_002）で返す（API としての正直な応答）。FE のページミドルウェア（[01](./01_console_routes.md) §5）は管理ページの存在自体を秘匿するため 404 を出す。API レベルでの存在秘匿は不要（API パスは管理者しか叩かないうえ、列挙防止の本丸は scope 絞り込み）。

---

## 8. 契約テスト（P1・test-first）

メモリ `feedback_test_first_be_api` に従い、実装より前に以下の契約テストを書く:

- `admin-action-required` を MEMBER で叩く → 403。
- ADMIN で叩く → 200・`domains` に5ドメイン全てが（0件でも）含まれる。
- `preview_size=0` → `items` 空・`pending_count` のみ。
- 他組織の orgId → 403（IDOR）。
- 1ドメイン Service が例外 → 当該ドメインのみ 0 件・他は正常（縮退の検証）。
- N+1 検出: 5ドメイン×プレビュー3件で発行 SQL 数が上限以下（Datasource プロキシでカウント）。
