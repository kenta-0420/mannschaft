import { getCurrentScope, onScopeDispose, ref, watch } from 'vue'
import type { RanchState } from '~/types/ranch'

export function useRanchState() {
 const api = useRanchApi()
 const auth = useAuthStore()
 const state = ref<RanchState | null>(null)
 const loading = ref(false)
 const failed = ref(false)
 const { handleApiError } = useErrorHandler()
 let generation = 0
 let requestId = 0
 let controller: AbortController | null = null
 function cancelRead() {
  requestId += 1
  const previous = controller
  controller = null
  previous?.abort()
 }
 watch(() => auth.user?.id ?? null, () => {
  generation += 1
  state.value = null
  loading.value = false
  failed.value = false
  cancelRead()
 }, { flush: 'sync' })
 if (getCurrentScope()) onScopeDispose(cancelRead)
 async function load() {
  const owner = auth.user?.id
  if (owner === undefined) return
  cancelRead()
  const runId = requestId
  const runGeneration = generation
  const run = new AbortController()
  controller = run
  const current = () => auth.user?.id === owner && generation === runGeneration && requestId === runId && controller === run
  loading.value = true
  failed.value = false
  try {
   const result = await api.state(run.signal)
   if (current()) state.value = result
  } catch (error) {
   if (!current()) return
   state.value = null
   failed.value = true
   handleApiError(error, 'RanchState')
  } finally {
   if (current()) {
    loading.value = false
    controller = null
   }
  }
 }
 async function act<T>(action: () => Promise<T>) {
  const owner = auth.user?.id
  const runGeneration = generation
  const current = () => owner !== undefined && auth.user?.id === owner && generation === runGeneration
  if (!current()) throw new Error('COMMAND_NOT_AUTHENTICATED')
  failed.value = false
  try {
   const result = await action()
   if (!current()) throw new Error('COMMAND_ACCOUNT_CHANGED')
   await load()
   if (!current()) throw new Error('COMMAND_ACCOUNT_CHANGED')
   return result
  } catch (error) {
   if (!current()) throw new Error('COMMAND_ACCOUNT_CHANGED')
   const status = (error as { statusCode?: number; status?: number }).statusCode ?? (error as { status?: number }).status
   if (status && status >= 400 && status < 500 && status !== 429) {
    api.command.discardRejected()
    await load()
    if (!current()) throw new Error('COMMAND_ACCOUNT_CHANGED')
   }
   failed.value = true
   handleApiError(error, 'RanchCommand')
   throw error
  }
 }
 return { api, state, loading, failed, load, act }
}
