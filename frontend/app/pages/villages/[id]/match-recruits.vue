<script setup lang="ts">
/**
 * F17.1 村機能 — 村詳細 / 練習試合・募集タブ
 *
 * 設計書: docs/features/F17.1_village_community.md §2.2 / §13.2
 *
 * 構成:
 *   - 上段: <VillageHeader activeTab="matchRecruit" />
 *   - 下段:
 *       - カテゴリフィルタ (PRACTICE_MATCH / REFEREE / VENUE / OTHER / ALL)
 *       - ステータスフィルタ (OPEN / CLOSED / FULFILLED / CANCELLED / ALL)
 *       - 募集カード一覧
 *       - 村人なら誰でも「募集を作成」ボタン
 *       - カード -> 詳細 Dialog（応募ボタン + 応募一覧（投稿者/HEADMAN/ELDER のみ））
 *
 * Phase 2: 募集作成 / 応募 Dialog に VillagePostingIdentitySelector を組み込む。
 */
import type {
  MembershipResponse,
  VillageMatchApplicationCreateRequest,
  VillageMatchApplicationResponse,
  VillageMatchRecruitCategory,
  VillageMatchRecruitCreateRequest,
  VillageMatchRecruitResponse,
  VillageMatchRecruitStatus,
  VillageResponse,
} from '~/types/village'
import type { PostingIdentitySelection } from '~/components/VillagePostingIdentitySelector.vue'

definePageMeta({
  middleware: 'auth',
  layout: 'default',
})

const route = useRoute()
const villageId = String(route.params.id)
const { t } = useI18n()
const villageApi = useVillageApi()
const authStore = useAuthStore()
const { handleApiError } = useErrorHandler()
const toast = useToast()

// =====================================================================
// State — 村本体
// =====================================================================

const village = ref<VillageResponse | null>(null)
const loading = ref(true)
const notFound = ref(false)
const myMembership = ref<MembershipResponse | null>(null)

// =====================================================================
// State — 募集一覧
// =====================================================================

type CategoryFilter = VillageMatchRecruitCategory | 'ALL'
type StatusFilter = VillageMatchRecruitStatus | 'ALL'

const categoryFilter = ref<CategoryFilter>('ALL')
const statusFilter = ref<StatusFilter>('OPEN')
const recruits = ref<VillageMatchRecruitResponse[]>([])
const recruitsLoading = ref(false)

const isVillager = computed(() => !!village.value?.isMember)
const canManage = computed(
  () => village.value?.myRole === 'HEADMAN' || village.value?.myRole === 'ELDER',
)
const currentUserId = computed<number | null>(() => authStore.currentUser?.id ?? null)

const categoryOptions: { value: CategoryFilter; i18nKey: string }[] = [
  { value: 'ALL', i18nKey: 'village.matchRecruit.filterAllCategory' },
  { value: 'PRACTICE_MATCH', i18nKey: 'village.matchRecruit.category.PRACTICE_MATCH' },
  { value: 'REFEREE', i18nKey: 'village.matchRecruit.category.REFEREE' },
  { value: 'VENUE', i18nKey: 'village.matchRecruit.category.VENUE' },
  { value: 'OTHER', i18nKey: 'village.matchRecruit.category.OTHER' },
]

const statusOptions: { value: StatusFilter; i18nKey: string }[] = [
  { value: 'ALL', i18nKey: 'village.matchRecruit.filterAllStatus' },
  { value: 'OPEN', i18nKey: 'village.matchRecruit.status.OPEN' },
  { value: 'CLOSED', i18nKey: 'village.matchRecruit.status.CLOSED' },
  { value: 'FULFILLED', i18nKey: 'village.matchRecruit.status.FULFILLED' },
  { value: 'CANCELLED', i18nKey: 'village.matchRecruit.status.CANCELLED' },
]

const categoryDropdownOptions = computed(() =>
  categoryOptions.map(o => ({ value: o.value, label: t(o.i18nKey) })),
)
const statusDropdownOptions = computed(() =>
  statusOptions.map(o => ({ value: o.value, label: t(o.i18nKey) })),
)

