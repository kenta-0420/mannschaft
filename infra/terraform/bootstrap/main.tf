# =============================================================================
# Mannschaft 本番インフラ — bootstrap 層
# =============================================================================
# 役割: 「Terraform を CI で安全に回すための土台」だけを作る最小レイヤー。
#   1. Terraform state 保管用 S3 バケット（versioning + SSE-S3 + public block）
#   2. AWS Budgets 月次予算アラート（使いすぎ防止の命綱）
#   3. GitHub Actions OIDC provider + IAM ロール 3 種
#      - mannschaft-tf-plan   : PR 上で terraform plan（読み取り専用 + state 読取 + ロック）
#      - mannschaft-tf-apply  : main ブランチで terraform apply（PowerUser + 限定 IAM）
#      - mannschaft-app-deploy: アプリのデプロイ（ECR push + ECS 更新）
#
# 実行方法: マスターの手元で 1 回だけ `terraform init && terraform apply`。
#   state はローカル（このディレクトリの terraform.tfstate / gitignore 済み）。
#   手順の詳細は同ディレクトリの README.md を参照。
# =============================================================================

terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.56"
    }
  }
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project   = "mannschaft"
      ManagedBy = "terraform-bootstrap"
    }
  }
}

# 現在の AWS アカウント ID を取得（IAM ポリシーの ARN 組み立てに使用）
data "aws_caller_identity" "current" {}

locals {
  account_id = data.aws_caller_identity.current.account_id
}

# -----------------------------------------------------------------------------
# 1. Terraform state 用 S3 バケット
# -----------------------------------------------------------------------------

resource "aws_s3_bucket" "tfstate" {
  bucket = var.state_bucket_name

  # state バケットの誤削除はインフラ管理機能の全損につながるため保護する
  lifecycle {
    prevent_destroy = true
  }
}

# バージョニング有効化（state の誤上書き・破損からの復旧手段）
resource "aws_s3_bucket_versioning" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id

  versioning_configuration {
    status = "Enabled"
  }
}

# SSE-S3（AES256）でサーバーサイド暗号化
resource "aws_s3_bucket_server_side_encryption_configuration" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# パブリックアクセスを全面ブロック（state には接続情報等が含まれる）
resource "aws_s3_bucket_public_access_block" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# -----------------------------------------------------------------------------
# 2. AWS Budgets 月次予算アラート
# -----------------------------------------------------------------------------
# 実額 80% / 実額 100% / 予測 100% の 3 段階でメール通知する。

resource "aws_budgets_budget" "monthly" {
  name         = "mannschaft-monthly-budget"
  budget_type  = "COST"
  limit_amount = tostring(var.monthly_budget_usd)
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  # 実額が予算の 80% を超えたら通知
  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 80
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = [var.alert_email]
  }

  # 実額が予算の 100% を超えたら通知
  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 100
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = [var.alert_email]
  }

  # 月末時点の予測額が予算の 100% を超える見込みになったら通知
  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 100
    threshold_type             = "PERCENTAGE"
    notification_type          = "FORECASTED"
    subscriber_email_addresses = [var.alert_email]
  }
}

# -----------------------------------------------------------------------------
# 3. GitHub Actions OIDC provider
# -----------------------------------------------------------------------------
# GitHub Actions から長期アクセスキーなしで AWS にログインするための信頼基盤。

resource "aws_iam_openid_connect_provider" "github" {
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]

  # AWS は GitHub OIDC のルート CA を直接信頼するため thumbprint は実質未使用だが、
  # API 仕様上必須のため公式に案内されてきた値を設定する。
  # 参考: https://docs.github.com/en/actions/deployment/security-hardening-your-deployments/configuring-openid-connect-in-amazon-web-services
  thumbprint_list = ["6938fd4d98bab03faadb97b34396831e3780aea1"]
}

# -----------------------------------------------------------------------------
# 3-a. mannschaft-tf-plan ロール（PR 上の terraform plan 用・読み取り専用）
# -----------------------------------------------------------------------------

# 信頼ポリシー: このリポジトリの pull_request イベントからのみ assume 可能
data "aws_iam_policy_document" "tf_plan_trust" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_repository}:pull_request"]
    }
  }
}

resource "aws_iam_role" "tf_plan" {
  name = "mannschaft-tf-plan"
  # IAM ロールの description は ASCII/Latin-1 のみ許可（日本語不可）のため英語で記述する
  description        = "Terraform plan from GitHub Actions (PR): read-only + state read + S3 native lock"
  assume_role_policy = data.aws_iam_policy_document.tf_plan_trust.json
}

# AWS 管理ポリシー ReadOnlyAccess（plan に必要な Describe/List/Get 系を一括付与）
resource "aws_iam_role_policy_attachment" "tf_plan_readonly" {
  role       = aws_iam_role.tf_plan.name
  policy_arn = "arn:aws:iam::aws:policy/ReadOnlyAccess"
}

