---

## 5.9 slug（URL 識別子）正準仕様 — 村方式（ユーザー任意入力）

> **マスター御裁可（2026-06-14）により確定した正準仕様。** チーム／組織の URL 識別子戦略が二重移行で混乱したため、村（`F17.1`）と同じ「ユーザーが登録時に任意 slug を決める」方式に一本化する。

### 5.9.1 基本方針

| 項目 | 正準仕様 |
|---|---|
| **入力主体** | チーム／組織の**作成時にユーザーが任意の slug を入力する**（村 `F17.1` の `VillageCreateRequest.slug` と同じ UX）。`name` からの自動生成は「編集可能な提案プレフィル」に降格 |
| **形式** | `^[a-z0-9][a-z0-9-]{1,28}[a-z0-9]$`（3〜30文字・英小文字／数字／ハイフン。`teams.slug` / `organizations.slug` ともに VARCHAR(30) 上限）|
| **一意性** | **グローバル一意**（`uq_teams_slug` / `uq_organizations_slug`。テーブル内で一意）|
| **予約語** | FE ルートと衝突する語を禁止（§5.9.3）|
| **重複時** | 強制連番付与（`team-000017` 等）は**廃止**。重複は 400 を返し、ユーザーに別 slug の入力を促す |
| **可用性チェック** | `GET /api/v1/teams/slug-check?slug=` / `GET /api/v1/organizations/slug-check?slug=` でリアルタイム重複確認（既存。レスポンス `{ available: boolean, suggestions: string[] }`）|

### 5.9.2 提案プレフィル（自動生成の降格）

- FE は作成フォームで `name` を ASCII 変換（スペース→ハイフン・小文字化・不正文字除去）した候補を **slug 入力欄の初期値**として表示してよい。
- これはあくまで**編集可能なプレフィル**であり、ユーザーは自由に書き換えられる。サーバが裏で確定 slug を上書きしたり、衝突時に数値サフィックスを強制付与したりはしない。
- 候補が空文字・予約語・既存重複になる場合（日本語名のみ等）は、FE は slug 欄を空のままにしてユーザー入力を必須とし、`slug-check` API の `suggestions` を併用して候補を提示する。

### 5.9.3 予約語

以下の **FE ルートと衝突しうる語** を slug として禁止する。マスタ／定数（例: `ReservedSlug` enum またはアプリ設定 `app.slug.reserved`）で一元管理し、作成・リネーム時に検証する:

```
new, search, admin, settings, me, public, api, login, logout, signup,
discover, slug-check, invite, system-admin, static, assets
```

> 予約語に該当した場合は 400（`TEAM_SLUG_RESERVED` / `ORG_SLUG_RESERVED`）を返す。リストは FE ルート追加時に追従更新し、本設計書と README の変更履歴に明記する。

### 5.9.4 既存連番 slug の是正方針

V71 backfill で ASCII 3 字未満（日本語名等）のチーム／組織には `team-000017` / `org-000017` のような**連番 slug** が付与された（§7 教訓参照）。これを次の方針で是正する:

- **一括強制リネームは採用しない**（既発行 URL・ブックマーク・外部リンクを破壊するため）。
- 設定画面でユーザー自身が slug を変更可能にする（`PATCH /teams/{slug}` / `PATCH /organizations/{slug}` で `slug` 変更を許可。形式・一意・予約語の同一バリデーションを適用）。
- 連番 slug に該当するチーム／組織にのみ、設定画面で「URL を分かりやすい slug に変更しませんか？」と**変更を促す導線**を表示する（任意・非強制）。

### 5.9.5 slug リネーム時の 301 リダイレクト（後続 wave ① BE 実装済み）

slug を変更すると旧 URL が 404 になり SEO・ブックマークが失われるため、**旧 slug → 新 slug のリダイレクト用履歴を残す**。**後続 wave ① で BE 実装済み**（FE 設定画面 UI・301 遷移は次 wave）:

- **履歴テーブル**: `team_slug_history` / `organization_slug_history`（Flyway `V88.001`）。`id BINARY(16)`（UuidV7・原則6）, `team_id`/`organization_id BIGINT`, `old_slug VARCHAR(30) UNIQUE`, `created_at`。`old_slug` の UNIQUE が「恒久予約」と「301 解決キー」を兼ねる。**クロスドメイン FK は張らない**（原則1）。
- **リネーム API**（既存 update と分離）: `PUT /api/v1/teams/{slug}/slug` / `PUT /api/v1/organizations/{slug}/slug` body `{ "newSlug": "..." }`。認可は `AccessControlService.checkAdminOrAbove`（ADMIN/DEPUTY 相当）。`newSlug == 現slug` は no-op で 200。成功時は旧 slug を履歴 INSERT → slug 更新を同一トランザクションで実施。
- **301 解決 EP**（permitAll＋レート制限）: `GET /api/v1/public/teams/slug-resolve?slug=` / `GET /api/v1/public/organizations/slug-resolve?slug=` → `{ status: "CURRENT" | "MOVED" | "NOT_FOUND", canonicalSlug? }`。現 slug 存在=`CURRENT`、旧 slug 一致=その現 slug へ `MOVED(canonicalSlug)`、どちらも無し=`NOT_FOUND`。スコープ漏洩防止のため `canonicalSlug` のみ返し名前等は返さない。FE は `MOVED` を受けて **301 Moved Permanently** で `canonicalSlug` の URL へ誘導する（次 wave）。
- **恒久予約**: 旧 slug は他チーム/組織が再利用できないよう**恒久予約**する。作成可用性チェック（`slug-available`）・作成検証・リネーム検証の全てで他スコープの履歴 slug を弾く（理由コード `SLUG_RETIRED`／`TEAM_063`・`ORG_063`＝409）。**自スコープ自身の過去 slug への戻しは許可**（同一 scope_id の履歴は判定から除外）。
- エラーコード: `TEAM_060`/`ORG_060`=形式不正(422)・`TEAM_061`/`ORG_061`=予約語(422)・`TEAM_062`/`ORG_062`=重複(409)・`TEAM_063`/`ORG_063`=履歴予約(409)。

### 5.9.6 public_id（UUID）路線を採らない決定と根拠

過去に列挙攻撃対策として slug を UUID（`public_id`）へ置き換える案があったが、**不採用**とする。根拠:

1. **列挙攻撃の本丸防御は F00 認可基盤**（`ContentVisibilityResolver` / `@EnableMethodSecurity`）が担う。URL 識別子の難読化は本丸防御ではなく、非公開エンティティは slug を知られても 403/404 のみ返す（§6 optional-auth 注記参照）。
2. **SEO 公開と矛盾**: チーム／組織は `/public/teams/{slug}` 等で**意図的に公開・被リンク・検索流入**を狙う（F15.4 / F19.1）。UUID 難読化は人間可読 URL・SEO と正面から衝突する。
3. **非推測化はユーザー任意入力で担保**: 「推測しにくい URL が欲しい」ニーズは、村方式のユーザー任意入力（自分で決めた slug）で十分に満たせる。一律 UUID 化は過剰。

### 5.9.7 DB 設計原則との整合

slug は既存 BIGINT 主キーテーブル（`teams` / `organizations`）上の **URL 識別子（外部公開キー）** であり、CLAUDE.md「DB設計の原則 #6（新規テーブルの主キーは UUIDv7）」とは**別軸**である（主キーは BIGINT のまま、slug は人間可読の二次キーとして併存する）。

---

## 6. セキュリティ考慮事項

- **認可チェック**: 全 Service メソッドの入り口で `team_id` / `organization_id` と `currentUser` の所属を検証する（メンバーでないスコープへのアクセスは 403）
- **親リソース認可チェック（子組織作成時）**: 子組織を作成する際は、作成者が**親組織**に対する ADMIN 権限を持つことを必ず確認する。具体的には:
  - `POST /organizations`（`parent_organization_id` 指定時）: 指定親組織に対して ADMIN 権限が必要
  - 親組織が存在しない・論理削除済みの場合は 404、権限不足は 403 を返す（存在チェックと権限チェックを分けることで情報漏洩を防ぐ）
  - チーム作成（`POST /teams`）は常に独立した状態で作成され、組織への所属は `POST /organizations/{slug}/team-invites` 経由で行うため、親リソース認可チェックは不要
