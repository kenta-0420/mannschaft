# =============================================================================
# app module — 契約スタブ（二番隊実装範囲）
# =============================================================================
# 責務: アプリ実行層（ALB + ACM + ECR + ECS Fargate / Spring Boot desired 1）。
#
# 二番隊が実装する主要リソース:
#   - aws_acm_certificate（var.domain_name / DNS 検証）
#       * 検証レコードの作成は edge module（Cloudflare DNS）側。
#         本 module は domain_validation_options を出力するだけ。
#       * aws_acm_certificate_validation で検証完了を待ってから ALB リスナーに紐付け
#   - aws_lb（public subnet ×2）+ HTTPS リスナー（ACM）+ HTTP→HTTPS リダイレクト
#   - aws_lb_target_group（port 8080 / target_type "ip" / health check /actuator/health）
#   - aws_ecr_repository（${var.prefix}-backend。scan_on_push 推奨）
#   - aws_ecs_cluster + aws_ecs_task_definition + aws_ecs_service（desired_count = 1）
#       * タスクは public subnet + public IP 付与で起動（NAT Gateway 代を節約）
#       * 環境変数: var.app_env（非秘密）+ DB/Valkey 接続情報から組み立て
#       * 秘密: container_definitions の secrets で
#         var.db_master_user_secret_arn / SSM Parameter Store を参照（平文を経由しない）
#   - IAM: タスク実行ロール（ECR pull / CloudWatch Logs / secrets 読取）と
#          タスクロール（SES 送信等）。名前は mannschaft-* プレフィクス必須
#          （bootstrap の tf-apply ロールが mannschaft-* しか IAM 操作できないため）
#   - aws_cloudwatch_log_group（アプリログ）
#
# 契約（variables.tf / outputs.tf）は確定済み。勝手に増減しないこと。
# =============================================================================

terraform {
  required_providers {
    aws = {
      source = "hashicorp/aws"
    }
  }
}

# =============================================================================
# ECR リポジトリ
# =============================================================================

resource "aws_ecr_repository" "backend" {
  name                 = "${var.prefix}-backend"
  image_tag_mutability = "MUTABLE"

  image_scanning_configuration {
    # プッシュ時にイメージスキャンを有効化（CVE 等の早期検知）
    scan_on_push = true
  }
}

# ECR ライフサイクルポリシー: 直近 10 イメージのみ保持（古いイメージのコスト削減）
resource "aws_ecr_lifecycle_policy" "backend" {
  repository = aws_ecr_repository.backend.name

  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "直近10イメージを保持してそれ以前を削除"
        selection = {
          tagStatus   = "any"
          countType   = "imageCountMoreThan"
          countNumber = 10
        }
        action = {
          type = "expire"
        }
      }
    ]
  })
}

# =============================================================================
# CloudWatch Logs
# =============================================================================

# 注意: デフォルトは無期限保存でコストが膨らむ。必ず retention_in_days を明示すること
resource "aws_cloudwatch_log_group" "ecs" {
  name              = "/ecs/${var.prefix}"
  retention_in_days = 90
}

# =============================================================================
# ACM 証明書（DNS 検証）
# =============================================================================

resource "aws_acm_certificate" "this" {
  domain_name       = var.domain_name
  validation_method = "DNS"

  lifecycle {
    # 証明書の更新時に新旧並存して ALB の切り替えを無停止にする
    create_before_destroy = true
  }
}

# 注意: aws_acm_certificate_validation は**使わない**。
# 検証 CNAME は edge module が Cloudflare 側で作成する非同期構成のため、
# Terraform の apply 中に検証完了を待つと Cloudflare 側の apply が完了するまで
# タイムアウトしてしまう。ALB リスナーは証明書 ARN で参照し、
# ACM の検証完了は Cloudflare DNS が伝播してから自動的に行われる。

# =============================================================================
# ALB / ターゲットグループ / リスナー
# =============================================================================

resource "aws_lb" "this" {
  name               = "${var.prefix}-alb"
  internal           = false
  load_balancer_type = "application"
  security_groups    = [var.alb_sg_id]
  subnets            = var.public_subnet_ids
}

resource "aws_lb_target_group" "app" {
  name        = "${var.prefix}-app-tg"
  port        = 8080
  protocol    = "HTTP"
  vpc_id      = var.vpc_id
  target_type = "ip"

  health_check {
    path                = "/actuator/health"
    interval            = 30
    healthy_threshold   = 2
    unhealthy_threshold = 3
    timeout             = 10
    matcher             = "200"
  }
}

# HTTPS リスナー（TLS 1.2 以上のみ許可。Cloudflare がオリジンへ HTTPS で接続する）
resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.this.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = aws_acm_certificate.this.arn

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.app.arn
  }
}

