## 4. API設計

### エンドポイント一覧
| メソッド | パス | 認証 | 説明 |
|---------|-----|------|------|
| GET | `/api/v1/teams/search` | 任意 | チーム公開検索（visibility=PUBLIC のみ対象・名前/地域/テンプレートで検索）|
| GET | `/api/v1/teams/slug-check?slug={slug}` | 必要 | スラッグ使用可否チェック（チーム）。レスポンス: `{ available: boolean, suggestions: string[] }`。レートリミット: 60 req/min/user |
| GET | `/api/v1/organizations/search` | 任意 | 組織公開検索（visibility=PUBLIC のみ対象・名前/地域/種別で検索）|
| GET | `/api/v1/organizations/slug-check?slug={slug}` | 必要 | スラッグ使用可否チェック（組織）。レスポンス: `{ available: boolean, suggestions: string[] }`。レートリミット: 60 req/min/user |
| POST | `/api/v1/organizations` | 必要 | 組織作成 |
| GET | `/api/v1/organizations/{slug}` | 任意 | 組織詳細取得（可視性による）。`{slug}`: 組織のURLスラッグ（例: fc-tokyo-association）|
| PATCH | `/api/v1/organizations/{slug}` | 必要（ADMIN+）| 組織情報更新 |
| DELETE | `/api/v1/organizations/{slug}` | 必要（ADMIN+）| 組織論理削除 |
| GET | `/api/v1/organizations/{slug}/members` | 必要（visibility = PUBLIC は外部閲覧可）| 組織メンバー一覧（直接所属・visibility 依存の認可・返却粒度あり）|
| PATCH | `/api/v1/organizations/{slug}/members/{userId}/role` | 必要（ADMIN）| 組織メンバーロール変更 |
| DELETE | `/api/v1/organizations/{slug}/members/{userId}` | 必要（ADMIN）| 組織メンバー除名 |
| POST | `/api/v1/organizations/{slug}/invite-tokens` | 必要（ADMIN / DEPUTY_ADMIN※）| 組織招待トークン発行（※INVITE_MEMBERS + MANAGE_INVITE_TOKENS 権限必要）|
| GET | `/api/v1/organizations/{slug}/invite-tokens` | 必要（ADMIN / DEPUTY_ADMIN※）| 組織招待トークン一覧（※MANAGE_INVITE_TOKENS 権限必要）|
| DELETE | `/api/v1/organizations/{slug}/invite-tokens/{tokenId}` | 必要（ADMIN / DEPUTY_ADMIN※）| 組織招待トークン失効（※MANAGE_INVITE_TOKENS 権限必要）|
| POST | `/api/v1/teams` | 必要 | チーム作成 |
| GET | `/api/v1/teams/{slug}` | 任意 | チーム詳細取得（可視性による）。`{slug}`: チームのURLスラッグ（例: fc-tokyo）|
| PATCH | `/api/v1/teams/{slug}` | 必要（ADMIN+）| チーム情報更新 |
| DELETE | `/api/v1/teams/{slug}` | 必要（ADMIN+）| チーム論理削除 |
| GET | `/api/v1/teams/{slug}/members` | 必要（visibility = PUBLIC / ORGANIZATION_ONLY は外部閲覧可）| チームメンバー一覧（visibility 依存の認可・返却粒度あり）|
| PATCH | `/api/v1/teams/{slug}/members/{userId}/role` | 必要（ADMIN）| メンバーロール変更 |
| DELETE | `/api/v1/teams/{slug}/members/{userId}` | 必要（ADMIN）| メンバー除名 |
| POST | `/api/v1/teams/{slug}/invite-tokens` | 必要（ADMIN / DEPUTY_ADMIN※）| チーム招待トークン発行（※INVITE_MEMBERS + MANAGE_INVITE_TOKENS 権限必要）|
| GET | `/api/v1/teams/{slug}/invite-tokens` | 必要（ADMIN / DEPUTY_ADMIN※）| チーム招待トークン一覧（※MANAGE_INVITE_TOKENS 権限必要）|
| DELETE | `/api/v1/teams/{slug}/invite-tokens/{tokenId}` | 必要（ADMIN / DEPUTY_ADMIN※）| 招待トークン失効（※MANAGE_INVITE_TOKENS 権限必要）|
| GET | `/api/v1/invite/{token}` | 不要 | 招待プレビュー（参加前確認）|
| POST | `/api/v1/invite/{token}/join` | 必要 | 招待URLで参加 |
| GET | `/api/v1/teams/{slug}/permission-groups` | 必要（ADMIN）| 権限グループ一覧（`?target_role=DEPUTY_ADMIN\|MEMBER` でフィルタ可）|
| POST | `/api/v1/teams/{slug}/permission-groups` | 必要（ADMIN）| 権限グループ作成（`target_role` で DEPUTY_ADMIN / MEMBER を指定）|
| PATCH | `/api/v1/teams/{slug}/permission-groups/{groupId}` | 必要（ADMIN）| 権限グループ更新 |
| DELETE | `/api/v1/teams/{slug}/permission-groups/{groupId}` | 必要（ADMIN）| 権限グループ論理削除 |
| PUT | `/api/v1/teams/{slug}/members/{userId}/permission-groups` | 必要（ADMIN）| DEPUTY_ADMIN / MEMBER への権限グループ一括設定（ユーザーのロールに対応する `target_role` のグループのみ割り当て可）|
| GET | `/api/v1/organizations/{slug}/permission-groups` | 必要（ADMIN）| 組織権限グループ一覧（`?target_role=DEPUTY_ADMIN\|MEMBER` でフィルタ可）|
| POST | `/api/v1/organizations/{slug}/permission-groups` | 必要（ADMIN）| 組織権限グループ作成（`target_role` で DEPUTY_ADMIN / MEMBER を指定）|
| PATCH | `/api/v1/organizations/{slug}/permission-groups/{groupId}` | 必要（ADMIN）| 組織権限グループ更新 |
| DELETE | `/api/v1/organizations/{slug}/permission-groups/{groupId}` | 必要（ADMIN）| 組織権限グループ論理削除 |
| PUT | `/api/v1/organizations/{slug}/members/{userId}/permission-groups` | 必要（ADMIN）| 組織 DEPUTY_ADMIN / MEMBER への権限グループ一括設定 |
| GET | `/api/v1/teams/{slug}/me/permissions` | 必要 | 自分の実効パーミッション一覧（対象チームでの権限確認用）|
| GET | `/api/v1/organizations/{slug}/me/permissions` | 必要 | 自分の実効パーミッション一覧（対象組織での権限確認用）|
| POST | `/api/v1/teams/{slug}/transfer-ownership` | 必要（ADMIN）| チーム ADMIN 権限移譲（1ステップ: 対象→ADMIN、自分→DEPUTY_ADMIN）|
| POST | `/api/v1/organizations/{slug}/transfer-ownership` | 必要（ADMIN）| 組織 ADMIN 権限移譲（1ステップ: 対象→ADMIN、自分→DEPUTY_ADMIN）|
| GET | `/api/v1/permissions` | 必要（ADMIN+）| パーミッションカタログ一覧 |
| GET | `/api/v1/me/teams` | 必要 | 自分が所属するチーム一覧（ロール・参加日時付き）|
| GET | `/api/v1/me/organizations` | 必要 | 自分が所属する組織一覧（ロール・参加日時付き）|
| POST | `/api/v1/teams/{slug}/follow` | 必要 | チームをフォロー（SUPPORTER 自己登録・招待コード不要）|
| DELETE | `/api/v1/teams/{slug}/follow` | 必要 | フォロー解除（自分の SUPPORTER ロールを削除）|
| GET | `/api/v1/teams/{slug}/blocks` | 必要（ADMIN）| ブロック一覧 |
| POST | `/api/v1/teams/{slug}/blocks` | 必要（ADMIN/DEPUTY_ADMIN）| ユーザーをブロック（自己登録禁止・現在のロールも同時除名）|
| DELETE | `/api/v1/teams/{slug}/blocks/{userId}` | 必要（ADMIN/DEPUTY_ADMIN）| ブロック解除 |
| POST | `/api/v1/organizations/{slug}/follow` | 必要 | 組織をフォロー（SUPPORTER 自己登録・招待コード不要）|
| DELETE | `/api/v1/organizations/{slug}/follow` | 必要 | 組織フォロー解除 |
| GET | `/api/v1/organizations/{slug}/blocks` | 必要（ADMIN）| 組織ブロック一覧 |
| POST | `/api/v1/organizations/{slug}/blocks` | 必要（ADMIN/DEPUTY_ADMIN）| 組織ユーザーをブロック |
| DELETE | `/api/v1/organizations/{slug}/blocks/{userId}` | 必要（ADMIN/DEPUTY_ADMIN）| 組織ブロック解除 |
| GET | `/api/v1/organizations/{slug}/members/all` | 必要（ADMIN+）| 組織サブツリーの全メンバー一覧（WITH RECURSIVE で全子組織・子チームを網羅・カスケード通知対象確認用）|
| DELETE | `/api/v1/teams/{slug}/me` | 必要 | 自主退会（ADMIN / DEPUTY_ADMIN / MEMBER が自ら離脱。SUPPORTER は `DELETE /teams/{slug}/follow` を使用）|
| DELETE | `/api/v1/organizations/{slug}/me` | 必要 | 自主退会（ADMIN / DEPUTY_ADMIN / MEMBER が自ら離脱。SUPPORTER は `DELETE /organizations/{slug}/follow` を使用）|
| PATCH | `/api/v1/teams/{slug}/archive` | 必要（ADMIN）| チームを手動アーカイブ（`archived_at = NOW()`。招待トークン失効・以降の書き込み操作ブロック）|
| PATCH | `/api/v1/teams/{slug}/unarchive` | 必要（ADMIN）| チームアーカイブ解除（`archived_at = NULL`。書き込み操作を再開）|
| PATCH | `/api/v1/organizations/{slug}/archive` | 必要（ADMIN）| 組織を手動アーカイブ（`archived_at = NOW()`。招待トークン失効・書き込み操作ブロック）|
| PATCH | `/api/v1/organizations/{slug}/unarchive` | 必要（ADMIN）| 組織アーカイブ解除（`archived_at = NULL`）|
| PATCH | `/api/v1/teams/{slug}/restore` | 必要（SYSTEM_ADMIN）| 論理削除済みチームの復元（`deleted_at = NULL`）|
| PATCH | `/api/v1/organizations/{slug}/restore` | 必要（SYSTEM_ADMIN）| 論理削除済み組織の復元（`deleted_at = NULL`）|
| POST | `/api/v1/organizations/{slug}/team-invites` | 必要（ADMIN）| 組織からチームへ所属招待を送信 |
| GET | `/api/v1/organizations/{slug}/team-invites` | 必要（ADMIN）| 送信済み招待一覧（PENDING のみ）|
| DELETE | `/api/v1/organizations/{slug}/team-invites/{teamId}` | 必要（ADMIN）| 招待取消（PENDING を削除）|
| DELETE | `/api/v1/organizations/{slug}/teams/{teamId}` | 必要（ADMIN）| 所属チームを除名（ACTIVE を削除）|
| GET | `/api/v1/organizations/{slug}/teams` | 必要 | 組織に所属するチーム一覧（ACTIVE のみ）|
| GET | `/api/v1/teams/{slug}/organizations` | 必要 | チームが所属する組織一覧（ACTIVE のみ）|
| GET | `/api/v1/organizations/{slug}/ancestors` | 任意 | 上位組織チェーン取得（root → 親の順。`hierarchy_visibility` を尊重）|
| GET | `/api/v1/organizations/{slug}/children` | 任意 | 下位組織一覧（直近の子のみ・`visibility` で可視範囲フィルタ）|
| GET | `/api/v1/teams/{slug}/org-invites` | 必要（ADMIN）| 受信した組織招待一覧（PENDING のみ）|
| POST | `/api/v1/teams/{slug}/org-invites/{membershipId}/accept` | 必要（ADMIN）| 組織招待を承認（PENDING → ACTIVE）|
| POST | `/api/v1/teams/{slug}/org-invites/{membershipId}/reject` | 必要（ADMIN）| 組織招待を拒否（PENDING を削除）|
| DELETE | `/api/v1/teams/{slug}/organizations/{orgSlug}` | 必要（ADMIN）| チームが組織から自主離脱（ACTIVE を削除）|
| GET | `/api/v1/invite/{token}/qr` | 不要 | 招待QRコード画像取得（PNG）|
| PATCH | `/api/v1/organizations/{slug}/profile` | 必要（ADMIN / DEPUTY_ADMIN※）| 組織プロフィール拡張項目の一括更新（homepage_url / established_date / philosophy / profile_visibility）。※MANAGE_ORGANIZATION 権限必要 |
| PATCH | `/api/v1/teams/{slug}/profile` | 必要（ADMIN / DEPUTY_ADMIN※）| チームプロフィール拡張項目の一括更新。※MANAGE_TEAM 権限必要 |
| GET | `/api/v1/organizations/{slug}/officers` | 任意 | 組織役員一覧（可視性と `is_visible` に基づくフィルタ）|
| POST | `/api/v1/organizations/{slug}/officers` | 必要（ADMIN / DEPUTY_ADMIN※）| 役員追加（最大50件）|
| PATCH | `/api/v1/organizations/{slug}/officers/{officerId}` | 必要（ADMIN / DEPUTY_ADMIN※）| 役員編集（氏名・役職・is_visible）|
| DELETE | `/api/v1/organizations/{slug}/officers/{officerId}` | 必要（ADMIN / DEPUTY_ADMIN※）| 役員削除（物理削除）|
| PUT | `/api/v1/organizations/{slug}/officers/reorder` | 必要（ADMIN / DEPUTY_ADMIN※）| 役員並び替え（display_order の一括更新）|
| GET | `/api/v1/teams/{slug}/officers` | 任意 | チーム役員一覧 |
| POST | `/api/v1/teams/{slug}/officers` | 必要（ADMIN / DEPUTY_ADMIN※）| チーム役員追加 |
| PATCH | `/api/v1/teams/{slug}/officers/{officerId}` | 必要（ADMIN / DEPUTY_ADMIN※）| チーム役員編集 |
| DELETE | `/api/v1/teams/{slug}/officers/{officerId}` | 必要（ADMIN / DEPUTY_ADMIN※）| チーム役員削除 |
| PUT | `/api/v1/teams/{slug}/officers/reorder` | 必要（ADMIN / DEPUTY_ADMIN※）| チーム役員並び替え |
| GET | `/api/v1/organizations/{slug}/custom-fields` | 任意 | 組織カスタムフィールド一覧（可視性と `is_visible` に基づくフィルタ）|
| POST | `/api/v1/organizations/{slug}/custom-fields` | 必要（ADMIN / DEPUTY_ADMIN※）| カスタムフィールド追加（最大20件）|
| PATCH | `/api/v1/organizations/{slug}/custom-fields/{fieldId}` | 必要（ADMIN / DEPUTY_ADMIN※）| カスタムフィールド編集 |
| DELETE | `/api/v1/organizations/{slug}/custom-fields/{fieldId}` | 必要（ADMIN / DEPUTY_ADMIN※）| カスタムフィールド削除 |
| PUT | `/api/v1/organizations/{slug}/custom-fields/reorder` | 必要（ADMIN / DEPUTY_ADMIN※）| カスタムフィールド並び替え |
| GET | `/api/v1/teams/{slug}/custom-fields` | 任意 | チームカスタムフィールド一覧 |
| POST | `/api/v1/teams/{slug}/custom-fields` | 必要（ADMIN / DEPUTY_ADMIN※）| チームカスタムフィールド追加 |
| PATCH | `/api/v1/teams/{slug}/custom-fields/{fieldId}` | 必要（ADMIN / DEPUTY_ADMIN※）| チームカスタムフィールド編集 |
| DELETE | `/api/v1/teams/{slug}/custom-fields/{fieldId}` | 必要（ADMIN / DEPUTY_ADMIN※）| チームカスタムフィールド削除 |
| PUT | `/api/v1/teams/{slug}/custom-fields/reorder` | 必要（ADMIN / DEPUTY_ADMIN※）| チームカスタムフィールド並び替え |

