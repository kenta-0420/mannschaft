# =============================================================================
# app module — 契約スタブ（二番隊実装範囲）
# =============================================================================
# 責務: アプリ実行層（ALB + ACM + ECR + ECS Fargate / Spring Boot desired 1）。
#
# 二番隊が実装する主要リソース:
#   - aws_acm_certificate（var.domain_name / DNS 検証）
#       * 検証レコードの作成は edge module（Cloudflare DNS）側。
#         本 module は domain_validation_options を出力するだけ。
#       * aws_acm_certificate_validation で検証完了を待ってから ALB リスナーに紐付け
#   - aws_lb（public subnet ×2）+ HTTPS リスナー（ACM）+ HTTP→HTTPS リダイレクト
#   - aws_lb_target_group（port 8080 / target_type "ip" / health check /actuator/health）
#   - aws_ecr_repository（${var.prefix}-backend。scan_on_push 推奨）
#   - aws_ecs_cluster + aws_ecs_task_definition + aws_ecs_service（desired_count = 1）
#       * タスクは public subnet + public IP 付与で起動（NAT Gateway 代を節約）
#       * 環境変数: var.app_env（非秘密）+ DB/Valkey 接続情報から組み立て
#       * 秘密: container_definitions の secrets で
#         var.db_master_user_secret_arn / SSM Parameter Store を参照（平文を経由しない）
#   - IAM: タスク実行ロール（ECR pull / CloudWatch Logs / secrets 読取）と
#          タスクロール（SES 送信等）。名前は mannschaft-* プレフィクス必須
#          （bootstrap の tf-apply ロールが mannschaft-* しか IAM 操作できないため）
#   - aws_cloudwatch_log_group（アプリログ）
#
# 契約（variables.tf / outputs.tf）は確定済み。勝手に増減しないこと。
# =============================================================================

terraform {
  required_providers {
    aws = {
      source = "hashicorp/aws"
    }
  }
}