- **招待トークン**: UUID v4（推測不可能）を使用。HTTPS 必須。`SELECT ... FOR UPDATE` でアトミックに使用回数チェックと更新を行い同時参加による上限超過を防ぐ
- **ロール昇格制限**: ADMIN は自分と同等以上（priority <= 2）のロールを他ユーザーに付与できない（自己昇格・SYSTEM_ADMIN 付与を防止）
- **ADMIN 昇格時の2FA必須**: ADMIN ロールへの昇格操作は、対象ユーザーが `two_factor_auth` テーブルに有効な TOTP レコードを持つ場合のみ許可する。2FA 未設定のまま ADMIN にすることはできない（README: 「SYSTEM_ADMIN・ADMIN には2FA必須」）
- **オーナー委譲の宛先照合（承諾型・IDOR 防止 / 2026-07-18）**: オーナー委譲は承諾型（`ownership_transfer_offers`）で行う。`accept`/`decline` は **オファーの `target_user_id` と実行ユーザー ID を Service 入口で照合** し、不一致は 403 とする（他人宛ての委譲オファーを第三者が承諾する IDOR を封じる）。承諾時に 2FA を再チェックし、`checkLastAdmin` は承諾（新 ADMIN 確定後）でのみスキップする。オファー作成（打診）は ADMIN のみ・同一スコープの PENDING は 1 件まで（409）。監査ログは `TEAM_OWNERSHIP_TRANSFER_OFFERED` / `ORGANIZATION_OWNERSHIP_TRANSFER_OFFERED`（打診）、`TEAM_ADMIN_TRANSFERRED` / `ORGANIZATION_ADMIN_TRANSFERRED`（承諾＝実行・metadata に `offer_id` 含む）、`*_OWNERSHIP_TRANSFER_DECLINED` / `*_OWNERSHIP_TRANSFER_CANCELLED`（辞退/取消）を記録する
- **組織種別変更**: `org_type` は ADMIN による自己申告制（承認プロセスなし・即時反映）。NONPROFIT / FORPROFIT の識別は UI のカラーコーディング等で視覚的に区別する。変更履歴は audit_logs（`ORGANIZATION_ORG_TYPE_CHANGED`）に before / after を含めて記録し、事後追跡を可能にする
- **スコープ境界**: `user_roles` の `team_id` と `organization_id` を同時に非 NULL にすることをアプリ層で禁止
- **招待URL公開範囲**: `GET /api/v1/invite/{token}` は未認証でアクセス可能だが、チーム名・アイコン・ロール名のみ返す（メンバー一覧・内部情報は含めない）
- **ブロック済みユーザーの招待参加防止**: `POST /invite/{token}/join` では有効性チェック → アーカイブチェック → ブロック済みチェックの順で検証する。`team_blocks` / `organization_blocks` に対象ユーザーのエントリが存在する場合は 403 を返し、招待トークンを保持していてもブロックをバイパスして参加できないようにする
- **フォロー登録の公開制限**: チーム/組織フォローはいずれも `visibility = PUBLIC` かつ `supporter_enabled = TRUE` のエンティティのみ受け付ける。条件を満たさない場合は 403
- **レートリミット**: 以下のエンドポイントに Bucket4j を適用する

  | エンドポイント | 制限 | 単位 | 認証 | 目的 |
  |--------------|------|------|------|------|
  | `GET /invite/{token}` | 10 req/min | **per IP** | 不要 | 未認証エンドポイントへのトークン列挙試行・DoS 防止 |
  | `GET /invite/{token}/qr` | 10 req/min | **per IP** | 不要 | 同上（PNG 画像生成リソースの保護）|
  | `POST /invite/{token}/join` | 10 req/min | per user | 必要 | トークンのブルートフォース探索を防止 |
  | `POST /teams/{slug}/invite-tokens` | 10 req/hour | per user | 必要 | 悪意ある ADMIN による大量トークン生成を防止 |
  | `POST /organizations/{slug}/invite-tokens` | 10 req/hour | per user | 必要 | 同上 |
  | `POST /teams/{slug}/follow` | 10 req/min | per user | 必要 | フォロー操作の乱用防止 |
  | `POST /organizations/{slug}/follow` | 10 req/min | per user | 必要 | フォロー操作の乱用防止 |
  | `PATCH /organizations/{slug}/profile` | 10 req/min | per user | 必要 | プロフィール連続更新の乱用防止（`PATCH /teams/{slug}/profile` も同じ制限）|
  | `POST/PATCH/DELETE/PUT /organizations/{slug}/officers` 系 | 30 req/min | per user | 必要 | 役員 CRUD の乱用防止（`/teams/{slug}/officers` 系も同じ制限）|
  | `POST/PATCH/DELETE/PUT /organizations/{slug}/custom-fields` 系 | 30 req/min | per user | 必要 | カスタムフィールド CRUD の乱用防止 |

  > - **per IP vs per user**: 未認証エンドポイント（`GET /invite/*`）は user ID が存在しないため IP アドレスをキーに制限する。認証済みエンドポイントは user ID をキーに適用し、NAT・プロキシ環境での誤検知を防ぐ
  > - **optional-auth エンドポイント**（`GET /teams/{slug}`・`GET /organizations/{slug}` など認証「任意」のもの）: スラッグは推測されにくいが、非公開エンティティは 403/404 のみ返し内部情報を返さない。SNS 経由の大量流入も想定されるため現時点では厳格な制限を設けず、将来的に問題が顕在化した場合に Nginx 等のゲートウェイで IP 単位のグローバル制限を追加することで対応する
  > - トークン作成のレートリミットは per user（ADMIN 個人）で適用する。`max_uses` を大きく設定すれば1枚のトークンで多人数を招待できるため、枚数制限はあくまで大量生成の乱用防止が目的

### プロフィール拡張項目のセキュリティ

- **URL バリデーション（homepage_url・custom_fields.value 内 URL）**: Service 層で以下を実施
  - 正規表現: **`^https?://` を case-insensitive で判定**（`HTTP://`, `HTTPS://` も許可するが、保存前に小文字に正規化する）
  - 拒否スキーム: `javascript:`, `data:`, `file:`, `vbscript:`, `ftp:`, `blob:`, `about:`
  - 最大512文字（homepage_url）。URL 単体は `value` 内でも `<`, `>`, `"` の混入を禁止（400）
  - ホスト部の制限: なし（localhost・private IP も許可。SSRF は「サーバーが fetch しない」ことで防御）
  - フロントエンドでの自動リンク化も `http(s)://` 始まりのみ対象
- **XSS 対策**: `philosophy`, `officer.name`, `officer.title`, `custom_fields.label`, `custom_fields.value` は全てプレーンテキスト扱い。サーバー側では保存時に HTML タグの検出（`<script`, `<iframe`, `on*=` 属性等）があれば 400 を返す。レンダリング時はフロント側で必ずエスケープし、Markdown・HTML は一切解釈しない
- **SSRF 回避**: URL は表示用のみで、サーバーから能動的に fetch しない（OGP プレビュー実装時は別途 SSRF 対策を追加）
- **可視性の強制（最重要）**: API 応答を組み立てる Service 層の最上位で以下を順にチェックする:
  1. `organization.visibility == PRIVATE` かつ呼び出し者が非メンバー → 404 / 空レスポンス
  2. `team.visibility == ORGANIZATION_ONLY` かつ呼び出し者が所属組織メンバーでない → 404 / 空レスポンス
  3. `profile_visibility.<field> == false` → その項目を応答から除外
  4. `officer.is_visible == false` / `custom_field.is_visible == false` → 当該行を応答から除外
  5. 上記3-4のルールは ADMIN / DEPUTY_ADMIN（当該組織/チームの）にも**デフォルトでは適用される**（実際に公開される状態を確認できるように）。非公開項目も含めた全件取得は `?visibility_preview=true` クエリパラメータで明示的に要求する（ADMIN/DEPUTY_ADMIN のみ使用可・それ以外は 403）。ADMIN 向けの編集画面では `?visibility_preview=true` を付けて呼び出し、全件に「現在非公開」バッジを表示して編集可能にする
  6. 上記のためのレスポンスフィールド: `visibility_preview=true` 時のみ各項目に `isPubliclyVisible: boolean` フィールドを追加し、現在の公開状態を明示する
- **権限チェック**: プロフィール編集系エンドポイントは `MANAGE_ORGANIZATION` / `MANAGE_TEAM` パーミッションを必須。該当権限を持たない DEPUTY_ADMIN は 403
- **Mass Assignment 対策**: リクエスト DTO は `profile_visibility` の JSON キーを strict mode で検証し、未知キーを含む場合は 400。`id` / `organization_id` / `team_id` / `created_at` はリクエスト DTO に含めずパスパラメータ/JPA で付与
- **監査ログ**: 以下のアクションを `audit_logs` に before/after JSON で記録
  - `ORGANIZATION_PROFILE_UPDATE` / `TEAM_PROFILE_UPDATE`
  - `ORGANIZATION_OFFICER_CREATE/UPDATE/DELETE/REORDER`
  - `TEAM_OFFICER_CREATE/UPDATE/DELETE/REORDER`
  - `ORGANIZATION_CUSTOM_FIELD_CREATE/UPDATE/DELETE/REORDER`
  - `TEAM_CUSTOM_FIELD_CREATE/UPDATE/DELETE/REORDER`
