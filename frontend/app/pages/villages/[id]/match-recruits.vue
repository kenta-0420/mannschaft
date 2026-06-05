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
 *
 * リファクタリング第5弾 (2026-05-17): 一覧・募集作成・詳細・応募 Dialog を
 * components/match-recruits/ 配下の子コンポーネントに分割。本ページは API
 * 呼び出し・state 統合・配置に専念する。振る舞いは完全に元コードと同一。
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

definePageMeta({
  middleware: 'auth',
  layout: 'default',
  key: route => route.fullPath,
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

// =====================================================================
// 募集作成 Dialog
// =====================================================================

const showCreateDialog = ref(false)

const createCategoryOptions = computed(() =>
  [
    { value: 'PRACTICE_MATCH', i18nKey: 'village.matchRecruit.category.PRACTICE_MATCH' },
    { value: 'REFEREE', i18nKey: 'village.matchRecruit.category.REFEREE' },
    { value: 'VENUE', i18nKey: 'village.matchRecruit.category.VENUE' },
    { value: 'OTHER', i18nKey: 'village.matchRecruit.category.OTHER' },
  ].map(o => ({ value: o.value as VillageMatchRecruitCategory, label: t(o.i18nKey) })),
)

function openCreateDialog() {
  showCreateDialog.value = true
}

async function submitCreate(body: VillageMatchRecruitCreateRequest) {
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

function openApplyDialog() {
  showApplyDialog.value = true
}

async function submitApply(body: VillageMatchApplicationCreateRequest) {
  if (!detailRecruit.value) return
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

      <!-- フィルタ + 募集一覧 -->
      <MatchRecruitList
        v-model:category-filter="categoryFilter"
        v-model:status-filter="statusFilter"
        :recruits="recruits"
        :recruits-loading="recruitsLoading"
        :is-villager="isVillager"
        :category-dropdown-options="categoryDropdownOptions"
        :status-dropdown-options="statusDropdownOptions"
        @create="openCreateDialog"
        @select="openDetailDialog"
      />

      <!-- 募集作成 Dialog (Selector 付き) -->
      <MatchRecruitForm
        v-model:visible="showCreateDialog"
        :village-id="villageId"
        :category-options="createCategoryOptions"
        @submit="submitCreate"
      />

      <!-- 詳細 Dialog -->
      <MatchRecruitDetailDialog
        v-model:visible="showDetailDialog"
        :recruit="detailRecruit"
        :applications="detailApplications"
        :can-see-applications="canSeeApplications"
        :is-villager="isVillager"
        :is-detail-owner="isDetailOwner"
        :can-manage="canManage"
        :current-user-id="currentUserId"
        @apply="openApplyDialog"
        @close-recruit="closeRecruit"
        @review-app="reviewApp"
        @withdraw-app="withdrawApp"
      />

      <!-- 応募 Dialog (Selector 付き) -->
      <MatchRecruitApplyDialog
        v-model:visible="showApplyDialog"
        :village-id="villageId"
        @submit="submitApply"
      />

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
