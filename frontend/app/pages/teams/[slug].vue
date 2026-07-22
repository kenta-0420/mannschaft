<script setup lang="ts">
/**
 * チーム詳細「永続シェル」親レイアウトルート。
 *
 * # 役割（永続シェル方式 / SPA・村 pages/villages/[id].vue を範とする）
 *  Nuxt 3 では `pages/teams/[slug].vue` を置くと `pages/teams/[slug]/*.vue` の親になる。
 *  本ファイルはチーム詳細の「常駐シェル」として以下を 1 度だけ解決し、子タブへ provide する:
 *    - チームデータ（onMounted 取得・slug redirect フォールバック）
 *    - 権限（useRoleAccess）・ウィジェット可視性（useDashboardWidgetVisibility）
 *    - 予約モジュール有効フラグ・サポーターフォロー状態
 *    - TeamPageHeader の常駐描画 + follow/leave アクションの親集約
 *    - 管理者レンズ（adminLens）と、管理ルート滞在時のレンズ OFF リダイレクト
 *
 *  `<NuxtPage/>`（ScopePageShell の default slot 経由）でタブ本体だけを差し替えるため、
 *  タブ遷移時にヘッダ・タブ・サイドバーは再マウントされず、白画面ローディングが出ない。
 *
 * # 注意
 *  - `key: route => route.fullPath` は付けない（付けると子遷移で親が再マウントし崩壊）。
 *  - pageTransition(out-in) 対応でルート直下は単一 <div>。
 */
import type { FetchError } from 'ofetch'
import type { ViewerRole } from '~/types/dashboard'
import type { ScopeTab } from '~/types/scopeShell'
import type { TeamResponse } from '~/types/team'
import TeamSidebar from '~/components/TeamSidebar.vue'
import { provideTeamShellContext } from '~/composables/useTeamShellContext'
import { resolveSlugRedirectPath } from '~/utils/slugRedirect'

definePageMeta({
  // 親シェルには middleware を付けない。付けると子ルート全体に継承され、
  // 認証不要の公開ページ（例 organizations/[slug]/teams/search=公開店舗検索,
  // recruitment-listings/new 等）まで認証必須化してしまう（F15.4 退行の根治）。
  // 認証は各シェル子ページ（index/info/... やウィジェット遷移先）が個別に持つ。
  layout: 'default',
})

const route = useRoute()
const { t } = useI18n()
const teamApi = useTeamApi()
const notification = useNotification()
const { handleApiError } = useErrorHandler()
const { templateLabel, visibilityLabel } = useScopeLabels()

const teamSlug = computed(() => String(route.params.slug))

const {
  roleName,
  loading: roleLoading,
  loadPermissions,
  isAdmin,
  isAdminOrDeputy,
} = useRoleAccess('team', teamSlug)

const {
  settings: widgetVisibilitySettings,
  fetch: fetchWidgetVisibility,
} = useDashboardWidgetVisibility('team', teamSlug)

const reservationEnabled = ref(false)

async function fetchReservationEnabled() {
  try {
    const { getTeamModules } = useModuleApi()
    const res = await getTeamModules(teamSlug.value)
    reservationEnabled.value = res.data.some(m => m.moduleSlug === 'reservation' && m.isEnabled)
  }
  catch {
    // 取得失敗はモジュール未有効と同義（false フォールバック）
    reservationEnabled.value = false
  }
}

// =============================================================================
// サポーターフォロー
// =============================================================================
const followStatus = ref<'NONE' | 'PENDING' | 'APPROVED'>('NONE')
const followLoading = ref(false)
const showCancelSupporterConfirm = ref(false)

async function fetchFollowStatus() {
  if (roleName.value) return
  try {
    const res = await teamApi.getFollowStatus(teamSlug.value)
    followStatus.value = res.data.status
  }
  catch {
    followStatus.value = 'NONE'
  }
}

async function applySupporter() {
  followLoading.value = true
  try {
    await teamApi.followTeam(teamSlug.value)
    const res = await teamApi.getFollowStatus(teamSlug.value)
    followStatus.value = res.data.status
    notification.success(
      followStatus.value === 'APPROVED'
        ? t('common.scopeShell.supporter_registered')
        : t('common.scopeShell.supporter_applied'),
    )
  }
  catch (error) {
    handleApiError(error, 'サポーター申請')
  }
  finally {
    followLoading.value = false
  }
}

