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
    if (props.mode === 'mine') {
      // BE GET /reservations/my は全件返却（meta なし・status/page クエリ非対応）。
      // 件数は data.length で算出する。status 絞り込み・ページングはクライアント側で行う
      // （displayedReservations と DataTable 非 lazy）。他人の予約・氏名は API 段階で一切返らない。
      const res = await reservationApi.listMyReservations()
      reservations.value = res.data
      totalRecords.value = res.data.length
    }
    else {
      // team モードは BE がサーバー側で status 絞り込み・ページングを行い meta を返す。
      const res = await reservationApi.listReservations(props.teamId, {
        status: statusFilter.value || undefined,
        page: page.value,
        size: 20,
      })
      reservations.value = res.data as ReservationResponse[]
      totalRecords.value = res.meta.totalElements
    }
  }
  catch {
    // 取得失敗は症状を隠さずユーザーへ通知する（catch での握り潰し＝対処療法を避ける）。
    // 表示は空へフォールバックしつつ、失敗した事実は隠さない。
    reservations.value = []
    totalRecords.value = 0
    notification.error(t('reservation.message.my_load_failed'))
  }
  finally { loading.value = false }
}

// mine モードの status 絞り込みはクライアント側で行う（BE /my はフィルタ非対応）。
// team モードは既にサーバー側で絞り込み済みのためそのまま表示する。
const displayedReservations = computed<ReservationResponse[]>(() => {
  if (props.mode === 'mine' && statusFilter.value) {
    return reservations.value.filter(r => r.status?.status === statusFilter.value)
  }
  return reservations.value
})

// team のみサーバー側ページング。mine は DataTable のクライアント側ページングに委ねる。
function onPage(e: { page: number }) {
  if (props.mode === 'team') {
    page.value = e.page
    loadReservations()
  }
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

watch(statusFilter, () => {
  // team はサーバー再取得。mine はクライアント側フィルタ（displayedReservations）が反応するため再取得不要。
  if (props.mode === 'team') {
    page.value = 0
    loadReservations()
  }
})
onMounted(loadReservations)
</script>

<template>
  <div>
    <ConfirmDialog />
    <div class="mb-4">
      <Select v-model="statusFilter" :options="statusOptions" option-label="label" option-value="value" class="w-40" />
    </div>
    <DataTable :value="displayedReservations" :loading="loading" :lazy="mode === 'team'" paginator :rows="20" :total-records="mode === 'team' ? totalRecords : undefined" data-key="id" row-hover @page="onPage">
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
