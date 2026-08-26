<script setup lang="ts">
/**
 * F17.1 村機能 — 村詳細 / 歳時記タブ（永続シェル子）
 *
 * 設計書: docs/features/F17.1_village_community.md §2.2 / §13.2
 *
 * 永続シェル方式（SPA）: 村データ・権限・VillageHeader・アクションは親
 * `pages/villages/[id].vue` に集約。本ファイルは歳時記カレンダーパネル本体のみ。
 *
 * 構成:
 *   - 月送りナビ（前月 / 今月 / 翌月）
 *   - 月別行事一覧（カード形式、絵文字 + タイトル + 開催日）
 *   - HEADMAN/ELDER のみ「行事を追加」ボタン
 *   - 行事クリックで詳細 Dialog（HEADMAN/ELDER は編集 / 削除も可能）
 */
import dayjs from 'dayjs'
import type {
  VillageCalendarEventCreateRequest,
  VillageCalendarEventLogResponse,
  VillageCalendarEventResponse,
  VillageCalendarEventUpdateRequest,
} from '~/types/village'
import { useVillageContext } from '~/composables/useVillageContext'

// auth は各タブで明示宣言（本コードベースの規約。親シェルも auth を持つ）。
definePageMeta({ middleware: 'auth' })

const route = useRoute()
const villageId = computed(() => String(route.params.id))
const { t } = useI18n()
const villageApi = useVillageApi()
const { handleApiError } = useErrorHandler()
const { success } = useNotification()
const { confirmAction } = useConfirmDialog()
const { userTimezone } = useDatetime()

// 権限は親シェルから inject
const { perms, currentUserId } = useVillageContext()

// =====================================================================
// State — カレンダー
// =====================================================================

/** 現在表示中の年月（1 始まり） */
const currentYear = ref<number>(dayjs().tz(userTimezone.value).year())
const currentMonth = ref<number>(dayjs().tz(userTimezone.value).month() + 1)

const events = ref<VillageCalendarEventResponse[]>([])
const eventsLoading = ref(false)

const canManage = computed(() => perms.value.isAdmin)
const isVillager = computed(() => perms.value.isMember)

// =====================================================================
// 月別フィルタ＋API 呼び出し
// =====================================================================

/** YYYY-MM-DD 形式 */
function formatYmd(y: number, m: number, d: number): string {
  return `${y}-${String(m).padStart(2, '0')}-${String(d).padStart(2, '0')}`
}

async function loadEvents() {
  eventsLoading.value = true
  try {
    // BE の @RequestParam は year/month のみ（from/to は存在しない。年中行事は月のみで判定するため）
    const result = await villageApi.listCalendarEvents(villageId.value, {
      year: currentYear.value,
      month: currentMonth.value,
    })
    events.value = result.items
  }
  catch (error) {
    events.value = []
    handleApiError(error, t('village.calendar.loadFailed'))
  }
  finally {
    eventsLoading.value = false
  }
}

// =====================================================================
// 月送り
// =====================================================================

function goPrevMonth() {
  if (currentMonth.value === 1) {
    currentYear.value -= 1
    currentMonth.value = 12
  }
  else {
    currentMonth.value -= 1
  }
  loadEvents()
}

function goNextMonth() {
  if (currentMonth.value === 12) {
    currentYear.value += 1
    currentMonth.value = 1
  }
  else {
    currentMonth.value += 1
  }
  loadEvents()
}

function goToday() {
  const now = dayjs().tz(userTimezone.value)
  currentYear.value = now.year()
  currentMonth.value = now.month() + 1
  loadEvents()
}

/** 表示用ラベル「2026年5月」など */
const monthLabel = computed(() => {
  try {
    return dayjs.tz(`${currentYear.value}-${String(currentMonth.value).padStart(2, '0')}-01`, userTimezone.value).format('YYYY年M月')
  }
  catch {
    return `${currentYear.value}-${String(currentMonth.value).padStart(2, '0')}`
  }
})

// =====================================================================
// 行事 CRUD Dialog
// =====================================================================

interface EventFormState {
  title: string
  description: string
  eventDate: string
  eventEndDate: string
  isAnnualRecurring: boolean
  iconEmoji: string
  colorHex: string
}

function emptyForm(): EventFormState {
  return {
    title: '',
    description: '',
    eventDate: formatYmd(currentYear.value, currentMonth.value, 1),
    eventEndDate: '',
    isAnnualRecurring: false,
    iconEmoji: '',
    colorHex: '',
  }
}

const showCreateDialog = ref(false)
const createForm = ref<EventFormState>(emptyForm())

