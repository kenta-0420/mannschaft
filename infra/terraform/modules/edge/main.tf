# =============================================================================
# edge module — 契約スタブ（二番隊実装範囲）
# =============================================================================
# 責務: Cloudflare 側の入口一式（DNS / Pages / R2）。同一オリジン構成の要。
#
# 二番隊が実装する主要リソース:
#   - cloudflare_record:
#       * ACM DNS 検証レコード（var.acm_domain_validation_options から for_each。
#         proxied = false 必須 — 検証 CNAME は素通しでないと ACM が確認できない）
#       * ルートドメイン → ALB（var.alb_dns_name）への CNAME（proxied = true）
#   - cloudflare_pages_project（Nuxt の FE。var.pages_env を deployment config の
#     env として設定。production ブランチ = main）
#   - cloudflare_pages_domain（var.domain_name を Pages にカスタムドメイン割当）
#   - cloudflare_r2_bucket（添付ファイル等のオブジェクトストレージ）
#   - /api/** ・ /ws を ALB へ向けるルーティング
#     （Origin Rules または Workers ルート。Cloudflare の仕様確認のうえ選定。
#       公式: https://developers.cloudflare.com/rules/origin-rules/）
#
# 認証: cloudflare provider は環境変数 CLOUDFLARE_API_TOKEN を自動読込（コードに書かない）。
# 契約（variables.tf / outputs.tf）は確定済み。勝手に増減しないこと。
# =============================================================================

terraform {
  required_providers {
    cloudflare = {
      source = "cloudflare/cloudflare"
    }
  }
}

# =============================================================================
# ACM 証明書の DNS 検証レコード
# =============================================================================
# proxied = false 必須: ACM の検証は CNAME 先へ直接アクセスして行われる。
# Cloudflare のプロキシ（オレンジ雲）が挟まると ACM が正しい CNAME 先を
# 確認できずに検証失敗するため、必ず素通し（グレー雲）にすること。
resource "cloudflare_record" "acm_validation" {
  for_each = {
    for dvo in var.acm_domain_validation_options :
    dvo.domain_name => dvo
  }

  zone_id = var.cloudflare_zone_id
  name    = trimsuffix(each.value.resource_record_name, ".")
  type    = each.value.resource_record_type
  content = trimsuffix(each.value.resource_record_value, ".")
  proxied = false
  ttl     = 60

  comment = "ACM DNS 検証レコード（proxied=false 必須 / Terraform 管理）"
}

# =============================================================================
# オリジン用 DNS（ALB への CNAME）
# =============================================================================
# origin.${var.domain_name} → ALB（Cloudflare プロキシ経由）
# /api/** ・ /ws の Origin Rule がこの CNAME をオリジンホストとして使用する。
# proxied = true にして Cloudflare WAF / DDoS 保護を有効にする。
resource "cloudflare_record" "origin_cname" {
  zone_id = var.cloudflare_zone_id
  name    = "origin.${var.domain_name}"
  type    = "CNAME"
  content = var.alb_dns_name
  proxied = true
  ttl     = 1 # proxied=true の場合は TTL を 1（自動）にする

  comment = "ALB オリジン CNAME（API・WS 用。Cloudflare Origin Rule が参照）"
}

# =============================================================================
# Cloudflare Pages プロジェクト（Nuxt FE）
# =============================================================================
resource "cloudflare_pages_project" "frontend" {
  account_id        = var.cloudflare_account_id
  name              = replace(var.domain_name, ".", "-")
  production_branch = "main"

  # build_config は省略: wrangler デプロイ（CI が npx wrangler pages deploy で直接デプロイ）
  # を前提とするため、Cloudflare Pages の Git 連携ビルドは使用しない。
  # build_config ブロックを省略すると provider デフォルト（ビルドなし）が適用される。

  deployment_configs {
    production {
      environment_variables = var.pages_env
    }
  }
}

# =============================================================================
# Cloudflare Pages カスタムドメイン
# =============================================================================
resource "cloudflare_pages_domain" "apex" {
  account_id   = var.cloudflare_account_id
  project_name = cloudflare_pages_project.frontend.name
  domain       = var.domain_name
}

# apex CNAME → Pages（var.domain_name → <project>.pages.dev）
# proxied = true: Cloudflare が WAF・キャッシュ・HTTPS を担う
resource "cloudflare_record" "apex_cname" {
  zone_id = var.cloudflare_zone_id
  name    = var.domain_name
  type    = "CNAME"
  content = "${cloudflare_pages_project.frontend.name}.pages.dev"
  proxied = true
  ttl     = 1 # proxied=true の場合は TTL を 1（自動）にする

  comment = "apex → Cloudflare Pages CNAME"
}

# =============================================================================
# /api/** ・ /ws ルーティング（Origin Rules）
# =============================================================================
# TODO（apply 前に公式 docs で要確認）:
#   cloudflare_ruleset の ruleset/origin-rules スキーマは provider バージョンや
#   アカウント権限によって挙動が変わる場合がある。
#   以下の実装は Cloudflare provider v4.52.7 の基本形に基づくが、
#   apply 前に https://developers.cloudflare.com/rules/origin-rules/ および
#   https://registry.terraform.io/providers/cloudflare/cloudflare/latest/docs/resources/ruleset
#   で action_parameters.origin の正確なスキーマを確認すること。
#   特に host_header / port の指定方法は version によって変わる可能性がある。
resource "cloudflare_ruleset" "origin_routing" {
  zone_id     = var.cloudflare_zone_id
  name        = "${var.domain_name} origin routing"
  description = "/api/** と /ws を ALB オリジンへルーティング"
  kind        = "zone"
  phase       = "http_request_origin"

  rules {
    description = "API・WebSocket リクエストを ALB オリジンへ転送"
    # ルール式: /api/ で始まるパスまたは /ws で始まるパスを対象とする
    expression = "(starts_with(http.request.uri.path, \"/api/\") or starts_with(http.request.uri.path, \"/ws\"))"
    action     = "route"

    # TODO（apply 前要確認）:
    # action_parameters.origin の host / port 指定方法は
    # provider v4 系のドキュメントで確認が必要。
    # https://registry.terraform.io/providers/cloudflare/cloudflare/latest/docs/resources/ruleset#origin
    action_parameters {
      origin {
        host = "origin.${var.domain_name}"
        port = 443
      }
    }

    enabled = true
  }
}

# =============================================================================
# R2 バケット（添付ファイル等のオブジェクトストレージ）
# =============================================================================
resource "cloudflare_r2_bucket" "storage" {
  account_id = var.cloudflare_account_id
  name       = "${replace(var.domain_name, ".", "-")}-storage"
  location   = "APAC"
}
