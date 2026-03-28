import type { CorkboardResponse, CorkboardCard } from '~/types/corkboard'

export function useCorkboardApi() {
  const api = useApi()

  async function getMyBoards() { return api<{ data: CorkboardResponse[] }>('/api/v1/corkboards/me') }
  async function getTeamBoards(teamId: number) { return api<{ data: CorkboardResponse[] }>(`/api/v1/teams/${teamId}/corkboards`) }
  async function getBoard(boardId: number) { return api<{ data: CorkboardResponse & { cards: CorkboardCard[] } }>(`/api/v1/corkboards/${boardId}`) }
  async function createBoard(body: Record<string, unknown>) { return api<{ data: CorkboardResponse }>('/api/v1/corkboards/me', { method: 'POST', body }) }
  async function updateBoard(boardId: number, body: Record<string, unknown>) { return api(`/api/v1/corkboards/${boardId}`, { method: 'PUT', body }) }
  async function deleteBoard(boardId: number) { return api(`/api/v1/corkboards/${boardId}`, { method: 'DELETE' }) }
  async function createCard(boardId: number, body: Record<string, unknown>) { return api<{ data: CorkboardCard }>(`/api/v1/corkboards/${boardId}/cards`, { method: 'POST', body }) }
  async function updateCard(boardId: number, cardId: number, body: Record<string, unknown>) { return api(`/api/v1/corkboards/${boardId}/cards/${cardId}`, { method: 'PUT', body }) }
  async function deleteCard(boardId: number, cardId: number) { return api(`/api/v1/corkboards/${boardId}/cards/${cardId}`, { method: 'DELETE' }) }
  async function moveCard(boardId: number, cardId: number, x: number, y: number) { return api(`/api/v1/corkboards/${boardId}/cards/${cardId}/position`, { method: 'PATCH', body: { x, y } }) }

  return { getMyBoards, getTeamBoards, getBoard, createBoard, updateBoard, deleteBoard, createCard, updateCard, deleteCard, moveCard }
}
