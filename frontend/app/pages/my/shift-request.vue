<script setup lang="ts">
import dayjs from 'dayjs'
import type {
  AvailabilityDefaultResponse,
  CreateShiftRequestRequest,
  ShiftPreference,
  ShiftRequestResponse,
  ShiftScheduleResponse,
  ShiftSlotResponse,
} from '~/types/shift'
import { preferenceToI18nKey } from '~/utils/shiftPreference'
import { isAcceptingShiftRequests } from '~/utils/shiftStatus'

/**
 * F03.5 シフト希望提出フォームページ
 *
 * ADHD 配慮:
 * - 5色ラジオカードで視覚的に判別容易
 * - 一括設定ボタンで全スロットをまとめて設定
 * - note は折りたたみ（デフォルト非表示）
 * - 最小タップサイズ 44x44px 確保
 * - 送信前プレビューで件数サマリー表示
 */

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const { userTimezone } = useDatetime()
const { error: showError, success: showSuccess } = useNotification()
const { listSchedules } = useShiftApi()
const { listSlots } = useShiftSlotApi()
const { listMyRequests, submitRequest, updateRequest } = useShiftRequestApi()
const { getAvailabilityDefaults } = useShiftAvailabilityDefaultApi()
const teamStore = useTeamStore()

// ステップ: team-select → schedule-select → slot-fill → preview
type Step = 'team-select' | 'schedule-select' | 'slot-fill' | 'preview'
const step = ref<Step>('team-select')

// チーム選択
const selectedTeamId = ref<number | null>(null)

// スケジュール選択
const schedules = ref<ShiftScheduleResponse[]>([])
const selectedSchedule = ref<ShiftScheduleResponse | null>(null)
const schedulesLoading = ref(false)

// スロット + 希望マトリクス
const slots = ref<ShiftSlotResponse[]>([])
const slotsLoading = ref(false)

// 既存の自分の希望（更新用）
const existingRequests = ref<ShiftRequestResponse[]>([])

/**
 * 未入力スロットの既定値（CMP-260908-2118）。
 * 「触っていない日は休み希望」。出られる日は利用者が明示的に選ぶ運用。
 */
const UNTOUCHED_PREFERENCE: ShiftPreference = 'STRONG_REST'

// スロットIDをキーに希望を管理
const preferences = ref<Map<number, ShiftPreference>>(new Map())

/**
 * 利用者がこの画面で操作した（または既に提出済みの）スロット ID。
 * 値が既定値と一致するかでは「未入力」を判定できないため、別途追跡する。
 */
const touchedSlotIds = ref<Set<number>>(new Set())
/** 曜日ごとの既定プロファイル由来で初期値が入ったスロット ID（第3区分） */
const defaultAppliedSlotIds = ref<Set<number>>(new Set())
const notes = ref<Map<number, string>>(new Map())
const expandedNotes = ref<Set<number>>(new Set())

// 一括設定ダイアログ
const bulkDialogVisible = ref(false)

// 送信中
const submitting = ref(false)

async function selectTeam(id: number) {
  selectedTeamId.value = id
  step.value = 'schedule-select'
  schedulesLoading.value = true
  try {
    const all = await listSchedules(String(id))
    // 希望を受け付けているシフト表のみ表示（COLLECTING かつ requestDeadline 未経過）。
    // 判定はチームのシフト表一覧と共通の isAcceptingShiftRequests に寄せる（CMP-260908-2118）。
    schedules.value = all.filter(isAcceptingShiftRequests)
  } catch {
    showError(t('shift.notification.errorLoad'))
    step.value = 'team-select'
  } finally {
    schedulesLoading.value = false
  }
}

