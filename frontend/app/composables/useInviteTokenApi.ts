/**
 * 招待リンク（招待トークン）API。
 * チーム/組織の両スコープに対応する。
 *
 * CRUD は useTeamMembers / useOrganizationApi が個別に提供する。
 * 本 composable は両スコープ共通の Blob ダウンロード（QR PDF）を提供する。
 */
export function useInviteTokenApi() {
  /**
   * 招待 QR コード PDF を Blob で取得する。
   *
   * 招待トークンはサーバーにハッシュしか残らないため、PDF は<b>発行直後に画面が保持する平文</b>を
   * POST の body で渡して作る（URL に平文を載せない）。tokenId からは作れない。
   *
   * PDF（Blob）取得は useApi() の ofetch インスタンスが responseType:'json' に
   * 固定されるため、既存の Blob ダウンロード作法（usePropertyWorkPackageApi /
   * useShiftUtilApi）と同様に生の $fetch を使い、認証ヘッダを手動付与する。
   */
  async function downloadInviteTokenPdf(
    scopeType: 'team' | 'organization',
    scopeId: string,
    plaintext: string,
  ): Promise<Blob> {
    const config = useRuntimeConfig()
    const { accessToken } = useAuthStore()
    const base = scopeType === 'team' ? 'teams' : 'organizations'
    return $fetch<Blob>(
      `${config.public.apiBase}/api/v1/${base}/${scopeId}/invite-tokens/pdf`,
      {
        method: 'POST',
        body: { token: plaintext },
        responseType: 'blob',
        credentials: 'include',
        headers: accessToken ? { Authorization: `Bearer ${accessToken}` } : {},
      },
    )
  }

  return {
    downloadInviteTokenPdf,
  }
}
