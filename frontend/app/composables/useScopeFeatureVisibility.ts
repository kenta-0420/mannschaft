import { computed, watch, type MaybeRefOrGetter } from 'vue'
import type { FeatureDisplayPolicy, FeatureDisplayRole } from '~/types/feature-display'

type PolicyState = {
  status: 'loading' | 'success' | 'error'
  features: FeatureDisplayPolicy[]
  error?: unknown
}
const requests = new WeakMap<object, Map<string, Promise<void>>>()

/** 認可を変更せず、所属scopeの一般導線だけを共通の表示設定で絞る。 */
export function useScopeFeatureVisibility(
  scopeType: MaybeRefOrGetter<'team' | 'organization'>,
  slug: MaybeRefOrGetter<string | undefined | null>,
  actualRole: MaybeRefOrGetter<FeatureDisplayRole>,
  memberLens: MaybeRefOrGetter<boolean> = false,
) {
  const api = useApi()
  const auth = useAuthStore()
  const app = useNuxtApp()
  const states = useState<Record<string, PolicyState>>('scope-feature-display-policies', () => ({}))
  const key = computed(() =>
    JSON.stringify([auth.user?.id ?? null, toValue(scopeType), toValue(slug)]),
  )
  const eligible = computed(
    () =>
      !!toValue(slug) &&
      ['SUPPORTER', 'MEMBER', 'DEPUTY_ADMIN', 'ADMIN', 'SYSTEM_ADMIN'].includes(
        toValue(actualRole) ?? '',
      ),
  )
  const state = computed(() => states.value[key.value])
  const ready = computed(() => !eligible.value || state.value?.status === 'success')
  const error = computed(() => (eligible.value ? state.value?.error : undefined))
  const loading = computed(
    () => eligible.value && !['success', 'error'].includes(state.value?.status ?? 'idle'),
  )
  let pending = requests.get(app)
  if (!pending) {
    pending = new Map()
    requests.set(app, pending)
  }
  const inflight = pending

  async function refresh(force = false): Promise<void> {
    if (!eligible.value) return
    const requestKey = key.value
    if (inflight.has(requestKey)) return inflight.get(requestKey)
    if (!force && states.value[requestKey]?.status === 'success') return
    const entry: PolicyState = { status: 'loading', features: [] }
    states.value[requestKey] = entry
    const requestSlug = toValue(slug)
    const base = toValue(scopeType) === 'team' ? 'teams' : 'organizations'
    const task: Promise<void> = Promise.resolve().then(async () => {
      try {
        if (toRaw(states.value[requestKey]) !== entry) return
        const response = await api<{ data: { features: FeatureDisplayPolicy[] } }>(
          '/api/v1/' +
            base +
            '/' +
            encodeURIComponent(requestSlug ?? '') +
            '/feature-display-policies',
        )
        if (toRaw(states.value[requestKey]) !== entry) return
        states.value[requestKey] = { status: 'success', features: response.data.features }
      } catch (cause) {
        if (toRaw(states.value[requestKey]) !== entry) return
        states.value[requestKey] = { status: 'error', features: [], error: cause }
      } finally {
        if (inflight.get(requestKey) === task) inflight.delete(requestKey)
      }
    })
    inflight.set(requestKey, task)
    return task
  }

  let generation = 0

  async function save(features: FeatureDisplayPolicy[]): Promise<void> {
    const requestKey = key.value
    const requestGeneration = generation
    const base = toValue(scopeType) === 'team' ? 'teams' : 'organizations'
    await api(
      '/api/v1/' +
        base +
        '/' +
        encodeURIComponent(toValue(slug) ?? '') +
        '/feature-display-policies',
      {
        method: 'PUT',
        body: { features },
      },
    )
    if (requestKey !== key.value || requestGeneration !== generation) return
    inflight.delete(requestKey)
    await refresh(true)
    if (state.value?.status === 'error') throw state.value.error
  }

  function isVisible(featureKey?: string): boolean {
    if (!featureKey || !eligible.value) return true
    const role = toValue(actualRole)
    if (!toValue(memberLens) && ['SYSTEM_ADMIN', 'ADMIN', 'DEPUTY_ADMIN'].includes(role ?? ''))
      return true
    if (!ready.value) return false
    const setting = state.value?.features.find((feature) => feature.featureKey === featureKey)
    if (!setting) return true
    return role === 'SUPPORTER' ? setting.supporterVisible : setting.memberVisible
  }

  watch(
    [key, eligible],
    ([, enabled], previous) => {
      if (previous && previous[0] !== key.value && previous[0]) {
        generation++
        Reflect.deleteProperty(states.value, previous[0])
        inflight.delete(previous[0])
      }
      if (enabled && import.meta.client) {
        const nextKey = key.value
        // 所有画面のscope/本人変更watchが実ロールを無効化した後で取得可否を確定する。
        queueMicrotask(() => {
          if (key.value === nextKey && eligible.value) void refresh()
        })
      }
    },
    { immediate: true, flush: 'sync' },
  )
  watch(
    eligible,
    (value) => {
      if (!value) {
        Reflect.deleteProperty(states.value, key.value)
        inflight.delete(key.value)
      }
    },
    { flush: 'sync' },
  )
  return {
    features: computed(() => state.value?.features ?? []),
    ready,
    loading,
    error,
    refresh,
    save,
    isVisible,
  }
}
