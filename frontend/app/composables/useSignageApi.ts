import type {
  SignageScreen,
  SignageSlot,
  SignageToken,
  CreateSignageScreenRequest,
  UpdateSignageScreenRequest,
  AddSignageSlotRequest,
} from '~/types/signage'

export function useSignageApi() {
  const api = useApi()

  // === Screens ===
  async function getScreens(scopeType: string, scopeId: number) {
    return api<{ data: SignageScreen[] }>(
      `/api/signage/screens?scopeType=${encodeURIComponent(scopeType)}&scopeId=${scopeId}`,
    )
  }

  async function getScreen(id: number) {
    return api<{ data: SignageScreen }>(`/api/signage/screens/${id}`)
  }

  async function createScreen(body: CreateSignageScreenRequest) {
    return api<{ data: SignageScreen }>('/api/signage/screens', {
      method: 'POST',
      body,
    })
  }

  async function updateScreen(id: number, body: UpdateSignageScreenRequest) {
    return api<{ data: SignageScreen }>(`/api/signage/screens/${id}`, {
      method: 'PUT',
      body,
    })
  }

  async function deleteScreen(id: number) {
    return api(`/api/signage/screens/${id}`, { method: 'DELETE' })
  }

  // === Slots ===
  async function getSlots(screenId: number) {
    return api<{ data: SignageSlot[] }>(`/api/signage/screens/${screenId}/slots`)
  }

  async function addSlot(screenId: number, body: AddSignageSlotRequest) {
    return api<{ data: SignageSlot }>(`/api/signage/screens/${screenId}/slots`, {
      method: 'POST',
      body,
    })
  }

  async function updateSlot(
    screenId: number,
    slotId: number,
    body: Partial<AddSignageSlotRequest>,
  ) {
    return api<{ data: SignageSlot }>(`/api/signage/screens/${screenId}/slots/${slotId}`, {
      method: 'PUT',
      body,
    })
  }

  async function deleteSlot(screenId: number, slotId: number) {
    return api(`/api/signage/screens/${screenId}/slots/${slotId}`, { method: 'DELETE' })
  }

  async function reorderSlots(screenId: number, slotIds: number[]) {
    return api(`/api/signage/screens/${screenId}/slots/reorder`, {
      method: 'POST',
      body: { slotIds },
    })
  }

  // === Tokens ===
  async function getTokens(screenId: number) {
    return api<{ data: SignageToken[] }>(`/api/signage/screens/${screenId}/tokens`)
  }

  async function issueToken(screenId: number) {
    return api<{ data: SignageToken }>(`/api/signage/screens/${screenId}/tokens`, {
      method: 'POST',
    })
  }

  async function deleteToken(tokenId: number) {
    return api(`/api/signage/screens/tokens/${tokenId}`, { method: 'DELETE' })
  }

  // === Emergency ===
  async function sendEmergency(screenId: number, message: string) {
    return api(`/api/signage/screens/${screenId}/emergency`, {
      method: 'POST',
      body: { message },
    })
  }

  return {
    getScreens,
    getScreen,
    createScreen,
    updateScreen,
    deleteScreen,
    getSlots,
    addSlot,
    updateSlot,
    deleteSlot,
    reorderSlots,
    getTokens,
    issueToken,
    deleteToken,
    sendEmergency,
  }
}
