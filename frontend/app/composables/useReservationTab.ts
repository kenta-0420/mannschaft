import type { Ref } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, type RouteLocationNormalized } from 'vue-router'

const RESERVATION_TABS: readonly string[] = ['book', 'list', 'manage', 'closure']

/** 予約ページの選択だけをURLへ保持する。タブ切替はブラウザー履歴を増やさない。 */
export function useReservationTab(
  managementAllowed: Ref<boolean>,
  permissionsResolved: Ref<boolean>,
) {
  const route = useRoute()
  const router = useRouter()
  const initialPath = route.path
  const initialSlug = String(route.params.slug ?? '')
  const navigatingAway = ref(false)
  let pendingNavigation: RouteLocationNormalized | undefined
  // routeは非同期middleware完了まで旧URLを指す。遷移開始を先に捉え、旧URLへの書き戻しを止める。
  onBeforeRouteLeave((to) => {
    pendingNavigation = to
    navigatingAway.value = true
  })
  onBeforeRouteUpdate((to) => {
    if (to.path !== initialPath || String(to.params.slug ?? '') !== initialSlug) {
      pendingNavigation = to
      navigatingAway.value = true
    }
  })
  function resumeTabSync(to: RouteLocationNormalized) {
    // 古い遷移のCANCELLED・例外で、より新しい遷移の抑止を解除しない。
    if (to !== pendingNavigation) return
    pendingNavigation = undefined
    navigatingAway.value = false
  }
  const removeAfterEach = router.afterEach((to, _from, failure) => {
    // abortだけでなく、リダイレクトで同じ画面へ戻った場合も操作可能にする。
    if (failure || (to.path === initialPath && String(to.params.slug ?? '') === initialSlug)) {
      resumeTabSync(to)
    }
  })
  // guardのthrowはafterEachへ来ないため、例外の経路でも同じ遷移だけを解除する。
  const removeErrorHandler = router.onError((_error, to) => resumeTabSync(to))
  onScopeDispose(() => {
    removeAfterEach()
    removeErrorHandler()
  })
  const isCurrentPage = computed(
    () => route.path === initialPath && String(route.params.slug ?? '') === initialSlug,
  )
  const requestedTab = computed(() => {
    if (!isCurrentPage.value || typeof route.query.tab !== 'string') return 0
    return Math.max(RESERVATION_TABS.indexOf(route.query.tab), 0)
  })
  // URLの非同期更新を待つ間も元のrefと同じ即時性を保ち、PrimeVueの内部選択とずらさない。
  const selectedTab = ref(requestedTab.value)
  watch(
    requestedTab,
    (value) => {
      selectedTab.value = value
    },
    { flush: 'sync' },
  )
  let queryUpdate = 0

  const activeTab = computed<number>({
    get: () => (selectedTab.value > 1 && !managementAllowed.value ? 0 : selectedTab.value),
    set: (value) => {
      if (navigatingAway.value || !isCurrentPage.value || !Number.isInteger(value)) return
      const tab = RESERVATION_TABS[value]
      if (!tab || (value > 1 && !managementAllowed.value)) return
      const previous = selectedTab.value
      selectedTab.value = value
      if (
        previous === value &&
        (route.query.tab === tab || (value === 0 && route.query.tab == null))
      )
        return
      const update = ++queryUpdate
      void router
        .replace({ path: route.path, query: { ...route.query, tab }, hash: route.hash })
        .finally(() => {
          // 拒否されたquery遷移はURLの値へ戻す。古い完了で新しい選択を巻き戻さない。
          if (update === queryUpdate && isCurrentPage.value) selectedTab.value = requestedTab.value
        })
    },
  })

  // 権限取得の初期状態・失敗では管理queryを壊さず、正常に確定した権限だけで正規化する。
  watch(
    [requestedTab, managementAllowed, permissionsResolved, navigatingAway],
    () => {
      if (
        !navigatingAway.value &&
        permissionsResolved.value &&
        !managementAllowed.value &&
        requestedTab.value > 1
      ) {
        activeTab.value = 0
      }
    },
    { immediate: true },
  )

  return activeTab
}
