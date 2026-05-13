<script setup lang="ts">
interface SidebarItem {
  labelKey: string
  icon: string
  path: string
  moduleSlug: string | null
  requiredRole: 'MEMBER' | 'DEPUTY_ADMIN' | 'ADMIN'
}

interface SidebarCategory {
  key: string
  labelKey: string
  icon: string
  items: SidebarItem[]
}

const props = defineProps<{
  orgId: number
}>()

const categories: SidebarCategory[] = [
  {
    key: 'home',
    labelKey: 'orgSidebar.category.home',
    icon: 'pi pi-home',
    items: [
      { labelKey: 'orgSidebar.timeline', icon: 'pi pi-comments', path: 'timeline', moduleSlug: 'timeline', requiredRole: 'MEMBER' },
      { labelKey: 'orgSidebar.chat', icon: 'pi pi-comment', path: 'chat', moduleSlug: 'chat', requiredRole: 'MEMBER' },
      { labelKey: 'orgSidebar.bulletin', icon: 'pi pi-megaphone', path: 'bulletin', moduleSlug: 'bulletin', requiredRole: 'MEMBER' },
      { labelKey: 'orgSidebar.blog', icon: 'pi pi-pen-to-square', path: 'blog', moduleSlug: null, requiredRole: 'MEMBER' },
      { labelKey: 'orgSidebar.circulation', icon: 'pi pi-sync', path: 'circulation', moduleSlug: 'circular', requiredRole: 'MEMBER' },
    ],
  },
  {
    key: 'schedule',
    labelKey: 'orgSidebar.category.schedule',
    icon: 'pi pi-calendar',
    items: [
      { labelKey: 'orgSidebar.schedule', icon: 'pi pi-calendar', path: 'schedule', moduleSlug: 'schedule', requiredRole: 'MEMBER' },
      { labelKey: 'orgSidebar.events', icon: 'pi pi-calendar-plus', path: 'events', moduleSlug: 'schedule', requiredRole: 'MEMBER' },
      { labelKey: 'orgSidebar.timetable', icon: 'pi pi-table', path: 'timetable', moduleSlug: null, requiredRole: 'MEMBER' },
      { labelKey: 'orgSidebar.annualPlan', icon: 'pi pi-chart-bar', path: 'annual-plan', moduleSlug: null, requiredRole: 'MEMBER' },
    ],
  },
  {
    key: 'member',
    labelKey: 'orgSidebar.category.member',
    icon: 'pi pi-users',
    items: [
      { labelKey: 'orgSidebar.memberList', icon: 'pi pi-users', path: '', moduleSlug: null, requiredRole: 'MEMBER' },
      { labelKey: 'orgSidebar.memberProfiles', icon: 'pi pi-id-card', path: 'member-profiles', moduleSlug: 'member_intro', requiredRole: 'MEMBER' },
      { labelKey: 'orgSidebar.invite', icon: 'pi pi-user-plus', path: '', moduleSlug: null, requiredRole: 'DEPUTY_ADMIN' },
      { labelKey: 'orgSidebar.permissionGroups', icon: 'pi pi-shield', path: '', moduleSlug: null, requiredRole: 'ADMIN' },
      { labelKey: 'orgSidebar.onboarding', icon: 'pi pi-flag', path: 'onboarding', moduleSlug: null, requiredRole: 'ADMIN' },
    ],
  },
  {
    key: 'ops',
    labelKey: 'orgSidebar.category.ops',
    icon: 'pi pi-briefcase',
    items: [
      { labelKey: 'orgSidebar.todos', icon: 'pi pi-check-square', path: 'todos', moduleSlug: 'todo', requiredRole: 'MEMBER' },
      { labelKey: 'orgSidebar.workflows', icon: 'pi pi-sitemap', path: 'workflows', moduleSlug: null, requiredRole: 'MEMBER' },
      { labelKey: 'orgSidebar.forms', icon: 'pi pi-file-edit', path: 'forms', moduleSlug: null, requiredRole: 'MEMBER' },
      { labelKey: 'orgSidebar.surveys', icon: 'pi pi-chart-pie', path: 'surveys', moduleSlug: 'survey', requiredRole: 'MEMBER' },
      { labelKey: 'orgSidebar.voting', icon: 'pi pi-check-circle', path: 'voting', moduleSlug: 'voting', requiredRole: 'MEMBER' },
      { labelKey: 'orgSidebar.safety', icon: 'pi pi-heart', path: 'safety', moduleSlug: 'safety_check', requiredRole: 'MEMBER' },
      { labelKey: 'orgSidebar.incidents', icon: 'pi pi-exclamation-triangle', path: 'incidents', moduleSlug: null, requiredRole: 'MEMBER' },
      { labelKey: 'orgSidebar.committees', icon: 'pi pi-building', path: 'committees', moduleSlug: null, requiredRole: 'MEMBER' },
    ],
  },
  {
    key: 'facility',
    labelKey: 'orgSidebar.category.facility',
    icon: 'pi pi-building',
    items: [
      { labelKey: 'orgSidebar.facilities', icon: 'pi pi-building', path: 'facilities', moduleSlug: null, requiredRole: 'MEMBER' },
      { labelKey: 'orgSidebar.equipment', icon: 'pi pi-box', path: 'equipment', moduleSlug: 'equipment', requiredRole: 'MEMBER' },
      { labelKey: 'orgSidebar.parking', icon: 'pi pi-car', path: 'parking', moduleSlug: 'parking', requiredRole: 'MEMBER' },
      { labelKey: 'orgSidebar.signage', icon: 'pi pi-desktop', path: 'signage', moduleSlug: null, requiredRole: 'ADMIN' },
    ],
  },
  {
    key: 'resident',
    labelKey: 'orgSidebar.category.resident',
    icon: 'pi pi-home',
    items: [
      { labelKey: 'orgSidebar.residents', icon: 'pi pi-users', path: 'residents', moduleSlug: 'resident_register', requiredRole: 'MEMBER' },
    ],
  },
  {
    key: 'data',
    labelKey: 'orgSidebar.category.data',
    icon: 'pi pi-database',
    items: [
      { labelKey: 'orgSidebar.files', icon: 'pi pi-folder', path: 'files', moduleSlug: 'file_sharing', requiredRole: 'MEMBER' },
      { labelKey: 'orgSidebar.kb', icon: 'pi pi-book', path: 'kb', moduleSlug: null, requiredRole: 'MEMBER' },
      { labelKey: 'orgSidebar.analytics', icon: 'pi pi-chart-line', path: 'analytics', moduleSlug: 'analytics', requiredRole: 'ADMIN' },
      { labelKey: 'orgSidebar.budget', icon: 'pi pi-wallet', path: 'budget', moduleSlug: null, requiredRole: 'ADMIN' },
      { labelKey: 'orgSidebar.auditLogs', icon: 'pi pi-list', path: 'audit-logs', moduleSlug: 'audit_log', requiredRole: 'ADMIN' },
    ],
  },
  {
    key: 'other',
    labelKey: 'orgSidebar.category.other',
    icon: 'pi pi-ellipsis-h',
    items: [
      { labelKey: 'orgSidebar.gamification', icon: 'pi pi-star', path: 'gamification', moduleSlug: null, requiredRole: 'MEMBER' },
      { labelKey: 'orgSidebar.tournaments', icon: 'pi pi-trophy', path: 'tournaments', moduleSlug: null, requiredRole: 'MEMBER' },
      { labelKey: 'orgSidebar.queue', icon: 'pi pi-sort-numeric-up', path: 'queue', moduleSlug: null, requiredRole: 'MEMBER' },
      { labelKey: 'orgSidebar.timelineDigest', icon: 'pi pi-align-left', path: 'timeline-digest', moduleSlug: null, requiredRole: 'MEMBER' },
      { labelKey: 'orgSidebar.translations', icon: 'pi pi-language', path: 'translations', moduleSlug: null, requiredRole: 'ADMIN' },
    ],
  },
  {
    key: 'revenue',
    labelKey: 'orgSidebar.category.revenue',
    icon: 'pi pi-credit-card',
    items: [
      { labelKey: 'orgSidebar.payments', icon: 'pi pi-credit-card', path: 'payments', moduleSlug: 'payment', requiredRole: 'MEMBER' },
      { labelKey: 'orgSidebar.directMail', icon: 'pi pi-envelope', path: 'direct-mail', moduleSlug: 'direct_mail', requiredRole: 'ADMIN' },
      { labelKey: 'orgSidebar.advertiser', icon: 'pi pi-megaphone', path: 'advertiser', moduleSlug: 'ad_display', requiredRole: 'ADMIN' },
      { labelKey: 'orgSidebar.webhooks', icon: 'pi pi-code', path: 'webhooks', moduleSlug: null, requiredRole: 'ADMIN' },
    ],
  },
  {
    key: 'settings',
    labelKey: 'orgSidebar.category.settings',
    icon: 'pi pi-cog',
    items: [
      { labelKey: 'orgSidebar.settingsModules', icon: 'pi pi-sliders-h', path: '', moduleSlug: null, requiredRole: 'ADMIN' },
      { labelKey: 'orgSidebar.notificationCredits', icon: 'pi pi-bell', path: 'settings/notification-credits', moduleSlug: null, requiredRole: 'ADMIN' },
      { labelKey: 'orgSidebar.todoStatusLabels', icon: 'pi pi-tags', path: 'settings/todo-status-labels', moduleSlug: null, requiredRole: 'ADMIN' },
    ],
  },
]

