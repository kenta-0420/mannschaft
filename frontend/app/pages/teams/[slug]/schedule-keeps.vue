<script setup lang="ts">
/**
 * F03.17 キープ（日付未定の予定）— チームスコープ一覧ページ。
 *
 * 設計書: docs/features/F03.17_schedule_keep.md（Wave 3・FE）
 * - 作成はタイトル1項目のみ必須（§1.3 ADHD 中核・AC-01）。
 * - 候補日バッジは1タップ、候補日なしは2タップ以内で変換（§4.5.3・AC-08/AC-08b）。
 * - 一覧は非同期の順序付きリストのため `ready` computed でゲートし、確定するまで
 *   skeleton を出す（並び替えのチラつき防止）。
 * - 状態別に編集 UI を事前に無効化する（ScheduleKeepCard 側・§4.4）。
 */
import draggable from 'vuedraggable'
import { useScheduleKeepApi, type ScheduleKeepResponse } from '~/composables/schedule/useScheduleKeep'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const teamSlug = String(route.params.slug)
const { t } = useI18n()
const api = useScheduleKeepApi()
const { isMember, isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamSlug)
const authStore = useAuthStore()
const { handleApiError } = useErrorHandler()

const keeps = ref<ScheduleKeepResponse[]>([])
const loading = ref(true)
const statusFilter = ref<'KEPT' | 'SCHEDULED' | 'ARCHIVED' | 'ALL'>('KEPT')
const reorderMode = ref(false)
const showGuide = ref(false)

// 非同期の順序付きリスト: 権限取得＋一覧取得の両方が終わるまで ready にしない
// （並び替え結果が確定する前にちらつくレンダリングを防ぐ）。
const permissionsLoaded = ref(false)
const ready = computed(() => permissionsLoaded.value && !loading.value)

async function fetchKeeps() {
  loading.value = true
  try {
    const res = await api.listScheduleKeeps('team', teamSlug, statusFilter.value)
    keeps.value = res.data ?? []
  }
  catch (error) {
    handleApiError(error, 'schedule-keeps:list')
  }
  finally {
    loading.value = false
  }
}

function canEdit(keep: ScheduleKeepResponse): boolean {
  return isAdminOrDeputy.value || keep.createdBy?.userId === authStore.user?.id
}

// === 作成（タイトル1項目のみ必須・AC-01） ===
const newTitle = ref('')
const newMemo = ref('')
const newCandidateDates = ref<Date[]>([])
const showCreateDetails = ref(false)
const creating = ref(false)

async function onCreate() {
  const title = newTitle.value.trim()
  if (!title) return
  creating.value = true
  try {
    await api.createScheduleKeep('team', teamSlug, {
      title,
      memo: newMemo.value.trim() || undefined,
      candidateDates: newCandidateDates.value.map(toLocalDateString),
    })
    newTitle.value = ''
    newMemo.value = ''
    newCandidateDates.value = []
    showCreateDetails.value = false
    if (statusFilter.value === 'KEPT' || statusFilter.value === 'ALL') {
      await fetchKeeps()
    }
  }
  catch (error) {
    handleApiError(error, 'schedule-keeps:create')
  }
  finally {
    creating.value = false
  }
}

// === 変換（候補日バッジ = 1タップ / 候補日なし = ダイアログ経由で2タップ以内） ===
const convertDialogVisible = ref(false)
const convertTargetId = ref<string | null>(null)

function openConvertDialog(keepId: string) {
  convertTargetId.value = keepId
  convertDialogVisible.value = true
}

async function onConvertFromDialog(payload: { startAt: string; allDay: boolean }) {
  if (!convertTargetId.value) return
  await doConvert(convertTargetId.value, payload.startAt, payload.allDay)
  convertTargetId.value = null
}

async function onConvertCandidate(keepId: string, date: string) {
  await doConvert(keepId, `${date}T00:00:00`, true)
}

