# 横断設計: 統合ストレージクォータ

> **ステータス**: 🟡 設計中（Phase 3 基盤 完了 / Phase 4 機能別統合 完了 / **Phase 5 R2パス命名規則変更 設計完了・実装待ち** / Phase 8 課金 未着手）
> **実装フェーズ**: Phase 3（基盤・クォータ制御）+ Phase 4（機能別統合ロードマップ）+ **Phase 5（R2スコープ別パス命名規則変更・ドリフトバッチ精度向上）** + Phase 8（課金・Stripe 連携）
> **最終更新**: 2026-05-04
> **影響範囲**: F03.14 スケジュールメディア、**F03.15 個人時間割メモ添付**、F04.1 タイムライン、F04.2 チャット、F05.1 掲示板、F05.2 回覧板、F05.5 ファイル共有、F06.1 CMS/ブログ、F06.2 メンバー紹介

---

## 1. 概要

Google の統合ストレージモデル（Gmail + Drive + Photos で合算15GB）に倣い、プラットフォーム上の全機能が消費する **Cloudflare R2** ストレージを**組織・チーム・個人**の3レベルで統合的に計量し、SYSTEM_ADMIN が設定した無料枠を超過した場合にアップロードを制限する仕組み。無料枠・有料プラン・超過料金は SYSTEM_ADMIN がプラットフォーム管理画面から自由に設定・変更できる。

### ストレージ基盤: Cloudflare R2

本プラットフォームは全ファイルストレージ（画像・動画・ドキュメント・添付ファイル等）を Cloudflare R2 に集約する。R2 を採用する主な理由は以下の通り:

- **エグレス料金ゼロ**: R2 はオブジェクト取得時の帯域課金がなく、動画の視聴回数が増えても配信コストが発生しない。これにより動画ファイルアップロード機能（F04.1 VIDEO_FILE、F06.2 メンバーギャラリー、F06.1 ブログ動画埋め込み等）を追加してもランニングコストが容量ベースで予測可能になる
- **S3 互換 API**: AWS SDK v3 でそのまま Presigned URL 発行・HeadObject・CopyObject が利用できるため、既存の Pre-signed URL フロー（`backend/.claudecode.md` §26）の発行元を R2 エンドポイント（`https://{accountId}.r2.cloudflarestorage.com`）に差し替えるだけで移行できる
- **Cloudflare Workers 連携**: ファイル配信は Workers ルート経由で認可 + ストリーミングを行う。AWS Lambda + CloudFront の代替として、画像の WebP 変換・動画メタデータ抽出・サムネイル生成は Cloudflare Workers（ffmpeg.wasm）または Cloudflare Images を用いる（Cloudflare Stream は R2 と別課金体系で割高になるため採用しない）
- **ライフサイクル管理**: R2 の Object Lifecycle 機能で `tmp/` プレフィックス等の自動削除ルールを設定できる（S3 ライフサイクルルールと同等）

### バケット構成: 単一バケット + プレフィックス分割

**原則: 全機能は `mannschaft-storage` 単一バケットにプレフィックスで分離して書き込む**。機能ごとにバケットを分ける運用は採用しない。理由: クォータ集計の簡素化（`ListObjectsV2` を単一バケットに対して走査するだけで済む）、ライフサイクルルール管理の一元化、鍵運用の単純化。プレフィックス規約の詳細・ライフサイクルルールのサンプル JSON は `docs/db_design_details.md` の「R2 単一バケット プレフィックス規約」セクションを参照（Single Source of Truth）。

**クォータ集計への影響**: ドリフト検出バッチ（週次）は `mannschaft-storage` バケットに対してプレフィックス単位で `ListObjectsV2` を実行し、`timeline/` / `gallery/` / `files/` / `chat/` / `blog/` / `chart/` の各配下のバイト数を集計する。`thumbnails/` プレフィックス配下と Cloudflare Images の variant は自動生成物なのでカウント対象外（後述のカウント対象テーブル参照）。

### 機能別ファイルサイズ上限は設けない（方針）

**チーム・組織・個人ごとの容量枠で課金するモデル**であるため、「機能ごとに恣意的なサイズ上限」を設ける運用は採用しない。ユーザーは割り当てられた容量の範囲内であれば、どの機能でも自由にファイルをアップロードできる。アップロード拒否の条件は以下の**技術的下限**と**スコープ容量枠**のみ。

| 制限 | 値 | 理由 |
|---|---|---|
| **単発 PUT の上限** | 100MB | 100MB を超える場合は Multipart Upload を必須化。理由: ブラウザメモリ・回線切断リスク・リトライ耐性 |
| **Multipart 1 オブジェクト上限** | 5TB | Cloudflare R2 のハード上限 |
| **チャット添付上限（UX ガード）** | 500MB | 容量課金ではなく **UX 観点** の上限。大容量ファイルは F05.5 ファイル共有へ誘導（チャットのストリームは短時間メッセージ中心のため） |
| **ブログ本文埋め込み上限（UX ガード）** | 1GB | 容量課金ではなく **記事読み込み UX 観点** の上限。1GB 超の動画は F05.5 ファイル共有で配布し、ブログからリンクする運用を推奨 |
| **チーム・組織・個人の容量枠** | プラン依存 | 課金モデルの基盤。本ドキュメントの `storage_plans` / `storage_subscriptions` で一元管理 |

上記 UX ガード（チャット 500MB / ブログ 1GB）は「プラン課金上限」ではなく「機能別 UX 上限」である点に注意。これらは容量枠の範囲内であっても、その機能のユースケースから外れる巨大ファイルを別機能（F05.5）へ誘導するためのソフトリミット。プラン容量枠による拒否とは別軸のバリデーションであり、矛盾しない。

### UX ガード（アップロード前後の動作）

全機能共通のアップロード体験として以下を実装する:

