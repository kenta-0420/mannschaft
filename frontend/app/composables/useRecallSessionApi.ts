// AR公開DTOの最小投影。正式OpenAPI生成時に生成型へ統合する。
import type { ReflectionEntryResponse, RecallSelfRating } from '~/types/reflection'
import type { ApiResponse } from '~/types/api'
import type { RanchCommandSnapshot, useRanchCommand } from '~/composables/useRanchCommand'
export interface RecallSessionPrompt {
 id: string
 kind: 'TERM_CARD' | 'FREE_RECALL'
 heading: string | null
 promptSide: 'TERM' | 'MEANING' | null
 promptText: string | null
 maxAnswerLength: number
}
export interface RecallSessionAnswer {
 promptId: string
 state: 'ANSWERED' | 'FORGOT'
 text: string | null
}
export interface RecallSessionView {
 id: string
 status: 'STARTED' | 'COMPLETED' | 'CANCELLED'
 version: string
 prompts: RecallSessionPrompt[]
 answers: RecallSessionAnswer[]
 original: ReflectionEntryResponse | null
}
// AR専用scopeで同じkey/bodyを再送し、他機能の未確定命令と分離する。
export function useRecallSessionApi(command: ReturnType<typeof useRanchCommand>) {
 const api = useApi()
 const privateRead = useRanchPrivateRead()
 const base = '/api/v1/me/reflections/recall-sessions'
 async function mutate(path: string, method: RanchCommandSnapshot['method'], body: unknown) {
  try {
   return await command.execute({ path, method, body }, async (snapshot, signal) => {
   const response = await api<ApiResponse<RecallSessionView>>(snapshot.path, {
    method: snapshot.method, body: snapshot.body as Record<string, unknown>, retry: 0, signal,
    headers: { 'Idempotency-Key': snapshot.key },
   })
    return response.data
   })
  } catch (error) {
   // 既存diagnosis/birth command分類に合わせる。429/5xx/通信不明は固定keyを保持。
   const value = error !== null && typeof error === 'object' ? error : null
   const statusCode = value && 'statusCode' in value ? value.statusCode : undefined
   const fallback = value && 'status' in value ? value.status : undefined
   const status = typeof statusCode === 'number' ? statusCode : typeof fallback === 'number' ? fallback : null
   if (status !== null && status >= 400 && status < 500 && status !== 429) command.discardRejected()
   throw error
  }
 }
 return {
  command,
  isCurrent: privateRead.isCurrent,
  session: async (id: string) => (await privateRead.read<ApiResponse<RecallSessionView>>(`${base}/${id}`)).data,
  start: (entryId: string) => mutate(`/api/v1/me/reflections/entries/${entryId}/recall-sessions`, 'POST', {}),
  save: (session: RecallSessionView, answers: RecallSessionAnswer[]) => mutate(`${base}/${session.id}/answers`, 'PUT', { version: session.version, answers }),
  complete: (session: RecallSessionView, answers: RecallSessionAnswer[], selfRating: RecallSelfRating) => mutate(`${base}/${session.id}/complete`, 'POST', { version: session.version, answers, selfRating }),
  cancel: (session: RecallSessionView) => mutate(`${base}/${session.id}/cancel`, 'POST', { version: session.version }),
  retry: () => {
   const snapshot = command.pending.value
   if (!snapshot) throw new Error('COMMAND_NOT_PENDING')
   return mutate(snapshot.path, snapshot.method, snapshot.body)
  },
 }
}