- **audit_logs に含まれる個人情報（GDPR/個人情報削除対応）**: 役員の `name` やカスタムフィールドの `value` に個人情報が含まれる場合、`audit_logs` への記録は F02 の GDPR 削除フローの対象となる。ユーザーが個人情報削除を申請した場合、該当ログ行の該当フィールドのみを `[REDACTED]` で上書きする運用とする（ログ行自体は削除しない：改ざん検知・監査証跡保持のため）
- **キャッシュ無効化**: Valkey 上で組織/チーム詳細をキャッシュしている場合、プロフィール系更新時は該当 `mannschaft:org:{id}` / `mannschaft:team:{id}` キーを必ず DEL する
- **パフォーマンス（N+1対策）**: 組織詳細取得で officers / custom_fields を同時に返す場合は以下いずれかで N+1 を回避
  - 案A: `@EntityGraph` や `FETCH JOIN` で一括取得（組織1件に対して officers 最大50件・custom_fields 最大20件と上限があるため、JOIN 結果の重複行は無視できる）
  - 案B: JPQL で組織詳細と関連コレクションを別クエリで取得し、Service 層で組み立てる（REST レスポンス DTO が個別フィールドを持つ設計のため、こちらを推奨）
  - `GET /organizations/{slug}/officers` / `custom-fields` は単独エンドポイントなので N+1 は発生しない
- **エラーコード一覧（新規追加分）**:
  - `ORG_040` (422): URL スキーム不正
  - `ORG_041` (422): 役員数上限超過（50件）
  - `ORG_042` (422): 役員 reorder 競合（stale）
  - `ORG_043` (422): カスタムフィールド上限超過（20件）
  - `ORG_044` (422): カスタムフィールド reorder 競合（stale）
  - `ORG_045` (422): `established_date` と `established_date_precision` のペア不整合
  - `ORG_046` (422): philosophy 文字数超過（2000コードポイント）
  - `ORG_047` (400): `profile_visibility` に未知キー含有
  - `ORG_048` (403): `visibility_preview=true` を ADMIN/DEPUTY_ADMIN 以外が要求
  - `ORG_049` (400): 棲み分け違反（`PATCH /organizations/{slug}` に拡張プロフィール項目、または `PATCH /organizations/{slug}/profile` に基本情報項目）
- **一括ON/OFF**: `PATCH /organizations/{slug}/profile` のリクエストで `profile_visibility` 全キーを `true` または `false` に設定することで実現可能（特別 API は作らない）。フロントエンドは「全公開」「全非公開」ボタンをローカル状態で切り替えて送信する

---

## 7. Flywayマイグレーション

```
V2.001__create_organizations_table.sql
V2.002__create_teams_table.sql
V2.003__add_audit_logs_fk_organization.sql      -- F02 より持ち越し: organizations FK 付与
V2.004__add_audit_logs_fk_team.sql              -- F02 より持ち越し: teams FK 付与
V2.005__create_roles_table.sql
V2.006__create_permissions_table.sql
V2.007__create_role_permissions_table.sql
V2.008__create_user_roles_table.sql
  -- scope_key VARCHAR(30) GENERATED ALWAYS AS (COALESCE(CONCAT('org:', organization_id), CONCAT('team:', team_id), 'platform')) STORED
  -- UNIQUE KEY uq_ur_user_scope (user_id, scope_key)
  -- CONSTRAINT chk_ur_scope CHECK (NOT (team_id IS NOT NULL AND organization_id IS NOT NULL))
  --   ※ 両方 NULL はプラットフォームスコープとして有効のため XOR でなく「同時非 NULL 禁止」制約を使用
V2.009__create_team_permission_groups_table.sql
V2.010__create_team_permission_group_permissions_table.sql
V2.011__create_user_permission_groups_table.sql
V2.012__create_invite_tokens_table.sql
  --   CONSTRAINT chk_it_scope CHECK ((team_id IS NULL) != (organization_id IS NULL))
  --   ※ invite_tokens は必ずチームまたは組織のどちらか一方に属するため真の XOR 制約を使用（両方 NULL 不可）
V2.013__seed_roles.sql
V2.014__seed_permissions.sql
V2.015__seed_role_permissions.sql
V2.016__seed_system_admin_user_role.sql         -- V1.012 で作成済みの SYSTEM_ADMIN ユーザーへ user_roles エントリを追加
V2.017__create_team_blocks_table.sql
V2.018__create_organization_blocks_table.sql
V2.019__add_is_default_to_role_permissions.sql      -- is_default カラム追加・DEPUTY_ADMIN 既存行を FALSE に UPDATE
V2.020__add_target_role_to_team_permission_groups.sql
V2.021__seed_member_permission_ceiling.sql           -- MEMBER 天井3件（is_default=FALSE）を INSERT
V2.022__rename_permission_groups_tables.sql
  -- team_permission_groups → permission_groups にリネーム
  --   organization_id BIGINT UNSIGNED NULL ADD COLUMN
  --   FK: organization_id → organizations (ON DELETE CASCADE)
  --   CONSTRAINT chk_pg_scope CHECK ((team_id IS NULL) != (organization_id IS NULL))
  --   INDEX idx_pg_org_id (organization_id)
  --   INDEX idx_pg_org_role (organization_id, target_role)
  --   INDEX idx_pg_team_id / idx_pg_team_role（旧 idx_tpg_* をリネーム）
  -- team_permission_group_permissions → permission_group_permissions にリネーム
  --   FK および UNIQUE KEY 名を uq_pgp_group_permission / idx_pgp_permission にリネーム
V2.023__refactor_team_org_memberships.sql
  -- teams テーブルから organization_id カラムを DROP:
  --   DROP INDEX idx_team_org（organization_id インデックスを先に削除）
  --   DROP FOREIGN KEY fk_team_organization（FK 制約を先に削除）
  --   DROP COLUMN organization_id
  -- team_org_memberships テーブルを新規作成:
  --   team_id BIGINT UNSIGNED NOT NULL, FK → teams (ON DELETE CASCADE)
  --   organization_id BIGINT UNSIGNED NOT NULL, FK → organizations (ON DELETE CASCADE)
  --   status ENUM('PENDING','ACTIVE') NOT NULL DEFAULT 'PENDING'
  --   invited_by / responded_by BIGINT UNSIGNED NULL, FK → users (SET NULL on delete)
  --   invited_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
  --   responded_at DATETIME NULL
  --   UNIQUE KEY uq_tom_team_org (team_id, organization_id)
  --   INDEX idx_tom_team_id, idx_tom_org_id, idx_tom_status

-- Phase 3 （F04 支払い管理実装時）
V3.007__add_manage_payments_permission.sql
  -- permissions に MANAGE_PAYMENTS（display_name='支払い管理', scope='ORGANIZATION'）を INSERT
  -- role_permissions に3件 INSERT:
  --   (SYSTEM_ADMIN, MANAGE_PAYMENTS, is_default=TRUE)
  --   (ADMIN,        MANAGE_PAYMENTS, is_default=TRUE)
  --   (DEPUTY_ADMIN, MANAGE_PAYMENTS, is_default=FALSE)
  -- ※ MEMBER には追加しない（支払い管理権限は ADMIN/DEPUTY_ADMIN 以上）

-- F01.2 拡張プロフィール機能
V3.131__add_extended_profile_to_orgs_and_teams.sql
  -- organizations テーブルに以下カラムを ADD:
  --   homepage_url VARCHAR(512) NULL
  --   established_date DATE NULL
  --   established_date_precision ENUM('YEAR','YEAR_MONTH','FULL') NULL
  --   philosophy TEXT NULL
  --   profile_visibility JSON NULL
  -- teams テーブルにも同じ5カラムを ADD
  -- CHECK 制約（MySQL 8.0.16+）: (established_date IS NULL AND established_date_precision IS NULL) OR (established_date IS NOT NULL AND established_date_precision IS NOT NULL)
V3.132__create_organization_officers_table.sql
  -- id BIGINT UNSIGNED AUTO_INCREMENT PK
  -- organization_id BIGINT UNSIGNED NOT NULL, FK → organizations(id) ON DELETE CASCADE
  -- name VARCHAR(100) NOT NULL
  -- title VARCHAR(100) NOT NULL
  -- display_order INT UNSIGNED NOT NULL DEFAULT 0
  -- is_visible BOOLEAN NOT NULL DEFAULT TRUE
  -- created_at / updated_at DATETIME
  -- INDEX idx_org_officers_org (organization_id, display_order)
V3.133__create_team_officers_table.sql
  -- team_officers: organization_officers と同構造で team_id FK → teams(id) ON DELETE CASCADE
V3.134__create_organization_custom_fields_table.sql
  -- id BIGINT UNSIGNED AUTO_INCREMENT PK
  -- organization_id BIGINT UNSIGNED NOT NULL, FK → organizations(id) ON DELETE CASCADE
  -- label VARCHAR(100) NOT NULL
  -- value TEXT NOT NULL
  -- display_order INT UNSIGNED NOT NULL DEFAULT 0
  -- is_visible BOOLEAN NOT NULL DEFAULT TRUE
  -- created_at / updated_at DATETIME
  -- INDEX idx_org_custom_fields_org (organization_id, display_order)
V3.135__create_team_custom_fields_table.sql
  -- team_custom_fields: organization_custom_fields と同構造で team_id FK → teams(id) ON DELETE CASCADE

-- チーム・組織スラッグ移行（F01.2 slug URL 対応）
V71.20260609001__add_slug_to_teams.sql
  -- teams テーブルに slug カラムを ADD:
  --   slug VARCHAR(30) NOT NULL
  --   UNIQUE KEY uq_teams_slug (slug)
  -- 既存データの backfill: ASCII 3 字以上に正規化できる name はそれを slug 候補とし、
  --   3 字未満（日本語名等）になるものは一時値 CONCAT('team-', LPAD(id,6,'0')) を埋める
  -- バリデーション: ^[a-z0-9][a-z0-9-]{1,28}[a-z0-9]$（3〜30文字、英小文字・数字・ハイフンのみ）
V71.20260609002__add_slug_to_organizations.sql
  -- organizations テーブルに slug カラムを ADD:
  --   slug VARCHAR(30) NOT NULL
  --   UNIQUE KEY uq_organizations_slug (slug)
  -- 既存データの backfill: teams と同様。3 字未満は CONCAT('org-', LPAD(id,6,'0'))
  -- バリデーション: teams と同一

-- オーナー委譲の承諾型化（2026-07-18・§10.11 解消）
V{major}.{yyyyMMddHHmmss}__create_ownership_transfer_offers_table.sql
  -- 新規テーブル ownership_transfer_offers（原則6: UUIDv7）
  --   id BINARY(16) NOT NULL PRIMARY KEY（UuidV7Entity 継承）
  --   team_id / organization_id BIGINT UNSIGNED NULL（FK なし・INDEX / 原則1）
  --   issued_by / target_user_id BIGINT UNSIGNED NOT NULL（FK なし）
  --   status VARCHAR(20) NOT NULL DEFAULT 'PENDING'（PENDING/ACCEPTED/DECLINED/EXPIRED/CANCELLED）
  --   expires_at DATETIME NOT NULL, accepted_at / resolved_at DATETIME NULL
  --   created_at / updated_at DATETIME
  --   CONSTRAINT chk_oto_scope CHECK ((team_id IS NULL) != (organization_id IS NULL))
  --   INDEX idx_oto_target_user (target_user_id, status)
  --   INDEX idx_oto_team (team_id, status) / idx_oto_org (organization_id, status)
  -- ※ major は origin/main 全体の最大 major+1 でマージ時に確定・minor はタイムスタンプ必須
  -- ※ 純粋な新規テーブル作成のみ（既存データ書き換えなし）のため既存データ番人テスト不要
```

