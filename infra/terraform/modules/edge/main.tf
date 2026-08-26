# =============================================================================
# edge module — Cloudflare 側の入口一式（DNS / Pages / R2 / Tunnel）
# =============================================================================
# 責務: 同一オリジン構成の要。
#   - Cloudflare Pages（Nuxt FE）+ カスタムドメイン + apex CNAME
#   - /api/** ・ /ws を AWS のバックエンドへ向けるルーティング（Origin Rules）
#   - Cloudflare Tunnel（cloudflared）本体・ingress・オリジン CNAME
#   - R2 バケット（添付ファイル等）
#
# 2026-07-10 コスト削減: 入口を ALB → Cloudflare Tunnel へ移行した。
#   - ALB / ACM 証明書（および ACM DNS 検証レコード）を撤去
#   - Tunnel 本体を cloudflare_zero_trust_tunnel_cloudflared で Terraform 管理
#     （provider は lock ファイルで 4.52.7 に固定。当該バージョンで
#      cloudflare_zero_trust_tunnel_cloudflared / _config が正式リソース名）
#   - Tunnel シークレットは random_id で自動生成（state に sensitive で保持。
#      人手管理の秘密を増やさない）。cloudflared サイドカーの run トークンは
#      tunnel_token 出力を AWS Secrets Manager の箱へ手動投入（app module 参照）
#
# 認証: cloudflare provider は環境変数 CLOUDFLARE_API_TOKEN を自動読込（コードに書かない）。
# =============================================================================

terraform {
  required_providers {
    cloudflare = {
      # v4 系に固定: 本 module は v4 スキーマ（cloudflare_record /
      # cloudflare_zero_trust_tunnel_cloudflared 等）で書かれている。
      # v5 はリソース名・スキーマが大きく変わる（cloudflare_dns_record 等）ため、
      # 制約なしで module 単体 init すると v5 が解決され validate が壊れる。
      source  = "cloudflare/cloudflare"
      version = "~> 4.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.0"
    }
  }
}

# =============================================================================
# Cloudflare Tunnel（cloudflared）
# =============================================================================
# ECS タスク内の cloudflared サイドカー（app module）がこのトンネルを張り、
# Cloudflare エッジ → localhost:8080（Spring Boot）へアウトバウンドのみで到達する。
#
# config_src = "cloudflare"（リモート管理）: サイドカーは `tunnel run --token` で
# Cloudflare からルーティング設定（ingress）を取得する。ingress は
# cloudflare_zero_trust_tunnel_cloudflared_config で Terraform 管理する。

# トンネルシークレット（base64。32 バイト以上をデコードできる必要がある）。
# random_id は一度生成すると state に固定され、以降変化しない。
resource "random_id" "tunnel_secret" {
  byte_length = 35 # base64 で 47 文字（>32 バイト）
}

resource "cloudflare_zero_trust_tunnel_cloudflared" "this" {
  account_id = var.cloudflare_account_id
  name       = "${var.domain_name}-tunnel"
  secret     = random_id.tunnel_secret.b64_std
  config_src = "cloudflare"
}

# Tunnel の ingress（リモート管理）。
# Origin Rule が /api・/ws のみをこのトンネルへ流すため、ingress は
# 「すべて localhost:8080 の Spring Boot へ」という単一のキャッチオールで十分。
# Host ヘッダの差異に依存せず堅牢（origin.<domain> でも apex でも同じ挙動）。
# WebSocket（/ws）は cloudflared が http サービスに対し透過的に中継するため追加設定不要。
resource "cloudflare_zero_trust_tunnel_cloudflared_config" "this" {
  account_id = var.cloudflare_account_id
  tunnel_id  = cloudflare_zero_trust_tunnel_cloudflared.this.id

  config {
    ingress_rule {
      service = "http://localhost:8080"
    }
  }
}

# =============================================================================
# オリジン用 DNS（Cloudflare Tunnel への CNAME）
# =============================================================================
# origin.${var.domain_name} → <tunnel_id>.cfargotunnel.com。
# /api/** ・ /ws の Origin Rule がこの CNAME をオリジンホストとして使用する。
#
# proxied = true 必須: cfargotunnel.com は Cloudflare のプロキシを通してのみ
# トンネルへルーティングされる特殊ホスト。ALB 時代の「proxied=true でループ 1000」は
# オリジンが同一ゾーンの ALB だった場合の話で、cfargotunnel では発生しない
# （プロキシがトンネルへ抜けるため）。
resource "cloudflare_record" "origin_cname" {
  zone_id = var.cloudflare_zone_id
  name    = "origin.${var.domain_name}"
  type    = "CNAME"
  content = cloudflare_zero_trust_tunnel_cloudflared.this.cname
  proxied = true
  ttl     = 1 # proxied=true の場合は TTL を 1（自動）にする

  comment = "Cloudflare Tunnel オリジン CNAME（API・WS 用。Origin Rule 参照。proxied=true 必須 — cfargotunnel はプロキシ経由のみ）"
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
# apex（var.domain_name）は Cloudflare Pages（Nuxt FE）へ向くが、/api/** と /ws だけは
# このルールでオリジンを origin.<domain>（= Cloudflare Tunnel の CNAME）へ差し替える。
# これで同一オリジン（cookie/CORS が apex 一本）を保ちつつバックエンドへ到達できる。
#
# TODO（apply 前に公式 docs で要確認）:
#   cloudflare_ruleset の ruleset/origin-rules スキーマは provider バージョンや
#   アカウント権限によって挙動が変わる場合がある。
#   以下の実装は Cloudflare provider v4.52.7 の基本形に基づくが、
#   apply 前に https://developers.cloudflare.com/rules/origin-rules/ および
#   https://registry.terraform.io/providers/cloudflare/cloudflare/latest/docs/resources/ruleset
#   で action_parameters.origin の正確なスキーマを確認すること。
resource "cloudflare_ruleset" "origin_routing" {
  zone_id     = var.cloudflare_zone_id
  name        = "${var.domain_name} origin routing"
  description = "/api/** と /ws を Cloudflare Tunnel オリジンへルーティング"
  kind        = "zone"
  phase       = "http_request_origin"

  rules {
    description = "API・WebSocket リクエストを Tunnel オリジンへ転送"
    # ルール式: /api/ で始まるパスまたは /ws で始まるパスを対象とする
    expression = "(starts_with(http.request.uri.path, \"/api/\") or starts_with(http.request.uri.path, \"/ws\"))"
    action     = "route"

    # origin.<domain> は cfargotunnel（proxied）へ CNAME されており、
    # ここへ origin を差し替えると Cloudflare がトンネル経由で app に到達する。
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
