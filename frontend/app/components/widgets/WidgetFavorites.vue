<script setup lang="ts">
// F02.9 Phase 2 — お気に入りウィジェット本体。
// 仕様: 設計書 docs/features/F02.9_favorites_widget.md §5.2
//   - 1〜10件は全件表示、11〜20件は先頭10件のみ表示し「さらに N 件表示」で展開
//   - vuedraggable で D&D 並び替え。500ms debounce で reorder API を叩く
//   - 削除は確認ダイアログ → API → トースト
//   - @edit は FavoriteQuickEditDialog（足軽3 実装）にバインドされる
import draggable from 'vuedraggable'
import type { UserFavoriteItem } from '~/types/favorite'
import FavoriteCard from '~/components/favorites/FavoriteCard.vue'
import FavoritesWidgetEmpty from '~/components/widgets/FavoritesWidgetEmpty.vue'
import FavoriteQuickEditDialog from '~/components/favorites/FavoriteQuickEditDialog.vue'

const { t } = useI18n()
const notification = useNotification()
const { showUndoToast } = useUndoToast()
const {
  items,
  isLoading,
  error,
  fetchFavorites,
  addFavorite,
  removeFavorite,
  reorderFavorites,
} = useFavoritesApi()

/** 折り畳み状態の閾値。設計書 §5.2「11件以上で折り畳み」に準拠。 */
const COLLAPSED_LIMIT = 10
const expanded = ref(false)

/**
 * vuedraggable は v-model 双方向束縛で内部配列を書き換える。
 * `items` は readonly なので、表示用にローカル ref へコピーして渡す。
 * `items` 更新時は同期する。
 */
const localItems = ref<UserFavoriteItem[]>([])
watch(
  items,
  (val) => {
    // useFavoritesApi が readonly な配列を返すため、書き込み可能にコピーし直す。
    // entity.editableFields も readonly なので明示的に複製する。
    localItems.value = val.map((it) => ({
      ...it,
      entity: {
        ...it.entity,
        editableFields: [...it.entity.editableFields],
      },
    }))
  },
  { immediate: true, deep: true },
)

const visibleItems = computed<UserFavoriteItem[]>({
  get: () => (expanded.value
    ? localItems.value
    : localItems.value.slice(0, COLLAPSED_LIMIT)),
  set: (newOrder) => {
    // D&D 後の並び順を localItems に反映する。折り畳み時は先頭 N 件だけが対象なので
    // 残りの件数（N+1 件目以降）はそのまま末尾に保持する。
    if (expanded.value) {
      localItems.value = newOrder
    } else {
      const tail = localItems.value.slice(COLLAPSED_LIMIT)
      localItems.value = [...newOrder, ...tail]
    }
  },
})

const overflowCount = computed(() =>
  Math.max(0, localItems.value.length - COLLAPSED_LIMIT),
)

/** 並び替え保存の debounce タイマー。連続 D&D を 500ms にまとめて API 呼び出し回数を抑える。 */
let reorderTimer: ReturnType<typeof setTimeout> | null = null
function onDragEnd() {
  if (reorderTimer) clearTimeout(reorderTimer)
  reorderTimer = setTimeout(async () => {
    try {
      await reorderFavorites(localItems.value.map((it) => it.favoriteId))
      notification.success(t('favorites.reorderSuccess'))
    } catch {
      notification.error(t('favorites.saveError'))
    }
  }, 500)
}

function handleOpen(item: UserFavoriteItem) {
  if (!item.entity.pageUrl) return
  navigateTo(item.entity.pageUrl)
}

/** クイック編集ダイアログの編集対象。null のときダイアログは非表示。 */
const editingFavorite = ref<UserFavoriteItem | null>(null)

function handleEdit(item: UserFavoriteItem) {
  editingFavorite.value = item
}

/** クイック編集ダイアログの保存完了通知 → 一覧再取得（name/icon の即時反映）。 */
async function onFavoriteSaved() {
  await refresh()
}

// ADHD 配慮 AC-15: 確認ダイアログを廃止し、即時削除 + Undo Toast に置換する。
// お気に入り削除は可逆（同一 entity を再登録すれば復元できる）ため、
// Undo では addFavorite(entityType, entityId) を呼び直して元の状態に戻す。
async function handleRemove(item: UserFavoriteItem) {
  try {
    await removeFavorite(item.favoriteId)
    showUndoToast({
      summary: t('favorites.removeSuccess'),
      undoLabel: t('button.undo'),
      severity: 'info',
      onUndo: async () => {
        try {
          await addFavorite(item.entityType, item.entityId)
          notification.success(t('favorites.restoredToast'))
        } catch {
          notification.error(t('favorites.restoreFailed'))
        }
      },
    })
  } catch {
    notification.error(t('favorites.saveError'))
  }
}

async function refresh() {
  try {
    await fetchFavorites()
  } catch {
    // error ref に積まれるので UI 側でエラー表示。トーストは二重表示になるので出さない
  }
}

onMounted(refresh)
</script>

<template>
  <DashboardWidgetCard
    :title="t('favorites.title')"
    icon="pi pi-star"
    :loading="isLoading"
    refreshable
    data-testid="widget-favorites"
    @refresh="refresh"
  >
    <div
      v-if="error"
      class="text-sm text-red-600 dark:text-red-400 py-4 text-center"
      data-testid="widget-favorites-error"
    >
      {{ t('favorites.loadError') }}
    </div>

    <FavoritesWidgetEmpty
      v-else-if="!isLoading && localItems.length === 0"
    />

    <template v-else>
      <draggable
        v-model="visibleItems"
        item-key="favoriteId"
        handle=".drag-handle"
        :animation="150"
        ghost-class="opacity-30"
        data-testid="widget-favorites-list"
        @end="onDragEnd"
      >
        <template #item="{ element }">
          <FavoriteCard
            :item="element"
            @open="handleOpen(element)"
            @edit="handleEdit(element)"
            @remove="handleRemove(element)"
          />
        </template>
      </draggable>

      <button
        v-if="overflowCount > 0"
        type="button"
        class="mt-2 w-full text-sm text-primary hover:underline py-1"
        data-testid="widget-favorites-toggle"
        @click="expanded = !expanded"
      >
        {{ expanded ? t('favorites.collapse') : t('favorites.showMore', { count: overflowCount }) }}
      </button>
    </template>

    <FavoriteQuickEditDialog
      v-model="editingFavorite"
      @saved="onFavoriteSaved"
    />
  </DashboardWidgetCard>
</template>
