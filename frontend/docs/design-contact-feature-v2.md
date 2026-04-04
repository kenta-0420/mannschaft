# チャット連絡先機能 設計書 v2.0

**バージョン:** 2.0.0
**作成日:** 2026-04-04
**対象システム:** Mannschaft (Nuxt 3 / Spring Boot)

---

## 変更履歴

| バージョン | 日付       | 内容                                                         |
| ---------- | ---------- | ------------------------------------------------------------ |
| 1.0        | 2026-04-04 | 初版作成                                                     |
| 2.0        | 2026-04-04 | セキュリティ・プライバシー精査を反映。20項目の指摘事項を修正 |

---

## 目次

1. [機能概要・スコープ](#1-機能概要スコープ)
2. [プライバシー方針](#2-プライバシー方針)
3. [DB設計](#3-db設計)
4. [APIエンドポイント一覧](#4-apiエンドポイント一覧)
5. [自動追加フローの設計](#5-自動追加フローの設計)
6. [ブロック機能の設計](#6-ブロック機能の設計)
7. [@ハンドルの設計](#7-ハンドルの設計)
8. [招待URL/QRコードの設計](#8-招待urlqrコードの設計)
9. [フロントエンド設計](#9-フロントエンド設計)
10. [レート制限](#10-レート制限)
11. [データ保持・削除ポリシー](#11-データ保持削除ポリシー)
12. [未成年保護](#12-未成年保護)
13. [実装フェーズ](#13-実装フェーズ)

---

## 1. 機能概要・スコープ

### 1.1 機能概要

チャット機能に「連絡先」を導入し、ユーザー間の1対1DMを安全に開始できるようにする。

### 1.2 スコープ内

| カテゴリ     | 詳細                                                                                        |
| ------------ | ------------------------------------------------------------------------------------------- |
| 連絡先追加   | チーム/組織参加時の自動追加、@ハンドル検索、招待URL/QRコード、チーム名/組織名検索からの申請 |
| ブロック     | 申請事前拒否、完全ブロック（サイレントブロック方式）                                        |
| プライバシー | 検索許可/拒否、申請承認制、オンライン状態の公開範囲                                         |

### 1.3 スコープ外

- メールアドレス検索
- 電話帳同期
- グループ連絡先

### 1.4 既存機能との関係

**変更あり（既存テーブル/コード）:**

| テーブル/コード             | 変更内容                                                                                           |
| --------------------------- | -------------------------------------------------------------------------------------------------- |
| `users`                     | `contact_handle`, `handle_searchable`, `contact_approval_required`, `online_visibility` カラム追加 |
| `chat_contact_folder_items` | 既存。`item_type='CONTACT'` で連絡先を格納。型定義更新                                             |
| `user_blocks` (V12.008)     | 既存。サイレントブロック方式に動作拡張                                                             |
| `UserBlockController`       | 既存パス `/api/v1/users/blocks` をそのまま使用。副作用処理を追加                                   |
| `ChatFolderItemResponse` 型 | `custom_name`, `is_pinned`, `private_note` を型定義に追加（V12.010 DB反映済み・フロント未反映）    |
| `UserProfileResponse` 型    | `dmReceiveFrom`, `contactHandle`, `onlineVisibility` を追加                                        |

**新規テーブル:**

| テーブル                 | 用途                   |
| ------------------------ | ---------------------- |
| `contact_requests`       | 連絡先追加申請         |
| `contact_request_blocks` | 申請事前拒否           |
| `contact_invite_tokens`  | 連絡先専用招待トークン |

---

## 2. プライバシー方針

### 2.1 基本原則

本機能はストーカー被害・ハラスメント対策を最優先事項として設計する。日本のストーカー規制法（令和3年改正）を踏まえ、オンライン状態やアクティビティ情報が追跡に使われるリスクへの対策を講じる。

### 2.2 デフォルト設定方針

**「プライバシー保護側をデフォルト」** の原則を徹底する。

| 設定項目                    | デフォルト値    | 理由                                             |
| --------------------------- | --------------- | ------------------------------------------------ |
| `handle_searchable`         | `true`          | ハンドルを知っている人のみ検索可能。十分に限定的 |
| `contact_approval_required` | `true`          | **一方的な追加を防止**。承認制をデフォルトにする |
| `online_visibility`         | `NOBODY`        | オンライン状態は誰にも見せない                   |
| `dm_receive_from`           | `CONTACTS_ONLY` | 連絡先からのみDMを受信                           |

> **v1.0 からの変更:** `contact_approval_required` のデフォルトを `false` → `true` に変更。`dm_receive_from` のデフォルトを `ANYONE` → `CONTACTS_ONLY` に変更。理由: 一方的な追加とDM送信を防ぐことがストーカー対策の基本。ユーザーが必要に応じて緩和できる設計が安全。

### 2.3 サイレントブロック方式

ブロックした事実が相手に**一切分からない**設計とする。

| 相手から見た挙動 | 実装方法                                                   |
| ---------------- | ---------------------------------------------------------- |
| メッセージ送信   | 「送信済み」と表示するが、実際には配信しない               |
| プロフィール検索 | 結果に含まれない（「見つかりません」ではなく、結果ゼロ）   |
| オンライン状態   | 最後に見えていた状態を静的に表示し続ける                   |
| 連絡先申請       | 「申請を送信しました」と表示するが、実際には作成しない     |
| グループチャット | 双方のメッセージを互いに非表示（退出したようには見せない） |

### 2.4 オンライン状態の公開範囲

`online_visibility` カラムで以下の3段階を提供:

| 値              | 説明                                           |
| --------------- | ---------------------------------------------- |
| `NOBODY`        | **デフォルト**。誰にもオンライン状態を見せない |
| `CONTACTS_ONLY` | 連絡先のユーザーにのみ表示                     |
| `EVERYONE`      | 全員に表示                                     |

> **既読表示について:** 既読表示（チャットメッセージの既読/未読）は本設計のスコープ外だが、将来実装する場合は「自分がOFFにした場合は相手の既読も見えない」という**相互制限方式**を採用すること。

### 2.5 ブロック検知の防止

ブロックされていることを検知しようとする行為（複数アカウント作成、API直叩きで応答時間を計測等）は利用規約で禁止し、通報対象とする。技術的にも以下で対策する:

- ブロックチェック有無で応答時間が変わらないよう**統一的な処理パス**を通す
- ブロック時も非ブロック時も同じHTTPステータスコードを返す

---

## 3. DB設計

### 3.1 `users` テーブルへのカラム追加

**Flyway:** `V13.001__add_contact_settings_to_users.sql`

```sql
ALTER TABLE users
  ADD COLUMN contact_handle   VARCHAR(30)  NULL UNIQUE
      COMMENT 'アプリ内@ハンドル。英数字・アンダースコア・ハイフン。user_social_profiles.handleとは別物'
      AFTER display_name,

  ADD COLUMN handle_searchable TINYINT(1) NOT NULL DEFAULT 1
      COMMENT '1=@ハンドルで検索可能, 0=検索不可'
      AFTER contact_handle,

  ADD COLUMN contact_approval_required TINYINT(1) NOT NULL DEFAULT 1
      COMMENT '1=追加申請に承認が必要(デフォルト), 0=自動承認'
      AFTER handle_searchable,

  ADD COLUMN online_visibility VARCHAR(20) NOT NULL DEFAULT 'NOBODY'
      COMMENT 'オンライン状態の公開範囲: NOBODY / CONTACTS_ONLY / EVERYONE'
      AFTER contact_approval_required;

CREATE INDEX idx_users_contact_handle ON users(contact_handle);
```

> **v1.0 からの変更:**
>
> - カラム名を `handle` → `contact_handle` に変更（`user_social_profiles.handle` との混同を防止）
> - `contact_approval_required` のデフォルトを `0` → `1` に変更
> - `online_visibility` カラムを追加
> - 既に `UNIQUE` 制約があるため冗長なインデックス作成を削除

**バリデーション:**

- `contact_handle`: `^[a-z0-9_-]{3,30}$`（小文字のみ。入力時に自動変換）
- **予約語チェック:** `admin`, `system`, `support`, `mannschaft`, `api`, `help`, `info`, `null`, `undefined`, `me`, `anonymous`, `moderator`, `bot`, `official` を禁止

### 3.2 `contact_requests` テーブル

**Flyway:** `V13.002__create_contact_requests_table.sql`

```sql
CREATE TABLE contact_requests (
    id              BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    requester_id    BIGINT UNSIGNED  NOT NULL COMMENT '申請した側のユーザーID',
    target_id       BIGINT UNSIGNED  NOT NULL COMMENT '申請された側のユーザーID',
    status          VARCHAR(20)      NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING / ACCEPTED / REJECTED / CANCELLED',
    source_type     VARCHAR(30)      NULL
        COMMENT '申請起点: HANDLE_SEARCH / TEAM_SEARCH / ORG_SEARCH / INVITE_URL / AUTO_TEAM / AUTO_ORG',
    source_id       BIGINT UNSIGNED  NULL
        COMMENT 'チーム・組織・招待トークンのID',
    message         VARCHAR(200)     NULL
        COMMENT '申請時の一言メッセージ（表示時はv-textでエスケープ必須）',
    responded_at    DATETIME         NULL,
    expires_at      DATETIME         NULL
        COMMENT 'NULLは無期限',
    created_at      DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    -- 同一ペアのPENDING申請は1件のみ
    -- アプリ層で「既にACCEPTEDなら申請不可」「REJECTED後の再申請クールダウン」を強制
    UNIQUE KEY uq_cr_pair_pending (requester_id, target_id, status),
    INDEX idx_cr_target_status   (target_id, status, created_at DESC),
    INDEX idx_cr_requester       (requester_id, status, created_at DESC),
    INDEX idx_cr_cleanup         (status, updated_at),
    CONSTRAINT fk_cr_requester FOREIGN KEY (requester_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_cr_target    FOREIGN KEY (target_id)    REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_cr_status CHECK (status IN ('PENDING','ACCEPTED','REJECTED','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='連絡先追加申請';
```

**アプリ層での追加チェック（DBだけでは防げない制約）:**

| チェック                               | 理由                                                          |
| -------------------------------------- | ------------------------------------------------------------- |
| 既にACCEPTED行が存在する場合は申請不可 | UNIQUE KEY は status を含むため ACCEPTED + PENDING が共存可能 |
| REJECTED 後 72時間以内の再申請禁止     | 拒否後の執拗な再申請を防止（ストーカー対策）                  |
| 同一ユーザーへの申請は24時間に1回まで  | CANCELLED→再申請のスパム防止                                  |

### 3.3 `contact_request_blocks` テーブル

**Flyway:** `V13.003__create_contact_request_blocks_table.sql`

```sql
CREATE TABLE contact_request_blocks (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id     BIGINT UNSIGNED NOT NULL COMMENT '拒否設定をしたユーザー',
    blocked_id  BIGINT UNSIGNED NOT NULL COMMENT '申請を拒否するユーザー',
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uq_crb (user_id, blocked_id),
    INDEX idx_crb_blocked (blocked_id, user_id),
    CONSTRAINT fk_crb_user    FOREIGN KEY (user_id)    REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_crb_blocked FOREIGN KEY (blocked_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='連絡先申請ブロック（事前拒否）';
```

### 3.4 `contact_invite_tokens` テーブル

**Flyway:** `V13.004__create_contact_invite_tokens_table.sql`

```sql
CREATE TABLE contact_invite_tokens (
    id          BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    user_id     BIGINT UNSIGNED  NOT NULL COMMENT 'トークン発行者',
    token       CHAR(36)         NOT NULL COMMENT 'UUID v4',
    label       VARCHAR(50)      NULL     COMMENT '管理用ラベル',
    max_uses    INT              NULL     COMMENT 'NULL=無制限',
    used_count  INT              NOT NULL DEFAULT 0,
    expires_at  DATETIME         NULL     COMMENT 'NULL=無期限',
    revoked_at  DATETIME         NULL,
    created_at  DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uq_cit_token (token),
    INDEX idx_cit_user (user_id, revoked_at, expires_at),
    CONSTRAINT fk_cit_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='個人連絡先招待トークン';
```

> **既存 `invite_tokens` テーブルとの違い:** `invite_tokens` はチーム/組織招待用（`team_id`/`organization_id` スコープ + `role_id`）。`contact_invite_tokens` は個人間の連絡先追加専用でスコープもロールも無い。

### 3.5 既存テーブルの流用

#### `chat_contact_folder_items`

| カラム         | 用途（連絡先の場合）                             |
| -------------- | ------------------------------------------------ |
| `folder_id`    | デフォルトフォルダ or ユーザーが作成したフォルダ |
| `item_type`    | `'CONTACT'` 固定                                 |
| `item_id`      | 相手の `users.id`                                |
| `custom_name`  | 任意表示名（V12.010で追加済み）                  |
| `is_pinned`    | お気に入り（V12.010で追加済み）                  |
| `private_note` | プライベートメモ（V12.010で追加済み）            |

> **注意:** `item_id` は `users.id` への FK が無い（polymorphic設計）。ユーザー削除時に残留データが発生するため、3.6 で対策する。

#### `user_blocks` (V12.008)

既存テーブル・既存APIをそのまま使用。サイレントブロック方式の動作拡張はアプリ層で行う。

### 3.6 ユーザー削除時のカスケード処理

ユーザーアカウント削除時に以下のクリーンアップを実行する（アプリ層・`@PreRemove` or サービス層）:

```
1. contact_requests: FK CASCADE で自動削除 ✅
2. contact_request_blocks: FK CASCADE で自動削除 ✅
3. contact_invite_tokens: FK CASCADE で自動削除 ✅
4. chat_contact_folder_items: FK無し → アプリ層で明示的に削除
   DELETE FROM chat_contact_folder_items
   WHERE item_type = 'CONTACT' AND item_id = {削除ユーザーID}
5. user_blocks: FK CASCADE で自動削除 ✅
```

### 3.7 ER図

```
users (id PK, contact_handle UNIQUE, handle_searchable, contact_approval_required,
       online_visibility, is_searchable, dm_receive_from, ...)
  │
  │──< contact_requests (requester_id FK, target_id FK, status, source_type, ...)
  │
  │──< contact_request_blocks (user_id FK, blocked_id FK)
  │
  │──< contact_invite_tokens (user_id FK, token UNIQUE, ...)
  │
  │──< user_blocks (blocker_id FK, blocked_id FK)  ※既存 V12.008
  │
  └──< chat_contact_folders (user_id, ...)  ※既存 V3.032
         │
         └──< chat_contact_folder_items (folder_id FK, item_type, item_id, ...)
              ※既存 V3.033 + V12.010改修。item_type='CONTACT' → item_id = users.id（FK無し）
```

---

## 4. APIエンドポイント一覧

### 4.1 @ハンドル

#### `GET /api/v1/users/me/contact-handle`

自分のハンドル情報を取得。

**レスポンス:**

```json
{
  "data": {
    "contactHandle": "taro_yamada",
    "handleSearchable": true,
    "contactApprovalRequired": true,
    "onlineVisibility": "NOBODY"
  }
}
```

#### `PUT /api/v1/users/me/contact-handle`

ハンドルを設定・変更する。

**リクエスト:**

```json
{ "contactHandle": "taro_yamada2" }
```

**バリデーション:** `^[a-z0-9_-]{3,30}$` + 予約語チェック + 重複チェック

#### `GET /api/v1/users/contact-handle-check?handle={handle}`

重複確認用軽量エンドポイント（認証必須、レート制限あり）。

**レスポンス:** `{ "available": true }`

#### `GET /api/v1/users/contact-handle/{handle}`

@ハンドルでユーザーを検索。

**レスポンス:**

```json
{
  "data": {
    "userId": 123,
    "displayName": "山田 太郎",
    "contactHandle": "taro_yamada",
    "avatarUrl": "https://...",
    "isContact": false,
    "hasPendingRequest": false,
    "contactApprovalRequired": true
  }
}
```

**プライバシー制御:**

- `handle_searchable = false` → 結果に含めない（404ではなく空結果）
- `user_blocks` でブロック関係 → 結果に含めない
- `contact_request_blocks` で拒否関係 → **検索は表示する（申請時にブロック）**

> **v1.0 からの変更:**
>
> - 「404 Not Found」ではなく「結果に含めない」方式に変更。404はハンドルの存在を暗示するため。
> - `isBlocked` フィールドを削除。ブロック状態は相手に開示しない。

### 4.2 連絡先一覧

#### `GET /api/v1/contacts`

**クエリパラメータ:** `folderId`, `q`, `isPinned`, `cursor`, `limit`

**レスポンス:**

```json
{
  "data": [
    {
      "folderItemId": 5,
      "folderId": null,
      "user": {
        "id": 123,
        "displayName": "山田 太郎",
        "contactHandle": "taro_yamada",
        "avatarUrl": "https://..."
      },
      "customName": null,
      "isPinned": false,
      "privateNote": null,
      "addedAt": "2026-03-01T10:00:00Z"
    }
  ],
  "meta": { "nextCursor": null, "total": 1 }
}
```

#### `DELETE /api/v1/contacts/{userId}`

連絡先から削除。自分の `chat_contact_folder_items` からのみ削除。相手側には影響しない。

### 4.3 連絡先追加申請

#### `POST /api/v1/contact-requests`

**リクエスト:**

```json
{
  "targetUserId": 456,
  "message": "よろしくお願いします",
  "sourceType": "HANDLE_SEARCH"
}
```

**バックエンド処理フロー（全ケースで同一応答時間を保つ）:**

```
1. 自分自身への申請チェック → 400
2. 既に連絡先かチェック → 409
3. user_blocks チェック（双方向）
   → ブロック関係あり: 200 OK を返すが実際にはINSERTしない（サイレント）
4. contact_request_blocks チェック
   → 拒否設定あり: 200 OK を返すが実際にはINSERTしない（サイレント）
5. REJECTED後72時間以内の再申請チェック → 429
6. 24時間以内の同一相手への申請チェック → 429
7. contact_approval_required = false → ACCEPTED で INSERT、連絡先に即時追加
8. contact_approval_required = true  → PENDING で INSERT、通知送信
```

**レスポンス（全ケースで同じ形式）:**

```json
{
  "data": {
    "requestId": 789,
    "status": "PENDING"
  }
}
```

> **重要: サイレントブロック対応**
> ブロック・拒否設定されている場合も「申請を送信しました」相当のレスポンスを返す。エラーを返すとブロックされていることが判明するため。

#### `GET /api/v1/contact-requests/received`

受信申請一覧（PENDING のみ）。

#### `GET /api/v1/contact-requests/sent`

送信済み申請一覧（PENDING のみ）。

#### `POST /api/v1/contact-requests/{requestId}/accept`

申請を承認。双方の `chat_contact_folder_items` にレコード追加。

#### `POST /api/v1/contact-requests/{requestId}/reject`

申請を拒否。`status` を REJECTED に更新。**申請者への通知は行わない（拒否されたことを知らせない）。**

> **v1.0 からの変更:** 拒否通知を削除。拒否の事実が伝わることで逆恨みリスクがあるため。申請者側では「申請中」のまま表示し、30日後に自動期限切れとする。

#### `DELETE /api/v1/contact-requests/{requestId}`

自分が送った申請をキャンセル（申請者のみ実行可能）。

### 4.4 申請事前拒否

#### `GET /api/v1/contact-request-blocks`

事前拒否リスト取得。

#### `POST /api/v1/contact-request-blocks`

特定ユーザーからの申請を事前拒否。

**リクエスト:** `{ "targetUserId": 999 }`

#### `DELETE /api/v1/contact-request-blocks/{blockedUserId}`

事前拒否設定を解除。

### 4.5 ユーザーブロック（完全ブロック）

**既存パスをそのまま使用:**

#### `POST /api/v1/users/blocks`

**リクエスト:** `{ "blockedId": 456 }`

**副作用（追加実装）:**

```
1. user_blocks に INSERT（既存処理）
2. 連絡先関係の解消:
   - 自分の chat_contact_folder_items WHERE item_type='CONTACT' AND item_id=target → 削除
   - 相手の chat_contact_folder_items WHERE item_type='CONTACT' AND item_id=me → 削除
3. PENDING 申請のキャンセル:
   - contact_requests WHERE 双方ペア AND status='PENDING' → CANCELLED
4. contact_request_blocks に INSERT（ブロック解除まで申請も拒否）
5. DM チャンネル:
   - chat_channels WHERE channel_type='DIRECT' AND 双方がmember → is_archived=true
   - 以降の送信は「送信済み」表示するが実際には配信しない（サイレント）
```

#### `DELETE /api/v1/users/blocks/{blockedId}`

ブロック解除。

**副作用:**

- `user_blocks` から DELETE（既存処理）
- `contact_request_blocks` からも DELETE
- 連絡先関係・DMチャンネルは**復元しない**（再申請が必要）

#### `GET /api/v1/users/blocks`

ブロック一覧取得（既存）。

### 4.6 招待URL / QRコード

#### `POST /api/v1/contact-invite-tokens`

新しい招待トークンを発行。

**リクエスト:**

```json
{
  "label": "SNS用",
  "maxUses": 10,
  "expiresIn": "7d"
}
```

**レスポンス:**

```json
{
  "data": {
    "id": 1,
    "token": "550e8400-...",
    "label": "SNS用",
    "inviteUrl": "https://app.example.com/contact-invite/550e8400-...",
    "qrCodeUrl": "/api/v1/contact-invite-tokens/550e8400-.../qr",
    "maxUses": 10,
    "usedCount": 0,
    "expiresAt": "2026-04-11T00:00:00Z"
  }
}
```

#### `GET /api/v1/contact-invite-tokens`

発行済みトークン一覧。

#### `DELETE /api/v1/contact-invite-tokens/{id}`

トークン無効化（revoke）。

#### `GET /api/v1/contact-invite-tokens/{token}/qr`

QRコード画像生成（PNG）。`size` パラメータ: 100-1000px。

**セキュリティ:** 埋め込むURLは**サーバー側で組み立て**、ユーザー入力値は含めない。

#### `GET /api/v1/contact-invite/{token}` (認証不要)

招待プレビュー。

**レスポンス（情報最小化）:**

```json
{
  "data": {
    "isValid": true,
    "issuer": {
      "displayName": "山田 太郎",
      "contactHandle": "taro_yamada"
    },
    "expiresAt": "2026-04-11T00:00:00Z"
  }
}
```

> **v1.0 からの変更:** `avatarUrl` と `remainingUses` をプレビューから削除。認証不要エンドポイントで公開する個人情報を最小限にする。

#### `POST /api/v1/contact-invite/{token}/accept` (認証必須)

招待URLから連絡先追加。

**処理フロー:**

```
1. トークン有効性チェック（期限・回数・revoke）
2. 自分が発行したトークンでないことを確認 → 400
3. user_blocks チェック → ブロック関係あり: 200 OK（サイレント、実際には追加しない）
4. contact_request_blocks チェック → 同上
5. contact_approval_required チェック → ACCEPTED or PENDING
6. used_count インクリメント
```

> **v1.0 からの変更:** ブロックチェックを追加。ブロック前に発行されたURLでブロックを回避できる問題を修正。

### 4.7 チーム・組織メンバーからの申請

#### `GET /api/v1/teams/{teamId}/members/contactable`

#### `GET /api/v1/organizations/{orgId}/members/contactable`

**アクセス制限:**

- `visibility = 'PUBLIC'` のチーム/組織 → 認証ユーザーなら閲覧可能
- `visibility = 'PRIVATE'` / `ORGANIZATION_ONLY` → **自分がメンバーまたはサポーターの場合のみ**

> **v1.0 からの変更:** アクセス制限を追加。非公開チーム/組織のメンバーが列挙される脆弱性を修正。

**クエリパラメータ:** `q`, `cursor`, `limit`

**レスポンス:**

```json
{
  "data": [
    {
      "userId": 123,
      "displayName": "山田 太郎",
      "contactHandle": "taro_yamada",
      "avatarUrl": "https://...",
      "isContact": false,
      "hasPendingRequest": false
    }
  ],
  "meta": { "nextCursor": null }
}
```

### 4.8 プライバシー設定

#### `GET /api/v1/users/me/contact-privacy`

```json
{
  "data": {
    "handleSearchable": true,
    "contactApprovalRequired": true,
    "dmReceiveFrom": "CONTACTS_ONLY",
    "onlineVisibility": "NOBODY"
  }
}
```

#### `PUT /api/v1/users/me/contact-privacy`

```json
{
  "handleSearchable": false,
  "contactApprovalRequired": true,
  "dmReceiveFrom": "CONTACTS_ONLY",
  "onlineVisibility": "CONTACTS_ONLY"
}
```

---

## 5. 自動追加フローの設計

### 5.1 トリガー

`user_roles` テーブルへの INSERT（チーム/組織ロール割り当て）時に非同期イベントで処理。

### 5.2 処理フロー

```
1. ユーザーAがチームTに参加
2. UserRoleService → ApplicationEventPublisher.publish(TeamMemberJoinedEvent)

3. ContactAutoAddEventListener（@Async）:
   a. チームTの既存メンバー一覧を取得（最大200件）
   b. 各メンバーBについて:
      - user_blocks (双方向) → スキップ
      - contact_request_blocks (B → A) → スキップ
      - 既に連絡先関係あり → スキップ
      - 直近30日以内に AUTO_TEAM で同一ペアの REJECTED/CANCELLED あり → スキップ（再参加スパム対策）
      - B の contact_approval_required = true → PENDING で申請（通知あり）
      - B の contact_approval_required = false → ACCEPTED で即時追加（通知あり）
   c. 双方のデフォルトフォルダに追加（ACCEPTED の場合）
```

### 5.3 バッチ上限

| チーム規模 | 処理方法                                                   |
| ---------- | ---------------------------------------------------------- |
| 50人以下   | 同期的に処理（イベントリスナー内）                         |
| 51-200人   | @Async で非同期処理                                        |
| 201人以上  | Spring Batch ジョブとして登録。`batch_job_logs` にログ記録 |

### 5.4 チーム退出時の動作

- **連絡先は削除しない。** 退出前に追加された連絡先はそのまま残る。
- DMチャンネルも維持する。
- 理由: チームを離れても個人的な繋がりは継続するのが自然な挙動。
- ただし、退出したユーザーのチームメンバー一覧（`/contactable`）への表示は消える。

### 5.5 再参加スパム対策

チーム退出→再参加を繰り返して自動追加を悪用するケースへの対策:

- 同一ペアで `source_type = 'AUTO_TEAM'` or `'AUTO_ORG'` かつ `status = REJECTED` or `CANCELLED` のレコードが直近30日以内に存在する場合、自動追加をスキップ。
- チーム側の管理者にはモデレーション画面で通知。

---

## 6. ブロック機能の設計

### 6.1 ブロックの種類

| 種類         | テーブル                 | 効果                                         | 相手への通知 |
| ------------ | ------------------------ | -------------------------------------------- | ------------ |
| 申請拒否のみ | `contact_request_blocks` | 連絡先申請を静かに無効化                     | なし         |
| 完全ブロック | `user_blocks`            | DM遮断・検索非表示・連絡先削除・全申請無効化 | なし         |

### 6.2 ブロック中のAPI挙動（サイレント方式）

| エンドポイント                               | 挙動                                    |
| -------------------------------------------- | --------------------------------------- |
| `GET /api/v1/users/contact-handle/{handle}`  | 結果に含めない（空結果）                |
| `GET /api/v1/teams/{id}/members/contactable` | リストから除外                          |
| `POST /api/v1/contact-requests`              | **200 OK を返すが実際にはINSERTしない** |
| `POST /api/v1/contact-invite/{token}/accept` | **200 OK を返すが実際には追加しない**   |
| `POST /api/v1/chat/channels/{id}/messages`   | **送信済みと表示するが配信しない**      |
| 通知                                         | ブロック相手からの通知は INSERT しない  |
| オンライン状態                               | 最後の既知状態を静的に返す              |

### 6.3 グループチャットでの挙動

ブロック関係にある2人が同じグループチャンネルにいる場合:

- **双方のメッセージを互いに非表示にする**（フィルタリング）
- 退出したようには見せない（メンバー一覧には表示）
- メンション（@）は届かない

---

## 7. @ハンドルの設計

### 7.1 仕様

| 項目               | 仕様                                                                                                                                      |
| ------------------ | ----------------------------------------------------------------------------------------------------------------------------------------- |
| DB カラム名        | `users.contact_handle`                                                                                                                    |
| 形式               | `@` + `[a-z0-9_-]{3,30}`                                                                                                                  |
| 大文字小文字       | 小文字のみ（入力時に自動変換）                                                                                                            |
| グローバルユニーク | UNIQUE 制約                                                                                                                               |
| 変更               | 変更可能。変更後も旧ハンドルは即時解放                                                                                                    |
| 初期値             | NULL（任意設定）                                                                                                                          |
| 予約語             | `admin`, `system`, `support`, `mannschaft`, `api`, `help`, `info`, `null`, `undefined`, `me`, `anonymous`, `moderator`, `bot`, `official` |

> **`user_social_profiles.handle` との違い:** `user_social_profiles` テーブルの `handle` は外部SNS（Twitter/Instagram等）のユーザー名を保存するフィールド。`users.contact_handle` はアプリ内の一意識別子であり、用途が完全に異なる。

### 7.2 設定場所

既存の `pages/settings/profile.vue` に「@ハンドル」セクションを追加。

- リアルタイム重複チェック（デバウンス 500ms、`/contact-handle-check`）
- 予約語はフロント側でも即時エラー表示
- 利用可能/不可をアイコンで表示

### 7.3 表示場所

| 場所                       | 表示方法                       |
| -------------------------- | ------------------------------ |
| 連絡先一覧                 | `@handle` をサブテキストで表示 |
| DMヘッダー                 | 相手の `@handle` を表示        |
| 連絡先検索ダイアログ       | 検索結果に `@handle` 表示      |
| 招待URL着地ページ          | 発行者の `@handle` 表示        |
| ユーザープロフィールカード | `@handle` 表示                 |

---

## 8. 招待URL/QRコードの設計

### 8.1 URL形式

```
https://{domain}/contact-invite/{UUID v4}
```

> **v1.0 からの変更:** パスを `/chat/contact-invite/` から `/contact-invite/` に変更。既存の `/invite/[token]`（チーム/組織招待）とは別パスで、トップレベルに配置。

### 8.2 QRコード生成

- バックエンド: `com.google.zxing:core` で生成
- **埋め込むURLはサーバー側で組み立て。** ユーザー入力値（ラベル等）はURL に含めない
- `Content-Type: image/png`
- `Cache-Control: public, max-age=3600`

### 8.3 トークン設定

| 設定         | デフォルト | 選択肢                    |
| ------------ | ---------- | ------------------------- |
| 有効期限     | 7日        | 1日 / 7日 / 30日 / 無期限 |
| 利用回数上限 | 1回        | 1, 5, 10, 50, 無制限      |

> **v1.0 からの変更:** デフォルトを「無期限・無制限」から「7日・1回」に変更。安全側デフォルト。

### 8.4 着地ページフロー

```
[未ログイン] → プレビュー表示 → ログイン/登録 → リダイレクトで自動追加
[ログイン済み] → 確認モーダル → ワンタップで追加
```

---

## 9. フロントエンド設計

### 9.1 ページ構成

```
pages/
  contact-invite/
    [token].vue                    招待URL着地ページ
  settings/
    contact-privacy.vue            連絡先プライバシー設定
    contact-invite-tokens.vue      招待トークン管理
    contact-request-blocks.vue     事前拒否リスト管理
```

> チャット連絡先一覧は既存 `pages/chat/index.vue` の左ペインに統合。

### 9.2 コンポーネント

```
components/
  contact/
    ContactList.vue                連絡先一覧（フォルダ対応）
    ContactListItem.vue            連絡先1件
    ContactSearchDialog.vue        @ハンドル検索 + 申請ダイアログ
    ContactTeamSearchDialog.vue    チーム/組織検索からの申請
    ContactRequestBadge.vue        未処理申請件数バッジ
    ContactRequestList.vue         受信申請一覧
    ContactRequestItem.vue         申請1件（承認/拒否ボタン）
    ContactSentRequestList.vue     送信済み申請一覧
    ContactInvitePanel.vue         招待URL + QRコード表示
    ContactInviteTokenList.vue     発行済みトークン管理
    ContactPrivacyForm.vue         プライバシー設定フォーム
    ContactRequestBlockList.vue    事前拒否リスト
    ContactContextMenu.vue         右クリックメニュー（DM, 削除, ブロック等）
    UserHandleInput.vue            @ハンドル入力（リアルタイム重複チェック付き）
```

### 9.3 Composable

```typescript
// useContactApi.ts
export function useContactApi() {
  // 連絡先
  listContacts(params?)
  deleteContact(userId)

  // 申請
  sendRequest(body)
  listReceivedRequests()
  listSentRequests()
  acceptRequest(requestId)
  rejectRequest(requestId)
  cancelRequest(requestId)

  // 事前拒否
  listRequestBlocks()
  addRequestBlock(targetUserId)
  removeRequestBlock(blockedUserId)

  // 招待トークン
  listInviteTokens()
  createInviteToken(body)
  revokeInviteToken(id)
  getInvitePreview(token)  // 認証不要
  acceptInvite(token)

  // @ハンドル
  searchByHandle(handle)
  getMyHandle()
  updateMyHandle(handle)
  checkHandleAvailability(handle)

  // チーム/組織メンバー
  getTeamContactableMembers(teamId, params?)
  getOrgContactableMembers(orgId, params?)

  // プライバシー設定
  getPrivacySettings()
  updatePrivacySettings(body)
}
```

### 9.4 型定義（`types/contact.ts`）

```typescript
export interface ContactResponse {
  folderItemId: number
  folderId: number | null
  user: ContactUser
  customName: string | null
  isPinned: boolean
  privateNote: string | null
  addedAt: string
}

export interface ContactUser {
  id: number
  displayName: string
  contactHandle: string | null
  avatarUrl: string | null
}

export interface ContactRequestResponse {
  id: number
  requester: ContactUser
  target: ContactUser
  status: 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'CANCELLED'
  message: string | null
  sourceType: string
  createdAt: string
}

export interface ContactRequestBlockResponse {
  id: number
  blockedUser: ContactUser
  createdAt: string
}

export interface ContactInviteTokenResponse {
  id: number
  token: string
  label: string | null
  inviteUrl: string
  qrCodeUrl: string
  maxUses: number | null
  usedCount: number
  expiresAt: string | null
  createdAt: string
}

export interface ContactInvitePreviewResponse {
  isValid: boolean
  issuer: { displayName: string; contactHandle: string | null }
  expiresAt: string | null
}

export interface ContactPrivacySettings {
  handleSearchable: boolean
  contactApprovalRequired: boolean
  dmReceiveFrom: 'ANYONE' | 'TEAM_MEMBERS_ONLY' | 'CONTACTS_ONLY'
  onlineVisibility: 'NOBODY' | 'CONTACTS_ONLY' | 'EVERYONE'
}
```

### 9.5 既存型の更新

```typescript
// types/chat-folder.ts に追加（V12.010のDB反映）
interface ChatFolderItemResponse {
  id: number
  itemType: string
  itemId: number
  customName: string | null // 追加
  isPinned: boolean // 追加
  privateNote: string | null // 追加
}

// types/user-settings.ts に追加
interface UserProfileResponse {
  // ...既存フィールド...
  contactHandle: string | null
  handleSearchable: boolean
  contactApprovalRequired: boolean
  dmReceiveFrom: 'ANYONE' | 'TEAM_MEMBERS_ONLY' | 'CONTACTS_ONLY'
  onlineVisibility: 'NOBODY' | 'CONTACTS_ONLY' | 'EVERYONE'
}
```

### 9.6 XSS対策

申請メッセージ（`contact_requests.message`）はユーザー入力値のため、テンプレートで表示する際は必ず `v-text` ディレクティブまたは `{{ }}` (Vue のデフォルトエスケープ) を使用する。`v-html` は**絶対に使用しない**。

---

## 10. レート制限

| エンドポイント                               | 制限                       | 理由                 |
| -------------------------------------------- | -------------------------- | -------------------- |
| `GET /api/v1/users/contact-handle-check`     | 10 req/min (ユーザー単位)  | ハンドル列挙攻撃防止 |
| `GET /api/v1/users/contact-handle/{handle}`  | 30 req/min (ユーザー単位)  | ハンドル列挙攻撃防止 |
| `POST /api/v1/contact-requests`              | 20 req/hour (ユーザー単位) | 申請スパム防止       |
| `GET /api/v1/contact-invite/{token}`         | 30 req/min (IP単位)        | トークン総当たり防止 |
| `POST /api/v1/contact-invite/{token}/accept` | 10 req/min (ユーザー単位)  | 不正利用防止         |
| `POST /api/v1/contact-invite-tokens`         | 10 req/hour (ユーザー単位) | トークン乱発防止     |

---

## 11. データ保持・削除ポリシー

| データ                                  | 保持期間               | 削除方法                         |
| --------------------------------------- | ---------------------- | -------------------------------- |
| `contact_requests` (ACCEPTED)           | 無期限                 | ユーザー削除時に CASCADE         |
| `contact_requests` (REJECTED/CANCELLED) | **90日**               | バッチジョブで定期削除           |
| `contact_requests` (PENDING)            | **30日で自動期限切れ** | `expires_at` 設定 + バッチジョブ |
| `contact_invite_tokens` (revoked)       | **30日**               | バッチジョブで定期削除           |
| `contact_request_blocks`                | ユーザーが解除するまで | ユーザー削除時に CASCADE         |

**バッチジョブ:** `V13.005__schedule_contact_data_cleanup.sql` でスケジュール設定。毎日AM3時実行。

### 11.1 データエクスポート

ユーザーは設定画面から自分の連絡先データをエクスポート可能:

- 連絡先一覧（相手の表示名・ハンドル・追加日時）
- 送受信した申請履歴（PENDING/ACCEPTED のみ。REJECTED の詳細は相手のプライバシー保護のため非公開）
- 形式: JSON

---

## 12. 未成年保護

### 12.1 現時点の対応

Mannschaft は登録時に生年月日を取得しないため、現時点では年齢ベースの制限は実装しない。

### 12.2 将来対応（検討事項）

本格的な未成年保護を導入する場合に必要な措置:

| 措置               | 内容                                                     |
| ------------------ | -------------------------------------------------------- |
| 年齢確認           | 登録時の生年月日入力                                     |
| 成人→未成年の制限  | 連絡先追加申請に追加の承認ステップ                       |
| コンテンツフィルタ | 画像・テキストの有害コンテンツ検知                       |
| 保護者通知         | 新規連絡先追加時に保護者へ通知（メッセージ内容は非公開） |
| 夜間制限           | 深夜帯の利用制限（保護者設定可）                         |

> これらは別の設計書として独立させるべき規模のため、本設計書ではスコープ外とする。

---

## 13. 実装フェーズ

| フェーズ    | 内容                                            | 優先度 |
| ----------- | ----------------------------------------------- | ------ |
| **Phase 1** | DB マイグレーション (V13.001-004)               | 必須   |
| **Phase 1** | @ハンドル CRUD API + 設定画面                   | 必須   |
| **Phase 1** | @ハンドル検索 + 連絡先申請API（承認フロー含む） | 必須   |
| **Phase 1** | 連絡先一覧API + 既存フォルダ統合                | 必須   |
| **Phase 1** | プライバシー設定API + 画面                      | 必須   |
| **Phase 2** | サイレントブロック方式の完全実装                | 高     |
| **Phase 2** | 申請事前拒否リスト                              | 高     |
| **Phase 2** | 招待URL / QRコード                              | 高     |
| **Phase 2** | レート制限の導入                                | 高     |
| **Phase 3** | チーム/組織参加時の自動追加                     | 中     |
| **Phase 3** | チーム名/組織名検索からの申請                   | 中     |
| **Phase 3** | データ保持・自動削除バッチ                      | 中     |
| **Phase 4** | データエクスポート機能                          | 低     |

---

## 付録A: Flyway マイグレーション一覧

| ファイル名                                         | 内容                                                                                                      |
| -------------------------------------------------- | --------------------------------------------------------------------------------------------------------- |
| `V13.001__add_contact_settings_to_users.sql`       | `users` に `contact_handle`, `handle_searchable`, `contact_approval_required`, `online_visibility` を追加 |
| `V13.002__create_contact_requests_table.sql`       | `contact_requests` テーブル作成                                                                           |
| `V13.003__create_contact_request_blocks_table.sql` | `contact_request_blocks` テーブル作成                                                                     |
| `V13.004__create_contact_invite_tokens_table.sql`  | `contact_invite_tokens` テーブル作成                                                                      |

---

## 付録B: 通知種別

| notification_type          | タイミング                    | 受信者         |
| -------------------------- | ----------------------------- | -------------- |
| `CONTACT_REQUEST_RECEIVED` | PENDING 申請受信時            | target_id      |
| `CONTACT_REQUEST_ACCEPTED` | 申請承認時                    | requester_id   |
| `CONTACT_AUTO_ADDED`       | 自動追加時（ACCEPTED の場合） | 既存メンバー   |
| `CONTACT_INVITE_USED`      | 招待URL使用時                 | トークン発行者 |

> **v1.0 からの変更:** `CONTACT_REQUEST_REJECTED` を削除。拒否通知は送らない（プライバシー保護）。

---

## 付録C: v1.0 からの変更点サマリー（精査による修正20項目）

### 整合性修正

| #   | 修正内容                                                                                         |
| --- | ------------------------------------------------------------------------------------------------ |
| 1   | ブロックAPIパスを既存 `/api/v1/users/blocks` に統一                                              |
| 2   | `users.handle` → `users.contact_handle` にリネーム（`user_social_profiles.handle` との混同防止） |
| 3   | `ChatFolderItemResponse` 型に `custom_name`, `is_pinned`, `private_note` 追加を明記              |
| 4   | `UserProfileResponse` 型に `dmReceiveFrom`, `contactHandle`, `onlineVisibility` 追加を明記       |
| 5   | `contact_requests` UNIQUE KEY の穴をアプリ層チェックで補強（72時間再申請禁止等）                 |
| 6   | チーム退出時の連絡先残留動作を明記                                                               |
| 7   | 招待URL着地ページパスを `/contact-invite/[token]` に統一                                         |

### セキュリティ修正

| #   | 修正内容                                                                |
| --- | ----------------------------------------------------------------------- |
| 8   | ハンドル検索・重複チェックにレート制限追加                              |
| 9   | ブロック時のレスポンスを統一（タイミング攻撃対策）                      |
| 10  | 招待URL accept 時に user_blocks / contact_request_blocks チェックを追加 |
| 11  | 申請メッセージの XSS 対策（v-text 必須）を明記                          |
| 12  | 自動追加スパム対策（再参加30日クールダウン）を追加                      |
| 13  | 招待プレビューの情報最小化（avatarUrl 削除）                            |
| 14  | 予約語ハンドルリストを追加                                              |
| 15  | アカウント削除時の chat_contact_folder_items クリーンアップを追加       |
| 16  | 非公開チーム/組織メンバー列挙のアクセス制限を追加                       |

### プライバシー修正

| #   | 修正内容                                                                     |
| --- | ---------------------------------------------------------------------------- |
| 17  | `contact_approval_required` デフォルトを true に変更                         |
| 18  | 拒否通知（`CONTACT_REQUEST_REJECTED`）を削除                                 |
| 19  | サイレントブロック方式を全面採用（送信済み偽装等）                           |
| 20  | オンライン状態の公開範囲設定（`online_visibility`）を追加、デフォルト NOBODY |
| 21  | REJECTED/CANCELLED の保持期間（90日）を設定                                  |
| 22  | データエクスポート機能を追加                                                 |
| 23  | 招待トークンのデフォルトを「7日・1回」に変更                                 |
