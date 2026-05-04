<script setup lang="ts">
import type { FailedEventResponse, FailedEventStatus } from '~/types/shiftBudget'

/**
 * F08.7 Phase 10-γ: 失敗イベント管理 (`/admin/shift-budget/failed-events`)。
 *
 * <p>Phase 10-β で追加した {@code shift_budget_failed_events} テーブルの管理 UI。
 * 状態別フィルタ + 個別 retry / resolve ボタン。</p>
 *
 * <p>権限:</p>
 * <ul>
 *   <li>BUDGET_VIEW: 一覧閲覧</li>
 *   <li>BUDGET_ADMIN: retry / resolve（バックエンドが権限不足時 403）</li>
 * </ul>
 */
definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const scopeStore = useScopeStore()
const notification = useNotification()
const api = useShiftBudgetApi()

const organizationId = computed(() => {
  if (scopeStore.current.type !== 'organization') return null
  return scopeStore.current.id
})

const events = ref<FailedEventResponse[]>([])
const loading = ref(false)
const page = ref(0)
const pageSize = ref(20)

const statusFilter = ref<FailedEventStatus | null>(null)

const statusOptions = computed<{ label: string; value: FailedEventStatus | null }[]>(() => [
  { label: t('shiftBudget.failedEvent.filterAll'), value: null },
  { label: t('shiftBudget.failedEvent.statusValue.PENDING'), value: 'PENDING' },
  { label: t('shiftBudget.failedEvent.statusValue.RETRYING'), value: 'RETRYING' },
  { label: t('shiftBudget.failedEvent.statusValue.EXHAUSTED'), value: 'EXHAUSTED' },
  { label: t('shiftBudget.failedEvent.statusValue.MANUAL_RESOLVED'), value: 'MANUAL_RESOLVED' },
])

const retryConfirmVisible = ref(false)
const retryTarget = ref<FailedEventResponse | null>(null)
const resolveConfirmVisible = ref(false)
const resolveTarget = ref<FailedEventResponse | null>(null)

async function load() {
  if (!organizationId.value) {
    events.value = []
    return
  }
  loading.value = true
  try {
    events.value = await api.listFailedEvents(
      organizationId.value,
      statusFilter.value,
      page.value,
      pageSize.value,
    )
  }
  catch {
    notification.error(t('shiftBudget.failedEvent.loadError'))
  }
  finally {
    loading.value = false
  }
}

function statusSeverity(status: string): 'info' | 'warn' | 'danger' | 'success' | 'secondary' {
  switch (status) {
    case 'PENDING':
      return 'info'
    case 'RETRYING':
      return 'warn'
    case 'EXHAUSTED':
      return 'danger'
    case 'MANUAL_RESOLVED':
      return 'success'
    default:
      return 'secondary'
  }
}

function statusLabel(status: string): string {
  const key = `shiftBudget.failedEvent.statusValue.${status}`
  // 未知ステータスは raw 値を返す
  return t(key, status)
}

function formatDateTime(value: string | null): string {
  if (!value) return '-'
  return new Date(value).toLocaleString('ja-JP')
}

function openRetry(event: FailedEventResponse) {
  retryTarget.value = event
  retryConfirmVisible.value = true
}

async function confirmRetry() {
  if (!retryTarget.value || !organizationId.value) return
  try {
    await api.retryFailedEvent(organizationId.value, retryTarget.value.id)
    notification.success(t('shiftBudget.failedEvent.retrySuccess'))
    retryConfirmVisible.value = false
    retryTarget.value = null
    await load()
  }
  catch {
    notification.error(t('shiftBudget.failedEvent.retryError'))
  }
}

function openResolve(event: FailedEventResponse) {
  resolveTarget.value = event
  resolveConfirmVisible.value = true
}

async function confirmResolve() {
  if (!resolveTarget.value || !organizationId.value) return
  try {
    await api.resolveFailedEvent(organizationId.value, resolveTarget.value.id)
    notification.success(t('shiftBudget.failedEvent.resolveSuccess'))
    resolveConfirmVisible.value = false
    resolveTarget.value = null
    await load()
  }
  catch {
    notification.error(t('shiftBudget.failedEvent.resolveError'))
  }
}

watch(organizationId, () => load())
watch(statusFilter, () => {
  page.value = 0
  load()
})
onMounted(load)
</script>

