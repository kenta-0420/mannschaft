import { computed, inject, provide, ref, shallowRef, watch, type Ref, type InjectionKey } from 'vue'
import type { TeamResponse } from '~/types/team'

const SchoolTeamContextKey: InjectionKey<ReturnType<typeof createSchoolTeamContext>> = Symbol('SchoolTeamContext')

/** 学校画面だけで共有する slug → 既存 TeamResponse の解決。資格はキャッシュしない。 */
function createSchoolTeamContext(slug: Ref<string>) {
  const api = useTeamApi()
  const team = shallowRef<TeamResponse | null>(null)
  const loading = ref(false)
  const error = shallowRef<unknown>()
  let sequence = 0
  let pending: Promise<boolean> | undefined
  const teamId = computed(() => team.value?.numericId ? String(team.value.numericId) : '')
  const template = computed(() => team.value?.location?.template ?? null)

  watch(slug, () => {
    ++sequence
    pending = undefined
    team.value = null
    error.value = undefined
    loading.value = false
  }, { flush: 'sync' })

  async function load(): Promise<boolean> {
    if (team.value) return true
    if (pending) return pending
    const requestedSlug = slug.value
    const seq = ++sequence
    const current = () => seq === sequence && requestedSlug === slug.value
    loading.value = true
    error.value = undefined
    pending = (async () => {
      try {
        const result = await api.getTeam(requestedSlug)
        if (!current()) return false
        if (typeof result.data.numericId !== 'number' || !Number.isSafeInteger(result.data.numericId) || result.data.numericId <= 0) {
          throw new Error('Team numericId missing')
        }
        team.value = result.data
        return true
      } catch (cause) {
        if (current()) error.value = cause
        return false
      } finally {
        if (current()) {
          loading.value = false
          pending = undefined
        }
      }
    })()
    return pending
  }

  return { teamId, template, loading, error, load }
}

export function useSchoolTeamContext(slug: Ref<string>) {
  return inject(SchoolTeamContextKey, null) ?? createSchoolTeamContext(slug)
}

export function provideSchoolTeamContext(slug: Ref<string>) {
  const context = createSchoolTeamContext(slug)
  provide(SchoolTeamContextKey, context)
  return context
}