async function loadRecruits() {
  recruitsLoading.value = true
  try {
    recruits.value = await villageApi.listMatchRecruits(villageId, {
      category: categoryFilter.value === 'ALL' ? undefined : categoryFilter.value,
      status: statusFilter.value === 'ALL' ? undefined : statusFilter.value,
      page: 0,
      size: 50,
    })
  }
  catch (error) {
    recruits.value = []
    handleApiError(error, t('village.matchRecruit.loadFailed'))
  }
  finally {
    recruitsLoading.value = false
  }
}

function severityForStatus(
  status: VillageMatchRecruitStatus,
): 'success' | 'info' | 'warn' | 'danger' | 'secondary' {
  switch (status) {
    case 'OPEN':
      return 'success'
    case 'CLOSED':
      return 'secondary'
    case 'FULFILLED':
      return 'info'
    case 'CANCELLED':
      return 'danger'
  }
}

function severityForAppStatus(
  status: VillageMatchApplicationResponse['status'],
): 'success' | 'info' | 'warn' | 'danger' | 'secondary' {
  switch (status) {
    case 'PENDING':
      return 'warn'
    case 'ACCEPTED':
      return 'success'
    case 'REJECTED':
      return 'danger'
    case 'WITHDRAWN':
      return 'secondary'
  }
}

// =====================================================================
// 募集作成 Dialog
// =====================================================================

interface RecruitFormState {
  category: VillageMatchRecruitCategory
  title: string
  description: string
  matchDate: string
  matchTimeStart: string
  matchTimeEnd: string
  venue: string
  requiredCount: string
  contactMethod: string
  applicationDeadline: string
}

function emptyForm(): RecruitFormState {
  return {
    category: 'PRACTICE_MATCH',
    title: '',
    description: '',
    matchDate: '',
    matchTimeStart: '',
    matchTimeEnd: '',
    venue: '',
    requiredCount: '',
    contactMethod: '',
    applicationDeadline: '',
  }
}

const showCreateDialog = ref(false)
const createForm = ref<RecruitFormState>(emptyForm())
const createPostingIdentity = ref<PostingIdentitySelection | null>(null)

const createCategoryOptions = computed(() =>
  [
    { value: 'PRACTICE_MATCH', i18nKey: 'village.matchRecruit.category.PRACTICE_MATCH' },
    { value: 'REFEREE', i18nKey: 'village.matchRecruit.category.REFEREE' },
    { value: 'VENUE', i18nKey: 'village.matchRecruit.category.VENUE' },
    { value: 'OTHER', i18nKey: 'village.matchRecruit.category.OTHER' },
  ].map(o => ({ value: o.value as VillageMatchRecruitCategory, label: t(o.i18nKey) })),
)

function openCreateDialog() {
  createForm.value = emptyForm()
  createPostingIdentity.value = null
  showCreateDialog.value = true
}

async function submitCreate() {
  if (!createForm.value.title) return
  const postedByTeamId
    = createPostingIdentity.value?.subjectType === 'TEAM'
      ? createPostingIdentity.value.subjectId
      : null
  const body: VillageMatchRecruitCreateRequest = {
    category: createForm.value.category,
    title: createForm.value.title,
    description: createForm.value.description || null,
    matchDate: createForm.value.matchDate || null,
    matchTimeStart: createForm.value.matchTimeStart || null,
    matchTimeEnd: createForm.value.matchTimeEnd || null,
    venue: createForm.value.venue || null,
    requiredCount: createForm.value.requiredCount ? Number(createForm.value.requiredCount) : null,
    contactMethod: createForm.value.contactMethod || null,
    applicationDeadline: createForm.value.applicationDeadline
      ? `${createForm.value.applicationDeadline}:00`
      : null,
    postedByTeamId,
  }
  try {
    await villageApi.createMatchRecruit(villageId, body)
    showCreateDialog.value = false
    toast.add({
      severity: 'success',
      summary: t('village.matchRecruit.saveSuccess'),
      life: 3000,
    })
    await loadRecruits()
  }
  catch (error) {
    handleApiError(error, t('village.matchRecruit.create'))
  }
}

