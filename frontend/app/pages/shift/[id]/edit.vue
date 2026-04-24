<script setup lang="ts">
import type {
  ShiftScheduleResponse,
  ShiftSlotResponse,
  ShiftPositionResponse,
  CreateShiftSlotRequest,
} from '~/types/shift'

definePageMeta({ middleware: 'auth' })

const { t, locale } = useI18n()
const route = useRoute()
const teamStore = useTeamStore()
const { getSchedule } = useShiftApi()
const { listSlots, deleteSlot, bulkCreateSlots } = useShiftSlotApi()
const { listPositions } = useShiftPositionApi()
const { handleApiError } = useErrorHandler()
const { success } = useNotification()

const scheduleId = computed(() => Number(route.params.id))

// =====================================================
// データ取得
// =====================================================
const schedule = ref<ShiftScheduleResponse | null>(null)
const slots = ref<ShiftSlotResponse[]>([])
const positions = ref<ShiftPositionResponse[]>([])
const loading = ref(false)

const canManage = computed(() => {
  if (!schedule.value) return false
  return teamStore.myTeams.some(
    (t) =>
      t.id === schedule.value!.teamId &&
      (t.role === 'ADMIN' || t.role === 'SYSTEM_ADMIN' || t.role === 'DEPUTY_ADMIN'),
  )
})

onMounted(async () => {
  loading.value = true
  await teamStore.fetchMyTeams()
  // schedule を先に取得してチームIDを得る
  try {
    schedule.value = await getSchedule(scheduleId.value)
    const [sl, pos] = await Promise.all([
      listSlots(scheduleId.value),
      listPositions(schedule.value.teamId),
    ])
    slots.value = sl
    positions.value = pos
  } catch (error) {
    handleApiError(error)
  } finally {
    loading.value = false
  }
})

// =====================================================
// スロット個別編集
// =====================================================
const showSlotDialog = ref(false)
const editingSlot = ref<ShiftSlotResponse | null>(null)

function openCreateSlot() {
  editingSlot.value = null
  showSlotDialog.value = true
}

function openEditSlot(slot: ShiftSlotResponse) {
  editingSlot.value = slot
  showSlotDialog.value = true
}

function handleSlotSaved(slot: ShiftSlotResponse) {
  const idx = slots.value.findIndex((s) => s.id === slot.id)
  if (idx >= 0) {
    slots.value[idx] = slot
  } else {
    slots.value.push(slot)
  }
  // 日付順でソート
  slots.value.sort((a, b) => a.slotDate.localeCompare(b.slotDate) || a.startTime.localeCompare(b.startTime))
}

async function handleDeleteSlot(slotId: number) {
  try {
    await deleteSlot(slotId)
    slots.value = slots.value.filter((s) => s.id !== slotId)
    success(t('shift.slot.deleteSuccess'))
  } catch (error) {
    handleApiError(error)
  }
}

// =====================================================
// 一括作成ダイアログ
// =====================================================
const showBulkDialog = ref(false)
const bulkCreating = ref(false)

interface BulkForm {
  fromDate: string
  toDate: string
  startTime: string
  endTime: string
  positionId: number | null
  requiredCount: number
  /** 曜日フィルタ（0=日 〜 6=土、空配列=全て） */
  dayOfWeeks: number[]
}

const bulkForm = ref<BulkForm>({
  fromDate: '',
  toDate: '',
  startTime: '09:00',
  endTime: '17:00',
  positionId: null,
  requiredCount: 1,
  dayOfWeeks: [],
})

const dayOfWeekOptions = [
  { label: '日', value: 0 },
  { label: '月', value: 1 },
  { label: '火', value: 2 },
  { label: '水', value: 3 },
  { label: '木', value: 4 },
  { label: '金', value: 5 },
  { label: '土', value: 6 },
]

const positionOptions = computed(() => [
  { label: t('shift.slot.noPosition'), value: null as number | null },
  ...positions.value.map((p) => ({ label: p.name, value: p.id as number | null })),
])