- **使用率プレビュー表示**: ファイル選択時に「現在の使用率 + このファイル = 予測使用率」をダイアログ上に表示し、ユーザーが容量残量を把握した上でアップロードを確定できる
- **容量枠超過時の 409 Conflict**: `checkQuota()` で `used_bytes + file_size > included_bytes` の場合は **409 Conflict** を返却し、フロントエンドでプラン更新への導線（`/teams/{id}/settings/storage-plan` 等）を表示する
- **技術下限（100MB / 5TB）超過時の 400 Bad Request**: 単発 PUT に 100MB 超を指定した場合は Multipart Upload の案内メッセージを返却する
- **UX ガード（チャット 500MB / ブログ 1GB）超過時の 413 Payload Too Large**: プラン容量枠には余裕がある場合でも、機能別 UX ガードを超過したら 413 を返却し、F05.5 ファイル共有への誘導メッセージを含める

### Class A / B オペレーション課金（運用メモ）

Cloudflare R2 はエグレス料金ゼロだが、**Class A オペレーション**（`PutObject`・`ListObjectsV2`・`CreateMultipartUpload` 等の書き込み系・リスト系、$4.50 / 百万リクエスト）と **Class B オペレーション**（`GetObject`・`HeadObject` 等の読み取り系、$0.36 / 百万リクエスト）に対して従量課金が発生する。大量の小ファイル（数KB 単位）を扱うと容量課金よりオペレーション課金のほうが支配的になるケースがあるため、以下を運用メモとして明記する:

- **チャット添付・タイムライン画像など小ファイルの多い機能**: クライアント側でまとめてアップロードする場合は 1 PUT あたりのサイズを大きくとる（画像は 300px サムネイルではなくオリジナルだけを PUT し、サムネイルは Workers 側で生成して `thumbnails/` に書き込む）
- **ドリフト検出バッチの頻度**: 週次に制限する（Class A の `ListObjectsV2` 課金を抑制）。バッチ実行時はページングサイズを最大（1000 件 / ページ）にする
- **サムネイル生成の冪等性**: Workers が同じオブジェクトキーに対して重複してサムネイルを生成しないように `HeadObject` で存在確認してから PUT する（ただし HeadObject も Class B 課金対象なので、DB の `processing_status` で事前判定する方が望ましい）
- **監視**: Cloudflare ダッシュボードの R2 メトリクスで Class A / B のリクエスト数を週次で監視し、異常値が出たら該当機能を調査する

### 計量方針（R2 ベース）

- **単位**: バイト（`used_bytes BIGINT UNSIGNED`）を維持。アプリケーションが R2 に PUT したバイト数をそのまま DB に記録する（計算ロジックは S3 時代と変わらず）
- **計量タイミング**:
  - アプリ層加算: アップロード成功時に `StorageQuotaService.recordUpload()` がアトミック加算。画像は multipart 受信後、動画は R2 直アップロード後の HeadObject 確認時点
  - アプリ層減算: 論理削除から30日経過の完全削除バッチ時に減算
  - ドリフト補正: 週次バッチが R2 の ListObjectsV2 と `used_bytes` を突合し、差分があれば実測値に補正する（S3 時代の Listing API と同等）
- **帯域課金**: **ゼロ**。視聴・ダウンロード回数によるランニングコストの増大がないため、容量ベースの無料枠で全機能を統一管理する。動画の視聴回数制限は設けない

### 実装フェーズ

3段階に分けて実装する。Phase 4〜7 の間に無制限にストレージが蓄積された後で Phase 8 からクォータを適用すると「突然制限された」UX 問題が発生するため、基盤は Phase 3 で先行実装し、各機能のアップロード経路は Phase 4 で `StorageQuotaService` に統合する。

> **Phase 4 機能別統合ロードマップは §11 を参照**。Phase 3 基盤は完了済みだが、各機能の `checkQuota` 統合は遅延しており、F03.15 個人時間割は独自に 100MB 直書きクォータで稼働中。Phase 4 で順次救出する。

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
| Cloudflare Workers / Cloudflare Images が生成するサムネイル・WebP 変換・動画ポスターフレーム（`thumbnails/` プレフィックス配下 + Cloudflare Images variant） | **しない** | システム都合で自動生成。ユーザーが制御不可。旧 AWS Lambda 生成物と同じ扱い |
| クライアント側で Canvas 抽出した動画サムネイル（`thumbnails/` プレフィックス配下） | **しない** | 自動生成ロジックと同一性質。ユーザーが手動で置き換えることは基本できない |
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
| `feature_type` | VARCHAR(30) | NO | — | 機能種別（`FILE_SHARING` / `CIRCULATION` / `BULLETIN` / `CHAT` / `TIMELINE` / `CMS` / `GALLERY` / `PERSONAL_TIMETABLE_NOTES` / `SCHEDULE_MEDIA`）。**PROFILE_MEDIA は対象外**（アイコン・バナーは数MBレベルでクォータ計上しない方針） |
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
2. 対象バケット: mannschaft-storage（単一バケット）
3. 全 storage_subscriptions を走査:
   a. Cloudflare R2 の ListObjectsV2 を以下のプレフィックスで走査し、
      feature_type 別の実バイト数を集計:
      - timeline/{scopeType}/{scopeId}/    → TIMELINE
      - gallery/{scopeType}/{scopeId}/     → GALLERY
      - files/{scopeType}/{scopeId}/       → FILE_SHARING
      - chat/{scopeType}/{scopeId}/        → CHAT
      - blog/{scopeType}/{scopeId}/        → CMS
      - chart/{scopeType}/{scopeId}/       → （カルテ。Phase に応じて対象化）
   b. 除外対象（カウント対象ルールに基づく）:
      - thumbnails/ プレフィックス配下（Workers / クライアント生成サムネイル）
      - Cloudflare Images の variant
      - アバター・チームロゴ（別プレフィックス）
   c. used_bytes と実際の合計を比較
   d. 差異が 1MB 以上の場合:
      - used_bytes を実際の値に修正
      - storage_usage_logs に DRIFT_CORRECTION を記録
      - アプリケーションログに WARNING を出力