const showEditDialog = ref(false)
const editForm = ref<EventFormState>(emptyForm())
const editTargetId = ref<string | null>(null)

const showDetailDialog = ref(false)
const detailEvent = ref<VillageCalendarEventResponse | null>(null)

function openCreateDialog() {
  createForm.value = emptyForm()
  showCreateDialog.value = true
}

function openDetailDialog(ev: VillageCalendarEventResponse) {
  detailEvent.value = ev
  showDetailDialog.value = true
  void loadEventLogs(ev.id)
}

// =====================================================================
// F17.2 Wave1 ④歳時記×村史の年輪（去年の様子）
// 設計書: docs/features/F17.2_village_events_activation.md §6
// =====================================================================

const eventLogs = ref<VillageCalendarEventLogResponse[]>([])
const eventLogsLoading = ref(false)
const showAddLogForm = ref(false)
const addLogSubmitting = ref(false)

interface LogFormState {
  year: number | null
  note: string
  photoR2Key: string
}

function emptyLogForm(): LogFormState {
  return {
    year: dayjs().tz(userTimezone.value).year(),
    note: '',
    photoR2Key: '',
  }
}

const logForm = ref<LogFormState>(emptyLogForm())

async function loadEventLogs(eventId: string) {
  eventLogsLoading.value = true
  try {
    eventLogs.value = await villageApi.listCalendarEventLogs(villageId.value, eventId, { size: 50 })
  }
  catch (error) {
    eventLogs.value = []
    handleApiError(error, t('village.calendar.log.loadFailed'))
  }
  finally {
    eventLogsLoading.value = false
  }
}

function openAddLogForm() {
  logForm.value = emptyLogForm()
  showAddLogForm.value = true
}

async function submitAddLog() {
  if (!detailEvent.value || !logForm.value.year) return
  addLogSubmitting.value = true
  try {
    await villageApi.addCalendarEventLog(villageId.value, detailEvent.value.id, {
      year: logForm.value.year,
      note: logForm.value.note || null,
      photoR2Key: logForm.value.photoR2Key || null,
    })
    showAddLogForm.value = false
    success(t('village.calendar.log.addSuccess'))
    await loadEventLogs(detailEvent.value.id)
  }
  catch (error) {
    handleApiError(error, t('village.calendar.log.addFailed'))
  }
  finally {
    addLogSubmitting.value = false
  }
}

function canDeleteLog(log: VillageCalendarEventLogResponse): boolean {
  return canManage.value || (currentUserId.value !== null && log.createdByUserId === currentUserId.value)
}

function deleteLog(log: VillageCalendarEventLogResponse) {
  if (!detailEvent.value) return
  const eventId = detailEvent.value.id
  confirmAction({
    message: t('village.calendar.log.deleteConfirm'),
    onAccept: async () => {
      try {
        await villageApi.deleteCalendarEventLog(villageId.value, eventId, log.id)
        eventLogs.value = eventLogs.value.filter(l => l.id !== log.id)
        success(t('village.calendar.log.deleteSuccess'))
      }
      catch (error) {
        handleApiError(error, t('village.calendar.log.deleteFailed'))
      }
    },
  })
}

function openEditDialog(ev: VillageCalendarEventResponse) {
  editForm.value = {
    title: ev.title,
    description: ev.description ?? '',
    eventDate: ev.eventDate,
    eventEndDate: ev.eventEndDate ?? '',
    isAnnualRecurring: ev.isAnnualRecurring,
    iconEmoji: ev.iconEmoji ?? '',
    colorHex: ev.colorHex ?? '',
  }
  editTargetId.value = ev.id
  showDetailDialog.value = false
  showEditDialog.value = true
}

async function submitCreate() {
  const body: VillageCalendarEventCreateRequest = {
    title: createForm.value.title,
    description: createForm.value.description || null,
    eventDate: createForm.value.eventDate,
    eventEndDate: createForm.value.eventEndDate || null,
    isAnnualRecurring: createForm.value.isAnnualRecurring,
    iconEmoji: createForm.value.iconEmoji || null,
    colorHex: createForm.value.colorHex || null,
  }
  try {
    await villageApi.createCalendarEvent(villageId.value, body)
    showCreateDialog.value = false
    success(t('village.calendar.saveSuccess'))
    await loadEvents()
  }
  catch (error) {
    handleApiError(error, t('village.calendar.create'))
  }
}

