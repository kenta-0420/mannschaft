<script setup lang="ts">
import type { AuditLog } from '~/types/audit-log'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const teamId = computed(() => Number(route.params.id))
const auditLogApi = useAuditLogApi()
const notification = useNotification()

const logs = ref<AuditLog[]>([])
const totalRecords = ref(0)
const loading = ref(true)
const page = ref(0)

async function loadLogs() {
  loading.value = true
  try {
    const res = await auditLogApi.listByTeam(teamId.value, { page: page.value, size: 30 })
    logs.value = res.data
    totalRecords.value = res.meta.totalElements
  } catch {
    notification.error('監査ログの取得に失敗しました')
  } finally {
    loading.value = false
  }
}

onMounted(loadLogs)
</script>

<template>
  <div>
    <h1 class="mb-6 text-2xl font-bold">監査ログ</h1>
    <DataTable :value="logs" :loading="loading" :lazy="true" :paginator="true" :rows="30" :total-records="totalRecords" :first="page * 30" data-key="id" striped-rows @page="(e: { page: number }) => { page = e.page; loadLogs() }">
      <template #empty><div class="py-8 text-center text-surface-500">ログがありません</div></template>
      <Column header="日時" style="width: 160px"><template #body="{ data }">{{ new Date(data.createdAt).toLocaleString('ja-JP') }}</template></Column>
      <Column field="userName" header="ユーザー" />
      <Column header="カテゴリ"><template #body="{ data }"><Badge :value="data.eventCategory" severity="secondary" /></template></Column>
      <Column field="eventType" header="イベント" />
    </DataTable>
  </div>
</template>