async function handleBulkCreate() {
  if (!bulkForm.value.fromDate || !bulkForm.value.toDate) return
  bulkCreating.value = true
  try {
    // 日付範囲を展開してスロット配列を生成
    const generatedSlots: CreateShiftSlotRequest[] = []
    const cur = new Date(bulkForm.value.fromDate)
    const end = new Date(bulkForm.value.toDate)
    while (cur <= end) {
      const dow = cur.getDay()
      const shouldInclude =
        bulkForm.value.dayOfWeeks.length === 0 || bulkForm.value.dayOfWeeks.includes(dow)
      if (shouldInclude) {
        generatedSlots.push({
          slotDate: cur.toISOString().split('T')[0]!,
          startTime: bulkForm.value.startTime,
          endTime: bulkForm.value.endTime,
          positionId: bulkForm.value.positionId ?? undefined,
          requiredCount: bulkForm.value.requiredCount,
        })
      }
      cur.setDate(cur.getDate() + 1)
    }
    if (generatedSlots.length === 0) {
      return
    }
    const created = await bulkCreateSlots(scheduleId.value, { slots: generatedSlots })
    slots.value.push(...created)
    slots.value.sort((a, b) => a.slotDate.localeCompare(b.slotDate) || a.startTime.localeCompare(b.startTime))
    success(t('shift.slot.bulkCreateSuccess', { count: created.length }))
    showBulkDialog.value = false
  } catch (error) {
    handleApiError(error)
  } finally {
    bulkCreating.value = false
  }
}

// =====================================================
// ソート済みスロット（日付 → 開始時刻順）
// =====================================================
const sortedSlots = computed(() =>
  [...slots.value].sort(
    (a, b) => a.slotDate.localeCompare(b.slotDate) || a.startTime.localeCompare(b.startTime),
  ),
)

function formatDate(dateStr: string): string {
  return new Date(dateStr).toLocaleDateString(locale.value, { month: 'short', day: 'numeric', weekday: 'short' })
}
</script>