// ロールアクセス
const { roleName, isAdmin, isAdminOrDeputy, loadPermissions } = useRoleAccess('organization', toRef(props, 'orgId'))

// MEMBER以上判定（SUPPORTER/GUEST/未加入は除外）
const isMember = computed(() =>
  roleName.value !== null
  && roleName.value !== 'SUPPORTER'
  && roleName.value !== 'GUEST',
)

// モジュール有効スラッグセット
const enabledSlugs = ref<Set<string>>(new Set())

// 項目表示判定
function isItemVisible(item: SidebarItem): boolean {
  if (roleName.value === 'SYSTEM_ADMIN') return true
  if (!isMember.value) return false
  if (item.moduleSlug !== null && !enabledSlugs.value.has(item.moduleSlug)) return false
  if (item.requiredRole === 'DEPUTY_ADMIN' && !isAdminOrDeputy.value) return false
  if (item.requiredRole === 'ADMIN' && !isAdmin.value) return false
  return true
}

// カテゴリ表示判定（全項目が非表示なら隠す）
function isCategoryVisible(category: SidebarCategory): boolean {
  return category.items.some(item => isItemVisible(item))
}

// モジュールAPI
const { getOrganizationModules } = useOrganizationModuleApi()

onMounted(async () => {
  await Promise.all([
    loadPermissions(),
    getOrganizationModules(props.orgId).then((modules) => {
      enabledSlugs.value = new Set(
        modules.filter(m => m.isEnabled).map(m => m.moduleSlug),
      )
    }),
  ])
})