> **Phase 5 以降**: Phase 5-c 完了後は、`storage_subscriptions` 全件を走査してスコープ別プレフィックス
> `{feature}/{scopeType}/{scopeId}/` でリストし、スコープ単位での実測値と `used_bytes` を突合する。
> Phase 5-a〜5-b の移行期間中は旧パス（トップレベル）と新パス（スコープ別）の両方を集計して合算する。

4. 差異が頻発するスコープがあれば、アプリ層のバグ（削除時の減算漏れ等）を調査
5. Class A オペレーション課金を抑えるため、ListObjectsV2 のページングサイズは
   最大（1000 件/ページ）に設定し、バッチは週次を厳守する
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

| 機能 | ドキュメント | 対象 API | 対象メディア | スコープ帰属 | feature_type |
|------|------------|---------|-----------|------------|---|
| F03.14 スケジュールメディア | F03.14_schedule_media.md | プリサイン PUT・Multipart Upload | 画像・動画（機能別上限なし。単発 PUT 100MB / Multipart 5TB の技術下限のみ）| schedules の scope_type に従う | `SCHEDULE_MEDIA` |
| **F03.15 個人時間割メモ添付** | F03.15_personal_timetable.md | プリサイン PUT・confirm | 画像・PDF（jpeg/png/webp/heic/pdf、5MB×5枚）| **PERSONAL（投稿者本人）** | `PERSONAL_TIMETABLE_NOTES` |
| F04.1 タイムライン | F04.1_timeline.md | 画像投稿、動画ファイル投稿（VIDEO_FILE）| 画像・動画（機能別上限なし。単発 PUT 100MB / Multipart 5TB の技術下限のみ）| scope_type に従う | `TIMELINE` |
| F04.2 チャット | F04.2_chat.md | ファイル添付（画像・動画・ドキュメント）| 画像・ドキュメント・動画（**UX ガード上限 500MB**。大容量は F05.5 へ誘導）| チャットルームの scope_type に従う。DIRECT / GROUP_DM は送信者の個人クォータ | `CHAT` |
| F05.1 掲示板 | F05.1_bulletin_board.md | 添付ファイル | 画像・ドキュメント | scope_type に従う | `BULLETIN` |
| F05.2 回覧板 | F05.2_circular.md | 添付ファイル追加 | 画像・ドキュメント | scope_type に従う | `CIRCULATION` |
| F05.5 ファイル共有 | F05.5_file_sharing.md | ファイルアップロード・バージョン追加・Multipart Upload | 全種類（単発 PUT 100MB / Multipart 5TB の技術下限のみ。機能別上限なし）| scope_type に従う（**既存の `team_storage_subscriptions` を `storage_subscriptions` に統合**） | `FILE_SHARING` |
| F06.1 CMS/ブログ | F06.1_cms_blog.md | 画像・動画アップロード（blog_media_uploads）| 画像・動画（**UX ガード上限 1GB**。記事読み込み UX 観点で残す）| scope_type に従う | `CMS` |
| F06.2 メンバー紹介 | F06.2_member_gallery.md | 写真・動画アップロード（photos.media_type）| 写真・動画（機能別上限なし。単発 PUT 100MB / Multipart 5TB の技術下限のみ）| scope_type に従う | `GALLERY` |
| F01.6 プロフィールメディア | F01.6_profile_media.md | アイコン・バナー画像 | 画像のみ（数MB レベル） | **クォータ対象外**（UX 観点で「アイコン変更で容量警告」を避けるため、Slack/Discord 流の運用）。R2 へのアップロードは行うが `checkQuota` / `recordUpload` を呼ばない | — |

> **機能別上限について**: チーム・組織・個人の容量枠で課金するモデルのため、機能ごとのサイズ上限は設けない（ユーザーは容量枠の範囲内なら自由にアップロードできる）。ただし F04.2 チャット 500MB / F06.1 ブログ 1GB は **容量課金上限ではなく UX 観点のガード** として残す（大容量ファイルは F05.5 へ誘導する方針）。詳細は本ドキュメント冒頭「機能別ファイルサイズ上限は設けない（方針）」セクションを参照。

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

### Phase 4: 機能別統合（feature_type 拡張）
```
V14.010__extend_storage_usage_logs_feature_type.sql  -- feature_type に PERSONAL_TIMETABLE_NOTES / SCHEDULE_MEDIA を追加
```
> 既存 `storage_usage_logs.feature_type VARCHAR(30)` は ENUM ではなく文字列のため DDL 変更は不要。本マイグレーションはコメント更新と既存データ整合性チェックのみ（NO-OP に近い）。アプリ層の Java enum / 定数クラス更新が本体

### Phase 5: F05.5 移行
```
V5.050__migrate_team_storage_subscriptions.sql       -- F05.5 の既存データを移行（team_storage_subscriptions → storage_subscriptions）
```

**マイグレーション上の注意点**
- V3.023 のデフォルトプラン初期データは暫定値（Phase 8 の課金設計確定時に見直し）。Phase 3 時点では無料プランのみ seed
- V3.020〜V3.022 は Phase 3 の共通基盤として、認証テーブル（Phase 1）の後に実行
- V14.010 は Phase 4-α 着手時に併せて適用（番号は実装着手時に最新空きを再確認）
- V5.050 は F05.5 実装時に既存の `team_storage_subscriptions` からデータを移行し、旧テーブルをリネーム（`_deprecated` suffix）。一定期間後に DROP
- Phase 4 以降の各機能実装時に、アップロード処理へ `StorageQuotaService.checkQuota()` を組み込む（DB マイグレーションは V14.010 のみ、それ以外はアプリ層）

---

## 10. 未解決事項

- [ ] **デフォルト無料枠の具体的な容量** → Phase 8 課金設計時に決定。暫定値: 組織 50GB / チーム 5GB / 個人 1GB
- [ ] **超過課金の GB 単価** → Phase 8 で Stripe 連携時に決定
- [ ] **プランのダウングレード時の挙動** → 既存ファイルが新プランの上限を超過している場合の猶予期間・通知フロー。提案: 即時ダウングレード可能だが超過状態では新規アップロード不可。ファイル削除は強制しない
- [ ] **チャット DM の帰属** → 送信者 vs 受信者どちらの個人クォータを消費するか。提案: 送信者（アップロードした人が責任を持つ）
- [ ] **組織解散・チーム解散時のストレージ解放** → R2 ファイルの物理削除タイミング。提案: 30日の猶予期間後にバッチで物理削除