### リクエスト／レスポンス仕様

#### `POST /api/v1/teams`

**リクエストボディ**
```json
{
  "name": "FCマンシャフト",
  "nickname1": "マンシャフト",
  "nickname2": null,
  "template": "SPORTS",
  "prefecture": "東京都",
  "city": "渋谷区",
  "description": "東京を拠点とするサッカーチーム",
  "visibility": "PUBLIC"
}
```

**レスポンス（201 Created）**
```json
{
  "data": {
    "id": 1,
    "name": "FCマンシャフト",
    "visibility": "PUBLIC",
    "member_count": 1,
    "created_at": "2026-03-01T10:00:00Z"
  }
}
```

---

#### `GET /api/v1/teams/search`

`visibility = PUBLIC` のチームを検索する。未認証でも利用可能。

**クエリパラメータ**
| パラメータ | 型 | デフォルト | 説明 |
|-----------|---|-----------|------|
| `q` | String | — | チーム名の部分一致検索（`name` / `nickname1` / `nickname2` を OR 検索）|
| `prefecture` | String | — | 都道府県でフィルタ |
| `city` | String | — | 市区町村でフィルタ |
| `template` | String | — | テンプレート種別でフィルタ（例: `SPORTS`）|
| `cursor` | String | — | 次ページ取得用カーソル |
| `size` | Int | `20` | 1ページ件数（最大50）|

