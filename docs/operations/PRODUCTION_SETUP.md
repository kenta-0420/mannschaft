# 本番環境セットアップガイド

本番環境を新規構築 / 引き継ぐ際に必要な **環境変数・シークレット・設定ファイル** の一覧と手順をまとめる。
日々のリリース前確認は `PRODUCTION_DEPLOY_CHECKLIST.md` を参照。

---

## 設計の前提

Mannschaft はシークレットをソースコードに **一切ハードコードしない** 設計。

| ファイル | 用途 | リポジトリに含める？ |
|---|---|---|
| `backend/src/main/resources/application.yml` | 共通設定。デフォルト値はローカル開発向け | ✅ コミット対象 |
| `backend/src/main/resources/application-local.yml` | ローカル開発専用の値 | ✅ コミット対象（開発向けダミー値のみ） |
| `backend/src/main/resources/application-prod.yml` | 本番プロファイル。**全て `${...}` プレースホルダ** | ✅ コミット対象（実値はゼロ） |
| 本番の実値 | 環境変数 / Secrets Manager で注入 | ❌ リポジトリ外で管理 |

本番起動コマンド：

```bash
java -jar -Dspring.profiles.active=prod backend.jar
```

`application-prod.yml` のプレースホルダが解決できないと **起動失敗** するので、設定漏れは検知できる。

---

## 必要な環境変数一覧

### 必須（未設定なら起動失敗）

| 環境変数 | 用途 | 例 / 生成方法 |
|---|---|---|
| `SPRING_DATASOURCE_URL` | MySQL 接続 URL | `jdbc:mysql://prod-db.example.com:3306/mannschaft?useSSL=true&serverTimezone=Asia/Tokyo` |
| `SPRING_DATASOURCE_USERNAME` | MySQL ユーザー名 | `mannschaft_app` |
| `SPRING_DATASOURCE_PASSWORD` | MySQL パスワード | `openssl rand -base64 32` |
| `SPRING_REDIS_HOST` | Valkey/Redis ホスト | `prod-cache.example.com` |
| `MANNSCHAFT_JWT_SECRET` | JWT 署名鍵（256bit 以上必須） | `openssl rand -base64 64` |
| `JOB_QR_SIGNING_SECRET` | QR チェックイン署名鍵（F13.1） | `openssl rand -base64 64` |

### 任意（未設定でも起動するが、機能が無効化される）

| 環境変数 | 用途 | デフォルト |
|---|---|---|
| `SPRING_REDIS_PORT` | Redis ポート | `6379` |
| `SERVER_PORT` | アプリ Listen ポート | `8080` |
| `MANNSCHAFT_JWT_ACCESS_EXPIRATION` | アクセストークン有効期限（秒） | `900`（15分） |
| `MANNSCHAFT_JWT_REFRESH_EXPIRATION` | リフレッシュトークン有効期限（秒） | `604800`（7日） |
| `MANNSCHAFT_ENCRYPTION_KEY` | アプリ暗号化鍵（PII 等） | 未設定で機能 OFF |
| `MANNSCHAFT_HMAC_KEY` | HMAC 鍵 | 未設定で機能 OFF |
| `JOB_QR_SIGNING_KID` | QR 署名鍵 ID | `v1` |

#### Cloudflare R2（ストレージ）

| 環境変数 | 用途 |
|---|---|
| `MANNSCHAFT_R2_BUCKET` | R2 バケット名 |
| `MANNSCHAFT_R2_ENDPOINT` | `https://{accountId}.r2.cloudflarestorage.com` |
| `MANNSCHAFT_R2_ACCESS_KEY` | R2 アクセスキー |
| `MANNSCHAFT_R2_SECRET_KEY` | R2 シークレットキー |
| `MANNSCHAFT_R2_UPLOAD_TTL` | プリサイン URL 有効期限（秒、既定 600） |
| `MANNSCHAFT_R2_DOWNLOAD_TTL` | 同上（既定 3600） |
| `MANNSCHAFT_CDN_WORKERS_DOMAIN` | CDN Workers ドメイン |
| `MANNSCHAFT_CDN_ENABLED` | `true` で CDN 有効化 |

