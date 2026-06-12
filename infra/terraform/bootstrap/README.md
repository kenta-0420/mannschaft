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

## 6. state（terraform.tfstate）の扱い

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
terraform import aws_iam_role_policy_attachment.tf_apply_poweruser mannschaft-tf-apply/arn:aws:iam::aws:policy/PowerUserAccess
terraform import aws_iam_role.app_deploy mannschaft-app-deploy
terraform import aws_iam_role_policy.app_deploy mannschaft-app-deploy:mannschaft-app-deploy-access

terraform plan   # 差分なし（No changes）になれば復旧完了
```
