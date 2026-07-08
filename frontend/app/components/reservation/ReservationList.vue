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

/**
 * 一覧操作（承認/却下/キャンセル）成功時に emit する。#2179 で結線した「予約→枠表示」の
 * 逆方向（一覧操作→予約するタブの枠表示 refresh）を成立させるため、親（TeamReservationsPanel）
 * が SlotMatrixPicker/SlotGridPicker/SlotPicker の refresh をトリガーできるようにする。
 */
const emit = defineEmits<{ changed: [] }>()

const { t } = useI18n()
const reservationApi = useReservationApi()
const notification = useNotification()
const confirm = useConfirm()
const { handleApiError } = useErrorHandler()

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

// 承認 = PENDING→CONFIRMED（BE: POST /reservations/{id}/confirm）。
// グループ所属行（data.group が非null）への単票操作は 400=RESERVATION_042 で拒否されるため、
// グループAPI（POST /reservation-groups/{groupId}/confirm）へ回す（F03.4.3 §4 既存資産棚卸し#9）。
async function approve(data: ReservationResponse) {
  if (data.group?.groupId) {
    await reservationApi.confirmGroup(props.teamId, data.group.groupId)
  }
  else {
    await reservationApi.confirmReservation(props.teamId, data.id!)
  }
  notification.success(t('reservation.message.confirm_success'))
  await loadReservations()
  emit('changed')
}

// 却下 = 管理者キャンセル（BE: POST /reservations/{id}/cancel、理由付き）。グループ行は同様にグループAPIへ。
async function reject(data: ReservationResponse) {
  if (data.group?.groupId) {
    await reservationApi.cancelGroup(props.teamId, data.group.groupId, t('reservation.message.reject_reason'))
  }
  else {
    await reservationApi.cancelReservation(props.teamId, data.id!, t('reservation.message.reject_reason'))
  }
  notification.success(t('reservation.message.reject_success'))
  await loadReservations()
  emit('changed')
}

async function cancel(data: ReservationResponse) {
  const isGroup = !!data.group?.groupId
  confirm.require({
    message: isGroup
      ? t('reservation.group.cancel_confirm', { n: data.group?.groupSize ?? 1 })
      : t('reservation.dialog.cancel_confirm'),
    header: t('reservation.dialog.title'),
    icon: 'pi pi-exclamation-triangle',
    acceptLabel: t('reservation.button.cancel_reservation'),
    rejectLabel: t('reservation.button.back'),
    acceptClass: 'p-button-danger',
    accept: async () => {
      if (isGroup) {
        await reservationApi.cancelGroup(props.teamId, data.group!.groupId!)
      }
      else {
        await reservationApi.cancelReservation(props.teamId, data.id!)
      }
      notification.success(t('reservation.message.cancel_success'))
      await loadReservations()
      emit('changed')
    },
  })
}

/** BE エラー応答から RESERVATION_xxx コードを取り出す（GroupBookingDialog と同じ抽出パターン）。 */
function extractErrorCode(error: unknown): string | undefined {
  const apiError = error as { data?: { error?: { code?: string } } }
  return apiError?.data?.error?.code
}

/**
 * 本人キャンセル（mine モード・PENDING/CONFIRMED 行）。
 *
 * - 単枠行: 共通 API POST /api/v1/reservations/{id}/cancel（`cancelByUser`。team スコープの
 *   cancelReservation は @PreAuthorize isScopeAdmin の管理者専用のため使えない）
 * - グループ行: cancelGroup（BE は「本人=締切内 / ADMIN=常時」を Service 層で判定。
 *   グループ所属行への単票キャンセルは 400=RESERVATION_042 で拒否されるため必ずこちらへ回す）
 *
 * キャンセル期限の判定は BE が権威（FE での事前判定はしない）。期限超過は
 * 400=RESERVATION_026 で返るため、丁寧な文言（cancel_deadline_passed）で案内する。
 */
