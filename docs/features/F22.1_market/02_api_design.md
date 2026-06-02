# F22.1 市（Market）— 02. API 設計

> 親: [README.md](README.md) ／ 関連: [01_data_model.md](01_data_model.md) / [04_security.md](04_security.md)

---

## 1. 方針 ―「薄い集約 API ＋ 既存 recruitment への委譲」

市の API は 2 系統に分かれる。

| 系統 | 役割 | 実装 |
|---|---|---|
| **市集約 API**（新規・`market` ドメイン） | 地域×ジャンルで束ねた**閲覧・検索・パンくず・地域選択** | `MarketController` / `MarketQueryService`（読み取り中心・PII抑制DTO） |
| **札の操作 API**（既存 `recruitment` を拡張/委譲） | 札立て・札に応じる・札を下げる・最終認証・リマインド | 既存 `RecruitmentListing*Controller` に地域列・フレンド宛先を追加。市は導線のみ |

市ドメインは**書き込み・状態遷移を持たない**（README §1）。札の CRUD は recruitment、フレンド判定は F01.5、決済は F13.1 に委譲する。

---

## 2. エンドポイント一覧

| メソッド | パス | 認証 | 説明 |
|---|---|---|---|
| GET | `/api/v1/public/market/listings` | **不要**（permitAll） | 市の札一覧（地域×ジャンル×状態フィルタ・PII抑制） |
| GET | `/api/v1/public/market/listings/{id}` | **不要**（permitAll） | 公開札の詳細（PII抑制・非公開は404） |
| GET | `/api/v1/public/market/regions` | **不要**（permitAll） | 都道府県一覧 ＋ 都道府県指定で市区町村一覧（フィルタ連動用） |
| GET | `/api/v1/public/market/summary` | **不要**（permitAll） | 地域ノードごとの札件数（パンくず/集客用） |
| GET | `/api/v1/public/market/categories` | **不要**（permitAll） | ジャンル（カテゴリ）マスタ一覧（フィルタ用・全テナント共通固定マスタ・PIIなし） |
| POST | `/api/v1/teams/{teamId}/recruitment-listings` | 必要 | **札立て（チーム）**＝既存作成APIに地域・フレンド宛先を拡張 |
| POST | `/api/v1/organizations/{orgId}/recruitment-listings` | 必要 | **札立て（組織）**＝同上 |
| POST | `/api/v1/recruitment-listings/{id}/applications` | 必要 | **札に応じる**＝既存応募API（変更なし） |
| POST | `/api/v1/recruitment-listings/{id}/cancel` | 必要 | **札を下げる（手動）**＝既存（札主のみ） |
| POST | `/api/v1/confirmable-notifications/{id}/confirm` | 必要 | **最終認証**＝既存F04.9確認応答（`MARKET_FINALIZE`） |
| （バッチ） | — | — | **札を下げる（自動）**＝既存 autoCancel/充足判定バッチ |

> 札立て/応募/取下げの**実体は既存 recruitment API**。市は新規パス `/api/v1/public/market/**` の**読み取り集約のみ**を追加する。これにより F03.11 を不必要に作り替えない（最小侵襲）。

---

## 3. 市集約 API（新規）

### 3.1 `GET /api/v1/public/market/listings`

> ⚠️ **実装注意 — レスポンス JSON の camelCase/snake_case 対応**
> 本節以降の JSON 例（`scope_type`、`display_name`、`prefecture_code`、`total_elements` 等）は**説明用に snake_case 表記の箇所がある**が、実 API のレスポンスフィールドは **Jackson 既定の camelCase**（例: `scopeType`、`displayName`、`prefectureCode`、`totalElements`）で返る。クエリパラメータ（`prefecture`、`city`、`category_id` 等）は snake_case のまま。FE の型定義・API 呼び出し実装では BE の DTO/Controller テストの `jsonPath` と 1:1 で突き合わせること。snake_case の JSON 例をそのまま FE 型にコピーしないこと（既知の実機バグ原因・PR #1210/#1221 で根治済の同種問題を再発させないため）。

地域×ジャンルで絞った「立っている札」の一覧。未ログインでも叩ける（PII抑制DTO）。