// アコーディオン開閉状態（localStorage）
const STORAGE_KEY = computed(() => `mannschaft:orgSidebar:${props.orgId}:openCategories`)
const openCategories = ref<string[]>(['home', 'member'])

onMounted(() => {
  if (import.meta.client) {
    const saved = localStorage.getItem(STORAGE_KEY.value)
    if (saved) {
      try { openCategories.value = JSON.parse(saved) } catch { /* ignore */ }
    }
  }
})

function toggleCategory(key: string) {
  const idx = openCategories.value.indexOf(key)
  if (idx >= 0) openCategories.value.splice(idx, 1)
  else openCategories.value.push(key)
  if (import.meta.client) {
    localStorage.setItem(STORAGE_KEY.value, JSON.stringify(openCategories.value))
  }
}

// アクティブ状態
const route = useRoute()

function isItemActive(item: SidebarItem): boolean {
  if (item.path === '') return false
  return route.path.startsWith(`/organizations/${props.orgId}/${item.path}`)
}

// タブ遷移
// index.vue のタブ番号確認済み:
//   value=2: メンバー、value=4: 招待（isAdminOrDeputy）、value=5: 権限グループ（isAdmin）、value=7: 機能設定（isAdmin）
const router = useRouter()
const tabMap: Record<string, number> = {
  'orgSidebar.memberList': 2,
  'orgSidebar.invite': 4,
  'orgSidebar.permissionGroups': 5,
  'orgSidebar.settingsModules': 7,
}

function navigateToTab(item: SidebarItem) {
  const tab = tabMap[item.labelKey]
  router.push(`/organizations/${props.orgId}${tab !== undefined ? `?tab=${tab}` : ''}`)
}
</script>

<template>
  <nav v-if="isMember" class="flex flex-col gap-0.5 py-2 px-1">
    <template v-for="category in categories" :key="category.key">
      <div v-if="isCategoryVisible(category)">
        <!-- カテゴリヘッダー（クリックで展開/折り畳み） -->
        <button
          class="flex w-full items-center gap-2 rounded-lg px-3 py-2 text-xs font-semibold uppercase tracking-wide text-surface-500 hover:bg-surface-100 transition-colors"
          @click="toggleCategory(category.key)"
        >
          <i :class="category.icon" class="text-sm" />
          <span class="flex-1 text-left">{{ $t(category.labelKey) }}</span>
          <i :class="openCategories.includes(category.key) ? 'pi pi-chevron-down' : 'pi pi-chevron-right'" class="text-xs" />
        </button>

        <!-- カテゴリ内アイテム -->
        <div v-show="openCategories.includes(category.key)" class="ml-2 flex flex-col gap-0.5">
          <template v-for="item in category.items" :key="item.path + item.labelKey">
            <NuxtLink
              v-if="isItemVisible(item) && item.path !== ''"
              :to="`/organizations/${props.orgId}/${item.path}`"
              class="flex items-center gap-2 rounded-lg px-3 py-1.5 text-sm transition-colors hover:bg-surface-100"
              :class="isItemActive(item) ? 'bg-primary/10 text-primary font-medium' : 'text-surface-600'"
            >
              <i :class="item.icon" class="text-sm w-4" />
              {{ $t(item.labelKey) }}
            </NuxtLink>
            <!-- タブ遷移アイテム（path === ''） -->
            <button
              v-else-if="isItemVisible(item) && item.path === ''"
              class="flex w-full items-center gap-2 rounded-lg px-3 py-1.5 text-sm text-surface-600 transition-colors hover:bg-surface-100 text-left"
              @click="navigateToTab(item)"
            >
              <i :class="item.icon" class="text-sm w-4" />
              {{ $t(item.labelKey) }}
            </button>
          </template>
        </div>
      </div>
    </template>
  </nav>
</template>
