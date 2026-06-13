# =============================================================================
# app module — 実装済み
# =============================================================================
# 責務: アプリ実行層（ALB + ACM + ECR + ECS Fargate / Spring Boot desired 1）。
#
# ACM 証明書の検証フロー:
#   1. 本 module が aws_acm_certificate を作成し domain_validation_options を出力
#   2. edge module がその情報で Cloudflare に検証 CNAME を作成し、
#      acm_validation_record_fqdns を出力
#   3. ルート（envs/prod/main.tf）が aws_acm_certificate_validation で
#      検証完了を待ち、その certificate_arn を var.listener_certificate_arn として
#      本 module に渡す
#   → HTTPS リスナーは検証済み証明書を参照するため循環依存なし
#
# 依存グラフ（循環なし）:
#   app（cert 作成）→ edge（DNS 検証レコード作成）
#   → ルートの validation リソース（検証完了待機）
#   → app（var.listener_certificate_arn としてリスナーへ渡す）
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
        description  = "Keep last 10 images and expire older ones"
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

# 注意: aws_acm_certificate_validation はこの module では定義しない。
# 理由: edge module（Cloudflare）が同一 apply 内で検証 CNAME を作成するため、
# ルート（envs/prod/main.tf）で validation リソースを定義することで
# 一発 apply 中に検証完了を待てる。
# HTTPS リスナーは var.listener_certificate_arn（ルートが validation 完了後に渡す値）
# を使うことで、検証済み証明書のみが ALB にアタッチされることを保証する。

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
# certificate_arn は var.listener_certificate_arn を使う。
# これはルートの aws_acm_certificate_validation.certificate_arn（検証済み）が渡される。
# 直接 aws_acm_certificate.this.arn を参照しないのは、検証未完了の証明書が
# リスナーにアタッチされて ALB が HTTPS を受け付けられない状態を防ぐため。
resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.this.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.listener_certificate_arn

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
        # mannschaft/* プレフィクス配下のシークレット（JWT / Stripe / 内部トークン等）
        # + RDS 管理シークレット（rds!db-... 名で mannschaft/* プレフィクス外）を許可
        Resource = [
          "arn:aws:secretsmanager:*:*:secret:${var.prefix}/*",
          var.db_master_user_secret_arn,
        ]
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

# F09.6 Phase 8a: SES 通知 SQS の受信権限（Spring Boot の SesNotificationSqsListener が使用）
# spring-cloud-aws は受信時に ReceiveMessage / DeleteMessage / GetQueueAttributes /
# GetQueueUrl / ChangeMessageVisibility を呼ぶ。最小権限で当該キューのみに限定する。
resource "aws_iam_role_policy" "task_sqs_ses" {
  name = "sqs-ses-receive"
  role = aws_iam_role.task.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "sqs:ReceiveMessage",
          "sqs:DeleteMessage",
          "sqs:GetQueueAttributes",
          "sqs:GetQueueUrl",
          "sqs:ChangeMessageVisibility"
        ]
        Resource = aws_sqs_queue.ses_notifications.arn
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

# AWS リソースの description は非 ASCII を拒否するものがある（IAM で実証）ため英語で統一
resource "aws_secretsmanager_secret" "jwt_secret" {
  name                    = "${var.prefix}/jwt-secret"
  description             = "Spring Boot JWT signing secret"
  recovery_window_in_days = 7
}

resource "aws_secretsmanager_secret" "stripe" {
  name                    = "${var.prefix}/stripe"
  description             = "Stripe API keys (secret key / webhook secrets)"
  recovery_window_in_days = 7
}

resource "aws_secretsmanager_secret" "internal_tokens" {
  name                    = "${var.prefix}/internal-tokens"
  description             = "Internal tokens for service-to-service communication"
  recovery_window_in_days = 7
}