---

## 11. Phase 4 機能別統合ロードマップ（2026-05-04 追補）

### 背景
Phase 3 で基盤（DB 3 テーブル + `StorageQuotaService.checkQuota / recordUpload / recordDeletion`）は完備済み。
しかし Phase 4 以降の各機能は **大半が `StorageQuotaService` に未統合**。F03.15 は独自に 100MB 直書きクォータで稼働中。
本節で各機能の段階的接続計画を確定する。**Phase ごとに足軽 1 PR の粒度で進め、レビューしやすさを保つ。**

### 統合対象の現状サマリ（2026-05-04 偵察結果）

| 機能 | 現状 | R2 prefix | 必要対応 |
|---|---|---|---|
| F03.15 timetable-notes | ❌ 100MB 直書き | `user/{userId}/timetable-notes/` | 直書き削除 + checkQuota 統合（**最優先**） |
| F04.2 chat | ❌ 未統合 | `chat/{uuid}/` | checkQuota 統合 + UX ガード 500MB 維持 |
| F03.14 schedule-media | 🟢 統合済み | `schedules/{scheduleId}/` | Phase 4-γ 完了（2026-05-04）|
| F04.1 timeline | 🟢 統合済み | `timeline/` | Phase 4-γ 完了（2026-05-04）|
| F06.1 cms/blog | 🟢 統合済み | `blog/` | Phase 4-δ 完了（2026-05-04）UX ガード 1GB 維持 |
| F06.2 gallery | 🟢 統合済み | `gallery/` | Phase 4-δ 完了（2026-05-04）|
| F05.5 file-sharing | 🟢 統合済み | `files/` | Phase 4-ε 完了（2026-05-04）。`team_storage_subscriptions` は存在せず V5.050 不要 |
| F01.6 profile-media | ❌ 未統合 | `{scope}/{id}/{role}/` | **クォータ対象外**（数MBのアイコン・バナーは UX 観点で対象外） |

### Phase 分割

| Phase | スコープ | 対象 | 想定 PR 数 | 備考 |
|---|---|---|---|---|
| **4-α** | 救出・最優先 | F03.15 timetable-notes 直書き → 統合 | 1 | feature_type 追加（V14.010）含む |
| **4-β** | 高頻度 | F04.2 chat | 1 | UX ガード 500MB 維持。R2 オペレーション課金に注意（多数小ファイル） |
| **4-γ** | 大容量メディア | F03.14 schedule-media + F04.1 timeline | 2 | 🟢 完了（2026-05-04）Multipart Upload 経路の checkQuota 組み込み完了 |
| **4-δ** | 中頻度 | F06.1 cms/blog + F06.2 gallery | 2 | 🟢 完了（2026-05-04）UX ガード 1GB 維持（cms）|
| **4-ε** | F05.5 統合 | F05.5 ファイル共有を `StorageQuotaService` に統合 | 1 | 🟢 完了（2026-05-04）`team_storage_subscriptions` は存在せず V5.050 不要と判明。`SharedFileQuotaService` 新規追加。`SharedFileService` / `SharedFileVersionService` 改修 |
| **4-ζ** | 検証バッチ | ドリフト検出を全 prefix 走査に更新 + feature_type 集計確定 | 1 | 🟢 完了（2026-05-04）Phase 4-α〜δ 完了後に実施 |

**本節の対象**: 4-α / 4-β / 4-γ×2 / 4-δ×2 / **4-ε** / 4-ζ の **計 8 PR**。

> 4-α が最優先（既に直書きクォータが本番稼働中のため）。それ以降の β〜δ は順序自由（並行 PR 可）。4-ζ は α〜δ 完了後に着手。

### Phase 4-α 救出手順（F03.15 timetable-notes）

1. **DDL**: `V14.010__extend_storage_usage_logs_feature_type.sql`
   - `feature_type` は VARCHAR(30) のため DDL 変更不要。コメント更新と既存値の整合性チェック SQL のみ
2. **Java 定数追加**: 既存の `feature_type` 定数クラス（または enum）に `PERSONAL_TIMETABLE_NOTES`、`SCHEDULE_MEDIA` を追加
3. **`TimetableSlotUserNoteAttachmentService` の改修**:
   - `USER_QUOTA_BYTES = 100MB` 直書き定数を削除
   - `presign` メソッド冒頭で `storageQuotaService.checkQuota(PERSONAL, userId, fileSize)` を呼ぶ。超過時は 409 Conflict
   - `confirm` の R2 検証成功直後に `storageQuotaService.recordUpload(PERSONAL, userId, fileSize, PERSONAL_TIMETABLE_NOTES, "timetable_slot_user_note_attachments", attachmentId, currentUser.id)`
   - `delete`（論理削除時）に `storageQuotaService.recordDeletion(PERSONAL, userId, fileSize, PERSONAL_TIMETABLE_NOTES, ...)`
4. **テスト更新**: `TimetableSlotUserNoteAttachmentServiceTest` に `@Mock StorageQuotaService` を追加し、`given(storageQuotaService.checkQuota(...))` のスタブ・`verify(storageQuotaService).recordUpload(...)` の検証を追加
5. **設計書更新**: `docs/features/F03.15_personal_timetable.md` §3「上限値（運用ガード）」と §5.7 添付ファイル管理の「クォータ」記述を「F13 統合 (`StorageQuotaService`) で管理」に書き換え。100MB 直書きの記載を削除
6. **メモリ更新**: `project_f0315_personal_timetable_complete.md` の「残課題」を消化済みに更新

### Phase 4-β〜δ の共通パターン

