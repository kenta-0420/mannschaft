# 09. キー管理・ローテーション手順

> **ステータス**: 🟡 設計確定（手順整備未完了）
> **実装フェーズ**: Security Hardening Phase 3
> **最終更新**: 2026-06-02
> **関連ドキュメント**: [README](README.md), [08 インシデント対応](08_incident_response.md), F01.1 認証, F12.3 GDPR

---

## 1. 管理する秘密鍵の全リスト

| 環境変数名 | 用途 | アルゴリズム | 最小長 | 本番必須 |
|---|---|---|---|---|
| `MANNSCHAFT_JWT_SECRET` | JWT 署名（HMAC-SHA256） | HMAC-SHA256 | 256 bit | ✅ |
| `MANNSCHAFT_ENCRYPTION_KEY` | AES-256-GCM（birth_date 等の PII 暗号化） | AES-256 | 256 bit | ✅ |
| `MANNSCHAFT_HMAC_KEY` | ブラインドインデックス生成（検索用） | HMAC-SHA256 | 256 bit | ✅ |
| `MANNSCHAFT_INTERNAL_SIGNING_KEY` | 内部 API 署名（PDF 署名等） | HMAC-SHA256 | 256 bit | ✅ |
| `JOB_QR_SIGNING_SECRET` | 求人 QR コード署名 | HMAC-SHA256 | 256 bit | ✅ |
| `MANNSCHAFT_AD_UNSUBSCRIBE_SECRET` | 広告配信停止リンク署名 | HMAC-SHA256 | 256 bit | ✅ |
| `MANNSCHAFT_AD_OPEN_PIXEL_SECRET` | 広告開封ピクセル署名 | HMAC-SHA256 | 256 bit | ✅ |
| `INTERNAL_LOG_TOKEN` | `/api/internal/ssr-logs` 内部認証 | ランダムトークン | 128 bit 以上 | ✅ |
| `MANNSCHAFT_R2_ACCESS_KEY` | Cloudflare R2 アクセスキー | — | — | ✅ |
| `MANNSCHAFT_R2_SECRET_KEY` | Cloudflare R2 シークレットキー | — | — | ✅ |
| `MANNSCHAFT_COOKIE_SECURE` | Cookie の Secure 属性（boolean） | — | — | ✅ |
| `MANNSCHAFT_ALLOWED_ORIGINS` | CORS 許可オリジン | — | — | ✅ |
| `MANNSCHAFT_*_CLIENT_SECRET`（Google/LINE/Apple） | OAuth クライアントシークレット | — | — | OAuth 利用時 |

> このリストが唯一の正典（single source of truth）である。新規に秘密鍵を追加した場合は必ず本テーブルに追記すること（§6 参照）。

---

## 2. 通常ローテーション手順（計画的ローテーション）

### 2.1 ローテーション推奨頻度

| キー | 推奨頻度 | 理由 |
|---|---|---|
| `MANNSCHAFT_JWT_SECRET` | 年次（または侵害疑い時） | access token TTL 15分で自然失効するため即影響なし |
| `MANNSCHAFT_ENCRYPTION_KEY` | 2 年次（または侵害疑い時） | 再暗号化バッチの工数が大きいため |
| `MANNSCHAFT_HMAC_KEY` | 2 年次（または侵害疑い時） | ブラインドインデックスの再計算が必要なため |
| `JOB_QR_SIGNING_SECRET` | 年次 | QR コードの有効期限は短期のため影響は最小 |
| R2 アクセスキー | 年次 | クラウドストレージのセキュリティベストプラクティス |
| OAuth クライアントシークレット | プロバイダー推奨に従う | — |

### 2.2 JWT Secret のゼロダウンタイムローテーション（デュアルキー方式）

```
手順:
1. 新しいランダムキー JWT_SECRET_NEXT を生成（§4 参照）
2. 環境変数に JWT_SECRET_NEXT を追加（既存の JWT_SECRET はまだ有効）
3. JwtAuthenticationFilter を「現在のキーで検証失敗した場合に次のキーで再試行」するように改修
4. AuthTokenService の発行キーを JWT_SECRET_NEXT に切り替え
5. 既存 access token の最大 TTL（900 秒 = 15 分）が経過するまで待機
6. 旧キー（JWT_SECRET）の検証ロジックを削除
7. JWT_SECRET に JWT_SECRET_NEXT の値をコピー、JWT_SECRET_NEXT を削除
```

> デュアルキー方式により、ローテーション中も既存のログインセッションを維持できる。

### 2.3 AES-256-GCM キーのローテーション

AES キーのローテーションは PII 暗号化データ（`users.birth_date` 等）の**全件再暗号化**を伴う。

```
手順:
1. EncryptionService に旧キーと新キーを両方保持するよう改修
2. バックグラウンドバッチを実行:
   - 旧キーで復号 → 新キーで再暗号化（トランザクション内で処理）
   - バッチは 1000 件ずつ分割処理（メモリ・ロック対策）
3. 再暗号化完了を確認（全行の暗号化バージョンが更新済みであること）
4. 旧キーによる復号サポートを削除
5. 旧キーを環境変数から削除
```

> 再暗号化バッチの実行前に**必ずデータバックアップを取得**すること。

### 2.4 HMAC キー（ブラインドインデックス）のローテーション

ブラインドインデックスは検索用ハッシュであり、キー変更後は**インデックス値も再計算が必要**。

```
手順:
1. 新キーで全インデックス値を再計算するバッチを作成
2. 「古いインデックス値 OR 新しいインデックス値」で検索できる期間を設ける
3. 全行の再計算完了後、旧キーを削除
```

---

## 3. 緊急ローテーション手順（漏洩時）

秘密鍵が漏洩した、または漏洩した可能性がある場合の手順。