**クエリパラメータ**
| 名前 | 型 | 必須 | 説明 |
|---|---|---|---|
| `prefecture` | CHAR(2) | 任意 | 都道府県コード。未指定＝全国 |
| `city` | CHAR(5) | 任意 | 市区町村コード。指定時は `prefecture` と整合必須 |
| `category_id` | BIGINT | 任意 | ジャンル（`recruitment_categories`） |
| `keyword` | string | 任意 | タイトル部分一致 |
| `include_region_none` | bool | 任意 | "地域を問わない" 札も含めるか（既定 true） |
| `page` / `size` | int | 任意 | ページネーション（既定 0 / 20） |

**サーバ側の絞り込み（固定条件）**
```
visibility = 'PUBLIC' AND status IN ('OPEN','FULL') AND deleted_at IS NULL
```
- `city` 指定 → その市区町村の札。`prefecture` のみ → 配下市区町村をロールアップ（`SUBSTRING(city_code,1,2)=:pref` または `prefecture_code=:pref`）。

**レスポンス（200・PII抑制）**
```json
{
  "data": {
    "content": [
      {
        "id": 1234,
        "title": "11/3 練習試合の相手募集（U-12）",
        "category": { "id": 7, "name_key": "recruitment.category.practiceMatch" },
        "owner": { "scope_type": "TEAM", "scope_id": 88, "display_name": "別府FC", "icon_url": "..." },
        "region": { "prefecture_code": "44", "prefecture_name": "大分県", "city_code": "44202", "city_name": "別府市" },
        "location_text": "別府市総合運動公園",
        "start_at": "2026-11-03T09:00:00Z",
        "application_deadline": "2026-11-01T23:59:59Z",
        "capacity": 1, "confirmed_count": 0, "status": "OPEN",
        "payment_enabled": false
      }
    ],
    "total_elements": 42, "page": 0, "size": 20
  }
}
```
> **PII抑制**: `owner.display_name` はチーム/組織の公称名のみ。作成者個人名・連絡先・応募者個人名は**含めない**（§04_security §1.3）。
> **Phase 1 の決済**: `payment_enabled` は Phase 1 では常に `false`。謝礼決済（`price` 連動）は Phase 2 で F13.1 にフック委譲して有効化する。

### 3.2 `GET /api/v1/public/market/listings/{id}`

公開札の詳細。`visibility != 'PUBLIC'`（SCOPE_ONLY / FRIEND_TEAMS_ONLY 等）の札は**存在秘匿のため 404**（F19.1 §10.1 準拠）。連絡先・申込導線は未ログイン時「ログインして応募」に置換。

### 3.3 `GET /api/v1/public/market/regions`

フィルタ連動用の地域ファサード。`prefectures`/`cities` マスタの読み取り。

- `GET /api/v1/public/market/regions` → 都道府県47件
- `GET /api/v1/public/market/regions?prefecture=44` → 大分県配下の市区町村一覧

```json
{ "data": [ { "code": "44202", "name": "別府市", "prefecture_code": "44" }, ... ] }
```

### 3.4 `GET /api/v1/public/market/summary`

パンくず/集客用に、地域ノードごとの**立っている札の件数**を返す（PII なし）。

```json
{ "data": { "by_prefecture": [ { "code": "44", "name": "大分県", "count": 18 } ],
            "by_city":       [ { "code": "44202", "name": "別府市", "count": 7 } ] } }
```
- 件数は `COUNT(*) WHERE visibility='PUBLIC' AND status IN ('OPEN','FULL')`。`idx_rl_market_region` を利用。

### 3.5 応募者向け「自分が応じた札」一覧（既存F03.11流用）

市で札に応じた後、応募者が自分の応募状況を確認する導線は **F03.11 既存の参加一覧 API**（`GET /api/v1/me/recruitment-listings` 系 = `listMyActiveParticipations`、フロント `pages/me/recruitment-listings/`）を流用する。市レイヤで新規APIは作らない。

- 状態: `APPLIED`/`CONFIRMED`/`WAITLISTED`/`CANCELLED`（既存）。札が論理削除・フレンド解消後も、応募レコードを正典に表示・キャンセル可能（§7）。
- ダッシュボードの「届いた札」ウィジェット（フレンド宛非公開札の受信箱）と、この応募一覧で、参加者側の市の使い勝手を担保する。

### 3.6 `GET /api/v1/public/market/categories`

市一覧ページのジャンルフィルタ用に、**全テナント共通の固定カテゴリマスタ**（`recruitment_categories`）を返す。**未ログインで叩ける**（permitAll・PIIなし・i18nキー込み・表示順）。