> **教訓（slug 二重移行の退行）**: V71 系は当初 `public_id`（UUID）→ slug への置換として実装され、`public_id` 列を DROP した。さらに **`CreateTeamRequest` から slug 入力欄が失われ、name からの自動生成のみ**になった結果、ASCII 3 字未満（日本語名等）のチーム／組織には `team-000017` のような**意味の無い連番 slug** が backfill で付与された。正準仕様は「ユーザーが任意 slug を入力する村方式」（§5.9）であり、連番付与・public_id(UUID) 路線は不採用。既存連番 slug の是正は §5.9.4、public_id 不採用の根拠は §5.9.6 を参照。

**マイグレーション上の注意点**
- V2.001（organizations）→ V2.002（teams）の順を守ること（teams は organizations に FK）
- V2.003 は V2.001 直後、V2.004 は V2.002 直後に実行すること（audit_logs の FK 対象テーブルが先に存在すること）
- V2.008（user_roles）は V2.001 / V2.002 / V2.005 がすべて完了していること（FK: users, teams, organizations, roles）
- V2.013〜V2.015 はシードデータ。テスト環境でも必ず投入すること（`roles` テーブルが空だとアプリ起動時にエラー）
- V2.016 は V2.008 の後に実行すること（user_roles テーブル作成後）
- V2.019 は V2.015（シード投入）の後に実行すること。`is_default = FALSE` への UPDATE は DEPUTY_ADMIN の role_id を WHERE 条件に指定する
- V2.021 は V2.006（permissions テーブル）および V2.019（is_default カラム追加）の後に実行すること
- V2.022 は V2.009〜V2.011（権限グループテーブル作成）および V2.001（organizations テーブル作成）の後に実行すること
- V2.023 は V2.002（teams テーブル作成）および V2.001（organizations テーブル作成）の後に実行すること。teams.organization_id の DROP は FK・インデックスを先に削除してから行うこと（MySQL はカラム DROP 前に FK を明示削除する必要がある）

---

## 8. 未解決事項