async function doConvert(keepId: string, startAt: string, allDay: boolean) {
  try {
    await api.convertScheduleKeep('team', teamSlug, keepId, { startAt, allDay })
    await fetchKeeps()
  }
  catch (error) {
    handleApiError(error, 'schedule-keeps:convert')
  }
}

// === 状態遷移操作 ===
async function onArchive(keepId: string) {
  try {
    await api.archiveScheduleKeep('team', teamSlug, keepId)
    await fetchKeeps()
  }
  catch (error) {
    handleApiError(error, 'schedule-keeps:archive')
  }
}

async function onRestore(keepId: string) {
  try {
    await api.restoreScheduleKeep('team', teamSlug, keepId)
    await fetchKeeps()
  }
  catch (error) {
    handleApiError(error, 'schedule-keeps:restore')
  }
}

async function onRevert(keepId: string) {
  try {
    await api.revertScheduleKeep('team', teamSlug, keepId)
    await fetchKeeps()
  }
  catch (error) {
    handleApiError(error, 'schedule-keeps:revert')
  }
}

async function onDelete(keepId: string) {
  try {
    await api.deleteScheduleKeep('team', teamSlug, keepId)
    await fetchKeeps()
  }
  catch (error) {
    handleApiError(error, 'schedule-keeps:delete')
  }
}

// === 編集（title/memo/candidateDates の部分更新） ===
const editDialogVisible = ref(false)
const editTargetId = ref<string | null>(null)
const editTitle = ref('')
const editMemo = ref('')

function openEditDialog(keep: ScheduleKeepResponse) {
  editTargetId.value = keep.id ?? null
  editTitle.value = keep.title ?? ''
  editMemo.value = keep.memo ?? ''
  editDialogVisible.value = true
}

async function onSaveEdit() {
  if (!editTargetId.value) return
  try {
    await api.updateScheduleKeep('team', teamSlug, editTargetId.value, {
      title: editTitle.value.trim(),
      memo: editMemo.value.trim() || undefined,
    })
    editDialogVisible.value = false
    await fetchKeeps()
  }
  catch (error) {
    handleApiError(error, 'schedule-keeps:update')
  }
}

// === 並び替え ===
async function onDragEnd() {
  try {
    await api.reorderScheduleKeeps('team', teamSlug, keeps.value.map(k => k.id!).filter(Boolean))
  }
  catch (error) {
    handleApiError(error, 'schedule-keeps:reorder')
    await fetchKeeps()
  }
}

watch(statusFilter, () => {
  fetchKeeps()
})

onMounted(async () => {
  const result = await loadPermissions()
  permissionsLoaded.value = result.ok
  await fetchKeeps()
})
</script>

