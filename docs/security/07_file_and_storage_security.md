# 07. ファイル・ストレージセキュリティ

> **ステータス**: 🟡 設計確定（実装未着手）
> **実装フェーズ**: Security Hardening Phase 3
> **最終更新**: 2026-06-02
> **関連ドキュメント**: [README](README.md), [05 インジェクション・入力検証](05_injection_and_input_validation.md), F05.5 ファイル共有, F06.2 ギャラリー, F05.1 掲示板添付

---

## 1. 概要

Mannschaft は Cloudflare R2（S3 互換）を使用した **Presigned URL 方式**でファイルのアップロード/ダウンロードを管理する。クライアントはバックエンドを経由せずに R2 へ直接ファイルを転送するため、このアーキテクチャ固有の攻撃面を整理し、統一的な防止策を定義する。

### 1.1 なぜ Presigned URL を使うか

- バックエンドの帯域幅・メモリ消費を削減（大容量ファイルでも BE に転送不要）
- Cloudflare R2 のネットワーク最適化（CDN エッジ近接でのアップロード）
- ストリーミング転送によるタイムアウトリスクの軽減

---

## 2. Presigned URL のライフサイクルと攻撃面

### 2.1 ライフサイクル

```
クライアント
  → BE: アップロード要求（ファイル種別・サイズ・スコープ）
  ← BE: Presigned PUT URL（有効期限 900 秒・TTL 上限強制）+ objectKey

クライアント
  → Cloudflare R2: ファイルを直接 PUT（Content-Type ヘッダーを付与）

クライアント
  → BE: アップロード完了通知（objectKey）

BE:
  1. R2 の実 Content-Type を HEAD で確認（クライアント送信値を信用しない）
  2. ファイルサイズを確認（クォータチェック）
  3. objectKey をDBに所有権登録（user_id / organization_id / scope）
```

### 2.2 攻撃面と対策

| 攻撃 | リスク | 対策 |
|---|---|---|
| Presigned URL の第三者共有（期限内なら誰でもアクセス可） | 情報漏洩 | Download URL の有効期限を最小化（閲覧用: 3600 秒以内）。再取得 API を設ける |
| アップロード後の Content-Type 偽装（クライアント送信値は偽装可能） | WebShell/マルウェア配布 | BE でアップロード完了通知受信後に R2 の実 Content-Type を HEAD で確認する |
| テナント間 URL 流用（IDOR 変形） | 他組織のファイルへの不正アクセス | objectKey にスコープ識別子を含める（`org-{id}/user-{id}/filename`）。アクセス前にスコープ一致確認 |
| ホットリンクによるストレージ帯域消費 | コスト増大 | Download Presigned URL はログイン済みユーザーのみ発行（公開ファイルを除く） |
| 大量アップロードによるストレージ枯渇 | 可用性低下 | StorageQuotaService で org/team/personal ごとに上限管理（既実装） |
| 悪意あるファイルのアップロード（WebShell 等） | サーバー侵害・XSS | MIME 検証ホワイトリスト適用（§3 参照）|
| Presigned URL の転送途中での漏洩 | 不正ファイル操作 | HTTPS 必須。URL に短い TTL を設定（Upload: 900 秒、Download: 3600 秒） |
| クライアントが任意の長い TTL を指定 | 意図より長い URL が発行される | BE 側で TTL 上限を強制（§2.3 参照） |

### 2.3 TTL 上限の強制（サーバー側必須実装）

クライアントから任意の TTL が指定されることを防ぐため、
サーバー側で以下の上限を強制すること:

| URL 種別 | 上限 TTL |
|---|---|
| アップロード用 Presigned URL | 900秒（15分） |
| ダウンロード用 Presigned URL | 3600秒（1時間） |
| Multipart Upload パート URL | 900秒（15分） |

```java
// 上限を超えるリクエストは上限値に丸める
long safeTtl = Math.min(requestedTtlSeconds, MAX_UPLOAD_TTL_SECONDS);
```

クライアントには `expiry_at`（UNIX タイムスタンプ）を返し、
有効期限切れ前に再取得するよう促すこと。

### 2.4 Presigned URL エンドポイントのキャッシング防止

アップロード URL / ダウンロード URL を返す API エンドポイントには
以下のレスポンスヘッダーを必ず付与すること:

```
Cache-Control: no-cache, no-store, must-revalidate, max-age=0
Pragma: no-cache
Expires: 0
```

これにより、ブラウザや Cloudflare CDN が期限切れ URL をキャッシュするリスクを防ぐ。

---

## 3. ファイル種別ホワイトリストと MIME 検証

### 3.0 実装上の制約（Presigned URL 方式）