**レスポンス（200 OK）**
```json
{
  "data": [
    {
      "id": 1,
      "name": "FCマンシャフト",
      "nickname1": "マンシャフト",
      "icon_url": "https://cdn.mannschaft.app/teams/1/icon.webp",
      "prefecture": "東京都",
      "city": "渋谷区",
      "template": "SPORTS",
      "member_count": 24,
      "supporter_enabled": true
    }
  ],
  "meta": {
    "next_cursor": "eyJ...",
    "size": 20,
    "has_next": true
  }
}
```

> - `visibility = PUBLIC` のチームのみ返す（GUESTS_AND_ABOVE / SUPPORTERS_AND_ABOVE / MEMBERS_AND_ABOVE は対象外）
> - 論理削除済み・アーカイブ済みチームは常に除外
> - ソート: 名前昇順（デフォルト）
> - `member_count` はフォロワー（SUPPORTER）を含む全メンバー数

---

#### `GET /api/v1/organizations/search`

`visibility = PUBLIC` の組織を検索する。未認証でも利用可能。

**クエリパラメータ**
| パラメータ | 型 | デフォルト | 説明 |
|-----------|---|-----------|------|
| `q` | String | — | 組織名の部分一致検索（`name` / `nickname1` / `nickname2` を OR 検索）|
| `prefecture` | String | — | 都道府県でフィルタ |
| `city` | String | — | 市区町村でフィルタ |
| `org_type` | String | — | 組織種別でフィルタ（`NONPROFIT` / `FORPROFIT`）|
| `cursor` | String | — | 次ページ取得用カーソル |
| `size` | Int | `20` | 1ページ件数（最大50）|

**レスポンス（200 OK）**
```json
{
  "data": [
    {
      "id": 5,
      "name": "〇〇サッカー協会",
      "nickname1": null,
      "icon_url": "https://cdn.mannschaft.app/organizations/5/icon.webp",
      "prefecture": "東京都",
      "city": null,
      "org_type": "NONPROFIT",
      "member_count": 150,
      "supporter_enabled": true
    }
  ],
  "meta": {
    "next_cursor": null,
    "size": 20,
    "has_next": false
  }
}
```

> - `visibility = PUBLIC` の組織のみ返す（PRIVATE は対象外）
> - 論理削除済み・アーカイブ済み組織は常に除外
> - ソート: 名前昇順（デフォルト）

---

#### `GET /api/v1/teams/{slug}/members`

**認可ルール（visibility 依存）**

| チーム visibility | アクセス可能なユーザー |
|------------------|----------------------|
| `PUBLIC` | 任意の認証済みユーザー |
| `GUESTS_AND_ABOVE` | チームメンバー（任意ロール）が閲覧可（GUEST 以上）|
| `SUPPORTERS_AND_ABOVE` | チームのサポーター以上のロールを持つメンバーが閲覧可 |
| `MEMBERS_AND_ABOVE` | チームの正規メンバー以上のロールを持つメンバーのみ。それ以外は 403 |

**返却フィールドのロール別制限**

| 呼び出し者の区分 | 返却フィールド |
|-----------------|--------------|
| ADMIN / DEPUTY_ADMIN（チームメンバー）| 全フィールド（`user_id`, `display_name`, `icon_url`, `role`, `permission_groups`, `joined_at`）|
| MEMBER / SUPPORTER / GUEST（チームメンバー）| 基本プロフィール（`user_id`, `display_name`, `icon_url`, `role`）— `permission_groups` / `joined_at` は返さない |
| 非メンバー（PUBLIC への外部アクセス）| 基本プロフィールのみ（MEMBER と同内容）|

