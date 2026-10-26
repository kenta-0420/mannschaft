<script setup lang="ts">
/**
 * 自分のキャンセル待ち一覧（F03.4.5 §6.1 W2-4-FE）。
 *
 * BE GET /api/v1/users/me/reservation-waitlist は全チーム横断・WAITING のみを返すため、
 * このチーム（props.teamId）分のみへ FE 側で絞り込む。既存の予約一覧タブ（ReservationList）の
 * 構造に倣い、新規ページは作らずタブ内セクションとして埋め込む想定。
 *
 * 🔴teamId は slug（`pages/teams/[slug]/reservations.vue` が `String(route.params.slug)` を渡す）。
 * 一方 `WaitlistEntryResponse.teamId` は BE の数値 DB id（`Long`）。両者は絶対に文字列一致しないため、
 * `useActivityScopeId().resolveScopeId('TEAM', slug)` で数値 id に解決してから比較する
 * （`EmergencyClosureForm.vue` の `resolveTeamId` と同型の既存作法・`feedback_copy_working_pattern_first`）。
 */
import type { components } from '~/types/generated'

type WaitlistEntryResponse = components['schemas']['WaitlistEntryResponse']

const props = defineProps<{ teamId: string }>()

const { t } = useI18n()
const reservationApi = useReservationApi()
const notification = useNotification()
const { handleApiError } = useErrorHandler()
const { resolveScopeId } = useActivityScopeId()

const entries = ref<WaitlistEntryResponse[]>([])
const loading = ref(true)
const cancellingId = ref<string | null>(null)
/** 解決済みの数値 teamId（キャッシュ）。 */
const numericTeamId = ref<number | null>(null)
/**
 * 直近に数値解決した slug。`props.teamId` とずれたら再解決する。
 * 🟡検分是正（2026-07-30）: このコンポーネントは永続シェル構成（`/teams/[slug]/reservations`）の下で
 * mount されたまま `props.teamId` だけが別チームの slug へ切り替わり得る。単に
 * `numericTeamId.value == null` だけをキャッシュ判定に使うと、切替後も古いチームの数値 id で
 * 絞り込み続け、別チームのキャンセル待ちを表示してしまう（自分のデータなので漏洩ではないが誤表示）。
 */
const resolvedSlug = ref<string | null>(null)

function fmt(time?: string): string {
  if (!time) return ''
  return time.length >= 5 ? time.slice(0, 5) : time
}

async function load() {
  loading.value = true
  try {
    if (resolvedSlug.value !== props.teamId) {
      numericTeamId.value = await resolveScopeId('TEAM', props.teamId)
      resolvedSlug.value = props.teamId
    }
    const res = await reservationApi.listMyWaitlist()
    entries.value = numericTeamId.value == null
      ? []
      : (res.data ?? []).filter(e => e.teamId === numericTeamId.value)
  }
  catch (error) {
    entries.value = []
    handleApiError(error)
  }
  finally {
    loading.value = false
  }
}

/** BE エラー応答から RESERVATION_xxx コードを取り出す（GroupBookingDialog と同じ抽出パターン）。 */
function extractErrorCode(error: unknown): string | undefined {
  const apiError = error as { data?: { error?: { code?: string } } }
  return apiError?.data?.error?.code
}

async function cancelEntry(entry: WaitlistEntryResponse) {
  if (entry.slotId == null) return
  cancellingId.value = entry.id ?? null
  try {
    await reservationApi.leaveWaitlist(props.teamId, entry.slotId)
    notification.success(t('reservation.waitlist.cancel_success'))
    await load()
  }
  catch (error) {
    if (extractErrorCode(error) === 'RESERVATION_046') {
      // 既に取消/変換済み（他タブでの操作等）。表示を最新化するだけで足りる。
      notification.error(t('reservation.waitlist.not_found'))
      await load()
    }
    else {
      handleApiError(error)
    }
  }
  finally {
    cancellingId.value = null
  }
}

onMounted(load)

// props.teamId が変わったら自動で再読込する（永続シェルで同一コンポーネントが別チームへ再利用される
// ケースの保険。親が明示的に refresh() を呼ばなくても正しいチームの一覧に追従する）。
watch(() => props.teamId, () => { void load() })

// 親（TeamReservationsPanel）から再読込させるための公開メソッド（既存 MatchRequestList 等と同一パターン）。
defineExpose({ refresh: load })
</script>

<template>
  <div>
    <p class="mb-2 text-xs font-semibold uppercase tracking-wide text-surface-500">
      {{ t('reservation.waitlist.my_list_title') }}
    </p>
    <div v-if="loading" class="space-y-2">
      <Skeleton height="2.5rem" width="100%" />
    </div>
    <div v-else-if="entries.length > 0" class="space-y-2">
      <div
        v-for="entry in entries"
        :key="entry.id"
        class="flex items-center justify-between rounded-lg border border-surface-200 p-3 text-sm dark:border-surface-600"
        :data-testid="`my-waitlist-entry-${entry.id}`"
      >
        <div>
          <p class="font-medium">{{ entry.slotDate }} {{ fmt(entry.startTime) }} - {{ fmt(entry.endTime) }}</p>
          <p v-if="entry.slotTitle" class="text-xs text-surface-500">{{ entry.slotTitle }}</p>
        </div>
        <Button
          :label="t('reservation.waitlist.cancel_button')"
          text
          size="small"
          severity="secondary"
          :loading="cancellingId === entry.id"
          :disabled="cancellingId !== null"
          :data-testid="`my-waitlist-cancel-${entry.id}`"
          @click="cancelEntry(entry)"
        />
      </div>
    </div>
    <DashboardEmptyState v-else icon="pi pi-clock" :message="t('reservation.waitlist.no_entries')" />
  </div>
</template>
