<script setup lang="ts">
/**
 * 組織詳細「永続シェル」親レイアウトルート。
 *
 * # 役割（永続シェル方式 / SPA・チーム pages/teams/[slug].vue を範とする）
 *  Nuxt 3 では `pages/organizations/[slug].vue` を置くと `pages/organizations/[slug]/*.vue`
 *  の親になる。本ファイルは組織詳細の「常駐シェル」として以下を 1 度だけ解決し、
 *  子タブへ provide する:
 *    - 組織データ・所属チーム・階層（祖先/子）・権限グループ（onMounted 取得・slug redirect）
 *    - 権限（useRoleAccess）・ウィジェット可視性（useDashboardWidgetVisibility）
 *    - OrgPageHeader の常駐描画 + follow/leave アクションの親集約
 *    - 管理者レンズ（adminLens）と、管理ルート滞在時のレンズ OFF リダイレクト
 *
 *  共通シェル ScopePageShell.vue（チームと共用）の default slot に <NuxtPage/> を渡し、
 *  タブ本体だけを差し替える。タブ遷移時にヘッダ・タブ・サイドバーは再マウントされない。
 *
 * # 注意
 *  - `key: route => route.fullPath` は付けない（付けると子遷移で親が再マウントし崩壊）。
 *  - pageTransition(out-in) 対応でルート直下は単一 <div>。
 */
import type { ViewerRole } from '~/types/dashboard'
import type { ScopeTab } from '~/types/scopeShell'
import OrganizationSidebar from '~/components/OrganizationSidebar.vue'
import { provideOrgShellContext } from '~/composables/useOrgShellContext'
import { resolveSlugRedirectPath } from '~/utils/slugRedirect'

definePageMeta({
  middleware: 'auth',
  layout: 'default',
})

const route = useRoute()
const { t } = useI18n()
const orgSlug = computed(() => String(route.params.slug))

const {
  roleName,
  loading: roleLoading,
  loadPermissions,
  isAdmin,
  isAdminOrDeputy,
} = useRoleAccess('organization', orgSlug)

const {
  settings: widgetVisibilitySettings,
  fetch: fetchWidgetVisibility,
} = useDashboardWidgetVisibility('organization', orgSlug)

const {
  org,
  orgTeams,
  permissionGroups,
  loading,
  followStatus,
  followLoading,
  showCancelSupporterConfirm,
  showLeaveConfirm,
  fetchOrg,
  fetchOrgTeams,
  fetchPermissionGroups,
  fetchFollowStatus,
  applySupporter,
  cancelSupporter,
  leaveOrganization,
} = useOrgDetail(orgSlug)

const {
  ancestors,
  children,
  loading: hierarchyLoading,
  childrenHasNext,
  fetchAncestors,
  fetchChildren,
} = useOrgHierarchy(orgSlug)

// 管理者レンズ（true=管理者ビュー, false=メンバービュー）。管理者は既定で管理者ビュー。
const adminLens = ref(true)

const effectiveViewerRole = computed<ViewerRole>(() =>
  adminLens.value ? ((roleName.value as ViewerRole | null) ?? 'PUBLIC') : 'MEMBER',
)

const displayName = computed(
  () => org.value?.basicInfo?.nickname1 || org.value?.basicInfo?.name || '',
)

/** 下位組織タブを表示するか（子 0 件かつ非 ADMIN なら隠す）。 */
const showChildrenTab = computed(() => isAdmin.value || children.value.length > 0)

/**
 * 組織内チーム検索ページへの導線を表示するか。
 * - 組織が PUBLIC のときは未ログイン者を含め誰でも表示
 * - それ以外は組織メンバー（roleName が付与されている）にのみ表示
 */
const showTeamSearchLink = computed(() => {
  if (!org.value) return false
  if (org.value.visibility?.visibility === 'PUBLIC') return true
  return Boolean(roleName.value)
})

/**
 * 組織取得が 404（org 未取得）のとき、旧 slug → 新 slug の MOVED かを解決し 301 遷移する
 * （村方式・BE #1542）。遷移した場合は true を返す。
 */
