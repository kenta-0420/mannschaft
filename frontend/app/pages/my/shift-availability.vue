<script setup lang="ts">
import type {
  AvailabilityDefaultRequest,
  AvailabilityDefaultResponse,
  ShiftPreference,
} from '~/types/shift'
import { preferenceToColor, preferenceToI18nKey } from '~/utils/shiftPreference'

/**
 * F03.5 デフォルト可否プロファイル設定ページ
 *
 * 曜日ごとにデフォルトの希望強度と勤務可能な時間帯を設定する。
 * 希望提出フォームの初期値・自動割当の候補算出に使用される。
 */

definePageMeta({ middleware: 'auth' })

const { t, locale } = useI18n()
const { error: showError, success: showSuccess } = useNotification()
const { getAvailabilityDefaults, setAvailabilityDefaults, deleteAvailabilityDefaults } =
  useShiftAvailabilityDefaultApi()
const teamStore = useTeamStore()

/** 「終日」を表す時間帯（従来のハードコード値と同値） */
const ALL_DAY_START = '00:00'
const ALL_DAY_END = '23:59'

/** 曜日ごとの入力状態 */
interface DayAvailability {
  preference: ShiftPreference
  /** 終日（時間帯を指定しない）か */
  allDay: boolean
  startTime: string
  endTime: string
}

/**
 * 取得状態。`error` から `empty` へフォールバックしてはならない
 * （設計書 F03.5 06_manual_authoring §11.6）。
 */
type LoadState = 'loading' | 'error' | 'empty' | 'loaded'

const saving = ref(false)
const selectedTeamId = ref<number | null>(null)
const loadState = ref<LoadState>('loading')

// 曜日 0(日)〜6(土) — toLocaleDateString で動的生成。locale 変更に追従させる。
const DOW_LABELS = computed(() =>
  Array.from({ length: 7 }, (_, i) => {
    // 2024-01-07(日)を起点に i 日加算
    const d = new Date(2024, 0, 7 + i)
    return d.toLocaleDateString(locale.value, { weekday: 'short' })
  }),
)

function dowLabel(idx: number): string {
  return DOW_LABELS.value[idx] ?? ''
}

const preferenceOptions: ShiftPreference[] = [
  'PREFERRED',
  'AVAILABLE',
  'WEAK_REST',
  'STRONG_REST',
  'ABSOLUTE_REST',
]

const days = ref<DayAvailability[]>([])

/** API が返す `HH:mm:ss` / `HH:mm` を入力欄が扱う `HH:mm` へ正規化する。 */
function toHourMinute(value: string | null): string {
  if (!value) return ''
  return value.slice(0, 5)
}

function createDefaultDay(): DayAvailability {
  return {
    preference: 'AVAILABLE',
    allDay: true,
    startTime: ALL_DAY_START,
    endTime: ALL_DAY_END,
  }
}

function initDefaults(current: AvailabilityDefaultResponse[]) {
  const next: DayAvailability[] = Array.from({ length: 7 }, () => createDefaultDay())
  for (const d of current) {
    const row = next[d.dayOfWeek]
    if (!row) continue
    row.preference = d.preference
    const start = toHourMinute(d.startTime)
    const end = toHourMinute(d.endTime)
    // 時間帯が無い（NULL）／終日相当なら「終日」として復元する
    if (!start || !end || (start === ALL_DAY_START && end === ALL_DAY_END)) {
      row.allDay = true
      row.startTime = ALL_DAY_START
      row.endTime = ALL_DAY_END
    } else {
      row.allDay = false
      row.startTime = start
      row.endTime = end
    }
  }
  days.value = next
}

async function loadForTeam(id: number) {
  loadState.value = 'loading'
  try {
    const data = await getAvailabilityDefaults(String(id))
    initDefaults(data)
    loadState.value = data.length === 0 ? 'empty' : 'loaded'
  } catch {
    // 取得失敗は「未設定」ではない。空状態へフォールバックせずエラー状態を出す。
    days.value = []
    loadState.value = 'error'
    showError(t('shift.notification.errorLoad'))
  }
}

async function retryLoad() {
  if (!selectedTeamId.value) return
  await loadForTeam(selectedTeamId.value)
}

function setDowPreference(dow: number, pref: ShiftPreference) {
  const row = days.value[dow]
  if (!row) return
  row.preference = pref
}