async function cancelMine(data: ReservationResponse) {
  const isGroup = !!data.group?.groupId
  confirm.require({
    message: isGroup
      ? t('reservation.group.cancel_confirm', { n: data.group?.groupSize ?? 1 })
      : t('reservation.dialog.cancel_confirm'),
    header: t('reservation.dialog.title'),
    icon: 'pi pi-exclamation-triangle',
    acceptLabel: t('reservation.button.cancel_reservation'),
    rejectLabel: t('reservation.button.back'),
    acceptClass: 'p-button-danger',
    accept: async () => {
      try {
        if (isGroup) {
          await reservationApi.cancelGroup(props.teamId, data.group!.groupId!)
        }
        else {
          await reservationApi.cancelMyReservation(data.id!)
        }
        notification.success(t('reservation.message.cancel_success'))
        await loadReservations()
        emit('changed')
      }
      catch (err) {
        // 期限超過（026）は「なぜできないか＋次にどうするか」を丁寧に案内する。
        // それ以外は共通ハンドラへ（握りつぶさない）。
        if (extractErrorCode(err) === 'RESERVATION_026') {
          notification.error(t('reservation.message.cancel_deadline_passed'))
        }
        else {
          handleApiError(err)
        }
      }
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

// 予約直後に親（TeamReservationsPanel）から一覧を再読込させるための公開メソッド。
// 既存の MatchRequestList 等と同一パターン（defineExpose({ refresh })＋親は ref 経由で呼ぶ）。
defineExpose({ refresh: loadReservations })
</script>

<template>
  <div>
    <div class="mb-4">
      <Select v-model="statusFilter" :options="statusOptions" option-label="label" option-value="value" class="w-40" />
    </div>
    <DataTable :value="displayedReservations" :loading="loading" :lazy="mode === 'team'" paginator :rows="20" :total-records="mode === 'team' ? totalRecords : undefined" data-key="id" row-hover @page="onPage">
      <Column :header="t('reservation.column.datetime')" style="width: 200px">
        <template #body="{ data }">
          <div class="text-sm">
            <p class="font-medium">{{ data.slot?.slotDate }}</p>
            <p class="text-surface-500">
              {{ data.slot?.startTime }} - {{ data.group?.groupEndTime ?? data.slot?.endTime }}
            </p>
            <!-- F03.4.3 §5.6#10: グループ予約はメニュー名・枠数を併記（単枠は group=null） -->
            <p v-if="data.group" class="mt-0.5 text-xs text-primary">
              <i class="pi pi-link mr-1" />{{ data.group.menuName ?? t('reservation.group.title') }}
              ・{{ t('reservation.group.slot_count', { n: data.group.groupSize ?? 1 }) }}
            </p>
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
      <!-- 操作列: 管理者は承認/却下/キャンセル、mine モードは本人キャンセル（PENDING/CONFIRMED）。
           mine は API 段階で自分の予約しか返らないため他人予約への越権 UI にはならない。
           最終的な操作可否（期限含む）は BE が判定する。 -->
      <Column v-if="canManage || mode === 'mine'" :header="t('reservation.column.action')" style="width: 150px">
        <template #body="{ data }">
          <template v-if="canManage">
            <div v-if="data.status?.status === 'PENDING'" class="flex gap-1">
              <Button icon="pi pi-check" severity="success" text rounded size="small" @click="approve(data)" />
              <Button icon="pi pi-times" severity="danger" text rounded size="small" @click="reject(data)" />
            </div>
            <Button v-else-if="data.status?.status === 'CONFIRMED'" icon="pi pi-ban" text rounded size="small" severity="secondary" @click="cancel(data)" />
          </template>
          <Button
            v-else-if="data.status?.status === 'PENDING' || data.status?.status === 'CONFIRMED'"
            icon="pi pi-ban"
            :label="t('reservation.button.cancel_reservation')"
            text
            size="small"
            severity="secondary"
            :aria-label="t('reservation.button.cancel_reservation')"
            data-testid="my-reservation-cancel"
            @click="cancelMine(data)"
          />
        </template>
      </Column>
      <template #empty>
        <DashboardEmptyState icon="pi pi-calendar" :message="t('reservation.empty.no_reservations')" />
      </template>
    </DataTable>
  </div>
</template>