async function tryRedirectMovedSlug(): Promise<boolean> {
  const { resolveSlug } = useSlugRedirect()
  const target = await resolveSlugRedirectPath(route.path, resolveSlug)
  if (!target) return false
  await navigateTo(
    { path: target, query: route.query, hash: route.hash },
    { redirectCode: 301, replace: true },
  )
  return true
}

/** 状態同期用の再取得（follow/leave 後など）。 */
async function refresh() {
  await Promise.all([fetchOrg(), loadPermissions()])
  await fetchFollowStatus(roleName)
}

// =============================================================================
// アクティブタブ導出（ルートセグメント → タブ key）
// =============================================================================
/** ルートセグメント → タブ key。未登録（slug 自身・schedule 等）は dashboard。 */
const SEGMENT_TO_TAB: Record<string, string> = {
  info: 'info',
  members: 'members',
  'member-teams': 'member-teams',
  children: 'children',
  invites: 'invites',
  'permission-groups': 'permission-groups',
  supporters: 'supporters',
  modules: 'modules',
}

/**
 * 永続シェル（ヘッダ＋タブ）で包む対象セグメント。
 *
 * `pages/organizations/[slug].vue` は organizations/[slug] 配下の全ページ（schedule /
 * chat / budget … 多くは layout:'organization' の独立ページ）の親ルート record になる。
 * そのため無条件にシェルを描画すると全ページにヘッダ＋タブが被さり大規模退行する。
 *
 * シェル化対象は以下の 2 系統に限定する（チーム [slug].vue と同型）:
 *  1. 8 タブのルート（info / members / member-teams / children / invites /
 *     permission-groups / supporters / modules ＋ダッシュボード=slug 自身）
 *  2. ダッシュボードのウィジェット遷移先（ScopeDashboard.vue の scopeLinks が正本のうち、
 *     組織に実在する 13 セグメント。member-info / match-analytics は組織に無いため対象外）。
 */
const SHELL_SEGMENTS = new Set([
  // --- 8 タブ ---
  'info',
  'members',
  'member-teams',
  'children',
  'invites',
  'permission-groups',
  'supporters',
  'modules',
  // --- ウィジェット遷移先（ScopeDashboard.vue scopeLinks 正本・組織に実在する 13 セグメント） ---
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
  'tournaments',
  'projects',
])

/**
 * パス全体のセグメント配列（先頭 '' は除去済み。例 ['organizations', '{slug}', 'schedule', '123']）。
 */
const pathSegments = computed<string[]>(() =>
  route.path.replace(/\/+$/, '').split('/').filter(Boolean),
)

/**
 * SHELL_SEGMENTS に一致する最初のセグメント（無ければ null）。
 *
 * # slug 衝突・深ネストの根治
 *  照合対象は index 2 以降（= 'organizations' と '{slug}' を除いた残り）に限定する。
 *  slug 自身がタブ名（info/members/schedule 等）と一致した場合に slug セグメントが
 *  ヒットして activeTab が誤るのを防ぐ（slice(2) で slug を照合から外す）。
 */
const matchedShellSegment = computed<string | null>(() => {
  for (const seg of pathSegments.value.slice(2)) {
    if (SHELL_SEGMENTS.has(seg)) return seg
  }
  return null
})

/** ちょうど `/organizations/{slug}`（タブ未指定・ダッシュボード）か。 */
const isDashboardRoute = computed<boolean>(() =>
  pathSegments.value.length === 2 && pathSegments.value[1] === orgSlug.value,
)

const isShellRoute = computed<boolean>(() =>
  isDashboardRoute.value || matchedShellSegment.value !== null,
)

const activeTab = computed<string>(() => {
  const seg = matchedShellSegment.value
  if (!seg) return 'dashboard'
  // SEGMENT_TO_TAB 未登録のウィジェット遷移先（schedule 等）は dashboard をハイライト。
  return SEGMENT_TO_TAB[seg] ?? 'dashboard'
})