resource "aws_secretsmanager_secret" "app_keys" {
  name                    = "${var.prefix}/app-keys"
  description             = "Other application secrets (encryption keys etc.)"
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
          # A1 修正: db_endpoint は "host:3306" 形式のため ":3306" を追記しない
          # A6 追加: SPRING_PROFILES_ACTIVE は tfvars 任せにせず常に prod を設定
          {
            name  = "SPRING_PROFILES_ACTIVE"
            value = "prod"
          },
          {
            name  = "SPRING_DATASOURCE_URL"
            value = "jdbc:mysql://${var.db_endpoint}/${var.db_name}?serverTimezone=UTC&characterEncoding=UTF-8"
          },
          # A2 追加: SPRING_DATASOURCE_USERNAME を注入（application-prod.yml:6 で必須）
          {
            name  = "SPRING_DATASOURCE_USERNAME"
            value = var.db_username
          },
          # A3 修正: application-prod.yml:11-12 は SPRING_REDIS_HOST / SPRING_REDIS_PORT を参照
          {
            name  = "SPRING_REDIS_HOST"
            value = var.valkey_endpoint
          },
          {
            name  = "SPRING_REDIS_PORT"
            value = "6379"
          },
          # F09.6 Phase 8a: SES バウンス/苦情通知の SQS リスナー入口。
          # spring-cloud-aws の @SqsListener はキュー名（URL でなく名前）を受け取る。
          # MANNSCHAFT_SQS_ENABLED は application-prod.yml で既定 true のため明示不要だが、
          # 将来一時停止できるよう env でも上書き可能にしておく。
          {
            name  = "SES_NOTIFICATION_QUEUE_NAME"
            value = aws_sqs_queue.ses_notifications.name
          },
          {
            name  = "AWS_REGION"
            value = data.aws_region.current.name
          }
        ]
      )

      # 秘密はタスク定義の secrets で参照（平文を state に残さない）
      secrets = [
        {
          # RDS マスターユーザーパスワード（RDS 管理シークレットの :password:: キー参照）
          name      = "SPRING_DATASOURCE_PASSWORD"
          valueFrom = "${var.db_master_user_secret_arn}:password::"
        },
        # A4 修正: application-prod.yml:35 は MANNSCHAFT_JWT_SECRET を参照
        {
          name      = "MANNSCHAFT_JWT_SECRET"
          valueFrom = aws_secretsmanager_secret.jwt_secret.arn
        },
        # A5 修正: application.yml:159,161 は MANNSCHAFT_STRIPE_SECRET_KEY / MANNSCHAFT_STRIPE_WEBHOOK_SECRET
        {
          name      = "MANNSCHAFT_STRIPE_SECRET_KEY"
          valueFrom = "${aws_secretsmanager_secret.stripe.arn}:secret_key::"
        },
        {
          name      = "MANNSCHAFT_STRIPE_WEBHOOK_SECRET"
          valueFrom = "${aws_secretsmanager_secret.stripe.arn}:webhook_secret::"
        },
        # A5 追加: application.yml:163 STRIPE_CONNECT_WEBHOOK_SECRET も必要
        {
          name      = "STRIPE_CONNECT_WEBHOOK_SECRET"
          valueFrom = "${aws_secretsmanager_secret.stripe.arn}:connect_webhook_secret::"
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

  # B9: HTTPS リスナーがターゲットグループを関連付けた後にサービスを作成する。
  # リスナーが存在しない状態で CreateService すると ALB 結合に失敗するため。
  depends_on = [aws_lb_listener.https]

  # minimum 0 / maximum 100: WebSocket インメモリブローカー（SockJS/STOMP）を使用しているため
  # 同時に 2 タスクが動くとセッションが別タスクに分散してしまい接続が切れる。
  # ローリングアップデート中に一時的に 0 タスク（数十秒の断）になることを許容して
  # 2 タスク並走を完全に防ぐ設定とする。
  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 100

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
# SES バウンス/苦情通知 — SNS Topic → SQS Queue（F09.6 Phase 8a）
# =============================================================================
# 経路: SES（設定セットのイベント送信先 or Identity 通知）→ SNS Topic
#       → SQS Queue → ECS の Spring Boot SesNotificationSqsListener
#
# 旧来の HTTP webhook（permitAll + SNS 署名検証なし）を廃止し、SQS 内部認証へ移行する。
# SQS のアクセスポリシーで「この SNS Topic からの SendMessage のみ許可」に絞り、
# 受信は ECS タスクロール（mannschaft-prod-task）の最小権限で行う（上記 task_sqs_ses）。
#
# ⚠️ SES → SNS の結線（どの通知種別を Topic に流すか）はこの module の範囲外:
#    - Identity レベル通知の場合: SES Identity の Bounce/Complaint 通知先に
#      aws_sns_topic.ses_notifications.arn を設定（SES Identity は DKIM 検証と同じく
#      edge contract 拡張待ちのため別陣。aws_ses_identity_notification_topic で結線する）。
#    - Configuration Set + Event Destination（推奨）の場合: aws_sesv2_configuration_set_event_destination
#      の sns_destination に本 Topic を指定する。
#    本 module では「SNS Topic / SQS / DLQ / 購読 / IAM」までを用意し、SES 側の結線は
#    SES Identity 整備（別陣）と同時に最後のひと結びとして実施する（apply 手順を docs に明記）。

# --- DLQ（処理に失敗し続けたメッセージの退避先）---
resource "aws_sqs_queue" "ses_notifications_dlq" {
  name = "${var.prefix}-ses-notifications-dlq"

  # 退避メッセージは原因調査のため 14 日（SQS 最大）保持する
  message_retention_seconds = 1209600

  # 保存時暗号化（SSE-SQS。KMS 不要・追加料金なし）
  sqs_managed_sse_enabled = true
}

# --- 本キュー（SES 通知の受信キュー）---
resource "aws_sqs_queue" "ses_notifications" {
  name = "${var.prefix}-ses-notifications"

  # 可視性タイムアウト: リスナーの処理時間に余裕を持たせる（DB 更新数十 ms 想定だが
  # コールドスタート/再試行を考慮し 60 秒）。Spring の処理失敗時はこの時間後に再配信される。
  visibility_timeout_seconds = 60

  # 通知は数日内に処理されれば十分。未処理滞留の上限として 4 日保持。
  message_retention_seconds = 345600

  # ロングポーリング（空受信のコスト/スロットリング削減）
  receive_wait_time_seconds = 20

  sqs_managed_sse_enabled = true

  # 3 回処理に失敗したメッセージは DLQ へ退避する
  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.ses_notifications_dlq.arn
    maxReceiveCount     = 3
  })
}