- [x] 組織レベルの DEPUTY_ADMIN 権限グループの必要性を Phase 2 開始前に確認する（現設計はチームスコープのみ）→ **対応済み（2026-03-08）**: `team_permission_groups` / `team_permission_group_permissions` を `permission_groups` / `permission_group_permissions` に統合リネームし、`organization_id` カラム（XOR 制約）を追加。チーム・組織共通で同一テーブルを使用する設計に変更。API も `GET/POST/PATCH/DELETE /organizations/{id}/permission-groups` および `PUT /organizations/{id}/members/{userId}/permission-groups` を追加
- ~~組織レベルのサポーター登録（`POST /api/v1/organizations/{id}/follow`）の必要性を確認する~~ → 対応済み（2026-02-21、チームと対称的に実装）
- [x] `invite_tokens.created_by` が退会した場合に紐付くトークンを自動失効させるか、そのまま残すかを確定する → **自動失効させず有効のままとする**（`created_by` は `SET NULL on delete`）。理由: 運用継続性の確保・管理責任はチーム/組織に帰属・他の ADMIN による手動 revoke が代替手段として存在するため
- [x] チーム論理削除時に `invite_tokens.revoked_at` を自動設定するか確定する → **自動設定する**（一括失効）。組織論理削除時も同様に直属トークンを一括失効。子チームのトークンは組織削除では失効させない（チームが独立存続するため）。チーム/組織削除フローをビジネスロジックに追加
- [x] `user_roles` の一意性保証方法を Phase 2 実装時に確定する → **STORED 生成列 `scope_key` + `UNIQUE KEY (user_id, scope_key)`** を採用。`COALESCE(CONCAT('org:', organization_id), CONCAT('team:', team_id), 'platform')` で NULL 問題を回避し、DB レベルで一意性を強制。Service 層は例外ハンドリングのみで対応可
- [x] `teams.template` の型: VARCHAR(50) → 将来的に FK → `team_templates` テーブルへ移行するタイミングを確定する → **Phase 2 は VARCHAR(50) のままアプリ層 enum 定数でバリデーション**。テンプレートごとのメタデータ（カスタムフィールド等）が必要になった段階で `team_templates` テーブルを新設し FK へ移行（テンプレート管理 feature doc で設計）
- [x] 組織階層の最大深さ（現在3階層固定）をシステム設定として管理可能にするかを確定する → **`app.org.max-depth` 設定値（デフォルト: 5）に外出し**。再帰構造（`parent_organization_id`）はどの深さにも対応するため、設定値変更だけで上限を調整可能。Service 層・CTE ともにハードコードなしで設定値を参照。超過時は 422
- [x] MEMBER のデフォルト権限（MANAGE_SCHEDULES / MANAGE_FILES / MANAGE_POSTS）をチーム単位または個人単位で剥奪する「制限機能」の設計（Phase 3 以降） → **権限グループによる完全上書き（オーバーライド）方式を採用**。グループ割り当て時は `is_default` を無視しグループ内権限のみが実効権限となるため、マイナス計算のロジックなしで制限が可能。MEMBER 天井を `is_default = FALSE` の3件→ 全6件に拡張。権限解決ロジック・3層制御説明・フロー例を更新
- [x] F04（支払い管理）で定義された `MANAGE_PAYMENTS` パーミッションを `permissions` シードに追加する（Phase 3 実装前に確定）→ **Phase 3 の V3.007 で追加**。SYSTEM_ADMIN ✓ / ADMIN ✓ / DEPUTY_ADMIN △。MEMBER には付与不可（天井エントリなし）。scope = ORGANIZATION（F04 確定設計でチーム・組織の両方に支払い管理 API が存在するため ORGANIZATION に確定）。Flyway マイグレーションと role_permissions シード表に反映済み
- ~~`ORGANIZATION_MEMBER_JOINED` イベントを F02 イベントカタログの「今後追加予定」に追記する~~ → 対応済み（2026-02-21）
- [x] MEMBER / DEPUTY_ADMIN の自主退会フローが未定義 → **対応済み（2026-03-09）**: `DELETE /teams/{id}/me` / `DELETE /organizations/{id}/me` エンドポイントおよびフローを追加。最後のADMIN保護・SUPPORTER 誘導・payment data 保持（F04 参照）・組織退会は直属ロールのみ削除を明記
- [x] **組織の自動アーカイブ条件が未定義** → **対応済み（2026-03-09）**: 案②（直接所属メンバー + ACTIVE 所属チームの全メンバーの最終ログイン12ヶ月超過）を採用。加えて有効な `member_payments` が存在しないことを C3 条件として追加（F04 設計確定後に実装）。カスケードアーカイブは「このorgにのみ ACTIVE 所属するチーム」に限定し、多対多所属チームへの誤波及を防止。子組織はカスケード対象外（独自バッチで判定）。手動アーカイブフローにも同カスケードロジックを適用（reason: MANUAL_CASCADE_ORG）
- [x] **拡張プロフィールの楽観ロック** → **初版は「最後に書いた人の勝ち」運用で確定**（2026-04-15）。理由: プロフィール編集は ADMIN/DEPUTY_ADMIN のみで同時編集頻度が極めて低い・audit_logs で before/after を保持しているため事後復元は可能。運用で衝突が問題化した場合は `If-Match` / `ETag` ベースの制御を追加する（変更は現行APIに後方互換に追加可能）
- [x] **拡張プロフィールの OGP プレビュー** → **本設計のスコープ外で確定**（2026-04-15）。`homepage_url` は表示用のみでサーバーから能動的に fetch しない方針。将来 OGP 機能を追加する場合は別 feature doc で SSRF 対策（許可ホスト制限・レスポンスサイズ制限・タイムアウト・プライベートIP拒否）を設計した上で実装する
- [x] **profile_visibility 項目追加時の移行方針** → **「未知キー＝false（非公開）扱い」で確定**（2026-04-15）。既存組織の `profile_visibility` JSON に将来追加されたキーが不在の場合、アプリ層のデシリアライザが `false` を返す設計。後方互換のためマイグレーションは原則不要。将来キー名を変更・削除する場合のみマイグレーションスクリプトを用意する
- [x] **オーナー委譲に指名先の事前承諾フローがない**（`account_purge_last_admin_succession.md` §10.11）→ **対応（2026-07-18 マスター御裁可）**: オーナー委譲を承諾型（オファー→承諾）に統一。新テーブル `ownership_transfer_offers`（UUIDv7）＋ `POST /{scope}/{slug}/transfer-ownership-offers`（打診）／`.../{offerId}/accept`（承諾＝実行）／`decline`／`DELETE`（取消）を新設。指名相手のみ承諾可（宛先照合 = IDOR 防止）。旧即時 `transfer-ownership` は廃止。§10.11 の未解決状態が本改修で解消される
- [ ] **降格先ロールは MEMBER（実装が正・旧 doc の DEPUTY_ADMIN は誤記）**: 実装 `RoleService#transferOwnership` は発行者を MEMBER 降格（javadoc・コード確認済み）。旧設計記述の DEPUTY_ADMIN は乖離した誤りであり、マスター御裁可（発行者 MEMBER 降格）と一致する **MEMBER に統一**する。02_api_design のレスポンス例・03_business_logic のフロー・実装コードを MEMBER で揃える（対応済み）
- [ ] **FE-BE 不一致は方式ごとの乖離＝既存バグ（実装時に刷新・M-4）**: 旧 BE `transfer-ownership` は `@RequestParam Long targetUserId`（**クエリ**）でボディを読まない（`TeamController` 確認済み）。FE は body `{ newAdminUserId }` のみ送信 → クエリ未付与で **現行は 400 になるバグ**。承諾型 2 ステップ API への FE 全面刷新時に、新 API は **JSON body `{ targetUserId }`** に統一しクエリ方式は廃止する
- [x] **退会 purge 経路と承諾型の衝突（H-2）**→ **決着**: 通常委譲は承諾型2段、**退会 purge 経由の最後の ADMIN 承継のみ承諾スキップの強制委譲**（2FA チェックなし・audit `forced=true`）を残す。GDPR Art.17 の 30 日タイムリミット順守のため先送り不可。`acceptOffer` と `forceTransferForPurge` を別メソッドに分離（03_business_logic・account_purge §10.11 に明文化）

---

## 9. 変更履歴