`MarketController` が recruitment 層の `RecruitmentCategoryService.listCategories()` に委譲する（市は実体テーブルを持たず recruitment のカテゴリ解決で既に recruitment を参照している前例に倣う）。レスポンスは既存 `RecruitmentCategoryResponse`（camelCase）をそのまま再利用する。

```json
{ "data": [
  { "id": 7, "code": "PRACTICE_MATCH", "nameI18nKey": "recruitment.category.practiceMatch",
    "icon": "pi-flag", "defaultParticipationType": "TEAM", "displayOrder": 1, "isActive": true }
] }
```

> **🔴 根治記録（2026-05-31）**: 実機 E2E で「未ログインで `/market` を開くとログイン画面へ強制リダイレクトされる」重大バグが発覚。真因は市一覧ページの `onMounted` がジャンルフィルタ用に**認証必須**の `GET /api/v1/recruitment-categories` を直叩きし、未ログインで 401 → `useApi` の `onResponseError`（user=null）が市ページごと `/login` へ飛ばしていたこと。公開ページは**公開 API のみに依存**させるべく本エンドポイントを新設し、FE を切り替えて根治した。

### 3.7 SEO / sitemap

- 市の公開ページ（`/market`・`/market/listings/[id]`）の SEO は **F19.1 既存方針に準拠**（`sitemap.xml` 動的生成・`canonical`・`hreflang`）。
- フィルタ付きURL（`?prefecture=44&city=44202`）は **`canonical` を地域確定URLに正規化**し、パラメータ組合せの無限URL膨張を防ぐ。札詳細（公開札のみ）は sitemap 収録、非公開/scope限定は収録しない（404と整合）。

---

## 4. 札立て（既存作成APIの拡張）

既存 `POST /api/v1/teams/{teamId}/recruitment-listings`（および組織版）の**リクエストに地域・フレンド宛先を追加**する。新規エンドポイントは作らない。**ダッシュボードからのみ呼ばれる**（市画面からは呼べない・§04_security）。

**追加リクエスト項目**
```json
{
  "category_id": 7,
  "title": "11/3 練習試合の相手募集（U-12）",
  "capacity": 1, "min_capacity": 1,
  "participation_type": "TEAM",
  "start_at": "...", "end_at": "...",
  "application_deadline": "...", "auto_cancel_at": "...",
  "location": "別府市総合運動公園",

  "prefecture_code": "44",            // ★追加（任意）
  "city_code": "44202",               // ★追加（任意・prefectureと整合）

  "visibility": "PUBLIC",             // PUBLIC=市に出す / FRIEND_TEAMS_ONLY=非公開札
  "distribution_targets": ["PUBLIC_FEED"],

  "friend_targets": [                 // ★追加（visibility=FRIEND_TEAMS_ONLY のとき1件以上必須）
    { "target_kind": "ALL_FRIENDS" },
    { "target_kind": "FOLDER", "folder_id": 12 },
    { "target_kind": "TEAM",   "team_id": 305 }
  ],

  "reminders": [ { "remind_at": "..." } ]   // 既存（任意）
}
```

**Service の検証（要点）**
1. `AccessControlService` で当該 scope の `MANAGE_RECRUITMENTS` 権限を検証（TEAMは `checkPermission`／ORGは `checkAdminOrHasPermission`。§04_security §1.1）。
2. 地域: `city_code` が `cities` に存在し `SUBSTRING(city_code,1,2)=prefecture_code`（不一致 `MARKET_001`）。
3. `visibility='FRIEND_TEAMS_ONLY'` のとき:
   - `friend_targets` が1件以上（0件は `MARKET_002`）。
   - `TEAM` 宛先は札主チームと `team_friends` 成立済み（未成立 `MARKET_003`）。**正規化キー検索**: `team_a_id=MIN(札主,宛先) AND team_b_id=MAX(札主,宛先)`（01_data_model §4）。
   - `FOLDER` 宛先は札主チーム所有フォルダ（他人所有 `MARKET_004`）。フレンドフォルダ未実装環境では `FOLDER` を受け付けない（gating）。
   - `distribution_targets` の併用は不可。`FRIEND_TEAMS_ONLY` なのに `distribution_targets`（PUBLIC_FEED 等）を指定したら `MARKET_005`（400）。
4. 既存の定員/期限 CHECK（`min_capacity<=capacity`、`application_deadline<start_at` 等）は F03.11 を踏襲。

**レスポンス（201）**: 既存 `RecruitmentListingResponse` に `region`・`friend_targets` を加えた形。

---