/** 開始 < 終了 を満たさない曜日の index 集合 */
const invalidDays = computed(() => {
  const invalid = new Set<number>()
  days.value.forEach((day, idx) => {
    if (day.allDay) return
    if (!day.startTime || !day.endTime || day.startTime >= day.endTime) {
      invalid.add(idx)
    }
  })
  return invalid
})

const hasInvalidInput = computed(() => invalidDays.value.size > 0)

async function save() {
  if (!selectedTeamId.value) return
  if (hasInvalidInput.value) {
    showError(t('shift.availability.timeRangeInvalid'))
    return
  }
  saving.value = true
  try {
    const availabilities: AvailabilityDefaultRequest[] = days.value.map((day, dow) => ({
      dayOfWeek: dow,
      startTime: day.allDay ? ALL_DAY_START : day.startTime,
      endTime: day.allDay ? ALL_DAY_END : day.endTime,
      preference: day.preference,
    }))
    await setAvailabilityDefaults(String(selectedTeamId.value), { availabilities })
    loadState.value = 'loaded'
    showSuccess(t('shift.notification.updateSuccess'))
  } catch {
    showError(t('shift.notification.errorUpdate'))
  } finally {
    saving.value = false
  }
}

async function resetAll() {
  if (!selectedTeamId.value) return
  try {
    await deleteAvailabilityDefaults(String(selectedTeamId.value))
    initDefaults([])
    loadState.value = 'empty'
    showSuccess(t('shift.notification.deleteSuccess'))
  } catch {
    showError(t('shift.notification.errorDelete'))
  }
}

function cardClass(pref: ShiftPreference, selected: boolean): string {
  const base =
    'flex-1 min-h-[44px] min-w-[44px] flex items-center justify-center rounded-lg border-2 text-xs font-medium cursor-pointer transition-all text-center leading-tight px-1 py-1'
  const color = preferenceToColor(pref)
  const active = selected
    ? 'ring-2 ring-primary ring-offset-1 scale-105 shadow-sm'
    : 'opacity-60 hover:opacity-90'
  return [base, color, active].join(' ')
}

async function onTeamSelect(id: number) {
  selectedTeamId.value = id
  await loadForTeam(id)
}

onMounted(async () => {
  await teamStore.fetchMyTeams()
  // チームが1つなら自動選択
  if (teamStore.myTeams.length === 1) {
    await onTeamSelect(teamStore.myTeams[0]!.id)
  }
})
</script>

