# 横断設計: 統合ストレージクォータ

> **ステータス**: 🟡 設計中
> **実装フェーズ**: Phase 3（基盤・クォータ制御）+ Phase 8（課金・Stripe 連携）
> **最終更新**: 2026-04-11
> **影響範囲**: F04.1 タイムライン、F04.2 チャット、F05.1 掲示板、F05.2 回覧板、F05.5 ファイル共有、F06.1 CMS/ブログ、F06.2 メンバー紹介

---

## 1. 概要

Google の統合ストレージモデル（Gmail + Drive + Photos で合算15GB）に倣い、プラットフォーム上の全機能が消費する **Cloudflare R2** ストレージを**組織・チーム・個人**の3レベルで統合的に計量し、SYSTEM_ADMIN が設定した無料枠を超過した場合にアップロードを制限する仕組み。無料枠・有料プラン・超過料金は SYSTEM_ADMIN がプラットフォーム管理画面から自由に設定・変更できる。

### ストレージ基盤: Cloudflare R2

本プラットフォームは全ファイルストレージ（画像・動画・ドキュメント・添付ファイル等）を Cloudflare R2 に集約する。R2 を採用する主な理由は以下の通り:

- **エグレス料金ゼロ**: R2 はオブジェクト取得時の帯域課金がなく、動画の視聴回数が増えても配信コストが発生しない。これにより動画ファイルアップロード機能（F04.1 VIDEO_FILE、F06.2 メンバーギャラリー、F06.1 ブログ動画埋め込み等）を追加してもランニングコストが容量ベースで予測可能になる
- **S3 互換 API**: AWS SDK v3 でそのまま Presigned URL 発行・HeadObject・CopyObject が利用できるため、既存の Pre-signed URL フロー（`backend/.claudecode.md` §26）の発行元を R2 エンドポイント（`https://{accountId}.r2.cloudflarestorage.com`）に差し替えるだけで移行できる
- **Cloudflare Workers 連携**: ファイル配信は Workers ルート経由で認可 + ストリーミングを行う。AWS Lambda + CloudFront の代替として、画像の WebP 変換・動画メタデータ抽出・サムネイル生成は Cloudflare Workers（ffmpeg.wasm）または Cloudflare Images / Cloudflare Stream を用いる
- **ライフサイクル管理**: R2 の Object Lifecycle 機能で `tmp/` プレフィックス等の自動削除ルールを設定できる（S3 ライフサイクルルールと同等）

### 計量方針（R2 ベース）

- **単位**: バイト（`used_bytes BIGINT UNSIGNED`）を維持。アプリケーションが R2 に PUT したバイト数をそのまま DB に記録する（計算ロジックは S3 時代と変わらず）
- **計量タイミング**:
  - アプリ層加算: アップロード成功時に `StorageQuotaService.recordUpload()` がアトミック加算。画像は multipart 受信後、動画は R2 直アップロード後の HeadObject 確認時点
  - アプリ層減算: 論理削除から30日経過の完全削除バッチ時に減算
  - ドリフト補正: 週次バッチが R2 の ListObjectsV2 と `used_bytes` を突合し、差分があれば実測値に補正する（S3 時代の Listing API と同等）
- **帯域課金**: **ゼロ**。視聴・ダウンロード回数によるランニングコストの増大がないため、容量ベースの無料枠で全機能を統一管理する。動画の視聴回数制限は設けない

### 実装フェーズ

2段階に分けて実装する。Phase 4〜7 の間に無制限にストレージが蓄積された後で Phase 8 からクォータを適用すると「突然制限された」UX 問題が発生するため、基盤は Phase 3 で先行実装する。

#### Phase 3: 基盤・クォータ制御（共通基盤フェーズ）

Phase 4 以降の全アップロード機能より前に、クォータ制御の基盤を構築する。