# state バケット読取 + S3 ネイティブロック（use_lockfile）用の限定書込
data "aws_iam_policy_document" "tf_plan_state" {
  # state ファイルの読取
  statement {
    sid     = "StateRead"
    effect  = "Allow"
    actions = ["s3:ListBucket"]
    resources = [
      aws_s3_bucket.tfstate.arn,
    ]
  }

  statement {
    sid     = "StateObjectRead"
    effect  = "Allow"
    actions = ["s3:GetObject"]
    resources = [
      "${aws_s3_bucket.tfstate.arn}/*",
    ]
  }

  # S3 ネイティブロック（backend "s3" の use_lockfile = true）は
  # 「<stateキー>.tflock」オブジェクトの作成/削除でロックを表現するため、
  # .tflock に限定して PutObject / DeleteObject を許可する。
  statement {
    sid    = "StateLockfile"
    effect = "Allow"
    actions = [
      "s3:PutObject",
      "s3:DeleteObject",
    ]
    resources = [
      "${aws_s3_bucket.tfstate.arn}/*.tflock",
    ]
  }
}

resource "aws_iam_role_policy" "tf_plan_state" {
  name   = "mannschaft-tf-plan-state-access"
  role   = aws_iam_role.tf_plan.id
  policy = data.aws_iam_policy_document.tf_plan_state.json
}

# -----------------------------------------------------------------------------
# 3-b. mannschaft-tf-apply ロール（main ブランチの terraform apply 用）
# -----------------------------------------------------------------------------

# 信頼ポリシー: このリポジトリの main ブランチ（push）からのみ assume 可能
data "aws_iam_policy_document" "tf_apply_trust" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_repository}:ref:refs/heads/main"]
    }
  }
}

resource "aws_iam_role" "tf_apply" {
  name = "mannschaft-tf-apply"
  # IAM ロールの description は ASCII/Latin-1 のみ許可（日本語不可）のため英語で記述する
  description        = "Terraform apply from GitHub Actions (main): PowerUser + IAM ops limited to mannschaft-* + state RW"
  assume_role_policy = data.aws_iam_policy_document.tf_apply_trust.json
}

# PowerUserAccess（IAM / Organizations / Account 以外のフルアクセス）
resource "aws_iam_role_policy_attachment" "tf_apply_poweruser" {
  role       = aws_iam_role.tf_apply.name
  policy_arn = "arn:aws:iam::aws:policy/PowerUserAccess"
}

# PowerUserAccess に含まれない IAM 操作を mannschaft-* プレフィクスに限定して許可
# （ECS タスクロール等を Terraform で作成するために必要な最小セット）
data "aws_iam_policy_document" "tf_apply_iam" {
  statement {
    sid    = "ManageMannschaftRoles"
    effect = "Allow"
    actions = [
      "iam:CreateRole",
      "iam:DeleteRole",
      "iam:GetRole",
      "iam:UpdateRole",
      "iam:UpdateRoleDescription",
      "iam:UpdateAssumeRolePolicy",
      "iam:TagRole",
      "iam:UntagRole",
      "iam:AttachRolePolicy",
      "iam:DetachRolePolicy",
      "iam:PutRolePolicy",
      "iam:DeleteRolePolicy",
      "iam:GetRolePolicy",
      "iam:ListRolePolicies",
      "iam:ListAttachedRolePolicies",
      "iam:ListInstanceProfilesForRole",
      "iam:PassRole",
    ]
    resources = [
      "arn:aws:iam::${local.account_id}:role/mannschaft-*",
    ]
  }

  statement {
    sid    = "ManageMannschaftPolicies"
    effect = "Allow"
    actions = [
      "iam:CreatePolicy",
      "iam:DeletePolicy",
      "iam:GetPolicy",
      "iam:GetPolicyVersion",
      "iam:CreatePolicyVersion",
      "iam:DeletePolicyVersion",
      "iam:ListPolicyVersions",
      "iam:TagPolicy",
      "iam:UntagPolicy",
    ]
    resources = [
      "arn:aws:iam::${local.account_id}:policy/mannschaft-*",
    ]
  }

  # RDS / ECS / ElastiCache 等が初回作成時に要求するサービスリンクロール
  statement {
    sid       = "CreateServiceLinkedRoles"
    effect    = "Allow"
    actions   = ["iam:CreateServiceLinkedRole"]
    resources = ["*"]
  }

  # state バケットへのフル RW（apply は state を書き換える）
  statement {
    sid       = "StateBucketList"
    effect    = "Allow"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.tfstate.arn]
  }

  statement {
    sid    = "StateBucketRW"
    effect = "Allow"
    actions = [
      "s3:GetObject",
      "s3:PutObject",
      "s3:DeleteObject",
    ]
    resources = ["${aws_s3_bucket.tfstate.arn}/*"]
  }
}