# HTTP(80) リスナーは作成しない。
# 理由: Cloudflare が常時 HTTPS で接続するため、ポート 80 を開放する意味がない。
# Cloudflare の「Always Use HTTPS」設定でクライアント→Cloudflare 間も強制される。

# =============================================================================
# IAM ロール
# =============================================================================

# --- タスク実行ロール（ECR pull / CloudWatch Logs / Secrets 読取）---
# 注意: IAM ロール名は必ず mannschaft-* プレフィクスにすること。
#       bootstrap の tf-apply ロールが mannschaft-* の IAM 操作のみ許可しているため。
resource "aws_iam_role" "task_exec" {
  name = "mannschaft-${var.prefix}-task-exec"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect    = "Allow"
        Principal = { Service = "ecs-tasks.amazonaws.com" }
        Action    = "sts:AssumeRole"
      }
    ]
  })
}

# ECS タスク実行に必要な最低限の AWS マネージドポリシー
resource "aws_iam_role_policy_attachment" "task_exec_managed" {
  role       = aws_iam_role.task_exec.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# Secrets Manager からシークレットを読み取るインラインポリシー
resource "aws_iam_role_policy" "task_exec_secrets" {
  name = "secrets-read"
  role = aws_iam_role.task_exec.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "secretsmanager:GetSecretValue",
          "secretsmanager:DescribeSecret"
        ]
        # 同一プレフィクス配下のシークレットのみに絞る
        Resource = "arn:aws:secretsmanager:*:*:secret:${var.prefix}/*"
      }
    ]
  })
}

# --- タスクロール（アプリ実行時の AWS 操作権限）---
# 注意: IAM ロール名は必ず mannschaft-* プレフィクスにすること。
resource "aws_iam_role" "task" {
  name = "mannschaft-${var.prefix}-task"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect    = "Allow"
        Principal = { Service = "ecs-tasks.amazonaws.com" }
        Action    = "sts:AssumeRole"
      }
    ]
  })
}

# SES 送信権限（Spring Boot の EmailService が使用）
resource "aws_iam_role_policy" "task_ses" {
  name = "ses-send"
  role = aws_iam_role.task.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "ses:SendEmail",
          "ses:SendRawEmail"
        ]
        Resource = "*"
      }
    ]
  })
}

# =============================================================================
# Secrets Manager（箱のみ。値は手動投入）
# =============================================================================
# 重要: aws_secretsmanager_secret_version は**絶対に書かない**。
# 理由: シークレットの値（JWT 秘密鍵・Stripe API キー等）は Terraform の state に
#       平文で残ってしまうため、セキュリティ上 Terraform で管理すべきでない。
#       箱（aws_secretsmanager_secret）を作るだけにして、値は AWS コンソール
#       または aws secretsmanager put-secret-value コマンドで手動投入すること。

resource "aws_secretsmanager_secret" "jwt_secret" {
  name                    = "${var.prefix}/jwt-secret"
  description             = "Spring Boot JWT 署名用秘密鍵"
  recovery_window_in_days = 7
}

resource "aws_secretsmanager_secret" "stripe" {
  name                    = "${var.prefix}/stripe"
  description             = "Stripe API キー（Secret Key / Webhook Secret）"
  recovery_window_in_days = 7
}

resource "aws_secretsmanager_secret" "internal_tokens" {
  name                    = "${var.prefix}/internal-tokens"
  description             = "サービス間通信用の内部トークン類"
  recovery_window_in_days = 7
}

resource "aws_secretsmanager_secret" "app_keys" {
  name                    = "${var.prefix}/app-keys"
  description             = "その他アプリケーション用シークレット（暗号化キー等）"
  recovery_window_in_days = 7
}

# =============================================================================
# ECS クラスタ
# =============================================================================

resource "aws_ecs_cluster" "this" {
  name = "${var.prefix}-cluster"

  setting {
    name  = "containerInsights"
    value = "enabled"
  }
}

# =============================================================================
# ECS タスク定義
# =============================================================================