> - `permission_groups` は `role = DEPUTY_ADMIN` または `role = MEMBER`（権限グループが1件以上割り当て済み）のユーザーに返す。その他のロールまたは未割り当て MEMBER は空配列
> - 支払い状況・連絡先等の個人情報は本エンドポイントには含めない（F04 参照）。SUPPORTER が自身の関連メンバー（子など）の詳細を閲覧する機能は F04 で設計する

**クエリパラメータ**
| パラメータ | 型 | デフォルト | 説明 |
|-----------|---|-----------|------|
| `role` | String | — | ロールでフィルタ（例: `MEMBER`, `DEPUTY_ADMIN`。カンマ区切りで複数指定可: `MEMBER,GUEST`）|
| `q` | String | — | 名前の部分一致検索（`last_name` / `first_name`（実名）および `display_name` を OR 検索）|
| `sort` | String | `joined_at_asc` | ソート順。`joined_at_asc` / `joined_at_desc` / `name_asc` / `name_desc` / `role_asc` / `role_desc` |
| `cursor` | String | — | 次ページ取得用カーソル（前回レスポンスの `meta.next_cursor` を指定）|
| `size` | Int | `50` | 1ページ件数（最大100）|

**レスポンス（200 OK）**

※ デフォルトソート: `joined_at`（参加日時）昇順。

ADMIN / DEPUTY_ADMIN が取得した場合（全フィールド）:
```json
{
  "data": [
    {
      "user_id": 42,
      "display_name": "田中太郎",
      "icon_url": "https://cdn.mannschaft.app/users/42/icon.webp",
      "role": "DEPUTY_ADMIN",
      "permission_groups": [
        {"id": 1, "name": "受付・安否確認"}
      ],
      "joined_at": "2026-03-01T10:00:00Z"
    }
  ],
  "meta": {
    "next_cursor": "eyJ...",
    "size": 50,
    "has_next": true
  }
}
```

MEMBER / SUPPORTER / GUEST またはチーム非メンバー（PUBLIC / ORGANIZATION_ONLY）が取得した場合（基本プロフィールのみ）:
```json
{
  "data": [
    {
      "user_id": 42,
      "display_name": "田中太郎",
      "icon_url": "https://cdn.mannschaft.app/users/42/icon.webp",
      "role": "DEPUTY_ADMIN"
    }
  ],
  "meta": {
    "next_cursor": null,
    "size": 50,
    "has_next": false
  }
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 401 | 未認証 |
| 403 | `visibility = PRIVATE` かつ呼び出し者がチームメンバーでない / `visibility = ORGANIZATION_ONLY` かつ呼び出し者が当該チームのメンバーでも所属組織のメンバーでもない |
| 404 | チームが存在しない / 論理削除済み |

---

#### `POST /api/v1/teams/{slug}/invite-tokens`

**リクエストボディ**
```json
{
  "role_id": 4,
  "expires_in": "7d",
  "max_uses": 50
}
```

> `expires_in`: `1d` / `7d` / `30d` / `90d` / `unlimited`（`unlimited` は `expires_at = NULL`）

**レスポンス（201 Created）**
```json
{
  "data": {
    "id": 10,
    "token": "550e8400-e29b-41d4-a716-446655440000",
    "invite_url": "https://mannschaft.app/invite/550e8400-e29b-41d4-a716-446655440000",
    "role": "MEMBER",
    "expires_at": "2026-03-08T10:00:00Z",
    "max_uses": 50,
    "used_count": 0
  }
}
```

---

#### `GET /api/v1/invite/{token}`（未認証可）

**レスポンス（200 OK）**

チーム招待の場合:
```json
{
  "data": {
    "invite_type": "TEAM",
    "target": {
      "id": 1,
      "name": "FCマンシャフト",
      "icon_url": "https://cdn.mannschaft.app/teams/1/icon.webp"
    },
    "role": "MEMBER",
    "expires_at": "2026-03-08T10:00:00Z",
    "is_valid": true
  }
}
```

組織招待の場合:
```json
{
  "data": {
    "invite_type": "ORGANIZATION",
    "target": {
      "id": 5,
      "name": "〇〇サッカー協会",
      "icon_url": "https://cdn.mannschaft.app/organizations/5/icon.webp"
    },
    "role": "MEMBER",
    "expires_at": "2026-03-08T10:00:00Z",
    "is_valid": true
  }
}
```

> - `invite_type`: `"TEAM"` または `"ORGANIZATION"`
> - `target.id`: チームまたは組織の ID（`invite_type` で解釈を切り替える）
> - `is_valid = false` の場合は期限切れ・上限到達・手動失効のいずれか（詳細は返さない）
> - `target` は `is_valid = false` でも返す（参加先の名前をUI表示するため）

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 404 | トークンが存在しない |
| 429 | レートリミット超過（10 req/min per IP）|

---

#### `GET /api/v1/invite/{token}/qr`（未認証可）

招待URLをエンコードした QR コード画像を返す。`GET /invite/{token}` と同一のアクセス制御を適用する。

**レスポンス（200 OK）**

```
Content-Type: image/png
Body: QRコード PNG バイナリ（invite_url をエンコード・デフォルト 300×300px）
```

> - QR コードにエンコードする値は `https://mannschaft.app/invite/{token}` 形式の invite_url
> - `size` クエリパラメータ（任意・整数・px）でサイズ変更可能（最小64 / 最大1024 / デフォルト300）
> - バックエンドで ZXing ライブラリを使用して動的生成（S3 への保存は行わない）
> - **キャッシュ推奨**: ZXing による PNG 生成は CPU コストが高い。同一トークン・同一サイズへの連続リクエストに備え、生成済み画像を Valkey またはオンヒープキャッシュ（`{token}:{size}` をキーに TTL 5分）に保存し、キャッシュヒット時は再生成をスキップする実装を推奨する

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | `size` パラメータが範囲外 |
| 404 | トークンが存在しない |
| 429 | レートリミット超過（10 req/min per IP）|

---

#### `POST /api/v1/invite/{token}/join`

**リクエストボディ**: なし（認証ヘッダーからユーザーを取得）

**レスポンス（200 OK）**

チーム招待の場合:
```json
{
  "data": {
    "invite_type": "TEAM",
    "target": {
      "id": 1,
      "name": "FCマンシャフト"
    },
    "role": "MEMBER"
  }
}
```

組織招待の場合:
```json
{
  "data": {
    "invite_type": "ORGANIZATION",
    "target": {
      "id": 5,
      "name": "〇〇サッカー協会"
    },
    "role": "MEMBER"
  }
}
```

> `invite_type` によりフロントエンドが遷移先（チームダッシュボード or 組織ダッシュボード）を決定する。

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | トークン期限切れ・使用回数上限・手動失効 |
| 401 | 未認証（ログインしていない状態でのアクセス）|
| 403 | ブロック済みユーザー（`team_blocks` / `organization_blocks` にエントリが存在）|
| 409 | すでにメンバーとして参加済み |
| 422 | 招待先チーム / 組織がアーカイブ済み（`archived_at IS NOT NULL`）|

