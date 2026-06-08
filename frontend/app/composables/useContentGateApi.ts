import type { GateCheckResponse } from '~/types/payment'

/**
 * F08.9 P4b: ペイウォール判定 API クライアント。
 *
 * <p>Backend Controller: {@code ContentGateCheckController}（GET /api/v1/content-gates/check）。
 * viewer（受益者キー）は BE 側で SecurityUtils.getCurrentUserId() により確定するため、
 * FE 側では contentType と contentId のみを渡す。</p>
 *
 * <p>コンテンツ種別定数（{@code ContentGateType}）:
 * POST / FILE / ANNOUNCEMENT / SCHEDULE</p>
 */
export function useContentGateApi() {
  const api = useApi()

  /**
   * 指定コンテンツに対するログインユーザー本人のペイウォール解錠可否を判定する。
   *
   * @param contentType  コンテンツ種別（"POST" / "FILE" / "ANNOUNCEMENT" / "SCHEDULE" 等）
   * @param contentId    コンテンツ ID（number）
   * @returns ペイウォール判定結果（accessible / titleHidden / requiredItems）
   */
  async function checkAccess(
    contentType: string,
    contentId: number,
  ): Promise<{ data: GateCheckResponse }> {
    const q = new URLSearchParams()
    q.set('contentType', contentType)
    q.set('contentId', String(contentId))
    return api<{ data: GateCheckResponse }>(`/api/v1/content-gates/check?${q.toString()}`)
  }

  return { checkAccess }
}