#### AWS SES（メール送信）

| 環境変数 | 用途 |
|---|---|
| `MANNSCHAFT_SES_REGION` | SES リージョン（既定 `ap-northeast-1`） |
| `MANNSCHAFT_SES_ENDPOINT` | カスタムエンドポイント（任意） |

#### WebAuthn

| 環境変数 | 用途 |
|---|---|
| `MANNSCHAFT_WEBAUTHN_ORIGIN` | RP origin（既定 `https://mannschaft.app`） |

#### OAuth — Google

| 環境変数 | 用途 |
|---|---|
| `MANNSCHAFT_GOOGLE_CLIENT_ID` | Google OAuth クライアント ID |
| `MANNSCHAFT_GOOGLE_CLIENT_SECRET` | 同シークレット |
| `MANNSCHAFT_GOOGLE_REDIRECT_URI` | コールバック URL |
| `MANNSCHAFT_GOOGLE_PLACES_API_KEY` | Places API キー |

#### OAuth — LINE

| 環境変数 | 用途 |
|---|---|
| `MANNSCHAFT_LINE_CLIENT_ID` | LINE Login チャネル ID |
| `MANNSCHAFT_LINE_CLIENT_SECRET` | チャネルシークレット |
| `MANNSCHAFT_LINE_REDIRECT_URI` | コールバック URL |

#### OAuth — Apple

| 環境変数 | 用途 |
|---|---|
| `MANNSCHAFT_APPLE_CLIENT_ID` | Apple Service ID |
| `MANNSCHAFT_APPLE_CLIENT_SECRET` | Apple Sign In シークレット（JWT） |
| `MANNSCHAFT_APPLE_REDIRECT_URI` | コールバック URL |

#### Stripe（決済）

| 環境変数 | 用途 |
|---|---|
| `MANNSCHAFT_STRIPE_SECRET_KEY` | Stripe シークレットキー（`sk_live_...`） |
| `MANNSCHAFT_STRIPE_CONNECT_RETURN_URL` | Connect 戻り URL |
| `MANNSCHAFT_STRIPE_CONNECT_REFRESH_URL` | Connect リフレッシュ URL |

---

## フロントエンド（Nuxt 3）

| 環境変数 | 用途 |
|---|---|
| `NUXT_PUBLIC_API_BASE` | バックエンド API のベース URL（例: `https://api.mannschaft.com`） |

`frontend/.env.example` をコピーして `.env` を作成（`.env` は `.gitignore` 済み）。

---

## ファイル作成手順

### 1. バックエンド `.env`（ローカルでの本番疎通テスト用）

`.env` は `.gitignore` 済み。以下のテンプレートをコピーして `backend/.env` を作る。

```bash
# ===== 必須 =====
SPRING_DATASOURCE_URL=jdbc:mysql://your-db-host:3306/mannschaft?useSSL=true&serverTimezone=Asia/Tokyo
SPRING_DATASOURCE_USERNAME=mannschaft_app
SPRING_DATASOURCE_PASSWORD=__REPLACE__
SPRING_REDIS_HOST=your-redis-host
MANNSCHAFT_JWT_SECRET=__REPLACE__
JOB_QR_SIGNING_SECRET=__REPLACE__

# ===== 任意（必要な機能だけ埋める） =====
# MANNSCHAFT_ENCRYPTION_KEY=
# MANNSCHAFT_HMAC_KEY=
# MANNSCHAFT_R2_BUCKET=
# MANNSCHAFT_R2_ENDPOINT=
# MANNSCHAFT_R2_ACCESS_KEY=
# MANNSCHAFT_R2_SECRET_KEY=
# MANNSCHAFT_GOOGLE_CLIENT_ID=
# MANNSCHAFT_GOOGLE_CLIENT_SECRET=
# MANNSCHAFT_LINE_CLIENT_ID=
# MANNSCHAFT_LINE_CLIENT_SECRET=
# MANNSCHAFT_APPLE_CLIENT_ID=
# MANNSCHAFT_APPLE_CLIENT_SECRET=
# MANNSCHAFT_STRIPE_SECRET_KEY=
```