Mannschaft は Cloudflare R2 への Presigned URL 直接アップロードを採用しているため、
バックエンドはファイルバイト列を受け取らず、**magic byte 検査は実施不可能**。

現在の防御策:
- ホワイトリスト（`FileTypeValidator`）による Content-Type 制限
- ブロックリストによる危険 MIME タイプの明示排除（SVG/HTML/スクリプト/XML 等）
- 全アップロードエンドポイントで `FileTypeValidator` を一元使用（`com.mannschaft.app.common.storage.FileTypeValidator`）

将来対応（本番前検討）:
- Cloudflare Workers によるアップロード後の MIME 検査
- R2 の Object lifecycle rule でウイルススキャン連携

### 3.1 全エンドポイント共通ルール

1. **Content-Type ヘッダーのみで判断しない**（クライアントによる偽装が可能）
2. サーバー側で **magic byte（ファイルシグネチャ）検証** を実施する（Apache Tika 等を使用）
   - ただし Presigned URL 方式では §3.0 の制約により実施不可。Cloudflare Worker による代替を検討する
3. 拡張子と MIME が一致しない場合は **415 Unsupported Media Type** を返す
4. ファイル名のサニタイズ: `../`（パストラバーサル）、制御文字、NULL バイトを除去する

```java
// Magic byte 検証の概念（Presigned URL 方式では適用不可）
public MediaType detectMediaType(InputStream fileStream) {
    // Apache Tika を使用してバイト列から MIME タイプを判定
    Tika tika = new Tika();
    return MediaType.parseMediaType(tika.detect(fileStream));
}
```

### 3.2 許可ファイル種別一覧

| カテゴリ | MIME | 拡張子 | 用途 | 上限サイズ |
|---|---|---|---|---|
| 画像 | image/jpeg, image/png, image/webp, image/gif, image/heic | .jpg/.png/.webp/.gif/.heic | プロフィール・ギャラリー・記事 | 10 MB |
| 動画 | video/mp4, video/webm, video/quicktime | .mp4/.webm/.mov | ハイライト動画 | 500 MB |
| 圧縮 | application/zip, application/gzip, application/x-tar | .zip/.gz/.tar | 一括エクスポート | 100 MB |
| PDF | application/pdf | .pdf | シフト表・契約書 | 20 MB |

> エンドポイント固有の制限（例: プロフィール画像は 5 MB 以内）は上記上限より厳しく設定してよい。

### 3.3 禁止ファイル種別

以下は**全エンドポイントで無条件に禁止**する:

| カテゴリ | 理由 |
|---|---|
| SVG（image/svg+xml） | XSS: `<script>` タグ・イベントハンドラを含むことができる。掲示板添付では既に除外済み（全エンドポイントで統一） |
| HTML（text/html） | XSS |
| JavaScript / TypeScript（text/javascript 等） | XSS・任意コード実行 |
| 実行ファイル（application/octet-stream の .exe/.sh/.bat/.py 等） | マルウェア配布 |
| PHP / JSP / ASP | サーバーサイドコード実行 |
| XML（application/xml）※ | XXE（XML External Entity）攻撃 |

> ※ XMLは用途によって許可する場合は XXE 対策を必須とし、外部エンティティ参照を無効化する。

---

## 4. ファイルアクセス制御（スコープ別）

R2 バケット自体は**プライベート設定**とし、直接アクセスを禁止する。アクセス制御は Presigned Download URL 発行時に BE で確認する。

| スコープ | アクセス条件 | objectKey パターン |
|---|---|---|
| ORGANIZATION | 同一 `organization_id` のメンバーのみ | `org-{orgId}/...` |
| TEAM | 同一 `team_id` のメンバーのみ | `team-{teamId}/...` |
| PERSONAL | 本人のみ（自分の `user_id` に一致） | `user-{userId}/...` |
| VILLAGE | 同一 village の参加者 + 公開設定時は全ログイン済みユーザー | `village-{villageId}/...` |
| TOURNAMENT | トーナメント参加チームのメンバー + 主催組織 ADMIN + SYSTEM_ADMIN | `tournament-{tournamentId}/...` |
| PUBLIC | 未ログインでもアクセス可（OGP 画像等） | `public/...` |

### 4.1 Presigned URL 発行時の認可チェック