各 Phase で同じ流れを踏む：
1. 機能の R2 アップロード経路の特定（presign 発行箇所 / multipart 確定箇所 / delete 箇所）
2. `checkQuota` を presign 直前に組み込み（superscope: TEAM/ORG/PERSONAL は機能のスコープ判定ロジックに従う）
3. `recordUpload` を確定直後に組み込み
4. `recordDeletion` を削除確定時に組み込み
5. 既存テストに `@Mock StorageQuotaService` を追加
6. 既存統合テストで checkQuota 失敗時 409、成功時 used_bytes 加算を end-to-end で検証
7. 機能側の設計書に「F13 クォータ統合済み」を追記

### Phase 4-ζ ドリフトバッチ強化（🟢 完了 2026-05-04）

**実装概要**: `StorageDriftDetectionBatchService` を新規作成。Phase 3 時点でドリフト検出バッチは未実装だったため、新規実装として全プレフィックスを走査するバッチを構築した。

**走査プレフィックスマッピング（`FEATURE_PREFIX_MAP` に登録済み）**:

| feature_type | R2 プレフィックス | 追加フェーズ |
|---|---|---|
| `TIMELINE` | `timeline/` | Phase 3 設計済み |
| `GALLERY` | `gallery/` | Phase 3 設計済み |
| `FILE_SHARING` | `files/` | Phase 3 設計済み |
| `CHAT` | `chat/` | Phase 3 設計済み |
| `CMS` | `blog/` | Phase 3 設計済み |
| `CIRCULATION` | `circulation/` | Phase 3 設計済み |
| `BULLETIN` | `bulletin/` | Phase 3 設計済み |
| `PERSONAL_TIMETABLE_NOTES` | `user/` | **Phase 4-α 追加** |
| `SCHEDULE_MEDIA` | `schedules/` | **Phase 4-α 追加** |

**仕様**:
- 毎週日曜日深夜 2:00 に `@Scheduled(cron = "0 0 2 * * SUN")` で実行
- R2 の `ListObjectsV2` でページングサイズ最大（1000 件/ページ）で走査し Class A 課金を抑制
- `thumbnails/` / `tmp/` プレフィックスは除外（自動生成物・一時ファイルはカウント対象外）
- 差異が 1MB 以上の場合: `storage_subscriptions.used_bytes` を実測値に補正 + `DRIFT_CORRECTION` ログを `storage_usage_logs` に挿入
- `StorageDriftDetectionBatchServiceTest` で 14 件のユニットテストを追加（全 feature_type のマッピング検証・除外ロジック・ドリフト修正フロー）

**Phase 5 で対応**: スコープ別プレフィックス走査は §12 Phase 5 で対応済み（設計完了）。

### 完了基準（Phase 4 全体）

- 全アップロード機能で `StorageQuotaService.checkQuota` が呼ばれる
- 直書きクォータ定数（`USER_QUOTA_BYTES = 100MB` 等）がコードベースから消滅
- ドリフト検出バッチが全 R2 prefix を走査
- `feature_type` enum が現実の機能リストと一致
- `GET /users/me/storage` 等で feature_type 別の使用量内訳が正確に返る

### Phase 8 への接続性

Phase 4 完了時点で「全機能のクォータ計上が `storage_subscriptions.used_bytes` に集約されている」状態を担保。
Phase 8 着手時は **基盤 + 計上が完備されている前提で** 課金 API（Stripe 連携・超過課金・SYSTEM_ADMIN 管理画面）を組むだけで済む。Phase 4 の遅れは Phase 8 の前提を崩すため、Phase 4-α〜δ は早期完了が望ましい。

---

## 12. Phase 5: R2 スコープ別パス命名規則変更・ドリフトバッチ精度向上

### 背景と課題

Phase 4-ζ で実装した `StorageDriftDetectionBatchService` はトップレベルプレフィックス（`timeline/` / `gallery/` など）単位でしか R2 を走査できない。このため **全スコープ横断の合計バイト数** しか取得できず、`storage_subscriptions` の各スコープ（チームA / チームB / 組織）の `used_bytes` と実測値を 1:1 で照合できない。

Phase 8 でチーム・組織ごとの GB 課金を実施するには、ドリフト検出の精度がスコープ単位で保証されている必要がある。Phase 5 では以下の 3 段階でこれを解決する。

### 現状のR2パスとスコープ埋め込み状況

| feature_type | 現在のパスパターン | スコープ埋め込み状態 |
|---|---|---|
| TIMELINE | `timeline/{TEAM\|ORGANIZATION\|PERSONAL}/{scopeId}/...` | ✅ 埋め込み済み |
| GALLERY | `gallery/{TEAM\|ORGANIZATION}/{scopeId}/...` | ✅ 埋め込み済み |
| CMS | `blog/{TEAM\|ORGANIZATION\|PERSONAL}/{scopeId}/...` | ✅ 埋め込み済み |
| PERSONAL_TIMETABLE_NOTES | `user/{userId}/timetable-notes/{uuid}.ext` | ⚠️ PERSONAL 固定だが "PERSONAL" セグメント欠落 |
| FILE_SHARING | `files/{uuid}.ext` | ❌ スコープなし |
| CHAT | `chat/{uuid}/{filename}` | ❌ スコープなし |
| CIRCULATION | `circulation/{documentId}/{uuid}` | ❌ スコープなし |
| SCHEDULE_MEDIA | `schedules/{scheduleId}/{uuid}.ext` | ❌ scheduleId のみ（スコープなし） |
| BULLETIN | 未実装 | ❌ |

### 新統一パス命名規則

```
{feature}/{scopeType}/{scopeId}/{context}/{uuid}.{ext}
```

- `scopeType`: 大文字固定 — `TEAM` / `ORGANIZATION` / `PERSONAL`
- `scopeId`: team_id / organization_id / user_id（数値）
- `context`: feature 固有のサブパス（例: `album-{albumId}` / `timetable-notes` / `tmp`）。不要なら省略可

**機能別の新旧パス対照表**