<template>
  <div class="mx-auto max-w-5xl px-4 py-6">
    <!-- ナビゲーション -->
    <div class="mb-4 flex items-center gap-2">
      <BackButton :to="`/shift/${scheduleId}`" />
      <h1 class="text-xl font-bold text-surface-800 dark:text-surface-100">
        {{ schedule?.title ?? '...' }}
      </h1>
      <ShiftStatusBadge v-if="schedule" :status="schedule.status" />
    </div>

    <!-- タブ -->
    <nav class="mb-6 flex gap-1 overflow-x-auto border-b border-surface-200 dark:border-surface-700">
      <NuxtLink
        :to="`/shift/${scheduleId}`"
        class="flex shrink-0 items-center gap-1.5 px-4 py-2 text-sm font-medium text-surface-500 transition-colors hover:text-surface-800 dark:hover:text-surface-200"
      >
        <i class="pi pi-calendar" />{{ t('shift.detail.tabOverview') }}
      </NuxtLink>
      <span class="flex shrink-0 items-center gap-1.5 border-b-2 border-primary px-4 py-2 text-sm font-medium text-primary">
        <i class="pi pi-pencil" />{{ t('shift.detail.tabEdit') }}
      </span>
      <NuxtLink
        :to="`/shift/${scheduleId}/requests`"
        class="flex shrink-0 items-center gap-1.5 px-4 py-2 text-sm font-medium text-surface-500 transition-colors hover:text-surface-800 dark:hover:text-surface-200"
      >
        <i class="pi pi-list" />{{ t('shift.detail.tabRequests') }}
      </NuxtLink>
      <NuxtLink
        :to="`/shift/${scheduleId}/work-constraints`"
        class="flex shrink-0 items-center gap-1.5 px-4 py-2 text-sm font-medium text-surface-500 transition-colors hover:text-surface-800 dark:hover:text-surface-200"
      >
        <i class="pi pi-shield" />{{ t('shift.detail.tabConstraints') }}
      </NuxtLink>
    </nav>

    <PageLoading v-if="loading" />

    <template v-else>
      <!-- アクションバー -->
      <div v-if="canManage" class="mb-4 flex flex-wrap gap-2">
        <Button
          icon="pi pi-plus"
          :label="t('shift.slot.createTitle')"
          size="small"
          @click="openCreateSlot"
        />
        <Button
          icon="pi pi-list"
          :label="t('shift.slot.bulkCreate')"
          severity="secondary"
          size="small"
          @click="showBulkDialog = true"
        />
      </div>

      <!-- スロット一覧 -->
      <div v-if="sortedSlots.length === 0">
        <DashboardEmptyState icon="pi pi-table" :message="t('shift.slot.noSlot')" />
      </div>

      <div v-else class="space-y-2">
        <div
          v-for="slot in sortedSlots"
          :key="slot.id"
          class="flex items-center gap-3 rounded-lg border border-surface-200 bg-surface-0 px-4 py-3 dark:border-surface-700 dark:bg-surface-900"
        >
          <!-- 日付 -->
          <div class="w-24 shrink-0">
            <p class="text-sm font-medium text-surface-700 dark:text-surface-200">
              {{ formatDate(slot.slotDate) }}
            </p>
          </div>

          <!-- 時間・ポジション -->
          <div class="min-w-0 flex-1">
            <p class="text-sm font-medium">
              {{ slot.startTime.slice(0, 5) }} 〜 {{ slot.endTime.slice(0, 5) }}
            </p>
            <p v-if="slot.positionName" class="text-xs text-surface-500">
              {{ slot.positionName }}
            </p>
          </div>

          <!-- 割当状況 -->
          <div class="shrink-0 text-center">
            <span
              class="rounded-full px-2 py-0.5 text-xs font-medium"
              :class="
                slot.assignedUserIds.length < slot.requiredCount
                  ? 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-300'
                  : 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-300'
              "
            >
              {{ slot.assignedUserIds.length }} / {{ slot.requiredCount }}
            </span>
            <p class="mt-0.5 text-xs text-surface-400">{{ t('shift.slot.assigned') }}</p>
          </div>

          <!-- 操作 -->
          <div v-if="canManage" class="flex gap-1">
            <Button
              icon="pi pi-pencil"
              text
              rounded
              size="small"
              @click="openEditSlot(slot)"
            />
            <Button
              icon="pi pi-trash"
              text
              rounded
              size="small"
              severity="danger"
              @click="handleDeleteSlot(slot.id)"
            />
          </div>
        </div>
      </div>
    </template>

    <!-- スロット編集ダイアログ -->
    <ShiftSlotEditDialog
      v-model:visible="showSlotDialog"
      :schedule-id="scheduleId"
      :slot="editingSlot"
      :positions="positions"
      @saved="handleSlotSaved"
    />

    <!-- 一括作成ダイアログ -->
    <Dialog
      v-model:visible="showBulkDialog"
      :header="t('shift.slot.bulkCreate')"
      :style="{ width: '520px' }"
      modal
    >
      <div class="flex flex-col gap-4">
        <!-- 日付範囲 -->
        <div class="grid grid-cols-2 gap-3">
          <div>
            <label class="mb-1 block text-sm font-medium">{{ t('shift.index.fromDate') }}</label>
            <InputText v-model="bulkForm.fromDate" type="date" class="w-full" />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">{{ t('shift.index.toDate') }}</label>
            <InputText v-model="bulkForm.toDate" type="date" class="w-full" />
          </div>
        </div>

        <!-- 曜日フィルタ -->
        <div>
          <label class="mb-2 block text-sm font-medium">{{ t('shift.slot.dayOfWeek') }}</label>
          <div class="flex flex-wrap gap-2">
            <button
              v-for="opt in dayOfWeekOptions"
              :key="opt.value"
              class="rounded-full border-2 px-3 py-1 text-sm font-medium transition-all"
              :class="
                bulkForm.dayOfWeeks.includes(opt.value)
                  ? 'border-primary bg-primary/10 text-primary'
                  : 'border-surface-200 text-surface-500 dark:border-surface-600'
              "
              @click="
                bulkForm.dayOfWeeks.includes(opt.value)
                  ? (bulkForm.dayOfWeeks = bulkForm.dayOfWeeks.filter((d) => d !== opt.value))
                  : bulkForm.dayOfWeeks.push(opt.value)
              "
            >
              {{ opt.label }}
            </button>
          </div>
          <p class="mt-1 text-xs text-surface-400">{{ t('shift.slot.dayOfWeekHint') }}</p>
        </div>

        <!-- 時間帯 -->
        <div class="grid grid-cols-2 gap-3">
          <div>
            <label class="mb-1 block text-sm font-medium">{{ t('shift.slot.startTime') }}</label>
            <InputText v-model="bulkForm.startTime" type="time" class="w-full" />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">{{ t('shift.slot.endTime') }}</label>
            <InputText v-model="bulkForm.endTime" type="time" class="w-full" />
          </div>
        </div>

        <!-- ポジション -->
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('shift.slot.position') }}</label>
          <Select
            v-model="bulkForm.positionId"
            :options="positionOptions"
            option-label="label"
            option-value="value"
            class="w-full"
          />
        </div>

        <!-- 必要人数 -->
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('shift.slot.requiredCount') }}</label>
          <InputNumber v-model="bulkForm.requiredCount" :min="1" :max="99" class="w-full" />
        </div>
      </div>

      <template #footer>
        <Button :label="t('common.cancel')" text @click="showBulkDialog = false" />
        <Button
          :label="t('common.create')"
          icon="pi pi-check"
          :loading="bulkCreating"
          :disabled="!bulkForm.fromDate || !bulkForm.toDate"
          @click="handleBulkCreate"
        />
      </template>
    </Dialog>
  </div>
</template>
