<script setup lang="ts">
import type {
  ShiftScheduleResponse,
  ShiftScheduleStatus,
  ShiftRequestSummaryResponse,
  CreateShiftScheduleRequest,
} from '~/types/shift'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const teamStore = useTeamStore()
const { listSchedules, createSchedule } = useShiftApi()
const { getRequestSummary } = useShiftRequestApi()
const { handleApiError } = useErrorHandler()
const { success } = useNotification()

const showGuide = ref(false)

// =====================================================
// チーム選択
// =====================================================
const selectedTeamId = ref<number | null>(null)

/** シフトを参照できない所属ロール（BE が 403 にする側）。 */
const SHIFT_UNREADABLE_ROLES = ['SUPPORTER', 'GUEST']

/**
 * チーム選択セレクトの選択肢＝「自分が所属し、かつシフトを参照できるチーム」。
 *
 * 管理ロールでは絞らない。一般メンバーも COLLECTING / ADJUSTING のシフト表を閲覧できる
 * 仕様（CMP-260826-2127）であり、ここを ADMIN 系に絞ると MEMBER として所属するチームを
 * 選べず、シフト一覧そのものに到達できなくなるため。作成・編集ボタンの出し分けは
 * 選択肢ではなく canManage が担う。
 *
 * 一方で myTeams をそのまま出すこともできない。`GET /me/teams` は user_roles と memberships の
 * <b>和集合</b>であり「一覧に出る＝その機能を使える」ではないためである。シフト参照の認可は
 * BE の ShiftScheduleService#checkTeamReadAccess が持ち、
 *   - membership が無い（純 GUEST は user_roles 由来で membership を持たない）→ isMember false で 403
 *   - membership の role_kind が SUPPORTER → isSupporter true で 403
 * となる。よって SUPPORTER / GUEST を除いた集合が BE の許可境界と一致する
 * （memberships.role_kind は MEMBER / SUPPORTER の2値。GUEST は user_roles 側の権限ロール）。
 *
 * 絞らないと「選択肢には出るのに、選ぶと一覧取得が 403 で失敗する」組み合わせが生じる。
 * 同じ集合のズレは CalendarLayerService の Javadoc にも
 * 「GET /me/teams の一覧には出るのに色を設定すると 403」という前例として記録されている。
 */
const selectableTeams = computed(() =>
  teamStore.myTeams.filter((t) => !SHIFT_UNREADABLE_ROLES.includes(t.role)),
)

/** ADMIN / SYSTEM_ADMIN / DEPUTY_ADMIN のチーム（シフト表の作成・編集権限あり。canManage 専用） */
const manageableTeams = computed(() =>
  teamStore.myTeams.filter(
    (t) => t.role === 'ADMIN' || t.role === 'SYSTEM_ADMIN' || t.role === 'DEPUTY_ADMIN',
  ),
)

const canManage = computed(() => {
  if (!selectedTeamId.value) return false
  return manageableTeams.value.some((t) => t.id === selectedTeamId.value)
})

// =====================================================
// フィルタ
// =====================================================
const statusFilter = ref<ShiftScheduleStatus | ''>('')
const fromDate = ref('')
const toDate = ref('')

const statusOptions = computed(() => [
  { label: t('shift.status.all'), value: '' },
  { label: t('shift.status.draft'), value: 'DRAFT' as ShiftScheduleStatus },
  { label: t('shift.status.collecting'), value: 'COLLECTING' as ShiftScheduleStatus },
  { label: t('shift.status.adjusting'), value: 'ADJUSTING' as ShiftScheduleStatus },
  { label: t('shift.status.published'), value: 'PUBLISHED' as ShiftScheduleStatus },
  { label: t('shift.status.archived'), value: 'ARCHIVED' as ShiftScheduleStatus },
])

// =====================================================
// データ
// =====================================================
const schedules = ref<ShiftScheduleResponse[]>([])
const summaryMap = ref<Map<number, ShiftRequestSummaryResponse>>(new Map())
const loading = ref(false)

const filteredSchedules = computed(() => {
  if (!statusFilter.value) return schedules.value
  return schedules.value.filter((s) => s.status === statusFilter.value)
})

async function load() {
  if (!selectedTeamId.value) return
  loading.value = true
  try {
    schedules.value = await listSchedules(
      String(selectedTeamId.value),
      fromDate.value || undefined,
      toDate.value || undefined,
    )
    // サマリーを並列取得
    const summaryEntries = await Promise.allSettled(
      schedules.value.map(async (s) => {
        const summary = await getRequestSummary(s.id)
        return { id: s.id, summary }
      }),
    )
    summaryMap.value = new Map(
      summaryEntries
        .filter((r): r is PromiseFulfilledResult<{ id: number; summary: ShiftRequestSummaryResponse }> => r.status === 'fulfilled')
        .map((r) => [r.value.id, r.value.summary]),
    )
  } catch (error) {
    handleApiError(error)
  } finally {
    loading.value = false
  }
}

watch(selectedTeamId, () => load())