---

#### `PUT /api/v1/teams/{slug}/members/{userId}/permission-groups`

**リクエストボディ**
```json
{
  "group_ids": [1, 3]
}
```

> 既存の割り当てを一括置換する（差分でなく全上書き）。空配列で全グループ解除。

**レスポンス（200 OK）**
```json
{
  "data": {
    "user_id": 42,
    "permission_groups": [
      {"id": 1, "name": "受付・安否確認"},
      {"id": 3, "name": "スケジュール管理"}
    ]
  }
}
```

**エラーレスポンス（共通）**
| ステータス | 条件 |
|-----------|------|
| 400 | バリデーションエラー |
| 401 | 未認証 |
| 403 | 権限不足 |
| 404 | リソース不存在 |
| 409 | 競合（メンバー重複参加など）|
| 422 | ビジネスロジックエラー（独立チームに ORGANIZATION_ONLY 設定など）|

---

#### `GET /api/v1/organizations/{slug}/members`

**認可ルール（visibility 依存）**

| 組織 visibility | アクセス可能なユーザー |
|----------------|----------------------|
| `PUBLIC` | 任意の認証済みユーザー |
| `PRIVATE` | 組織メンバー（任意ロール）のみ。それ以外は 403 |

> 組織の `visibility` は `PUBLIC` / `PRIVATE` の2値のみ（チームの `ORGANIZATION_ONLY` は存在しない）

**返却フィールドのロール別制限**

| 呼び出し者の区分 | 返却フィールド |
|-----------------|--------------|
| ADMIN / DEPUTY_ADMIN（組織メンバー）| 全フィールド（`user_id`, `display_name`, `icon_url`, `role`, `permission_groups`, `joined_at`）|
| MEMBER / SUPPORTER / GUEST（組織メンバー）| 基本プロフィール（`user_id`, `display_name`, `icon_url`, `role`）|
| 非メンバー（PUBLIC 組織への外部アクセス）| 基本プロフィールのみ（MEMBER と同内容）|

> - `permission_groups` は `role = DEPUTY_ADMIN` または `role = MEMBER`（権限グループが1件以上割り当て済み）のユーザーに返す。その他のロールまたは未割り当て MEMBER は空配列
> - 支払い状況・連絡先等の個人情報は本エンドポイントには含めない（F04 参照）

**クエリパラメータ**
| パラメータ | 型 | デフォルト | 説明 |
|-----------|---|-----------|------|
| `role` | String | — | ロールでフィルタ（カンマ区切りで複数指定可）|
| `q` | String | — | 名前の部分一致検索 |
| `sort` | String | `joined_at_asc` | ソート順（チームメンバー一覧と同一の選択肢）|
| `cursor` | String | — | 次ページ取得用カーソル |
| `size` | Int | `50` | 1ページ件数（最大100）|

**レスポンス（200 OK）**

※ デフォルトソート: `joined_at`（参加日時）昇順。レスポンス構造・カーソルベースページネーション仕様は `GET /api/v1/teams/{slug}/members` と同一（`permission_groups` / `joined_at` の返却条件も同様）。

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 401 | 未認証 |
| 403 | `visibility = PRIVATE` かつ呼び出し者が組織メンバーでない |
| 404 | 組織が存在しない / 論理削除済み |

---

#### `GET /api/v1/organizations/{slug}/members/all`

**クエリパラメータ**
| パラメータ | 型 | デフォルト | 説明 |
|-----------|---|-----------|------|
| `scope` | String | `INDIVIDUAL` | 収集対象の範囲。`ORGANIZATION`（組織直属のみ）/ `TEAM`（チームメンバーのみ）/ `INDIVIDUAL`（全員）|
| `cursor` | String | — | 次ページ取得用カーソル |
| `size` | Int | `50` | 1ページ件数（最大200）|

**レスポンス（200 OK）**
```json
{
  "data": [
    {
      "user_id": 42,
      "display_name": "田中太郎",
      "icon_url": "https://cdn.mannschaft.app/users/42/icon.webp",
      "member_of": {
        "type": "ORGANIZATION",
        "id": 10,
        "name": "1年生"
      },
      "role": "ADMIN"
    },
    {
      "user_id": 55,
      "display_name": "鈴木花子",
      "icon_url": "https://cdn.mannschaft.app/users/55/icon.webp",
      "member_of": {
        "type": "TEAM",
        "id": 3,
        "name": "1年A組"
      },
      "role": "MEMBER"
    }
  ],
  "meta": {
    "next_cursor": "eyJ...",
    "size": 50,
    "has_next": true,
    "scope": "INDIVIDUAL"
  }
}
```

> - `member_of.type`: `"ORGANIZATION"` または `"TEAM"`（どのエンティティ経由で所属しているか）
> - カスケード通知の送信前に対象人数・構成を確認する用途を想定

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | `scope` の値が不正 |
| 403 | 権限不足（ADMIN 未満）|
| 404 | 組織が存在しない |

---

#### `GET /api/v1/me/teams`

**クエリパラメータ**
| パラメータ | 型 | デフォルト | 説明 |
|-----------|---|-----------|------|
| `include_archived` | Boolean | `false` | `true` にするとアーカイブ済みチームも含める |
| `cursor` | String | — | 次ページ取得用カーソル |
| `size` | Int | `50` | 1ページ件数（最大100）|

**レスポンス（200 OK）**
```json
{
  "data": [
    {
      "id": 1,
      "name": "FCマンシャフト",
      "icon_url": "https://cdn.mannschaft.app/teams/1/icon.webp",
      "visibility": "PUBLIC",
      "member_count": 24,
      "role": "ADMIN",
      "joined_at": "2026-03-01T10:00:00Z",
      "is_archived": false
    },
    {
      "id": 7,
      "name": "渋谷バスケ部",
      "icon_url": null,
      "visibility": "PRIVATE",
      "member_count": 8,
      "role": "MEMBER",
      "joined_at": "2026-02-15T09:00:00Z",
      "is_archived": false
    }
  ],
  "meta": {
    "next_cursor": null,
    "size": 50,
    "has_next": false
  }
}
```

> - 論理削除済みチーム（`teams.deleted_at IS NOT NULL`）は常に除外する
> - `include_archived = false`（デフォルト）の場合、アーカイブ済みチーム（`archived_at IS NOT NULL`）も除外する
> - `role` はそのチームにおける自分のロール名（ADMIN / DEPUTY_ADMIN / MEMBER / SUPPORTER / GUEST）
> - `joined_at` は `memberships.joined_at`（参加日時。F00.5 Phase 2 以降は memberships テーブルの真値を使用）
> - `member_count` はそのチームの現在のメンバー数（`memberships WHERE scope_type='TEAM' AND scope_id = X AND left_at IS NULL` の件数）
> - 返却順: `joined_at` 昇順

---

#### `GET /api/v1/me/organizations`

**クエリパラメータ**
| パラメータ | 型 | デフォルト | 説明 |
|-----------|---|-----------|------|
| `include_archived` | Boolean | `false` | `true` にするとアーカイブ済み組織も含める |
| `cursor` | String | — | 次ページ取得用カーソル |
| `size` | Int | `50` | 1ページ件数（最大100）|

