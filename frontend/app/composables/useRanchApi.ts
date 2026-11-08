import type { ApiResponse } from '~/types/api'
import type { AssignmentRequest, AssignmentResult, CursorPage, FeedingResult, HatchResponse, InteractionResult, OwnerSummary, RanchInventory, RanchLegacySyncResult, RanchRecord, RanchSettings, RanchSlot, RanchState, ShopItem } from '~/types/ranch'
import type { RanchCommandSnapshot } from './useRanchCommand'
export function useRanchApi() {
 const api = useApi()
 const command = useRanchCommand('ranch')
 const base = '/api/v1/me/ranch'
 async function mutate<T>(path: string, method: RanchCommandSnapshot['method'], body?: unknown, version?: string): Promise<T> {
  return command.execute({ path, method, body, version }, async (snapshot, signal) => {
   const response = await api<ApiResponse<T>>(snapshot.path, { method: snapshot.method, body: snapshot.body as Record<string, unknown> | undefined, retry: 0, signal, headers: { 'Idempotency-Key': snapshot.key, ...(snapshot.version ? { 'If-Match': snapshot.version } : {}) } })
   return response?.data
  })
 }
 return {
  command,
  retryPending: async () => {
   const snapshot = command.pending.value
   if (!snapshot) return
   return mutate<unknown>(snapshot.path, snapshot.method, snapshot.body, snapshot.version)
  },
  state: async (signal?: AbortSignal) => (await api<ApiResponse<RanchState>>(base, { cache: 'no-store', signal })).data,
  start: () => mutate<RanchState>(base, 'POST', {}),
  settings: (body: Omit<RanchSettings,'isVisible' | 'viewMode'>) => mutate<RanchSettings>(`${base}/settings`, 'PUT', body),
  participation: (action: 'pause' | 'resume', version: string) => mutate<OwnerSummary>(`${base}/${action}`, 'POST', { version }),
  assignment: (body: AssignmentRequest) => mutate<AssignmentResult>(`${base}/assignment`, 'PUT', body),
  hatch: (version: string, name: string) => mutate<HatchResponse>(`${base}/hatch`, 'POST', { version, name, nameConfirmed: true }),
  feed: (version: string) => mutate<FeedingResult>(`${base}/feeding`, 'POST', { version }),
  touch: (version: string) => mutate<InteractionResult>(`${base}/interactions`, 'POST', { kind: 'TOUCH', version }),
  shop: async () => (await api<ApiResponse<ShopItem[]>>(`${base}/shop`, { cache: 'no-store' })).data,
  purchase: (skuKey: string, priceVersion: string, version: string) => mutate(`${base}/purchases`, 'POST', { skuKey, priceVersion, version }),
  inventory: (cursor?: string) => api<CursorPage<RanchInventory>>(`${base}/collectibles`, { query: { cursor, limit: 20 }, cache: 'no-store' }),
  syncLegacy: (afterAwardId: string) => mutate<RanchLegacySyncResult>(`${base}/collectibles/sync`, 'POST', { afterAwardId }),
  records: (cursor?: string) => api<CursorPage<RanchRecord>>(`${base}/records`, { query: { cursor, limit: 20 }, cache: 'no-store' }),
  place: (slot: RanchSlot, inventoryId: string) => mutate<RanchSlot>(`${base}/room/slots/${slot.slotKey}`, 'PUT', { inventoryId, version: slot.version }),
  remove: (slot: RanchSlot) => mutate<undefined>(`${base}/room/slots/${slot.slotKey}`, 'DELETE', undefined, slot.version),
  commandResult: (id: string) => api(`${base}/commands/${id}`, { cache: 'no-store' }),
 }
}
