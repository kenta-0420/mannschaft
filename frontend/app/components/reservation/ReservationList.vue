<script setup lang="ts">
import type { ReservationResponse } from '~/types/reservation'

const props = withDefaults(defineProps<{
  teamId: string
  canManage: boolean
  /**
   * 表示モード。
   * - 'team' … チーム全件（管理者向け・予約者名を含む）
   * - 'mine' … ログインユーザー自身の予約のみ（非管理者向け・他人の情報を一切表示しない）
   */
  mode?: 'team' | 'mine'
}>(), { mode: 'team' })

const { t } = useI18n()
const reservationApi = useReservationApi()
const notification = useNotification()
const confirm = useConfirm()

const reservations = ref<ReservationResponse[]>([])
const totalRecords = ref(0)
const loading = ref(true)
const page = ref(0)
const statusFilter = ref('')

const statusOptions = computed(() => [
  { label: t('reservation.filter.all'), value: '' },
  { label: t('reservation.status.PENDING'), value: 'PENDING' },
  { label: t('reservation.status.CONFIRMED'), value: 'CONFIRMED' },
  { label: t('reservation.status.CANCELLED'), value: 'CANCELLED' },
  { label: t('reservation.status.COMPLETED'), value: 'COMPLETED' },
  { label: t('reservation.status.NO_SHOW'), value: 'NO_SHOW' },
])

const statusSeverity: Record<string, string> = {
  PENDING: 'warn', CONFIRMED: 'success',
  CANCELLED: 'secondary', COMPLETED: 'info', NO_SHOW: 'danger',
}

function statusLabel(status?: string): string {
  if (!status) return ''
  const known = ['PENDING', 'CONFIRMED', 'CANCELLED', 'COMPLETED', 'NO_SHOW']
  return known.includes(status) ? t(`reservation.status.${status}`) : status
}

async function loadReservations() {
  loading.value = true
  try {
    // mine モードは自分の予約のみを取得する（他人の予約・氏名は API 段階で返らない）。
    const res = props.mode === 'mine'
      ? await reservationApi.listMyReservations({ status: statusFilter.value || undefined, page: page.value, size: 20 })
      : await reservationApi.listReservations(props.teamId, { status: statusFilter.value || undefined, page: page.value, size: 20 })
    reservations.value = res.data as ReservationResponse[]
    totalRecords.value = res.meta.totalElements
  }
  catch {
    // 取得失敗時は空表示にフォールバック
    reservations.value = []
  }
  finally { loading.value = false }
}

// 承認 = PENDING→CONFIRMED（BE: POST /reservations/{id}/confirm）
async function approve(id: number) {
  await reservationApi.confirmReservation(props.teamId, id)
  notification.success(t('reservation.message.confirm_success'))
  await loadReservations()
}

// 却下 = 管理者キャンセル（BE: POST /reservations/{id}/cancel、理由付き）
async function reject(id: number) {
  await reservationApi.cancelReservation(props.teamId, id, t('reservation.message.reject_reason'))
  notification.success(t('reservation.message.reject_success'))
  await loadReservations()
}

async function cancel(id: number) {
  confirm.require({
    message: t('reservation.dialog.cancel_confirm'),
    header: t('reservation.dialog.title'),
    icon: 'pi pi-exclamation-triangle',
    acceptLabel: t('reservation.button.cancel_reservation'),
    rejectLabel: t('reservation.button.back'),
    acceptClass: 'p-button-danger',
    accept: async () => {
      await reservationApi.cancelReservation(props.teamId, id)
      notification.success(t('reservation.message.cancel_success'))
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
      <Column :header="t('reservation.column.datetime')" style="width: 160px">
        <template #body="{ data }">
          <div class="text-sm">
            <p class="font-medium">{{ data.slot?.slotDate }}</p>
            <p class="text-surface-500">{{ data.slot?.startTime }} - {{ data.slot?.endTime }}</p>
          </div>
        </template>
      </Column>
      <Column :header="t('reservation.column.line')" style="width: 120px">
        <template #body="{ data }">{{ data.slot?.lineName }}</template>
      </Column>
      <Column v-if="mode === 'team'" :header="t('reservation.column.reserver')" style="width: 140px">
        <template #body="{ data }">{{ data.identifier?.userName }}</template>
      </Column>
      <Column :header="t('reservation.column.status')" style="width: 100px">
        <template #body="{ data }">
          <Tag :value="statusLabel(data.status?.status)" :severity="statusSeverity[data.status?.status] ?? 'secondary'" rounded />
        </template>
      </Column>
      <Column v-if="canManage" :header="t('reservation.column.action')" style="width: 150px">
        <template #body="{ data }">
          <div v-if="data.status?.status === 'PENDING'" class="flex gap-1">
            <Button icon="pi pi-check" severity="success" text rounded size="small" @click="approve(data.id)" />
            <Button icon="pi pi-times" severity="danger" text rounded size="small" @click="reject(data.id)" />
          </div>
          <Button v-else-if="data.status?.status === 'CONFIRMED'" icon="pi pi-ban" text rounded size="small" severity="secondary" @click="cancel(data.id)" />
        </template>
      </Column>
      <template #empty>
        <DashboardEmptyState icon="pi pi-calendar" :message="t('reservation.empty.no_reservations')" />
      </template>
    </DataTable>
  </div>
</template>