**レスポンス（200 OK）**
```json
{
  "data": [
    {
      "id": 5,
      "name": "〇〇サッカー協会",
      "icon_url": "https://cdn.mannschaft.app/organizations/5/icon.webp",
      "visibility": "PUBLIC",
      "member_count": 150,
      "role": "ADMIN",
      "joined_at": "2026-01-10T08:00:00Z",
      "is_archived": false
    }
  ],
  "meta": {
    "next_cursor": null,
    "size": 50,
    "has_next": false
  }
}
```

> - 論理削除済み組織（`organizations.deleted_at IS NOT NULL`）は常に除外する
> - `include_archived = false`（デフォルト）の場合、アーカイブ済み組織（`archived_at IS NOT NULL`）も除外する
> - `role` はその組織における自分のロール名
> - `joined_at` は `memberships.joined_at`（F00.5 Phase 2 以降は memberships テーブルの真値を使用）
> - `member_count` はその組織の直接所属メンバー数（`memberships WHERE scope_type='ORGANIZATION' AND scope_id = X AND left_at IS NULL` の件数）
> - 返却順: `joined_at` 昇順

**エラーレスポンス（`/me/teams` および `/me/organizations` 共通）**
| ステータス | 条件 |
|-----------|------|
| 401 | 未認証 |

---

#### `GET /api/v1/organizations/{slug}/teams`

組織に所属する（`team_org_memberships.status = 'ACTIVE'`）チーム一覧を返す。

**認可ルール**

| 組織 visibility | アクセス可能なユーザー |
|----------------|----------------------|
| `PUBLIC` | 任意の認証済みユーザー |
| `PRIVATE` | 組織メンバー（任意ロール）のみ。それ以外は 403 |

**返却チームの visibility フィルタ**

呼び出し可能な場合でも、個々のチームの `visibility` に応じてレスポンスの内容を制限する:

| チーム visibility | 返却条件 |
|------------------|---------|
| `PUBLIC` | 常に返す |
| `ORGANIZATION_ONLY` | 常に返す（組織所属チームが `ORGANIZATION_ONLY` を選択した意図＝組織コンテキストでの公開を尊重するため）|
| `PRIVATE` | 呼び出し者がそのチームのメンバーの場合のみ返す。非メンバーにはレスポンスから除外（404 にはしない）|

**クエリパラメータ**
| パラメータ | 型 | デフォルト | 説明 |
|-----------|---|-----------|------|
| `cursor` | String | — | 次ページ取得用カーソル |
| `size` | Int | `50` | 1ページ件数（最大100）|

**レスポンス（200 OK）**
```json
{
  "data": [
    {
      "id": 3,
      "name": "FCマンシャフト U-12",
      "icon_url": "https://cdn.mannschaft.app/teams/3/icon.webp",
      "visibility": "PUBLIC",
      "member_count": 18
    }
  ],
  "meta": {
    "next_cursor": null,
    "size": 50,
    "has_next": false
  }
}
```

> - 論理削除済みチーム（`deleted_at IS NOT NULL`）は常に除外する
> - `member_count` はチームの現在のメンバー数

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 401 | 未認証 |
| 403 | 組織の `visibility = PRIVATE` かつ呼び出し者が組織メンバーでない |
| 404 | 組織が存在しない / 論理削除済み |

---

#### `GET /api/v1/teams/{slug}/organizations`

チームが所属する（`team_org_memberships.status = 'ACTIVE'`）組織一覧を返す。

**認可ルール**

| チーム visibility | アクセス可能なユーザー |
|------------------|----------------------|
| `PUBLIC` / `ORGANIZATION_ONLY` | 任意の認証済みユーザー |
| `PRIVATE` | チームメンバー（任意ロール）のみ。それ以外は 403 |

**返却組織の visibility フィルタ**

| 組織 visibility | 返却条件 |
|----------------|---------|
| `PUBLIC` | 常に返す |
| `PRIVATE` | 呼び出し者がその組織のメンバーの場合のみ返す。非メンバーにはレスポンスから除外（404 にはしない）|

**クエリパラメータ**
| パラメータ | 型 | デフォルト | 説明 |
|-----------|---|-----------|------|
| `cursor` | String | — | 次ページ取得用カーソル |
| `size` | Int | `50` | 1ページ件数（最大100）|

**レスポンス（200 OK）**
```json
{
  "data": [
    {
      "id": 5,
      "name": "〇〇サッカー協会",
      "icon_url": "https://cdn.mannschaft.app/organizations/5/icon.webp",
      "visibility": "PUBLIC",
      "member_count": 150
    }
  ],
  "meta": {
    "next_cursor": null,
    "size": 50,
    "has_next": false
  }
}
```

> - 論理削除済み組織（`deleted_at IS NOT NULL`）は常に除外する
> - `member_count` は組織の直接所属メンバー数

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 401 | 未認証 |
| 403 | チームの `visibility = PRIVATE` かつ呼び出し者がチームメンバーでない |
| 404 | チームが存在しない / 論理削除済み |

---

#### `GET /api/v1/organizations/{slug}/ancestors`

対象組織の上位組織チェーン（祖先）を `parent_organization_id` を辿って返す。配列の先頭が root（最上位）、末尾が直近の親。`hierarchy_visibility` および `visibility` を尊重する。

**認可ルール**

| 対象組織 visibility | アクセス可能なユーザー |
|--------------------|----------------------|
| `PUBLIC` | 任意の認証済み・未認証ユーザー（公開検索由来のアクセスを許可）|
| `PRIVATE` | 対象組織の直接所属メンバー、または対象組織の子孫（子組織・所属チーム）のメンバーのみ |

**祖先個別の返却フィルタ**

各祖先について以下を順に判定し、不可なら**プレースホルダ**（`{ id, hidden: true }` のみ）として配列に残す。プレースホルダはチェーンの抜けを示すために残す（UX 的に「親があるが見れない」ことを伝える）。

| 状況 | 返却内容 |
|------|---------|
| 呼び出し者が当該祖先の直接所属メンバー | フル情報 |
| 呼び出し者が当該祖先の子孫メンバーで `hierarchy_visibility = FULL` | フル情報 |
| 同上で `hierarchy_visibility = BASIC` | `id` / `name` / `nickname1` / `description` / `icon_url` のみ |
| 同上で `hierarchy_visibility = NONE` | `{ id, hidden: true }`（プレースホルダ）|
| 呼び出し者が外部ユーザーで祖先 `visibility = PUBLIC` | `id` / `name` / `nickname1` / `icon_url` のみ |
| 呼び出し者が外部ユーザーで祖先 `visibility = PRIVATE` | `{ id, hidden: true }`（プレースホルダ）|

**深度・サイクル**

- 祖先探索の最大深さは `app.org.max-depth`（デフォルト: 5）。これを超える場合は途中で打ち切る（部分結果を返す。エラーは出さない）
- サイクルが検出された場合（あってはならないが防御的に）はそこで探索を打ち切る

