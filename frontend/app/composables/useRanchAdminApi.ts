import type { ApiResponse } from '~/types/api'
import type { RanchCommandSnapshot } from './useRanchCommand'
import type { RanchCareRulePublicationRequest, RanchOperationalControls, RanchOperationalControlsRequest, RanchPolicyPublicationRequest, RanchPublicationAck, RanchRewardSourceType, RanchSourceHealth, RanchSourceRetryAck } from '~/types/ranch-admin'

export function useRanchAdminApi() {
 const api = useApi()
 const command = useRanchCommand('ranch-admin')
 const privateRead = useRanchPrivateRead()
 const base = '/api/v1/system-admin/ranch'
 async function mutate<T>(path: string, method: RanchCommandSnapshot['method'], body: unknown): Promise<T> {
  try {
   return await command.execute({ path, method, body }, async (snapshot, signal) => {
    const result = await api<ApiResponse<T>>(snapshot.path, {
     method: snapshot.method, body: snapshot.body as Record<string, unknown>, retry: 0, signal,
     headers: { 'Idempotency-Key': snapshot.key },
    })
    if (result?.data == null) throw new Error('COMMAND_RESPONSE_UNKNOWN')
    return result.data
   })
  } catch (error) {
   const status = (error as { statusCode?: number; status?: number })?.statusCode ?? (error as { status?: number })?.status
   // 確定した拒否だけ解放する。通信不明は同key/bodyで再送し、旧scopeは新本人を変更しない。
   const code = (error as { data?: { error?: { code?: string } } })?.data?.error?.code
   // RANCH_004 は保存前の事前確認拒否。一般の503には拡張せず、不確定な保存は同じキーで再送する。
   if ((status && status >= 400 && status < 500 && status !== 408 && status !== 429)
    || (status === 503 && code === 'RANCH_004')) command.discardRejected()
   throw error
  }
 }
 async function read<T>(path: string): Promise<T> {
  const response = await privateRead.read<ApiResponse<T>>(path)
  if (response?.data == null) throw new Error('PRIVATE_RESPONSE_UNKNOWN')
  return response.data
 }
 return {
  command, isCurrent: privateRead.isCurrent,
  controls: () => read<RanchOperationalControls>(`${base}/operational-controls`),
  saveControls: (body: RanchOperationalControlsRequest) => mutate<RanchOperationalControls>(`${base}/operational-controls`, 'PUT', body),
  publishCare: (body: RanchCareRulePublicationRequest) => mutate<RanchPublicationAck<RanchCareRulePublicationRequest>>(`${base}/care-rules`, 'POST', body),
  publishPolicy: (body: RanchPolicyPublicationRequest) => mutate<RanchPublicationAck<RanchPolicyPublicationRequest>>(`${base}/policies`, 'POST', body),
  health: () => read<RanchSourceHealth>(`${base}/outbox-health`),
  retrySource: (source: RanchRewardSourceType, eventId: string, reasonCode: string) => mutate<RanchSourceRetryAck>(`${base}/outboxes/${source}/${encodeURIComponent(eventId)}/retry`, 'POST', { reasonCode }),
  retryPending: () => {
   const snapshot = command.pending.value
   if (!snapshot) throw new Error('COMMAND_NOT_PENDING')
   return mutate<unknown>(snapshot.path, snapshot.method, snapshot.body)
  },
 }
}