| 項目 | 内容 |
|------|------|
| DB | `storage_plans`・`storage_subscriptions`・`storage_usage_logs` の3テーブル作成 |
| 初期データ | デフォルト無料プランの seed（暫定値: 組織 50GB / チーム 5GB / 個人 1GB） |
| 共通サービス | `StorageQuotaService`（checkQuota / recordUpload / recordDeletion） |
| クォータ制御 | 無料枠超過時は 409 でアップロード拒否（課金なし。ハードブロック） |
| 通知 | 80% / 90% / 100% の3段階で ADMIN / ユーザーにプッシュ通知 |
| 使用状況 API | `GET /teams/{id}/storage`・`GET /organizations/{id}/storage`・`GET /users/me/storage` |
| バッチ | 週次ドリフト検出・修正、日次ログパージ |
| 対象外 | SYSTEM_ADMIN のプラン管理 UI、有料プラン購入、Stripe 連携、超過課金 |

Phase 4 以降の各機能実装時に、アップロード処理へ `StorageQuotaService.checkQuota()` の呼び出しを組み込む。

#### Phase 8: 課金・Stripe 連携

| 項目 | 内容 |
|------|------|
| SYSTEM_ADMIN 管理画面 | プランの CRUD（`POST/PUT/DELETE /system-admin/storage-plans`） |
| プラン購入 UI | ADMIN がチーム/組織のプランを変更（`PUT /teams/{id}/storage/plan`） |
| 超過課金 | `price_per_extra_gb` に基づく月次超過課金（Stripe 連携） |
| 請求書 | ストレージ超過分を統合請求書に合算 |
| ダウングレード | 猶予期間の設計・通知フロー |
| SYSTEM_ADMIN 全体管理 | `GET /system-admin/storage-usage`（全スコープの使用状況一覧） |

---

## 2. 設計方針

### クォータ階層モデル: 独立プール型

```
組織クォータ（例: 50GB）   ← 組織レベルの回覧板・CMS・お知らせ等
  ├─ チームAクォータ（例: 5GB） ← チームレベルのファイル共有・回覧板・掲示板等
  ├─ チームBクォータ（例: 5GB）
  └─ ...
個人クォータ（例: 1GB）    ← 個人チャットDM・個人スケジュール添付等
```

- 各スコープは**独立した容量枠**を持つ（共有プール型ではない）
- 組織のクォータとチームのクォータは独立。チームの使用量が組織の枠を消費することはない
- 理由: 「誰が使いすぎているか」の管理がシンプル。スコープ間の干渉がない

### カウント対象

| 対象 | カウント | 理由 |
|------|---------|------|
| ユーザーがアップロードしたオリジナル画像・動画・ドキュメント | **する** | ユーザーが制御可能な消費。動画ファイル（F04.1 VIDEO_FILE、F06.2 動画、F06.1 ブログ動画、F04.2 チャット動画添付）もここに含む |
| Cloudflare Workers / Cloudflare Images / Cloudflare Stream が生成するサムネイル・WebP 変換・動画ポスターフレーム | **しない** | システム都合で自動生成。ユーザーが制御不可。旧 AWS Lambda 生成物と同じ扱い |
| R2 上のバージョン管理ファイル（F05.5） | **する** | 全バージョンが容量を消費（最大20世代） |
| アバター画像（ユーザー/チーム） | **しない** | 微量（2MB以下）かつ基本機能。容量不足でアバター設定不可は UX が悪い |
| チームロゴ | **しない** | 同上 |
| PDF エクスポート（回覧板等のシステム生成PDF） | **しない** | システムが自動生成するキャッシュファイル |
| チャットの冷蔵庫アーカイブ（Cold Tier） | **する** | 長期保存のユーザーデータ |
| 動画リンク（VIDEO_LINK: YouTube / Vimeo の外部 URL） | **しない** | 外部サービスの URL を保持するだけで R2 バイトを消費しない |

> **動画視聴回数による追加課金は行わない**。R2 はエグレス料金ゼロのため、容量ベースの計量のみで全ユーザーに公平な枠を提供する。無料枠の容量自体は S3 時代と同一に保ち、動画投稿が増えても運用コストが急増しない設計になっている。

### スコープ帰属ルール

アップロード先のスコープに帰属する:

| 操作 | 帰属先 | 例 |
|------|--------|---|
| チームの回覧板に添付 | チームクォータ | チームAの回覧板 → チームAの使用量 |
| 組織レベルの CMS に画像投稿 | 組織クォータ | 組織のブログ → 組織の使用量 |
| チームのファイル共有にアップロード | チームクォータ | チームBのフォルダ → チームBの使用量 |
| 個人チャット DM に添付 | 個人クォータ | 自分→相手の DM → 送信者の使用量 |
| チームチャットに添付 | チームクォータ | チームAのチャット → チームAの使用量 |
| タイムライン投稿（チームスコープ） | チームクォータ | チームAのタイムライン → チームAの使用量 |

---

## 3. DB設計

### テーブル一覧
| テーブル名 | 役割 | 論理削除 |
|-----------|------|---------|
| `storage_plans` | ストレージプラン定義（容量・料金。SYSTEM_ADMIN 管理） | あり |
| `storage_subscriptions` | スコープ別のプラン紐付け + リアルタイム使用量追跡 | なし |
| `storage_usage_logs` | 使用量変動の履歴ログ（増減の追跡・デバッグ用） | なし |

### テーブル定義

#### `storage_plans`

SYSTEM_ADMIN が管理するストレージプラン定義。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `name` | VARCHAR(100) | NO | — | プラン名（例: 「フリー」「スタンダード」「プレミアム」） |
| `scope_level` | VARCHAR(20) | NO | — | 適用レベル（`ORGANIZATION` / `TEAM` / `PERSONAL`） |
| `included_bytes` | BIGINT UNSIGNED | NO | — | 無料枠のバイト数（例: 5GB = 5,368,709,120） |
| `max_bytes` | BIGINT UNSIGNED | YES | NULL | ハード上限（NULL = 超過課金で無制限） |
| `price_monthly` | DECIMAL(10,2) | NO | 0 | 月額料金（税抜。0 = 無料プラン） |
| `price_yearly` | DECIMAL(10,2) | YES | NULL | 年額料金（NULL = 月額のみ） |
| `price_per_extra_gb` | DECIMAL(10,2) | YES | NULL | 超過時の GB 単価（NULL = 超過不可、ハードブロック） |
| `is_default` | BOOLEAN | NO | FALSE | 新規作成時に自動適用されるデフォルトプラン（scope_level 別に1つ） |
| `sort_order` | SMALLINT | NO | 0 | プラン選択画面の表示順 |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |
| `deleted_at` | DATETIME | YES | NULL | 論理削除 |

**インデックス**
```sql
INDEX idx_sp_level (scope_level, is_default, deleted_at)  -- レベル別デフォルトプラン検索
```

**制約・備考**
- `scope_level` + `is_default = TRUE` + `deleted_at IS NULL` の組み合わせはユニーク（レベル別に1つのデフォルト）
- `price_per_extra_gb = NULL` かつ `max_bytes = NULL` の場合: `included_bytes` がハード上限（超過時は 409 で拒否）
- `price_per_extra_gb IS NOT NULL` の場合: 超過分を月次課金（Phase 8 で Stripe 連携）
- SYSTEM_ADMIN が管理画面からプランの作成・編集・無効化（論理削除）が可能
- 既に利用中のプランを削除した場合、既存サブスクリプションは維持（新規割当不可になるのみ）

#### `storage_subscriptions`

