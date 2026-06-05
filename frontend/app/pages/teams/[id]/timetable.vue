<script setup lang="ts">
import type {
  Timetable,
  TimetableTerm,
  WeeklyView,
  TimetableChange,
  DayOfWeekKey,
  TimetableStatus,
  TimetableVisibility,
  ChangeType,
} from '~/types/timetable'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const teamId = computed(() => String(route.params.id))
const timetableApi = useTimetableApi()
const notification = useNotification()
const { isAdmin, can, loadPermissions } = useRoleAccess('team', teamId)

const canManage = computed(() => isAdmin.value || can('MANAGE_TIMETABLE'))

const timetables = ref<Timetable[]>([])
const selectedTimetable = ref<Timetable | null>(null)
const weeklyView = ref<WeeklyView | null>(null)
const changes = ref<TimetableChange[]>([])
const terms = ref<TimetableTerm[]>([])
const loading = ref(true)
const activeTab = ref('0')

// 週ナビゲーション
const currentWeekOf = ref<string | undefined>(undefined)

// 時間割作成ダイアログ
const showCreateDialog = ref(false)
const createSubmitting = ref(false)

// 臨時変更ダイアログ
const showChangeDialog = ref(false)
const changeSubmitting = ref(false)

interface CreatePayload {
  name: string
  termId: number
  effectiveFrom: string
  effectiveUntil: string | null
  visibility: TimetableVisibility
  weekPatternEnabled: boolean
  weekPatternBaseDate: string | null
  notes: string | null
}

interface ChangePayload {
  targetDate: string
  periodNumber: number | null
  changeType: ChangeType
  subjectName: string | null
  teacherName: string | null
  roomName: string | null
  reason: string | null
  notifyMembers: boolean
}

const DAY_KEYS: DayOfWeekKey[] = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN']
const dayLabels: Record<DayOfWeekKey, string> = {
  MON: '月',
  TUE: '火',
  WED: '水',
  THU: '木',
  FRI: '金',
  SAT: '土',
  SUN: '日',
}

function statusLabel(s: TimetableStatus | string) {
  return (
    {
      DRAFT: t('timetable.status_draft'),
      ACTIVE: t('timetable.status_active'),
      ARCHIVED: t('timetable.status_archived'),
    }[s] ?? s
  )
}
function statusSeverity(s: TimetableStatus | string) {
  return ({ DRAFT: 'warn', ACTIVE: 'success', ARCHIVED: 'secondary' }[s] ?? 'info') as
    | 'warn'
    | 'success'
    | 'secondary'
    | 'info'
}

async function loadData() {
  loading.value = true
  try {
    await loadPermissions()
    timetables.value = await timetableApi.list(teamId.value)
    const active = timetables.value.find((tt) => tt.status === 'ACTIVE')
    if (active) await selectTimetable(active)
  } catch {
    notification.error(t('timetable.load_error'))
  } finally {
    loading.value = false
  }
}

async function selectTimetable(tt: Timetable) {
  selectedTimetable.value = tt
  currentWeekOf.value = undefined
  try {
    weeklyView.value = await timetableApi.getWeekly(teamId.value, tt.id)
  } catch {
    notification.error(t('timetable.weekly_error'))
  }
  if (activeTab.value === '2') {
    await loadChanges()
  }
}

async function loadChanges() {
  if (!selectedTimetable.value) return
  try {
    changes.value = await timetableApi.listChanges(selectedTimetable.value.id)
  } catch {
    // サイレント失敗
  }
}

async function navigateWeek(direction: 'prev' | 'next' | 'current') {
  if (!selectedTimetable.value) return
  let date: Date
  if (direction === 'current') {
    date = new Date()
    currentWeekOf.value = undefined
  } else {
    const base = currentWeekOf.value ? new Date(currentWeekOf.value) : new Date()
    date = new Date(base)
    date.setDate(date.getDate() + (direction === 'next' ? 7 : -7))
    currentWeekOf.value = date.toISOString().slice(0, 10)
  }
  try {
    weeklyView.value = await timetableApi.getWeekly(
      teamId.value,
      selectedTimetable.value.id,
      currentWeekOf.value,
    )
  } catch {
    notification.error(t('timetable.weekly_error'))
  }
}

async function handleActivate(id: number) {
  try {
    await timetableApi.activate(teamId.value, id)
    notification.success(t('timetable.activate_success'))
    await loadData()
  } catch {
    notification.error(t('timetable.activate_error'))
  }
}

async function handleArchive(id: number) {
  try {
    await timetableApi.archive(teamId.value, id)
    notification.success(t('timetable.archive_success'))
    await loadData()
  } catch {
    notification.error(t('timetable.archive_error'))
  }
}

async function handleRevertToDraft(id: number) {
  try {
    await timetableApi.revertToDraft(teamId.value, id)
    notification.success(t('timetable.revert_success'))
    await loadData()
  } catch {
    notification.error(t('timetable.revert_error'))
  }
}

