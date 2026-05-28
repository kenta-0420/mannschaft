import type {
  ArchiveFolderTreeResponse,
  BulletinArchiveFolder,
  BulletinThreadResponse,
  CreateArchiveFolderRequest,
  DeleteArchiveFolderResponse,
  UpdateArchiveFolderRequest,
} from '~/types/bulletin'

/**
 * 掲示板 保管庫（アーカイブ）フォルダ関連 API（設計書 F05.1 §4 / §5）。
 *
 * すべてスコープ別エンドポイント:
 * `/api/v1/{scopeType}/{scopeId}/bulletin/archive/...`
 * （{scopeType} = `teams` | `organizations`）
 *
 * フォルダ CRUD・移動・スレッド振り分けは ADMIN / DEPUTY_ADMIN のみ（BE が認可）。
 * 閲覧（ツリー取得・スレッド一覧）は所属メンバー全員。
 */
export function useBulletinArchiveFolders() {
  const api = useApi()

  /** {scopeType} のパスセグメント（TEAM→teams / ORGANIZATION→organizations）。 */
  function scopeSegment(scopeType: string): string {
    return scopeType === 'ORGANIZATION' || scopeType === 'organizations'
      ? 'organizations'
      : 'teams'
  }

  function basePath(scopeType: string, scopeId: string | number): string {
    return `/api/v1/${scopeSegment(scopeType)}/${scopeId}/bulletin/archive`
  }

  /**
   * 保管庫フォルダ一覧をツリー構造で取得する。
   * data: ルートフォルダ配列（children に子を再帰ネスト）、meta: 集計情報。
   */
  async function getFolderTree(
    scopeType: string,
    scopeId: string | number,
  ): Promise<ArchiveFolderTreeResponse> {
    return api<ArchiveFolderTreeResponse>(`${basePath(scopeType, scopeId)}/folders`)
  }

  /** 保管庫フォルダを作成する。 */
  async function createFolder(
    scopeType: string,
    scopeId: string | number,
    body: CreateArchiveFolderRequest,
  ): Promise<{ data: BulletinArchiveFolder }> {
    return api<{ data: BulletinArchiveFolder }>(`${basePath(scopeType, scopeId)}/folders`, {
      method: 'POST',
      body,
    })
  }

  /**
   * 保管庫フォルダを更新・移動する。
   * `parentFolderId` を指定するとサブツリーごと移動（null でルートへ移動）。
   */
  async function updateFolder(
    scopeType: string,
    scopeId: string | number,
    folderId: string,
    body: UpdateArchiveFolderRequest,
  ): Promise<{ data: BulletinArchiveFolder }> {
    return api<{ data: BulletinArchiveFolder }>(
      `${basePath(scopeType, scopeId)}/folders/${folderId}`,
      { method: 'PUT', body },
    )
  }

  /**
   * 保管庫フォルダを削除する（論理削除）。
   * 配下スレッドは保管庫直下へ退避、子フォルダは親へ繰り上げ。
   */
  async function deleteFolder(
    scopeType: string,
    scopeId: string | number,
    folderId: string,
  ): Promise<{ data: DeleteArchiveFolderResponse }> {
    return api<{ data: DeleteArchiveFolderResponse }>(
      `${basePath(scopeType, scopeId)}/folders/${folderId}`,
      { method: 'DELETE' },
    )
  }

  /**
   * 保管庫内のアーカイブ済みスレッド一覧を取得する。
   *
   * @param folderId
   *   - undefined / null: 保管庫直下（未分類）
   *   - `'all'`: 全保管庫スレッド横断
   *   - UUID 文字列: そのフォルダ直下に絞り込み
   */
  async function getArchiveThreads(
    scopeType: string,
    scopeId: string | number,
    params?: { folderId?: string | null; page?: number; size?: number },
  ): Promise<{
    data: BulletinThreadResponse[]
    meta: { page: number; size: number; totalElements: number; totalPages: number }
  }> {
    const query = new URLSearchParams()
    if (params?.folderId) query.set('folder_id', params.folderId)
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 20))
    return api(`${basePath(scopeType, scopeId)}/threads?${query.toString()}`)
  }

  /**
   * アーカイブ済みスレッドを別の保管庫フォルダへ振り分ける。
   * `archiveFolderId` に null を渡すと保管庫直下（未分類）へ移動。
   */
  async function moveThreadToFolder(
    scopeType: string,
    scopeId: string | number,
    threadId: number,
    archiveFolderId: string | null,
  ): Promise<{ data: BulletinThreadResponse }> {
    return api<{ data: BulletinThreadResponse }>(
      `${basePath(scopeType, scopeId)}/threads/${threadId}/folder`,
      { method: 'PATCH', body: { archiveFolderId } },
    )
  }

  return {
    getFolderTree,
    createFolder,
    updateFolder,
    deleteFolder,
    getArchiveThreads,
    moveThreadToFolder,
  }
}