スコープ別のストレージプラン紐付けとリアルタイム使用量。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `scope_type` | VARCHAR(20) | NO | — | `ORGANIZATION` / `TEAM` / `PERSONAL` |
| `scope_id` | BIGINT UNSIGNED | NO | — | organizations.id / teams.id / users.id |
| `plan_id` | BIGINT UNSIGNED | NO | — | FK → storage_plans |
| `used_bytes` | BIGINT UNSIGNED | NO | 0 | 現在の使用量（バイト。リアルタイム更新） |
| `file_count` | INT UNSIGNED | NO | 0 | 現在のファイル数 |
| `last_notified_threshold` | SMALLINT | YES | NULL | 最後に通知した閾値（%）。80 / 90 / 100。同じ閾値の重複通知を防止 |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
UNIQUE KEY uq_ss_scope (scope_type, scope_id)    -- スコープ別に1レコード
INDEX idx_ss_plan (plan_id)                        -- プラン別のサブスクリプション一覧
```

**制約・備考**
- 組織・チーム・ユーザー作成時に自動で `storage_subscriptions` を INSERT（デフォルトプランを割当）
- `used_bytes` はファイルアップロード時に `+file_size`、削除時に `-file_size` でアトミック更新
- `used_bytes` の整合性は週次バッチで実際の R2 使用量（ListObjectsV2 の集計）と突合（ドリフト検出・自動修正）

#### `storage_usage_logs`

使用量の変動履歴。デバッグ・監査・使用量推移グラフに使用。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `subscription_id` | BIGINT UNSIGNED | NO | — | FK → storage_subscriptions |
| `delta_bytes` | BIGINT | NO | — | 変動量（正=増加、負=減少） |
| `after_bytes` | BIGINT UNSIGNED | NO | — | 変動後の使用量 |
| `feature_type` | VARCHAR(30) | NO | — | 機能種別（`FILE_SHARING` / `CIRCULATION` / `BULLETIN` / `CHAT` / `TIMELINE` / `CMS` / `GALLERY`） |
| `reference_type` | VARCHAR(50) | NO | — | 参照先テーブル（例: `shared_files`, `circulation_attachments`） |
| `reference_id` | BIGINT UNSIGNED | NO | — | 参照先レコード ID |
| `action` | VARCHAR(20) | NO | — | `UPLOAD` / `DELETE` / `VERSION_ADD` / `VERSION_DELETE` / `DRIFT_CORRECTION` |
| `actor_id` | BIGINT UNSIGNED | YES | NULL | FK → users（操作者。バッチ処理の場合は NULL） |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |

**インデックス**
```sql
INDEX idx_sul_sub (subscription_id, created_at DESC)     -- スコープ別の使用量推移
INDEX idx_sul_feature (feature_type, created_at DESC)     -- 機能別の使用量推移
```

**制約・備考**
- INSERT のみ（UPDATE / DELETE 不可。監査ログと同じ設計）
- `DRIFT_CORRECTION`: 週次バッチで `used_bytes` と実際の R2 使用量に差異があった場合の自動修正レコード
- 保持期間: 1年。1年以上前のレコードは日次バッチで物理削除（証跡性は `storage_subscriptions.used_bytes` で担保）

### ER図（テキスト形式）
```
storage_plans (1) ──── (N) storage_subscriptions
storage_subscriptions (1) ──── (N) storage_usage_logs
organizations / teams / users (1) ──── (0..1) storage_subscriptions [scope_type + scope_id]
```

---

## 4. クォータチェック共通フロー

全機能のファイルアップロード時に呼び出される共通ロジック。

```
1. アップロードリクエストを受信（R2 Presigned URL 発行前 or multipart 受信時）
2. スコープの特定:
   a. scope_type + scope_id をアップロード先から決定
   b. storage_subscriptions をスコープで検索
3. クォータチェック:
   a. plan = storage_plans から included_bytes, max_bytes, price_per_extra_gb を取得
   b. 判定ロジック:
      - used_bytes + file_size <= included_bytes → ✅ 許可
      - used_bytes + file_size > included_bytes かつ price_per_extra_gb IS NOT NULL
        → ✅ 許可（超過分は月次課金。Phase 8 で実装）
      - used_bytes + file_size > included_bytes かつ price_per_extra_gb IS NULL
        → ❌ 拒否（409 Conflict: "ストレージ容量が不足しています"）
      - max_bytes IS NOT NULL かつ used_bytes + file_size > max_bytes
        → ❌ 拒否（409: "ストレージ上限に達しています"）
