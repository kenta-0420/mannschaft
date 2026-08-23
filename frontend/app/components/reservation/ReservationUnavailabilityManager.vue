<script setup lang="ts">
/**
 * 予約不可枠（機能B・F03.4 §4.B/§5.B）管理UI（ADMIN限定）
 *
 * - 対象スコープ（チーム全体=TEAM / 担当者=STAFF）を選択
 *   STAFF は「予約対象（ライン）」の default_staff_user_id を resource_id として保存する
 *   （担当者未設定のラインは対象選択不可）。
 * - テンプレート（全休/遅出/早上がり/中抜け/手入力）を選ぶと営業時間基準で開始〜終了を自動充填。
 *   手入力で微修正可。空（全日）も可。
 * - 登録前に impact API を呼び、overlap する既存予約が 1 件以上なら警告し登録ボタンを無効化する。
 *   競合したまま登録した場合は BE が 409（RESERVATION_027）で最終防御 → トースト表示。
 * - 登録済み予約不可枠の一覧表示＋削除。
 *
 * 最終ゲートは BE。FE は表示・入力補助に徹する。
 */
import dayjs from 'dayjs'
import type { components } from '~/types/generated'
import type { BlockedResourceType } from '~/composables/useReservationApi'

type BlockedTimeResponse = components['schemas']['BlockedTimeResponse'] & {
  endsNextDay?: boolean
  timeSlot?: NonNullable<components['schemas']['BlockedTimeResponse']['timeSlot']> & { endsNextDay?: boolean }
}
type BlockedTimeImpactResponse = components['schemas']['BlockedTimeImpactResponse']
type ReservationLineResponse = components['schemas']['ReservationLineResponse']
type BusinessHourResponse = components['schemas']['BusinessHourResponse']

const props = defineProps<{
  teamId: string
  disabled?: boolean
}>()

const { t } = useI18n()
const reservationApi = useReservationApi()
const notification = useNotification()
const { handleApiError } = useErrorHandler()
const { userTimezone } = useDatetime()

/**
 * 呼称の動的差し込み（F03.4.5 §5.2・要確認事項の判断）: targetLabel() の STAFF フォールバック
 * （resource.resourceName 未設定時の表示）に使う。scope.staff（「担当者」固定文言）自体は対象スコープ
 * 選択の意味論として残すが、一覧表示上の「呼称の欠落を埋める」フォールバックとしては動的呼称のほうが
 * 一貫する（家老指摘・殿の判断: 含める）。
 */
const { resourceName, load: loadResourceName } = useResourceName(computed(() => props.teamId))

// === テンプレート種別 ===
type TemplateKey = 'FULL_DAY' | 'LATE' | 'EARLY_LEAVE' | 'MIDDAY' | 'CUSTOM'

// === 状態 ===
const loading = ref(false)
const submitting = ref(false)
const blockedTimes = ref<BlockedTimeResponse[]>([])
const lines = ref<ReservationLineResponse[]>([])
const businessHours = ref<BusinessHourResponse[]>([])

// フォーム
const scope = ref<BlockedResourceType>('TEAM')
const selectedLineId = ref<number | null>(null)
const blockedDate = ref<Date | null>(new Date())
const template = ref<TemplateKey>('FULL_DAY')
const startTime = ref<string | null>(null)
const endTime = ref<string | null>(null)
const endsNextDay = ref(false)
const reason = ref<string>('')

/** 営業時間が取れずテンプレを算出できなかった場合の注意フラグ */
const templateNeedsManual = ref(false)

// impact
const impact = ref<BlockedTimeImpactResponse | null>(null)
const impactLoading = ref(false)
let impactToken = 0

// === 選択肢 ===
const scopeOptions = computed(() => [
  { label: t('reservation.unavailability.scope.team'), value: 'TEAM' as BlockedResourceType },
  { label: t('reservation.unavailability.scope.staff'), value: 'STAFF' as BlockedResourceType },
])

