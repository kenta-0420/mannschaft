import { computed, onScopeDispose, ref, shallowRef, watch } from 'vue'
import { useApi } from '~/composables/useApi'
import { useErrorReport } from '~/composables/useErrorReport'
import { useAuthStore } from '~/stores/useAuthStore'
import type { PageMeta, PagedResponse } from '~/types/api'
import type { JoinRequestResponse } from '~/types/village'

/** 認証本人の申請履歴。利用者切替を跨いだ応答を画面へ適用しない。 */
export function useVillageJoinRequestHistory() {
  const api = useApi()
  const auth = useAuthStore()
  const { captureQuiet } = useErrorReport()
  const actor = computed(() => auth.user?.id ?? null)
  const actorGeneration = ref(0)
  const requests = ref<JoinRequestResponse[]>([])
  const page = ref(0)
  const size = ref(20)
  const meta = ref<PageMeta>({ total: 0, page: 0, size: 20, totalPages: 0 })
  const loading = ref(false)
  const error = shallowRef<unknown>(null)
  let generation = 0

  // logout→同じ利用者のloginでも、logout時に旧表示と取得世代を即時無効にする。
  watch(
    actor,
    () => {
      generation++
      actorGeneration.value++
      requests.value = []
      meta.value = { total: 0, page: 0, size: size.value, totalPages: 0 }
      error.value = null
      loading.value = false
      page.value = 0
    },
    { flush: 'sync' },
  )

  async function load() {
    const requestActor = actor.value
    if (requestActor === null) return
    const requestPage = page.value
    const requestSize = size.value
    const requestGeneration = ++generation
    const isCurrent = () =>
      generation === requestGeneration &&
      actor.value === requestActor &&
      page.value === requestPage &&
      size.value === requestSize
    loading.value = true
    error.value = null
    try {
      const response = await api<PagedResponse<JoinRequestResponse>>(
        `/api/v1/village-join-requests/me?page=${requestPage}&size=${requestSize}`,
      )
      if (!isCurrent()) return
      requests.value = response.data
      meta.value = response.meta
    } catch (caught) {
      if (!isCurrent()) return
      error.value = caught
      captureQuiet(caught)
    } finally {
      if (isCurrent()) loading.value = false
    }
  }

  watch(
    [actorGeneration, page, size],
    () => {
      void load()
    },
    { immediate: true },
  )
  onScopeDispose(() => {
    generation++
  })
  return { requests, page, size, meta, loading, error, load }
}
