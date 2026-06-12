# bootstrap 層 — 実行手順書（マスター向け・インフラ未経験でも進められる版）

この層は **マスターの手元 PC で 1 回だけ** 実行する。
ここで作るのは「Terraform を CI（GitHub Actions）で安全に回すための土台」3 点セット:

1. **state 用 S3 バケット** — 本体（envs/prod）の Terraform state の保管庫
2. **AWS Budgets 月次予算** — 使いすぎたらメールが飛ぶ命綱（既定 100 USD/月）
3. **GitHub OIDC + IAM ロール 3 種** — GitHub Actions が長期キーなしで AWS に入るための鍵

> なぜ手元で実行するのか: 「CI が AWS に入るためのロール」自体を作る層なので、
> ニワトリとタマゴの関係で CI からは実行できない。だから最初の 1 回だけ手動。

---

## 0. 前提ツール

- [AWS CLI v2](https://docs.aws.amazon.com/ja_jp/cli/latest/userguide/getting-started-install.html)
- Terraform 1.10 以上（winget なら `winget install Hashicorp.Terraform`）
- GitHub CLI `gh`（リポジトリに variables/secrets を設定するため。導入済みのはず）

## 1. AWS CLI の認証設定（2 択・どちらか一方）

### 選択肢 A: IAM Identity Center（SSO）— 推奨

AWS アカウントで IAM Identity Center を有効化済みならこちら。短期クレデンシャルで安全。

```powershell
aws configure sso
# SSO start URL / リージョン等を対話で入力 → プロファイル名を例えば mannschaft にする
aws sso login --profile mannschaft
$env:AWS_PROFILE = "mannschaft"
aws sts get-caller-identity   # アカウントIDが表示されれば成功
```

### 選択肢 B: IAM ユーザー + アクセスキー — 手っ取り早い

AWS コンソール → IAM → ユーザー作成（AdministratorAccess を付与）→
「セキュリティ認証情報」タブ → アクセスキー作成（用途: CLI）。

```powershell
aws configure
# AWS Access Key ID / Secret Access Key / region (ap-northeast-1) / output (json) を入力
aws sts get-caller-identity   # アカウントIDが表示されれば成功
```

> 注意: 選択肢 B のアクセスキーは bootstrap 完了後も「手元から Terraform を叩く」用途で残る。
> 漏らさないこと。CI 側は OIDC（このbootstrapが作るロール）を使うのでキーは登録しない。

## 2. 変数ファイルを作る

このディレクトリに `terraform.tfvars` を作成（gitignore 済みなのでコミットされない）:

```hcl
# S3 バケット名は全世界で一意。<アカウントID> は手順1で表示された12桁に置き換える
state_bucket_name = "mannschaft-tfstate-<アカウントID>"

# 予算アラートの通知先
alert_email = "hideharu215@yahoo.co.jp"

# 任意（既定 100 USD/月）
# monthly_budget_usd = 100
```

## 3. terraform init / plan / apply

```powershell
cd infra/terraform/bootstrap
terraform init
terraform plan    # 作られるものの一覧が表示される。エラーがないことを確認
terraform apply   # 「yes」と入力で実行
```

成功すると最後に **Outputs** が表示される:

```
app_deploy_role_arn = "arn:aws:iam::123456789012:role/mannschaft-app-deploy"
state_bucket_name   = "mannschaft-tfstate-123456789012"
tf_apply_role_arn   = "arn:aws:iam::123456789012:role/mannschaft-tf-apply"
tf_plan_role_arn    = "arn:aws:iam::123456789012:role/mannschaft-tf-plan"
```

後から見直したいときは `terraform output` でいつでも再表示できる。

## 4. envs/prod の backend を差し替える

`infra/terraform/envs/prod/backend.tf` の `bucket = "REPLACE_ME..."` を
上記 `state_bucket_name` の値に書き換えて、PR でコミットする。

## 5. GitHub に variables / secrets を設定する

リポジトリルートで以下を実行（値は手順 3 の Outputs と Cloudflare ダッシュボードから）:

```powershell
# --- Terraform CI 用ロール（variables・秘密ではないので variable でよい）---
gh variable set INFRA_TF_PLAN_ROLE_ARN  --body "arn:aws:iam::123456789012:role/mannschaft-tf-plan"
gh variable set INFRA_TF_APPLY_ROLE_ARN --body "arn:aws:iam::123456789012:role/mannschaft-tf-apply"

# --- envs/prod の必須変数（CI の terraform plan/apply が TF_VAR_ 経由で読む）---
gh variable set INFRA_DOMAIN_NAME           --body "<本番ドメイン名 例: mannschaft.example.com>"
gh variable set INFRA_CLOUDFLARE_ACCOUNT_ID --body "<CloudflareアカウントID>"
gh variable set INFRA_CLOUDFLARE_ZONE_ID    --body "<CloudflareゾーンID>"

# --- Cloudflare API トークン（これは秘密なので secret）---
# Cloudflare ダッシュボード → My Profile → API Tokens で作成
# 必要権限: Zone:DNS:Edit / Account:Cloudflare Pages:Edit / Account:Workers R2 Storage:Edit
gh secret set CLOUDFLARE_API_TOKEN --body "<トークン値>"

# --- 最後に CI を起こす（これを設定するまで infra CI は眠っている）---
gh variable set INFRA_CI_ENABLED --body "true"
```

> `INFRA_CI_ENABLED` を **最後** に設定するのが重要。これより前に infra/terraform を
> 触る PR が出ても、CI ジョブは `if:` 条件でスキップされるだけで失敗にはならない。

## 6. 初回 apply の順序について（ACM 証明書検証）

envs/prod の apply は **一発で完了する**。以下の順序が自動的に解決される:

1. `module.app` が `aws_acm_certificate` を作成し `domain_validation_options` を出力
2. `module.edge` が Cloudflare に検証 CNAME（proxied=false）を作成
3. `aws_acm_certificate_validation.this` が DNS 伝播を確認するまで待機（通常 1〜5 分）
4. 検証済み証明書 ARN が `module.app` の HTTPS リスナーにアタッチ

> Terraform は依存グラフを自動解析するため `-target` による手動分割は不要。
> ただし初回 apply では ACM 検証の待機により apply が数分間ブロックされる（正常動作）。

## 7. Secrets Manager への値投入（apply 後に必須）

envs/prod の apply 完了後、以下の 4 箱に値を投入する。
`<region>` は ap-northeast-1 等、`<prefix>` は terraform.tfvars の prefix（既定: mannschaft）。

### 7-1. JWT 署名秘密鍵

```powershell
# 256bit 以上のランダム鍵を生成して投入
$jwtSecret = [Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Maximum 256 -Minimum 0 }) -as [byte[]])
aws secretsmanager put-secret-value `
  --secret-id "<prefix>/jwt-secret" `
  --secret-string $jwtSecret
```

> ECS タスク定義は `MANNSCHAFT_JWT_SECRET` として直接この値を参照する（JSON キーなし）。

### 7-2. Stripe API キー

```powershell
# stripe シークレットは JSON 形式（secret_key / webhook_secret / connect_webhook_secret の 3 キー）
$stripeJson = @{
  secret_key             = "sk_live_xxxx"
  webhook_secret         = "whsec_xxxx"        # F08.2 platform webhook
  connect_webhook_secret = "whsec_yyyy"        # F22.1 Connect webhook
} | ConvertTo-Json -Compress

aws secretsmanager put-secret-value `
  --secret-id "<prefix>/stripe" `
  --secret-string $stripeJson
```

> タスク定義は `:secret_key::` / `:webhook_secret::` / `:connect_webhook_secret::` でキー指定参照。

### 7-3. 内部トークン類

```powershell
# 用途: MANNSCHAFT_AD_UNSUBSCRIBE_SECRET / MANNSCHAFT_AD_OPEN_PIXEL_SECRET 等
$internalJson = @{
  ad_unsubscribe_secret  = "<256bit random>"
  ad_open_pixel_secret   = "<256bit random>"
  internal_signing_key   = "<256bit random>"   # F12.1 §5.14 QR 署名
} | ConvertTo-Json -Compress

aws secretsmanager put-secret-value `
  --secret-id "<prefix>/internal-tokens" `
  --secret-string $internalJson
```

### 7-4. その他アプリキー

```powershell
# 用途: MANNSCHAFT_ENCRYPTION_KEY / MANNSCHAFT_HMAC_KEY 等
$appKeysJson = @{
  encryption_key = "<256bit random>"
  hmac_key       = "<256bit random>"
} | ConvertTo-Json -Compress

aws secretsmanager put-secret-value `
  --secret-id "<prefix>/app-keys" `
  --secret-string $appKeysJson
```

## 8. state（terraform.tfstate）の扱い

- bootstrap の state は **このディレクトリのローカルファイル** `terraform.tfstate`（gitignore 済み）
- 中に IAM ロール等の管理情報が入っている。**消さないこと**。PC バックアップ対象に含めると安心
- bootstrap は一度作ったらほぼ触らないので、ローカル state で実用上問題ない

### state を紛失した場合の復旧（import）

AWS 上のリソース自体は無傷なので、再 init 後に既存リソースを取り込めば復旧できる:

```powershell
cd infra/terraform/bootstrap
terraform init

# 値は自分のアカウントのものに置き換える
terraform import aws_s3_bucket.tfstate mannschaft-tfstate-<アカウントID>
terraform import aws_s3_bucket_versioning.tfstate mannschaft-tfstate-<アカウントID>
terraform import aws_s3_bucket_server_side_encryption_configuration.tfstate mannschaft-tfstate-<アカウントID>
terraform import aws_s3_bucket_public_access_block.tfstate mannschaft-tfstate-<アカウントID>
terraform import aws_budgets_budget.monthly <アカウントID>:mannschaft-monthly-budget
terraform import aws_iam_openid_connect_provider.github arn:aws:iam::<アカウントID>:oidc-provider/token.actions.githubusercontent.com
terraform import aws_iam_role.tf_plan mannschaft-tf-plan
terraform import aws_iam_role_policy.tf_plan_state mannschaft-tf-plan:mannschaft-tf-plan-state-access
terraform import aws_iam_role_policy_attachment.tf_plan_readonly mannschaft-tf-plan/arn:aws:iam::aws:policy/ReadOnlyAccess
terraform import aws_iam_role.tf_apply mannschaft-tf-apply
terraform import aws_iam_role_policy.tf_apply_iam mannschaft-tf-apply:mannschaft-tf-apply-iam-and-state
terraform import aws_iam_role_policy.tf_apply_deny_escalation mannschaft-tf-apply:mannschaft-tf-apply-deny-privilege-escalation
terraform import aws_iam_role_policy_attachment.tf_apply_poweruser mannschaft-tf-apply/arn:aws:iam::aws:policy/PowerUserAccess
terraform import aws_iam_role.app_deploy mannschaft-app-deploy
terraform import aws_iam_role_policy.app_deploy mannschaft-app-deploy:mannschaft-app-deploy-access

terraform plan   # 差分なし（No changes）になれば復旧完了
```
