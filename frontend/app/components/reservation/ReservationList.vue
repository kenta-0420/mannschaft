<script setup lang="ts">
const props = defineProps<{
  teamId: number
  canManage: boolean
}>()

const reservationApi = useReservationApi()
const notification = useNotification()
const confirm = useConfirm()

interface Reservation {
  id: number; lineName: string; date: string; startTime: string; endTime: string
  displayName: string; status: string; serviceNotes: string | null; createdAt: string
}

const reservations = ref<Reservation[]>([])
const totalRecords = ref(0)
const loading = ref(true)
const page = ref(0)
const statusFilter = ref('')

const statusOptions = [
  { label: '全て', value: '' },
  { label: '保留中', value: 'PENDING' },
  { label: '承認済', value: 'APPROVED' },
  { label: '却下', value: 'REJECTED' },
  { label: 'キャンセル', value: 'CANCELLED' },
  { label: '完了', value: 'COMPLETED' },
]

const statusSeverity: Record<string, string> = {
  PENDING: 'warn', APPROVED: 'success', REJECTED: 'danger',
  CANCELLED: 'secondary', COMPLETED: 'info', NO_SHOW: 'danger',
}

const statusLabel: Record<string, string> = {
  PENDING: '保留中', APPROVED: '承認済', REJECTED: '却下',
  CANCELLED: 'キャンセル', COMPLETED: '完了', NO_SHOW: '無断欠席',
}

async function loadReservations() {
  loading.value = true
  try {
    const res = await reservationApi.listReservations(props.teamId, { status: statusFilter.value || undefined, page: page.value, size: 20 })
    reservations.value = res.data as Reservation[]
    totalRecords.value = res.meta.totalElements
  }
  catch (e) {
    console.error('予約一覧の取得に失敗しました', e)
    reservations.value = []
  }
  finally { loading.value = false }
}

async function approve(id: number) {
  await reservationApi.approveReservation(props.teamId, id)
  notification.success('予約を承認しました')
  await loadReservations()
}

async function reject(id: number) {
  await reservationApi.rejectReservation(props.teamId, id)
  notification.success('予約を却下しました')
  await loadReservations()
}

async function cancel(id: number) {
  confirm.require({
    message: 'この予約をキャンセルしますか？',
    header: '確認',
    icon: 'pi pi-exclamation-triangle',
    acceptLabel: 'キャンセルする',
    rejectLabel: '戻る',
    acceptClass: 'p-button-danger',
    accept: async () => {
      await reservationApi.cancelReservation(props.teamId, id)
      notification.success('予約をキャンセルしました')
      await loadReservations()
    },
  })
}

watch(statusFilter, () => { page.value = 0; loadReservations() })
onMounted(loadReservations)
</script>

<template>
  <div>
    <ConfirmDialog />
    <div class="mb-4">
      <Select v-model="statusFilter" :options="statusOptions" option-label="label" option-value="value" class="w-40" />
    </div>
    <DataTable :value="reservations" :loading="loading" lazy paginator :rows="20" :total-records="totalRecords" data-key="id" row-hover @page="(e: { page: number }) => { page = e.page; loadReservations() }">
      <Column header="日時" style="width: 160px">
        <template #body="{ data }">
          <div class="text-sm">
            <p class="font-medium">{{ data.date }}</p>
            <p class="text-surface-500">{{ data.startTime }} - {{ data.endTime }}</p>
          </div>
        </template>
      </Column>
      <Column field="lineName" header="ライン" style="width: 120px" />
      <Column field="displayName" header="予約者" style="width: 140px" />
      <Column header="ステータス" style="width: 100px">
        <template #body="{ data }">
          <Tag :value="statusLabel[data.status] ?? data.status" :severity="statusSeverity[data.status] ?? 'secondary'" rounded />
        </template>
      </Column>
      <Column v-if="canManage" header="操作" style="width: 150px">
        <template #body="{ data }">
          <div v-if="data.status === 'PENDING'" class="flex gap-1">
            <Button icon="pi pi-check" severity="success" text rounded size="small" @click="approve(data.id)" />
            <Button icon="pi pi-times" severity="danger" text rounded size="small" @click="reject(data.id)" />
          </div>
          <Button v-else-if="data.status === 'APPROVED'" icon="pi pi-ban" text rounded size="small" severity="secondary" @click="cancel(data.id)" />
        </template>
      </Column>
      <template #empty>
        <DashboardEmptyState icon="pi pi-calendar" message="予約はありません" />
      </template>
    </DataTable>
  </div>
</template>
