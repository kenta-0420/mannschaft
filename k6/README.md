# k6 負荷テスト基盤

ローカルの Docker Compose 環境（Spring Boot + MySQL）に対して [k6](https://k6.io/) で負荷テストを実行するための基盤です。

## 前提条件

以下のいずれかの方法で k6 を用意してください。

### A. k6 をローカルにインストールする場合（推奨）

```bash
# macOS (Homebrew)
brew install k6

# Windows (Winget)
winget install k6 --source winget

# Docker なしで直接実行
k6 version
```

### B. Docker で実行する場合

```bash
docker-compose -f docker-compose.yml -f k6/docker-compose.k6.yml \
  run --rm k6 run /scripts/scenarios/05_smoke.js
```

## Spring Boot の起動確認

負荷テスト実行前に Spring Boot が起動していることを確認してください。

```bash
# Docker Compose でバックエンドを起動
docker-compose up -d

# ヘルスチェック
curl http://localhost:8080/actuator/health
# => {"status":"UP"}
```

## ディレクトリ構成

```
k6/
├── README.md                # このファイル
├── docker-compose.k6.yml    # k6 コンテナの Compose オーバーライド
├── config/
│   └── env.local.js         # 環境変数（BASE_URL・認証情報・サンプル ID）
├── lib/
│   ├── auth.js              # ログイン・トークン取得の共通関数
│   └── helpers.js           # sleep・日時範囲生成等のユーティリティ
└── scenarios/
    ├── 01_auth.js           # 認証（ログイン）負荷テスト
    ├── 02_schedule.js       # スケジュール一覧・詳細取得
    ├── 03_team.js           # チーム情報取得
    ├── 04_public_pages.js   # 未ログイン公開ページ（F19.1）
    └── 05_smoke.js          # スモークテスト（全シナリオを軽量に一通り）
```

## 実行方法

### スモークテスト（最初に実行）

全シナリオを軽量に一通り実行してシステムの基本動作を確認します。

```bash
k6 run k6/scenarios/05_smoke.js
```

### シナリオ別実行

```bash
# 認証シナリオ（ログイン 50 VU）
k6 run k6/scenarios/01_auth.js

# スケジュール取得（30 VU）
k6 run k6/scenarios/02_schedule.js

# チーム情報取得（40 VU）
k6 run k6/scenarios/03_team.js

# 公開ページ（50 VU、認証不要）
k6 run k6/scenarios/04_public_pages.js
```

### 環境変数で設定を上書きする

```bash
# ベース URL を変更（デフォルト: http://localhost:8080）
BASE_URL=http://192.168.1.100:8080 k6 run k6/scenarios/05_smoke.js

# テストユーザーを変更
TEST_USER_EMAIL=admin@example.com TEST_USER_PASSWORD=secret k6 run k6/scenarios/01_auth.js

# サンプルデータの ID を指定（ローカル DB に存在する ID を指定すること）
SAMPLE_TEAM_ID=5 SAMPLE_ORG_ID=2 k6 run k6/scenarios/02_schedule.js
```

### Docker で実行する場合

```bash
docker-compose -f docker-compose.yml -f k6/docker-compose.k6.yml \
  run --rm k6 run /scripts/scenarios/05_smoke.js
```

## 実際のエンドポイント一覧

調査結果に基づく、テスト対象エンドポイントの一覧です。

| シナリオ | メソッド | パス | 認証 |
|---|---|---|---|
| 01 | POST | `/api/v1/auth/login` | 不要 |
| 02 | GET | `/api/v1/teams/{id}/schedules?from=...&to=...` | 必要 |
| 02 | GET | `/api/v1/organizations/{id}/schedules?from=...&to=...` | 必要 |
| 03 | GET | `/api/v1/teams/{id}` | 必要 |
| 03 | GET | `/api/v1/teams/{id}/members` | 必要 |
| 04 | GET | `/api/v1/public/teams/{id}` | 不要 |
| 04 | GET | `/api/v1/public/organizations/{id}` | 不要 |
| 04 | GET | `/api/v1/public/teams/{id}/posts` | 不要 |

### 認証の仕様

- **ログインエンドポイント**: `POST /api/v1/auth/login`
- **リクエストボディ**: `{ "email": "...", "password": "...", "rememberMe": false }`
- **トークンフィールド**: `data.accessToken`（`ApiResponse<TokenResponse>` のラッパー構造）
- **Authorization ヘッダー**: `Authorization: Bearer {accessToken}`
- **トークン種別**: `tokenType: "Bearer"`

## 結果の読み方

k6 実行後に表示されるサマリーの主要指標：

| 指標 | 説明 | 目安 |
|---|---|---|
| `http_req_duration` | レスポンスタイム | p(95) < 2000ms を目標 |
| `http_req_failed` | エラー率（4xx/5xx） | < 1% を目標 |
| `http_reqs` | 総リクエスト数 / RPS | スループット指標 |
| `vus` | 同時接続数 | 負荷レベルの確認 |
| `checks` | アサーション成功率 | 100% が理想 |

### 例: 正常な出力

```
✓ login: status 200
✓ login: has accessToken
✓ public team: status 200 or 404

http_req_duration............: avg=145ms  p(95)=312ms
http_req_failed..............: 0.00%
```

### 閾値（thresholds）が失敗した場合

終了コード `99` が返ります。以下を確認してください:

1. Spring Boot が起動しているか（`docker-compose up -d` 後に `/actuator/health` 確認）
2. テストユーザー（`test@example.com` / `password123`）がローカル DB に存在するか
3. `SAMPLE_TEAM_ID` / `SAMPLE_ORG_ID` のデータがローカル DB に存在するか

## テスト結果の保存

k6 の結果を JSON または CSV で保存する場合:

```bash
# JSON 形式で保存
k6 run --out json=k6/results/smoke_$(date +%Y%m%d_%H%M%S).json k6/scenarios/05_smoke.js

# CSV 形式で保存
k6 run --out csv=k6/results/smoke_$(date +%Y%m%d_%H%M%S).csv k6/scenarios/05_smoke.js
```

`k6/results/` ディレクトリと `k6/*.json` / `k6/*.csv` は `.gitignore` で除外されています。

## 注意事項

- **本番環境禁止**: このテストは必ずローカル Docker Compose 環境でのみ実行してください
- **レートリミット**: `04_public_pages.js` は `PublicApiRateLimitFilter` の制限（60 req/min/IP）を考慮した VU 数に設定しています。大幅に VU を増やす場合は注意が必要です
- **テストデータ**: ローカル DB の `test@example.com` ユーザーが必要です。シードスクリプトで事前に作成してください
