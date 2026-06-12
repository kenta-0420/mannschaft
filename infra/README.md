# infra/ — インフラ管理の見取り図

## 三行憲法

1. **ローカル開発** = リポジトリルートの `docker-compose.yml`（MySQL / Valkey / Grafana）
2. **本番インフラ（AWS + Cloudflare）** = `infra/terraform/`（このディレクトリ配下・CI で plan/apply）
3. **アプリのデプロイ** = 既存の deploy workflow（`.github/workflows/backend-deploy.yml` 等）— Terraform はインフラだけを持ち、アプリのイメージ更新は持たない

> `infra/grafana/` はローカル監視用の既存資産。Terraform とは無関係（触らない）。

## ディレクトリ構成

```
infra/terraform/
├── bootstrap/          # 土台層（state バケット / 予算 / GitHub OIDC + IAM ロール3種）
│   └── README.md       # ★マスターが最初に読む実行手順書
├── envs/prod/          # 本番環境ルート（module 結線の司令塔・CI の実行対象）
└── modules/
    ├── network/        # VPC / サブネット / SG
    ├── data/           # RDS MySQL 8.0 / ElastiCache Valkey
    ├── app/            # ALB / ACM / ECR / ECS Fargate (Spring Boot)
    └── edge/           # Cloudflare DNS / Pages (Nuxt) / R2
```

## 進軍順序（導入ロードマップ）

| # | 作戦 | 内容 | 状態 |
|---|---|---|---|
| 1 | **bootstrap** | マスターの手元で 1 回 apply（`bootstrap/README.md` の手順）| 手順書あり |
| 2 | **CI 有効化** | bootstrap output を GitHub variables/secrets に設定 → `INFRA_CI_ENABLED=true` | 手順書あり |
| 3 | **network** | VPC・サブネット・SG（module 実装 → PR plan → マージ apply）| 契約スタブ |
| 4 | **data** | RDS + Valkey | 契約スタブ |
| 5 | **app** | ALB + ACM + ECR + ECS Fargate | 契約スタブ |
| 6 | **edge** | Cloudflare DNS / Pages / R2・同一オリジン入口の完成 | 契約スタブ |

module は 3→6 の順に依存する（network の出力を data/app が使い、app の出力を edge が使う）。
契約（各 module の variables/outputs）は `envs/prod/main.tf` で確定済み。実装時に勝手に変えないこと。

## CI の仕組み

| workflow | トリガー | 何をするか |
|---|---|---|
| `.github/workflows/infra-plan.yml` | `infra/terraform/**` を触る PR | fmt / validate / plan → 結果を PR コメント |
| `.github/workflows/infra-apply.yml` | main への push（同 paths） | plan → apply -auto-approve（直列実行） |

- どちらも **`INFRA_CI_ENABLED=true` が設定されるまでスキップ**（bootstrap 前に誤作動しない）
- AWS 認証は GitHub OIDC（長期キーなし）。PR は読み取り専用ロール、main は apply ロール

## 必要な GitHub secrets / variables 一覧

| 名前 | 種別 | 値の出どころ |
|---|---|---|
| `CLOUDFLARE_API_TOKEN` | secret | Cloudflare ダッシュボード → API Tokens で作成 |
| `INFRA_CI_ENABLED` | variable | bootstrap 完了後に `true`（最後に設定する） |
| `INFRA_TF_PLAN_ROLE_ARN` | variable | bootstrap output `tf_plan_role_arn` |
| `INFRA_TF_APPLY_ROLE_ARN` | variable | bootstrap output `tf_apply_role_arn` |
| `INFRA_DOMAIN_NAME` | variable | 本番ドメイン名（例: mannschaft.example.com） |
| `INFRA_CLOUDFLARE_ACCOUNT_ID` | variable | Cloudflare アカウント ID |
| `INFRA_CLOUDFLARE_ZONE_ID` | variable | 対象ドメインのゾーン ID |

設定コマンド例は `infra/terraform/bootstrap/README.md` §5 を参照。

## 秘密の置き場所（コードに秘密を書かない原則）

| 秘密 | 置き場所 |
|---|---|
| Cloudflare API トークン | GitHub secret / ローカル環境変数 `CLOUDFLARE_API_TOKEN` |
| DB マスターパスワード | AWS Secrets Manager（RDS の自動管理。Terraform state にも平文を残さない） |
| アプリ秘密（JWT 鍵 / Stripe 鍵 等） | AWS SSM Parameter Store（SecureString）。ECS タスク定義の secrets で参照 |
| AWS 認証（CI） | なし（GitHub OIDC の短期クレデンシャル） |

詳細は `infra/terraform/envs/prod/variables.tf` 先頭の一覧表を参照。
