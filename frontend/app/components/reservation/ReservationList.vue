<script setup lang="ts">
import type { ReservationResponse, RecurringCancelDto } from '~/types/reservation'
import type { ReservationCancelScope } from '~/composables/useReservationApi'

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

/**
 * series（定期予約）の PENDING を一括承認する（scope=SERIES・§6.2 W2-5-FE）。
 * 認可は各行に適用される（BE 側の isScopeAdmin ゲート）。単票承認と同じ確認ダイアログ作法を踏襲する。
 */
async function approveSeries(data: ReservationResponse) {
  if (!data.id) return
  confirm.require({
    message: t('reservation.recurring.confirm_series.confirm_message'),
    header: t('reservation.dialog.title'),
    icon: 'pi pi-check-circle',
    acceptLabel: t('reservation.recurring.confirm_series.button'),
    rejectLabel: t('reservation.button.back'),
    accept: async () => {
      const res = await reservationApi.confirmReservation(props.teamId, data.id!, 'SERIES')
      const confirmed = res.data.recurringConfirm?.confirmedCount ?? 0
      const skipped = res.data.recurringConfirm?.skippedWeeks?.length ?? 0
      notification.success(t('reservation.recurring.confirm_series.success', { confirmed, skipped }))
      await loadReservations()
      emit('changed')
    },
  })
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
  // series（定期予約）所属の単票行は「この回だけ/この回以降すべて」の2択を先に出す（§6.2 W2-5-FE）。
  // グループ行は W2-5 のスコープ外（設計書§6.2「スコープ外」）のため従来どおり単一確認のまま。
  if (!isGroup && data.recurringSeriesId) {
    cancelScopeTarget.value = data
    showCancelScopeDialog.value = true
    return
  }
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

// === 定期予約(series)キャンセル 2択（§6.2 W2-5-FE）===
const showCancelScopeDialog = ref(false)
const cancelScopeTarget = ref<ReservationResponse | null>(null)
const showCancelScopeResultDialog = ref(false)
const cancelScopeResultDetail = ref<RecurringCancelDto | null>(null)

const RECURRING_SKIP_REASONS = [
  'NOT_GENERATED', 'FULL', 'CLOSED', 'BLOCKED', 'ALREADY_RESERVED',
  'UNAVAILABLE', 'NOT_CANCELLABLE', 'CANCEL_DEADLINE_PASSED', 'NOT_PENDING',
] as const

/** スキップ理由(9値enum)を i18n 文言へ変換する。丸めずに全値を出し分ける（症状を隠さない原則）。 */
function skipReasonLabel(reason?: string): string {
  return reason && (RECURRING_SKIP_REASONS as readonly string[]).includes(reason)
    ? t(`reservation.recurring.skip_reason.${reason}`)
    : (reason ?? '')
}

/**
 * 2択のいずれかを実行する。「この回だけ」= THIS_ONLY（従来と同じ単票キャンセル・締切超過は400）、
 * 「この回以降すべて」= THIS_AND_FOLLOWING（series内の当該日以降のactive行・締切超過はスキップ明細・
 * 全回スキップでも0件の明細を正直に返す＝エラーにしない）。
 */
async function executeCancelScope(scope: ReservationCancelScope) {
  const data = cancelScopeTarget.value
  showCancelScopeDialog.value = false
  if (!data?.id) return
  try {
    const res = await reservationApi.cancelMyReservation(data.id, { scope })
    if (scope === 'THIS_AND_FOLLOWING') {
      // 成立0件（全回が締切超過等でスキップ）でもエラーにせず、理由つき明細を正直に見せる。
      cancelScopeResultDetail.value = res.data.recurringCancel ?? null
      showCancelScopeResultDialog.value = true
    }
    else {
      notification.success(t('reservation.message.cancel_success'))
    }
    await loadReservations()
    emit('changed')
  }
  catch (err) {
    if (extractErrorCode(err) === 'RESERVATION_026') {
      notification.error(t('reservation.message.cancel_deadline_passed'))
    }
    else {
      handleApiError(err)
    }
  }
  finally {
    cancelScopeTarget.value = null
  }
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
            <!-- 定期予約(series)所属の一目バッジ（§6.2 W2-5-FE・recurringSeriesId がトップレベルの唯一の判定材料） -->
            <Tag
              v-if="data.recurringSeriesId"
              :value="t('reservation.recurring.badge')"
              severity="info"
              rounded
              class="mt-0.5"
              data-testid="recurring-series-badge"
            />
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
              <!-- series 一括承認（scope=SERIES・§6.2 W2-5-FE・定期予約所属の PENDING のみ表示） -->
              <Button
                v-if="data.recurringSeriesId"
                icon="pi pi-check-double"
                severity="success"
                outlined
                rounded
                size="small"
                :aria-label="t('reservation.recurring.confirm_series.button')"
                data-testid="approve-series"
                @click="approveSeries(data)"
              />
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

    <!-- 定期予約(series)キャンセル範囲の2択（§6.2 W2-5-FE）。null 判定材料は recurringSeriesId のみ。 -->
    <Dialog
      v-model:visible="showCancelScopeDialog"
      :header="t('reservation.recurring.cancel_scope.dialog_title')"
      modal
      :style="{ width: '420px' }"
    >
      <p class="mb-4 text-sm text-surface-600 dark:text-surface-300">
        {{ t('reservation.dialog.cancel_confirm') }}
      </p>
      <div class="flex flex-col gap-2">
        <Button
          :label="t('reservation.recurring.cancel_scope.this_only')"
          severity="danger"
          outlined
          data-testid="cancel-scope-this-only"
          @click="executeCancelScope('THIS_ONLY')"
        />
        <Button
          :label="t('reservation.recurring.cancel_scope.this_and_following')"
          severity="danger"
          data-testid="cancel-scope-this-and-following"
          @click="executeCancelScope('THIS_AND_FOLLOWING')"
        />
      </div>
      <template #footer>
        <Button :label="t('reservation.button.back')" text @click="showCancelScopeDialog = false" />
      </template>
    </Dialog>

    <!-- 「この回以降すべて」の結果明細。成立0件でも正直に理由つきで見せる（黙殺しない）。 -->
    <Dialog
      v-model:visible="showCancelScopeResultDialog"
      :header="t('reservation.recurring.cancel_scope.dialog_title')"
      modal
      :style="{ width: '420px' }"
    >
      <div class="space-y-3">
        <p class="font-medium" data-testid="cancel-scope-result-summary">
          {{ t('reservation.recurring.cancel_scope.result_summary', {
            cancelled: cancelScopeResultDetail?.cancelledCount ?? 0,
            skipped: cancelScopeResultDetail?.skippedWeeks?.length ?? 0,
          }) }}
        </p>
        <Message
          v-if="(cancelScopeResultDetail?.cancelledCount ?? 0) === 0"
          severity="warn"
          :closable="false"
        >
          {{ t('reservation.recurring.cancel_scope.all_skipped_notice') }}
        </Message>
        <div
          v-if="(cancelScopeResultDetail?.skippedWeeks?.length ?? 0) > 0"
          class="rounded-lg bg-surface-50 p-3 text-sm dark:bg-surface-700/50"
        >
          <p class="mb-1 font-medium text-surface-600 dark:text-surface-300">
            {{ t('reservation.recurring.result.skipped_list_title') }}
          </p>
          <ul class="space-y-1">
            <li
              v-for="(w, idx) in cancelScopeResultDetail?.skippedWeeks"
              :key="`${w.date}-${idx}`"
              class="flex justify-between gap-2 text-xs"
            >
              <span>{{ w.date }}</span>
              <span class="text-surface-500">{{ skipReasonLabel(w.reason) }}</span>
            </li>
          </ul>
        </div>
      </div>
      <template #footer>
        <Button :label="t('reservation.recurring.close_button')" @click="showCancelScopeResultDialog = false" />
      </template>
    </Dialog>
  </div>
</template>