const templateOptions = computed(() => [
  { label: t('reservation.unavailability.template.full_day'), value: 'FULL_DAY' as TemplateKey },
  { label: t('reservation.unavailability.template.late'), value: 'LATE' as TemplateKey },
  { label: t('reservation.unavailability.template.early_leave'), value: 'EARLY_LEAVE' as TemplateKey },
  { label: t('reservation.unavailability.template.midday'), value: 'MIDDAY' as TemplateKey },
  { label: t('reservation.unavailability.template.custom'), value: 'CUSTOM' as TemplateKey },
])

/** 30分刻みの時刻オプション（00:00〜23:30） */
const timeOptions = computed(() => {
  const opts: Array<{ label: string; value: string }> = []
  for (let h = 0; h < 24; h++) {
    for (const m of [0, 30]) {
      const v = `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`
      opts.push({ label: v, value: v })
    }
  }
  return opts
})

/** STAFF 選択用のライン選択肢（担当者未設定ラインは選択不可） */
const lineOptions = computed(() =>
  lines.value
    .filter(l => l.meta?.isActive)
    .map((l) => {
      const staffId = l.meta?.defaultStaffUserId ?? null
      return {
        label: l.meta?.name ?? '',
        value: l.id ?? 0,
        staffUserId: staffId,
        disabled: staffId == null,
      }
    }),
)

/** 選択中ラインの default_staff_user_id（STAFF 時の resource_id） */
const resolvedStaffUserId = computed<number | null>(() => {
  if (scope.value !== 'STAFF') return null
  const opt = lineOptions.value.find(o => o.value === selectedLineId.value)
  return opt?.staffUserId ?? null
})