async function handleDuplicate(id: number) {
  try {
    await timetableApi.duplicate(teamId.value, id)
    notification.success(t('timetable.duplicate_success'))
    await loadData()
  } catch {
    notification.error(t('timetable.duplicate_error'))
  }
}

async function handleExportPdf() {
  if (!selectedTimetable.value) return
  try {
    const res = await timetableApi.exportPdf(teamId.value, selectedTimetable.value.id)
    window.open(res.url, '_blank')
  } catch {
    notification.error(t('timetable.pdf_error'))
  }
}

async function openCreateDialog() {
  try {
    terms.value = await timetableApi.listTerms('team', teamId.value)
  } catch {
    // サイレント失敗
  }
  showCreateDialog.value = true
}

async function submitCreate(payload: CreatePayload) {
  createSubmitting.value = true
  try {
    await timetableApi.create(teamId.value, payload)
    notification.success(t('timetable.create_success'))
    showCreateDialog.value = false
    await loadData()
  } catch {
    notification.error(t('timetable.create_error'))
  } finally {
    createSubmitting.value = false
  }
}

async function submitChange(payload: ChangePayload) {
  if (!selectedTimetable.value) return
  changeSubmitting.value = true
  try {
    await timetableApi.createChange(selectedTimetable.value.id, payload)
    notification.success(t('timetable.change_success'))
    showChangeDialog.value = false
    await loadChanges()
    // 週間ビューも再取得
    if (selectedTimetable.value) {
      weeklyView.value = await timetableApi.getWeekly(
        teamId.value,
        selectedTimetable.value.id,
        currentWeekOf.value,
      )
    }
  } catch {
    notification.error(t('timetable.change_error'))
  } finally {
    changeSubmitting.value = false
  }
}

async function handleDeleteChange(changeId: number) {
  if (!selectedTimetable.value) return
  try {
    await timetableApi.deleteChange(selectedTimetable.value.id, changeId)
    notification.success(t('timetable.delete_change_success'))
    await loadChanges()
  } catch {
    notification.error(t('timetable.delete_change_error'))
  }
}

onMounted(loadData)

watch(activeTab, (tab) => {
  if (tab === '2') loadChanges()
})
</script>

<template>
  <div class="mx-auto max-w-6xl">
    <div class="mb-6 flex items-center justify-between">
      <PageHeader :title="$t('timetable.title')" />
      <div class="flex gap-2">
        <Button
          v-if="canManage"
          :label="$t('timetable.create_timetable')"
          icon="pi pi-plus"
          size="small"
          @click="openCreateDialog"
        />
        <Button
          v-if="selectedTimetable"
          :label="$t('timetable.export_pdf')"
          icon="pi pi-file-pdf"
          severity="secondary"
          size="small"
          @click="handleExportPdf"
        />
      </div>
    </div>

    <PageLoading v-if="loading" />

    <template v-else>
      <Tabs v-model:value="activeTab">
        <TabList>
          <Tab value="0">{{ $t('timetable.tab_weekly') }}</Tab>
          <Tab value="1">{{ $t('timetable.tab_list') }}</Tab>
          <Tab value="2" :disabled="!selectedTimetable">{{ $t('timetable.tab_changes') }}</Tab>
        </TabList>
        <TabPanels>
          <!-- 週間ビュー -->
          <TabPanel value="0">
            <TimetableWeeklyView
              :timetables="timetables"
              :selected-timetable="selectedTimetable"
              :weekly-view="weeklyView"
              :can-manage="canManage"
              :day-keys="DAY_KEYS"
              :day-labels="dayLabels"
              @select="selectTimetable"
              @navigate="navigateWeek"
              @open-change-dialog="showChangeDialog = true"
            />
          </TabPanel>

          <!-- 時間割一覧 -->
          <TabPanel value="1">
            <TimetableList
              :timetables="timetables"
              :can-manage="canManage"
              :status-label="statusLabel"
              :status-severity="statusSeverity"
              @select="selectTimetable"
              @activate="handleActivate"
              @archive="handleArchive"
              @revert-to-draft="handleRevertToDraft"
              @duplicate="handleDuplicate"
            />
          </TabPanel>

          <!-- 臨時変更一覧 -->
          <TabPanel value="2">
            <TimetableChangeList
              :selected-timetable="selectedTimetable"
              :changes="changes"
              :can-manage="canManage"
              @open-change-dialog="showChangeDialog = true"
              @delete-change="handleDeleteChange"
            />
          </TabPanel>
        </TabPanels>
      </Tabs>
    </template>

    <!-- 時間割作成ダイアログ -->
    <TimetableCreateDialog
      v-model:visible="showCreateDialog"
      :terms="terms"
      :submitting="createSubmitting"
      @submit="submitCreate"
    />

    <!-- 臨時変更登録ダイアログ -->
    <TimetableChangeDialog
      v-model:visible="showChangeDialog"
      :submitting="changeSubmitting"
      @submit="submitChange"
    />
  </div>
</template>