onMounted(async () => {
  await teamStore.fetchMyTeams()
  if (selectableTeams.value.length > 0 && selectableTeams.value[0]) {
    selectedTeamId.value = selectableTeams.value[0].id
  }
})

// =====================================================
// 新規作成ダイアログ
// =====================================================
const showCreateDialog = ref(false)
const creating = ref(false)

interface CreateForm {
  title: string
  startDate: string
  endDate: string
  requestDeadline: string
  note: string
}

const createForm = ref<CreateForm>({
  title: '',
  startDate: '',
  endDate: '',
  requestDeadline: '',
  note: '',
})

async function handleCreate() {
  if (!selectedTeamId.value || !createForm.value.title || !createForm.value.startDate || !createForm.value.endDate) {
    return
  }
  creating.value = true
  try {
    const payload: CreateShiftScheduleRequest = {
      title: createForm.value.title.trim(),
      startDate: createForm.value.startDate,
      endDate: createForm.value.endDate,
      requestDeadline: createForm.value.requestDeadline || undefined,
      note: createForm.value.note.trim() || undefined,
    }
    await createSchedule(String(selectedTeamId.value), payload)
    success(t('shift.index.createSuccess'))
    showCreateDialog.value = false
    createForm.value = { title: '', startDate: '', endDate: '', requestDeadline: '', note: '' }
    await load()
  } catch (error) {
    handleApiError(error)
  } finally {
    creating.value = false
  }
}

function openDetail(scheduleId: number) {
  navigateTo(`/shift/${scheduleId}`)
}
</script>

<template>
  <div class="mx-auto max-w-6xl px-4 py-8">
    <!-- ヘッダー -->
    <PageHeader :title="t('shift.index.title')" help @help="showGuide = true">
      <template #actions>
        <Button
          v-if="canManage"
          icon="pi pi-plus"
          :label="t('shift.index.createNew')"
          @click="showCreateDialog = true"
        />
      </template>
    </PageHeader>

    <!-- フィルタバー -->
    <div class="mb-6 flex flex-wrap items-center gap-3">
      <!-- チーム選択 -->
      <Select
        v-model="selectedTeamId"
        :options="selectableTeams"
        option-label="name"
        option-value="id"
        :placeholder="t('shift.index.selectTeam')"
        class="min-w-[180px] field-bordered"
      />

      <!-- ステータスフィルタ -->
      <Select
        v-model="statusFilter"
        :options="statusOptions"
        option-label="label"
        option-value="value"
        class="min-w-[140px] field-bordered"
      />

      <!-- 期間フィルタ -->
      <InputText
        v-model="fromDate"
        type="date"
        :placeholder="t('shift.index.fromDate')"
        class="w-36 field-bordered"
      />
      <span class="text-surface-400">〜</span>
      <InputText
        v-model="toDate"
        type="date"
        :placeholder="t('shift.index.toDate')"
        class="w-36 field-bordered"
      />
      <Button
        icon="pi pi-search"
        :label="t('common.search')"
        severity="secondary"
        class="field-bordered"
        @click="load"
      />
    </div>

    <!-- ローディング -->
    <PageLoading v-if="loading" />

    <!-- 空状態 -->
    <DashboardEmptyState
      v-else-if="filteredSchedules.length === 0"
      icon="pi pi-table"
      :message="t('shift.index.noSchedule')"
    />

    <!-- スケジュール一覧 -->
    <div v-else class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
      <ShiftScheduleCard
        v-for="schedule in filteredSchedules"
        :key="schedule.id"
        :schedule="schedule"
        :summary="summaryMap.get(schedule.id) ?? null"
        @click="openDetail"
      />
    </div>

    <!-- 新規作成ダイアログ -->
    <Dialog
      v-model:visible="showCreateDialog"
      :header="t('shift.index.createDialog')"
      :style="{ width: '480px' }"
      modal
    >
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('shift.index.formTitle') }}</label>
          <InputText v-model="createForm.title" class="w-full" :placeholder="t('shift.index.formTitlePlaceholder')" />
        </div>
        <div class="grid grid-cols-2 gap-3">
          <div>
            <label class="mb-1 block text-sm font-medium">{{ t('shift.index.formStart') }}</label>
            <InputText v-model="createForm.startDate" type="date" class="w-full" />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">{{ t('shift.index.formEnd') }}</label>
            <InputText v-model="createForm.endDate" type="date" class="w-full" />
          </div>
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('shift.index.formDeadline') }}</label>
          <InputText v-model="createForm.requestDeadline" type="date" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('shift.index.formNote') }}</label>
          <InputText v-model="createForm.note" class="w-full" :placeholder="t('shift.index.formNotePlaceholder')" />
        </div>
      </div>
      <template #footer>
        <Button :label="t('common.cancel')" text @click="showCreateDialog = false" />
        <Button
          :label="t('common.create')"
          icon="pi pi-check"
          :loading="creating"
          :disabled="!createForm.title || !createForm.startDate || !createForm.endDate"
          @click="handleCreate"
        />
      </template>
    </Dialog>

    <ShiftGuideModal v-model:visible="showGuide" />
  </div>
</template>