| feature_type | 旧パス | 新パス | 変更有無 |
|---|---|---|---|
| TIMELINE | `timeline/{TEAM\|ORGANIZATION\|PERSONAL}/{scopeId}/...` | 変更なし | ─ |
| GALLERY | `gallery/{TEAM\|ORGANIZATION}/{scopeId}/...` | 変更なし | ─ |
| CMS | `blog/{TEAM\|ORGANIZATION\|PERSONAL}/{scopeId}/...` | 変更なし | ─ |
| PERSONAL_TIMETABLE_NOTES | `user/{userId}/timetable-notes/` | `user/PERSONAL/{userId}/timetable-notes/` | ⚠️ PERSONAL セグメント追加 |
| FILE_SHARING | `files/{uuid}.ext` | `files/{scopeType}/{scopeId}/{uuid}.ext` | 🔴 変更 |
| CHAT | `chat/{uuid}/{filename}` | `chat/{scopeType}/{scopeId}/{uuid}/{filename}` | 🔴 変更 |
| CIRCULATION | `circulation/{documentId}/{uuid}` | `circulation/{scopeType}/{scopeId}/{documentId}/{uuid}` | 🔴 変更 |
| SCHEDULE_MEDIA | `schedules/{scheduleId}/{uuid}.ext` | `schedules/{scopeType}/{scopeId}/{scheduleId}/{uuid}.ext` | 🔴 変更 |
| BULLETIN | 未実装 | `bulletin/{scopeType}/{scopeId}/{uuid}.ext` | 🆕 新規 |

### 実装サブフェーズ

**Phase 5-a: 新規アップロードに新パス適用**

- **目的**: 以降の新規アップロードがスコープ付きパスに書き込まれるようにする。既存ファイルは旧パスのまま（破壊的変更なし）
- **対象クラス**（以下を修正）:
  - `ChatUploadController`: `"chat/" + UUID` → `"chat/" + scopeType + "/" + scopeId + "/" + UUID`
  - `SharedFileService`: presign API をサーバー生成に変更（OQ-4 決定）。`"files/" + UUID` → `"files/" + scopeType + "/" + scopeId + "/" + UUID`。フロントエンドは返却された `fileKey` をそのまま使う
  - `CirculationAttachmentService`: `"circulation/{documentId}/{uuid}"` → `"circulation/{scopeType}/{scopeId}/{documentId}/{uuid}"`
  - `ScheduleMediaService`: `"schedules/{scheduleId}/{uuid}"` → `"schedules/{scopeType}/{scopeId}/{scheduleId}/{uuid}"`
  - `TimetableSlotUserNoteAttachmentService`: `"user/{userId}/timetable-notes/"` → `"user/PERSONAL/{userId}/timetable-notes/"`
  - `BulletinAttachmentService`（未実装）: 実装時から `"bulletin/{scopeType}/{scopeId}/{uuid}"` で作成

**Phase 5-b: 既存ファイルの移行バッチ（Phase 5-a 直後に実施）**

- **目的**: 旧パスのファイルを新パスに移行し、ドリフトバッチが全ファイルを正確に計測できるようにする
- **新規クラス**: `StoragePathMigrationBatchService`
- **処理フロー**:
  1. `storage_usage_logs` の `referenceType` + `referenceId` から各機能テーブルの `file_key` を取得
  2. `file_key` が旧パスパターンに一致するレコードを対象にする
  3. R2 `CopyObject`（S3互換）で同一バケット内に旧パス → 新パスのコピー
  4. コピー成功後、各機能テーブルの `file_key` カラムを新パスに UPDATE
  5. 旧オブジェクトを R2 から削除（ただし削除前に 30 日間保持後、R2 ライフサイクルルールで自動削除を推奨）
  6. 失敗行は `storage_migration_errors` テーブルに記録してスキップ（再試行可能）
- **`R2StorageService` への追加**: `copyObject(String srcKey, String destKey): void`（現在未実装）
- **Flyway**: `V14.020__create_storage_migration_errors_table.sql`（移行エラー記録テーブル）
- **監視 API**: `GET /api/v1/system-admin/storage-migration-status`（移行進捗の可視化、Phase 5 スコープ内）

`storage_migration_errors` テーブル定義:

| カラム名 | 型 | NULL | 説明 |
|---------|---|------|------|
| `id` | BIGINT UNSIGNED | NO | PK |
| `reference_type` | VARCHAR(50) | NO | 対象テーブル名（例: `shared_files`） |
| `reference_id` | BIGINT UNSIGNED | NO | 対象レコード ID |
| `old_file_key` | VARCHAR(1000) | NO | 移行前の R2 キー |
| `new_file_key` | VARCHAR(1000) | NO | 移行先の R2 キー |
| `error_message` | TEXT | YES | エラー内容 |
| `retry_count` | INT | NO | リトライ回数 |
| `created_at` | DATETIME | NO | エラー発生日時 |
| `resolved_at` | DATETIME | YES | 解決日時（手動マーク用） |

**Phase 5-c: ドリフトバッチをスコープ別走査に更新**

- **前提**: Phase 5-b 完了後に実施（全ファイルが新パスに移行済みであること）
- **変更内容**: `StorageDriftDetectionBatchService` の `FEATURE_PREFIX_MAP`（トップレベルプレフィックス）を廃止
- **新ロジック**: `storage_subscriptions` 全件を走査し、各スコープに対して `{feature}/{scopeType}/{scopeId}/` プレフィックスで R2 を `ListObjectsV2` して実測バイト数を集計
- **移行期モード（Phase 5-a〜5-b 間）**: 旧パス集計 + 新パス集計を両方実行して合算するフラグを追加（`migrationModeEnabled: boolean`）

### セキュリティ考慮事項

- **旧パスの Presigned URL の有効期限**: Presigned URL の有効期限は 15 分。移行バッチ実行前に払い出された URL は期限切れ後にアクセス不可になるため、15 分以上経過したファイルのみを移行対象とする（または移行後 30 日間は旧パスを R2 に残す）
- **CopyObject 権限スコープ**: 同一バケット内のみ許可。クロスバケット CopyObject は禁止
- **移行バッチの actorId**: バッチ操作のため `actorId = NULL` で `storage_usage_logs` に記録
- **並行書き込みレースコンディション**: 移行バッチは旧パスのみ対象。新規アップロードは新パスに書き込まれるため競合なし