<template>
  <div class="mx-auto max-w-3xl p-6">
    <PageHeader :title="t('scheduleKeep.title')" size="sm" help @help="showGuide = true" />
    <p class="mb-4 text-sm text-surface-600 dark:text-surface-300">
      {{ t('scheduleKeep.subtitle') }}
    </p>

    <!-- 作成: タイトル1項目のみ必須（AC-01） -->
    <SectionCard v-if="isMember" class="mb-4">
      <form class="flex flex-col gap-2" data-testid="schedule-keep-create-form" @submit.prevent="onCreate">
        <div class="flex gap-2">
          <InputText
            v-model="newTitle"
            :placeholder="t('scheduleKeep.form.titlePlaceholder')"
            class="flex-1"
            data-testid="schedule-keep-title-input"
          />
          <Button
            type="submit"
            :label="t('scheduleKeep.form.submit')"
            :loading="creating"
            :disabled="!newTitle.trim()"
            data-testid="schedule-keep-submit-button"
          />
        </div>
        <button
          type="button"
          class="self-start text-xs text-primary-600 hover:underline dark:text-primary-400"
          data-testid="schedule-keep-details-toggle"
          @click="showCreateDetails = !showCreateDetails"
        >
          {{ showCreateDetails ? t('button.close') : t('scheduleKeep.form.memoLabel') }}
        </button>
        <div v-if="showCreateDetails" class="flex flex-col gap-2">
          <Textarea
            v-model="newMemo"
            :placeholder="t('scheduleKeep.form.memoLabel')"
            rows="2"
            auto-resize
            data-testid="schedule-keep-memo-input"
          />
          <div>
            <label class="mb-1 block text-xs text-surface-500">{{ t('scheduleKeep.form.candidateDatesLabel') }}</label>
            <DatePicker
              v-model="newCandidateDates"
              selection-mode="multiple"
              show-icon
              class="w-full"
              date-format="yy/mm/dd"
              data-testid="schedule-keep-candidate-dates-input"
            />
          </div>
        </div>
      </form>
    </SectionCard>

    <!-- フィルタ & 並び替えトグル -->
    <div class="mb-3 flex flex-wrap items-center justify-between gap-2">
      <SelectButton
        v-model="statusFilter"
        :options="['KEPT', 'SCHEDULED', 'ARCHIVED', 'ALL']"
        data-testid="schedule-keep-status-filter"
      >
        <template #option="{ option }">
          {{ t(`scheduleKeep.filter.${option.toLowerCase()}`) }}
        </template>
      </SelectButton>
      <Button
        v-if="statusFilter === 'KEPT'"
        :label="t('scheduleKeep.action.reorder')"
        size="small"
        :outlined="!reorderMode"
        icon="pi pi-sort-alt"
        data-testid="schedule-keep-reorder-toggle"
        @click="reorderMode = !reorderMode"
      />
    </div>

    <!-- 一覧: ready になるまで skeleton（並び替え確定前のちらつき防止） -->
    <div v-if="!ready" class="flex flex-col gap-3" data-testid="schedule-keep-skeleton">
      <Skeleton height="5rem" />
      <Skeleton height="5rem" />
      <Skeleton height="5rem" />
    </div>
    <p v-else-if="keeps.length === 0" class="text-sm text-surface-500" data-testid="schedule-keep-empty">
      {{ t('scheduleKeep.empty') }}
    </p>
    <draggable
      v-else
      v-model="keeps"
      item-key="id"
      handle=".drag-handle"
      :disabled="!reorderMode"
      :animation="150"
      ghost-class="opacity-30"
      class="flex flex-col gap-3"
      data-testid="schedule-keep-list"
      @end="onDragEnd"
    >
      <template #item="{ element }">
        <ScheduleKeepCard
          :keep="element"
          :can-edit="canEdit(element)"
          :reorder-mode="reorderMode"
          @convert-candidate="(date) => onConvertCandidate(element.id, date)"
          @convert-no-date="openConvertDialog(element.id)"
          @archive="onArchive(element.id)"
          @restore="onRestore(element.id)"
          @revert="onRevert(element.id)"
          @remove="onDelete(element.id)"
          @edit="openEditDialog(element)"
        />
      </template>
    </draggable>

    <ScheduleKeepConvertDialog
      v-model:visible="convertDialogVisible"
      @select="onConvertFromDialog"
    />

    <Dialog
      v-model:visible="editDialogVisible"
      modal
      :header="t('button.edit')"
      class="w-full max-w-md"
      data-testid="schedule-keep-edit-dialog"
    >
      <div class="flex flex-col gap-3">
        <InputText v-model="editTitle" :placeholder="t('scheduleKeep.form.titleLabel')" />
        <Textarea v-model="editMemo" :placeholder="t('scheduleKeep.form.memoLabel')" rows="3" auto-resize />
      </div>
      <template #footer>
        <Button :label="t('button.cancel')" text @click="editDialogVisible = false" />
        <Button :label="t('button.save')" @click="onSaveEdit" />
      </template>
    </Dialog>

    <ScheduleKeepGuideModal v-model:visible="showGuide" />
  </div>
</template>