// === ユーティリティ ===
const WEEKDAY = ['SUN', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT'] as const

function toHm(value?: string | null): string {
  return value ? value.slice(0, 5) : ''
}

function formatDate(date: Date): string {
  return dayjs(date).tz(userTimezone.value).format('YYYY-MM-DD')
}

/** 選択日の曜日に対応する営業時間（is_open のみ） */
function businessHoursForSelectedDate(): { open: string; close: string } | null {
  if (!blockedDate.value) return null
  const dow = WEEKDAY[blockedDate.value.getDay()]
  const bh = businessHours.value.find(b => b.businessStatus?.dayOfWeek === dow)
  if (!bh?.businessStatus?.isOpen || !bh.businessStatus.openTime || !bh.businessStatus.closeTime) {
    return null
  }
  return { open: toHm(bh.businessStatus.openTime), close: toHm(bh.businessStatus.closeTime) }
}

// === テンプレート適用 ===
function applyTemplate(key: TemplateKey) {
  templateNeedsManual.value = false
  if (key === 'CUSTOM') {
    // 手入力: 既存の時刻を維持
    return
  }
  if (key === 'FULL_DAY') {
    startTime.value = null
    endTime.value = null
    return
  }
  if (key === 'MIDDAY') {
    // 昼休憩を不可（営業時間非依存の固定既定）
    startTime.value = '12:00'
    endTime.value = '13:00'
    return
  }
  // LATE / EARLY_LEAVE は営業時間基準
  const bh = businessHoursForSelectedDate()
  if (!bh) {
    // 営業時間未設定 → 手入力を促すフォールバック
    templateNeedsManual.value = true
    template.value = 'CUSTOM'
    return
  }
  if (key === 'LATE') {
    startTime.value = bh.open
    endTime.value = '12:00'
  }
  else if (key === 'EARLY_LEAVE') {
    startTime.value = '12:00'
    endTime.value = bh.close
  }
}

function onTemplateChange(next: TemplateKey) {
  template.value = next
  applyTemplate(next)
}

// 日付変更時、非 CUSTOM テンプレは営業時間基準を再算出
watch(blockedDate, () => {
  if (template.value !== 'CUSTOM') applyTemplate(template.value)
})

// === impact 判定 ===
/** 有効な impact リクエストパラメータ（不足時 null）。両時刻セット or 両 null（全日）のみ有効。 */
const effectiveRequest = computed(() => {
  if (!blockedDate.value) return null
  if (scope.value === 'STAFF' && resolvedStaffUserId.value == null) return null
  const hasStart = !!startTime.value
  const hasEnd = !!endTime.value
  // 片側だけの時刻は無効（BE も 400）
  if (hasStart !== hasEnd) return null
  if (hasStart && hasEnd && !endsNextDay.value && startTime.value! >= endTime.value!) return null
  return {
    date: formatDate(blockedDate.value),
    resourceType: scope.value,
    resourceId: scope.value === 'STAFF' ? resolvedStaffUserId.value ?? undefined : undefined,
    startTime: startTime.value ?? undefined,
    endTime: endTime.value ?? undefined,
    endsNextDay: endsNextDay.value || undefined,
  }
})

async function refreshImpact() {
  const req = effectiveRequest.value
  if (!req) {
    impact.value = null
    return
  }
  const token = ++impactToken
  impactLoading.value = true
  try {
    const res = await reservationApi.getBlockedTimeImpact(props.teamId, req)
    if (token === impactToken) impact.value = res.data
  }
  catch {
    // impact 取得失敗は登録可否に影響させない（BE の 409 が最終防御）
    if (token === impactToken) impact.value = null
  }
  finally {
    if (token === impactToken) impactLoading.value = false
  }
}

watch(effectiveRequest, refreshImpact, { deep: true })

/** overlap 予約があるか */
const hasConflict = computed(() => (impact.value?.affectedCount ?? 0) > 0)

/** 登録ボタンを無効化するか */
const submitDisabled = computed(() =>
  props.disabled
  || submitting.value
  || !effectiveRequest.value
  || impactLoading.value
  || hasConflict.value,
)

// === データ取得 ===
async function loadBlockedTimes() {
  loading.value = true
  try {
    // 今日〜1年先の予約不可枠を取得（過去日は作成不可なので今日起点で十分）
    const from = dayjs().tz(userTimezone.value).format('YYYY-MM-DD')
    const to = dayjs().tz(userTimezone.value).add(365, 'day').format('YYYY-MM-DD')
    const res = await reservationApi.listBlockedTimes(props.teamId, { from, to })
    blockedTimes.value = res.data ?? []
  }
  catch {
    blockedTimes.value = []
  }
  finally {
    loading.value = false
  }
}

async function loadLines() {
  try {
    const res = await reservationApi.getLines(props.teamId)
    lines.value = res.data ?? []
  }
  catch {
    lines.value = []
  }
}

async function loadBusinessHours() {
  try {
    const res = await reservationApi.getBusinessHours(props.teamId)
    businessHours.value = res.data ?? []
  }
  catch {
    businessHours.value = []
  }
}

// === 登録 ===
async function submit() {
  const req = effectiveRequest.value
  if (!req || submitDisabled.value) return
  submitting.value = true
  try {
    await reservationApi.createBlockedTime(props.teamId, {
      blockedDate: req.date,
      startTime: req.startTime,
      endTime: req.endTime,
      endsNextDay: req.endsNextDay,
      reason: reason.value.trim() || undefined,
      resourceType: req.resourceType,
      resourceId: req.resourceId,
    })
    notification.success(t('reservation.unavailability.message.create_success'))
    resetForm()
    await loadBlockedTimes()
  }
  catch (error) {
    const code = (error as { data?: { error?: { code?: string } } })?.data?.error?.code
    if (code === 'RESERVATION_027') {
      // overlap する予約が残ったまま登録した競合（最終防御）
      notification.error(
        t('dialog.error'),
        t('reservation.unavailability.error.has_active_reservations'),
      )
      await refreshImpact()
    }
    else {
      handleApiError(error)
    }
  }
  finally {
    submitting.value = false
  }
}

function resetForm() {
  reason.value = ''
  // 対象・日付・テンプレはそのまま残し、連続登録しやすくする
  refreshImpact()
}

// === 削除 ===
async function remove(item: BlockedTimeResponse) {
  if (item.id == null) return
  if (!confirm(t('reservation.unavailability.dialog.delete_confirm'))) return
  try {
    await reservationApi.deleteBlockedTime(props.teamId, item.id)
    notification.success(t('reservation.unavailability.message.delete_success'))
    await loadBlockedTimes()
    await refreshImpact()
  }
  catch (error) {
    handleApiError(error)
  }
}

// === 表示ヘルパー ===
function targetLabel(item: BlockedTimeResponse): string {
  if (item.resource?.resourceType === 'STAFF') {
    return item.resource.resourceName ?? resourceName.value
  }
  return t('reservation.unavailability.scope.team')
}

function timeRangeLabel(item: BlockedTimeResponse): string {
  const s = toHm(item.timeSlot?.startTime)
  const e = toHm(item.timeSlot?.endTime)
  if (!s && !e) return t('reservation.unavailability.list.all_day')
  const nextDay = item.endsNextDay ?? item.timeSlot?.endsNextDay
  return nextDay ? `${s} - ${t('reservation.template.next_day_time', { time: e })}` : `${s} - ${e}`
}

onMounted(async () => {
  loading.value = true
  await Promise.all([loadBlockedTimes(), loadLines(), loadBusinessHours(), loadResourceName()])
  applyTemplate(template.value)
  await refreshImpact()
})
</script>

<template>
  <div class="space-y-5">
    <!-- 使い方の一言 -->
    <Message severity="secondary" :closable="false" class="text-sm">
      {{ t('reservation.unavailability.help') }}
    </Message>

    <!-- === 登録フォーム === -->
    <div class="space-y-4 rounded-lg border border-surface-200 p-4 dark:border-surface-700">
      <!-- 対象スコープ -->
      <div>
        <label class="mb-1.5 block text-sm font-medium text-surface-700 dark:text-surface-300">
          {{ t('reservation.unavailability.scope.label') }}
        </label>
        <SelectButton
          v-model="scope"
          :options="scopeOptions"
          option-label="label"
          option-value="value"
          :allow-empty="false"
          :disabled="disabled || submitting"
        />
      </div>

      <!-- STAFF 時: 予約対象（ライン）選択 -->
      <div v-if="scope === 'STAFF'">
        <label class="mb-1.5 block text-sm font-medium text-surface-700 dark:text-surface-300">
          {{ t('reservation.unavailability.staff.label') }}
        </label>
        <Select
          v-model="selectedLineId"
          :options="lineOptions"
          option-label="label"
          option-value="value"
          option-disabled="disabled"
          :placeholder="t('reservation.unavailability.staff.placeholder')"
          class="w-full sm:w-80"
          :disabled="disabled || submitting"
        />
        <p class="mt-1 text-xs text-surface-500">
          {{ t('reservation.unavailability.staff.hint') }}
        </p>
      </div>

      <!-- 日付 -->
      <div>
        <label class="mb-1.5 block text-sm font-medium text-surface-700 dark:text-surface-300">
          {{ t('reservation.unavailability.field.date') }}
        </label>
        <DatePicker
          v-model="blockedDate"
          date-format="yy/mm/dd"
          :min-date="new Date()"
          class="w-full sm:w-56"
          :disabled="disabled || submitting"
        />
      </div>

      <!-- テンプレート -->
      <div>
        <label class="mb-1.5 block text-sm font-medium text-surface-700 dark:text-surface-300">
          {{ t('reservation.unavailability.template.label') }}
        </label>
        <SelectButton
          :model-value="template"
          :options="templateOptions"
          option-label="label"
          option-value="value"
          :allow-empty="false"
          :disabled="disabled || submitting"
          @update:model-value="onTemplateChange"
        />
        <p v-if="templateNeedsManual" class="mt-1 text-xs text-amber-600 dark:text-amber-400">
          {{ t('reservation.unavailability.template.no_business_hours') }}
        </p>
      </div>

      <!-- 時間帯（空=全日） -->
      <div>
        <label class="mb-1.5 block text-sm font-medium text-surface-700 dark:text-surface-300">
          {{ t('reservation.unavailability.field.time_range') }}
        </label>
        <div class="flex flex-wrap items-center gap-2">
          <Select
            v-model="startTime"
            :options="timeOptions"
            option-label="label"
            option-value="value"
            show-clear
            :placeholder="t('reservation.unavailability.field.start_time')"
            class="w-32"
            :disabled="disabled || submitting"
          />
          <span class="text-surface-400">-</span>
          <Select
            v-model="endTime"
            :options="timeOptions"
            option-label="label"
            option-value="value"
            show-clear
            :placeholder="t('reservation.unavailability.field.end_time')"
            class="w-32"
            :disabled="disabled || submitting"
          />
        </div>
        <label class="mt-2 flex items-center gap-2 text-sm">
          <Checkbox v-model="endsNextDay" binary :disabled="disabled || submitting" />
          <span>{{ t('reservation.template.ends_next_day') }}</span>
        </label>
        <p class="mt-1 text-xs text-surface-500">
          {{ t('reservation.unavailability.field.time_range_hint') }}
        </p>
      </div>

      <!-- 理由 -->
      <div>
        <label class="mb-1.5 block text-sm font-medium text-surface-700 dark:text-surface-300">
          {{ t('reservation.unavailability.field.reason') }}
        </label>
        <InputText
          v-model="reason"
          maxlength="200"
          :placeholder="t('reservation.unavailability.field.reason_placeholder')"
          class="w-full"
          :disabled="disabled || submitting"
        />
      </div>

      <!-- impact 警告 -->
      <Message
        v-if="hasConflict"
        severity="warn"
        :closable="false"
      >
        <div class="space-y-2">
          <p class="text-sm font-medium">
            {{ t('reservation.unavailability.impact.warning', { count: impact?.affectedCount ?? 0 }) }}
          </p>
          <ul class="space-y-1 text-xs">
            <li
              v-for="r in impact?.reservations ?? []"
              :key="r.reservationId"
              class="flex flex-wrap gap-x-2"
            >
              <span class="font-medium">{{ r.userName }}</span>
              <span class="text-surface-500">{{ toHm(r.startTime) }} - {{ toHm(r.endTime) }}</span>
              <span v-if="r.staffName" class="text-surface-500">/ {{ r.staffName }}</span>
              <span class="text-surface-500">
                ({{ t(`reservation.status.${r.status}`) }})
              </span>
            </li>
          </ul>
          <p class="text-xs text-surface-500">
            {{ t('reservation.unavailability.impact.hint') }}
          </p>
        </div>
      </Message>

      <!-- 登録ボタン -->
      <div class="flex items-center gap-2">
        <Button
          :label="t('reservation.unavailability.button.register')"
          icon="pi pi-ban"
          size="small"
          :loading="submitting"
          :disabled="submitDisabled"
          @click="submit"
        />
        <span v-if="impactLoading" class="text-xs text-surface-500">
          {{ t('reservation.unavailability.impact.checking') }}
        </span>
      </div>
    </div>

    <!-- === 登録済み一覧 === -->
    <div>
      <p class="mb-2 text-xs font-semibold uppercase tracking-wide text-surface-500">
        {{ t('reservation.unavailability.list.title') }}
      </p>

      <div v-if="loading" class="space-y-2">
        <Skeleton height="2.5rem" width="100%" />
        <Skeleton height="2.5rem" width="100%" />
      </div>

      <p
        v-else-if="blockedTimes.length === 0"
        class="rounded-lg border border-surface-200 p-4 text-center text-sm text-surface-500 dark:border-surface-700"
      >
        {{ t('reservation.unavailability.list.empty') }}
      </p>

      <ul v-else class="divide-y divide-surface-200 rounded-lg border border-surface-200 dark:divide-surface-700 dark:border-surface-700">
        <li
          v-for="item in blockedTimes"
          :key="item.id"
          class="flex flex-wrap items-center justify-between gap-2 p-3"
        >
          <div class="min-w-0 flex-1">
            <div class="flex flex-wrap items-center gap-2 text-sm">
              <Tag :value="targetLabel(item)" :severity="item.resource?.resourceType === 'STAFF' ? 'info' : 'secondary'" />
              <span class="font-medium text-surface-700 dark:text-surface-300">
                {{ item.timeSlot?.blockedDate }}
              </span>
              <span class="text-surface-500">{{ timeRangeLabel(item) }}</span>
            </div>
            <p v-if="item.audit?.reason" class="mt-0.5 truncate text-xs text-surface-500">
              {{ item.audit.reason }}
            </p>
          </div>
          <Button
            icon="pi pi-trash"
            severity="danger"
            text
            rounded
            size="small"
            :aria-label="t('reservation.unavailability.button.delete')"
            :disabled="disabled"
            @click="remove(item)"
          />
        </li>
      </ul>
    </div>
  </div>
</template>
