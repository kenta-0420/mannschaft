# =============================================================================
# app module — 実装済み
# =============================================================================
# 責務: アプリ実行層（ECR + ECS Fargate / Spring Boot + cloudflared サイドカー, desired 1）。
#
# 2026-07-10 コスト削減: 入口を ALB → Cloudflare Tunnel へ移行した。
#   - ALB / ターゲットグループ / HTTPS リスナー / ACM 証明書は撤去（固定費 約$20/月削減）
#   - トンネル本体・DNS・ingress は edge module（Cloudflare）が管理
#   - 本 module は cloudflared サイドカーと、その run トークンを入れる
#     Secrets Manager の箱（値は手動投入）を持つ
#   - app ↔ edge の module 依存はなし（トークンの受け渡しは apply 後の手動投入で疎結合）
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

# 注意: デフォルトは無期限保存でコストが膨らむ。必ず retention_in_days を明示すること。
# コスト削減（2026-07-10）: 90 日 → 30 日に短縮。ECS/cloudflared のアプリログは
# 直近 1 ヶ月あれば障害調査に十分で、長期保存は CloudWatch Logs のストレージ課金を押し上げるだけ。
resource "aws_cloudwatch_log_group" "ecs" {
  name              = "/ecs/${var.prefix}"
  retention_in_days = 30
}

# =============================================================================
# 入口構成（ALB → Cloudflare Tunnel 化・2026-07-10 コスト削減）
# =============================================================================
# ALB（月 $20 前後の固定費）・ACM 証明書・HTTPS リスナー・ターゲットグループは撤去した。
# 代わりに ECS タスク内に cloudflared サイドカー（下の task_definition 参照）を同居させ、
# Cloudflare Tunnel（アウトバウンドのみ）で Cloudflare エッジ → localhost:8080 へ到達する。
#
# これにより:
#   - ALB / ACM が不要（TLS は Cloudflare エッジと Tunnel 転送で担保。AWS 側の証明書ゼロ）
#   - 外部インバウンド不要（app_sg はアウトバウンドのみ。network module 参照）
#   - トンネル本体・DNS・ingress は edge module（Cloudflare）が Terraform 管理
#
# ヘルスチェックは ALB ターゲットグループから ECS タスク定義の container healthCheck へ移設した。

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
        # mannschaft/* プレフィクス配下のシークレット（JWT / Stripe / 内部トークン
        # / cloudflared-tunnel-token 等はすべてこのワイルドカードで包含される）
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

# cloudflared サイドカーの Tunnel トークン（`cloudflared tunnel run` が TUNNEL_TOKEN で読む）。
# 箱だけ Terraform 管理・値は手動投入（jwt-secret 等と同じ方針）。
# 値は edge module の tunnel_token 出力（Cloudflare が発行）を変数に受けてから投入する:
#   $t = terraform -chdir=infra/terraform/envs/prod output -raw cloudflared_tunnel_token
#   aws secretsmanager put-secret-value --secret-id "<prefix>/cloudflared-tunnel-token" --secret-string $t
# 詳細手順は infra/terraform/bootstrap/README.md §7-5 を参照。
resource "aws_secretsmanager_secret" "cloudflared_tunnel_token" {
  name                    = "${var.prefix}/cloudflared-tunnel-token"
  description             = "Cloudflare Tunnel run token for the cloudflared ECS sidecar"
  recovery_window_in_days = 7
}

# =============================================================================
# ECS クラスタ
# =============================================================================

resource "aws_ecs_cluster" "this" {
  name = "${var.prefix}-cluster"

  setting {
    # コスト削減（2026-07-10）: Container Insights を無効化。
    # 追加メトリクス/ダッシュボードの CloudWatch 課金（数ドル/月）を止める。
    # 基本的な CPU/メモリ監視は無料のサービスメトリクスで足り、詳細計測が必要になったら再有効化する。
    name  = "containerInsights"
    value = "disabled"
  }
}

# =============================================================================
# ECS タスク定義
# =============================================================================