4. アップロード許可の場合:
   a. R2 S3 互換 API で Presigned URL を発行 / ファイル受信を続行
   b. アップロード完了後（動画の場合は HeadObject で R2 上の実在を確認してから）:
      - storage_subscriptions.used_bytes をアトミック更新（+file_size）
      - storage_subscriptions.file_count をアトミック更新（+1）
      - storage_usage_logs に INSERT（action = UPLOAD）
5. 通知チェック（アップロード後に非同期実行）:
   a. 使用率 = used_bytes / included_bytes × 100 を計算
   b. 閾値判定（80% / 90% / 100%）:
      - 使用率が閾値を超え、かつ last_notified_threshold < 閾値 の場合:
        - ADMIN / 個人ユーザーにプッシュ通知:
          - 80%: 「ストレージ使用量が80%を超えました（{used}/{total}）」
          - 90%: 「ストレージ使用量が90%を超えました。不要なファイルの削除をご検討ください」
          - 100%: 「ストレージ容量が上限に達しました。新規アップロードが制限されます」
        - last_notified_threshold を更新
   c. 使用率が閾値を下回った場合（ファイル削除後）:
      - last_notified_threshold をリセット（次回超過時に再通知可能に）
```

### 各機能からの呼び出しパターン

```java
// 共通サービス
@Service
public class StorageQuotaService {
    /**
     * アップロード前のクォータチェック。
     * 容量超過時は StorageQuotaExceededException をスロー。
     */
    public void checkQuota(ScopeType scopeType, Long scopeId, long fileSizeBytes);

    /**
     * アップロード完了後の使用量加算。
     */
    public void recordUpload(ScopeType scopeType, Long scopeId,
                             long fileSizeBytes, FeatureType featureType,
                             String referenceType, Long referenceId, Long actorId);

    /**
     * ファイル削除後の使用量減算。
     */
    public void recordDeletion(ScopeType scopeType, Long scopeId,
                               long fileSizeBytes, FeatureType featureType,
                               String referenceType, Long referenceId, Long actorId);
}
```

各機能のアップロード処理で以下のように呼び出す:
```
// F05.2 回覧板の例
POST /api/v1/circulation/{id}/attachments
  → storageQuotaService.checkQuota(TEAM, teamId, fileSize)  // 事前チェック
  → Cloudflare R2 へアップロード（multipart 受信 or Presigned URL 発行）
  → storageQuotaService.recordUpload(TEAM, teamId, fileSize, CIRCULATION,
                                      "circulation_attachments", attachmentId, userId)
```

**動画アップロードの呼び出し順序（F04.1 の例）**:
```
POST /api/v1/timeline/attachments/upload-url
  → storageQuotaService.checkQuota(TEAM, teamId, fileSize)  // Presigned URL 発行前に事前チェック
  → R2 Presigned URL（有効期限15分）を発行
  → クライアント: PUT {upload_url} でR2に直アップロード
POST /api/v1/timeline
  → R2 HeadObject で file_key の実在確認
  → storageQuotaService.checkQuota(..., fileSize)  // レースコンディション対策で再チェック
  → timeline_post_attachments に INSERT（video_processing_status = PENDING）
  → storageQuotaService.recordUpload(TEAM, teamId, fileSize, TIMELINE,
                                      "timeline_post_attachments", attachmentId, userId)
  → Cloudflare Workers を非同期起動して動画メタデータ抽出・サムネイル生成