async function cancelSupporter() {
  followLoading.value = true
  try {
    await teamApi.unfollowTeam(teamSlug.value)
    followStatus.value = 'NONE'
    showCancelSupporterConfirm.value = false
    notification.success(t('common.scopeShell.supporter_canceled'))
  }
  catch (error) {
    handleApiError(error, 'サポーター解除')
  }
  finally {
    followLoading.value = false
  }
}

// =============================================================================
// チームデータ + slug redirect
// =============================================================================
const team = ref<TeamResponse | null>(null)
const loading = ref(false)
const showLeaveConfirm = ref(false)

// 管理者レンズ（true=管理者ビュー, false=メンバービュー）。管理者は既定で管理者ビュー。
const adminLens = ref(true)

const effectiveViewerRole = computed<ViewerRole>(() =>
  adminLens.value ? ((roleName.value as ViewerRole | null) ?? 'PUBLIC') : 'MEMBER',
)

const displayName = computed(
  () => team.value?.basicInfo?.nickname1 || team.value?.basicInfo?.name || '',
)

async function fetchTeam() {
  loading.value = true
  try {
    const result = await teamApi.getTeam(teamSlug.value)
    team.value = result.data
  }
  catch (error) {
    // 404 のときは「旧 slug → 新 slug の 301 移動」かもしれないので解決を試みる（村方式・BE #1542）。
    if ((error as FetchError)?.response?.status === 404 && await tryRedirectMovedSlug()) {
      return
    }
    handleApiError(error, 'チーム詳細取得')
  }
  finally {
    loading.value = false
  }
}

/**
 * 現 slug が旧 slug（MOVED）なら新 slug の同一パスへ 301 遷移する。
 * 遷移した場合は true を返す（呼び出し元はそれ以上のエラー表示を行わない）。
 */
async function tryRedirectMovedSlug(): Promise<boolean> {
  const { resolveSlug } = useSlugRedirect()
  const target = await resolveSlugRedirectPath(useRoute().path, resolveSlug)
  if (!target) return false
  await navigateTo(
    { path: target, query: useRoute().query, hash: useRoute().hash },
    { redirectCode: 301, replace: true },
  )
  return true
}

async function leaveTeam() {
  try {
    await teamApi.leaveTeam(teamSlug.value)
    notification.success(t('teamShell.action.left'))
    navigateTo('/dashboard')
  }
  catch (error) {
    handleApiError(error, 'チーム退出')
  }
  finally {
    showLeaveConfirm.value = false
  }
}

/** 状態同期用の再取得（follow/leave 後など）。 */
async function refresh() {
  await Promise.all([fetchTeam(), loadPermissions()])
  await fetchFollowStatus()
}

// =============================================================================
// アクティブタブ導出（ルート末尾セグメント → タブ key）
// =============================================================================
/** ルート末尾セグメント（kebab）→ タブ key。未知（slug 自身・schedule 等）は dashboard。 */
const SEGMENT_TO_TAB: Record<string, string> = {
  info: 'info',
  members: 'members',
  invites: 'invites',
  supporters: 'supporters',
  modules: 'modules',
  reservations: 'reservations',
  nav: 'nav',
}

/**
 * 永続シェル（ヘッダ＋タブ）で包む対象セグメント。
 *
 * `pages/teams/[slug].vue` は teams/[slug] 配下の全ページ（schedule / chat / budget …
 * 約 100 ページ・多くは layout:'team' の独立ページ）の親ルート record になる。
 * そのため無条件にシェルを描画すると、全ページにチームヘッダ＋タブが被さり大規模退行する。
 *
 * シェル化対象は以下の 2 系統に限定する:
 *  1. 8 タブのルート（info / members / invites / supporters / modules / reservations / nav
 *     ＋ダッシュボード=slug 自身）
 *  2. ダッシュボードのウィジェット遷移先（ScopeDashboard.vue の team向け scopeLinks が正本）。
 *     schedule / todos / timeline / bulletin / blog / chat / member-profiles / activities /
 *     gallery / circulation / surveys / member-info / tournaments / match-analytics / projects
 *
 * これ以外の子ルート（settings 配下 / admin console / 深いネスト等）は bare <NuxtPage/> の
 * ままにして各ページの layout:'team' の見た目を変えない。
 */
