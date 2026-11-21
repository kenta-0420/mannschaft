<script setup lang="ts">
import type { CirculationDocumentListItem } from '~/types/circulation'

const props = defineProps<{
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: string
  canManage?: boolean
}>()

const emit = defineEmits<{
  select: [circulation: CirculationDocumentListItem]
  create: []
}>()

const { t } = useI18n()
const { listScopedCirculations, cancelScopedCirculation, deleteScopedCirculation } = useCirculationApi()
const confirm = useConfirm()
const { success } = useNotification()
const { handleApiError } = useErrorHandler()
const { relativeTime } = useRelativeTime()

const items = ref<CirculationDocumentListItem[]>([])
const loading = ref(false)
// 取得失敗フラグ。失敗時にスピナーを放置したり空状態（回覧がありません）で誤魔化さず、
// エラー面＋再試行を出して可視化する（症状を隠さない）。
const error = ref(false)
const statusFilter = ref<string | undefined>(undefined)

const statusOptions = [
  { label: 'すべて', value: undefined },
  { label: '下書き', value: 'DRAFT' },
  { label: '回覧中', value: 'ACTIVE' },
  { label: '完了', value: 'COMPLETED' },
  { label: 'キャンセル', value: 'CANCELLED' },
]

async function loadItems() {
  loading.value = true
  error.value = false
  try {
    const res = await listScopedCirculations(
      props.scopeType === 'TEAM' ? 'team' : 'organization',
      props.scopeId,
      { status: statusFilter.value },
    )
    items.value = res.data
  } catch {
    // 失敗はインラインのエラー面（＋再試行）で可視化する。トーストは重複通知になるため出さない。
    error.value = true
  } finally {
    loading.value = false
  }
}

/** 取消できる状態（BE 設計: DRAFT / ACTIVE → CANCELLED）。 */
function canCancel(item: CirculationDocumentListItem): boolean {
  return !!props.canManage && (item.status === 'DRAFT' || item.status === 'ACTIVE')
}

/** 削除できる状態（BE 設計: DRAFT / CANCELLED のみ）。 */
function canDelete(item: CirculationDocumentListItem): boolean {
  return !!props.canManage && (item.status === 'DRAFT' || item.status === 'CANCELLED')
}

const apiScope = computed(() => (props.scopeType === 'TEAM' ? 'team' : 'organization'))

/** 確認ダイアログ → API → 一覧再取得。失敗は握りつぶさず利用者へ表示する。 */
function confirmAndRun(
  item: CirculationDocumentListItem,
  kind: 'cancel' | 'delete',
) {
  confirm.require({
    header: t(`circulation.list.${kind}.confirm_title`),
    message: t(`circulation.list.${kind}.confirm_message`, { title: item.title }),
    icon: 'pi pi-exclamation-triangle',
    acceptLabel: t(`circulation.list.${kind}.accept`),
    rejectLabel: t('circulation.list.back'),
    acceptProps: { severity: 'danger' },
    accept: async () => {
      try {
        // 詳細 EP と同様、パス変数は数値 ID（item.scopeId）。props.scopeId は slug。
        const scopeId = String(item.scopeId)
        if (kind === 'cancel') await cancelScopedCirculation(apiScope.value, scopeId, item.id)
        else await deleteScopedCirculation(apiScope.value, scopeId, item.id)
      } catch (err) {
        handleApiError(err, t(`circulation.list.${kind}.failed`))
        return
      }
      success(t(`circulation.list.${kind}.success`))
      await loadItems()
    },
  })
}

function getStatusClass(status: string): string {
  switch (status) {
    case 'DRAFT': return 'bg-surface-100 text-surface-600'
    case 'ACTIVE': return 'bg-blue-100 text-blue-700'
    case 'COMPLETED': return 'bg-green-100 text-green-700'
    case 'CANCELLED': return 'bg-red-100 text-red-600'
    default: return 'bg-surface-100 text-surface-600'
  }
}

function getStatusLabel(status: string): string {
  const labels: Record<string, string> = { DRAFT: '下書き', ACTIVE: '回覧中', COMPLETED: '完了', CANCELLED: 'キャンセル' }
  return labels[status] || status
}

watch(statusFilter, () => loadItems())
onMounted(() => loadItems())

defineExpose({ refresh: loadItems })
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <Select v-model="statusFilter" :options="statusOptions" option-label="label" option-value="value" placeholder="ステータス" class="w-40" />
      <Button v-if="canManage" label="回覧作成" icon="pi pi-plus" @click="emit('create')" />
    </div>

    <div v-if="loading" class="flex justify-center py-8">
      <LoadingBounce />
    </div>

    <!-- 取得失敗のエラー面（空状態で誤魔化さず再試行を出す） -->
    <div v-else-if="error" class="py-12 text-center">
      <i class="pi pi-exclamation-triangle mb-3 text-4xl text-surface-400" aria-hidden="true" />
      <p class="text-surface-500 dark:text-surface-300">{{ t('common.scopeShell.load_error_title') }}</p>
      <Button
        class="mt-4"
        :label="t('common.scopeShell.retry')"
        icon="pi pi-refresh"
        size="small"
        outlined
        @click="loadItems"
      />
    </div>

    <div v-else class="flex flex-col gap-2">
      <div
        v-for="item in items"
        :key="item.id"
        class="flex items-center gap-2 rounded-xl border border-surface-300 bg-surface-0 transition-shadow hover:shadow-sm"
      >
      <button
        type="button"
        class="flex min-w-0 flex-1 items-center gap-4 p-4 text-left"
        @click="emit('select', item)"
      >
        <div class="min-w-0 flex-1">
          <div class="mb-1 flex items-center gap-2">
            <span :class="getStatusClass(item.status)" class="rounded px-2 py-0.5 text-xs font-medium">
              {{ getStatusLabel(item.status) }}
            </span>
            <h3 class="truncate text-sm font-semibold">{{ item.title }}</h3>
          </div>
          <div class="flex items-center gap-3 text-xs text-surface-400">
            <span v-if="item.createdByName">{{ item.createdByName }}</span>
            <span>{{ relativeTime(item.createdAt) }}</span>
            <span v-if="item.dueDate"><i class="pi pi-clock" /> {{ item.dueDate }}</span>
          </div>
        </div>
        <div class="text-right text-sm">
          <div class="font-medium">{{ item.stampedCount }}/{{ item.totalRecipientCount }}</div>
          <div class="text-xs text-surface-400">押印済み</div>
        </div>
      </button>
        <div v-if="canCancel(item) || canDelete(item)" class="flex shrink-0 gap-1 pr-3">
          <Button
            v-if="canCancel(item)"
            :data-testid="`circulation-cancel-${item.id}`"
            :label="t('circulation.list.cancel.button')"
            icon="pi pi-ban"
            size="small"
            severity="secondary"
            outlined
            @click="confirmAndRun(item, 'cancel')"
          />
          <Button
            v-if="canDelete(item)"
            :data-testid="`circulation-delete-${item.id}`"
            :label="t('circulation.list.delete.button')"
            icon="pi pi-trash"
            size="small"
            severity="danger"
            outlined
            @click="confirmAndRun(item, 'delete')"
          />
        </div>
      </div>

      <div v-if="items.length === 0" class="py-12 text-center">
        <i class="pi pi-file mb-3 text-4xl text-surface-300" />
        <p class="text-surface-400">回覧がありません</p>
      </div>
    </div>
  </div>
</template>
