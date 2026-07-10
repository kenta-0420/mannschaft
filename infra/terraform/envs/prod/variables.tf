# =============================================================================
# envs/prod の入力変数
# =============================================================================
#
# ■「どの秘密がどこにあるか」一覧表（このファイルには秘密を一切書かない）
#
# | 秘密                               | 置き場所                                   | 参照経路                                                |
# |------------------------------------|--------------------------------------------|---------------------------------------------------------|
# | CLOUDFLARE_API_TOKEN               | GitHub secret / ローカル環境変数           | cloudflare provider が環境変数から自動読込              |
# | DB マスターパスワード              | AWS Secrets Manager（RDS 自動管理）        | data module の db_master_user_secret_arn → app module が |
# |                                    |                                            | ECS タスク定義の secrets で注入（平文を経由しない）     |
# | アプリ秘密（JWT 鍵 / Stripe 鍵等） | AWS Secrets Manager（箱は Terraform 作成）  | app module が ECS タスク定義の secrets で参照。値の投入 |
# |                                    |                                            | は Terraform 管理外（aws secretsmanager     |
# |                                    |                                            | put-secret-value で手動。手順は bootstrap/README §7） |
# | AWS 認証（CI）                     | なし（GitHub OIDC で短期クレデンシャル）   | bootstrap 層の IAM ロール 3 種                           |
# | SES SMTP/API 認証                  | ECS タスクロール（IAM ロール）             | 鍵不要。タスクロールに ses:SendEmail を付与             |
# | Cloudflare Tunnel シークレット     | Terraform state（random_id 自動生成）      | edge module が生成しトンネルに設定。人手管理不要        |
# | cloudflared run トークン           | AWS Secrets Manager（箱は Terraform 作成） | apply 後に tunnel_token 出力を手動投入 → ECS サイドカー |
# |                                    |                                            | が TUNNEL_TOKEN として参照（手順は bootstrap/README §7-5）|
#
# 非秘密の実行時 env（APP_BASE_URL 等）は本ファイル下部 + main.tf の locals で管理する。
# =============================================================================

# -----------------------------------------------------------------------------
# 基本
# -----------------------------------------------------------------------------

variable "prefix" {
  description = "全リソース名のプレフィクス。bootstrap の IAM ポリシーが mannschaft-* に限定されているため変更しないこと"
  type        = string
  default     = "mannschaft"
}

variable "domain_name" {
  description = "本番ドメイン名（Cloudflare ゾーン配下。例: mannschaft.example.com）。FE/BE 同一オリジンの入口"
  type        = string
}

# -----------------------------------------------------------------------------
# Cloudflare
# -----------------------------------------------------------------------------

variable "cloudflare_account_id" {
  description = "Cloudflare アカウント ID（ダッシュボード右下 or Workers & Pages 概要に表示）"
  type        = string
}

variable "cloudflare_zone_id" {
  description = "対象ドメインの Cloudflare ゾーン ID（ゾーンの Overview ページ右下に表示）"
  type        = string
}

# -----------------------------------------------------------------------------
# ネットワーク
# -----------------------------------------------------------------------------

variable "vpc_cidr" {
  description = "VPC の CIDR ブロック"
  type        = string
  default     = "10.0.0.0/16"
}

# -----------------------------------------------------------------------------
# インスタンスサイズ（最小構成スタート。負荷に応じて tfvars で上げる）
# -----------------------------------------------------------------------------

variable "db_instance_class" {
  description = "RDS MySQL 8.0 のインスタンスクラス"
  type        = string
  default     = "db.t4g.micro"
}

variable "cache_node_type" {
  description = "ElastiCache Valkey のノードタイプ"
  type        = string
  default     = "cache.t4g.micro"
}

variable "task_cpu" {
  description = "ECS Fargate タスクの CPU ユニット（256/512/1024/...）"
  type        = number
  default     = 512
}

variable "task_memory" {
  description = "ECS Fargate タスクのメモリ（MiB）。task_cpu と組合せ制約あり（512cpu→1024〜4096 等）"
  type        = number
  default     = 1024
}

variable "cloudflared_image" {
  description = "cloudflared サイドカーのイメージ（固定タグ必須。:latest を prod に適用しない）。更新時は Docker Hub cloudflare/cloudflared で linux/arm64 対応の最新安定タグを確認してから上げる"
  type        = string
  # 2026-07-10 時点の最新安定タグ（linux/arm64 対応を Docker Hub で確認済み）
  default = "cloudflare/cloudflared:2026.7.1"
}

# -----------------------------------------------------------------------------
# 非秘密の実行時 env（上書き・追加用）
# -----------------------------------------------------------------------------
# 標準セットは main.tf の locals でドメイン名から自動導出する:
#
#   [Spring Boot（ECS）側 — locals.app_env]
#     APP_BASE_URL               = https://<domain_name>      … BE が組み立てる絶対 URL の起点
#     MANNSCHAFT_ALLOWED_ORIGINS = https://<domain_name>      … CORS 許可オリジン（同一オリジン構成なので自ドメインのみ）
#     MANNSCHAFT_COOKIE_SECURE   = true                       … 本番は常に Secure Cookie
#
#   [Nuxt（Cloudflare Pages）側 — locals.pages_env]
#     NUXT_PUBLIC_API_BASE   = ''（空文字）                   … 同一オリジンのため相対パスで /api/** を叩く
#     NUXT_INTERNAL_API_BASE = https://<domain_name>          … SSR がサーバー側から API を叩く際の絶対 URL
#     NUXT_PUBLIC_BASE_URL   = https://<domain_name>          … OGP 等で使う公開ベース URL
#
# ここで定義する 2 変数は「標準セットに追加・上書きしたいものだけ」を入れる。

variable "app_env_extra" {
  description = "ECS（Spring Boot）に渡す追加・上書きの非秘密 env。秘密は入れないこと（秘密は SSM/Secrets Manager 経由）"
  type        = map(string)
  default     = {}
}

variable "pages_env_extra" {
  description = "Cloudflare Pages（Nuxt）に渡す追加・上書きの非秘密 env。秘密は入れないこと"
  type        = map(string)
  default     = {}
}