async function submitEdit() {
  if (!editTargetId.value) return
  const body: VillageCalendarEventUpdateRequest = {
    title: editForm.value.title || null,
    description: editForm.value.description || null,
    eventDate: editForm.value.eventDate || null,
    eventEndDate: editForm.value.eventEndDate || null,
    isAnnualRecurring: editForm.value.isAnnualRecurring,
    iconEmoji: editForm.value.iconEmoji || null,
    colorHex: editForm.value.colorHex || null,
  }
  try {
    await villageApi.updateCalendarEvent(villageId.value, editTargetId.value, body)
    showEditDialog.value = false
    success(t('village.calendar.saveSuccess'))
    await loadEvents()
  }
  catch (error) {
    handleApiError(error, t('village.calendar.edit'))
  }
}

function submitDelete(ev: VillageCalendarEventResponse) {
  confirmAction({
    message: t('village.calendar.confirmDelete'),
    onAccept: async () => {
      try {
        await villageApi.deleteCalendarEvent(villageId.value, ev.id)
        showDetailDialog.value = false
        success(t('village.calendar.deleteSuccess'))
        await loadEvents()
      }
      catch (error) {
        handleApiError(error, t('village.calendar.delete'))
      }
    },
  })
}

// =====================================================================
// Init
// =====================================================================

onMounted(() => {
  void loadEvents()
})
</script>

