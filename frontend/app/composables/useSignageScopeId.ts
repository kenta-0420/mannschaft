import type { Ref } from 'vue'

/** route slugを数値scopeIdに解決し、切替中や古い応答から管理画面を生成させない。 */
export function useSignageScopeId(scopeType: 'TEAM' | 'ORGANIZATION', scopeRef: Ref<string>) {
  const { resolveScopeId } = useActivityScopeId()
  const scopeId = ref<number | null>(null)
  const resolving = ref(false)
  const failed = ref(false)
  let generation = 0

  async function resolve() {
    const request = ++generation
    const requestedRef = scopeRef.value
    // 非同期解決より先に旧scopeを無効化し、旧Managerの操作も残さない。
    scopeId.value = null
    resolving.value = true
    failed.value = false
    try {
      const id = requestedRef ? await resolveScopeId(scopeType, requestedRef) : null
      if (request !== generation || requestedRef !== scopeRef.value) return
      if (id !== null && Number.isSafeInteger(id) && id > 0) scopeId.value = id
      else failed.value = true
    }
    catch {
      if (request === generation) failed.value = true
    }
    finally {
      if (request === generation) resolving.value = false
    }
  }

  watch(scopeRef, () => { void resolve() }, { immediate: true, flush: 'sync' })
  onScopeDispose(() => { ++generation })

  return { scopeId, resolving, failed, retry: resolve }
}
