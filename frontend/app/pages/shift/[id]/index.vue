<script setup lang="ts">
import type {
  ShiftScheduleResponse,
  ShiftSlotResponse,
  ShiftScheduleStatus,
  UpdateShiftScheduleRequest,
} from '~/types/shift'
import { statusToStep } from '~/utils/shiftStatus'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const teamStore = useTeamStore()
const { getSchedule, updateSchedule, transitionStatus } = useShiftApi()
const { listSlots } = useShiftSlotApi()
const { handleApiError } = useErrorHandler()
const { success } = useNotification()

const scheduleId = computed(() => Number(route.params.id))

// =====================================================
// データ取得
// =====================================================
const schedule = ref<ShiftScheduleResponse | null>(null)
const slots = ref<ShiftSlotResponse[]>([])
const loading = ref(false)

const canManage = computed(() => {
  if (!schedule.value) return false
  return teamStore.myTeams.some(
    (t) =>
      t.id === schedule.value!.teamId &&
      (t.role === 'ADMIN' || t.role === 'SYSTEM_ADMIN' || t.role === 'DEPUTY_ADMIN'),
  )
})

async function load() {
  loading.value = true
  try {
    const [s, sl] = await Promise.all([
      getSchedule(scheduleId.value),
      listSlots(scheduleId.value),
    ])
    schedule.value = s
    slots.value = sl
  } catch (error) {
    handleApiError(error)
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await teamStore.fetchMyTeams()
  await load()
})

// =====================================================
// ステータス遷移
// =====================================================
const transitioning = ref(false)

const nextStatusMap: Record<ShiftScheduleStatus, ShiftScheduleStatus | null> = {
  DRAFT: 'COLLECTING',
  COLLECTING: 'ADJUSTING',
  ADJUSTING: 'PUBLISHED',
  PUBLISHED: 'ARCHIVED',
  ARCHIVED: null,
}

const nextStatusLabelMap: Record<ShiftScheduleStatus, string> = {
  DRAFT: 'shift.detail.startCollecting',
  COLLECTING: 'shift.detail.startAdjusting',
  ADJUSTING: 'shift.detail.publish',
  PUBLISHED: 'shift.detail.archive',
  ARCHIVED: '',
}

const nextStatus = computed(() =>
  schedule.value ? nextStatusMap[schedule.value.status] : null,
)

async function handleTransition() {
  if (!schedule.value || !nextStatus.value) return
  transitioning.value = true
  try {
    schedule.value = await transitionStatus(scheduleId.value, nextStatus.value)
    success(t('shift.detail.transitionSuccess'))
  } catch (error) {
    handleApiError(error)
  } finally {
    transitioning.value = false
  }
}

// =====================================================
// カレンダービュー
// =====================================================
/**
 * スロットをキー「日付_開始_終了_ポジションID」でグループ化し
 * 日付 × 時間帯のマトリクス表示に使用する
 */
const dateList = computed<string[]>(() => {
  if (!schedule.value) return []
  const dates: string[] = []
  const start = new Date(schedule.value.startDate)
  const end = new Date(schedule.value.endDate)
  const cur = new Date(start)
  while (cur <= end && dates.length <= 60) {
    dates.push(cur.toISOString().split('T')[0]!)
    cur.setDate(cur.getDate() + 1)
  }
  return dates
})

const slotsByDate = computed<Map<string, ShiftSlotResponse[]>>(() => {
  const map = new Map<string, ShiftSlotResponse[]>()
  for (const slot of slots.value) {
    const arr = map.get(slot.slotDate) ?? []
    arr.push(slot)
    map.set(slot.slotDate, arr)
  }
  return map
})

function isUnderStaffed(slot: ShiftSlotResponse): boolean {
  return slot.assignedUserIds.length < slot.requiredCount
}

function formatDateShort(dateStr: string): string {
  const d = new Date(dateStr)
  return `${d.getMonth() + 1}/${d.getDate()}`
}

function formatDayOfWeek(dateStr: string): string {
  const days = ['日', '月', '火', '水', '木', '金', '土']
  const d = new Date(dateStr)
  return days[d.getDay()] ?? ''
}

function isWeekend(dateStr: string): boolean {
  const d = new Date(dateStr)
  return d.getDay() === 0 || d.getDay() === 6
}