### Class A オペレーション課金の試算

CopyObject は Class A オペレーション（$4.50 / 百万リクエスト）。
- 仮に全ファイル数が 100 万件の場合: $4.50 の 1 回コスト
- 旧パスの削除は Class A（`DeleteObject`）: 同額
- 合計試算（100 万ファイル）: 約 $9.00 の 1 回限りのコスト
- 受容可能（スコープ別 GB 課金の正確性確保のほうが重要）

### Flyway マイグレーション

```
V14.020__create_storage_migration_errors_table.sql  -- 移行エラー記録テーブル
```

### 未解決問題（全件決着）

| OQ | 内容 | 決定 |
|---|---|---|
| OQ-1 | `user/` プレフィックスの命名統一 | `user/PERSONAL/{userId}/timetable-notes/` に変更（PERSONAL セグメント追加で一貫性確保） |
| OQ-2 | Phase 5-b の実施タイミング | Phase 5-a 直後に実施（マスター裁可） |
| OQ-3 | `bulletin/` の扱い（未実装機能） | Phase 5-a に含め、実装時から新パスを使う |
| OQ-4 | F05.5 fileKey 生成方法 | サーバー生成に変更（マスター裁可）。presign API がスコープ付き fileKey を生成して返す。フロントエンドは返却された fileKey をそのまま使う |
| OQ-5 | SharedFolderEntity スコープ情報 | ✅ 解決済み（scopeType / teamId / organizationId / userId フィールドあり） |
| OQ-6 | `user/` プレフィックス競合 | ✅ 解決済み（アバター等は `user/` を使っていない） |
| OQ-7 | CopyObject の Class A 課金コスト | 受容する（上記試算参照） |
| OQ-8 | ロールバック戦略 | 旧パスのオブジェクトを 30 日間保持後に R2 ライフサイクルルールで自動削除 |

### 完了基準

- [ ] Phase 5-a: 新規アップロードが全機能でスコープ付きパス（`{feature}/{scopeType}/{scopeId}/...`）に書き込まれる
- [ ] Phase 5-b: 移行バッチが完了し、旧パスのファイルが新パスに移行されている。`storage_migration_errors` の未解決件数がゼロ
- [ ] Phase 5-c: `StorageDriftDetectionBatchService` が `storage_subscriptions` 全件をスコープ別に走査し、`used_bytes` との差異が 1MB 未満に収束している
- [ ] マルチスコープ環境（チームA / チームB が同じ機能を使用）でスコープ別の使用量が正確に分離されている

---

## 13. 変更履歴