async function selectSchedule(schedule: ShiftScheduleResponse) {
  selectedSchedule.value = schedule
  slotsLoading.value = true
  step.value = 'slot-fill'
  try {
    const [fetchedSlots, myReqs, defaultProfiles] = await Promise.all([
      listSlots(schedule.id),
      listMyRequests(),
      selectedTeamId.value
        ? getAvailabilityDefaults(String(selectedTeamId.value))
        : Promise.resolve([] as AvailabilityDefaultResponse[]),
    ])
    slots.value = fetchedSlots
    existingRequests.value = myReqs.filter((r) => r.scheduleId === schedule.id)

    // 初期値: 既存希望 > デフォルトプロファイル > STRONG_REST（CMP-260908-2118）
    //
    // 未入力の既定は「できれば休みたい（STRONG_REST）」。触っていない日に
    // 意図せずシフトへ入れられる事故を防ぐため、出られる日は利用者が明示的に選ぶ。
    // ABSOLUTE_REST ではないのは、人手が足りないときに依頼が来る余地を残すため。
    const newPrefs = new Map<number, ShiftPreference>()
    const newNotes = new Map<number, string>()
    const newTouched = new Set<number>()
    const newDefaultApplied = new Set<number>()
    for (const slot of fetchedSlots) {
      const existing = existingRequests.value.find((r) => r.slotId === slot.id)
      if (existing) {
        newPrefs.set(slot.id, existing.preference)
        if (existing.note) newNotes.set(slot.id, existing.note)
        // 既に提出済みの希望は「入力済み」として扱う
        newTouched.add(slot.id)
        continue
      }
      // デフォルトプロファイルから曜日を取得（0=日曜）
      const slotDate = new Date(slot.time.slotDate)
      const dow = slotDate.getDay()
      const defaultPref = defaultProfiles.find((d) => d.dayOfWeek === dow)
      if (defaultPref) {
        newPrefs.set(slot.id, defaultPref.preference)
        newDefaultApplied.add(slot.id)
      } else {
        newPrefs.set(slot.id, UNTOUCHED_PREFERENCE)
      }
    }
    preferences.value = newPrefs
    notes.value = newNotes
    touchedSlotIds.value = newTouched
    defaultAppliedSlotIds.value = newDefaultApplied
  } catch {
    showError(t('shift.notification.errorLoad'))
    step.value = 'schedule-select'
  } finally {
    slotsLoading.value = false
  }
}

function toggleNote(slotId: number) {
  const next = new Set(expandedNotes.value)
  if (next.has(slotId)) {
    next.delete(slotId)
  } else {
    next.add(slotId)
  }
  expandedNotes.value = next
}

function markTouched(slotIds: number[]) {
  const next = new Set(touchedSlotIds.value)
  for (const id of slotIds) next.add(id)
  touchedSlotIds.value = next
}

function setPreference(slotId: number, pref: ShiftPreference) {
  const next = new Map(preferences.value)
  next.set(slotId, pref)
  preferences.value = next
  markTouched([slotId])
}

function setNote(slotId: number, note: string) {
  const next = new Map(notes.value)
  next.set(slotId, note)
  notes.value = next
  markTouched([slotId])
}

function applyBulk(payload: {
  preference: ShiftPreference
  target: 'all' | 'weekday' | 'weekend'
}) {
  const next = new Map(preferences.value)
  const touched: number[] = []
  for (const slot of slots.value) {
    if (payload.target === 'all') {
      next.set(slot.id, payload.preference)
      touched.push(slot.id)
      continue
    }
    const dow = new Date(slot.time.slotDate).getDay()
    const isWeekend = dow === 0 || dow === 6
    if (payload.target === 'weekday' && !isWeekend) {
      next.set(slot.id, payload.preference)
      touched.push(slot.id)
    }
    if (payload.target === 'weekend' && isWeekend) {
      next.set(slot.id, payload.preference)
      touched.push(slot.id)
    }
  }
  preferences.value = next
  markTouched(touched)
}

// --- 送信前確認モーダル（CMP-260908-2118）---------------------------------
const confirmDialogVisible = ref(false)

/** 日付ごとに「入力済み / 既定から自動入力 / 未入力」の 3 区分へ分類する */
const submitSummary = computed(() => {
  const filled: string[] = []
  const defaultApplied: string[] = []
  const untouched: string[] = []
  const byDate = new Map<string, ShiftSlotResponse[]>()
  for (const slot of slots.value) {
    if (!byDate.has(slot.time.slotDate)) byDate.set(slot.time.slotDate, [])
    byDate.get(slot.time.slotDate)!.push(slot)
  }
  for (const date of [...byDate.keys()].sort()) {
    const dateSlots = byDate.get(date) ?? []
    if (dateSlots.some((s) => touchedSlotIds.value.has(s.id))) {
      filled.push(date)
    } else if (dateSlots.some((s) => defaultAppliedSlotIds.value.has(s.id))) {
      defaultApplied.push(date)
    } else {
      untouched.push(date)
    }
  }
  return { filled, defaultApplied, untouched }
})

function openSubmitConfirm() {
  confirmDialogVisible.value = true
}