// =====================================================
// 編集（タイトル等）
// =====================================================
const showEditDialog = ref(false)
const editForm = ref({ title: '', note: '' })
const saving = ref(false)

function openEdit() {
  if (!schedule.value) return
  editForm.value = {
    title: schedule.value.title,
    note: schedule.value.note ?? '',
  }
  showEditDialog.value = true
}

async function saveEdit() {
  if (!schedule.value) return
  saving.value = true
  try {
    const payload: UpdateShiftScheduleRequest = {
      title: editForm.value.title.trim(),
      note: editForm.value.note.trim() || undefined,
    }
    schedule.value = await updateSchedule(scheduleId.value, payload)
    success(t('shift.detail.saveSuccess'))
    showEditDialog.value = false
  } catch (error) {
    handleApiError(error)
  } finally {
    saving.value = false
  }
}

// ステッパーステップ番号
const currentStep = computed(() => (schedule.value ? statusToStep(schedule.value.status) : 1))

// タブナビゲーション
const tabs = computed(() => [
  { label: t('shift.detail.tabOverview'), icon: 'pi pi-calendar', to: `/shift/${scheduleId.value}` },
  { label: t('shift.detail.tabEdit'), icon: 'pi pi-pencil', to: `/shift/${scheduleId.value}/edit` },
  { label: t('shift.detail.tabRequests'), icon: 'pi pi-list', to: `/shift/${scheduleId.value}/requests` },
  { label: t('shift.detail.tabConstraints'), icon: 'pi pi-shield', to: `/shift/${scheduleId.value}/work-constraints` },
])
</script>

