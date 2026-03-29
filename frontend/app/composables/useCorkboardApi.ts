import type { CorkboardResponse, CorkboardCard } from '~/types/corkboard'

interface CorkboardGroup {
  id: number
  name: string
  color: string | null
  boardId: number
}

export function useCorkboardApi() {
  const api = useApi()

  // === Personal Corkboards ===
  async function getMyBoards() {
    return api<{ data: CorkboardResponse[] }>('/api/v1/users/me/corkboards')
  }
  async function createMyBoard(body: Record<string, unknown>) {
    return api<{ data: CorkboardResponse }>('/api/v1/users/me/corkboards', { method: 'POST', body })
  }
  async function getMyBoard(id: number) {
    return api<{ data: CorkboardResponse & { cards: CorkboardCard[] } }>(
      `/api/v1/users/me/corkboards/${id}`,
    )
  }
  async function updateMyBoard(id: number, body: Record<string, unknown>) {
    return api(`/api/v1/users/me/corkboards/${id}`, { method: 'PUT', body })
  }
  async function deleteMyBoard(id: number) {
    return api(`/api/v1/users/me/corkboards/${id}`, { method: 'DELETE' })
  }

  // === Team Corkboards ===
  async function getTeamBoards(teamId: number) {
    return api<{ data: CorkboardResponse[] }>(`/api/v1/teams/${teamId}/corkboards`)
  }
  async function createTeamBoard(teamId: number, body: Record<string, unknown>) {
    return api<{ data: CorkboardResponse }>(`/api/v1/teams/${teamId}/corkboards`, {
      method: 'POST',
      body,
    })
  }
  async function getTeamBoard(teamId: number, id: number) {
    return api<{ data: CorkboardResponse & { cards: CorkboardCard[] } }>(
      `/api/v1/teams/${teamId}/corkboards/${id}`,
    )
  }
  async function updateTeamBoard(teamId: number, id: number, body: Record<string, unknown>) {
    return api(`/api/v1/teams/${teamId}/corkboards/${id}`, { method: 'PUT', body })
  }
  async function deleteTeamBoard(teamId: number, id: number) {
    return api(`/api/v1/teams/${teamId}/corkboards/${id}`, { method: 'DELETE' })
  }

  // === Organization Corkboards ===
  async function getOrgBoards(orgId: number) {
    return api<{ data: CorkboardResponse[] }>(`/api/v1/organizations/${orgId}/corkboards`)
  }
  async function createOrgBoard(orgId: number, body: Record<string, unknown>) {
    return api<{ data: CorkboardResponse }>(`/api/v1/organizations/${orgId}/corkboards`, {
      method: 'POST',
      body,
    })
  }

  // === Cards ===
  async function createCard(boardId: number, body: Record<string, unknown>) {
    return api<{ data: CorkboardCard }>(`/api/v1/corkboards/${boardId}/cards`, {
      method: 'POST',
      body,
    })
  }
  async function updateCard(boardId: number, cardId: number, body: Record<string, unknown>) {
    return api(`/api/v1/corkboards/${boardId}/cards/${cardId}`, { method: 'PUT', body })
  }
  async function deleteCard(boardId: number, cardId: number) {
    return api(`/api/v1/corkboards/${boardId}/cards/${cardId}`, { method: 'DELETE' })
  }
  async function archiveCard(boardId: number, cardId: number) {
    return api(`/api/v1/corkboards/${boardId}/cards/${cardId}/archive`, { method: 'PATCH' })
  }
  async function batchUpdateCardPositions(
    boardId: number,
    positions: Array<{ cardId: number; x: number; y: number }>,
  ) {
    return api(`/api/v1/corkboards/${boardId}/cards/batch-position`, {
      method: 'PATCH',
      body: { positions },
    })
  }

  // === Groups ===
  async function createGroup(boardId: number, body: Record<string, unknown>) {
    return api<{ data: CorkboardGroup }>(`/api/v1/corkboards/${boardId}/groups`, {
      method: 'POST',
      body,
    })
  }
  async function updateGroup(boardId: number, groupId: number, body: Record<string, unknown>) {
    return api(`/api/v1/corkboards/${boardId}/groups/${groupId}`, { method: 'PUT', body })
  }
  async function deleteGroup(boardId: number, groupId: number) {
    return api(`/api/v1/corkboards/${boardId}/groups/${groupId}`, { method: 'DELETE' })
  }
  async function addCardToGroup(boardId: number, groupId: number, cardId: number) {
    return api(`/api/v1/corkboards/${boardId}/groups/${groupId}/cards/${cardId}`, {
      method: 'POST',
    })
  }
  async function removeCardFromGroup(boardId: number, groupId: number, cardId: number) {
    return api(`/api/v1/corkboards/${boardId}/groups/${groupId}/cards/${cardId}`, {
      method: 'DELETE',
    })
  }

  return {
    getMyBoards,
    createMyBoard,
    getMyBoard,
    updateMyBoard,
    deleteMyBoard,
    getTeamBoards,
    createTeamBoard,
    getTeamBoard,
    updateTeamBoard,
    deleteTeamBoard,
    getOrgBoards,
    createOrgBoard,
    createCard,
    updateCard,
    deleteCard,
    archiveCard,
    batchUpdateCardPositions,
    createGroup,
    updateGroup,
    deleteGroup,
    addCardToGroup,
    removeCardFromGroup,
  }
}
