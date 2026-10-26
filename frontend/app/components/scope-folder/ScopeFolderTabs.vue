<script setup lang="ts">
import type { ScopeType } from '~/types/scopeFolder'

/**
 * F15.3 ハブ画面用フォルダタブ。
 *
 * 設計書 §7.3 に準拠。
 * - タブ: 「すべて」「（各フォルダ）」「未分類」
 * - URL クエリ `?folder={id|default|all|manage}` がソース・オブ・トゥルース
 * - 最終選択タブを localStorage に保存（再訪時に復元）
 *
 * `currentFolderId` は親と v-model で双方向同期する。
 *  - `'all'`: すべて表示
 *  - `'default'`: 未分類のみ表示
 *  - `'manage'`: 管理タブ
 *  - `<number>`: 該当フォルダのみ表示
 */

interface Props {
  scopeType: ScopeType
  /** 現在の選択タブ。文字列の `'all'` / `'default'` / `'manage'` または folderId（number）。 */
  currentFolderId: 'all' | 'default' | 'manage' | number
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:currentFolderId': [value: 'all' | 'default' | 'manage' | number]
}>()

const { t } = useI18n()
const foldersStore = useScopeFoldersStore()

const folders = computed(() => foldersStore.customFoldersFor(props.scopeType))
const defaultFolder = computed(() => foldersStore.defaultFolderFor(props.scopeType))

const LAST_TAB_KEY = computed(
  () => `mannschaft:scopeFolders:lastTab:${props.scopeType}`,
)

/** タブ選択時に親へ通知＋localStorage 保存。 */
function selectTab(value: 'all' | 'default' | 'manage' | number) {
  emit('update:currentFolderId', value)
  if (import.meta.client) {
    localStorage.setItem(LAST_TAB_KEY.value, String(value))
  }
}

onMounted(async () => {
  if (folders.value.length === 0 && !defaultFolder.value) {
    try {
      await foldersStore.fetchAll(props.scopeType)
    }
    catch {
      // 失敗時は「すべて」タブのみで継続
    }
  }
})

function isActive(value: 'all' | 'default' | 'manage' | number): boolean {
  return props.currentFolderId === value
}

function tabClass(value: 'all' | 'default' | 'manage' | number): string {
  const base =
    'flex shrink-0 items-center gap-2 rounded-lg px-3 py-2 text-sm font-medium whitespace-nowrap transition-colors'
  return isActive(value)
    ? `${base} bg-primary/10 text-primary`
    : `${base} text-surface-600 hover:bg-surface-100`
}
</script>

<template>
  <div
    role="tablist"
    :aria-label="t('scopeFolder.folders')"
    class="flex items-center gap-1 overflow-x-auto scrollbar-thin-nav"
    :data-testid="`scope-folder-tabs-${scopeType}`"
  >
    <!-- すべて -->
    <button
      type="button"
      role="tab"
      :aria-selected="isActive('all') ? 'true' : 'false'"
      :class="tabClass('all')"
      @click="selectTab('all')"
    >
      <i class="pi pi-list" aria-hidden="true" />
      {{ t('scopeFolder.all') }}
    </button>

    <!-- 各フォルダ -->
    <button
      v-for="folder in folders"
      :key="folder.id"
      type="button"
      role="tab"
      :aria-selected="isActive(folder.id) ? 'true' : 'false'"
      :class="tabClass(folder.id)"
      :data-testid="`scope-folder-tab-${folder.id}`"
      @click="selectTab(folder.id)"
    >
      <span
        class="inline-block h-3 w-3 shrink-0 rounded-full"
        :style="folder.color ? { backgroundColor: folder.color } : { backgroundColor: '#9CA3AF' }"
        aria-hidden="true"
      />
      <i
        v-if="folder.icon"
        :class="['pi', folder.icon, 'text-xs']"
        aria-hidden="true"
      />
      <span class="truncate">{{ folder.name }}</span>
      <span class="text-xs opacity-60">({{ folder.itemScopeIds.length }})</span>
    </button>

    <!-- 未分類 -->
    <button
      v-if="defaultFolder"
      type="button"
      role="tab"
      :aria-selected="isActive('default') ? 'true' : 'false'"
      :class="tabClass('default')"
      data-testid="scope-folder-tab-default"
      @click="selectTab('default')"
    >
      <span class="inline-block h-3 w-3 shrink-0 rounded-full bg-surface-300" aria-hidden="true" />
      {{ t('scopeFolder.untagged') }}
      <span class="text-xs opacity-60">({{ defaultFolder.itemScopeIds.length }})</span>
    </button>

    <!-- 管理タブ -->
    <button
      type="button"
      role="tab"
      :aria-selected="isActive('manage') ? 'true' : 'false'"
      :class="tabClass('manage')"
      @click="selectTab('manage')"
    >
      <i class="pi pi-cog" aria-hidden="true" />
      {{ t('scopeFolder.nav.manage') }}
    </button>
  </div>
</template>
