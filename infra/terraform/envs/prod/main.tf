# =============================================================================
# envs/prod — 司令塔（module 結線の正・契約確定済み）
# =============================================================================
# 本番構成の全体像:
#
#   利用者 → Cloudflare（FE = Pages / DNS / R2 / 同一オリジン入口）
#               ├─ 静的・SSR: Cloudflare Pages（Nuxt）
#               └─ /api/** ・ /ws: AWS ALB（HTTPS, ACM 証明書）
#                       └─ ECS Fargate（Spring Boot, desired 1）
#                               ├─ RDS MySQL 8.0（private subnet）
#                               ├─ ElastiCache Valkey（private subnet）
#                               └─ SES（メール送信）
#
# module 4 つの責務と結線（このファイルが契約の正。二番隊は modules/ を実装する）:
#   network → data/app へ subnet・SG を供給
#   data    → app へ DB/Valkey の接続情報を供給
#   app     → edge へ ALB DNS 名・ACM DNS 検証レコードを供給
#   edge    → Cloudflare 側（DNS / Pages / R2）を構成
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
# app: ALB + ACM + ECR + ECS Fargate（Spring Boot）
# -----------------------------------------------------------------------------
module "app" {
  source = "../../modules/app"

  prefix                    = var.prefix
  vpc_id                    = module.network.vpc_id
  public_subnet_ids         = module.network.public_subnet_ids
  alb_sg_id                 = module.network.alb_sg_id
  app_sg_id                 = module.network.app_sg_id
  domain_name               = var.domain_name
  db_endpoint               = module.data.db_endpoint
  db_master_user_secret_arn = module.data.db_master_user_secret_arn
  valkey_endpoint           = module.data.valkey_primary_endpoint
  app_env                   = local.app_env
  task_cpu                  = var.task_cpu
  task_memory               = var.task_memory
}

# -----------------------------------------------------------------------------
# edge: Cloudflare DNS / Pages（Nuxt） / R2
# -----------------------------------------------------------------------------
module "edge" {
  source = "../../modules/edge"

  cloudflare_account_id         = var.cloudflare_account_id
  cloudflare_zone_id            = var.cloudflare_zone_id
  domain_name                   = var.domain_name
  alb_dns_name                  = module.app.alb_dns_name
  acm_domain_validation_options = module.app.acm_domain_validation_options
  pages_env                     = local.pages_env
}