| 日付 | 変更内容 |
|------|---------|
| 2026-07-18 | **オーナー委譲の承諾型化（マスター御裁可）**: 旧即時 `POST /{scope}/{slug}/transfer-ownership`（承諾なし即時昇格・降格）を廃止し、承諾型オファーの 2 ステップ API に置換。① 新テーブル `ownership_transfer_offers`（UUIDv7・原則6 / クロスドメイン FK なし・原則1 / `chk_oto_scope` XOR）を 01_db_design に追加。② 02_api_design のエンドポイント表・詳細節を `transfer-ownership-offers`（打診/accept/decline/DELETE）に改訂。③ 03_business_logic に「オーナー委譲 承諾フロー（2ステップ）」を新設（状態機械 PENDING→ACCEPTED/DECLINED/EXPIRED/CANCELLED・宛先照合による IDOR 防止）。④ 06 セキュリティに宛先照合・監査イベント（`*_OWNERSHIP_TRANSFER_OFFERED`/`_DECLINED`/`_CANCELLED`）を追記。⑤ Flyway に `create_ownership_transfer_offers_table` を追加。`account_purge_last_admin_succession.md` §10.11（指名先承諾欠如）が本改修で解消。既知課題として「降格先ロール DEPUTY_ADMIN↔MEMBER 不一致」「FE-BE パラメータ `newAdminUserId`↔`targetUserId` 不一致」を実装時統一事項として記録。関連: [F04.12 チャットからの承諾型招待](../F04.12_chat_membership_invite.md)（共通の承諾型オファー思想）|
| 2026-04-15 | 組織・チーム拡張プロフィール機能を追加: ① `organizations`/`teams` に `homepage_url` / `established_date` / `established_date_precision` / `philosophy` / `profile_visibility` JSON の5カラムを追加。② 新テーブル `organization_officers` / `team_officers`（役員一覧・最大50件）、`organization_custom_fields` / `team_custom_fields`（フリー記述項目・最大20件）を追加。③ 全項目に ON/OFF 公開可否フラグを搭載（`profile_visibility` JSON + 個別行の `is_visible`）。④ 対応API 23本を追加（プロフィール一括更新 PATCH、officers/custom-fields の CRUD + reorder）。⑤ セキュリティ考慮事項に「URL スキーム検証（case insensitive）」「XSS プレーンテキスト強制」「可視性の Service 層強制チェック6ステップ」「監査ログ 10 種追加・GDPR REDACTED 方針」「Valkey キャッシュ無効化」「N+1対策」「エラーコード `ORG_040`〜`ORG_049`」を追記。⑥ Flyway V3.131〜V3.135 を追加 |
| 2026-04-25 | 組織階層の表示用エンドポイントを追加: ① `GET /api/v1/organizations/{id}/ancestors` 上位組織チェーン取得（root → 親順・`hierarchy_visibility` と `visibility` を尊重・閲覧不可な祖先は `{id, hidden:true}` プレースホルダで返す・最大深度は `app.org.max-depth`）② `GET /api/v1/organizations/{id}/children` 直近の子組織一覧（visibility フィルタ・カーソルページネーション・`archived` フラグ含む）。これによりフロントエンドで「上位組織パンくず」「下位組織一覧」が単一API呼び出しで構築可能になる。既存の `parent_organization_id` カラム・`hierarchy_visibility` ENUM・`app.org.max-depth` 設定をそのまま活用する読み取り専用追加 |
| 2026-04-15 | 拡張プロフィール機能の精査（第1回・第2回）: ① ER図に新テーブル4つを反映 ② JSON vs 個別BOOLEANのトレードオフ設計ノート追記 ③ `reorder` 時の stale 競合処理追加（`ORG_042` / `ORG_044`）④ Unicode 文字数カウント方針（`Character.codePointCount()`）⑤ 空文字の正規化ルール（nullable は NULL、NOT NULL は 400）⑥ `visibility_preview=true` クエリの詳細仕様（ADMIN/DEPUTY_ADMIN のみ・`isPubliclyVisible` フィールド追加）⑦ 既存 `PATCH /{id}` と新 `PATCH /{id}/profile` の棲み分けルール（`ORG_049`）⑧ 役員個人情報の登録責任を明文化 ⑨ 未解決事項に「楽観ロック」「OGP プレビューの SSRF」「profile_visibility 将来拡張の移行」を追加 |
| 2026-03-11 | 精査（深度レビュー）: ① 招待トークン参加エラーテーブルに 401（未認証）を追加 ② ロール変更フローに同一ロール冪等処理を追加（step 6: DB 更新・audit_logs をスキップし 200 OK）③ `user_roles.user_id` FK の ON DELETE RESTRICT 設計理由を備考に追記（blocks の CASCADE との設計意図の違いを明文化）|
| 2026-02-21 | 初版作成 |
| 2026-02-21 | SUPPORTER 参加方式を「招待URL」から「フォロー（サポーター登録）API」に修正。チーム・組織それぞれに `supporter_enabled` カラム・`team_blocks`/`organization_blocks` テーブル・フォロー/ブロックAPI・フローを追加。F02 に関連イベント追記 |
| 2026-02-21 | 招待プレビュー・参加レスポンスを `invite_type` + `target` 構造に統一（チーム/組織の両方に対応）。`is_valid = false` 時も `target` を返す仕様を明記 |
| 2026-02-21 | `role_permissions` シードデータ（36件）を追加。DEPUTY_ADMIN の role_permissions が「天井定義」として機能する設計を明記。`DELETE_OTHERS_CONTENT` を天井に含めることで ADMIN が権限グループへ明示的に追加可能とする設計に修正。権限解決ロジックに MEMBER/SUPPORTER/GUEST の扱いを追記 |
| 2026-02-21 | サブグループ（組織階層）対応を追加: `organizations.hierarchy_visibility` カラム追加（NONE/BASIC/FULL）、WITH RECURSIVE CTE によるカスケード通知メカニズム設計、`GET /organizations/{id}/members/all` エンドポイント追加（`scope` クエリパラメータで ORGANIZATION / TEAM / INDIVIDUAL を選択可）、子孫メンバーからの上位組織閲覧制御ロジックをビジネスロジックに追記 |
| 2026-02-21 | カスケード通知に `announcement_scope`（SELF / ORGANIZATIONS / TEAMS）を追加: プッシュ通知宛先（`notification_scope`）と掲示板表示範囲（`announcement_scope`）を独立制御する設計に更新。掲示板伝搬のプルモデル解決ロジック・スコープ×表示先対応表を追記 |
| 2026-02-21 | MEMBER 権限制御（Issue #8）対応: `role_permissions.is_default` カラム追加（TRUE=自動付与 / FALSE=天井のみ）。MEMBER 天井3件追加（MANAGE_ANNOUNCEMENTS / DELETE_OTHERS_CONTENT / SEND_SAFETY_CONFIRMATION）。`team_permission_groups.target_role` カラム追加（DEPUTY_ADMIN / MEMBER）。MEMBER への追加権限付与フロー・3層制御設計を追記。Flyway V2.019〜V2.021 追加 |
| 2026-02-21 | org_type 変更フロー対応（Issue #9）: `org_type_verified` カラムを削除（自己申告制のため審査フラグ不要）。org_type 変更フローをビジネスロジックに追加。セキュリティ考慮事項の org_type 記述を「ADMIN による自己申告制・即時反映・audit_logs に記録」に更新。組織作成フローから SYSTEM_ADMIN への審査通知を削除 |
| 2026-02-21 | 精査・整合性修正: 組織作成フローの `org_type_verified` 残存を削除。ロール変更フローの権限グループ削除条件を「DEPUTY_ADMIN でも MEMBER でもない場合」に修正。メンバー一覧レスポンスの `permission_groups` 返却条件を MEMBER にも拡張。テーブル一覧の `team_permission_groups` 説明を DEPUTY_ADMIN / MEMBER 両対応に更新。ブロックフローのイベント種別を `TEAM/ORGANIZATION_MEMBER_REMOVED`（reason:BLOCK）→ `TEAM/ORGANIZATION_MEMBER_BLOCKED` に修正。チーム・組織のブロック解除フローを新設。未解決事項に `MANAGE_PAYMENTS` パーミッション追加タスクを追記 |
| 2026-02-21 | 招待トークンレートリミット・QRコード対応（Issue #10）: セキュリティ考慮事項のレートリミットをテーブル形式に整理し `POST /teams\|organizations/{id}/invite-tokens` に 10 req/hour per user を追加。`GET /api/v1/invite/{token}/qr` エンドポイントを追加（ZXing による動的 PNG 生成・S3 保存なし・`size` パラメータ対応）|
| 2026-03-08 | 組織階層の最大深さを3階層→`app.org.max-depth` 設定値（デフォルト: 5）に変更: 再帰構造（parent_organization_id）はどの深さにも対応するため、設定値変更だけで上限を調整可能。Service 層・CTE ともにハードコードを廃止し設定値を参照する設計に更新 |
| 2026-03-08 | `teams.template` の移行方針を確定: Phase 2 は VARCHAR(50) + アプリ層 enum 定数バリデーションで運用。`team_templates` テーブルへの FK 移行はメタデータが必要になった段階でテンプレート管理 feature doc にて実施。テーブル定義・制約備考・未解決事項を更新 |
| 2026-03-08 | `user_roles` 一意性保証を確定: STORED 生成列 `scope_key`（COALESCE 式）+ `UNIQUE KEY uq_ur_user_scope (user_id, scope_key)` を採用。テーブル定義・インデックス・制約備考を更新。Flyway V2.008 コメントに生成列定義を明記。未解決事項を解決済みに変更 |
| 2026-03-08 | チーム・組織論理削除フローを追加: 削除時に紐付く invite_tokens を一括失効（revoked_at = NOW()）する設計に確定。invite_tokens 制約・備考に「チーム/組織論理削除時の扱い」を追記。組織削除時は直属トークンのみ失効（子チームのトークンはそのまま）。未解決事項を解決済みに変更 |
| 2026-03-08 | 招待トークン発行者退会時の扱いを確定: 自動失効なし・`created_by` は SET NULL on delete で有効のまま残す設計を `invite_tokens` 制約・備考に明記。理由（運用継続性・管理責任の所在・手動 revoke が代替手段）を記載 |
| 2026-03-08 | 組織レベル権限グループ対応: `team_permission_groups` → `permission_groups`、`team_permission_group_permissions` → `permission_group_permissions` にリネームし、`organization_id` カラム（XOR 制約 `chk_pg_scope`）を追加。チーム・組織スコープを単一テーブルで共通管理する設計に変更。組織向け権限グループ管理 API（`GET/POST/PATCH/DELETE /organizations/{id}/permission-groups` および `PUT /organizations/{id}/members/{userId}/permission-groups`）を追加。権限解決ロジック・3層制御説明をテーブル名変更・スコープ分岐の注記追加に合わせ更新。Flyway V2.022 追加。|
| 2026-03-09 | `MANAGE_PAYMENTS` パーミッションを確定: Phase 3 V3.008 で追加（SYSTEM_ADMIN✓ / ADMIN✓ / DEPUTY_ADMIN△ / MEMBER なし）。permissions シード表に追記・role_permissions シード表に行追加・Phase 3 Flyway マイグレーション定義を追加。scope=TEAM（F04 設計時に再確認予定）|
| 2026-03-10 | `MANAGE_PAYMENTS` の scope を TEAM → ORGANIZATION に確定（F04 で組織レベル支払い管理 API が確定したため）。Flyway マイグレーション名を `V3.xxx` → `V3.007` に採番（F04 でのマイグレーション統合に伴い変更）。未解決事項の scope 変更検討を解決済みに更新 |
| 2026-03-09 | MEMBER 権限をオーバーライドモデルに変更: グループ割り当て時は is_default を無視しグループ内権限のみを実効権限とする設計に統一。権限解決ロジック step 6・MEMBER 天井定義（is_default=FALSE の3件→ 全6件）・3層制御説明・デフォルト権限注記・MEMBER 権限グループ設定フロー（例1:追加維持 / 例2:制限）を更新 |
| 2026-03-09 | 組織階層の最大深さをアプリ層固定から `app.org.max-depth` 設定値（デフォルト: 5）への外出しに修正: 再帰構造を活かした拡張性確保のため。階層構造の説明・例・CTE（`:maxDepth - 1`）・組織作成フロー・organizations テーブル備考・未解決事項を更新 |
| 2026-03-09 | 自分の所属一覧 API を追加: `GET /me/teams` / `GET /me/organizations` エンドポイントを追加。論理削除済みは常に除外、アーカイブ済みは `include_archived` パラメータで制御。ロール・参加日時を含むレスポンス仕様・エラーレスポンスを定義 |
| 2026-03-09 | セキュリティ修正: 招待参加フローにブロック済みチェックを追加（step 5）。team_blocks / organization_blocks にエントリが存在するユーザーは招待トークンを保持していても参加不可（403）。セキュリティ考慮事項に「ブロック済みユーザーの招待参加防止」を追記 |
| 2026-03-09 | チーム-組織の多対多所属設計に変更: `teams.organization_id`（単一FK）を廃止し `team_org_memberships` 中間テーブル（PENDING/ACTIVE ステータス管理・物理削除）を導入。1つのチームが複数の組織に同時所属可能に。チーム作成は常に独立状態で開始し組織への所属は招待フロー経由で行う設計に統一。チーム-組織招待フロー6種（招待送信/承認/拒否/取消/自主離脱/除名）・API エンドポイント10件を追加。カスケード通知 CTE の TEAM/INDIVIDUAL スコープクエリを junction table 経由に更新。`visibility = ORGANIZATION_ONLY` の前提条件を `team_org_memberships` ACTIVE 存在チェックに変更。Flyway V2.023 追加 |
| 2026-03-09 | 親リソース認可チェックを追加: チーム作成（organization_id 指定時）および子組織作成（parent_organization_id 指定時）に、親リソースの ADMIN チェックを必須化。存在しない・論理削除済みは 404、権限不足は 403。DEPUTY_ADMIN への CREATE_TEAM 委任は Phase 3 以降で検討。セキュリティ考慮事項に「親リソース認可チェック」項目を追加 |
| 2026-03-09 | 自主退会フローを追加: `DELETE /teams/{id}/me` / `DELETE /organizations/{id}/me` エンドポイントを追加。最後のADMIN保護（422）・SUPPORTER は `/follow` へ誘導（422）・user_permission_groups の削除・audit_logs TEAM/ORGANIZATION_MEMBER_LEFT（reason: SELF_DEPARTURE）を定義。支払いデータは削除しない（F04 参照）。組織退会は組織直属ロールのみ削除し配下チームへの所属は保持する設計 |
| 2026-03-09 | 精査対応（記載不具合修正）: ① role_permissions シードの `MANAGE_PAYMENTS` 行末注釈をテーブル外に移動（Markdown 崩れ修正）② DEPUTY_ADMIN 3層制御の件数を「Phase 2 時点で11件、Phase 3 以降 12件」に修正 ③ ロール変更フロー step 7 に DEPUTY_ADMIN→MEMBER 遷移時の `target_role='DEPUTY_ADMIN'` グループ削除を明記 ④ 変更履歴の日付順序を時系列順（2026-03-08→2026-03-09）に整列 ⑤ Section 2 MEMBER ロール説明をオーバーライドモデルに合わせ修正 ⑥ `user_roles` 制約備考に CHECK 制約（`chk_ur_scope`）を追加・Flyway V2.008 コメントに反映 |
| 2026-03-09 | `invite_tokens` に DB レベルの XOR CHECK 制約（`chk_it_scope`）を追加: 制約備考の「アプリ層でバリデーション」を `chk_it_scope CHECK ((team_id IS NULL) != (organization_id IS NULL))` に変更。`permission_groups.chk_pg_scope` と同方式。Flyway V2.012 コメントに反映 |
| 2026-03-09 | 一覧取得系 API に visibility 依存の認可ルール・返却粒度を追加（B-7 対応）: ① `GET /teams/{id}/members` に認可ルール表（PUBLIC/ORGANIZATION_ONLY/PRIVATE × アクセス可能者）・ロール別返却フィールド表・エラーレスポンスを追記。ADMIN/DEPUTY_ADMIN は全フィールド、MEMBER/SUPPORTER/GUEST および非メンバー（PUBLIC）は基本プロフィールのみ返却。② `GET /organizations/{id}/members` の仕様セクションを新設（組織は PUBLIC/PRIVATE の2値のみ、ロール別返却粒度はチームと同一）。③ `GET /organizations/{id}/teams` の仕様セクションを新設（PRIVATE 組織は組織メンバーのみ閲覧可・返却チームは各 visibility でフィルタ）。④ `GET /teams/{id}/organizations` の仕様セクションを新設（PRIVATE チームはチームメンバーのみ閲覧可・返却組織は各 visibility でフィルタ）。エンドポイント一覧の認証欄を visibility 依存の注記に更新 |
| 2026-03-09 | アーカイブ・アーカイブ解除 API とフローを追加（B-8 対応）: ① エンドポイント一覧に `PATCH /teams/{id}/archive`・`/unarchive`・`PATCH /organizations/{id}/archive`・`/unarchive` を追加（ADMIN 専用・204 No Content）。② 自動アーカイブバッチ（チームのみ・毎月1日 03:00 JST）をビジネスロジックに追加。判定条件: 全メンバーの最終ログイン（`users.last_login_at`・F02 クロスフィーチャー参照）の最大値が 12ヶ月超過。SUPPORTER 含む全ロールを対象。NULL ログインは `COALESCE(last_login_at, '1970-01-01')` で処理。③ 手動アーカイブ / 解除フローを追加（アーカイブ時に `invite_tokens` を一括失効・audit_logs に reason: MANUAL）。④ アーカイブ状態の書き込み制限一覧（F03 スコープのブロック対象 / 許可対象操作）を追加。F04・F05 等への横断チェック指示を注記。⑤ 組織自動アーカイブ条件が未定義のため未解決事項に追記 |
| 2026-03-09 | 未認証招待 API にレートリミットを追加（C-1・C-2 対応）: `GET /invite/{token}` と `GET /invite/{token}/qr` に 10 req/min per IP を追加。既存テーブルに認証列を追加し per IP / per user の使い分けを明記。optional-auth GET エンドポイント（`/teams/{id}`・`/organizations/{id}` 等）は将来対応（現時点で厳格制限は不採用）とする理由を注記 |
| 2026-03-09 | 招待 API エラーレスポンス・QR キャッシュ対応（C-2 補完）: `GET /invite/{token}` と `GET /invite/{token}/qr` のエラーレスポンス表に `429 Too Many Requests`（レートリミット超過）を追記。`GET /invite/{token}/qr` に ZXing PNG 生成の CPU コストを踏まえた Valkey / オンヒープキャッシュ推奨注記（キー: `{token}:{size}`・TTL 5分）を追記 |
| 2026-03-10 | 組織自動アーカイブバッチを追加（未解決事項解決）: 案②（直接所属メンバー + ACTIVE 所属チーム全メンバーの最終ログイン12ヶ月超過）を採用。C3 条件として有効な `member_payments` 不存在を追加（F04 設計確定後に実装・現時点は SQL コメントアウト）。カスケードアーカイブ対象を「このorgにのみ ACTIVE 所属するチーム」に限定（多対多所属チームへの誤波及を防止）。子組織はカスケード対象外。手動アーカイブフローをチーム / 組織で分割し、組織フローにカスケードロジック（MANUAL_CASCADE_ORG）を追加。バッチ SQL・フロー（6〜8ステップ）・audit_logs reason を明記 |
| 2026-03-10 | 精査: F03 設計完了・不整合修正 10件。① 論理削除と ON DELETE CASCADE の混同を3箇所修正（team_org_memberships 制約備考・チーム論理削除フロー step 5 コメント・組織論理削除フローにステップ欠落→step 5 追加）② 組織論理削除フローの注記に user_roles 保持方針を明記 ③ C3 自動アーカイブ条件のカラム名を F04 確定設計に合わせ `expires_at > NOW()` → `valid_until >= CURDATE()` に修正（C3 SQL も `status = 'PAID'` + `valid_until IS NULL` 考慮に更新）④ セキュリティ考慮事項の「POST /teams（organization_id 指定時）」を削除（V2.023 で teams.organization_id は DROP 済み・チーム作成は常に独立）⑤ ステータスを設計完了・最終更新日を 2026-03-10 に更新 ⑥ 変更履歴の V3.008 → V3.007 を反映 |
| 2026-03-10 | 精査②: 不整合修正 4件。① 招待トークン作成フローを追加（role_id バリデーション: ADMIN は priority >= 3 のみ許可、SYSTEM_ADMIN は priority >= 2 まで許可。ADMIN/SYSTEM_ADMIN ロールのトークン作成を防止）② チームブロックフロー・組織ブロックフローに `user_permission_groups` 削除ステップを追加（除名時と同様にグループ割り当てを確実にクリーンアップ）③ ロール変更フロー step 7 に MEMBER → DEPUTY_ADMIN 遷移時の `target_role='MEMBER'` グループ削除を追加（異なる target_role のグループが残存する問題を修正）④ 招待トークン作成フローのビジネスロジックセクションを新設 |
| 2026-03-10 | 精査③: 不整合修正 4件。① `POST /invite/{token}/join` エラーレスポンス表に 403（ブロック済み）・422（アーカイブ済み）を追加 ② 招待参加フローにアーカイブチェックステップ（step 5）を追加（アーカイブ制限一覧との整合性確保）③ エンドポイント一覧の招待トークン管理 6 エンドポイント（POST/GET/DELETE × チーム/組織）の認証欄に DEPUTY_ADMIN 委譲（INVITE_MEMBERS + MANAGE_INVITE_TOKENS 権限）を反映 ④ `GET /organizations/{id}/teams` の ORGANIZATION_ONLY 返却条件コメントを修正（PUBLIC 組織では非メンバーもアクセス可能な点を考慮） |
| 2026-03-10 | 精査④: 不整合修正 3件。① アーカイブ書き込み制限テーブルに組織スコープ操作を追加（ブロック対象: `PATCH /organizations/{id}` 等 7 操作、許可対象: `DELETE /organizations/{id}` 等 6 操作）② セキュリティ考慮事項のブロック済みチェック説明を「有効性チェックの直後に」→「有効性チェック → アーカイブチェック → ブロック済みチェックの順で検証」に修正（精査③で挿入したアーカイブチェックとの整合性確保）③ ブロックエンドポイント説明の「SUPPORTER ロールも同時除名」→「ロールも同時除名」に修正（実装フローは全ロールを `user_roles` から削除するため） |
| 2026-03-11 | PaginationMeta に has_next フィールド追加（共通レスポンス統一） |
| 2026-03-12 | UX・保守性改善 10件: ① `GET /teams/search` / `GET /organizations/search` 公開検索 API を追加（visibility=PUBLIC のみ・名前/地域/種別検索） ② メンバー一覧 API にフィルタ（`role` / `q`）・ソート（`sort`）パラメータを追加 ③ `GET /teams/{id}/me/permissions` / `GET /organizations/{id}/me/permissions` 実効パーミッション API を追加（フロントエンド UI 制御用） ④ チーム/組織レスポンスに `member_count` フィールドを追加（`/me/teams` / `/me/organizations` / `/organizations/{id}/teams` / `/teams/{id}/organizations` / 検索 API） ⑤ `team_blocks` / `organization_blocks` に `reason VARCHAR(500) NULL` カラムを追加（ブロック理由の記録・一覧表示） ⑥ `POST /teams/{id}/transfer-ownership` / `POST /organizations/{id}/transfer-ownership` ADMIN 権限移譲 API を追加（1ステップ: 対象→ADMIN + 自分→DEPUTY_ADMIN を1トランザクション内で実行・2FA 必須チェックあり） ⑦ 全一覧取得 API をカーソルベースページネーションに統一（`cursor` + `size` + `next_cursor` + `has_next`） ⑧ 権限解決 Valkey キャッシュ戦略を追加（キー: `perm:{user_id}:{scope_key}`・TTL 5分・ロール変更/グループ更新時に `@CacheEvict`） ⑨ `PATCH /teams/{id}/restore` / `PATCH /organizations/{id}/restore` 論理削除復元 API を追加（SYSTEM_ADMIN 専用・誤削除復旧用） ⑩ 招待参加フローの audit_logs メタデータに `invite_token_id` を追加（参加経路の事後追跡用） |
| 2026-04-18 | F04.10 組織委員会機能の追加に伴う追記。組織配下のサブスコープとして Committee を導入（詳細は F04.10_committee.md 参照） |
| 2026-06-09 | チーム・組織スラッグ移行: `teams`/`organizations` テーブルに `slug VARCHAR(30) NOT NULL UNIQUE` を追加。全 API パスの `{id}`（チーム・組織識別子）を `{slug}` に統一。Flyway V71.20260609001/002 を追加。セキュリティ考慮事項のレートリミット表・optional-auth 注記を slug 表記に更新 |
| 2026-06-14 | **slug を村方式（ユーザー任意入力）に正準化**（マスター御裁可）: ① §5.9「slug 正準仕様」節を新設（村 `F17.1` と同じくユーザーが作成時に任意 slug を入力・形式 `^[a-z0-9][a-z0-9-]{1,28}[a-z0-9]$`・グローバル一意・予約語禁止）。② `name` からの自動生成を「編集可能な提案プレフィル」に降格し、**強制連番付与（`team-000017`）を廃止方針**に。③ `slug-check` API による可用性チェックを正準フローに明記。④ 既存連番 slug の是正方針（一括強制リネームせず、設定画面でユーザー自身が変更可能・連番該当のみ促す）。⑤ slug リネーム時の **301 リダイレクト用履歴**（`team_slug_history` 等・後続 wave 実装予定）。⑥ 予約語（`new`/`search`/`admin`/`settings`/`me`/`public` 等）をマスタ管理。⑦ **public_id（UUID）路線の不採用**と根拠（列挙防御=F00 認可が本丸／SEO 公開と矛盾／非推測化は村方式のユーザー任意入力で担保）を明記。⑧ V71 退行（slug 入力欄喪失→連番 backfill）を「教訓」として記録。⑨ `01_db_design.md` の slug 制約備考・`02_api_design.md` の `POST /teams`（および `POST /organizations`）リクエストボディに `slug` フィールド（誤って欠落していた退行）を復元。slug は既存 BIGINT テーブル上の URL 識別子であり主キー UUIDv7 方針（新規テーブル）とは別軸である旨を補足 |

