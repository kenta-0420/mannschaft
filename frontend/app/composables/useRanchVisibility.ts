import { getCurrentScope, onScopeDispose, watch } from 'vue'

// 表示設定は既存dashboard設定を正本にし、牧場の開始commandを再送しない。
export function useRanchVisibility() {
 const api = useApi()
 const auth = useAuthStore()
 let generation = 0
 let owner = auth.user?.id ?? null
 let ownerGeneration = 0
 let runId = 0
 let controller: AbortController | null = null
 function cancel() {
  runId += 1
  const previous = controller
  controller = null
  previous?.abort()
 }
 watch(() => auth.user?.id ?? null, value => {
  generation += 1
  if (owner === null && value !== null) {
   owner = value
   ownerGeneration = generation
  }
  cancel()
 }, { flush: 'sync' })
 if (getCurrentScope()) onScopeDispose(cancel)
 async function setVisible(visible: boolean) {
  if (auth.user === null) throw new Error('COMMAND_NOT_AUTHENTICATED')
  if (auth.user.id !== owner || generation !== ownerGeneration) throw new Error('COMMAND_ACCOUNT_CHANGED')
  cancel()
  const run = new AbortController()
  const currentRun = runId
  controller = run
  const current = () => auth.user?.id === owner && generation === ownerGeneration && runId === currentRun && controller === run
  try {
   const response = await api<{ data: { widgetKey: string; visible: boolean; sortOrder: number }[] }>('/api/v1/dashboard/widgets', { query: { scopeType: 'personal' }, signal: run.signal })
   if (!current()) throw new Error('COMMAND_ACCOUNT_CHANGED')
   const widgets = response.data.map(item => ({ widgetKey: item.widgetKey, isVisible: item.widgetKey === 'PERSONAL_DINOSAUR_RANCH' ? visible : item.visible, sortOrder: item.sortOrder }))
   if (!widgets.some(item => item.widgetKey === 'PERSONAL_DINOSAUR_RANCH')) widgets.push({ widgetKey: 'PERSONAL_DINOSAUR_RANCH', isVisible: visible, sortOrder: widgets.length })
   await api('/api/v1/dashboard/widgets', { method: 'PUT', body: { scopeType: 'personal', widgets }, signal: run.signal })
   if (!current()) throw new Error('COMMAND_ACCOUNT_CHANGED')
   await refreshNuxtData('dashboard-widgets:personal:0')
   if (!current()) throw new Error('COMMAND_ACCOUNT_CHANGED')
  } catch (error) {
   if (!current()) throw new Error('COMMAND_ACCOUNT_CHANGED')
   throw error
  } finally {
   if (current()) controller = null
  }
 }
 return { setVisible }
}
