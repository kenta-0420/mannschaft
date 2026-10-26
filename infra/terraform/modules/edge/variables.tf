# =============================================================================
# edge module — 入力契約（確定済み）
# =============================================================================

variable "cloudflare_account_id" {
  description = "Cloudflare アカウント ID（Pages / R2 の作成先）"
  type        = string
}

variable "cloudflare_zone_id" {
  description = "対象ドメインの Cloudflare ゾーン ID（DNS レコードの作成先）"
  type        = string
}

variable "domain_name" {
  description = "本番ドメイン名（例: mannschaft.example.com）"
  type        = string
}

variable "pages_env" {
  description = "Cloudflare Pages（Nuxt）に設定する非秘密の環境変数 map（NUXT_PUBLIC_API_BASE 等）"
  type        = map(string)
}