---

## 付録: 組織委員会機能（F04.10）との連携

F04.10 で新設される **組織委員会（Committee）** は、本設計書の組織階層に新しいサブスコープとして追加される。

### 階層上の位置付け

```
Organization
  ├─ Teams (既存)
  ├─ Sub-Organizations (既存)
  └─ Committees (F04.10 で新設) — 組織配下の「閉じた意思決定グループ」
```

- Committee は必ず組織配下に所属する（v1 ではチーム配下の委員会は扱わない）
- Committee のメンバーシップは既存の `organization_members` / `team_members` とは **独立**。`committee_members` テーブルで管理
- Committee 内の役割（CHAIR / VICE_CHAIR / SECRETARY / MEMBER）は本設計書のロール体系（ADMIN / DEPUTY_ADMIN / MEMBER 等）とは別軸

### ORG_ADMIN の委員会に対する権限

- 組織 ADMIN は配下のすべての委員会を作成・解散（ARCHIVE）できる
- 監査目的で全委員会のコンテンツを閲覧可能（閲覧時は F10.3 監査ログに記録）
- 委員会内ロール（CHAIR 等）の直接変更はできない（CHAIR からの要請がある場合のみ対応）

### DEPUTY_ADMIN への委譲

- `MANAGE_COMMITTEE` 権限を新設。DEPUTY_ADMIN に付与された場合のみ委員会の作成・解散が可能
- 既存の権限グループシステム（`permission_groups`）経由で配布

### 関連マイグレーション

- V9.080〜V9.082: `committees` / `committee_members` / `committee_invitations` テーブル作成
- 既存 `organizations` への FK 参照（ON DELETE CASCADE）により、組織削除時に配下の委員会も自動削除
