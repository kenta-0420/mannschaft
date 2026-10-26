import type {
  TournamentContactSpace,
  TournamentContactSpaceListResponse,
  UpdateContactSpaceVisibilityRequest,
} from '~/types/tournament'

/**
 * 大会連絡スペース（掲示板・チャット）に関するAPI composable。
 *
 * - `fetchContactSpaces` — 大会レベルの連絡スペース一覧取得
 * - `fetchDivisionContactSpaces` — ディビジョンレベルの連絡スペース一覧取得
 * - `toggleVisibility` — 主催者用の公開設定変更
 *
 * APIパス:
 * - GET  /api/v1/tournaments/{tId}/contact-spaces
 * - GET  /api/v1/tournaments/{tId}/divisions/{divId}/contact-spaces
 * - PATCH /api/v1/tournaments/{tId}/contact-spaces/{spaceId}/visibility
 * - PATCH /api/v1/tournaments/{tId}/divisions/{divId}/contact-spaces/{spaceId}/visibility
 */
export function useTournamentContact() {
  const api = useApi()

  /**
   * 大会レベルの連絡スペース一覧を取得する。
   */
  async function fetchContactSpaces(tournamentId: number): Promise<TournamentContactSpace[]> {
    const res = await api<TournamentContactSpaceListResponse>(
      `/api/v1/tournaments/${tournamentId}/contact-spaces`,
    )
    return res.data
  }

  /**
   * ディビジョンレベルの連絡スペース一覧を取得する。
   */
  async function fetchDivisionContactSpaces(
    tournamentId: number,
    divisionId: number,
  ): Promise<TournamentContactSpace[]> {
    const res = await api<TournamentContactSpaceListResponse>(
      `/api/v1/tournaments/${tournamentId}/divisions/${divisionId}/contact-spaces`,
    )
    return res.data
  }

  /**
   * 大会レベルの連絡スペースの公開設定を変更する（主催者のみ）。
   */
  async function toggleVisibility(
    tournamentId: number,
    spaceId: string,
    isPublic: boolean,
  ): Promise<void> {
    const body: UpdateContactSpaceVisibilityRequest = { isPublic }
    await api(
      `/api/v1/tournaments/${tournamentId}/contact-spaces/${spaceId}/visibility`,
      { method: 'PATCH', body },
    )
  }

  /**
   * ディビジョンレベルの連絡スペースの公開設定を変更する（主催者のみ）。
   */
  async function toggleDivisionVisibility(
    tournamentId: number,
    divisionId: number,
    spaceId: string,
    isPublic: boolean,
  ): Promise<void> {
    const body: UpdateContactSpaceVisibilityRequest = { isPublic }
    await api(
      `/api/v1/tournaments/${tournamentId}/divisions/${divisionId}/contact-spaces/${spaceId}/visibility`,
      { method: 'PATCH', body },
    )
  }

  return {
    fetchContactSpaces,
    fetchDivisionContactSpaces,
    toggleVisibility,
    toggleDivisionVisibility,
  }
}