```
1. [即時] Valkey に全トークン無効化タイムスタンプをセット
   → 全ユーザーを即時ログアウト（§3.1）

2. [即時] 新しいランダムキーを生成
   openssl rand -base64 32

3. [5 分以内] 新キーで BE を再デプロイ（環境変数を更新してコンテナを再起動）

4. [状況に応じて] 暗号化データの再暗号化バッチを実行（§2.3）

5. [24 時間以内] インシデント記録と事後分析（docs/security/08_incident_response.md §8）

6. [72 時間以内] GDPR Article 33 通知が必要かを評価（08_incident_response.md §5）
```

### 3.1 全ユーザー即時ログアウト

```bash
# Valkey で全トークン無効化タイムスタンプをセット
redis-cli SET mannschaft:emergency_invalidation_at $(date +%s)

# またはアプリケーション経由:
# PATCH /api/v1/system-admin/auth/invalidate-all-tokens
```

> `JwtAuthenticationFilter` が `iat < emergency_invalidation_at` を確認するロジックの実装が必要（現時点は per-user タイムスタンプのみ実装済み）。

---

## 4. キー生成方法

### 4.1 コマンドラインでの安全なキー生成

```bash
# 256 bit（32 バイト）のランダムキー（Base64 エンコード）
openssl rand -base64 32

# または Python を使用
python3 -c "import secrets; print(secrets.token_urlsafe(32))"

# または Java でのキー生成
# SecureRandom.getInstanceStrong().generateSeed(32) をBase64エンコード
```

### 4.2 キーの品質確認

```bash
# 生成したキーのエントロピーを確認（256 bit = 32 バイト = Base64 44 文字）
echo -n "生成したキー" | base64 -d | wc -c  # → 32 と表示されること
```

---

## 5. シークレット管理ツール連携方針

| 環境 | 管理方式 | 備考 |
|---|---|---|
| 開発 | `.env` ファイル | `.gitignore` 対象。ダミー値可 |
| テスト（CI） | GitHub Actions Secrets | `${{ secrets.MANNSCHAFT_JWT_SECRET }}` として参照 |
| ステージング | AWS Secrets Manager または HashiCorp Vault（推奨） | 起動時に取得 |
| 本番 | AWS Secrets Manager または HashiCorp Vault（推奨） | 起動時に取得 |

### 5.1 Secrets Manager を使用する場合の設定例（Spring Boot）

```yaml
# application-prod.yml
mannschaft:
  jwt:
    secret: ${AWS_SECRET_MANNSCHAFT_JWT_SECRET}  # 環境変数経由で注入
```

```bash
# AWS Secrets Manager からの取得（起動スクリプト等で）
export MANNSCHAFT_JWT_SECRET=$(aws secretsmanager get-secret-value \
  --secret-id mannschaft/production/jwt-secret \
  --query SecretString --output text)
```

### 5.2 現在の状態（開発環境）

`application.yml` の `mannschaft.jwt.secret` 等は開発用ダミー値のみ設定する。`application-prod.yml` は以下のようにデフォルト値を持たず、未設定なら起動失敗（fail-fast）する。

```yaml
# application-prod.yml
mannschaft:
  jwt:
    secret: ${MANNSCHAFT_JWT_SECRET}  # デフォルト値なし → 未設定で起動失敗
```

---

## 6. 新規機能で秘密鍵が必要になった場合の命名規則

### 6.1 命名ルール

- プレフィックス: `MANNSCHAFT_`
- 用途を明示するサフィックス:
  - `_KEY`: 対称暗号鍵（AES 等）
  - `_SECRET`: HMAC/署名用シークレット
  - `_TOKEN`: 認証トークン

### 6.2 例

| 用途 | 環境変数名 |
|---|---|
| 決済署名 | `MANNSCHAFT_PAYMENT_SIGNING_KEY` |
| Webhook 署名 | `MANNSCHAFT_WEBHOOK_SECRET` |
| 新規 OAuth プロバイダー | `MANNSCHAFT_{PROVIDER}_CLIENT_SECRET` |

### 6.3 必須作業

新規に秘密鍵を追加する場合、以下を全て実施すること:

1. **本ファイル（09_key_management_and_rotation.md）の §1 テーブルに追記**
2. `docs/operations/PRODUCTION_SETUP.md` の環境変数一覧に追記
3. `.github/workflows/backend-ci.yml` または `frontend-ci.yml` の Secrets 設定を確認
4. `application-prod.yml` にデフォルト値なしの参照を追加

---

## 7. 秘密鍵の誤コミット時の対応

### 7.1 発見した場合の即時対応

```
1. [即時] 漏洩した秘密鍵の緊急ローテーション（§3 参照）
2. [即時] git の履歴から削除（git filter-branch または BFG Repo Cleaner）
3. [即時] GitHub の secret scanning アラートを確認
4. [24 時間以内] gitleaks または truffleHog で全履歴スキャン
5. [48 時間以内] インシデント記録
```

### 7.2 git 履歴からの削除

```bash
# BFG Repo Cleaner を使用（推奨）
java -jar bfg.jar --delete-files .env
git reflog expire --expire=now --all && git gc --prune=now --aggressive
git push --force

# または git filter-branch
git filter-branch --tree-filter 'rm -f .env' HEAD
```

> **注意**: 強制プッシュ前に全チームメンバーへ通知し、ローカルクローンの更新を促すこと。

---

## 8. 変更履歴

| 日付 | 変更 |
|---|---|
| 2026-06-02 | 新規作成。秘密鍵全リスト・ローテーション手順（JWT デュアルキー方式/AES/HMAC）・緊急ローテーション手順・キー生成方法・シークレット管理ツール連携・命名規則・誤コミット対応を定義 |
