<script setup lang="ts">
import type { SidebarCategory, SidebarItem } from '~/types/sidebar'

const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: string
  categories: SidebarCategory[]
  /**
   * 管理者/メンバーレンズが「メンバー」プレビュー中か（true=メンバー項目のみに絞る）。
   * CMP-260917-1351 課題A: レンズはあくまで表示の絞り込み（プレビュー）であり、実ロールの
   * 昇格には使わない。ここでの判定は常に自前で取得した実ロール（roleName/isAdmin/
   * isAdminOrDeputy、下の useRoleAccess 参照）が土台であり、レンズは ADMIN/DEPUTY_ADMIN 項目を
   * 追加で隠す方向にしか働かない（実ロールが MEMBER のユーザーに、レンズを「管理者」にしても
   * 管理者項目が見えることは無い）。省略時（チーム等・レンズ機構を持たないスコープ）は
   * 従来どおり実ロールのみで判定する。
   */
  memberLensActive?: boolean
}>()

const emit = defineEmits<{
  tabNavigate: [item: SidebarItem]
}>()

// ロールアクセス
const { roleName, isAdmin, isAdminOrDeputy, loadPermissions } = useRoleAccess(props.scopeType, toRef(props, 'scopeId'))

// MEMBER以上判定（SUPPORTER/GUEST/未加入は除外）
const isMember = computed(() =>
  roleName.value !== null
  && roleName.value !== 'SUPPORTER'
  && roleName.value !== 'GUEST',
)

// モジュール有効スラッグセット
const enabledSlugs = ref<Set<string>>(new Set())

// モジュールAPI
const { getOrganizationModules } = useOrganizationModuleApi()
const { getTeamModules } = useModuleApi()

// 項目表示判定
function isItemVisible(item: SidebarItem): boolean {
  // レンズが「メンバー」プレビュー中は SYSTEM_ADMIN バイパスも効かせない（実ロールを問わず
  // メンバー項目のみに絞る＝プレビューとして機能させる）。
  if (roleName.value === 'SYSTEM_ADMIN' && !props.memberLensActive) return true
  if (!isMember.value) return false
  if (item.moduleSlug !== null && !enabledSlugs.value.has(item.moduleSlug)) return false
  if (props.memberLensActive) {
    // レンズ=メンバー: 実ロールに関わらず ADMIN/DEPUTY_ADMIN 項目は隠す（狭める方向のみ）。
    if (item.requiredRole === 'DEPUTY_ADMIN' || item.requiredRole === 'ADMIN') return false
    return true
  }
  if (item.requiredRole === 'DEPUTY_ADMIN' && !isAdminOrDeputy.value) return false
  if (item.requiredRole === 'ADMIN' && !isAdmin.value) return false
  return true
}

// カテゴリ表示判定（全項目が非表示なら隠す）
function isCategoryVisible(category: SidebarCategory): boolean {
  return category.items.some(item => isItemVisible(item))
}

// ベースパス
const basePath = computed(() =>
  props.scopeType === 'organization'
    ? `/organizations/${props.scopeId}`
    : `/teams/${props.scopeId}`,
)

onMounted(async () => {
  await Promise.all([
    loadPermissions(),
    (async () => {
      if (props.scopeType === 'organization') {
        const modules = await getOrganizationModules(props.scopeId)
        enabledSlugs.value = new Set(
          modules.filter(m => m.isEnabled).map(m => m.moduleSlug),
        )
      }
      else {
        const res = await getTeamModules(props.scopeId)
        enabledSlugs.value = new Set(
          res.data.filter(m => m.isEnabled).map(m => m.moduleSlug),
        )
      }
    })(),
  ])
})

// アコーディオン開閉状態（localStorage）
const STORAGE_KEY = computed(
  () => `mannschaft:${props.scopeType}Sidebar:${props.scopeId}:openCategories`,
)
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

/**
 * 項目のリンク先を解決する。`absolutePath` を持つ項目（スコープ配下に存在しない
 * 横断ルート・例: /admin/receipts）はスコープ基点を前置しない。
 */
function itemLinkTo(item: SidebarItem): string {
  return item.absolutePath ?? `${basePath.value}/${item.path}`
}

/** リンクとして描画する項目か（false ならタブ遷移ボタンとして描画する）。 */
function isLinkItem(item: SidebarItem): boolean {
  return item.absolutePath !== undefined || item.path !== ''
}

function isItemActive(item: SidebarItem): boolean {
  if (!isLinkItem(item)) return false
  return route.path.startsWith(itemLinkTo(item))
}
</script>

<template>
  <nav v-if="isMember" class="flex flex-col gap-0.5 py-2 px-1">
    <template v-for="category in categories" :key="category.key">
      <div v-if="isCategoryVisible(category)">
        <!-- カテゴリヘッダー（クリックで展開/折り畳み） -->
        <button
          class="flex w-full items-center gap-2 rounded-lg px-3 py-2 text-xs font-semibold uppercase tracking-wide text-surface-500 dark:text-surface-400 hover:bg-surface-100 dark:hover:bg-surface-700 transition-colors"
          @click="toggleCategory(category.key)"
        >
          <i :class="category.icon" class="text-sm" />
          <span class="flex-1 text-left">{{ $t(category.labelKey) }}</span>
          <i :class="openCategories.includes(category.key) ? 'pi pi-chevron-down' : 'pi pi-chevron-right'" class="text-xs" />
        </button>

        <!-- カテゴリ内アイテム -->
        <div v-show="openCategories.includes(category.key)" class="ml-2 flex flex-col gap-0.5">
          <template v-for="item in category.items" :key="item.labelKey">
            <NuxtLink
              v-if="isItemVisible(item) && isLinkItem(item)"
              :to="itemLinkTo(item)"
              class="flex items-center gap-2 rounded-lg px-3 py-1.5 text-sm transition-colors hover:bg-surface-100 dark:hover:bg-surface-700"
              :class="isItemActive(item) ? 'bg-primary/10 text-primary font-medium' : 'text-surface-600 dark:text-surface-300'"
            >
              <i :class="item.icon" class="text-sm w-4" />
              {{ $t(item.labelKey) }}
            </NuxtLink>
            <!-- タブ遷移アイテム（path === ''） -->
            <button
              v-else-if="isItemVisible(item) && !isLinkItem(item)"
              class="flex w-full items-center gap-2 rounded-lg px-3 py-1.5 text-sm text-surface-600 dark:text-surface-300 transition-colors hover:bg-surface-100 dark:hover:bg-surface-700 text-left"
              @click="emit('tabNavigate', item)"
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