<template>
  <div class="mx-auto max-w-7xl px-4 py-6">
    <PageLoading v-if="loading" />

    <template v-else-if="schedule">
      <!-- ヘッダー -->
      <div class="mb-4 flex flex-wrap items-start gap-3">
        <BackButton :to="`/shift`" />
        <div class="min-w-0 flex-1">
          <div class="flex flex-wrap items-center gap-2">
            <h1 class="text-2xl font-bold text-surface-800 dark:text-surface-100">
              {{ schedule.title }}
            </h1>
            <ShiftStatusBadge :status="schedule.status" />
          </div>
          <p class="mt-1 text-sm text-surface-500">
            {{ schedule.startDate }} 〜 {{ schedule.endDate }}
          </p>
        </div>
        <div v-if="canManage" class="flex items-center gap-2">
          <Button
            icon="pi pi-pencil"
            :label="t('common.edit')"
            severity="secondary"
            size="small"
            @click="openEdit"
          />
          <Button
            v-if="nextStatus"
            icon="pi pi-arrow-right"
            :label="t(nextStatusLabelMap[schedule.status])"
            size="small"
            :loading="transitioning"
            @click="handleTransition"
          />
        </div>
      </div>

      <!-- ステッパー -->
      <div class="mb-6 flex items-center gap-0">
        <div
          v-for="(step, idx) in [t('shift.status.draft'), t('shift.status.collecting'), t('shift.status.adjusting'), t('shift.status.published')]"
          :key="idx"
          class="flex flex-1 items-center"
        >
          <div class="flex flex-col items-center">
            <div
              class="flex h-8 w-8 items-center justify-center rounded-full text-sm font-bold"
              :class="
                currentStep > idx + 1
                  ? 'bg-primary text-white'
                  : currentStep === idx + 1
                    ? 'bg-primary/20 text-primary ring-2 ring-primary'
                    : 'bg-surface-200 text-surface-400 dark:bg-surface-700'
              "
            >
              {{ idx + 1 }}
            </div>
            <span class="mt-1 hidden text-xs text-surface-500 sm:block">{{ step }}</span>
          </div>
          <div
            v-if="idx < 3"
            class="h-0.5 flex-1 transition-all"
            :class="currentStep > idx + 1 ? 'bg-primary' : 'bg-surface-200 dark:bg-surface-700'"
          />
        </div>
      </div>

      <!-- タブナビ -->
      <nav class="mb-6 flex gap-1 overflow-x-auto border-b border-surface-200 dark:border-surface-700">
        <NuxtLink
          v-for="tab in tabs"
          :key="tab.to"
          :to="tab.to"
          class="flex shrink-0 items-center gap-1.5 px-4 py-2 text-sm font-medium transition-colors"
          :class="
            $route.path === tab.to
              ? 'border-b-2 border-primary text-primary'
              : 'text-surface-500 hover:text-surface-800 dark:hover:text-surface-200'
          "
        >
          <i :class="tab.icon" />
          {{ tab.label }}
        </NuxtLink>
      </nav>

      <!-- カレンダービュー -->
      <section>
        <h2 class="mb-3 text-base font-semibold text-surface-700 dark:text-surface-300">
          {{ t('shift.detail.calendarView') }}
        </h2>

        <div v-if="dateList.length === 0" class="text-sm text-surface-400">
          {{ t('shift.detail.noDate') }}
        </div>

        <div v-else class="overflow-x-auto rounded-xl border border-surface-200 dark:border-surface-700">
          <table class="w-full border-collapse text-sm">
            <thead>
              <tr>
                <th class="sticky left-0 min-w-[100px] bg-surface-50 px-3 py-2 text-left text-xs font-medium text-surface-500 dark:bg-surface-800">
                  {{ t('shift.detail.colDate') }}
                </th>
                <th class="min-w-[140px] bg-surface-50 px-3 py-2 text-left text-xs font-medium text-surface-500 dark:bg-surface-800">
                  {{ t('shift.detail.colSlots') }}
                </th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="date in dateList"
                :key="date"
                class="border-t border-surface-100 dark:border-surface-700"
                :class="isWeekend(date) ? 'bg-blue-50/30 dark:bg-blue-900/10' : ''"
              >
                <!-- 日付列 -->
                <td class="sticky left-0 whitespace-nowrap bg-inherit px-3 py-2 font-medium">
                  <span>{{ formatDateShort(date) }}</span>
                  <span
                    class="ml-1 text-xs"
                    :class="
                      isWeekend(date) ? 'text-blue-500' : 'text-surface-400'
                    "
                  >
                    ({{ formatDayOfWeek(date) }})
                  </span>
                </td>

                <!-- スロット列 -->
                <td class="px-3 py-2">
                  <div v-if="slotsByDate.get(date)?.length" class="flex flex-wrap gap-1.5">
                    <NuxtLink
                      v-for="slot in slotsByDate.get(date)"
                      :key="slot.id"
                      :to="`/shift/${scheduleId}/edit`"
                      class="inline-flex items-center gap-1 rounded-lg border px-2 py-0.5 text-xs transition-colors hover:border-primary"
                      :class="
                        isUnderStaffed(slot)
                          ? 'border-red-300 bg-red-50 text-red-700 dark:border-red-700 dark:bg-red-900/20 dark:text-red-300'
                          : 'border-surface-200 bg-surface-50 text-surface-700 dark:border-surface-600 dark:bg-surface-800 dark:text-surface-300'
                      "
                    >
                      <span>{{ slot.startTime.slice(0, 5) }}〜{{ slot.endTime.slice(0, 5) }}</span>
                      <span v-if="slot.positionName" class="text-surface-400">/{{ slot.positionName }}</span>
                      <!-- 割当バッジ -->
                      <span
                        class="rounded px-1 font-medium"
                        :class="
                          isUnderStaffed(slot)
                            ? 'bg-red-100 text-red-700 dark:bg-red-800 dark:text-red-200'
                            : 'bg-green-100 text-green-700 dark:bg-green-800 dark:text-green-200'
                        "
                      >
                        {{ slot.assignedUserIds.length }}/{{ slot.requiredCount }}
                      </span>
                    </NuxtLink>
                  </div>
                  <span v-else class="text-xs text-surface-300">—</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </template>

    <!-- タイトル編集ダイアログ -->
    <Dialog
      v-model:visible="showEditDialog"
      :header="t('shift.detail.editDialogTitle')"
      :style="{ width: '400px' }"
      modal
    >
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('shift.index.formTitle') }}</label>
          <InputText v-model="editForm.title" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('shift.index.formNote') }}</label>
          <InputText v-model="editForm.note" class="w-full" />
        </div>
      </div>
      <template #footer>
        <Button :label="t('common.cancel')" text @click="showEditDialog = false" />
        <Button :label="t('common.save')" icon="pi pi-check" :loading="saving" @click="saveEdit" />
      </template>
    </Dialog>
  </div>
</template>
