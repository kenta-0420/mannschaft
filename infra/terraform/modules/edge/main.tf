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
