import type { RanchState } from '~/types/ranch'
export function useRanchState() {
 const api = useRanchApi()
 const state = ref<RanchState | null>(null)
 const loading = ref(false)
 const failed = ref(false)
 const { handleApiError } = useErrorHandler()
 async function load() {
  loading.value = true; failed.value = false
  try { state.value = await api.state() } catch (error) { state.value = null; failed.value = true; handleApiError(error, 'RanchState') } finally { loading.value = false }
 }
 async function act(action: () => Promise<unknown>) {
  failed.value = false
  try { const result = await action(); await load(); return result } catch (error) {
   const status = (error as { statusCode?: number; status?: number }).statusCode ?? (error as { status?: number }).status
   if (status && status >= 400 && status < 500 && status !== 429) { api.command.discardRejected(); await load() }
   failed.value = true; handleApiError(error, 'RanchCommand'); throw error
  }
 }
 return { api, state, loading, failed, load, act }
}
