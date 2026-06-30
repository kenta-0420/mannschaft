<script setup lang="ts">
import type { SidebarCategory, SidebarItem } from '~/types/sidebar'

const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: string
  categories: SidebarCategory[]
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

function isItemActive(item: SidebarItem): boolean {
  if (item.path === '') return false
  return route.path.startsWith(`${basePath.value}/${item.path}`)
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
              :to="`${basePath}/${item.path}`"
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
