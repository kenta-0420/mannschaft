# =============================================================================
# プロバイダ設定
# =============================================================================

provider "aws" {
  region = "ap-northeast-1"

  # 全リソースに共通タグを付与（コスト集計・棚卸し用）
  default_tags {
    tags = {
      Project   = "mannschaft"
      ManagedBy = "terraform"
    }
  }
}

provider "cloudflare" {
  # 認証トークンは環境変数 CLOUDFLARE_API_TOKEN から自動読込される。
  # コード・tfvars には絶対に書かないこと。
  #   - CI: GitHub secret CLOUDFLARE_API_TOKEN → workflow の env で注入
  #   - ローカル: $env:CLOUDFLARE_API_TOKEN = "..." を設定してから実行
}