**レスポンス（200 OK）**
```json
{
  "data": [
    {
      "id": 1,
      "name": "全国〇〇連盟",
      "nickname1": null,
      "icon_url": "https://cdn.mannschaft.app/organizations/1/icon.webp",
      "visibility": "PUBLIC",
      "hidden": false
    },
    {
      "id": 3,
      "name": "関東支部",
      "nickname1": "関東",
      "icon_url": null,
      "visibility": "PRIVATE",
      "hidden": false
    },
    {
      "id": 7,
      "hidden": true
    }
  ],
  "meta": {
    "depth": 3,
    "truncated": false
  }
}
```

> - 配列順は **root → 直近の親**（パンくず表示でそのまま左から右に並べられる順序）
> - `hidden: true` のエントリには `id` 以外のフィールドを含めない（情報漏洩防止）
> - `meta.truncated = true` は `max-depth` 到達による打ち切り発生を示す
> - 親が存在しない（トップレベル組織）の場合は `data: []`・`meta.depth: 0` を返す

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 401 | 未認証（対象組織が PRIVATE の場合のみ）|
| 403 | 対象組織が PRIVATE で呼び出し者が直接所属でも子孫メンバーでもない |
| 404 | 対象組織が存在しない / 論理削除済み |

---

#### `GET /api/v1/organizations/{slug}/children`

対象組織の **直近の子組織**（`parent_organization_id = {slug}` かつ `deleted_at IS NULL`）の一覧を返す。深い孫は含まない（必要なら呼び出し側で再帰取得）。

**認可ルール**

| 対象組織 visibility | アクセス可能なユーザー |
|--------------------|----------------------|
| `PUBLIC` | 任意の認証済みユーザー |
| `PRIVATE` | 対象組織の直接所属メンバーのみ。それ以外は 403 |

**返却子組織の visibility フィルタ**

| 子組織 visibility | 返却条件 |
|------------------|---------|
| `PUBLIC` | 常に返す |
| `PRIVATE` | 呼び出し者が当該子組織の直接所属メンバーの場合のみ返す。非メンバーにはレスポンスから除外（404 にはしない）|

**クエリパラメータ**
| パラメータ | 型 | デフォルト | 説明 |
|-----------|---|-----------|------|
| `cursor` | String | — | 次ページ取得用カーソル |
| `size` | Int | `50` | 1ページ件数（最大100）|

**レスポンス（200 OK）**
```json
{
  "data": [
    {
      "id": 12,
      "name": "FCマンシャフト ジュニアユース",
      "nickname1": "ジュニアユース",
      "icon_url": "https://cdn.mannschaft.app/organizations/12/icon.webp",
      "visibility": "PUBLIC",
      "member_count": 32
    }
  ],
  "meta": {
    "next_cursor": null,
    "size": 50,
    "has_next": false
  }
}
```

> - 論理削除済み・アーカイブ済みは `archived` フィールドで示し、レスポンスからは除外しない（クライアント側で識別可能）。ただし `archived_at IS NOT NULL` の場合は `archived: true` を含める
> - `member_count` は子組織の直接所属メンバー数

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 401 | 未認証 |
| 403 | 対象組織の `visibility = PRIVATE` かつ呼び出し者が直接所属メンバーでない |
| 404 | 対象組織が存在しない / 論理削除済み |

---

#### `PATCH /api/v1/teams/{slug}/archive` / `PATCH /api/v1/teams/{slug}/unarchive`

リクエストボディなし。204 No Content を返す。

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 401 | 未認証 |
| 403 | 権限不足（ADMIN 未満）|
| 404 | チームが存在しない / 論理削除済み |
| 422 | archive: すでにアーカイブ済み / unarchive: アーカイブ状態でない |

---

#### `PATCH /api/v1/organizations/{slug}/archive` / `PATCH /api/v1/organizations/{slug}/unarchive`

リクエストボディなし。204 No Content を返す。

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 401 | 未認証 |
| 403 | 権限不足（ADMIN 未満）|
| 404 | 組織が存在しない / 論理削除済み |
| 422 | archive: すでにアーカイブ済み / unarchive: アーカイブ状態でない |

---

#### `GET /api/v1/teams/{slug}/me/permissions`

対象チームにおける自分の実効パーミッション一覧を返す。フロントエンドの UI 制御（ボタン表示/非表示）に使用する。

**レスポンス（200 OK）**
```json
{
  "data": {
    "role": "DEPUTY_ADMIN",
    "permissions": [
      "MANAGE_SCHEDULES",
      "MANAGE_FILES",
      "MANAGE_POSTS",
      "MANAGE_ANNOUNCEMENTS"
    ],
    "permission_groups": [
      {"id": 1, "name": "受付・安否確認"}
    ]
  }
}
```

> - 権限解決ロジック（Section 5）に従い、ロール・権限グループから実効パーミッションを算出して返す
> - SYSTEM_ADMIN の場合は全パーミッションを返す
> - SUPPORTER / GUEST の場合は `permissions` は空配列
> - `GET /organizations/{slug}/me/permissions` も同一仕様（スコープが組織に変わるのみ）

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 401 | 未認証 |
| 404 | チーム/組織が存在しない / 論理削除済み / 自分がメンバーでない |

---

#### `POST /api/v1/teams/{slug}/transfer-ownership`

ADMIN 権限を別のメンバーに移譲する。1ステップで「対象ユーザー→ADMIN」「自分→DEPUTY_ADMIN」を同時に実行する。

**リクエストボディ**
```json
{
  "target_user_id": 42
}
```

**レスポンス（200 OK）**
```json
{
  "data": {
    "new_admin": {
      "user_id": 42,
      "display_name": "田中太郎",
      "role": "ADMIN"
    },
    "previous_admin": {
      "user_id": 1,
      "display_name": "佐藤一郎",
      "role": "DEPUTY_ADMIN"
    }
  }
}
```

> - `POST /organizations/{slug}/transfer-ownership` も同一仕様（スコープが組織に変わるのみ）

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 401 | 未認証 |
| 403 | 操作者が ADMIN でない |
| 404 | チーム/組織が存在しない / 論理削除済み / 対象ユーザーがメンバーでない |
| 422 | 対象ユーザーが 2FA 未設定（ADMIN 昇格には 2FA 必須）|
| 422 | アーカイブ済みチーム/組織 |

---

#### `PATCH /api/v1/teams/{slug}/restore` / `PATCH /api/v1/organizations/{slug}/restore`

論理削除済みのチーム/組織を復元する。SYSTEM_ADMIN のみ実行可能。

リクエストボディなし。204 No Content を返す。

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 401 | 未認証 |
| 403 | 権限不足（SYSTEM_ADMIN のみ）|
| 404 | チーム/組織が存在しない |
| 422 | 論理削除されていない（`deleted_at IS NULL`）|

---

#### `PATCH /api/v1/organizations/{slug}/profile`（`/api/v1/teams/{slug}/profile` も同一仕様）

組織（またはチーム）のプロフィール拡張項目を一括更新する。

