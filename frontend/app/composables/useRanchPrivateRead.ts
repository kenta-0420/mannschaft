import { getCurrentScope, onScopeDispose, watch } from 'vue'

// 出生/診断の私的GETを本人と世代に結び、取得値は共有state/payloadへ保存しない。
// 使用componentは本人変更時にprivate scopeを再生成し、旧instanceのownerを変更しない。
export function useRanchPrivateRead() {
 const api = useApi()
 const auth = useAuthStore()
 let owner = auth.user?.id ?? null
 let generation = 0
 let ownerGeneration = 0
 let disposed = false
 const requests = new Set<AbortController>()
 function invalidate() {
  const previous = [...requests]
  requests.clear()
  for (const controller of previous) controller.abort()
 }
 watch(() => auth.user?.id ?? null, value => {
  generation += 1
  if (owner === null && value !== null) {
   owner = value
   ownerGeneration = generation
  }
  invalidate()
 }, { flush: 'sync' })
 if (getCurrentScope()) onScopeDispose(() => {
  disposed = true
  generation += 1
  invalidate()
 })
 const isCurrent = () => !disposed && owner !== null && auth.user?.id === owner && generation === ownerGeneration
 async function read<T>(path: string, query?: Record<string, string | number | undefined>): Promise<T> {
  const currentOwner = isCurrent
  if (!currentOwner()) throw new Error(owner === null ? 'COMMAND_NOT_AUTHENTICATED' : 'COMMAND_ACCOUNT_CHANGED')
  const controller = new AbortController()
  requests.add(controller)
  const current = () => currentOwner() && requests.has(controller) && !controller.signal.aborted
  try {
   const response = await api<T>(path, { query, cache: 'no-store', signal: controller.signal })
   if (!current()) throw new Error('COMMAND_ACCOUNT_CHANGED')
   return response
  } catch (error) {
   if (!current()) throw new Error('COMMAND_ACCOUNT_CHANGED')
   throw error
  } finally {
   requests.delete(controller)
  }
 }
 return { read, isCurrent }
}
