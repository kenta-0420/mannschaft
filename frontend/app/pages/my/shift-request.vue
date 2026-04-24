<script setup lang="ts">
import type {
  AvailabilityDefaultResponse,
  CreateShiftRequestRequest,
  ShiftPreference,
  ShiftRequestResponse,
  ShiftScheduleResponse,
  ShiftSlotResponse,
} from '~/types/shift'
import { preferenceToI18nKey } from '~/utils/shiftPreference'

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

const { t, locale } = useI18n()
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

// スロットIDをキーに希望を管理
const preferences = ref<Map<number, ShiftPreference>>(new Map())
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
    const all = await listSchedules(id)
    // COLLECTING 状態のみ表示
    schedules.value = all.filter((s) => s.status === 'COLLECTING')
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
        ? getAvailabilityDefaults(selectedTeamId.value)
        : Promise.resolve([] as AvailabilityDefaultResponse[]),
    ])
    slots.value = fetchedSlots
    existingRequests.value = myReqs.filter((r) => r.scheduleId === schedule.id)

    // 初期値: 既存希望 > デフォルトプロファイル > AVAILABLE
    const newPrefs = new Map<number, ShiftPreference>()
    const newNotes = new Map<number, string>()
    for (const slot of fetchedSlots) {
      const existing = existingRequests.value.find((r) => r.slotId === slot.id)
      if (existing) {
        newPrefs.set(slot.id, existing.preference)
        if (existing.note) newNotes.set(slot.id, existing.note)
        continue
      }
      // デフォルトプロファイルから曜日を取得（0=日曜）
      const slotDate = new Date(slot.slotDate)
      const dow = slotDate.getDay()
      const defaultPref = defaultProfiles.find((d) => d.dayOfWeek === dow)
      newPrefs.set(slot.id, defaultPref?.preference ?? 'AVAILABLE')
    }
    preferences.value = newPrefs
    notes.value = newNotes
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

function setPreference(slotId: number, pref: ShiftPreference) {
  const next = new Map(preferences.value)
  next.set(slotId, pref)
  preferences.value = next
}

function setNote(slotId: number, note: string) {
  const next = new Map(notes.value)
  next.set(slotId, note)
  notes.value = next
}

function applyBulk(payload: {
  preference: ShiftPreference
  target: 'all' | 'weekday' | 'weekend'
}) {
  const next = new Map(preferences.value)
  for (const slot of slots.value) {
    if (payload.target === 'all') {
      next.set(slot.id, payload.preference)
      continue
    }
    const dow = new Date(slot.slotDate).getDay()
    const isWeekend = dow === 0 || dow === 6
    if (payload.target === 'weekday' && !isWeekend) {
      next.set(slot.id, payload.preference)
    }
    if (payload.target === 'weekend' && isWeekend) {
      next.set(slot.id, payload.preference)
    }
  }
  preferences.value = next
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
      const pref = preferences.value.get(slot.id) ?? 'AVAILABLE'
      const note = notes.value.get(slot.id)
      const existing = existingRequests.value.find((r) => r.slotId === slot.id)
      if (existing) {
        tasks.push(updateRequest(existing.id, { preference: pref, note }))
      } else {
        const payload: CreateShiftRequestRequest = {
          scheduleId: selectedSchedule.value.id,
          slotId: slot.id,
          slotDate: slot.slotDate,
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
    if (!map.has(slot.slotDate)) map.set(slot.slotDate, [])
    map.get(slot.slotDate)!.push(slot)
  }
  return map
})

const sortedDates = computed(() => [...slotsByDate.value.keys()].sort())

function formatDate(dateStr: string): string {
  const d = new Date(dateStr)
  return d.toLocaleDateString(locale.value, { month: 'numeric', day: 'numeric', weekday: 'short' })
}

function formatTime(timeStr: string): string {
  return timeStr.substring(0, 5)
}

onMounted(async () => {
  await teamStore.fetchMyTeams()
  // チームが1つの場合は自動選択
  if (teamStore.myTeams.length === 1) {
    await selectTeam(teamStore.myTeams[0].id)
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
            />
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
          :label="t('common.button.back')"
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
                  {{ schedule.title }}
                </h3>
                <p class="mt-1 text-xs text-surface-500">
                  {{ schedule.startDate }} 〜 {{ schedule.endDate }}
                </p>
                <p v-if="schedule.requestDeadline" class="mt-0.5 text-xs text-surface-400">
                  {{ t('shift.field.deadline') }}: {{ schedule.requestDeadline }}
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
          :label="t('common.button.back')"
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

      <div
        v-if="selectedSchedule"
        class="mb-4 rounded-xl border border-surface-200 bg-surface-0 p-3"
      >
        <h3 class="text-sm font-semibold text-surface-800">{{ selectedSchedule.title }}</h3>
        <p class="mt-0.5 text-xs text-surface-500">
          {{ selectedSchedule.startDate }} 〜 {{ selectedSchedule.endDate }}
        </p>
      </div>

      <PageLoading v-if="slotsLoading" size="40px" />

      <template v-else>
        <div class="flex flex-col gap-4">
          <div
            v-for="date in sortedDates"
            :key="date"
            class="rounded-xl border border-surface-200 bg-surface-0 p-4"
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
                    {{ formatTime(slot.startTime) }}–{{ formatTime(slot.endTime) }}
                  </span>
                  <span
                    v-if="slot.positionName"
                    class="rounded bg-surface-200 px-1.5 py-0.5 text-xs text-surface-600"
                  >
                    {{ slot.positionName }}
                  </span>
                </div>

                <!-- 5段階ラジオカード -->
                <ShiftPreferenceRadioCard
                  :model-value="preferences.get(slot.id) ?? 'AVAILABLE'"
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
          </div>
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
          :label="t('common.button.back')"
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
          :label="t('common.button.cancel')"
          text
          severity="secondary"
          @click="step = 'slot-fill'"
        />
        <Button
          :label="t('shift.action.submit')"
          :loading="submitting"
          @click="submitAll"
        />
      </div>
    </template>

    <!-- 一括設定ダイアログ -->
    <ShiftPreferenceBulkSetDialog v-model:visible="bulkDialogVisible" @apply="applyBulk" />
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