**シークレット生成コマンド（Linux / macOS / WSL）**:

```bash
openssl rand -base64 64   # JWT_SECRET / JOB_QR_SIGNING_SECRET 用
openssl rand -base64 32   # DB パスワード用
```

### 2. 本番用 docker-compose（任意）

ローカルの `docker-compose.yml` は開発用。本番で Docker Compose を使うなら `docker-compose.prod.yml` を作る（`.gitignore` 済み）。

```yaml
# docker-compose.prod.yml — リポジトリにはコミットしない
services:
  backend:
    image: mannschaft-backend:${IMAGE_TAG}
    restart: unless-stopped
    environment:
      SPRING_PROFILES_ACTIVE: prod
      SPRING_DATASOURCE_URL: ${SPRING_DATASOURCE_URL}
      SPRING_DATASOURCE_USERNAME: ${SPRING_DATASOURCE_USERNAME}
      SPRING_DATASOURCE_PASSWORD: ${SPRING_DATASOURCE_PASSWORD}
      SPRING_REDIS_HOST: ${SPRING_REDIS_HOST}
      MANNSCHAFT_JWT_SECRET: ${MANNSCHAFT_JWT_SECRET}
      JOB_QR_SIGNING_SECRET: ${JOB_QR_SIGNING_SECRET}
      # ... 任意の env も同様に渡す
    ports:
      - "8080:8080"
```

起動:

```bash
docker compose --env-file /etc/mannschaft/prod.env -f docker-compose.prod.yml up -d
```

### 3. Kubernetes / AWS ECS の場合

シークレットは以下のいずれかで管理（**プレーンの ConfigMap / Task Definition に直書き禁止**）:

- **AWS Secrets Manager** + ECS の `secrets` フィールド
- **AWS Systems Manager Parameter Store**（SecureString）
- **Kubernetes Secret**（`kubectl create secret generic`）+ 可能なら External Secrets Operator
- **HashiCorp Vault**

---

## デプロイ前チェックリスト（このドキュメント単体での最低限）

- [ ] 必須 env 6種すべてが本番環境変数 / Secret に設定されている
- [ ] `MANNSCHAFT_JWT_SECRET` と `JOB_QR_SIGNING_SECRET` が **256bit 以上**（base64 で 44 文字以上）
- [ ] `SPRING_DATASOURCE_URL` が `useSSL=true` を含む
- [ ] OAuth リダイレクト URI が本番ドメインに一致している（Google / LINE / Apple すべての管理コンソール側も合わせて更新）
- [ ] R2 / Stripe / SES のキーが **本番用**（テスト用 `sk_test_...` 等を使っていない）
- [ ] `.env` ファイルがリポジトリにコミットされていない（`git ls-files | grep -E '\.env$'` で空であること）
- [ ] `application-prod.yml` に `${...}` 以外の値が混入していない（`grep -vE '\$\{|^\s*#|^\s*$' backend/src/main/resources/application-prod.yml` で env 値の直書きが出ないこと）

詳しいリリース手順は `PRODUCTION_DEPLOY_CHECKLIST.md` を参照。

---

## シークレットローテーション

漏洩時 / 定期ローテーション時の手順：

1. **JWT_SECRET 変更** → 全ユーザーが再ログインを要求される（短期は許容範囲）
2. **JOB_QR_SIGNING_SECRET 変更** → `JOB_QR_SIGNING_KID` を `v1` → `v2` に上げて新キーで署名、旧キーは検証用に残す段階的移行が推奨（`signing-keys` 配列に複数登録できる設計）
3. **DB パスワード変更** → アプリと DB 両方更新、無停止ローテーションは Read Replica 経由のメンテ枠で
4. **OAuth クライアントシークレット変更** → 各プロバイダ管理画面で発行 → env 更新 → アプリ再起動

---

## 関連ドキュメント

- `PRODUCTION_DEPLOY_CHECKLIST.md` — リリースごとの確認項目
- `backend/.claudecode.md` — プロジェクト構成規約
- `CLAUDE.md` 「障害対応の原則」 — トラブル時の対処方針