// =====================================================================
// 詳細 + 応募 Dialog
// =====================================================================

const showDetailDialog = ref(false)
const detailRecruit = ref<VillageMatchRecruitResponse | null>(null)
const detailApplications = ref<VillageMatchApplicationResponse[]>([])

const isDetailOwner = computed(() => {
  if (!detailRecruit.value || !currentUserId.value) return false
  return detailRecruit.value.postedByUserId === currentUserId.value
})
const canSeeApplications = computed(() => isDetailOwner.value || canManage.value)

async function openDetailDialog(r: VillageMatchRecruitResponse) {
  detailRecruit.value = r
  detailApplications.value = []
  showDetailDialog.value = true
  if (isDetailOwner.value || canManage.value) {
    try {
      detailApplications.value = await villageApi.listApplications(villageId, r.id)
    }
    catch (error) {
      handleApiError(error, t('village.matchRecruit.applications'))
    }
  }
}

const showApplyDialog = ref(false)
const applyMessage = ref('')
const applyPostingIdentity = ref<PostingIdentitySelection | null>(null)

function openApplyDialog() {
  applyMessage.value = ''
  applyPostingIdentity.value = null
  showApplyDialog.value = true
}

async function submitApply() {
  if (!detailRecruit.value) return
  const applicantTeamId
    = applyPostingIdentity.value?.subjectType === 'TEAM'
      ? applyPostingIdentity.value.subjectId
      : null
  const body: VillageMatchApplicationCreateRequest = {
    message: applyMessage.value || null,
    applicantTeamId,
  }
  try {
    await villageApi.applyToMatchRecruit(villageId, detailRecruit.value.id, body)
    showApplyDialog.value = false
    toast.add({
      severity: 'success',
      summary: t('village.matchRecruit.applySuccess'),
      life: 3000,
    })
    // 応募一覧を再取得
    if (detailRecruit.value && (isDetailOwner.value || canManage.value)) {
      detailApplications.value = await villageApi.listApplications(
        villageId,
        detailRecruit.value.id,
      )
    }
  }
  catch (error) {
    handleApiError(error, t('village.matchRecruit.apply'))
  }
}

async function reviewApp(
  app: VillageMatchApplicationResponse,
  action: 'accept' | 'reject',
) {
  if (!detailRecruit.value) return
  try {
    await villageApi.reviewApplication(
      villageId,
      detailRecruit.value.id,
      app.id,
      { action },
    )
    toast.add({
      severity: 'success',
      summary:
        action === 'accept'
          ? t('village.matchApplication.acceptSuccess')
          : t('village.matchApplication.rejectSuccess'),
      life: 3000,
    })
    detailApplications.value = await villageApi.listApplications(
      villageId,
      detailRecruit.value.id,
    )
  }
  catch (error) {
    handleApiError(error, t('village.matchApplication.review'))
  }
}

async function withdrawApp(app: VillageMatchApplicationResponse) {
  if (!detailRecruit.value) return
  if (!window.confirm(t('village.matchApplication.confirmWithdraw'))) return
  try {
    await villageApi.withdrawApplication(
      villageId,
      detailRecruit.value.id,
      app.id,
    )
    toast.add({
      severity: 'success',
      summary: t('village.matchApplication.withdrawSuccess'),
      life: 3000,
    })
    if (isDetailOwner.value || canManage.value) {
      detailApplications.value = await villageApi.listApplications(
        villageId,
        detailRecruit.value.id,
      )
    }
  }
  catch (error) {
    handleApiError(error, t('village.matchApplication.withdraw'))
  }
}

async function closeRecruit() {
  if (!detailRecruit.value) return
  if (!window.confirm(t('village.matchRecruit.confirmClose'))) return
  try {
    await villageApi.closeMatchRecruit(villageId, detailRecruit.value.id)
    showDetailDialog.value = false
    await loadRecruits()
  }
  catch (error) {
    handleApiError(error, t('village.matchRecruit.close'))
  }
}