// =============================================================================
// シェルデータのロード（シェル対象ルートに居るときだけ）
// =============================================================================
/** シェル描画に必要なデータを一括ロード（重複ロード防止に orgLoaded で番人）。 */
const orgLoaded = ref(false)
async function loadShellData() {
  if (orgLoaded.value) return
  orgLoaded.value = true
  await Promise.all([fetchOrg(), loadPermissions()])
  // 組織が取得できなかった（404 等）場合は MOVED slug の可能性を解決し 301 遷移を試みる。
  if (!org.value && await tryRedirectMovedSlug()) return
  await Promise.all([
    fetchOrgTeams(),
    isAdmin.value ? fetchPermissionGroups() : Promise.resolve(),
    fetchFollowStatus(roleName),
    fetchAncestors(),
    fetchChildren(true),
  ])
  // ウィジェット可視性設定を取得（非メンバー・サポーターは403になるため catch して空のまま）
  fetchWidgetVisibility().catch(() => {})
}

// シェル対象ルートに居るときだけデータをロードする。
// 非シェルルート（budget / analytics 等）ではロードしない＝従来と同じ挙動。
onMounted(() => {
  if (isShellRoute.value) void loadShellData()
})

// 非シェル → シェルへの client 遷移で初回ロードする。
watch(isShellRoute, (shell) => {
  if (shell) void loadShellData()
})

// 永続シェルを維持したまま別組織へ遷移した場合（同一親ルート record）に再取得する。
watch(orgSlug, () => {
  orgLoaded.value = false
  org.value = null
  followStatus.value = 'NONE'
  if (isShellRoute.value) void loadShellData()
})

/** 管理系タブ key（レンズ OFF・非管理者では滞在させない）。 */
const ADMIN_ONLY_SEGMENTS = new Set(['invites', 'permission-groups', 'supporters', 'modules'])

/**
 * 管理者レンズ OFF、または管理権限を失った状態で管理ルートに滞在している場合は
 * ダッシュボード（/organizations/{slug}）へ戻す（旧 index.vue の activeTab リセット相当）。
 */
watch([adminLens, isAdmin, isAdminOrDeputy, () => route.path], () => {
  const tab = activeTab.value
  if (!ADMIN_ONLY_SEGMENTS.has(tab)) return
  // 招待は副管理者以上、その他（権限グループ/サポーター/機能設定）は管理者のみ。
  const allowed = tab === 'invites' ? isAdminOrDeputy.value : isAdmin.value
  if (!adminLens.value || !allowed) {
    navigateTo(`/organizations/${orgSlug.value}`)
  }
})

// =============================================================================
// タブ定義（権限出し分けは visible に移植）
// =============================================================================
const tabs = computed<ScopeTab[]>(() => {
  const base = `/organizations/${orgSlug.value}`
  return [
    {
      key: 'dashboard',
      to: base,
      icon: 'pi pi-th-large',
      labelKey: 'orgShell.tab.dashboard',
    },
    {
      key: 'info',
      to: `${base}/info`,
      icon: 'pi pi-info-circle',
      labelKey: 'orgShell.tab.info',
    },
    {
      key: 'members',
      to: `${base}/members`,
      icon: 'pi pi-users',
      labelKey: 'orgShell.tab.members',
    },
    {
      key: 'member-teams',
      to: `${base}/member-teams`,
      icon: 'pi pi-sitemap',
      labelKey: 'orgShell.tab.member_teams',
    },
    {
      key: 'children',
      to: `${base}/children`,
      icon: 'pi pi-share-alt',
      labelKey: 'organization.children_tab',
      visible: showChildrenTab.value,
    },
    {
      key: 'invites',
      to: `${base}/invites`,
      icon: 'pi pi-envelope',
      labelKey: 'orgShell.tab.invites',
      visible: isAdminOrDeputy.value && adminLens.value,
    },
    {
      key: 'permission-groups',
      to: `${base}/permission-groups`,
      icon: 'pi pi-shield',
      labelKey: 'orgShell.tab.permission_groups',
      visible: isAdmin.value && adminLens.value,
    },
    {
      key: 'supporters',
      to: `${base}/supporters`,
      icon: 'pi pi-heart',
      labelKey: 'orgShell.tab.supporters',
      visible: isAdmin.value && (org.value?.visibility?.supporterEnabled ?? false) && adminLens.value,
    },
    {
      key: 'modules',
      to: `${base}/modules`,
      icon: 'pi pi-sliders-h',
      labelKey: 'orgShell.tab.modules',
      visible: isAdmin.value && adminLens.value,
    },
  ]
})

