<script setup lang="ts">
/**
 * F17.1 村機能 — 村詳細 / 練習試合・募集タブ（永続シェル子）
 *
 * 設計書: docs/features/F17.1_village_community.md §2.2 / §13.2
 *
 * 永続シェル方式（SPA）: 村データ・権限・VillageHeader・アクションは親
 * `pages/villages/[id].vue` に集約。本ファイルは募集パネル本体のみ。
 *
 * 構成:
 *   - カテゴリ / ステータスフィルタ + 募集カード一覧（村人なら「募集を作成」）
 *   - カード -> 詳細 Dialog（応募ボタン + 応募一覧（投稿者/HEADMAN/ELDER のみ））
 */
import type {
  VillageMatchApplicationCreateRequest,
  VillageMatchApplicationResponse,
  VillageMatchRecruitCategory,
  VillageMatchRecruitCreateRequest,
  VillageMatchRecruitResponse,
  VillageMatchRecruitStatus,
} from '~/types/village'
import { useVillageContext } from '~/composables/useVillageContext'

const route = useRoute()
const villageId = computed(() => String(route.params.id))
const { t } = useI18n()
const villageApi = useVillageApi()
const { handleApiError } = useErrorHandler()
const toast = useToast()

// 権限・ユーザー id は親シェルから inject
const { perms, currentUserId } = useVillageContext()

// =====================================================================
// State — 募集一覧
// =====================================================================

type CategoryFilter = VillageMatchRecruitCategory | 'ALL'
type StatusFilter = VillageMatchRecruitStatus | 'ALL'

const categoryFilter = ref<CategoryFilter>('ALL')
const statusFilter = ref<StatusFilter>('OPEN')
const recruits = ref<VillageMatchRecruitResponse[]>([])
const recruitsLoading = ref(false)

const isVillager = computed(() => perms.value.isMember)
const canManage = computed(() => perms.value.isAdmin)

const categoryOptions: { value: CategoryFilter, i18nKey: string }[] = [
  { value: 'ALL', i18nKey: 'village.matchRecruit.filterAllCategory' },
  { value: 'PRACTICE_MATCH', i18nKey: 'village.matchRecruit.category.PRACTICE_MATCH' },
  { value: 'REFEREE', i18nKey: 'village.matchRecruit.category.REFEREE' },
  { value: 'VENUE', i18nKey: 'village.matchRecruit.category.VENUE' },
  { value: 'OTHER', i18nKey: 'village.matchRecruit.category.OTHER' },
]

const statusOptions: { value: StatusFilter, i18nKey: string }[] = [
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
    recruits.value = await villageApi.listMatchRecruits(villageId.value, {
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
    await villageApi.createMatchRecruit(villageId.value, body)
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
      detailApplications.value = await villageApi.listApplications(villageId.value, r.id)
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
    await villageApi.applyToMatchRecruit(villageId.value, detailRecruit.value.id, body)
    showApplyDialog.value = false
    toast.add({
      severity: 'success',
      summary: t('village.matchRecruit.applySuccess'),
      life: 3000,
    })
    // 応募一覧を再取得
    if (detailRecruit.value && (isDetailOwner.value || canManage.value)) {
      detailApplications.value = await villageApi.listApplications(
        villageId.value,
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
      villageId.value,
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
      villageId.value,
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
      villageId.value,
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
        villageId.value,
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
    await villageApi.closeMatchRecruit(villageId.value, detailRecruit.value.id)
    showDetailDialog.value = false
    await loadRecruits()
  }
  catch (error) {
    handleApiError(error, t('village.matchRecruit.close'))
  }
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
  void loadRecruits()
})
</script>

<template>
  <div>
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
  </div>
</template>
