import type { ApiResponse } from '~/types/api'
import type { BirthConfirmation, CursorPage, DiagnosisResult, DiagnosisSession } from '~/types/ranch'
export function useDiagnosisApi() {
 const api = useApi(); const privateRead = useRanchPrivateRead(); const command = useRanchCommand('diagnosis')
 const base = '/api/v1/me/diagnoses'
 async function mutate<T>(path: string, body: unknown, method: 'POST' | 'PUT' = 'POST') {
  try {
   return await command.execute({ path, method, body }, async (snapshot, signal) => (await api<ApiResponse<T>>(snapshot.path, { method: snapshot.method, body: snapshot.body as Record<string, unknown>, retry: 0, signal, headers: { 'Idempotency-Key': snapshot.key } })).data)
  } catch (error) {
   const status = (error as {statusCode?:number;status?:number}).statusCode ?? (error as {status?:number}).status
   if (status && status >= 400 && status < 500 && status !== 429) command.discardRejected()
   throw error
  }
 }
 return { command, isCurrent: privateRead.isCurrent,
  retryPending: async <T>() => {
   const snapshot = command.pending.value
   if (!snapshot) throw new Error('COMMAND_NOT_PENDING')
   return mutate<T>(snapshot.path, snapshot.body, snapshot.method as 'POST' | 'PUT')
  },
  start: () => mutate<DiagnosisSession>(`${base}/sessions`, {}),
  session: async (id: string) => (await privateRead.read<ApiResponse<DiagnosisSession>>(`${base}/sessions/${id}`)).data,
  save: (session: DiagnosisSession, answers: DiagnosisSession['answers']) => mutate<DiagnosisSession>(`${base}/sessions/${session.id}/answers`, { version: session.version, answers }, 'PUT'),
  complete: (session: DiagnosisSession, tieAnswers: { axisId: string; value: number }[]) => mutate<DiagnosisSession>(`${base}/sessions/${session.id}/complete`, { version: session.version, answerRevision: session.answerRevision, tieAnswers }),
  cancel: (session: DiagnosisSession) => mutate<DiagnosisSession>(`${base}/sessions/${session.id}/cancel`, { version: session.version }),
  birth: (confirmation: BirthConfirmation) => mutate<DiagnosisResult>(`${base}/birth-style-results`, { confirmationRef: confirmation.confirmationRef }),
  results: (method?: string, cursor?: string) => privateRead.read<CursorPage<DiagnosisResult>>(`${base}/results`, { method, cursor, limit: 20 }),
  result: async (id: string) => (await privateRead.read<ApiResponse<DiagnosisResult>>(`${base}/results/${id}`)).data,
 }
}