```

---

## 5. API設計

### エンドポイント一覧
| メソッド | パス | 認証 | Phase | 説明 |
|---------|-----|------|-------|------|
| GET | `/api/v1/teams/{id}/storage` | 必要 | 3 | チームのストレージ使用状況 |
| GET | `/api/v1/organizations/{id}/storage` | 必要 | 3 | 組織のストレージ使用状況 |
| GET | `/api/v1/users/me/storage` | 必要 | 3 | 個人のストレージ使用状況 |
| GET | `/api/v1/storage-plans` | 必要 | 3 | 利用可能なプラン一覧（Phase 3 ではデフォルトプランのみ） |
| PUT | `/api/v1/teams/{id}/storage/plan` | 必要 | 8 | チームのプラン変更（ADMIN。有料プラン購入） |
| PUT | `/api/v1/organizations/{id}/storage/plan` | 必要 | 8 | 組織のプラン変更（ADMIN。有料プラン購入） |
| GET | `/api/v1/system-admin/storage-plans` | 必要 | 8 | プラン管理一覧（SYSTEM_ADMIN） |
| POST | `/api/v1/system-admin/storage-plans` | 必要 | 8 | プラン作成（SYSTEM_ADMIN） |
| PUT | `/api/v1/system-admin/storage-plans/{id}` | 必要 | 8 | プラン編集（SYSTEM_ADMIN） |
| DELETE | `/api/v1/system-admin/storage-plans/{id}` | 必要 | 8 | プラン無効化（SYSTEM_ADMIN） |
| GET | `/api/v1/system-admin/storage-usage` | 必要 | 8 | 全スコープの使用状況一覧（SYSTEM_ADMIN） |

### リクエスト／レスポンス仕様

#### `GET /api/v1/teams/{id}/storage`

**認可**: 対象チームの MEMBER 以上

チームのストレージ使用状況を取得する。

**レスポンス（200 OK）**
```json
{
  "data": {
    "plan": {
      "id": 1,
      "name": "フリー",
      "included_bytes": 5368709120,
      "max_bytes": null,
      "price_per_extra_gb": null
    },
    "usage": {
      "used_bytes": 2147483648,
      "used_percentage": 40.0,
      "file_count": 128,
      "breakdown": [
        { "feature_type": "FILE_SHARING", "used_bytes": 1500000000, "file_count": 45 },
        { "feature_type": "CIRCULATION", "used_bytes": 400000000, "file_count": 30 },
        { "feature_type": "BULLETIN", "used_bytes": 200000000, "file_count": 40 },
        { "feature_type": "CHAT", "used_bytes": 47483648, "file_count": 13 }
      ]
    },
    "is_over_limit": false,
    "can_upload": true
  }
}
```

**補足**
- `breakdown`: 機能別の内訳。`storage_usage_logs` から集計（キャッシュ推奨）
- `is_over_limit`: `used_bytes >= included_bytes` の場合 `true`
- `can_upload`: 超過課金プラン（`price_per_extra_gb IS NOT NULL`）の場合は `is_over_limit = true` でも `can_upload = true`
- 組織・個人の API も同一構造

#### `POST /api/v1/system-admin/storage-plans`

**認可**: SYSTEM_ADMIN

新規ストレージプランを作成する。

**リクエストボディ**
```json
{
  "name": "スタンダード",
  "scope_level": "TEAM",
  "included_bytes": 53687091200,
  "max_bytes": null,
  "price_monthly": 500,
  "price_yearly": 5000,
  "price_per_extra_gb": 50,
  "is_default": false,
  "sort_order": 1
}
```

**レスポンス（201 Created）**: 作成されたプランオブジェクト

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | name 未入力 / included_bytes が 0 / scope_level が不正 |
| 403 | SYSTEM_ADMIN でない |
| 409 | is_default = true だが同一 scope_level に既にデフォルトが存在 |

#### `GET /api/v1/system-admin/storage-usage`

**認可**: SYSTEM_ADMIN

全スコープのストレージ使用状況を一覧表示する。

**クエリパラメータ**
| パラメータ | 型 | 必須 | 説明 |
|-----------|---|------|------|
| `scope_type` | String | No | `ORGANIZATION` / `TEAM` / `PERSONAL`（フィルタ） |
| `over_limit` | Boolean | No | `true` の場合、容量超過のスコープのみ |
| `sort` | String | No | `used_bytes_desc`（使用量降順）/ `used_percentage_desc`（使用率降順）。デフォルト: `used_percentage_desc` |
| `cursor` | Long | No | カーソル |
| `limit` | Integer | No | 取得件数（デフォルト: 50） |

**レスポンス（200 OK）**
```json
{
  "data": [
    {
      "scope_type": "TEAM",
      "scope_id": 1,
      "scope_name": "開発チーム",
      "plan_name": "フリー",
      "included_bytes": 5368709120,
      "used_bytes": 5000000000,
      "used_percentage": 93.1,
      "file_count": 200,
      "is_over_limit": false
    }
  ],
  "meta": { "next_cursor": null, "limit": 50, "has_next": false }
}
```

---

## 6. バッチ処理

### 使用量ドリフト検出・修正（週次）
```
1. Spring @Scheduled バッチが週次で実行（日曜深夜）
2. 全 storage_subscriptions を走査:
   a. Cloudflare R2 上の実ファイルサイズを feature_type 別に集計
      （R2 の ListObjectsV2 でプレフィックス単位に走査。
       カウント対象ルールに基づき、Workers 生成のサムネイル・WebP 変換物・
       動画ポスターフレーム・アバター等を除外）
   b. used_bytes と実際の合計を比較
   c. 差異が 1MB 以上の場合:
      - used_bytes を実際の値に修正
      - storage_usage_logs に DRIFT_CORRECTION を記録
      - アプリケーションログに WARNING を出力