# アーキテクチャ選定根拠:
# backend/Dockerfile のベースイメージが eclipse-temurin:21-jdk-jammy（linux/amd64 前提）。
# jammy 系の eclipse-temurin は arm64 ビルドも存在するが、
# Dockerfile に PLATFORM 指定がなく、ローカル開発環境が amd64 前提でビルドされているため
# X86_64 を採用する。ARM64（Graviton）を使いたい場合はマルチアーキテクチャビルドが必要。
resource "aws_ecs_task_definition" "app" {
  family                   = "${var.prefix}-app"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.task_cpu
  memory                   = var.task_memory
  execution_role_arn       = aws_iam_role.task_exec.arn
  task_role_arn            = aws_iam_role.task.arn

  # X86_64: eclipse-temurin:21-jdk-jammy が linux/amd64 前提のため（上記コメント参照）
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  container_definitions = jsonencode([
    {
      name      = "${var.prefix}-app"
      image     = "${aws_ecr_repository.backend.repository_url}:latest"
      essential = true

      portMappings = [
        {
          containerPort = 8080
          protocol      = "tcp"
        }
      ]

      # 非秘密の環境変数（var.app_env + DB/Valkey 接続先）
      environment = concat(
        [for k, v in var.app_env : { name = k, value = v }],
        [
          {
            name  = "SPRING_DATASOURCE_URL"
            value = "jdbc:mysql://${var.db_endpoint}:3306/${var.prefix}?serverTimezone=UTC&characterEncoding=UTF-8"
          },
          {
            name  = "SPRING_DATA_REDIS_HOST"
            value = split(":", var.valkey_endpoint)[0]
          },
          {
            name  = "SPRING_DATA_REDIS_PORT"
            value = try(split(":", var.valkey_endpoint)[1], "6379")
          }
        ]
      )

      # 秘密はタスク定義の secrets で参照（平文を state に残さない）
      secrets = [
        {
          # RDS マスターユーザーパスワード（Secrets Manager の :password:: キー参照）
          name      = "SPRING_DATASOURCE_PASSWORD"
          valueFrom = "${var.db_master_user_secret_arn}:password::"
        },
        {
          name      = "APP_JWT_SECRET"
          valueFrom = aws_secretsmanager_secret.jwt_secret.arn
        },
        {
          name      = "STRIPE_SECRET_KEY"
          valueFrom = "${aws_secretsmanager_secret.stripe.arn}:secret_key::"
        },
        {
          name      = "STRIPE_WEBHOOK_SECRET"
          valueFrom = "${aws_secretsmanager_secret.stripe.arn}:webhook_secret::"
        }
      ]

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.ecs.name
          "awslogs-region"        = data.aws_region.current.name
          "awslogs-stream-prefix" = "ecs"
        }
      }
    }
  ])
}

# 現在の AWS リージョン取得（awslogs-region に使用）
data "aws_region" "current" {}

# =============================================================================
# ECS サービス
# =============================================================================

resource "aws_ecs_service" "app" {
  name            = "${var.prefix}-app"
  cluster         = aws_ecs_cluster.this.id
  task_definition = aws_ecs_task_definition.app.arn
  desired_count   = 1
  launch_type     = "FARGATE"

  network_configuration {
    subnets = var.public_subnet_ids
    # public IP 付与: NAT Gateway を使わず ECR/Secrets Manager 等に直接アクセスするため
    assign_public_ip = true
    security_groups  = [var.app_sg_id]
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.app.arn
    container_name   = "${var.prefix}-app"
    container_port   = 8080
  }

  # minimum 0 / maximum 100: WebSocket インメモリブローカー（SockJS/STOMP）を使用しているため
  # 同時に 2 タスクが動くとセッションが別タスクに分散してしまい接続が切れる。
  # ローリングアップデート中に一時的に 0 タスク（数十秒の断）になることを許容して
  # 2 タスク並走を完全に防ぐ設定とする。
  deployment_minimum_healthy_percent = 0
  maximum_percent                    = 100

  # Flyway の初回 migrate が完了するまでヘルスチェックを猶予する（最大 3 分）
  health_check_grace_period_seconds = 180

  lifecycle {
    # Terraform はインフラの「器」（クラスタ・サービス設定）を管理する。
    # タスク定義のイメージタグ更新は CD パイプライン（GitHub Actions）が担う。
    # Terraform が apply するたびにタスク定義が上書きされると CD のデプロイが
    # 巻き戻ってしまうため ignore_changes でデプロイ管理を CD 側に委譲する。
    ignore_changes = [task_definition]
  }
}

# =============================================================================
# SES（SES ドメイン検証は別陣で contract 拡張とセットで実施）
# =============================================================================
# TODO: SES ドメイン検証は別陣で contract 拡張とセットで実施すること。
#       現在の edge module の outputs.tf には DKIM トークンの出力契約がないため、
#       ここで aws_ses_domain_identity + aws_ses_domain_dkim を有効化しても
#       Cloudflare 側で DKIM レコードを作成できない。
#       contract 拡張（edge outputs に dkim_tokens を追加）と同時に実装すること。
#
# # resource "aws_ses_domain_identity" "this" {
# #   domain = var.domain_name
# # }
# #
# # resource "aws_ses_domain_dkim" "this" {
# #   domain = aws_ses_domain_identity.this.domain
# # }
# #
# # output "ses_dkim_tokens" {
# #   description = "SES DKIM トークン（edge module が Cloudflare に DKIM レコードを作成する）"
# #   value       = aws_ses_domain_dkim.this.dkim_tokens
# # }
