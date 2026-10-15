import type { SecurityScanStatusResponse } from '~/types/system-admin'

/**
 * セキュリティスキャン状態 API クライアント。
 *
 * バックエンド: {@code GET /api/v1/system-admin/security-scan/status}
 * SYSTEM_ADMIN ロールでのみアクセス可能。
 *
 * GitHub Actions の OWASP Dependency-Check 週次スキャン（security-scan.yml）の
 * 最新実行状態をシステム管理画面に表示するために使用する。
 */
export function useSecurityScanApi() {
  const api = useApi()

  /**
   * OWASP Dependency-Check スキャンの最新実行状態を取得する。
   *
   * @returns スキャン状態（conclusion / runUrl / runAt）
   */
  async function fetchStatus() {
    return api<{ data: SecurityScanStatusResponse }>(
      '/api/v1/system-admin/security-scan/status',
    )
  }

  return { fetchStatus }
}