3. 差異が頻発するスコープがあれば、アプリ層のバグ（削除時の減算漏れ等）を調査
```

### 使用量ログのパージ（日次）
```
1. storage_usage_logs から created_at < NOW() - INTERVAL 1 YEAR のレコードを物理削除
2. バッチサイズ: 1,000件ずつ DELETE（ロック長期化を防止）
```

---

## 7. セキュリティ考慮事項

- **クォータチェックのバイパス防止**: R2 Presigned URL 発行前に必ずクォータチェックを実行。Presigned URL を使い回して容量超過状態でアップロードされた場合、Cloudflare Workers の R2 Event Notification（または週次ドリフトバッチ）でサイズチェックし、超過分を自動削除 + ADMIN に通知
- **used_bytes の改ざん防止**: `used_bytes` の更新は `StorageQuotaService` 経由のみ。直接 UPDATE を禁止（コードレビューで担保）
- **レートリミット**: ストレージ使用状況 API は Bucket4j で 1分間に30回。プラン変更 API は 1分間に5回
- **SYSTEM_ADMIN プラン管理の監査**: プランの作成・編集・削除は `audit_logs` に記録

---

## 8. 各機能ドキュメントへの影響

以下の機能ドキュメントで、アップロード処理に `StorageQuotaService.checkQuota()` の呼び出しを追記する:

| 機能 | ドキュメント | 対象 API | 対象メディア | スコープ帰属 |
|------|------------|---------|-----------|------------|
| F04.1 タイムライン | F04.1_timeline.md | 画像投稿、動画ファイル投稿（VIDEO_FILE）| 画像10MB/枚・動画200MB/本 | scope_type に従う |
| F04.2 チャット | F04.2_chat.md | ファイル添付（画像・動画・ドキュメント）| 画像10MB / 動画50MB | チャットルームの scope_type に従う。DIRECT / GROUP_DM は送信者の個人クォータ |
| F05.1 掲示板 | F05.1_bulletin_board.md | 添付ファイル | 画像・ドキュメント | scope_type に従う |
| F05.2 回覧板 | F05.2_circular.md | 添付ファイル追加 | 画像・ドキュメント | scope_type に従う |
| F05.5 ファイル共有 | F05.5_file_sharing.md | ファイルアップロード・バージョン追加・Multipart Upload | 通常100MB / Multipart 最大5GB（動画・大容量） | scope_type に従う（**既存の `team_storage_subscriptions` を `storage_subscriptions` に統合**） |
| F06.1 CMS/ブログ | F06.1_cms_blog.md | 画像・動画アップロード（blog_image_uploads）| 画像10MB / 動画300MB | scope_type に従う |
| F06.2 メンバー紹介 | F06.2_member_gallery.md | 写真・動画アップロード（photos.media_type）| 写真20MB / 動画500MB | scope_type に従う |

**F05.5 への移行**: 既存の `team_storage_subscriptions` テーブルは `storage_subscriptions` に統合。F05.5 固有のストレージ追跡ロジックは共通の `StorageQuotaService` に移行する。

---

## 9. Flyway マイグレーション

### Phase 3: 基盤（共通基盤フェーズ）
```
V3.020__create_storage_plans_table.sql              -- ストレージプラン定義
V3.021__create_storage_subscriptions_table.sql       -- スコープ別サブスクリプション + 使用量
V3.022__create_storage_usage_logs_table.sql          -- 使用量変動ログ
V3.023__seed_default_storage_plans.sql               -- デフォルト無料プラン初期データ（暫定値）
```

### Phase 5: F05.5 移行
```
V5.050__migrate_team_storage_subscriptions.sql       -- F05.5 の既存データを移行（team_storage_subscriptions → storage_subscriptions）
```

**マイグレーション上の注意点**
- V3.023 のデフォルトプラン初期データは暫定値（Phase 8 の課金設計確定時に見直し）。Phase 3 時点では無料プランのみ seed
- V3.020〜V3.022 は Phase 3 の共通基盤として、認証テーブル（Phase 1）の後に実行
- V5.050 は F05.5 実装時に既存の `team_storage_subscriptions` からデータを移行し、旧テーブルをリネーム（`_deprecated` suffix）。一定期間後に DROP
- Phase 4 以降の各機能実装時に、アップロード処理へ `StorageQuotaService.checkQuota()` を組み込む（マイグレーション不要、アプリ層のみ）

---

## 10. 未解決事項

- [ ] **デフォルト無料枠の具体的な容量** → Phase 8 課金設計時に決定。暫定値: 組織 50GB / チーム 5GB / 個人 1GB
- [ ] **超過課金の GB 単価** → Phase 8 で Stripe 連携時に決定
- [ ] **プランのダウングレード時の挙動** → 既存ファイルが新プランの上限を超過している場合の猶予期間・通知フロー。提案: 即時ダウングレード可能だが超過状態では新規アップロード不可。ファイル削除は強制しない
- [ ] **チャット DM の帰属** → 送信者 vs 受信者どちらの個人クォータを消費するか。提案: 送信者（アップロードした人が責任を持つ）
- [ ] **組織解散・チーム解散時のストレージ解放** → R2 ファイルの物理削除タイミング。提案: 30日の猶予期間後にバッチで物理削除

---

## 11. 変更履歴

| 日付 | 変更内容 |
|------|---------|
| 2026-03-15 | 初版作成: 統合ストレージクォータの横断設計。独立プール型（組織/チーム/個人）。DB 3テーブル（storage_plans / storage_subscriptions / storage_usage_logs）。API 11本。クォータチェック共通フロー。SYSTEM_ADMIN によるプラン管理。80/90/100% の3段階通知。週次ドリフト検出バッチ。F05.5 既存テーブルからの移行計画 |
| 2026-03-15 | 2段階実装フェーズを明記。Phase 3（基盤: DB・StorageQuotaService・ハードブロック・通知・使用状況API 4本・バッチ2本）+ Phase 8（課金: SYSTEM_ADMIN管理画面・プラン購入UI・Stripe連携・超過課金・API 7本）。Flyway を V3.020〜V3.023（基盤）+ V5.050（F05.5移行）に再編。エンドポイント一覧に Phase 列を追加 |
| 2026-04-11 | ストレージ基盤を AWS S3 → Cloudflare R2 に全面差し替え。クォータ計上ロジック（GB 単位・組織/チーム/個人の独立プール）はそのまま維持。動画ファイル（F04.1 VIDEO_FILE、F06.2 動画、F06.1 ブログ動画）もクォータ計上対象として明記。R2 のエグレス料金ゼロに伴い、視聴回数による追加課金は行わず容量ベースの計量のみに統一。ドリフト検出バッチを R2 ListObjectsV2 ベースに更新。Lambda 生成物 → Cloudflare Workers / Cloudflare Images / Cloudflare Stream 生成物に呼称変更（カウント除外扱いは同じ）。動画アップロード時の `StorageQuotaService` 呼び出し順序を明示 |
