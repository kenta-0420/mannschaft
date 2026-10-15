/**
 * URL 用の組織 slug を、Long を要求する内部連携 API 用の数値 ID へ解決する。
 *
 * 組織詳細 API は slug を受け取り、レスポンスの numericId に内部 BIGINT ID を返す。
 * URL の slug 自体を Long 専用 API へ渡してはならない。
 */
export function useOrganizationNumericId() {
  const organizationApi = useOrganizationApi()

  async function resolveOrganizationNumericId(orgSlug: string): Promise<string> {
    const result = await organizationApi.getOrganization(orgSlug)
    const numericId = result.data.numericId
    if (!Number.isInteger(numericId) || Number(numericId) <= 0) {
      throw new Error(`Organization numeric ID is unavailable: ${orgSlug}`)
    }
    return String(numericId)
  }

  return { resolveOrganizationNumericId }
}