resource "aws_iam_role_policy" "tf_apply_iam" {
  name   = "mannschaft-tf-apply-iam-and-state"
  role   = aws_iam_role.tf_apply.id
  policy = data.aws_iam_policy_document.tf_apply_iam.json
}

# C13: tf-apply ロールの自己昇格対策
# PowerUserAccess は IAM 操作を含まないが、上記インラインポリシーで mannschaft-* ロールへの
# AttachRolePolicy / PutRolePolicy を許可している。悪意あるコードや誤操作で
# AdministratorAccess / IAMFullAccess / PowerUserAccess をアタッチすることを
# 明示 Deny で防止する。Deny は Allow より優先されるため確実に防御できる。
data "aws_iam_policy_document" "tf_apply_privilege_escalation_deny" {
  statement {
    sid    = "DenyHighPrivilegedPolicyAttach"
    effect = "Deny"
    actions = [
      "iam:AttachRolePolicy",
      "iam:PutRolePolicy",
    ]
    resources = ["*"]

    condition {
      test     = "ArnLike"
      variable = "iam:PolicyARN"
      values = [
        "arn:aws:iam::aws:policy/AdministratorAccess",
        "arn:aws:iam::aws:policy/IAMFullAccess",
        "arn:aws:iam::aws:policy/PowerUserAccess",
      ]
    }
  }
}

resource "aws_iam_role_policy" "tf_apply_deny_escalation" {
  name   = "mannschaft-tf-apply-deny-privilege-escalation"
  role   = aws_iam_role.tf_apply.id
  policy = data.aws_iam_policy_document.tf_apply_privilege_escalation_deny.json
}

# -----------------------------------------------------------------------------
# 3-c. mannschaft-app-deploy ロール（アプリデプロイ用: ECR push + ECS 更新）
# -----------------------------------------------------------------------------

# 信頼ポリシー: main ブランチからのみ（tf-apply と同条件）
data "aws_iam_policy_document" "app_deploy_trust" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_repository}:ref:refs/heads/main"]
    }
  }
}

resource "aws_iam_role" "app_deploy" {
  name = "mannschaft-app-deploy"
  # IAM ロールの description は ASCII/Latin-1 のみ許可（日本語不可）のため英語で記述する
  description        = "App deploy from GitHub Actions (main): ECR push + ECS task definition update + scoped PassRole"
  assume_role_policy = data.aws_iam_policy_document.app_deploy_trust.json
}

data "aws_iam_policy_document" "app_deploy" {
  # ECR ログイン（GetAuthorizationToken はリソース指定不可）
  statement {
    sid       = "EcrAuth"
    effect    = "Allow"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  # ECR push/pull（mannschaft-* リポジトリ限定）
  statement {
    sid    = "EcrPush"
    effect = "Allow"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:BatchGetImage",
      "ecr:GetDownloadUrlForLayer",
      "ecr:InitiateLayerUpload",
      "ecr:UploadLayerPart",
      "ecr:CompleteLayerUpload",
      "ecr:PutImage",
      "ecr:DescribeRepositories",
      "ecr:DescribeImages",
    ]
    resources = [
      "arn:aws:ecr:${var.aws_region}:${local.account_id}:repository/mannschaft-*",
    ]
  }

  # タスク定義の登録・参照（RegisterTaskDefinition はリソース指定不可）
  statement {
    sid    = "EcsTaskDefinition"
    effect = "Allow"
    actions = [
      "ecs:RegisterTaskDefinition",
      "ecs:DescribeTaskDefinition",
      "ecs:DeregisterTaskDefinition",
    ]
    resources = ["*"]
  }

  # ECS サービス更新（mannschaft-* クラスタ配下のサービス限定）
  statement {
    sid    = "EcsServiceUpdate"
    effect = "Allow"
    actions = [
      "ecs:UpdateService",
      "ecs:DescribeServices",
    ]
    resources = [
      "arn:aws:ecs:${var.aws_region}:${local.account_id}:service/mannschaft-*",
    ]
  }

  # タスク定義に紐づくロールの PassRole。
  # 本来はタスク実行ロール / タスクロールの 2 ARN に限定する設計だが、
  # 本ロール作成時点では ARN 未確定のため、当面 mannschaft-* プレフィクス +
  # PassedToService=ecs-tasks 条件で絞る。app module 実装後に 2 ARN へ絞り込むこと。
  statement {
    sid       = "PassEcsTaskRoles"
    effect    = "Allow"
    actions   = ["iam:PassRole"]
    resources = ["arn:aws:iam::${local.account_id}:role/mannschaft-*"]

    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role_policy" "app_deploy" {
  name   = "mannschaft-app-deploy-access"
  role   = aws_iam_role.app_deploy.id
  policy = data.aws_iam_policy_document.app_deploy.json
}
