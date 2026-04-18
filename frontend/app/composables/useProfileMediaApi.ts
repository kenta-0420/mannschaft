import type {
  ProfileMediaCommitResponse,
  ProfileMediaRole,
  ProfileMediaScope,
  ProfileMediaUploadUrlRequest,
  ProfileMediaUploadUrlResponse,
} from '~/types/profileMedia'

/**
 * F01.6 プロフィールメディア API composable。
 * ユーザー・チーム・組織のアイコン・バナー画像のアップロード／コミット／削除フローを提供する。
 */
export function useProfileMediaApi() {
  const api = useApi()

  /**
   * スコープと役割に対応する API ベースパスを構築する。
   *
   * @param scope スコープ種別（"user" | "team" | "organization"）
   * @param scopeId スコープ ID（user の場合は null）
   * @param role メディアロール（"icon" | "banner"）
   * @returns API ベースパス文字列
   */
  function buildBasePath(
    scope: ProfileMediaScope,
    scopeId: number | null,
    role: ProfileMediaRole,
  ): string {
    switch (scope) {
      case 'user':
        return `/api/v1/users/me/profile-media/${role}`
      case 'team':
        return `/api/v1/teams/${scopeId}/profile-media/${role}`
      case 'organization':
        return `/api/v1/organizations/${scopeId}/profile-media/${role}`
    }
  }

  /**
   * アップロード URL を発行する。
   * POST {basePath}/upload-url
   *
   * @param scope スコープ種別
   * @param scopeId スコープ ID（user の場合は null）
   * @param role メディアロール
   * @param request アップロード URL 発行リクエスト
   * @returns アップロード URL 発行レスポンス
   */
  async function generateUploadUrl(
    scope: ProfileMediaScope,
    scopeId: number | null,
    role: ProfileMediaRole,
    request: ProfileMediaUploadUrlRequest,
  ): Promise<ProfileMediaUploadUrlResponse> {
    const result = await api<{ data: ProfileMediaUploadUrlResponse }>(
      `${buildBasePath(scope, scopeId, role)}/upload-url`,
      { method: 'POST', body: request },
    )
    return result.data
  }

  /**
   * メディアをコミット（DB 反映）する。
   * PUT {basePath}
   *
   * @param scope スコープ種別
   * @param scopeId スコープ ID（user の場合は null）
   * @param role メディアロール
   * @param r2Key R2 オブジェクトキー
   * @returns コミット完了レスポンス
   */
  async function commitMedia(
    scope: ProfileMediaScope,
    scopeId: number | null,
    role: ProfileMediaRole,
    r2Key: string,
  ): Promise<ProfileMediaCommitResponse> {
    const result = await api<{ data: ProfileMediaCommitResponse }>(
      buildBasePath(scope, scopeId, role),
      { method: 'PUT', body: { r2Key } },
    )
    return result.data
  }

  /**
   * メディアを削除する。
   * DELETE {basePath}
   *
   * @param scope スコープ種別
   * @param scopeId スコープ ID（user の場合は null）
   * @param role メディアロール
   */
  async function deleteMedia(
    scope: ProfileMediaScope,
    scopeId: number | null,
    role: ProfileMediaRole,
  ): Promise<void> {
    await api(buildBasePath(scope, scopeId, role), { method: 'DELETE' })
  }

  /**
   * アップロードからコミットまでのフルフローを実行する。
   * 1. generateUploadUrl() でアップロード URL を発行
   * 2. fetch() で R2 に直接 PUT
   * 3. commitMedia() でバックエンドの DB に反映
   *
   * @param scope スコープ種別
   * @param scopeId スコープ ID（user の場合は null）
   * @param role メディアロール
   * @param file アップロードするファイル
   * @param onProgress 進捗コールバック（PUT 完了時に 100 が渡される）
   * @returns コミット完了レスポンス
   */
  async function uploadAndCommit(
    scope: ProfileMediaScope,
    scopeId: number | null,
    role: ProfileMediaRole,
    file: File,
    onProgress?: (progress: number) => void,
  ): Promise<ProfileMediaCommitResponse> {
    // 1. アップロード URL 発行
    const urlResponse = await generateUploadUrl(scope, scopeId, role, {
      contentType: file.type || 'image/jpeg',
      fileSize: file.size,
    })

    // 2. R2 に直接 PUT
    const r2Response = await fetch(urlResponse.uploadUrl, {
      method: 'PUT',
      body: file,
      headers: { 'Content-Type': file.type || 'image/jpeg' },
    })
    if (!r2Response.ok) {
      throw new Error(`R2 アップロード失敗: ${r2Response.status} ${r2Response.statusText}`)
    }
    onProgress?.(100)

    // 3. コミット（DB 反映）
    return commitMedia(scope, scopeId, role, urlResponse.r2Key)
  }

  return {
    generateUploadUrl,
    commitMedia,
    deleteMedia,
    uploadAndCommit,
  }
}