async function confirmAndSubmit() {
  confirmDialogVisible.value = false
  await submitAll()
}

// プレビュー用カウント
const previewCounts = computed((): Record<ShiftPreference, number> => {
  const counts: Record<ShiftPreference, number> = {
    PREFERRED: 0,
    AVAILABLE: 0,
    WEAK_REST: 0,
    STRONG_REST: 0,
    ABSOLUTE_REST: 0,
  }
  for (const pref of preferences.value.values()) {
    counts[pref]++
  }
  return counts
})

async function submitAll() {
  if (!selectedSchedule.value) return
  submitting.value = true
  try {
    const tasks: Promise<ShiftRequestResponse>[] = []
    for (const slot of slots.value) {
      const pref = preferences.value.get(slot.id) ?? UNTOUCHED_PREFERENCE
      const note = notes.value.get(slot.id)
      const existing = existingRequests.value.find((r) => r.slotId === slot.id)
      if (existing) {
        tasks.push(updateRequest(existing.id, { preference: pref, note }))
      } else {
        const payload: CreateShiftRequestRequest = {
          scheduleId: selectedSchedule.value.id,
          slotId: slot.id,
          slotDate: slot.time.slotDate,
          preference: pref,
          note,
        }
        tasks.push(submitRequest(payload))
      }
    }
    await Promise.all(tasks)
    showSuccess(t('shift.notification.submitSuccess'))
    // リセット
    step.value = 'team-select'
    selectedSchedule.value = null
    selectedTeamId.value = null
  } catch {
    showError(t('shift.notification.errorSubmit'))
  } finally {
    submitting.value = false
  }
}

// スロットを日付でグループ化
const slotsByDate = computed(() => {
  const map = new Map<string, ShiftSlotResponse[]>()
  for (const slot of slots.value) {
    if (!map.has(slot.time.slotDate)) map.set(slot.time.slotDate, [])
    map.get(slot.time.slotDate)!.push(slot)
  }
  return map
})

const sortedDates = computed(() => [...slotsByDate.value.keys()].sort())

function formatDate(dateStr: string): string {
  return dayjs.tz(dateStr, userTimezone.value).format('M/D (ddd)')
}

function formatTime(timeStr: string): string {
  return timeStr.substring(0, 5)
}

onMounted(async () => {
  await teamStore.fetchMyTeams()
  // チームが1つの場合は自動選択
  if (teamStore.myTeams.length === 1) {
    await selectTeam(teamStore.myTeams[0]!.id)
  }
})
</script>