```java
// Download URL 発行時のスコープ認可チェック（概念）
public String generateDownloadUrl(String objectKey, UserDetails currentUser) {
    FileMetadata metadata = fileMetadataRepo.findByObjectKey(objectKey)
        .orElseThrow(() -> new ResourceNotFoundException());  // 存在しない → 404

    // スコープに応じた認可チェック
    switch (metadata.getScope()) {
        case ORGANIZATION -> accessControlService.checkMembership(
            currentUser.getId(), metadata.getScopeId(), "ORGANIZATION");
        case TEAM -> accessControlService.checkMembership(
            currentUser.getId(), metadata.getScopeId(), "TEAM");
        case PERSONAL -> {
            if (!metadata.getOwnerId().equals(currentUser.getId())) {
                throw new AccessDeniedException();  // 403
            }
        }
        case PUBLIC -> { /* チェック不要 */ }
        // 他スコープも同様に追加
    }

    return r2Service.generatePresignedDownloadUrl(objectKey, Duration.ofHours(1));
}
```

---

## 5. objectKey の設計ルール

objectKey の設計はスコープ分離と IDOR 防止に直結する。

### 5.1 推奨フォーマット

```
{scope}-{scopeId}/{category}/{uuidv4}.{ext}

例:
org-123/profiles/550e8400-e29b-41d4-a716-446655440000.jpg
team-456/shifts/550e8400-e29b-41d4-a716-446655440001.pdf
user-789/personal/550e8400-e29b-41d4-a716-446655440002.png
public/og-images/550e8400-e29b-41d4-a716-446655440003.jpg
```

### 5.2 禁止事項

- ユーザーが指定したファイル名をそのまま objectKey に使用することを禁止する
- ユーザーの ID や個人情報をそのまま objectKey に含めることを禁止する（ハッシュ化するか UUID を使う）
- 連番 ID（`file-1.jpg`, `file-2.jpg`）による推測可能な objectKey を禁止する

---

## 6. 大容量ファイルの暗号化方針

`EncryptionService`（AES-256-GCM）は個人情報フィールド（氏名・生年月日等）の
小粒度データ向けに設計されており、大容量ファイルの暗号化には使用しないこと。

| ファイルサイズ | 暗号化方針 |
|---|---|
| 個人情報フィールド（< 1KB） | `EncryptionService` で AES-256-GCM |
| 画像（< 10MB） | R2 バケット全体を暗号化（Cloudflare 管理キー）で対応 |
| 動画（< 500MB） | Presigned PUT URL で直接 R2 へアップロード。バックエンドを経由しない。R2 バケット暗号化に委ねる |

動画ファイルをバックエンド経由でストリーム暗号化することは禁止する
（メモリ枯渇・タイムアウトのリスク）。

---

## 7. ストレージクォータと TOCTOU 対策

### 7.1 TOCTOU（Time of Check to Time of Use）脆弱性

クォータチェックと実アップロードの間に時間差があると、並行リクエストで合計クォータを超過できる。

```
攻撃シナリオ:
1. ユーザーA が同時に 100 本のアップロード要求を送信
2. 全てのリクエストで「残クォータ 10MB あり」と判定される
3. 全てのファイルがアップロードされ、合計は 100 * ファイルサイズに
```

### 7.2 対策

- アップロード完了通知受信後に **サイズを再確認**し、合計が超過していれば objectKey を削除する
- Valkey の `INCRBY` + `EXPIRE` で楽観的クォータカウントを行い、超過時はロールバックする
- 既実装の `StorageQuotaService` を**全アップロードエンドポイントで使用**すること（抜け漏れ禁止）

---

## 8. セキュリティインシデント対応

### 8.1 悪意あるファイルが発見された場合

1. 対象 objectKey の Presigned URL を即時無効化（R2 のプリセットポリシーを変更）
2. DB の `file_metadata` テーブルで `is_quarantined = true` にフラグ
3. 悪意あるファイルにアクセスしたユーザーの ID をログに記録
4. SYSTEM_ADMIN にアラート通知

### 8.2 バケットポリシーの定期確認

- R2 バケットが意図せずパブリック公開に変更されていないかを月次で確認する
- CORS 設定が Mannschaft のフロントエンドオリジンのみを許可していることを確認する

---

## 9. 変更履歴

| 日付 | 変更 |
|---|---|
| 2026-06-02 | 新規作成。Presigned URL のライフサイクル・攻撃面・MIME 検証・スコープ別アクセス制御・objectKey 設計ルール・TOCTOU 対策を定義 |
| 2026-06-02 | §2.3 TTL 上限の強制（サーバー側 `Math.min` 上限丸め・`expiry_at` 返却）を追加。§2.4 Presigned URL エンドポイントのキャッシング防止ヘッダーを追加。§6 大容量ファイルの暗号化方針を追加（個人情報フィールドは `EncryptionService`・画像/動画は R2 バケット暗号化・動画 BE 経由暗号化禁止） |
| 2026-06-03 | §3.0 追加: Presigned URL 方式の制約（magic byte 検査不可）と `FileTypeValidator` による一元管理を明記。§3.1 更新: magic byte 検証の Presigned URL 適用不可注釈を追加 |
