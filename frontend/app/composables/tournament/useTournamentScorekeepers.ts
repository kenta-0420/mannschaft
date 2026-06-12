// F08.7 順位UI Wave B-3: 大会スコアキーパー指名管理 API クライアント。
// 主催組織 ADMIN が「当該大会のスコア入力を許可するユーザー」を指名・解除・一覧する。
// BE: TournamentScorekeeperController（#1464・すべて主催組織 ADMIN / SYSTEM_ADMIN 限定）。
//   GET    .../scorekeepers          → ScorekeeperResponse[]（ApiResponse でラップ）
//   POST   .../scorekeepers          → 201 ScorekeeperResponse（既存指名は冪等）
//   DELETE .../scorekeepers/{skId}   → 204
//
// 既存 useTournamentBracket と同じく薄いラッパーとし、通知（成功/失敗トースト）や
// エラー文言は呼び出し側 UI が握る（症状を隠さず 403/404/409 を i18n で提示する責務は UI 側）。
import type { ScorekeeperResponse, CreateScorekeeperRequest } from '~/types/tournament'

export function useTournamentScorekeepers() {
  const api = useApi()
  const base = (orgId: string, tId: number) =>
    `/api/v1/organizations/${orgId}/tournaments/${tId}/scorekeepers`

  /** 指名一覧を取得する。BE は ApiResponse で { data: ScorekeeperResponse[] } を返す。 */
  async function listScorekeepers(orgId: string, tId: number) {
    return api<{ data: ScorekeeperResponse[] }>(base(orgId, tId))
  }

  /** スコアキーパーを指名する。既に指名済みの場合 BE は冪等に既存を返す。 */
  async function addScorekeeper(orgId: string, tId: number, userId: number) {
    const body: CreateScorekeeperRequest = { userId }
    return api<{ data: ScorekeeperResponse }>(base(orgId, tId), { method: 'POST', body })
  }

  /** 指名を解除する（skId = 指名 ID / UUIDv7）。BE は 204 No Content。 */
  async function removeScorekeeper(orgId: string, tId: number, skId: string) {
    return api(`${base(orgId, tId)}/${skId}`, { method: 'DELETE' })
  }

  return {
    listScorekeepers,
    addScorekeeper,
    removeScorekeeper,
  }
}