## 5. 札に応じる（既存・変更なし）

`POST /api/v1/recruitment-listings/{id}/applications`（F03.11 §5.2）。

- 定員到達で `status` が `OPEN`→`FULL` に自動遷移（F03.11 §5.2 の原子的UPDATE）。
- 上限到達後は `RECRUITMENT_106`（上限超過）。キャンセル待ちは既存仕様。
- レート制限: 申込APIは 1ユーザー1分10回（F03.11 §5.2-9 既存）。
- `FRIEND_TEAMS_ONLY` の札は、宛先解決（§7）で対象外のユーザーには 404（存在秘匿）。応募可否も同判定。

---

## 6. 札を下げる（自動 / 手動 / 最終認証）

「札を下げる」は要件に従い 3 経路。状態遷移は既存 `recruitment_listings.status`（F03.11 §4.1）に**新カラムなしで**マッピングする。

### 6.1 自動で札が下がる（要件充足）→ 最終認証
1. 応募で `confirmed_count == capacity` に到達 → `status` が `FULL`（＝**充足・最終認証待ち**）に自動遷移（既存）。
2. 市レイヤのイベントリスナが `FULL` 遷移を検知 → 札主へ **最終認証の確認通知**（`confirmable_notifications`, `source_type='MARKET_FINALIZE'`）を送信。
3. 札主が `POST /confirmable-notifications/{id}/confirm` で確認 → `status` を `FULL`→`COMPLETED`（札が完全に下がる）。
4. 確認応答中は**自動下げバッチをスキップ**（札行を `PESSIMISTIC_WRITE` でロックし競合回避。§8-4 / F03.11 §5.4）。

> 「要件充足だが未認証」は `FULL`、「最終認証済み」は `COMPLETED` で表現でき、**新カラム不要**。

### 6.2 期限切れ（自動キャンセル）
- 既存 `RecruitmentAutoCancelBatch` が `auto_cancel_at` 経過かつ `confirmed_count < min_capacity` で `status`→`AUTO_CANCELLED`（F03.11 §5.4）。
- 既応募者へ「募集が成立しませんでした」通知（既存）。

### 6.3 手動キャンセル
- `POST /api/v1/recruitment-listings/{id}/cancel`（札主のみ・既存）。`status`→`CANCELLED`。
- リクエストに `cancelled_reason`（既存カラム `VARCHAR(200)`・自由文）を**必須化**。入力は XSS/CI禁則ワード検証（§04_security §1.5）を通す。
- **既応募者（個人/チーム/組織）へ理由付き一斉通知**（confirmable または通常通知）。F03.11 §5 のキャンセル通知を流用し、お詫び文＋`cancelled_reason`＋再応募導線（「同じチームの他の札を見る」＝市の scope 別一覧へ）を付す。

### 6.5 札の編集（地域・ジャンル変更）
- 既存 `PUT /api/v1/teams/{teamId}/recruitment-listings/{id}`（および組織版）に地域列を委譲拡張（F03.11 編集APIに準拠）。`prefecture_code`/`city_code` の更新は §4 と同一バリデーション（`MARKET_001`）。
- 地域・ジャンル変更は **`idx_rl_market_region` が自動更新**され、市ビューは次回検索で即時反映（市側にキャッシュを置く場合は当該地域キーを無効化）。同一札IDが旧地域に残らないこと（更新は単一行のため二重計上は起きない）。

### 6.4 リマインド（事前通知）
- 既存 `recruitment_reminders` + `RecruitmentReminderBatch`（F03.11 §3.8）を流用。
- **締切前に要件未達（`confirmed_count < min_capacity`）なら札主へ事前通知**（「まだ◯名足りません」）。`remind_at` は UTC 保存・表示時アカウントTZ変換（§8-5 / 全アカウントTZ対応済）。

---

## 7. フレンド宛非公開札の配信・アクセス解決

`visibility='FRIEND_TEAMS_ONLY'` の札は、`recruitment_friend_targets` を **F01.5 サービスで都度解決**して「現在の成立フレンド集合」を得る。複数粒度の混在指定は**集合和（UNION / OR）**で解決し、重複チームは排除する。