<template>
  <div class="mx-auto max-w-7xl">
    <div class="mb-4 flex items-center justify-between">
      <PageHeader :title="t('shiftBudget.failedEvent.title')" />
      <Select
        v-model="statusFilter"
        :options="statusOptions"
        option-label="label"
        option-value="value"
        :placeholder="t('shiftBudget.failedEvent.filterStatus')"
        class="w-56"
      />
    </div>
    <p class="mb-4 text-sm text-surface-500">{{ t('shiftBudget.failedEvent.subtitle') }}</p>

    <Message v-if="!organizationId" severity="warn" :closable="false" class="mb-4">
      {{ t('shiftBudget.scope.selectOrganization') }}
    </Message>

    <PageLoading v-else-if="loading" />

    <DataTable v-else :value="events" striped-rows data-key="id">
      <template #empty>
        <div class="py-8 text-center text-surface-500">{{ t('shiftBudget.failedEvent.empty') }}</div>
      </template>
      <Column field="id" :header="t('shiftBudget.failedEvent.id')" style="width: 80px" />
      <Column field="event_type" :header="t('shiftBudget.failedEvent.eventType')" style="width: 200px" />
      <Column :header="t('shiftBudget.failedEvent.sourceId')" style="width: 100px">
        <template #body="{ data }: { data: FailedEventResponse }">
          <span>{{ data.source_id ?? '-' }}</span>
        </template>
      </Column>
      <Column :header="t('shiftBudget.failedEvent.errorMessage')">
        <template #body="{ data }: { data: FailedEventResponse }">
          <span class="line-clamp-2 text-sm">{{ data.error_message ?? '-' }}</span>
        </template>
      </Column>
      <Column :header="t('shiftBudget.failedEvent.retryCount')" style="width: 100px">
        <template #body="{ data }: { data: FailedEventResponse }">
          <span class="font-medium">{{ data.retry_count }}</span>
        </template>
      </Column>
      <Column :header="t('shiftBudget.failedEvent.lastRetriedAt')" style="width: 180px">
        <template #body="{ data }: { data: FailedEventResponse }">
          <span class="text-sm">{{ formatDateTime(data.last_retried_at) }}</span>
        </template>
      </Column>
      <Column :header="t('shiftBudget.failedEvent.status')" style="width: 160px">
        <template #body="{ data }: { data: FailedEventResponse }">
          <Tag :value="statusLabel(data.status)" :severity="statusSeverity(data.status)" />
        </template>
      </Column>
      <Column :header="t('shiftBudget.failedEvent.createdAt')" style="width: 180px">
        <template #body="{ data }: { data: FailedEventResponse }">
          <span class="text-sm">{{ formatDateTime(data.created_at) }}</span>
        </template>
      </Column>
      <Column :header="t('shiftBudget.failedEvent.actions')" style="width: 200px">
        <template #body="{ data }: { data: FailedEventResponse }">
          <div class="flex gap-1">
            <Button
              v-if="data.status !== 'MANUAL_RESOLVED'"
              :label="t('shiftBudget.failedEvent.retry')"
              size="small"
              severity="secondary"
              @click="openRetry(data)"
            />
            <Button
              v-if="data.status !== 'MANUAL_RESOLVED'"
              :label="t('shiftBudget.failedEvent.resolve')"
              size="small"
              severity="warn"
              outlined
              @click="openResolve(data)"
            />
          </div>
        </template>
      </Column>
    </DataTable>

    <Dialog
      v-model:visible="retryConfirmVisible"
      :header="t('shiftBudget.failedEvent.retry')"
      :style="{ width: '450px' }"
      modal
    >
      <p v-if="retryTarget" class="py-2">
        {{ t('shiftBudget.failedEvent.retryConfirm', { id: retryTarget.id }) }}
      </p>
      <template #footer>
        <div class="flex justify-end gap-2">
          <Button
            :label="t('shiftBudget.allocation.form.cancel')"
            severity="secondary"
            @click="retryConfirmVisible = false"
          />
          <Button :label="t('shiftBudget.failedEvent.retry')" @click="confirmRetry" />
        </div>
      </template>
    </Dialog>

    <Dialog
      v-model:visible="resolveConfirmVisible"
      :header="t('shiftBudget.failedEvent.resolve')"
      :style="{ width: '450px' }"
      modal
    >
      <p v-if="resolveTarget" class="py-2">
        {{ t('shiftBudget.failedEvent.resolveConfirm', { id: resolveTarget.id }) }}
      </p>
      <template #footer>
        <div class="flex justify-end gap-2">
          <Button
            :label="t('shiftBudget.allocation.form.cancel')"
            severity="secondary"
            @click="resolveConfirmVisible = false"
          />
          <Button
            :label="t('shiftBudget.failedEvent.resolve')"
            severity="warn"
            @click="confirmResolve"
          />
        </div>
      </template>
    </Dialog>
  </div>
</template>
