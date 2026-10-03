import type { ApiResponse } from '~/types/api'
import type { BirthConfirmation, BirthProfile } from '~/types/ranch'
export function useBirthProfile() {
 const api = useApi(); const command = useRanchCommand(); const base = '/api/v1/me/birth-profile'
 async function get() { return (await api<ApiResponse<BirthProfile>>(base, { cache: 'no-store' })).data }
 async function save(profile: BirthProfile) {
  await command.execute({ path: base, method: 'PUT', body: profile }, async snapshot => api<ApiResponse<{revision: string}>>(base, { method: 'PUT', body: snapshot.body as BirthProfile, retry: 0, headers: { 'Idempotency-Key': snapshot.key } }))
  return get()
 }
 async function confirm(revision: string) {
  return command.execute({ path: `${base}/confirmations`, method: 'POST', body: { revision, useConfirmed: true } }, async snapshot => (await api<ApiResponse<BirthConfirmation>>(snapshot.path, { method: 'POST', body: snapshot.body as Record<string,unknown>, retry: 0, headers: { 'Idempotency-Key': snapshot.key } })).data)
 }
 async function retryPending(): Promise<BirthProfile | BirthConfirmation> {
  const snapshot = command.pending.value
  if (!snapshot) throw new Error('COMMAND_NOT_PENDING')
  const response = await command.execute(snapshot, async original => (await api<ApiResponse<BirthConfirmation | { revision: string }>>(original.path, { method: original.method, body: original.body as Record<string, unknown>, retry: 0, headers: { 'Idempotency-Key': original.key } })).data)
  return snapshot.method === 'PUT' ? get() : response as BirthConfirmation
 }
 return { command, get, save, confirm, retryPending }
}