```
解決対象チーム集合 = （OR で和をとる・重複排除）
   ∪ (target_kind=ALL_FRIENDS → 札主チームの全成立フレンド)
   ∪ (target_kind=FOLDER      → 当該フォルダ内の成立フレンド／存在しないフォルダは空集合)
   ∪ (target_kind=TEAM        → 当該チーム（成立フレンドであることを再検証）)
```
- **配信（札立て時）**: 上記集合の各チーム管理者へ `NotificationHelper`（`sourceType='MARKET_FRIEND_LISTING'`, `sourceId=listing_id`）で通知。これが要件の「相手が通知を受け取れる＝札の市民権」を満たす。
- **アクセス/応募判定（閲覧時）**: 閲覧ユーザーの所属チームが解決集合に含まれるかを判定。含まれなければ **404**（存在秘匿）。
- フレンド関係は**保存時に固定せず都度解決**するため、フレンド増減に追従する（フレンド解消後は新規には見えなくなる）。
- **応募済みユーザーの扱い（フレンド解消後）**: 「自分が応じた札」の閲覧・状態確認・キャンセルは **`visibility` 判定ではなく応募レコード（`recruitment_participants`）の存在を正典**とする。フレンド解消後も**既存応募は表示・キャンセル可能**（UIに「フレンド解消により無効」バッジ）。ただし**新規応募・新規閲覧は不可**（404）。これにより「さっき見えた札が突然404」で応募導線が壊れる事故を防ぐ。

---

## 8. エラーコード

市レイヤ固有のエラーは `MARKET_*` 名前空間で新設（recruitment ドメインのバリデーションは `RECRUITMENT_*` を踏襲）。

| コード | HTTP | 条件 |
|---|---|---|
| `MARKET_001` | 400 | `city_code` がマスタ不在 / `prefecture_code` と不整合 |
| `MARKET_002` | 400 | `visibility='FRIEND_TEAMS_ONLY'` で `friend_targets` が0件 |
| `MARKET_003` | 403 | フレンド未成立のチームを宛先指定 |
| `MARKET_004` | 403 | 他チーム所有のフレンドフォルダを宛先指定 |
| `MARKET_005` | 400 | `visibility='FRIEND_TEAMS_ONLY'` なのに `distribution_targets` を併用指定 |
| `RECRUITMENT_204` | 400 | `PUBLIC` 札を配信対象0件で公開（publish）しようとした（`EMPTY_DISTRIBUTION_TARGETS`）。`GlobalExceptionHandler` で 400 にマッピング（ERROR severity 既定の 500 を上書き。`MARKET_002` と対称） |
| `RECRUITMENT_207` | 400 | `visibility` と配信対象の不整合（`PUBLIC` なのに `PUBLIC_FEED` 不在 等）。同上 400 マッピング |
| （委譲） | — | 札立て/応募/取下げ本体の検証は `RECRUITMENT_*`（206/106/300 等）を踏襲 |

> **配信対象（`distribution_targets`）の設定タイミング（実装メモ・2026-06-02 根治）**
> 札立て（`create`）リクエストの `distribution_targets` は **検証専用**で、BE は作成時に配信対象を永続化しない（`FRIEND_TEAMS_ONLY` との併用不可チェックのみ）。配信対象は別途 `PUT /api/v1/recruitment-listings/{id}/distribution-targets` で登録する。
> そのため FE の札立て導線（`pages/teams|organizations/[id]/recruitment-listings/new.vue`）は **`visibility='PUBLIC'` のとき作成直後に `PUT distribution-targets` で `PUBLIC_FEED` を自動登録**する。これを怠ると publish が `RECRUITMENT_204`（配信対象0件）で失敗し、PUBLIC 札が市に出ない（実機 CRUD E2E `market.crud.real.spec.ts` で炙り出した🔴）。`FRIEND_TEAMS_ONLY` は `friend_targets` で配信するため `distribution_targets` は設定しない。

---

## 9. 型定義（フロント）

- 手動型 `frontend/app/types/market.ts` を新設（`MarketListingResponse` / `MarketRegion` / `MarketSummary` / 札立て拡張リクエスト `FriendTargetInput`）。
- `FriendTargetInput` は粒度ごとに必須項目が異なるため **discriminated union** で表現する（`any` 禁止・型で不正組合せを排除）:
  ```ts
  type FriendTargetInput =
    | { target_kind: 'ALL_FRIENDS' }
    | { target_kind: 'FOLDER'; folder_id: number }
    | { target_kind: 'TEAM';   team_id: number }
  ```
- Backend API 追加後は `npm run generate:types` で `types/generated/index.ts` を再生成し、market 手動型は生成型をラップして enum を i18n キー化する（既存 recruitment.ts / village.ts と同方針）。