const SHELL_SEGMENTS = new Set([
  // --- 8 タブ ---
  'info',
  'members',
  'invites',
  'supporters',
  'modules',
  'reservations',
  'nav',
  // --- ウィジェット遷移先（ScopeDashboard.vue scopeLinks 正本・15 セグメント） ---
  'schedule',
  'todos',
  'timeline',
  'bulletin',
  'blog',
  'chat',
  'member-profiles',
  'activities',
  'gallery',
  'circulation',
  'surveys',
  'member-info',
  'tournaments',
  'match-analytics',
  'projects',
])

/**
 * パス全体のセグメント配列（先頭 '' は除去済み。例 ['teams', '{slug}', 'schedule', '123']）。
 */
const pathSegments = computed<string[]>(() =>
  route.path.replace(/\/+$/, '').split('/').filter(Boolean),
)

/**
 * SHELL_SEGMENTS に一致する最初のセグメント（無ければ null）。
 *
 * # slug 衝突・深ネストの根治
 *  照合対象は index 2 以降（= 'teams' と '{slug}' を除いた残り）に限定する。
 *  こうしないと slug 自身がタブ名（info/members/schedule 等）と一致した場合に
 *  slug セグメントがヒットして activeTab が誤る。slice(2) で slug を照合から外す。
 */
const matchedShellSegment = computed<string | null>(() => {
  for (const seg of pathSegments.value.slice(2)) {
    if (SHELL_SEGMENTS.has(seg)) return seg
  }
  return null
})

/** ちょうど `/teams/{slug}`（タブ未指定・ダッシュボード）か。 */
const isDashboardRoute = computed<boolean>(() =>
  pathSegments.value.length === 2 && pathSegments.value[1] === teamSlug.value,
)

const isShellRoute = computed<boolean>(() =>
  isDashboardRoute.value || matchedShellSegment.value !== null,
)

const activeTab = computed<string>(() => {
  const seg = matchedShellSegment.value
  if (!seg) return 'dashboard'
  // SEGMENT_TO_TAB 未登録のウィジェット遷移先（schedule 等）は dashboard をハイライト（AC-2）。
  return SEGMENT_TO_TAB[seg] ?? 'dashboard'
})

// =============================================================================
// シェルデータのロード（シェル対象ルートに居るときだけ）
// =============================================================================
/** シェル描画に必要なデータを一括ロード（重複ロード防止に teamLoaded で番人）。 */
const teamLoaded = ref(false)
// 15 秒 watchdog: team / 権限が settle しない場合にスピナーの下へ「時間がかかっています」＋再試行を
// 追加表示する（読み込み自体は継続）。無限スピナーで固着させないための番人。
const shellSlowLoading = ref(false)
const SHELL_WATCHDOG_MS = 15_000
let shellWatchdogTimer: ReturnType<typeof setTimeout> | null = null

function clearShellWatchdog() {
  if (shellWatchdogTimer !== null) {
    clearTimeout(shellWatchdogTimer)
    shellWatchdogTimer = null
  }
}

async function loadShellData() {
  if (teamLoaded.value) return
  teamLoaded.value = true
  shellSlowLoading.value = false
  clearShellWatchdog()
  shellWatchdogTimer = setTimeout(() => {
    if (loading.value || roleLoading.value) shellSlowLoading.value = true
  }, SHELL_WATCHDOG_MS)
  try {
    await Promise.all([fetchTeam(), loadPermissions()])
    await fetchFollowStatus()
    // ウィジェット可視性設定と予約モジュール有効フラグを並列取得（失敗は無音 fallback）
    fetchWidgetVisibility().catch(() => {})
    fetchReservationEnabled()
  }
  finally {
    shellSlowLoading.value = false
    clearShellWatchdog()
  }
}

/**
 * シェルデータ取得の再試行（team=null のエラー面 / watchdog から呼ぶ）。
 * teamLoaded を戻して番人を解除してから再ロードする。
 */
function retryShellData() {
  teamLoaded.value = false
  team.value = null
  void loadShellData()
}

onBeforeUnmount(clearShellWatchdog)

// シェル対象ルート（8 タブ）に居るときだけデータをロードする。
// 非シェルルート（schedule / chat 等 約 100 ページ）ではロードしない＝従来と同じ挙動。
onMounted(() => {
  if (isShellRoute.value) void loadShellData()
})

// 非シェル → シェルへの client 遷移（例: schedule から dashboard タブへ）で初回ロードする。
watch(isShellRoute, (shell) => {
  if (shell) void loadShellData()
})