<template>
  <div class="mx-auto max-w-3xl">
    <PageHeader :title="t('shift.page.submitRequest')" />

    <!-- ステップ 1: チーム選択 -->
    <template v-if="step === 'team-select'">
      <PageLoading v-if="teamStore.loading" size="40px" />
      <div v-else class="flex flex-col gap-3">
        <DashboardEmptyState
          v-if="teamStore.myTeams.length === 0"
          icon="pi-users"
          :message="t('shift.empty.noSchedules')"
        />
        <SectionCard
          v-for="team in teamStore.myTeams"
          :key="team.id"
          class="cursor-pointer transition-shadow hover:shadow-md"
          @click="selectTeam(team.id)"
        >
          <div class="flex items-center gap-3">
            <img
              v-if="team.iconUrl"
              :src="team.iconUrl"
              class="h-10 w-10 rounded-full object-cover"
              alt=""
            >
            <div
              v-else
              class="flex h-10 w-10 items-center justify-center rounded-full bg-surface-200 text-sm font-bold text-surface-600"
            >
              {{ team.name.charAt(0) }}
            </div>
            <div>
              <p class="text-sm font-semibold text-surface-800">{{ team.name }}</p>
              <p v-if="team.nickname1" class="text-xs text-surface-500">{{ team.nickname1 }}</p>
            </div>
          </div>
        </SectionCard>
      </div>
    </template>

    <!-- ステップ 2: スケジュール選択 -->
    <template v-else-if="step === 'schedule-select'">
      <div class="mb-4">
        <Button
          icon="pi pi-arrow-left"
          :label="t('button.back')"
          text
          severity="secondary"
          @click="step = 'team-select'"
        />
      </div>

      <PageLoading v-if="schedulesLoading" size="40px" />
      <template v-else>
        <DashboardEmptyState
          v-if="schedules.length === 0"
          icon="pi-calendar"
          :message="t('shift.empty.noSchedules')"
        />
        <div v-else class="flex flex-col gap-3">
          <SectionCard
            v-for="schedule in schedules"
            :key="schedule.id"
            class="cursor-pointer transition-shadow hover:shadow-md"
            @click="selectSchedule(schedule)"
          >
            <div class="flex items-start justify-between gap-2">
              <div class="min-w-0 flex-1">
                <h3 class="truncate text-sm font-semibold text-surface-800">
                  {{ schedule.content.title }}
                </h3>
                <p class="mt-1 text-xs text-surface-500">
                  {{ schedule.period.startDate }} 〜 {{ schedule.period.endDate }}
                </p>
                <p v-if="schedule.period.requestDeadline" class="mt-0.5 text-xs text-surface-400">
                  {{ t('shift.field.deadline') }}: {{ schedule.period.requestDeadline }}
                </p>
              </div>
              <span
                class="shrink-0 rounded-full bg-blue-100 px-2 py-0.5 text-xs font-medium text-blue-700"
              >
                {{ t('shift.status.collecting') }}
              </span>
            </div>
          </SectionCard>
        </div>
      </template>
    </template>

    <!-- ステップ 3: スロットごとに希望入力 -->
    <template v-else-if="step === 'slot-fill'">
      <!-- ヘッダー -->
      <div class="mb-4 flex items-center justify-between">
        <Button
          icon="pi pi-arrow-left"
          :label="t('button.back')"
          text
          severity="secondary"
          @click="step = 'schedule-select'"
        />
        <Button
          :label="t('shift.bulkSet.title')"
          icon="pi pi-sliders-h"
          outlined
          size="small"
          @click="bulkDialogVisible = true"
        />
      </div>

      <SectionCard
        v-if="selectedSchedule"
        class="mb-4"
      >
        <h3 class="text-sm font-semibold text-surface-800">{{ selectedSchedule.content.title }}</h3>
        <p class="mt-0.5 text-xs text-surface-500">
          {{ selectedSchedule.period.startDate }} 〜 {{ selectedSchedule.period.endDate }}
        </p>
      </SectionCard>

      <PageLoading v-if="slotsLoading" size="40px" />

      <template v-else>
        <div class="flex flex-col gap-4">
          <SectionCard
            v-for="date in sortedDates"
            :key="date"
          >
            <h4 class="mb-3 text-sm font-semibold text-surface-700">{{ formatDate(date) }}</h4>
            <div class="flex flex-col gap-4">
              <div
                v-for="slot in slotsByDate.get(date)"
                :key="slot.id"
                class="rounded-lg border border-surface-100 bg-surface-50 p-3"
              >
                <!-- 時刻・ポジション -->
                <div class="mb-2 flex flex-wrap items-center gap-2">
                  <span class="text-xs font-medium text-surface-600">
                    {{ formatTime(slot.time.startTime) }}–{{ formatTime(slot.time.endTime) }}
                  </span>
                  <span
                    v-if="slot.position.positionName"
                    class="rounded bg-surface-200 px-1.5 py-0.5 text-xs text-surface-600"
                  >
                    {{ slot.position.positionName }}
                  </span>
                </div>

                <!-- 5段階ラジオカード -->
                <ShiftPreferenceRadioCard
                  :model-value="preferences.get(slot.id) ?? UNTOUCHED_PREFERENCE"
                  @update:model-value="setPreference(slot.id, $event)"
                />

                <!-- note 折りたたみ -->
                <div class="mt-2">
                  <button
                    type="button"
                    class="flex min-h-[44px] items-center gap-1 text-xs text-surface-400 hover:text-surface-600"
                    @click="toggleNote(slot.id)"
                  >
                    <i
                      :class="
                        expandedNotes.has(slot.id) ? 'pi pi-chevron-up' : 'pi pi-chevron-down'
                      "
                    />
                    {{ t('shift.field.preferenceNote') }}
                  </button>
                  <Transition name="slide-down">
                    <div v-if="expandedNotes.has(slot.id)" class="mt-2">
                      <InputText
                        :value="notes.get(slot.id) ?? ''"
                        :placeholder="t('shift.field.preferenceNote')"
                        class="w-full text-sm"
                        @input="setNote(slot.id, ($event.target as HTMLInputElement).value)"
                      />
                    </div>
                  </Transition>
                </div>
              </div>
            </div>
          </SectionCard>
        </div>

        <!-- 次へボタン -->
        <div class="mt-6 flex justify-end">
          <Button
            :label="t('shift.preview.title')"
            icon="pi pi-arrow-right"
            icon-pos="right"
            :disabled="slots.length === 0"
            @click="step = 'preview'"
          />
        </div>
      </template>
    </template>

    <!-- ステップ 4: プレビュー -->
    <template v-else-if="step === 'preview'">
      <div class="mb-4 flex items-center">
        <Button
          icon="pi pi-arrow-left"
          :label="t('button.back')"
          text
          severity="secondary"
          @click="step = 'slot-fill'"
        />
      </div>

      <SectionCard>
        <h3 class="mb-3 text-sm font-semibold text-surface-700">{{ t('shift.preview.title') }}</h3>
        <div class="flex flex-col gap-2">
          <div
            v-for="pref in (['PREFERRED', 'AVAILABLE', 'WEAK_REST', 'STRONG_REST', 'ABSOLUTE_REST'] as ShiftPreference[])"
            :key="pref"
            class="flex items-center justify-between text-sm"
          >
            <span>{{ t(preferenceToI18nKey(pref)) }}</span>
            <span class="font-semibold text-surface-700">{{ previewCounts[pref] }}</span>
          </div>
          <div
            class="mt-2 flex items-center justify-between border-t border-surface-200 pt-2 text-sm font-semibold"
          >
            <span>{{ t('shift.preview.totalLabel') }}</span>
            <span>{{ slots.length }}</span>
          </div>
        </div>
      </SectionCard>

      <div class="mt-6 flex justify-end gap-2">
        <Button
          :label="t('button.cancel')"
          text
          severity="secondary"
          @click="step = 'slot-fill'"
        />
        <Button
          :label="t('shift.action.submit')"
          :loading="submitting"
          @click="openSubmitConfirm"
        />
      </div>
    </template>

    <!-- 一括設定ダイアログ -->
    <ShiftPreferenceBulkSetDialog v-model:visible="bulkDialogVisible" @apply="applyBulk" />

    <!-- 送信前確認モーダル（CMP-260908-2118） -->
    <Dialog
      v-model:visible="confirmDialogVisible"
      :header="t('shift.submitConfirm.title')"
      :style="{ width: '440px' }"
      modal
    >
      <div class="flex flex-col gap-3 text-sm">
        <div class="flex items-center justify-between">
          <span>{{ t('shift.submitConfirm.filledLabel') }}</span>
          <span class="font-semibold">
            {{ t('shift.submitConfirm.dayCount', { count: submitSummary.filled.length }) }}
          </span>
        </div>
        <div
          v-if="submitSummary.defaultApplied.length > 0"
          class="flex items-center justify-between"
        >
          <span>{{ t('shift.submitConfirm.defaultAppliedLabel') }}</span>
          <span class="font-semibold">
            {{ t('shift.submitConfirm.dayCount', { count: submitSummary.defaultApplied.length }) }}
          </span>
        </div>
        <div class="flex items-center justify-between">
          <span>{{ t('shift.submitConfirm.untouchedLabel') }}</span>
          <span class="font-semibold">
            {{ t('shift.submitConfirm.dayCount', { count: submitSummary.untouched.length }) }}
          </span>
        </div>

        <div
          v-if="submitSummary.untouched.length > 0"
          class="rounded-lg bg-surface-100 p-3 text-xs text-surface-600 dark:bg-surface-800 dark:text-surface-300"
        >
          <p>{{ t('shift.submitConfirm.untouchedNotice') }}</p>
          <p class="mt-1">{{ submitSummary.untouched.map(formatDate).join('、') }}</p>
        </div>
      </div>
      <template #footer>
        <Button
          :label="t('shift.submitConfirm.back')"
          text
          severity="secondary"
          @click="confirmDialogVisible = false"
        />
        <Button
          :label="t('shift.submitConfirm.submit')"
          :loading="submitting"
          @click="confirmAndSubmit"
        />
      </template>
    </Dialog>
  </div>
</template>

<style scoped>
.slide-down-enter-active,
.slide-down-leave-active {
  transition:
    max-height 0.2s ease,
    opacity 0.2s ease;
  overflow: hidden;
  max-height: 80px;
}
.slide-down-enter-from,
.slide-down-leave-to {
  max-height: 0;
  opacity: 0;
}
</style>