<template>
  <div class="mx-auto max-w-3xl p-4 sm:p-6">
    <!-- 月送りナビ + 追加ボタン -->
    <div class="flex items-center justify-between flex-wrap gap-3 mb-4">
      <div class="flex items-center gap-2">
        <Button
          :label="t('village.calendar.prev')"
          icon="pi pi-chevron-left"
          size="small"
          outlined
          @click="goPrevMonth"
        />
        <Button
          :label="t('village.calendar.today')"
          size="small"
          text
          @click="goToday"
        />
        <Button
          :label="t('village.calendar.next')"
          icon="pi pi-chevron-right"
          icon-pos="right"
          size="small"
          outlined
          @click="goNextMonth"
        />
        <span class="ml-2 font-semibold text-lg">{{ monthLabel }}</span>
      </div>
      <Button
        v-if="canManage"
        :label="t('village.calendar.create')"
        icon="pi pi-plus"
        severity="primary"
        size="small"
        @click="openCreateDialog"
      />
    </div>

    <!-- 行事一覧 -->
    <div v-if="eventsLoading" class="text-center py-12 text-surface-500">
      <i class="pi pi-spin pi-spinner text-2xl" />
    </div>
    <DashboardEmptyState
      v-else-if="events.length === 0"
      icon="pi pi-calendar"
      :message="t('village.calendar.noEventsInMonth')"
    />
    <div v-else class="flex flex-col gap-2">
      <button
        v-for="ev in events"
        :key="ev.id"
        type="button"
        class="village-calendar__row flex items-center gap-3 rounded-lg border border-surface-200 p-3 text-left transition hover:bg-surface-50 dark:border-surface-700 dark:hover:bg-surface-800"
        :style="ev.colorHex ? { borderLeft: `4px solid ${ev.colorHex}` } : undefined"
        @click="openDetailDialog(ev)"
      >
        <span class="text-2xl">{{ ev.iconEmoji || '🗓' }}</span>
        <div class="flex flex-col min-w-0 flex-1">
          <span class="font-medium truncate">{{ ev.title }}</span>
          <span class="text-xs text-surface-500">
            {{ ev.eventDate }}<span v-if="ev.eventEndDate"> 〜 {{ ev.eventEndDate }}</span>
            <span v-if="ev.isAnnualRecurring" class="ml-2">
              <i class="pi pi-replay" /> {{ t('village.calendar.annualRecurring') }}
            </span>
          </span>
        </div>
      </button>
    </div>

    <!-- 作成 Dialog -->
    <Dialog
      v-model:visible="showCreateDialog"
      modal
      :draggable="false"
      :header="t('village.calendar.createTitle')"
      :style="{ width: '32rem' }"
      :breakpoints="{ '640px': '92vw' }"
    >
      <div class="flex flex-col gap-3">
        <div>
          <label class="block text-sm font-medium mb-1">
            {{ t('village.calendar.eventTitle') }}
          </label>
          <InputText v-model="createForm.title" class="w-full" />
        </div>
        <div>
          <label class="block text-sm font-medium mb-1">
            {{ t('village.calendar.description') }}
          </label>
          <Textarea v-model="createForm.description" class="w-full" rows="3" />
        </div>
        <div class="grid grid-cols-2 gap-3">
          <div>
            <label class="block text-sm font-medium mb-1">
              {{ t('village.calendar.eventDate') }}
            </label>
            <InputText v-model="createForm.eventDate" type="date" class="w-full" />
          </div>
          <div>
            <label class="block text-sm font-medium mb-1">
              {{ t('village.calendar.eventEndDate') }}
            </label>
            <InputText v-model="createForm.eventEndDate" type="date" class="w-full" />
          </div>
        </div>
        <div class="grid grid-cols-2 gap-3">
          <div>
            <label class="block text-sm font-medium mb-1">
              {{ t('village.calendar.icon') }}
            </label>
            <InputText v-model="createForm.iconEmoji" maxlength="4" class="w-full" />
          </div>
          <div>
            <label class="block text-sm font-medium mb-1">
              {{ t('village.calendar.color') }}
            </label>
            <InputText v-model="createForm.colorHex" type="color" class="w-full h-10" />
          </div>
        </div>
        <div class="flex items-center gap-2">
          <Checkbox
            v-model="createForm.isAnnualRecurring"
            input-id="calendar-recur"
            binary
          />
          <label for="calendar-recur" class="text-sm">
            {{ t('village.calendar.annualRecurring') }}
          </label>
        </div>
      </div>
      <template #footer>
        <Button
          :label="t('village.action.cancel')"
          severity="secondary"
          text
          @click="showCreateDialog = false"
        />
        <Button
          :label="t('village.action.save')"
          icon="pi pi-check"
          severity="primary"
          @click="submitCreate"
        />
      </template>
    </Dialog>

    <!-- 詳細 Dialog -->
    <Dialog
      v-model:visible="showDetailDialog"
      modal
      :draggable="false"
      :header="detailEvent?.title ?? ''"
      :style="{ width: '34rem', maxHeight: '90vh' }"
      :breakpoints="{ '640px': '92vw' }"
    >
      <div v-if="detailEvent" class="flex flex-col gap-3 max-h-[70vh] overflow-y-auto pr-1">
        <div class="flex items-center gap-2 text-sm">
          <span class="text-2xl">{{ detailEvent.iconEmoji || '🗓' }}</span>
          <span>
            {{ detailEvent.eventDate }}<span v-if="detailEvent.eventEndDate"> 〜 {{ detailEvent.eventEndDate }}</span>
          </span>
        </div>
        <div v-if="detailEvent.description" class="whitespace-pre-wrap text-sm">
          {{ detailEvent.description }}
        </div>
        <div v-if="detailEvent.isAnnualRecurring" class="text-xs text-surface-500">
          <i class="pi pi-replay" /> {{ t('village.calendar.annualRecurring') }}
        </div>

        <!-- F17.2 Wave1 ④歳時記×村史の年輪（去年の様子） -->
        <hr class="border-surface-200 dark:border-surface-700">
        <div class="flex flex-col gap-2">
          <div class="flex items-center justify-between">
            <h3 class="font-semibold">
              {{ t('village.calendar.log.title') }}
            </h3>
            <Button
              v-if="isVillager && !showAddLogForm"
              :label="t('village.calendar.log.addLog')"
              icon="pi pi-plus"
              size="small"
              text
              @click="openAddLogForm"
            />
          </div>

          <div v-if="showAddLogForm" class="flex flex-col gap-2 rounded border border-surface-200 p-3 dark:border-surface-700">
            <div class="grid grid-cols-2 gap-2">
              <div>
                <label class="block text-xs font-medium mb-1">{{ t('village.calendar.log.year') }}</label>
                <InputNumber
                  v-model="logForm.year"
                  :use-grouping="false"
                  :min="1900"
                  :max="3000"
                  class="w-full"
                />
              </div>
              <div>
                <label class="block text-xs font-medium mb-1">{{ t('village.calendar.log.photoKey') }}</label>
                <InputText v-model="logForm.photoR2Key" class="w-full" :placeholder="t('village.calendar.log.photoKeyHint')" />
              </div>
            </div>
            <div>
              <label class="block text-xs font-medium mb-1">{{ t('village.calendar.log.note') }}</label>
              <Textarea v-model="logForm.note" class="w-full" rows="2" :placeholder="t('village.calendar.log.notePlaceholder')" />
            </div>
            <div class="flex items-center justify-end gap-2">
              <Button
                :label="t('village.action.cancel')"
                severity="secondary"
                text
                size="small"
                @click="showAddLogForm = false"
              />
              <Button
                :label="t('village.calendar.log.submit')"
                icon="pi pi-check"
                severity="primary"
                size="small"
                :loading="addLogSubmitting"
                :disabled="!logForm.year"
                @click="submitAddLog"
              />
            </div>
          </div>

          <div v-if="eventLogsLoading" class="text-center py-3 text-surface-500">
            <i class="pi pi-spin pi-spinner" />
          </div>
          <div v-else-if="eventLogs.length === 0" class="text-xs text-surface-500">
            {{ t('village.calendar.log.empty') }}
          </div>
          <div v-else class="flex flex-col gap-2">
            <div
              v-for="log in eventLogs"
              :key="log.id"
              class="rounded border border-surface-200 p-2 text-sm dark:border-surface-700"
            >
              <div class="flex items-center justify-between gap-2">
                <span class="font-medium">
                  {{ t('village.calendar.log.lastYear', { year: log.year }) }}
                </span>
                <Button
                  v-if="canDeleteLog(log)"
                  icon="pi pi-trash"
                  severity="danger"
                  text
                  size="small"
                  :aria-label="t('village.calendar.log.delete')"
                  @click="deleteLog(log)"
                />
              </div>
              <img
                v-if="log.photoUrl"
                :src="log.photoUrl"
                :alt="t('village.calendar.log.title')"
                class="mt-2 max-h-40 w-full rounded object-cover"
              >
              <p v-if="log.note" class="whitespace-pre-wrap mt-1">
                {{ log.note }}
              </p>
              <p class="text-xs text-surface-500 mt-1">
                {{ log.createdByDisplayName }}
              </p>
            </div>
          </div>
        </div>
      </div>
      <template #footer>
        <Button
          v-if="canManage && detailEvent"
          :label="t('village.calendar.edit')"
          icon="pi pi-pencil"
          severity="secondary"
          outlined
          @click="openEditDialog(detailEvent)"
        />
        <Button
          v-if="canManage && detailEvent"
          :label="t('village.calendar.delete')"
          icon="pi pi-trash"
          severity="danger"
          outlined
          @click="submitDelete(detailEvent)"
        />
        <Button
          :label="t('village.action.cancel')"
          severity="secondary"
          text
          @click="showDetailDialog = false"
        />
      </template>
    </Dialog>

    <!-- 編集 Dialog -->
    <Dialog
      v-model:visible="showEditDialog"
      modal
      :draggable="false"
      :header="t('village.calendar.editTitle')"
      :style="{ width: '32rem' }"
      :breakpoints="{ '640px': '92vw' }"
    >
      <div class="flex flex-col gap-3">
        <div>
          <label class="block text-sm font-medium mb-1">
            {{ t('village.calendar.eventTitle') }}
          </label>
          <InputText v-model="editForm.title" class="w-full" />
        </div>
        <div>
          <label class="block text-sm font-medium mb-1">
            {{ t('village.calendar.description') }}
          </label>
          <Textarea v-model="editForm.description" class="w-full" rows="3" />
        </div>
        <div class="grid grid-cols-2 gap-3">
          <div>
            <label class="block text-sm font-medium mb-1">
              {{ t('village.calendar.eventDate') }}
            </label>
            <InputText v-model="editForm.eventDate" type="date" class="w-full" />
          </div>
          <div>
            <label class="block text-sm font-medium mb-1">
              {{ t('village.calendar.eventEndDate') }}
            </label>
            <InputText v-model="editForm.eventEndDate" type="date" class="w-full" />
          </div>
        </div>
        <div class="grid grid-cols-2 gap-3">
          <div>
            <label class="block text-sm font-medium mb-1">
              {{ t('village.calendar.icon') }}
            </label>
            <InputText v-model="editForm.iconEmoji" maxlength="4" class="w-full" />
          </div>
          <div>
            <label class="block text-sm font-medium mb-1">
              {{ t('village.calendar.color') }}
            </label>
            <InputText v-model="editForm.colorHex" type="color" class="w-full h-10" />
          </div>
        </div>
        <div class="flex items-center gap-2">
          <Checkbox
            v-model="editForm.isAnnualRecurring"
            input-id="calendar-edit-recur"
            binary
          />
          <label for="calendar-edit-recur" class="text-sm">
            {{ t('village.calendar.annualRecurring') }}
          </label>
        </div>
      </div>
      <template #footer>
        <Button
          :label="t('village.action.cancel')"
          severity="secondary"
          text
          @click="showEditDialog = false"
        />
        <Button
          :label="t('village.action.save')"
          icon="pi pi-check"
          severity="primary"
          @click="submitEdit"
        />
      </template>
    </Dialog>
  </div>
</template>