# --- SNS Topic（SES 通知の集約先）---
resource "aws_sns_topic" "ses_notifications" {
  name = "${var.prefix}-ses-notifications"
}

# --- SNS → SQS サブスクリプション（HTTPS でなく sqs プロトコル）---
# raw_message_delivery = false（既定）: SQS には SNS エンベロープ（Message に SES 通知 JSON 文字列）が届く。
# リスナー（SesNotificationSqsListener）はエンベロープ/raw の両方をパースできる実装にしてある。
resource "aws_sns_topic_subscription" "ses_notifications_to_sqs" {
  topic_arn = aws_sns_topic.ses_notifications.arn
  protocol  = "sqs"
  endpoint  = aws_sqs_queue.ses_notifications.arn
}

# --- SQS アクセスポリシー（この SNS Topic からの SendMessage のみ許可）---
# 偽造メッセージ注入を防ぐため、Principal=SNS かつ aws:SourceArn が当該 Topic の場合のみ許可する。
resource "aws_sqs_queue_policy" "ses_notifications" {
  queue_url = aws_sqs_queue.ses_notifications.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "AllowSNSPublish"
        Effect    = "Allow"
        Principal = { Service = "sns.amazonaws.com" }
        Action    = "sqs:SendMessage"
        Resource  = aws_sqs_queue.ses_notifications.arn
        Condition = {
          ArnEquals = {
            "aws:SourceArn" = aws_sns_topic.ses_notifications.arn
          }
        }
      }
    ]
  })
}

# =============================================================================
# SES Identity / DKIM（別陣で contract 拡張とセットで実施・未着手）
# =============================================================================
# TODO: SES ドメイン検証は別陣で contract 拡張とセットで実施すること。
#       現在の edge module の outputs.tf には DKIM トークンの出力契約がないため、
#       ここで aws_ses_domain_identity + aws_ses_domain_dkim を有効化しても
#       Cloudflare 側で DKIM レコードを作成できない。
#       contract 拡張（edge outputs に dkim_tokens を追加）と同時に実装すること。
#       SES Identity 整備時に、Bounce/Complaint 通知先を aws_sns_topic.ses_notifications に
#       結線する（aws_ses_identity_notification_topic もしくは Configuration Set Event Destination）。
#
# # resource "aws_ses_domain_identity" "this" {
# #   domain = var.domain_name
# # }
# #
# # resource "aws_ses_domain_dkim" "this" {
# #   domain = aws_ses_domain_identity.this.domain
# # }
# #
# # resource "aws_ses_identity_notification_topic" "bounce" {
# #   identity          = aws_ses_domain_identity.this.domain
# #   notification_type = "Bounce"
# #   topic_arn         = aws_sns_topic.ses_notifications.arn
# # }
# #
# # resource "aws_ses_identity_notification_topic" "complaint" {
# #   identity          = aws_ses_domain_identity.this.domain
# #   notification_type = "Complaint"
# #   topic_arn         = aws_sns_topic.ses_notifications.arn
# # }
# #
# # output "ses_dkim_tokens" {
# #   description = "SES DKIM トークン（edge module が Cloudflare に DKIM レコードを作成する）"
# #   value       = aws_ses_domain_dkim.this.dkim_tokens
# # }
