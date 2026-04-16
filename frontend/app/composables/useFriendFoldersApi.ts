import type {
  AddFolderMemberRequest,
  CreateFolderRequest,
  TeamFriendFolderView,
  UpdateFolderRequest,
} from '~/types/friendFolders'

/**
 * F01.5 フレンドフォルダ管理 composable。
 *
 * 提供するエンドポイント（設計書 §5）:
 * - GET    /api/v1/teams/{id}/friend-folders — 一覧
 * - POST   /api/v1/teams/{id}/friend-folders — 作成
 * - PUT    /api/v1/teams/{id}/friend-folders/{folderId} — 更新
 * - DELETE /api/v1/teams/{id}/friend-folders/{folderId} — 論理削除
 * - POST   /api/v1/teams/{id}/friend-folders/{folderId}/members — メンバー追加
 * - DELETE /api/v1/teams/{id}/friend-folders/{folderId}/members/{teamFriendId} — メンバー削除
 */
export function useFriendFoldersApi() {
  const api = useApi()

  /**
   * フレンドフォルダ一覧を取得する。
   *
   * @param teamId 自チーム ID
   * @returns フォルダ一覧
   */
  async function listFolders(teamId: number): Promise<TeamFriendFolderView[]> {
    const result = await api<{ data: TeamFriendFolderView[] }>(
      `/api/v1/teams/${teamId}/friend-folders`,
    )
    return result.data
  }

  /**
   * フレンドフォルダを新規作成する。1 チームあたり最大 20 個。
   *
   * @param teamId 自チーム ID
   * @param req    作成リクエスト
   * @returns 作成されたフォルダ
   */
  async function createFolder(
    teamId: number,
    req: CreateFolderRequest,
  ): Promise<TeamFriendFolderView> {
    const result = await api<{ data: TeamFriendFolderView }>(
      `/api/v1/teams/${teamId}/friend-folders`,
      { method: 'POST', body: req },
    )
    return result.data
  }

  /**
   * フレンドフォルダを更新する（全量更新）。
   *
   * @param teamId   自チーム ID
   * @param folderId フォルダ ID
   * @param req      更新リクエスト
   * @returns 更新後のフォルダ
   */
  async function updateFolder(
    teamId: number,
    folderId: number,
    req: UpdateFolderRequest,
  ): Promise<TeamFriendFolderView> {
    const result = await api<{ data: TeamFriendFolderView }>(
      `/api/v1/teams/${teamId}/friend-folders/${folderId}`,
      { method: 'PUT', body: req },
    )
    return result.data
  }

  /**
   * フレンドフォルダを論理削除する。関連メンバーレコードは CASCADE 削除。
   * フレンド関係自体は維持される。
   *
   * @param teamId   自チーム ID
   * @param folderId フォルダ ID
   */
  async function deleteFolder(teamId: number, folderId: number): Promise<void> {
    await api(`/api/v1/teams/${teamId}/friend-folders/${folderId}`, {
      method: 'DELETE',
    })
  }

  /**
   * フォルダにフレンドチームを追加する。重複追加は 409。
   *
   * @param teamId   自チーム ID
   * @param folderId フォルダ ID
   * @param req      追加リクエスト
   */
  async function addFolderMember(
    teamId: number,
    folderId: number,
    req: AddFolderMemberRequest,
  ): Promise<void> {
    await api(`/api/v1/teams/${teamId}/friend-folders/${folderId}/members`, {
      method: 'POST',
      body: req,
    })
  }

  /**
   * フォルダから指定のフレンドチームを取り外す。フレンド関係自体は維持される。
   *
   * @param teamId       自チーム ID
   * @param folderId     フォルダ ID
   * @param teamFriendId フレンド関係 ID
   */
  async function removeFolderMember(
    teamId: number,
    folderId: number,
    teamFriendId: number,
  ): Promise<void> {
    await api(
      `/api/v1/teams/${teamId}/friend-folders/${folderId}/members/${teamFriendId}`,
      { method: 'DELETE' },
    )
  }

  return {
    listFolders,
    createFolder,
    updateFolder,
    deleteFolder,
    addFolderMember,
    removeFolderMember,
  }
}