# アーキテクチャ選定根拠（2026-07-10 コスト削減で ARM64 / Graviton へ移行）:
# Fargate は Graviton（ARM64）で同スペック比 約2割安い。
# backend/Dockerfile のベースイメージ eclipse-temurin:21-jdk-jammy / 21-jre-jammy は
# arm64 マルチアーキテクチャイメージが公式に存在するため、Dockerfile 変更なしで arm64 ビルド可能。
# CD（.github/workflows/backend-deploy.yml）は docker buildx --platform linux/arm64 で
# arm64 イメージを push する構成に更新済み。
# cloudflared サイドカー（cloudflare/cloudflared）も linux/arm64 を含むマルチアーキイメージ。
resource "aws_ecs_task_definition" "app" {
  family                   = "${var.prefix}-app"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.task_cpu
  memory                   = var.task_memory
  execution_role_arn       = aws_iam_role.task_exec.arn
  task_role_arn            = aws_iam_role.task.arn

  # ARM64（Graviton）: eclipse-temurin / cloudflared とも arm64 マルチアーキイメージあり。
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
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

      # ヘルスチェック（旧 ALB ターゲットグループから移設）。
      # cloudflared サイドカーはこの healthCheck が HEALTHY になるまで起動を待つ（dependsOn）。
      # runtime イメージ（jre-jammy）に wget が入っているため wget で actuator を叩く。
      healthCheck = {
        command     = ["CMD-SHELL", "wget -q --spider http://localhost:8080/actuator/health || exit 1"]
        interval    = 30
        timeout     = 10
        retries     = 3
        startPeriod = 180 # Flyway 初回 migrate 完了までの猶予（旧 health_check_grace_period_seconds 相当）
      }

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
          # WebSocket 外部ブローカー化（Valkey Pub/Sub relay）§8.6: ElastiCache の転送時暗号化
          # （transit_encryption_enabled = true・data module）に追随する Lettuce 側 TLS 有効化。
          # 単一の LettuceConnectionFactory を全用途（キャッシュ・レート制限・presence TTL・relay）が
          # 共有するため、この 1 設定で全 Redis 接続が TLS 化される。ローカル/開発は
          # application.yml 側で ssl.enabled 未設定（既定 false）のため無変更。
          {
            name  = "SPRING_REDIS_SSL_ENABLED"
            value = "true"
          },
          # WebSocket 外部ブローカー化（設計 §1.3）: relay 部品（Publisher/Subscriber/
          # RedisMessageListenerContainer）の Bean 生成を切り替える feature flag。
          # 既定 false（段階 1 で true へ切替。variable 化: modules/app/variables.tf
          # websocket_relay_enabled → envs/prod/variables.tf 経由で tfvars から上書き可能）。
          {
            name  = "MANNSCHAFT_WEBSOCKET_RELAY_ENABLED"
            value = tostring(var.websocket_relay_enabled)
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
    },
    # -------------------------------------------------------------------------
    # cloudflared サイドカー（Cloudflare Tunnel。旧 ALB の入口を置き換える）
    # -------------------------------------------------------------------------
    # 同一タスク内なので app へは localhost:8080 で到達（awsvpc: 同一 ENI・ループバック共有）。
    # アウトバウンドのみで Cloudflare エッジへ接続するため外部インバウンド SG は不要。
    # トークンは Secrets Manager の箱（値は手動投入）から TUNNEL_TOKEN として注入する。
    {
      name      = "${var.prefix}-cloudflared"
      image     = var.cloudflared_image
      essential = true # トンネルが落ちたら入口が消えるためタスクごと再作成させる

      # `cloudflared tunnel run`（トークンは環境変数 TUNNEL_TOKEN から読む）。
      # --no-autoupdate: イメージ更新は CD/Terraform で管理し、実行中の自動更新を止める。
      command = ["tunnel", "--no-autoupdate", "run"]

      # app が HEALTHY になってからトンネルを張る（起動直後の 5xx を Cloudflare に晒さない）
      dependsOn = [
        {
          containerName = "${var.prefix}-app"
          condition     = "HEALTHY"
        }
      ]

      secrets = [
        {
          name      = "TUNNEL_TOKEN"
          valueFrom = aws_secretsmanager_secret.cloudflared_tunnel_token.arn
        }
      ]

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.ecs.name
          "awslogs-region"        = data.aws_region.current.name
          "awslogs-stream-prefix" = "cloudflared"
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
    # public IP 付与: NAT Gateway を使わず ECR/Secrets Manager 等に直接アクセスするため。
    # cloudflared のアウトバウンド（Cloudflare エッジへの接続）にも公開 IP 経由の egress を使う。
    assign_public_ip = true
    security_groups  = [var.app_sg_id]
  }

  # ALB 撤去（Cloudflare Tunnel 化）に伴い load_balancer ブロックは削除した。
  # 入口は cloudflared サイドカーが張るトンネルで、ECS サービスはロードバランサに紐づかない。
  # health_check_grace_period_seconds は LB 前提の設定のため削除し、
  # コンテナ healthCheck の startPeriod（タスク定義側 180 秒）で初回 migrate を待つ。

  # ローリングデプロイ解禁（2026-07-11 隊3・設計: docs/architecture/websocket_external_broker_valkey.md §8.6）:
  # WebSocket は SimpleBroker（ノードローカル）を維持しつつ、Valkey Pub/Sub による
  # ノード間中継 relay（PR #2231 で BE 中核実装済み・feature flag
  # MANNSCHAFT_WEBSOCKET_RELAY_ENABLED で ON/OFF）を新設した。relay が ON の間は
  # 新旧タスクが並走してもノードを跨いだ配信が揃うため、2 タスク並走が安全になった。
  # minimum 100 / maximum 200: ローリング中も旧タスクを維持したまま新タスクを追加起動し、
  # ヘルシーになってから旧タスクを落とす（断ゼロ）。desired_count は段階移行に従い
  # 段階 1 では 1 のまま（relay ON の単一ノード検証）。段階 2 で 2 以上へ引き上げる
  # （設計 §1.3）。relay OFF のままこの設定に変更しても、単一タスク運用である限り
  # 旧来と同じくデプロイ中の瞬断は起きうるため、relay ON 後の運用を前提とする。
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200

  # デプロイ失敗時（新タスクが継続的に unhealthy 等）は自動でロールバックする。
  # ローリング解禁（新旧並走）に伴い、失敗検知と自動復旧を明示的に持たせる。
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

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
#       注意: ALB→Tunnel 化（2026-07-10）で var.domain_name を app module から削除した。
#       下記を有効化する際は variables.tf に domain_name を再追加すること。
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