// 永続シェルを維持したまま別チームへ遷移した場合（同一親ルート record）に再取得する。
// useRoleAccess / useDashboardWidgetVisibility は内部で teamSlug を watch して再取得するが、
// team 本体・follow・予約フラグは本ページ側で明示的に再ロードする必要がある。
watch(teamSlug, () => {
  teamLoaded.value = false
  team.value = null
  followStatus.value = 'NONE'
  reservationEnabled.value = false
  if (isShellRoute.value) void loadShellData()
})

/** 管理系タブ key（レンズ OFF・非管理者では滞在させない）。 */
const ADMIN_ONLY_SEGMENTS = new Set(['invites', 'supporters', 'modules'])

/**
 * 管理者レンズ OFF、または管理権限を失った状態で管理ルートに滞在している場合は
 * ダッシュボード（/teams/{slug}）へ戻す（旧 index.vue の activeTab リセット相当）。
 */
watch([adminLens, isAdminOrDeputy, () => route.path], () => {
  if (ADMIN_ONLY_SEGMENTS.has(activeTab.value) && (!adminLens.value || !isAdminOrDeputy.value)) {
    navigateTo(`/teams/${teamSlug.value}`)
  }
})

// =============================================================================
// タブ定義（権限出し分けは visible に移植）
// =============================================================================
const tabs = computed<ScopeTab[]>(() => {
  const base = `/teams/${teamSlug.value}`
  return [
    {
      key: 'dashboard',
      to: base,
      icon: 'pi pi-th-large',
      labelKey: 'teamShell.tab.dashboard',
    },
    {
      key: 'info',
      to: `${base}/info`,
      icon: 'pi pi-info-circle',
      labelKey: 'teamShell.tab.info',
    },
    {
      key: 'members',
      to: `${base}/members`,
      icon: 'pi pi-users',
      labelKey: 'teamShell.tab.members',
    },
    {
      key: 'invites',
      to: `${base}/invites`,
      icon: 'pi pi-envelope',
      labelKey: 'teamShell.tab.invites',
      visible: isAdminOrDeputy.value && adminLens.value,
    },
    {
      key: 'supporters',
      to: `${base}/supporters`,
      icon: 'pi pi-heart',
      labelKey: 'teamShell.tab.supporters',
      visible: isAdmin.value && (team.value?.visibility?.supporterEnabled ?? false) && adminLens.value,
    },
    {
      key: 'modules',
      to: `${base}/modules`,
      icon: 'pi pi-sliders-h',
      labelKey: 'teamShell.tab.modules',
      visible: isAdmin.value && adminLens.value,
    },
    {
      key: 'reservations',
      to: `${base}/reservations`,
      icon: 'pi pi-calendar-clock',
      labelKey: 'reservation.tab.team_page',
      visible: !!roleName.value && reservationEnabled.value,
    },
    {
      key: 'nav',
      to: `${base}/nav`,
      icon: 'pi pi-compass',
      labelKey: 'nav.tab',
      visible: !!roleName.value,
    },
  ]
})

// =============================================================================
// 子タブへ provide（TeamDetailInfo の双方向 emit 用ミューテータも含む）
// =============================================================================
/**
 * 親 team ref を子から部分更新するミューテータ群。
 * TeamPageHeader の icon/banner 更新・TeamDetailInfo の mapEmbed/regionCodes 更新を
 * ここへ集約し、子タブ（info.vue）とヘッダの双方から同じ関数を使う。
 */
const teamMutators = {
  updateTeamIcon: (url: string | null) => { if (team.value?.metadata) team.value.metadata.iconUrl = url },
  updateTeamBanner: (url: string | null) => { if (team.value?.metadata) team.value.metadata.bannerUrl = url },
  updateTeamMapEmbed: (url: string | null) => { if (team.value?.metadata) team.value.metadata.mapEmbedUrl = url },
  updateTeamRegionCodes: (pc: string | null, cc: string | null) => {
    if (team.value?.location) {
      team.value.location.prefectureCode = pc
      team.value.location.cityCode = cc
    }
  },
}

provideTeamShellContext({
  team: computed(() => team.value),
  displayName,
  roleName,
  isAdmin,
  isAdminOrDeputy,
  effectiveViewerRole,
  adminLens,
  widgetVisibilitySettings,
  reservationEnabled,
  templateLabel,
  visibilityLabel,
  refresh,
  mutators: teamMutators,
})
</script>