| 日付 | 変更内容 |
|------|---------|
| 2026-03-15 | 初版作成: 統合ストレージクォータの横断設計。独立プール型（組織/チーム/個人）。DB 3テーブル（storage_plans / storage_subscriptions / storage_usage_logs）。API 11本。クォータチェック共通フロー。SYSTEM_ADMIN によるプラン管理。80/90/100% の3段階通知。週次ドリフト検出バッチ。F05.5 既存テーブルからの移行計画 |
| 2026-03-15 | 2段階実装フェーズを明記。Phase 3（基盤: DB・StorageQuotaService・ハードブロック・通知・使用状況API 4本・バッチ2本）+ Phase 8（課金: SYSTEM_ADMIN管理画面・プラン購入UI・Stripe連携・超過課金・API 7本）。Flyway を V3.020〜V3.023（基盤）+ V5.050（F05.5移行）に再編。エンドポイント一覧に Phase 列を追加 |
| 2026-04-11 | ストレージ基盤を AWS S3 → Cloudflare R2 に全面差し替え。クォータ計上ロジック（GB 単位・組織/チーム/個人の独立プール）はそのまま維持。動画ファイル（F04.1 VIDEO_FILE、F06.2 動画、F06.1 ブログ動画）もクォータ計上対象として明記。R2 のエグレス料金ゼロに伴い、視聴回数による追加課金は行わず容量ベースの計量のみに統一。ドリフト検出バッチを R2 ListObjectsV2 ベースに更新。Lambda 生成物 → Cloudflare Workers / Cloudflare Images / Cloudflare Stream 生成物に呼称変更（カウント除外扱いは同じ）。動画アップロード時の `StorageQuotaService` 呼び出し順序を明示 |
| 2026-04-11 | R2 移行 第2弾 — 上限撤廃・単一バケット・ハイブリッドサムネイル確定: (1) **機能別ファイルサイズ上限を撤廃**（チーム課金モデルと矛盾するため）。容量枠の範囲内なら自由にアップロード可能 (2) 技術的下限のみ残す: 単発 PUT 100MB / Multipart 5TB (3) UX ガードとして F04.2 チャット 500MB（大容量は F05.5 へ誘導）/ F06.1 ブログ 1GB（記事読み込み UX 観点）を維持。容量課金上限とは別軸 (4) **R2 バケット構成を `mannschaft-storage` 単一バケット + プレフィックス分割に統一**（機能別バケットは採用しない）(5) **サムネイル生成をハイブリッド方式に確定**: 動画 5 分以下は Workers + ffmpeg.wasm / 5 分超はクライアント側 Canvas 抽出 (6) Cloudflare Stream は採用しない（R2 と別課金体系で割高）(7) Cloudflare Images は画像最適化用途（F06.1 ブログ・F06.2 ギャラリー）で採用 (8) Class A/B オペレーション課金の運用メモを追記 (9) UX ガード: アップロード前の使用率プレビュー・容量超過時の 409 Conflict・技術下限超過時の 400・UX ガード超過時の 413 を明記 (10) ドリフト検出バッチを単一バケットのプレフィックス走査に更新 (11) 各機能影響テーブルの上限列を「機能別上限なし」または「UX ガードのみ」に修正 (12) `blog_image_uploads` → `blog_media_uploads` のテーブル名変更に追随 |
| 2026-05-04 | **Phase 4-γ 完了**: F03.14 `ScheduleMediaService`・F04.1 `TimelineVideoAttachmentService` / `TimelinePostService` を `StorageQuotaService` に統合。presign 前 `checkQuota`（超過 → 409 Conflict）・INSERT 後 `recordUpload`・削除後 `recordDeletion` を実装。スコープ解決ロジック（TEAM / ORGANIZATION / PERSONAL フォールバック）を各 Service に追加。単体テスト（`ScheduleMediaServiceTest` / `TimelineVideoAttachmentServiceTest` / `TimelinePostServiceTest`）を更新し F13 Phase 4-γ 統合検証を追加 |
| 2026-05-04 | **Phase 4 機能別統合ロードマップ追補**: F03.15 個人時間割の 100MB 直書きクォータが「F13 統合クォータに未接続」状態で稼働中であることが判明したため、各機能の段階的統合計画を §12 として新設。(1) §8 影響範囲表に F03.14 schedule-media / F03.15 timetable-notes / F01.6 profile-media の後発機能を追加（F01.6 は数MB レベルのアイコン・バナーのため **クォータ対象外** と明記）(2) feature_type 拡張: `PERSONAL_TIMETABLE_NOTES` / `SCHEDULE_MEDIA` を追加（PROFILE_MEDIA は対象外）(3) Phase 4 を α〜ζ の 6 段階に分割: α=F03.15 救出（最優先）/ β=F04.2 chat / γ=F03.14+F04.1 / δ=F06.1+F06.2 / ε=F05.5 統合（**別軍議**）/ ζ=ドリフトバッチ強化 (4) Phase ごとに足軽 1 PR の粒度で進める方針を確定 (5) Flyway V14.010（feature_type 拡張、実体は NO-OP に近い）を Phase 4-α 着手時に同梱 (6) ステータスを「Phase 3 基盤完了 / Phase 4 機能別統合 進行中 / Phase 8 課金 未着手」に更新 |
| 2026-05-04 | **Phase 4-δ 完了**: F06.1 `BlogMediaService`（CMS/ブログ）・F06.2 `GalleryMediaUploadService` / `PhotoService`（ギャラリー）を `StorageQuotaService` に統合。presign 前 `checkQuota`（CMS超過→CMS_023、ギャラリー超過→GALLERY_014）・presign/uploadPhotos 後 `recordUpload`・孤立メディア削除/deletePhoto 後 `recordDeletion` を実装。`CmsErrorCode` に `MEDIA_QUOTA_EXCEEDED`（CMS_023）、`GalleryErrorCode` に `STORAGE_QUOTA_EXCEEDED`（GALLERY_014）を追加。`MediaUploadUrlRequest` に `fileSize` フィールド追加（null 時はクォータチェックをスキップ・後方互換）。`BlogMediaService` に `ScopeResolution` record 追加（孤立メディアの s3Key からスコープ復元に使用）。単体テスト（`BlogMediaServiceTest` / `GalleryMediaUploadServiceTest` / `PhotoServiceTest`）を更新し F13 Phase 4-δ 統合検証を追加 |
| 2026-05-04 | **Phase 4-ζ 完了**: `StorageDriftDetectionBatchService` を新規実装。Phase 3 時点でドリフト検出バッチが未実装だったため、新規作成。毎週日曜日深夜 2:00 に `@Scheduled` で実行。R2 `ListObjectsV2`（ページングサイズ 1000）で全プレフィックスを走査。`FEATURE_PREFIX_MAP` に Phase 4-α 追加の `PERSONAL_TIMETABLE_NOTES`（`user/`）/ `SCHEDULE_MEDIA`（`schedules/`）を含む全 9 feature_type のマッピングを登録。差異 1MB 以上で `used_bytes` を自動補正 + `DRIFT_CORRECTION` ログを挿入。`thumbnails/` / `tmp/` を除外。`StorageDriftDetectionBatchServiceTest`（14 件）を追加。Phase 4（α〜ζ）の全 feature_type 走査が完了し、Phase 4 のドリフトバッチ要件を達成 |
| 2026-05-04 | **Phase 4-ε 完了**: F05.5 ファイル共有を `StorageQuotaService` に統合。調査の結果 `team_storage_subscriptions` テーブル/エンティティは存在せず（V5.050 は不要）、直接 `StorageQuotaService` に接続する実装とした。`SharedFileQuotaService`（新規）を追加し、`FileScopeType` から `StorageScopeType` へのスコープ解決（TEAM/ORGANIZATION/PERSONAL）を実装。`SharedFileService` の `createFile`・`deleteFile` にクォータ統合を追加（削除時の actorId 引数追加）。`SharedFileVersionService` の `createVersion` にクォータ統合を追加。`FileSharingErrorCode` に `STORAGE_QUOTA_EXCEEDED`（FILE_SHARING_016）を追加。`GlobalExceptionHandler` に 409 マッピング追加。単体テスト（`SharedFileQuotaServiceTest` 新規・`SharedFileServiceTest` 更新・`SharedFileVersionServiceTest` 更新・`SharedFileServiceAdditionalTest` 更新・`FileSharingControllerTest` 更新）を整備 |
| 2026-05-04 | **Phase 5 R2 スコープ別パス命名規則変更 設計完了**: R2 パスにスコープ情報を埋め込む新統一命名規則 `{feature}/{scopeType}/{scopeId}/{context}/{uuid}.{ext}` を確定。TIMELINE / GALLERY / CMS は既に適合済み。FILE_SHARING / CHAT / CIRCULATION / SCHEDULE_MEDIA / PERSONAL_TIMETABLE_NOTES / BULLETIN は新パターンへの変更対象。F05.5 fileKey 生成をサーバー側に移行（クライアント生成廃止）。3 段階実施計画: 5-a（新規アップロードのパス変更）→ 5-b（既存ファイル移行バッチ＋CopyObject 追加＋V14.020）→ 5-c（ドリフトバッチのスコープ別走査化）。全 8 OQ 決着済み。 |
