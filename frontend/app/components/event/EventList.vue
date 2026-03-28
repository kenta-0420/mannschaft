<script setup lang="ts">
import type { EventResponse } from '~/types/event'

const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: number
  canEdit: boolean
  canDelete: boolean
}>()

const emit = defineEmits<{
  select: [eventId: number]
}>()

const eventApi = useEventApi()
const notification = useNotification()

const events = ref<EventResponse[]>([])
const totalRecords = ref(0)
const loading = ref(true)
const page = ref(0)
const rows = ref(20)

// フィルター
const statusFilter = ref('')

const statusOptions = [
  { label: '全て', value: '' },
  { label: '下書き', value: 'DRAFT' },
  { label: '公開中', value: 'PUBLISHED' },
  { label: 'キャンセル', value: 'CANCELLED' },
  { label: '終了', value: 'CLOSED' },
]

function statusSeverity(status: string) {
  switch (status) {
    case 'DRAFT':
      return 'secondary'
    case 'PUBLISHED':
      return 'success'
    case 'CANCELLED':
      return 'danger'
    case 'CLOSED':
      return 'warn'
    default:
      return 'info'
  }
}

function statusLabel(status: string) {
  switch (status) {
    case 'DRAFT':
      return '下書き'
    case 'PUBLISHED':
      return '公開中'
    case 'CANCELLED':
      return 'キャンセル'
    case 'CLOSED':
      return '終了'
    default:
      return status
  }
}

async function loadEvents() {
  loading.value = true
  try {
    const res = await eventApi.listEvents(props.scopeType, props.scopeId, {
      status: statusFilter.value || undefined,
      page: page.value,
      size: rows.value,
    })
    events.value = res.data
    totalRecords.value = res.meta.totalElements
  } catch {
    events.value = []
  } finally {
    loading.value = false
  }
}

async function onDelete(eventId: number) {
  if (!confirm('このイベントを削除しますか？')) return
  try {
    await eventApi.deleteEvent(props.scopeType, props.scopeId, eventId)
    notification.success('イベントを削除しました')
    await loadEvents()
  } catch {
    notification.error('削除に失敗しました')
  }
}

function onPage(event: { page: number; rows: number }) {
  page.value = event.page
  rows.value = event.rows
  loadEvents()
}

function formatDate(dateStr: string | null): string {
  if (!dateStr) return '—'
  return new Date(dateStr).toLocaleDateString('ja-JP')
}

watch(statusFilter, () => {
  page.value = 0
  loadEvents()
})

onMounted(loadEvents)

defineExpose({ refresh: loadEvents })
</script>

<template>
  <div>
    <!-- フィルター -->
    <div class="mb-4 flex flex-wrap items-end gap-3">
      <div class="w-40">
        <label class="mb-1 block text-xs font-medium">ステータス</label>
        <Select
          v-model="statusFilter"
          :options="statusOptions"
          option-label="label"
          option-value="value"
          class="w-full"
        />
      </div>
    </div>

    <!-- テーブル -->
    <DataTable
      :value="events"
      :loading="loading"
      lazy
      paginator
      :rows="rows"
      :total-records="totalRecords"
      :rows-per-page-options="[10, 20, 50]"
      data-key="id"
      row-hover
      @page="onPage"
    >
      <Column header="イベント名" field="slug" style="min-width: 200px">
        <template #body="{ data }">
          <NuxtLink
            :to="`/${props.scopeType === 'team' ? 'teams' : 'organizations'}/${props.scopeId}/events/${data.id}`"
            class="font-medium hover:text-primary"
            @click.prevent="emit('select', data.id)"
          >
            {{ data.subtitle || data.slug || `イベント #${data.id}` }}
          </NuxtLink>
        </template>
      </Column>
      <Column header="ステータス" field="status" style="width: 120px">
        <template #body="{ data }">
          <Tag :value="statusLabel(data.status)" :severity="statusSeverity(data.status)" />
        </template>
      </Column>
      <Column header="公開" field="isPublic" style="width: 80px">
        <template #body="{ data }">
          <i
            :class="data.isPublic ? 'pi pi-eye text-green-500' : 'pi pi-eye-slash text-surface-400'"
          />
        </template>
      </Column>
      <Column header="参加者" style="width: 100px">
        <template #body="{ data }">
          {{ data.registrationCount
          }}<span v-if="data.maxCapacity" class="text-surface-400"> / {{ data.maxCapacity }}</span>
        </template>
      </Column>
      <Column header="受付開始" style="width: 120px">
        <template #body="{ data }">
          {{ formatDate(data.registrationStartsAt) }}
        </template>
      </Column>
      <Column header="作成日" field="createdAt" style="width: 120px">
        <template #body="{ data }">
          {{ formatDate(data.createdAt) }}
        </template>
      </Column>
      <Column v-if="canEdit || canDelete" header="操作" style="width: 100px">
        <template #body="{ data }">
          <div class="flex gap-1">
            <Button
              v-if="canEdit"
              icon="pi pi-eye"
              text
              rounded
              size="small"
              @click="emit('select', data.id)"
            />
            <Button
              v-if="canDelete"
              icon="pi pi-trash"
              text
              rounded
              size="small"
              severity="danger"
              @click="onDelete(data.id)"
            />
          </div>
        </template>
      </Column>
      <template #empty>
        <DashboardEmptyState icon="pi pi-calendar" message="イベントはありません" />
      </template>
    </DataTable>
  </div>
</template>
