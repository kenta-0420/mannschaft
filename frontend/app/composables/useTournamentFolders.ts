import type { SharedFolder } from '~/types/filesharing'

/**
 * F08.7.1 大会ファイル置き場 composable
 * BE APIパス: /api/v1/tournaments/{tournamentId}/folders
 *            /api/v1/tournaments/{tournamentId}/divisions/{divisionId}/folders
 */
export function useTournamentFolders() {
  const api = useApi()

  // === 大会ルートフォルダ ===

  async function listFolders(tournamentId: number) {
    return api<{ data: SharedFolder[] }>(`/api/v1/tournaments/${tournamentId}/folders`)
  }

  async function createFolder(tournamentId: number, name: string, parentId?: number) {
    return api<{ data: SharedFolder }>(`/api/v1/tournaments/${tournamentId}/folders`, {
      method: 'POST',
      body: { name, parentId: parentId ?? null },
    })
  }

  // === ディビジョン別フォルダ ===

  async function listDivisionFolders(tournamentId: number, divisionId: number) {
    return api<{ data: SharedFolder[] }>(
      `/api/v1/tournaments/${tournamentId}/divisions/${divisionId}/folders`,
    )
  }

  async function createDivisionFolder(
    tournamentId: number,
    divisionId: number,
    name: string,
    parentId?: number,
  ) {
    return api<{ data: SharedFolder }>(
      `/api/v1/tournaments/${tournamentId}/divisions/${divisionId}/folders`,
      {
        method: 'POST',
        body: { name, parentId: parentId ?? null },
      },
    )
  }

  return {
    listFolders,
    createFolder,
    listDivisionFolders,
    createDivisionFolder,
  }
}
