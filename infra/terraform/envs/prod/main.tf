# =============================================================================
# envs/prod — 司令塔（module 結線の正・契約確定済み）
# =============================================================================
# 本番構成の全体像（2026-07-10 コスト削減: ALB → Cloudflare Tunnel）:
#
#   利用者 → Cloudflare（FE = Pages / DNS / R2 / 同一オリジン入口）
#               ├─ 静的・SSR: Cloudflare Pages（Nuxt）
#               └─ /api/** ・ /ws: Cloudflare Tunnel（Origin Rule でオリジン差替）
#                       └─ ECS Fargate（Spring Boot + cloudflared サイドカー, desired 1）
#                               ├─ RDS MySQL 8.0（private subnet）
#                               ├─ ElastiCache Valkey（private subnet）
#                               └─ SES（メール送信）
#
# ALB / ACM 証明書は撤去（固定費 約$20/月削減）。TLS は Cloudflare エッジ + Tunnel 転送で担保。
#
# module 4 つの責務と結線:
#   network → data/app へ subnet・SG を供給
#   data    → app へ DB/Valkey の接続情報を供給
#   app     → ECR/ECS（cloudflared サイドカー同居）。外部インバウンドなし
#   edge    → Cloudflare 側（DNS / Pages / R2 / Tunnel 本体・ingress）を構成
# =============================================================================

locals {
  app_base_url = "https://${var.domain_name}"

  # Spring Boot（ECS）に渡す非秘密 env の標準セット（variables.tf 下部の解説参照）
  app_env = merge(
    {
      APP_BASE_URL               = local.app_base_url
      MANNSCHAFT_ALLOWED_ORIGINS = local.app_base_url
      MANNSCHAFT_COOKIE_SECURE   = "true"
    },
    var.app_env_extra,
  )

  # Cloudflare Pages（Nuxt）に渡す非秘密 env の標準セット
  pages_env = merge(
    {
      NUXT_PUBLIC_API_BASE   = "" # 同一オリジン構成のため空文字（相対パスで /api/** へ）
      NUXT_INTERNAL_API_BASE = local.app_base_url
      NUXT_PUBLIC_BASE_URL   = local.app_base_url
    },
    var.pages_env_extra,
  )
}

# -----------------------------------------------------------------------------
# network: VPC / サブネット / セキュリティグループ
# -----------------------------------------------------------------------------
module "network" {
  source = "../../modules/network"

  prefix   = var.prefix
  vpc_cidr = var.vpc_cidr
}

# -----------------------------------------------------------------------------
# data: RDS MySQL 8.0 + ElastiCache Valkey
# -----------------------------------------------------------------------------
module "data" {
  source = "../../modules/data"

  prefix             = var.prefix
  private_subnet_ids = module.network.private_subnet_ids
  db_sg_id           = module.network.db_sg_id
  cache_sg_id        = module.network.cache_sg_id
  db_instance_class  = var.db_instance_class
  cache_node_type    = var.cache_node_type
}

# -----------------------------------------------------------------------------
# app: ECR + ECS Fargate（Spring Boot + cloudflared サイドカー）
# 2026-07-10 コスト削減: ALB / ACM を撤去し、入口は edge の Cloudflare Tunnel が担う。
# -----------------------------------------------------------------------------
module "app" {
  source = "../../modules/app"

  prefix                    = var.prefix
  public_subnet_ids         = module.network.public_subnet_ids
  app_sg_id                 = module.network.app_sg_id
  db_endpoint               = module.data.db_endpoint
  db_name                   = module.data.db_name
  db_username               = module.data.db_username
  db_master_user_secret_arn = module.data.db_master_user_secret_arn
  valkey_endpoint           = module.data.valkey_primary_endpoint
  app_env                   = local.app_env
  task_cpu                  = var.task_cpu
  task_memory               = var.task_memory
}

# -----------------------------------------------------------------------------
# edge: Cloudflare DNS / Pages（Nuxt） / R2 / Tunnel（cloudflared 入口）
# -----------------------------------------------------------------------------
module "edge" {
  source = "../../modules/edge"

  cloudflare_account_id = var.cloudflare_account_id
  cloudflare_zone_id    = var.cloudflare_zone_id
  domain_name           = var.domain_name
  pages_env             = local.pages_env
}
