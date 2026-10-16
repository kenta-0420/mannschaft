/** 結果不明時は body/key を凍結し、利用者の明示操作で同じ要求を再送する。 */
export function useLoyaltyMutation<T>() {
  const pending = ref(false)
  const uncertain = ref(false)
  const result = shallowRef<T | null>(null)
  let attempt: (() => Promise<T>) | null = null
  async function send() {
    if (pending.value || !attempt) return
    pending.value = true
    try {
      result.value = await attempt()
      uncertain.value = false
      attempt = null
      return result.value
    } catch (error) {
      const status = (error as { statusCode?: number; status?: number }).statusCode
        ?? (error as { status?: number }).status
      uncertain.value = !status || status >= 500
      if (!uncertain.value) attempt = null
      throw error
    } finally { pending.value = false }
  }
  function start<B extends object>(body: B, operation: (body: B, key: string) => Promise<T>) {
    if (pending.value || uncertain.value) return Promise.resolve(undefined)
    const snapshot = structuredClone(body)
    const key = crypto.randomUUID()
    attempt = () => operation(snapshot, key)
    return send()
  }
  return { pending, uncertain, result, start, retry: send }
}
