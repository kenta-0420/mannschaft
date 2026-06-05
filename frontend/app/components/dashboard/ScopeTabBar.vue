<script setup lang="ts">
/**
 * F22.1 タグ行（スコープタブバー）。
 *
 * 設計書: docs/features/F22.1_swipe_scope_dashboard/03_security_ux.md §2.10 / §3.4 / §3.5
 * - 上位 6 件タグ（チップ）+ 選択で selectedTeamId / selectedOrgId 切替。
 * - ページ送り ‹ p/N ›（hasPrev / hasNext で活性制御）。
 * - フォルダフィルタ ドロップダウン（F15.3 my_scope_folders 一覧 +「すべて」）。
 *   選択で store.setFolder() → ページ 0 リセット + 再取得。
 * - 表示順設定ダイアログ起動ボタン（⚙）。
 * - 横スクロール禁止（6 件固定 + ページ送り。カルーセル左右スワイプとのジェスチャ競合回避）。
 */
import type { ScopeTabType } from '~/types/dashboard-scope'
import type { ScopeFolder } from '~/types/scopeFolder'

const props = defineProps<{
  scopeType: ScopeTabType
}>()

const store = useScopeDashboardStore()
const foldersStore = useScopeFoldersStore()

const showOrderDialog = ref(false)

// 現在のタグページデータ（キャッシュ）。
const page = computed(() => store.tabPages[props.scopeType] ?? null)
const items = computed(() => page.value?.items ?? [])
const hasPrev = computed(() => page.value?.hasPrev ?? false)
const hasNext = computed(() => page.value?.hasNext ?? false)
const currentPage = computed(() =>
  props.scopeType === 'TEAM' ? store.teamTabPage : store.orgTabPage,
)
const totalPages = computed(() => page.value?.totalPages ?? 0)

// 現在選択中の scope ID。
const selectedScopeId = computed(() =>
  props.scopeType === 'TEAM' ? store.selectedTeamId : store.selectedOrgId,
)

// フォルダフィルタの選択肢（「すべて」+ F15.3 フォルダ一覧）。
const folders = computed<ScopeFolder[]>(() => foldersStore.foldersFor(props.scopeType))

// 空状態判定。
const isEmpty = computed(() => items.value.length === 0)
const emptyMessageKey = computed(() =>
  store.activeFolderId !== null
    ? 'scopeDashboard.tagBar.folderEmpty'
    : 'scopeDashboard.tagBar.empty',
)

// store の lastError を監視してインライン表示する（握り潰さない）。
const errorMessage = computed(() => store.lastError)

onMounted(async () => {
  // フォルダ一覧を遅延取得（ドロップダウン用）。失敗はドロップダウン非表示に留める。
  try {
    if (foldersStore.foldersFor(props.scopeType).length === 0) {
      await foldersStore.fetchAll(props.scopeType)
    }
  } catch (e) {
    // フォルダ取得失敗はフィルタ機能のみ縮退（タグ行本体は機能する）。
    console.error('[ScopeTabBar] folder fetch failed', e)
  }
})

function selectScope(scopeId: string) {
  if (props.scopeType === 'TEAM') {
    store.selectedTeamId = scopeId
  } else {
    store.selectedOrgId = scopeId
  }
  store.persistToStorage()
}

async function goPrevPage() {
  if (!hasPrev.value) return
  const next = Math.max(0, currentPage.value - 1)
  if (props.scopeType === 'TEAM') store.teamTabPage = next
  else store.orgTabPage = next
  store.persistToStorage()
  await store.loadTabs(props.scopeType, next)
}

async function goNextPage() {
  if (!hasNext.value) return
  const next = currentPage.value + 1
  if (props.scopeType === 'TEAM') store.teamTabPage = next
  else store.orgTabPage = next
  store.persistToStorage()
  await store.loadTabs(props.scopeType, next)
}

async function onFolderChange(folderId: number | null) {
  await store.setFolder(folderId)
}
</script>

<template>
  <div class="flex flex-col gap-2" :data-testid="`scope-tab-bar-${scopeType}`">
    <div class="flex items-center gap-2">
      <!-- フォルダフィルタ -->
      <Select
        v-if="folders.length > 0"
        :model-value="store.activeFolderId"
        :options="[{ id: null, name: $t('scopeDashboard.tagBar.filterAll') }, ...folders]"
        option-label="name"
        option-value="id"
        :aria-label="$t('scopeDashboard.tagBar.folderFilter')"
        class="field-bordered border-2 w-40 shrink-0"
        @update:model-value="onFolderChange"
      />

      <!-- タグチップ（横スクロール禁止: flex-wrap せず 6 件固定 + ページ送り） -->
      <div class="flex flex-1 items-center gap-2 overflow-hidden">
        <template v-if="!isEmpty">
          <button
            v-for="item in items"
            :key="item.scopeId"
            type="button"
            role="button"
            :aria-pressed="item.scopeId === selectedScopeId"
            class="flex shrink-0 items-center gap-2 rounded-full border px-3 py-1.5 text-sm transition-colors"
            :class="
              item.scopeId === selectedScopeId
                ? 'border-primary bg-primary text-primary-contrast'
                : 'border-surface-300 bg-surface-0 hover:bg-surface-100 dark:border-surface-600 dark:bg-surface-800'
            "
            @click="selectScope(item.scopeId)"
          >
            <Avatar
              :image="item.avatarUrl ?? undefined"
              :label="item.avatarUrl ? undefined : item.name.charAt(0)"
              :aria-label="item.name"
              shape="circle"
              size="normal"
              class="!h-6 !w-6 !text-xs"
            />
            <span class="max-w-28 truncate">{{ item.name }}</span>
            <Badge
              v-if="item.unreadCount > 0"
              :value="item.unreadCount"
              severity="danger"
            />
          </button>
        </template>
        <span v-else class="text-sm text-surface-500">{{ $t(emptyMessageKey) }}</span>
      </div>

      <!-- ページ送り -->
      <div v-if="totalPages > 1" class="flex shrink-0 items-center gap-1">
        <Button
          icon="pi pi-chevron-left"
          text
          rounded
          size="small"
          :disabled="!hasPrev"
          :aria-label="$t('scopeDashboard.tagBar.prevPage')"
          @click="goPrevPage"
        />
        <span class="text-xs text-surface-500">{{ currentPage + 1 }}/{{ totalPages }}</span>
        <Button
          icon="pi pi-chevron-right"
          text
          rounded
          size="small"
          :disabled="!hasNext"
          :aria-label="$t('scopeDashboard.tagBar.nextPage')"
          @click="goNextPage"
        />
      </div>

      <!-- 表示順設定 -->
      <Button
        v-if="!isEmpty"
        icon="pi pi-cog"
        text
        rounded
        size="small"
        :aria-label="$t('scopeDashboard.tagBar.reorder')"
        @click="showOrderDialog = true"
      />
    </div>

    <!-- エラー表示（握り潰さない） -->
    <Message v-if="errorMessage" severity="error" :closable="false">
      {{ $t(errorMessage) }}
    </Message>

    <ScopeTabOrderDialog
      v-model:visible="showOrderDialog"
      :scope-type="scopeType"
      :items="items"
    />
  </div>
</template>