<template>
  <div class="mx-auto max-w-2xl">
    <PageHeader :title="t('shift.page.availability')" />

    <p class="mb-4 text-sm text-surface-500">
      {{ t('shift.availabilityDescription') }}
    </p>

    <!-- チームが複数の場合は選択 -->
    <template v-if="selectedTeamId === null">
      <PageLoading v-if="teamStore.loading" size="40px" />
      <div v-else class="flex flex-col gap-3">
        <DashboardEmptyState
          v-if="teamStore.myTeams.length === 0"
          icon="pi-users"
          :message="t('shift.empty.noConstraints')"
        />
        <SectionCard
          v-for="team in teamStore.myTeams"
          :key="team.id"
          class="cursor-pointer transition-shadow hover:shadow-md"
          @click="onTeamSelect(team.id)"
        >
          <div class="flex items-center gap-3">
            <div
              class="flex h-10 w-10 items-center justify-center rounded-full bg-surface-200 text-sm font-bold text-surface-600"
            >
              {{ team.name.charAt(0) }}
            </div>
            <p class="text-sm font-semibold text-surface-800">{{ team.name }}</p>
          </div>
        </SectionCard>
      </div>
    </template>

    <template v-else>
      <!-- チーム変更ボタン（複数チームの場合） -->
      <div v-if="teamStore.myTeams.length > 1" class="mb-4">
        <Button
          icon="pi pi-arrow-left"
          :label="t('button.back')"
          text
          severity="secondary"
          @click="selectedTeamId = null"
        />
      </div>

      <PageLoading v-if="loadState === 'loading'" size="40px" />

      <!-- 取得失敗: 空状態とは別コンポーネント・別 data-testid で描き分ける -->
      <SectionCard v-else-if="loadState === 'error'" data-testid="availability-error-state">
        <div class="flex flex-col items-center gap-3 py-6 text-center">
          <i class="pi pi-exclamation-triangle text-2xl text-red-500" />
          <p class="text-sm text-surface-700">{{ t('shift.availability.loadError') }}</p>
          <Button
            :label="t('shift.availability.retry')"
            icon="pi pi-refresh"
            severity="secondary"
            outlined
            data-testid="availability-error-retry"
            @click="retryLoad"
          />
        </div>
      </SectionCard>

      <template v-else>
        <!-- 未設定（取得は成功したがデータ無し） -->
        <div
          v-if="loadState === 'empty'"
          data-testid="availability-empty-state"
          class="mb-4 rounded-lg border border-surface-200 bg-surface-50 px-3 py-2 text-xs text-surface-600"
        >
          {{ t('shift.availability.notConfigured') }}
        </div>

        <div class="flex flex-col gap-4">
          <SectionCard v-for="(day, idx) in days" :key="idx">
            <!-- 曜日ラベル -->
            <div class="mb-3 flex items-center gap-2">
              <span
                class="flex h-8 w-8 items-center justify-center rounded-full text-sm font-bold"
                :class="
                  idx === 0 || idx === 6
                    ? 'bg-red-100 text-red-600'
                    : 'bg-surface-100 text-surface-700'
                "
              >
                {{ dowLabel(idx) }}
              </span>
              <span class="text-xs text-surface-500">
                {{ t(preferenceToI18nKey(day.preference)) }}
              </span>
            </div>

            <!-- 5段階ラジオ -->
            <div class="flex flex-wrap gap-1.5">
              <button
                v-for="pref in preferenceOptions"
                :key="pref"
                type="button"
                :class="cardClass(pref, day.preference === pref)"
                @click="setDowPreference(idx, pref)"
              >
                {{ t(preferenceToI18nKey(pref)) }}
              </button>
            </div>

            <!-- 時間帯 -->
            <div class="mt-3 border-t border-surface-100 pt-3">
              <div class="flex items-center gap-2">
                <Checkbox
                  v-model="day.allDay"
                  :binary="true"
                  :input-id="`availability-all-day-${idx}`"
                  :data-testid="`availability-all-day-${idx}`"
                />
                <label :for="`availability-all-day-${idx}`" class="text-xs text-surface-700">
                  {{ t('shift.availability.allDay') }}
                </label>
              </div>

              <div v-if="!day.allDay" class="mt-2 flex flex-wrap items-end gap-3">
                <div>
                  <label
                    :for="`availability-start-${idx}`"
                    class="mb-1 block text-xs text-surface-500"
                  >
                    {{ t('shift.availability.startTime') }}
                  </label>
                  <InputText
                    :id="`availability-start-${idx}`"
                    v-model="day.startTime"
                    type="time"
                    :data-testid="`availability-start-${idx}`"
                    class="w-32"
                  />
                </div>
                <div>
                  <label
                    :for="`availability-end-${idx}`"
                    class="mb-1 block text-xs text-surface-500"
                  >
                    {{ t('shift.availability.endTime') }}
                  </label>
                  <InputText
                    :id="`availability-end-${idx}`"
                    v-model="day.endTime"
                    type="time"
                    :data-testid="`availability-end-${idx}`"
                    class="w-32"
                  />
                </div>
              </div>

              <p
                v-if="invalidDays.has(idx)"
                class="mt-2 text-xs text-red-600"
                :data-testid="`availability-time-error-${idx}`"
              >
                {{ t('shift.availability.timeRangeInvalid') }}
              </p>
            </div>
          </SectionCard>
        </div>

        <!-- 凡例 -->
        <SectionCard class="mt-4">
          <h4 class="mb-2 text-xs font-semibold text-surface-500">{{ t('shift.legend') }}</h4>
          <div class="flex flex-wrap gap-2">
            <span
              v-for="pref in preferenceOptions"
              :key="pref"
              class="rounded-full px-2 py-0.5 text-xs"
              :class="preferenceToColor(pref)"
            >
              {{ t(preferenceToI18nKey(pref)) }}
            </span>
          </div>
        </SectionCard>

        <!-- アクションボタン -->
        <div class="mt-6 flex flex-wrap items-center justify-between gap-3">
          <Button
            :label="t('shift.action.reset')"
            icon="pi pi-refresh"
            text
            severity="secondary"
            @click="resetAll"
          />
          <Button
            :label="t('button.save')"
            icon="pi pi-check"
            :loading="saving"
            :disabled="hasInvalidInput"
            data-testid="availability-save"
            @click="save"
          />
        </div>
      </template>
    </template>
  </div>
</template>
