<script setup lang="ts">
import type {
  Timetable,
  TimetableTerm,
  WeeklyView,
  TimetableChange,
  DayOfWeekKey,
} from '~/types/timetable'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const teamId = computed(() => Number(route.params.id))
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
const createForm = ref({
  name: '',
  termId: null as number | null,
  effectiveFrom: '',
  effectiveUntil: '',
  visibility: 'MEMBERS_ONLY' as 'MEMBERS_ONLY' | 'PUBLIC',
  weekPatternEnabled: false,
  weekPatternBaseDate: '',
  notes: '',
})
const createSubmitting = ref(false)

// 臨時変更ダイアログ
const showChangeDialog = ref(false)
const changeForm = ref({
  targetDate: '',
  periodNumber: null as number | null,
  changeType: 'REPLACE' as string,
  subjectName: '',
  teacherName: '',
  roomName: '',
  reason: '',
  notifyMembers: true,
})
const changeSubmitting = ref(false)

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

function statusLabel(s: string) {
  return (
    {
      DRAFT: t('timetable.status_draft'),
      ACTIVE: t('timetable.status_active'),
      ARCHIVED: t('timetable.status_archived'),
    }[s] ?? s
  )
}
function statusSeverity(s: string) {
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

async function submitCreate() {
  if (!createForm.value.name || !createForm.value.termId || !createForm.value.effectiveFrom) return
  createSubmitting.value = true
  try {
    await timetableApi.create(teamId.value, {
      name: createForm.value.name,
      termId: createForm.value.termId,
      effectiveFrom: createForm.value.effectiveFrom,
      effectiveUntil: createForm.value.effectiveUntil || null,
      visibility: createForm.value.visibility,
      weekPatternEnabled: createForm.value.weekPatternEnabled,
      weekPatternBaseDate: createForm.value.weekPatternBaseDate || null,
      notes: createForm.value.notes || null,
    })
    notification.success(t('timetable.create_success'))
    showCreateDialog.value = false
    createForm.value = {
      name: '',
      termId: null,
      effectiveFrom: '',
      effectiveUntil: '',
      visibility: 'MEMBERS_ONLY',
      weekPatternEnabled: false,
      weekPatternBaseDate: '',
      notes: '',
    }
    await loadData()
  } catch {
    notification.error(t('timetable.create_error'))
  } finally {
    createSubmitting.value = false
  }
}

async function submitChange() {
  if (!selectedTimetable.value || !changeForm.value.targetDate || !changeForm.value.changeType) return
  changeSubmitting.value = true
  try {
    await timetableApi.createChange(selectedTimetable.value.id, {
      targetDate: changeForm.value.targetDate,
      periodNumber: changeForm.value.periodNumber,
      changeType: changeForm.value.changeType,
      subjectName: changeForm.value.subjectName || null,
      teacherName: changeForm.value.teacherName || null,
      roomName: changeForm.value.roomName || null,
      reason: changeForm.value.reason || null,
      notifyMembers: changeForm.value.notifyMembers,
    })
    notification.success(t('timetable.change_success'))
    showChangeDialog.value = false
    changeForm.value = {
      targetDate: '',
      periodNumber: null,
      changeType: 'REPLACE',
      subjectName: '',
      teacherName: '',
      roomName: '',
      reason: '',
      notifyMembers: true,
    }
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

const changeTypeOptions = computed(() => [
  { label: t('timetable.change_type_replace'), value: 'REPLACE' },
  { label: t('timetable.change_type_cancel'), value: 'CANCEL' },
  { label: t('timetable.change_type_add'), value: 'ADD' },
  { label: t('timetable.change_type_day_off'), value: 'DAY_OFF' },
])

const visibilityOptions = computed(() => [
  { label: t('timetable.visibility_members_only'), value: 'MEMBERS_ONLY' },
  { label: t('timetable.visibility_public'), value: 'PUBLIC' },
])

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
            <!-- 時間割選択 -->
            <div v-if="timetables.length > 1" class="mb-4 flex flex-wrap gap-2">
              <Button
                v-for="tt in timetables.filter((t) => t.status !== 'ARCHIVED')"
                :key="tt.id"
                :label="tt.name"
                :severity="selectedTimetable?.id === tt.id ? 'primary' : 'secondary'"
                size="small"
                @click="selectTimetable(tt)"
              />
            </div>

            <div v-if="weeklyView">
              <!-- 週ナビゲーション -->
              <div class="mb-3 flex items-center justify-between">
                <div class="flex items-center gap-2">
                  <Button
                    icon="pi pi-chevron-left"
                    severity="secondary"
                    size="small"
                    :aria-label="$t('timetable.prev_week')"
                    @click="navigateWeek('prev')"
                  />
                  <span class="text-sm font-medium">
                    {{ weeklyView.weekStart }} 〜 {{ weeklyView.weekEnd }}
                  </span>
                  <Button
                    icon="pi pi-chevron-right"
                    severity="secondary"
                    size="small"
                    :aria-label="$t('timetable.next_week')"
                    @click="navigateWeek('next')"
                  />
                </div>
                <div class="flex items-center gap-2">
                  <Badge
                    v-if="weeklyView.weekPatternEnabled"
                    :value="
                      weeklyView.currentWeekPattern === 'A'
                        ? $t('timetable.week_pattern_a')
                        : $t('timetable.week_pattern_b')
                    "
                    severity="info"
                  />
                  <Button
                    :label="$t('timetable.current_week')"
                    severity="secondary"
                    size="small"
                    @click="navigateWeek('current')"
                  />
                </div>
              </div>

              <!-- 時間割グリッド -->
              <div class="overflow-x-auto">
                <table class="w-full border-collapse text-sm">
                  <thead>
                    <tr>
                      <th
                        class="border border-surface-300 bg-surface-50 p-2 dark:border-surface-600 dark:bg-surface-800"
                      >
                        {{ $t('timetable.period_number') }}
                      </th>
                      <th
                        v-for="key in DAY_KEYS"
                        :key="key"
                        class="border border-surface-300 bg-surface-50 p-2 text-center dark:border-surface-600 dark:bg-surface-800"
                        :class="{ 'bg-red-50 dark:bg-red-900/20': weeklyView.days[key]?.isDayOff }"
                      >
                        <div>{{ dayLabels[key] }}</div>
                        <div v-if="weeklyView.days[key]" class="text-xs text-surface-400">
                          {{ weeklyView.days[key].date?.slice(5) }}
                        </div>
                        <div
                          v-if="weeklyView.days[key]?.isDayOff"
                          class="text-xs font-medium text-red-500"
                        >
                          {{ $t('timetable.day_off') }}
                        </div>
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    <!-- スロット行 - periodごと -->
                    <template v-if="weeklyView.periods.length > 0">
                      <tr v-for="period in weeklyView.periods" :key="period.periodNumber">
                        <td
                          class="border border-surface-300 bg-surface-50 p-2 text-center dark:border-surface-600 dark:bg-surface-800"
                        >
                          <div class="font-medium">{{ period.label }}</div>
                          <div class="text-xs text-surface-400">
                            {{ period.startTime }}-{{ period.endTime }}
                          </div>
                        </td>
                        <td
                          v-for="key in DAY_KEYS"
                          :key="key"
                          class="border border-surface-300 p-1 align-top dark:border-surface-600"
                          :class="{ 'bg-surface-100 dark:bg-surface-800': weeklyView.days[key]?.isDayOff }"
                        >
                          <template v-if="!weeklyView.days[key]?.isDayOff">
                            <template
                              v-for="slot in (weeklyView.days[key]?.slots ?? []).filter(
                                (s) => s.periodNumber === period.periodNumber,
                              )"
                              :key="slot.periodNumber"
                            >
                              <div
                                class="rounded p-1"
                                :style="slot.color ? { backgroundColor: slot.color + '20' } : {}"
                              >
                                <p
                                  class="font-medium"
                                  :class="
                                    slot.changeType === 'CANCEL'
                                      ? 'line-through text-surface-400'
                                      : ''
                                  "
                                >
                                  {{
                                    slot.changeType === 'CANCEL'
                                      ? $t('timetable.cancelled')
                                      : slot.subjectName
                                  }}
                                </p>
                                <p v-if="slot.isChanged" class="text-xs text-orange-500">
                                  <i class="pi pi-exclamation-circle" />
                                  {{
                                    slot.changeType === 'ADD'
                                      ? $t('timetable.added')
                                      : $t('timetable.changed')
                                  }}
                                </p>
                                <p v-if="slot.teacherName" class="text-xs text-surface-500">
                                  {{ slot.teacherName }}
                                </p>
                                <p v-if="slot.roomName" class="text-xs text-surface-400">
                                  {{ slot.roomName }}
                                </p>
                              </div>
                            </template>
                          </template>
                        </td>
                      </tr>
                    </template>
                    <!-- 時限定義なし（全スロットをまとめて表示） -->
                    <template v-else>
                      <tr>
                        <td
                          class="border border-surface-300 bg-surface-50 p-2 text-center text-sm text-surface-400 dark:border-surface-600 dark:bg-surface-800"
                        >
                          —
                        </td>
                        <td
                          v-for="key in DAY_KEYS"
                          :key="key"
                          class="border border-surface-300 p-1 align-top dark:border-surface-600"
                          :class="{ 'bg-surface-100 dark:bg-surface-800': weeklyView.days[key]?.isDayOff }"
                        >
                          <div v-if="weeklyView.days[key]?.isDayOff" class="text-xs text-red-500">
                            {{ weeklyView.days[key].dayOffReason ?? $t('timetable.day_off') }}
                          </div>
                          <div
                            v-for="slot in weeklyView.days[key]?.slots ?? []"
                            :key="slot.periodNumber"
                            class="mb-1 rounded p-1"
                            :style="slot.color ? { backgroundColor: slot.color + '20' } : {}"
                          >
                            <p class="text-xs font-semibold text-surface-400">
                              {{ slot.periodNumber }}{{ $t('timetable.period_suffix') }}
                            </p>
                            <p
                              class="font-medium"
                              :class="
                                slot.changeType === 'CANCEL' ? 'line-through text-surface-400' : ''
                              "
                            >
                              {{ slot.subjectName }}
                            </p>
                            <p v-if="slot.teacherName" class="text-xs text-surface-500">
                              {{ slot.teacherName }}
                            </p>
                          </div>
                        </td>
                      </tr>
                    </template>
                  </tbody>
                </table>
              </div>

              <!-- 管理者向けアクション -->
              <div v-if="canManage && selectedTimetable?.status === 'ACTIVE'" class="mt-4">
                <Button
                  :label="$t('timetable.add_change')"
                  icon="pi pi-plus"
                  severity="warn"
                  size="small"
                  @click="showChangeDialog = true"
                />
              </div>
            </div>
            <DashboardEmptyState
              v-else
              icon="pi pi-table"
              :message="$t('timetable.no_timetable')"
            />
          </TabPanel>

          <!-- 時間割一覧 -->
          <TabPanel value="1">
            <DataTable
              :value="timetables"
              data-key="id"
              striped-rows
              @row-click="(e: { data: Timetable }) => selectTimetable(e.data)"
            >
              <template #empty>
                <div class="py-8 text-center text-surface-500">
                  {{ $t('timetable.no_timetable') }}
                </div>
              </template>
              <Column field="name" :header="$t('timetable.timetable_name')" />
              <Column field="termName" :header="$t('timetable.term')" />
              <Column field="effectiveFrom" :header="$t('timetable.effective_from')" />
              <Column :header="$t('common.label.status')">
                <template #body="{ data }">
                  <Badge
                    :value="statusLabel(data.status)"
                    :severity="statusSeverity(data.status)"
                  />
                </template>
              </Column>
              <Column v-if="canManage" :header="$t('common.label.actions')" style="width: 200px">
                <template #body="{ data }">
                  <div class="flex gap-1">
                    <Button
                      v-if="data.status === 'DRAFT'"
                      v-tooltip="$t('timetable.activate')"
                      icon="pi pi-check"
                      size="small"
                      text
                      severity="success"
                      @click.stop="handleActivate(data.id)"
                    />
                    <Button
                      v-if="data.status === 'ACTIVE'"
                      v-tooltip="$t('timetable.archive')"
                      icon="pi pi-inbox"
                      size="small"
                      text
                      severity="secondary"
                      @click.stop="handleArchive(data.id)"
                    />
                    <Button
                      v-if="data.status === 'ARCHIVED'"
                      v-tooltip="$t('timetable.revert_draft')"
                      icon="pi pi-undo"
                      size="small"
                      text
                      severity="warn"
                      @click.stop="handleRevertToDraft(data.id)"
                    />
                    <Button
                      v-tooltip="$t('timetable.duplicate')"
                      icon="pi pi-copy"
                      size="small"
                      text
                      severity="info"
                      @click.stop="handleDuplicate(data.id)"
                    />
                  </div>
                </template>
              </Column>
            </DataTable>
          </TabPanel>

          <!-- 臨時変更一覧 -->
          <TabPanel value="2">
            <div class="mb-4 flex items-center justify-between">
              <p class="text-sm text-surface-500">
                {{ selectedTimetable?.name }}
              </p>
              <Button
                v-if="canManage && selectedTimetable?.status === 'ACTIVE'"
                :label="$t('timetable.add_change')"
                icon="pi pi-plus"
                size="small"
                @click="showChangeDialog = true"
              />
            </div>
            <DataTable :value="changes" data-key="id" striped-rows>
              <template #empty>
                <div class="py-8 text-center text-surface-500">{{ $t('timetable.no_change') }}</div>
              </template>
              <Column field="targetDate" :header="$t('timetable.change_date')" />
              <Column :header="$t('timetable.period_number')">
                <template #body="{ data }">
                  {{ data.periodNumber != null ? data.periodNumber + $t('timetable.period_suffix') : $t('timetable.period_all') }}
                </template>
              </Column>
              <Column :header="$t('timetable.change_type')">
                <template #body="{ data }">
                  <Badge
                    :value="
                      ({
                        REPLACE: $t('timetable.change_type_replace'),
                        CANCEL: $t('timetable.change_type_cancel'),
                        ADD: $t('timetable.change_type_add'),
                        DAY_OFF: $t('timetable.change_type_day_off'),
                      } as Record<string, string>)[data.changeType] ?? data.changeType
                    "
                    :severity="
                      ({
                        REPLACE: 'warn',
                        CANCEL: 'secondary',
                        ADD: 'success',
                        DAY_OFF: 'danger',
                      } as Record<string, string>)[data.changeType] ?? 'info'
                    "
                  />
                </template>
              </Column>
              <Column field="subjectName" :header="$t('timetable.change_subject')" />
              <Column field="reason" :header="$t('timetable.change_reason')" />
              <Column v-if="canManage" :header="$t('common.label.actions')" style="width: 80px">
                <template #body="{ data }">
                  <Button
                    icon="pi pi-trash"
                    size="small"
                    text
                    severity="danger"
                    @click="handleDeleteChange(data.id)"
                  />
                </template>
              </Column>
            </DataTable>
          </TabPanel>
        </TabPanels>
      </Tabs>
    </template>

    <!-- 時間割作成ダイアログ -->
    <Dialog
      v-model:visible="showCreateDialog"
      :header="$t('timetable.create_timetable')"
      :modal="true"
      class="w-full max-w-lg"
    >
      <div class="space-y-4">
        <div>
          <label class="mb-1 block text-sm font-medium">{{ $t('timetable.timetable_name') }}</label>
          <InputText
            v-model="createForm.name"
            class="w-full"
            :placeholder="$t('timetable.timetable_name')"
          />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ $t('timetable.term') }}</label>
          <Select
            v-model="createForm.termId"
            :options="terms"
            option-label="name"
            option-value="id"
            class="w-full"
            :placeholder="$t('timetable.term')"
          />
        </div>
        <div class="grid grid-cols-2 gap-3">
          <div>
            <label class="mb-1 block text-sm font-medium">{{
              $t('timetable.effective_from')
            }}</label>
            <InputText v-model="createForm.effectiveFrom" type="date" class="w-full" />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">{{
              $t('timetable.effective_until')
            }}</label>
            <InputText v-model="createForm.effectiveUntil" type="date" class="w-full" />
          </div>
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ $t('timetable.visibility_label') }}</label>
          <Select
            v-model="createForm.visibility"
            :options="visibilityOptions"
            option-label="label"
            option-value="value"
            class="w-full"
          />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ $t('timetable.notes') }}</label>
          <Textarea v-model="createForm.notes" class="w-full" rows="2" />
        </div>
      </div>
      <template #footer>
        <Button
          :label="$t('common.button.cancel')"
          severity="secondary"
          @click="showCreateDialog = false"
        />
        <Button
          :label="$t('common.button.create')"
          icon="pi pi-check"
          :loading="createSubmitting"
          :disabled="!createForm.name || !createForm.termId || !createForm.effectiveFrom"
          @click="submitCreate"
        />
      </template>
    </Dialog>

    <!-- 臨時変更登録ダイアログ -->
    <Dialog
      v-model:visible="showChangeDialog"
      :header="$t('timetable.add_change')"
      :modal="true"
      class="w-full max-w-lg"
    >
      <div class="space-y-4">
        <div class="grid grid-cols-2 gap-3">
          <div>
            <label class="mb-1 block text-sm font-medium">{{ $t('timetable.change_date') }}</label>
            <InputText v-model="changeForm.targetDate" type="date" class="w-full" />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">{{
              $t('timetable.period_number')
            }}</label>
            <InputNumber
              v-model="changeForm.periodNumber"
              class="w-full"
              :min="1"
              :max="15"
              :disabled="changeForm.changeType === 'DAY_OFF'"
              :placeholder="changeForm.changeType === 'DAY_OFF' ? $t('timetable.period_all') : $t('timetable.period_number_placeholder')"
            />
          </div>
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ $t('timetable.change_type') }}</label>
          <Select
            v-model="changeForm.changeType"
            :options="changeTypeOptions"
            option-label="label"
            option-value="value"
            class="w-full"
          />
        </div>
        <div
          v-if="changeForm.changeType === 'REPLACE' || changeForm.changeType === 'ADD'"
          class="space-y-3"
        >
          <div>
            <label class="mb-1 block text-sm font-medium">{{
              $t('timetable.change_subject')
            }}</label>
            <InputText
              v-model="changeForm.subjectName"
              class="w-full"
              :placeholder="$t('timetable.change_subject')"
            />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">{{
              $t('timetable.change_teacher')
            }}</label>
            <InputText v-model="changeForm.teacherName" class="w-full" />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">{{ $t('timetable.change_room') }}</label>
            <InputText v-model="changeForm.roomName" class="w-full" />
          </div>
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ $t('timetable.change_reason') }}</label>
          <InputText v-model="changeForm.reason" class="w-full" />
        </div>
        <div class="flex items-center gap-2">
          <Checkbox v-model="changeForm.notifyMembers" binary input-id="notify" />
          <label for="notify" class="text-sm">{{ $t('timetable.notify_members') }}</label>
        </div>
      </div>
      <template #footer>
        <Button
          :label="$t('common.button.cancel')"
          severity="secondary"
          @click="showChangeDialog = false"
        />
        <Button
          :label="$t('common.button.save')"
          icon="pi pi-check"
          :loading="changeSubmitting"
          :disabled="!changeForm.targetDate || !changeForm.changeType"
          @click="submitChange"
        />
      </template>
    </Dialog>
  </div>
</template>
