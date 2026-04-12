<script setup lang="ts">
import type { AuditLog, AuditLogParams, EventCategory } from '~/types/audit-log'

definePageMeta({ middleware: 'auth' })

const auditLogApi = useAuditLogApi()
const notification = useNotification()

const logs = ref<AuditLog[]>([])
const totalRecords = ref(0)
const loading = ref(true)
const page = ref(0)

const filters = ref<AuditLogParams>({
  eventCategory: undefined,
  from: '',
  to: '',
})

const categoryOptions: { label: string; value: EventCategory | undefined }[] = [
  { label: 'すべて', value: undefined },
  { label: '認証', value: 'AUTH' },
  { label: 'メンバー', value: 'MEMBER' },
  { label: 'コンテンツ', value: 'CONTENT' },
  { label: '管理', value: 'ADMIN' },
  { label: 'システム', value: 'SYSTEM' },
]

async function loadLogs() {
  loading.value = true
  try {
    const res = await auditLogApi.listAll({
      ...filters.value,
      page: page.value,
      size: 30,
    })
    logs.value = res.data
    totalRecords.value = res.meta.totalElements
  } catch {
    notification.error('監査ログの取得に失敗しました')
  } finally {
    loading.value = false
  }
}

function onPage(event: { page: number }) {
  page.value = event.page
  loadLogs()
}

function applyFilter() {
  page.value = 0
  loadLogs()
}

onMounted(loadLogs)
</script>

<template>
  <div class="mx-auto max-w-6xl">
    <PageHeader title="監査ログ" />

    <div class="mb-4 flex flex-wrap items-end gap-3">
      <div>
        <label class="mb-1 block text-xs font-medium">カテゴリ</label>
        <Select
          v-model="filters.eventCategory"
          :options="categoryOptions"
          option-label="label"
          option-value="value"
          class="w-40"
        />
      </div>
      <div>
        <label class="mb-1 block text-xs font-medium">開始日</label>
        <InputText v-model="filters.from" type="date" class="w-40" />
      </div>
      <div>
        <label class="mb-1 block text-xs font-medium">終了日</label>
        <InputText v-model="filters.to" type="date" class="w-40" />
      </div>
      <Button label="フィルタ適用" icon="pi pi-filter" size="small" @click="applyFilter" />
    </div>

    <DataTable
      :value="logs"
      :loading="loading"
      :lazy="true"
      :paginator="true"
      :rows="30"
      :total-records="totalRecords"
      :first="page * 30"
      data-key="id"
      striped-rows
      @page="onPage"
    >
      <template #empty>
        <div class="py-8 text-center text-surface-500">ログがありません</div>
      </template>
      <Column header="日時" style="width: 160px">
        <template #body="{ data }">
          <span class="text-sm">{{ new Date(data.createdAt).toLocaleString('ja-JP') }}</span>
        </template>
      </Column>
      <Column field="userName" header="ユーザー" />
      <Column header="カテゴリ">
        <template #body="{ data }">
          <Badge :value="data.eventCategory" severity="secondary" />
        </template>
      </Column>
      <Column field="eventType" header="イベント" />
      <Column field="ipAddress" header="IPアドレス" />
      <Column header="対象">
        <template #body="{ data }">
          {{ data.targetUserName ?? '-' }}
        </template>
      </Column>
    </DataTable>
  </div>
</template>