**既存 `PATCH /api/v1/organizations/{slug}` との棲み分け**:
- 既存 PATCH は「基本情報」（`name`, `name_kana`, `nickname1/2`, `description`, `icon_url`, `banner_url`, `visibility`, `hierarchy_visibility`, `supporter_enabled`, `org_type`）のみ
- 本 PATCH は「拡張プロフィール」（`homepage_url`, `established_date`, `established_date_precision`, `philosophy`, `profile_visibility`）のみ
- 既存 PATCH に `homepage_url` 等を渡すと 400（`ORG_049`: 拡張プロフィール項目は `/profile` エンドポイントで更新）
- 本 PATCH に `name` 等の基本情報を渡すと 400（同様に棲み分けを強制）

**楽観ロック**: 既存 `organizations` エンティティに `version` 列があれば利用するが、プロフィール項目の一括更新は頻度が低いため、まずは「最後に書いた人の勝ち」運用とする。同時編集衝突が運用上問題になったら If-Match / ETag ベースの制御を追加する（未解決事項に記載）。

**空文字の正規化**: `homepageUrl`, `philosophy`, `officer.name/title`, `customField.label/value` は、リクエスト値を Service 層で `trim()` し、空文字列になったフィールドは以下のルールで処理
- nullable 項目（homepage_url, philosophy）→ NULL に正規化して保存（削除扱い）
- NOT NULL 項目（officer.name/title, customField.label/value）→ 400 Bad Request（空文字不可）

**権限**: ADMIN または `MANAGE_ORGANIZATION`（チームは `MANAGE_TEAM`）権限を持つ DEPUTY_ADMIN

**リクエスト**
```json
{
  "homepageUrl": "https://example.org",
  "establishedDate": "2015-04-01",
  "establishedDatePrecision": "YEAR_MONTH",
  "philosophy": "全ての人に学びの機会を。",
  "profileVisibility": {
    "homepage_url": true,
    "established_date": true,
    "philosophy": true,
    "officers": true,
    "custom_fields": false
  }
}
```

- 全フィールド任意。リクエストに含まれないフィールドは更新しない（PATCH セマンティクス）
- `homepageUrl`: `^https?://` 必須、最大512文字。`null` 指定で削除
- `establishedDate`: `YYYY-MM-DD` 形式。`establishedDatePrecision` と必ずペア。片方のみの更新は 422
- `establishedDatePrecision`: `YEAR` / `YEAR_MONTH` / `FULL`
- `philosophy`: 最大2000文字。プレーンテキスト
- `profileVisibility`: JSON オブジェクト。既知キー（`homepage_url` / `established_date` / `philosophy` / `officers` / `custom_fields`）以外を含む場合 400

**レスポンス** 200 OK — 更新後のプロフィール全体を返す

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | バリデーションエラー（URL形式不正・`profile_visibility` に不明キー・JSON構文エラー）|
| 401 | 未認証 |
| 403 | ADMIN/DEPUTY_ADMIN でない、または必要権限なし |
| 404 | 組織/チームが存在しない |
| 422 | `establishedDate` と `establishedDatePrecision` の片方だけ指定・文字数超過 |
| 429 | レートリミット超過（10 req/min/user）|

**監査ログ**: 変更前後の差分を `audit_logs` に記録（action = `ORGANIZATION_PROFILE_UPDATE` / `TEAM_PROFILE_UPDATE`）

---

#### `POST /api/v1/organizations/{slug}/officers`（`/api/v1/teams/{slug}/officers` も同一仕様）

組織の役員を追加する。

**権限**: ADMIN または `MANAGE_ORGANIZATION`（チームは `MANAGE_TEAM`）権限を持つ DEPUTY_ADMIN

**リクエスト**
```json
{
  "name": "山田太郎",
  "title": "代表理事",
  "displayOrder": 10,
  "isVisible": true
}
```

- `name` / `title`: 必須、最大100文字、プレーンテキスト
- `displayOrder`: 任意。未指定時はサーバー側で既存最大値 + 10 を採番
- `isVisible`: 任意、デフォルト true

**レスポンス** 201 Created
```json
{
  "id": 42,
  "name": "山田太郎",
  "title": "代表理事",
  "displayOrder": 10,
  "isVisible": true,
  "createdAt": "2026-04-15T11:00:00Z"
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | バリデーションエラー（文字数超過・空文字・HTML/制御文字混入）|
| 403 | 権限不足 |
| 404 | 組織/チームが存在しない |
| 422 | 既に50件登録済み |

---

#### `PATCH /api/v1/organizations/{slug}/officers/{officerId}`

役員情報を更新する。リクエスト・認可・エラーは `POST` と同一（全フィールド任意）。

---

#### `DELETE /api/v1/organizations/{slug}/officers/{officerId}`

役員を物理削除する。204 No Content を返す。同一組織配下の役員でない場合 404。

---

#### `PUT /api/v1/organizations/{slug}/officers/reorder`

役員の並び順を一括更新する（楽観ロックのため単発 PATCH ではなく専用エンドポイント）。

**リクエスト**
```json
{
  "orders": [
    { "officerId": 42, "displayOrder": 10 },
    { "officerId": 43, "displayOrder": 20 },
    { "officerId": 41, "displayOrder": 30 }
  ]
}
```

- `orders` の配列は当該組織の全役員を網羅する必要がある（部分指定は 422）
- 重複 ID・不明 ID は 400

**レスポンス** 204 No Content

---

#### `GET /api/v1/organizations/{slug}/officers`

役員一覧を取得する。

**可視性ルール（重要）:**
1. 組織が PRIVATE のとき: 非メンバーは 403（メンバーは全件取得可）
2. 組織が PUBLIC でも `profile_visibility.officers = false` の場合: 非メンバーは空配列
3. `is_visible = false` の役員は非メンバーのレスポンスから除外（ADMIN / DEPUTY_ADMIN は全件取得）

**レスポンス** 200 OK
```json
{
  "data": [
    { "id": 42, "name": "山田太郎", "title": "代表理事", "displayOrder": 10, "isVisible": true }
  ]
}
```

---

#### `POST /api/v1/organizations/{slug}/custom-fields`（`/api/v1/teams/{slug}/custom-fields` も同一仕様）

フリー記述プロフィール項目を追加する。

**権限**: ADMIN または対応権限を持つ DEPUTY_ADMIN

**リクエスト**
```json
{
  "label": "活動拠点",
  "value": "東京都渋谷区〜",
  "displayOrder": 10,
  "isVisible": true
}
```

- `label`: 必須、最大100文字
- `value`: 必須、最大1000文字
- いずれもプレーンテキスト

**レスポンス** 201 Created

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | バリデーションエラー |
| 403 | 権限不足 |
| 404 | 組織/チームが存在しない |
| 422 | 既に20件登録済み |

---

#### `PATCH /api/v1/organizations/{slug}/custom-fields/{fieldId}` / `DELETE` / `PUT .../reorder`

officers と同じ仕様・エラーハンドリング（数値上限のみ 20 件に変更）。

---

#### `GET /api/v1/organizations/{slug}/custom-fields`

可視性ルールは officers と同じ。`profile_visibility.custom_fields = false` のときは非メンバーには空配列。

---

