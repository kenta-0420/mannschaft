import type { ApiResponse } from '~/types/api'
import type { BirthConfirmation, BirthProfile } from '~/types/ranch'
export function useBirthProfile() {
 const api = useApi(); const privateRead = useRanchPrivateRead(); const command = useRanchCommand('birth-profile'); const base = '/api/v1/me/birth-profile'
 async function get() { return (await privateRead.read<ApiResponse<BirthProfile>>(base)).data }
 async function save(profile: BirthProfile) {
  await command.execute({ path: base, method: 'PUT', body: profile }, async (snapshot, signal) => api<ApiResponse<{revision: string}>>(base, { method: 'PUT', body: snapshot.body as BirthProfile, retry: 0, signal, headers: { 'Idempotency-Key': snapshot.key } }))
  return get()
 }
 async function confirm(revision: string) {
  return command.execute({ path: `${base}/confirmations`, method: 'POST', body: { revision, useConfirmed: true } }, async (snapshot, signal) => (await api<ApiResponse<BirthConfirmation>>(snapshot.path, { method: 'POST', body: snapshot.body as Record<string,unknown>, retry: 0, signal, headers: { 'Idempotency-Key': snapshot.key } })).data)
 }
 async function retryPending(): Promise<BirthProfile | BirthConfirmation> {
  const snapshot = command.pending.value
  if (!snapshot) throw new Error('COMMAND_NOT_PENDING')
  const response = await command.execute(snapshot, async (original, signal) => (await api<ApiResponse<BirthConfirmation | { revision: string }>>(original.path, { method: original.method, body: original.body as Record<string, unknown>, retry: 0, signal, headers: { 'Idempotency-Key': original.key } })).data)
  return snapshot.method === 'PUT' ? get() : response as BirthConfirmation
 }
 return { command, get, save, confirm, retryPending, isCurrent: privateRead.isCurrent }
}