// =====================================================================
// VillageHeader アクションハンドラ
// =====================================================================

async function loadVillage() {
  loading.value = true
  notFound.value = false
  try {
    village.value = await villageApi.getVillage(villageId)
    if (village.value?.isMember) {
      await loadMyMembership()
    }
    await loadRecruits()
  }
  catch (error: unknown) {
    const status = (error as { statusCode?: number; response?: { status?: number } })
    const code = status?.statusCode ?? status?.response?.status
    if (code === 404) {
      notFound.value = true
    }
    else {
      handleApiError(error, t('village.title'))
    }
  }
  finally {
    loading.value = false
  }
}

async function loadMyMembership() {
  const myUserId = currentUserId.value
  if (!myUserId) {
    myMembership.value = null
    return
  }
  try {
    const res = await villageApi.listMembers(villageId, { page: 0, size: 100 })
    myMembership.value
      = res.content.find(
        m => m.subjectType === 'USER' && m.subjectId === myUserId,
      ) ?? null
  }
  catch (error) {
    console.warn('[village/match-recruits] listMembers failed', error)
    myMembership.value = null
  }
}

async function onJoin() {
  const myUserId = currentUserId.value
  if (!myUserId) return
  try {
    await villageApi.joinVillage(villageId, {
      subjectType: 'USER',
      subjectId: myUserId,
    })
    await loadVillage()
  }
  catch (error) {
    handleApiError(error, t('village.action.join'))
  }
}

async function onLeave() {
  if (!myMembership.value) await loadMyMembership()
  if (!myMembership.value) {
    await loadVillage()
    return
  }
  try {
    await villageApi.leaveVillage(villageId, myMembership.value.id)
    await loadVillage()
  }
  catch (error) {
    handleApiError(error, t('village.action.leave'))
  }
}

async function onPin() {
  try {
    await villageApi.addPin(villageId)
    await loadVillage()
  }
  catch (error) {
    handleApiError(error, t('village.action.pin'))
  }
}

async function onUnpin() {
  try {
    await villageApi.removePin(villageId)
    await loadVillage()
  }
  catch (error) {
    handleApiError(error, t('village.action.unpin'))
  }
}

/** 通報ダイアログ表示状態 — VillageReportDialog (FE5 完成済) を組み込む */
const showReportDialog = ref(false)
function onReportClick() {
  showReportDialog.value = true
}

/** 編集ダイアログ表示状態 — VillageEditDialog (FE α2 で新規実装) を組み込む */
const showVillageEditDialog = ref(false)
function onEdit() {
  showVillageEditDialog.value = true
}

/** 編集 Dialog から更新成功時に村情報を差し替え */
function onVillageUpdated(updated: VillageResponse) {
  village.value = updated
}

// =====================================================================
// フィルタ変更 watch
// =====================================================================

watch([categoryFilter, statusFilter], () => {
  loadRecruits()
})

// =====================================================================
// Init
// =====================================================================

onMounted(() => {
  loadVillage()
})
</script>

