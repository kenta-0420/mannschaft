<script setup lang="ts">
/**
 * F22.1 タグ表示順設定ダイアログ。
 *
 * 設計書: docs/features/F22.1_swipe_scope_dashboard/03_security_ux.md §2.11
 * - vuedraggable で全所属スコープをドラッグ並べ替え。
 * - 確定で store.reorder(scopeType, orders) → PUT /scope-tabs/order（楽観更新 + 失敗ロールバック）。
 * - 多数スコープでもダイアログ内をスクロールして全件並べ替え可能。
 */
import draggable from 'vuedraggable'
import type { ScopeTabType, ScopeTabItem } from '~/types/dashboard-scope'

const props = defineProps<{
  visible: boolean
  scopeType: ScopeTabType
  /** 並べ替え対象の全所属スコープ（タグ）一覧。 */
  items: ScopeTabItem[]
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
}>()

const store = useScopeDashboardStore()

/** ダイアログ内で並べ替え中のローカル配列（確定するまで store には反映しない）。 */
const localItems = ref<ScopeTabItem[]>([])
const saving = ref(false)

// ダイアログを開くたびに最新の items でローカル配列を初期化する。
watch(
  () => props.visible,
  (open) => {
    if (open) {
      localItems.value = [...props.items]
    }
  },
  { immediate: true },
)

function close() {
  emit('update:visible', false)
}

async function onSave() {
  saving.value = true
  try {
    // 並べ替え後のインデックスを sortOrder にマッピングする。
    const orders = localItems.value.map((item, index) => ({
      scopeId: item.scopeId,
      sortOrder: index,
    }))
    await store.reorder(props.scopeType, orders)
  } finally {
    saving.value = false
    // store.lastError が立っていればタグ行側でトースト表示されるため、ここでは閉じる。
    close()
  }
}
</script>

<template>
  <Dialog
    :visible="visible"
    modal
    :header="$t('scopeDashboard.orderDialog.title')"
    :style="{ width: '28rem' }"
    @update:visible="emit('update:visible', $event)"
  >
    <div class="max-h-[60vh] overflow-y-auto">
      <draggable
        v-model="localItems"
        item-key="scopeId"
        handle=".drag-handle"
        :animation="150"
        ghost-class="opacity-30"
        data-testid="scope-tab-order-list"
      >
        <template #item="{ element }">
          <div
            class="mb-2 flex items-center gap-3 rounded-lg border border-surface-300 bg-surface-0 p-3 dark:border-surface-600 dark:bg-surface-800"
          >
            <i
              class="drag-handle pi pi-bars cursor-move text-surface-400"
              aria-hidden="true"
            />
            <Avatar
              :image="element.avatarUrl ?? undefined"
              :label="element.avatarUrl ? undefined : element.name.charAt(0)"
              :aria-label="element.name"
              shape="circle"
              size="normal"
            />
            <span class="min-w-0 flex-1 truncate font-medium">{{ element.name }}</span>
          </div>
        </template>
      </draggable>
    </div>

    <template #footer>
      <Button
        :label="$t('scopeDashboard.orderDialog.cancel')"
        text
        :disabled="saving"
        @click="close"
      />
      <Button
        :label="$t('scopeDashboard.orderDialog.save')"
        icon="pi pi-check"
        :loading="saving"
        @click="onSave"
      />
    </template>
  </Dialog>
</template>