// =============================================================================
// 子タブへ provide
// =============================================================================
/** 親 org ref を子から部分更新するミューテータ群。 */
const orgMutators = {
  updateOrgIcon: (url: string | null) => { if (org.value?.metadata) org.value.metadata.iconUrl = url },
  updateOrgBanner: (url: string | null) => { if (org.value?.metadata) org.value.metadata.bannerUrl = url },
}

provideOrgShellContext({
  org: computed(() => org.value),
  displayName,
  roleName,
  isAdmin,
  isAdminOrDeputy,
  effectiveViewerRole,
  adminLens,
  widgetVisibilitySettings,
  orgTeams,
  ancestors,
  children,
  childrenHasNext,
  hierarchyLoading,
  fetchChildren,
  permissionGroups,
  showChildrenTab,
  showTeamSearchLink,
  refresh,
  mutators: orgMutators,
})
</script>

<template>
  <div>
    <!--
      非シェルルート（budget / analytics / advertiser 等）は永続シェルで包まず
      bare <NuxtPage/> を描画する。各ページの layout:'organization' の見た目を変えない。
      （pageTransition out-in 対応でルートは単一 <div>。）
    -->
    <NuxtPage v-if="!isShellRoute" />

    <!-- シェル対象ルート（8 タブ + ウィジェット遷移先）: 永続シェルで包む -->
    <template v-else>
      <div v-if="loading || roleLoading" class="flex justify-center px-6 py-12">
        <LoadingBounce />
      </div>

      <ScopePageShell
        v-else-if="org"
        :tabs="tabs"
        :active-tab="activeTab"
        :sidebar="OrganizationSidebar"
        :sidebar-props="{ orgId: orgSlug }"
        :show-lens="isAdminOrDeputy"
        :lens="adminLens"
        @update:lens="adminLens = $event"
      >
        <template #header>
          <OrgPageHeader
            :org="org"
            :org-id="orgSlug"
            :role-name="roleName"
            :is-admin="isAdmin"
            :is-admin-or-deputy="isAdminOrDeputy"
            :follow-status="followStatus"
            :follow-loading="followLoading"
            :ancestors="ancestors"
            @back="navigateTo('/dashboard')"
            @apply-supporter="applySupporter"
            @cancel-supporter="cancelSupporter"
            @show-cancel-confirm="showCancelSupporterConfirm = true"
            @show-leave-confirm="showLeaveConfirm = true"
            @icon-updated="orgMutators.updateOrgIcon"
            @banner-updated="orgMutators.updateOrgBanner"
          />
        </template>

        <!-- タブ本体（子）— 永続シェル下で差し替え -->
        <NuxtPage />
      </ScopePageShell>

      <!--
        取得失敗フォールバック（loading 完了かつ org=null: 403/404/503 等）。
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
        <NuxtLink to="/dashboard" class="mt-4 inline-block text-primary-600 hover:underline">
          <i class="pi pi-arrow-left mr-1" />
          {{ t('common.scopeShell.back_to_dashboard') }}
        </NuxtLink>
      </div>

      <!-- サポーター解除 / 組織退出の確認ダイアログ（親集約） -->
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
        :header="t('orgShell.action.leave_confirm_title')"
        :style="{ width: '400px' }"
        modal
      >
        <p>{{ t('orgShell.action.leave_confirm_body') }}</p>
        <template #footer>
          <Button :label="t('button.cancel')" text @click="showLeaveConfirm = false" />
          <Button :label="t('common.scopeShell.leave_action')" severity="danger" @click="leaveOrganization" />
        </template>
      </Dialog>
    </template>
  </div>
</template>