<template>
  <div>
    <!--
      非シェルルート（schedule / chat / budget 等 約 100 ページ）は永続シェルで包まず
      bare <NuxtPage/> を描画する。各ページの layout:'team' の見た目を一切変えない。
      （pageTransition out-in 対応でルートは単一 <div>。）
    -->
    <NuxtPage v-if="!isShellRoute" />

    <!-- シェル対象ルート（8 タブ）: 永続シェルで包む -->
    <template v-else>
      <div v-if="loading || roleLoading">
        <div class="flex justify-center px-6 py-12">
          <LoadingBounce />
        </div>
        <!-- 15s watchdog: settle しない場合のみ表示（正常時は描画に一切影響しない） -->
        <div v-if="shellSlowLoading" class="flex flex-col items-center gap-3 pb-8 text-center">
          <p class="text-sm text-surface-500">{{ t('common.scopeShell.slow_loading') }}</p>
          <Button
            :label="t('common.scopeShell.retry')"
            icon="pi pi-refresh"
            size="small"
            outlined
            @click="retryShellData"
          />
        </div>
      </div>

      <ScopePageShell
        v-else-if="team"
        :tabs="tabs"
        :active-tab="activeTab"
        :sidebar="TeamSidebar"
        :sidebar-props="{ teamId: teamSlug }"
        :show-lens="isAdminOrDeputy"
        :lens="adminLens"
        @update:lens="adminLens = $event"
      >
        <template #header>
          <TeamPageHeader
            :team="team"
            :display-name="displayName"
            :role-name="roleName"
            :is-admin="isAdmin"
            :is-admin-or-deputy="isAdminOrDeputy"
            :follow-status="followStatus"
            :follow-loading="followLoading"
            :template-label="templateLabel"
            @back="navigateTo('/dashboard')"
            @apply-supporter="applySupporter"
            @cancel-supporter="cancelSupporter"
            @show-cancel-confirm="showCancelSupporterConfirm = true"
            @show-leave-confirm="showLeaveConfirm = true"
            @icon-updated="teamMutators.updateTeamIcon"
            @banner-updated="teamMutators.updateTeamBanner"
          />
        </template>

        <!-- タブ本体（子）— 永続シェル下で差し替え -->
        <NuxtPage />
      </ScopePageShell>

      <!--
        取得失敗フォールバック（loading 完了かつ team=null: 403/404/503 等）。
        村金型 villages/[id].vue に倣い、白画面ではなくエラー文言＋戻り導線を出す。
      -->
      <div v-else class="mx-auto max-w-2xl p-6 text-center">
        <i class="pi pi-exclamation-triangle text-4xl text-surface-400" aria-hidden="true" />
        <p class="mt-4 text-lg font-medium text-surface-700 dark:text-surface-200">
          {{ t('common.scopeShell.load_error_title') }}
        </p>
        <p class="mt-1 text-sm text-surface-500">
          {{ t('common.scopeShell.load_error_body') }}
        </p>
        <div class="mt-4 flex flex-col items-center gap-3">
          <Button
            :label="t('common.scopeShell.retry')"
            icon="pi pi-refresh"
            @click="retryShellData"
          />
          <NuxtLink to="/dashboard" class="inline-block text-primary-600 hover:underline">
            <i class="pi pi-arrow-left mr-1" />
            {{ t('common.scopeShell.back_to_dashboard') }}
          </NuxtLink>
        </div>
      </div>

      <!-- サポーター解除 / チーム退出の確認ダイアログ（親集約） -->
      <Dialog
        v-model:visible="showCancelSupporterConfirm"
        :header="t('common.scopeShell.supporter_cancel_confirm_title')"
        :style="{ width: '400px' }"
        modal
      >
        <p>{{ t('common.scopeShell.supporter_cancel_confirm_body', { name: displayName }) }}</p>
        <template #footer>
          <Button :label="t('button.cancel')" text @click="showCancelSupporterConfirm = false" />
          <Button
            :label="t('common.scopeShell.supporter_cancel_action')"
            severity="danger"
            :loading="followLoading"
            @click="cancelSupporter"
          />
        </template>
      </Dialog>

      <Dialog
        v-model:visible="showLeaveConfirm"
        :header="t('teamShell.action.leave_confirm_title')"
        :style="{ width: '400px' }"
        modal
      >
        <p>{{ t('teamShell.action.leave_confirm_body') }}</p>
        <template #footer>
          <Button :label="t('button.cancel')" text @click="showLeaveConfirm = false" />
          <Button :label="t('common.scopeShell.leave_action')" severity="danger" @click="leaveTeam" />
        </template>
      </Dialog>
    </template>
  </div>
</template>
