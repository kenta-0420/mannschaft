import type {
  AddMemberRequest,
  CreateFolderRequest,
  TeamFriendFolderView,
  UpdateFolderRequest,
} from '~/types/social-friend'

export function useFriendFoldersApi() {
  const api = useApi()
  const { handleApiError } = useErrorHandler()

  // ─────────────────────────────────────────
  // GET /api/v1/teams/{teamId}/friend-folders — フォルダ一覧取得
  // ─────────────────────────────────────────

  async function listFolders(teamId: number) {
    return api<{ data: TeamFriendFolderView[] }>(`/api/v1/teams/${teamId}/friend-folders`)
  }

  // ─────────────────────────────────────────
  // POST /api/v1/teams/{teamId}/friend-folders — フォルダ作成
  // ─────────────────────────────────────────

  async function createFolder(teamId: number, body: CreateFolderRequest) {
    return api<{ data: TeamFriendFolderView }>(`/api/v1/teams/${teamId}/friend-folders`, {
      method: 'POST',
      body,
    })
  }

  // ─────────────────────────────────────────
  // PUT /api/v1/teams/{teamId}/friend-folders/{folderId} — フォルダ更新
  // ─────────────────────────────────────────

  async function updateFolder(teamId: number, folderId: number, body: UpdateFolderRequest) {
    return api<{ data: TeamFriendFolderView }>(
      `/api/v1/teams/${teamId}/friend-folders/${folderId}`,
      { method: 'PUT', body },
    )
  }

  // ─────────────────────────────────────────
  // DELETE /api/v1/teams/{teamId}/friend-folders/{folderId} — フォルダ削除
  // ─────────────────────────────────────────

  async function deleteFolder(teamId: number, folderId: number) {
    return api(`/api/v1/teams/${teamId}/friend-folders/${folderId}`, { method: 'DELETE' })
  }

  // ─────────────────────────────────────────
  // POST /api/v1/teams/{teamId}/friend-folders/{folderId}/members — メンバー追加
  // ─────────────────────────────────────────

  async function addMember(teamId: number, folderId: number, body: AddMemberRequest) {
    return api(`/api/v1/teams/${teamId}/friend-folders/${folderId}/members`, {
      method: 'POST',
      body,
    })
  }

  // ─────────────────────────────────────────
  // DELETE /api/v1/teams/{teamId}/friend-folders/{folderId}/members/{teamFriendId} — メンバー削除
  // ─────────────────────────────────────────

  async function removeMember(teamId: number, folderId: number, teamFriendId: number) {
    return api(
      `/api/v1/teams/${teamId}/friend-folders/${folderId}/members/${teamFriendId}`,
      { method: 'DELETE' },
    )
  }

  return {
    listFolders,
    createFolder,
    updateFolder,
    deleteFolder,
    addMember,
    removeMember,
    handleApiError,
  }
}