<template>
  <div>
    <PageLoading v-if="loading" />

    <div v-else-if="notFound" class="mx-auto max-w-2xl p-6 text-center">
      <i class="pi pi-exclamation-circle text-4xl text-surface-400" />
      <p class="mt-4 text-lg">
        {{ t('village.error.VILLAGE_001') }}
      </p>
      <NuxtLink to="/villages" class="mt-4 inline-block text-primary-600 hover:underline">
        <i class="pi pi-arrow-left mr-1" />
        {{ t('village.error.backToList') }}
      </NuxtLink>
    </div>

    <template v-else-if="village">
      <VillageHeader
        :village="village"
        active-tab="matchRecruit"
        @join="onJoin"
        @request-join="onJoin"
        @leave="onLeave"
        @pin="onPin"
        @unpin="onUnpin"
        @report-click="onReportClick"
        @edit="onEdit"
      />

      <div class="mx-auto max-w-4xl p-4 sm:p-6">
        <!-- フィルタ + 作成ボタン -->
        <div class="flex items-center justify-between flex-wrap gap-3 mb-4">
          <div class="flex items-center gap-2 flex-wrap">
            <Select
              v-model="categoryFilter"
              :options="categoryDropdownOptions"
              option-value="value"
              option-label="label"
              class="w-44"
            />
            <Select
              v-model="statusFilter"
              :options="statusDropdownOptions"
              option-value="value"
              option-label="label"
              class="w-44"
            />
          </div>
          <Button
            v-if="isVillager"
            :label="t('village.matchRecruit.create')"
            icon="pi pi-plus"
            severity="primary"
            size="small"
            @click="openCreateDialog"
          />
        </div>

        <!-- 募集一覧 -->
        <div v-if="recruitsLoading" class="text-center py-12 text-surface-500">
          <i class="pi pi-spin pi-spinner text-2xl" />
        </div>
        <DashboardEmptyState
          v-else-if="recruits.length === 0"
          icon="pi pi-flag"
          :message="t('village.matchRecruit.empty')"
        />
        <div v-else class="flex flex-col gap-3">
          <button
            v-for="r in recruits"
            :key="r.id"
            type="button"
            class="village-match-recruit__row flex flex-col gap-1 rounded-lg border border-surface-200 p-4 text-left transition hover:bg-surface-50 dark:border-surface-700 dark:hover:bg-surface-800"
            @click="openDetailDialog(r)"
          >
            <div class="flex items-center justify-between gap-2 flex-wrap">
              <div class="flex items-center gap-2">
                <Badge
                  :value="t(`village.matchRecruit.category.${r.category}`)"
                  severity="secondary"
                />
                <Badge
                  :value="t(`village.matchRecruit.status.${r.status}`)"
                  :severity="severityForStatus(r.status)"
                />
              </div>
              <span v-if="r.applicationDeadline" class="text-xs text-surface-500">
                {{ t('village.matchRecruit.deadline') }}: {{ r.applicationDeadline }}
              </span>
            </div>
            <span class="font-semibold truncate">{{ r.title }}</span>
            <div class="text-xs text-surface-500 flex items-center gap-3 flex-wrap">
              <span v-if="r.matchDate">
                <i class="pi pi-calendar mr-1" />{{ r.matchDate }}
                <span v-if="r.matchTimeStart"> {{ r.matchTimeStart }}</span>
                <span v-if="r.matchTimeEnd"> - {{ r.matchTimeEnd }}</span>
              </span>
              <span v-if="r.venue">
                <i class="pi pi-map-marker mr-1" />{{ r.venue }}
              </span>
              <span v-if="r.requiredCount">
                <i class="pi pi-users mr-1" />{{ r.requiredCount }}
              </span>
            </div>
          </button>
        </div>
      </div>

      <!-- 募集作成 Dialog (Selector 付き) -->
      <Dialog
        v-model:visible="showCreateDialog"
        modal
        :draggable="false"
        :header="t('village.matchRecruit.createTitle')"
        :style="{ width: '36rem' }"
        :breakpoints="{ '640px': '92vw' }"
      >
        <div class="flex flex-col gap-3">
          <!-- Phase 2: 投稿主体 Selector を有効化 -->
          <VillagePostingIdentitySelector
            :village-id="villageId"
            :model-value="createPostingIdentity"
            :visible="true"
            @update:model-value="(v) => (createPostingIdentity = v)"
          />
          <div>
            <label class="block text-sm font-medium mb-1">
              {{ t('village.field.category') }}
            </label>
            <Select
              v-model="createForm.category"
              :options="createCategoryOptions"
              option-value="value"
              option-label="label"
              class="w-full"
            />
          </div>
          <div>
            <label class="block text-sm font-medium mb-1">
              {{ t('village.matchRecruit.recruitTitle') }}
            </label>
            <InputText v-model="createForm.title" class="w-full" />
          </div>
          <div>
            <label class="block text-sm font-medium mb-1">
              {{ t('village.matchRecruit.description') }}
            </label>
            <Textarea v-model="createForm.description" class="w-full" rows="3" />
          </div>
          <div class="grid grid-cols-3 gap-3">
            <div>
              <label class="block text-sm font-medium mb-1">
                {{ t('village.matchRecruit.matchDate') }}
              </label>
              <InputText v-model="createForm.matchDate" type="date" class="w-full" />
            </div>
            <div>
              <label class="block text-sm font-medium mb-1">
                {{ t('village.matchRecruit.matchTimeStart') }}
              </label>
              <InputText v-model="createForm.matchTimeStart" type="time" class="w-full" />
            </div>
            <div>
              <label class="block text-sm font-medium mb-1">
                {{ t('village.matchRecruit.matchTimeEnd') }}
              </label>
              <InputText v-model="createForm.matchTimeEnd" type="time" class="w-full" />
            </div>
          </div>
          <div class="grid grid-cols-2 gap-3">
            <div>
              <label class="block text-sm font-medium mb-1">
                {{ t('village.matchRecruit.venue') }}
              </label>
              <InputText v-model="createForm.venue" class="w-full" />
            </div>
            <div>
              <label class="block text-sm font-medium mb-1">
                {{ t('village.matchRecruit.requiredCount') }}
              </label>
              <InputText
                v-model="createForm.requiredCount"
                type="number"
                min="0"
                class="w-full"
              />
            </div>
          </div>
          <div>
            <label class="block text-sm font-medium mb-1">
              {{ t('village.matchRecruit.contactMethod') }}
            </label>
            <InputText v-model="createForm.contactMethod" class="w-full" />
          </div>
          <div>
            <label class="block text-sm font-medium mb-1">
              {{ t('village.matchRecruit.deadline') }}
            </label>
            <InputText
              v-model="createForm.applicationDeadline"
              type="datetime-local"
              class="w-full"
            />
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
        :header="detailRecruit?.title ?? ''"
        :style="{ width: '40rem' }"
        :breakpoints="{ '640px': '92vw' }"
      >
        <div v-if="detailRecruit" class="flex flex-col gap-3">
          <div class="flex items-center gap-2 flex-wrap">
            <Badge
              :value="t(`village.matchRecruit.category.${detailRecruit.category}`)"
              severity="secondary"
            />
            <Badge
              :value="t(`village.matchRecruit.status.${detailRecruit.status}`)"
              :severity="severityForStatus(detailRecruit.status)"
            />
          </div>
          <p v-if="detailRecruit.description" class="whitespace-pre-wrap text-sm">
            {{ detailRecruit.description }}
          </p>
          <div class="grid grid-cols-2 gap-2 text-sm">
            <div v-if="detailRecruit.matchDate">
              <strong>{{ t('village.matchRecruit.matchDate') }}:</strong>
              {{ detailRecruit.matchDate }}
              <span v-if="detailRecruit.matchTimeStart">{{ detailRecruit.matchTimeStart }}</span>
              <span v-if="detailRecruit.matchTimeEnd"> - {{ detailRecruit.matchTimeEnd }}</span>
            </div>
            <div v-if="detailRecruit.venue">
              <strong>{{ t('village.matchRecruit.venue') }}:</strong>
              {{ detailRecruit.venue }}
            </div>
            <div v-if="detailRecruit.requiredCount">
              <strong>{{ t('village.matchRecruit.requiredCount') }}:</strong>
              {{ detailRecruit.requiredCount }}
            </div>
            <div v-if="detailRecruit.contactMethod">
              <strong>{{ t('village.matchRecruit.contactMethod') }}:</strong>
              {{ detailRecruit.contactMethod }}
            </div>
            <div v-if="detailRecruit.applicationDeadline">
              <strong>{{ t('village.matchRecruit.deadline') }}:</strong>
              {{ detailRecruit.applicationDeadline }}
            </div>
          </div>

          <!-- 応募一覧（投稿者 / HEADMAN / ELDER のみ） -->
          <div v-if="canSeeApplications" class="mt-2">
            <h3 class="font-semibold mb-2">
              {{ t('village.matchRecruit.applications') }}
            </h3>
            <DashboardEmptyState
              v-if="detailApplications.length === 0"
              icon="pi pi-inbox"
              :message="t('village.matchRecruit.noApplications')"
            />
            <div v-else class="flex flex-col gap-2">
              <div
                v-for="app in detailApplications"
                :key="app.id"
                class="rounded border border-surface-200 dark:border-surface-700 p-3 text-sm"
              >
                <div class="flex items-center justify-between gap-2 flex-wrap">
                  <span>
                    {{ t('village.matchRecruit.applicantUser') }} #{{ app.applicantUserId }}
                    <span v-if="app.applicantTeamId"> (team #{{ app.applicantTeamId }})</span>
                  </span>
                  <Badge
                    :value="t(`village.matchApplication.status.${app.status}`)"
                    :severity="severityForAppStatus(app.status)"
                  />
                </div>
                <p v-if="app.message" class="text-xs text-surface-500 mt-1 whitespace-pre-wrap">
                  {{ app.message }}
                </p>
                <div
                  v-if="app.status === 'PENDING'"
                  class="flex items-center gap-2 mt-2"
                >
                  <Button
                    :label="t('village.matchApplication.accept')"
                    size="small"
                    severity="success"
                    @click="reviewApp(app, 'accept')"
                  />
                  <Button
                    :label="t('village.matchApplication.reject')"
                    size="small"
                    severity="danger"
                    outlined
                    @click="reviewApp(app, 'reject')"
                  />
                  <Button
                    v-if="currentUserId === app.applicantUserId"
                    :label="t('village.matchApplication.withdraw')"
                    size="small"
                    severity="secondary"
                    text
                    @click="withdrawApp(app)"
                  />
                </div>
              </div>
            </div>
          </div>
        </div>
        <template #footer>
          <Button
            v-if="
              detailRecruit
                && detailRecruit.status === 'OPEN'
                && isVillager
            "
            :label="t('village.matchRecruit.apply')"
            icon="pi pi-send"
            severity="primary"
            @click="openApplyDialog"
          />
          <Button
            v-if="
              detailRecruit
                && detailRecruit.status === 'OPEN'
                && (isDetailOwner || canManage)
            "
            :label="t('village.matchRecruit.close')"
            icon="pi pi-times"
            severity="secondary"
            outlined
            @click="closeRecruit"
          />
          <Button
            :label="t('village.action.cancel')"
            severity="secondary"
            text
            @click="showDetailDialog = false"
          />
        </template>
      </Dialog>

      <!-- 応募 Dialog (Selector 付き) -->
      <Dialog
        v-model:visible="showApplyDialog"
        modal
        :draggable="false"
        :header="t('village.matchRecruit.applyTitle')"
        :style="{ width: '32rem' }"
        :breakpoints="{ '640px': '92vw' }"
      >
        <div class="flex flex-col gap-3">
          <VillagePostingIdentitySelector
            :village-id="villageId"
            :model-value="applyPostingIdentity"
            :visible="true"
            @update:model-value="(v) => (applyPostingIdentity = v)"
          />
          <div>
            <label class="block text-sm font-medium mb-1">
              {{ t('village.matchRecruit.applyMessage') }}
            </label>
            <Textarea v-model="applyMessage" class="w-full" rows="4" />
          </div>
        </div>
        <template #footer>
          <Button
            :label="t('village.action.cancel')"
            severity="secondary"
            text
            @click="showApplyDialog = false"
          />
          <Button
            :label="t('village.matchRecruit.apply')"
            icon="pi pi-send"
            severity="primary"
            @click="submitApply"
          />
        </template>
      </Dialog>

      <!-- 通報ダイアログ — 対象は村本体 (VILLAGE) -->
      <VillageReportDialog
        v-model:visible="showReportDialog"
        :village-id="village.id"
        target-type="VILLAGE"
        :target-ref-id="village.id"
      />

      <!-- 村本体編集ダイアログ — 村長のみ（VillageHeader 側で制御） -->
      <VillageEditDialog
        v-model:visible="showVillageEditDialog"
        :village="village"
        @updated="onVillageUpdated"
      />
    </template>
  </div>
</template>
