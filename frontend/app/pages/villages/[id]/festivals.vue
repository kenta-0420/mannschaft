<script setup lang="ts">
/**
 * F17.1 村機能 — 村詳細 / お祭りタブ
 *
 * 設計書: docs/features/F17.1_village_community.md §2.2 / §13.2
 *
 * 構成:
 *   - 上段: <VillageHeader activeTab="festival" />
 *   - 下段: <VillageFestivalListSection />（フィルタタブ + 一覧 + 企画ボタン）
 *   - 作成 Dialog: <VillageFestivalCreateDialog />（VillagePostingIdentitySelector 付き）
 *   - 詳細 Dialog: <VillageFestivalDetailDialog />
 *   - 編集 Dialog: <VillageFestivalEditDialog />
 *
 * 子コンポーネントは表示専用。API 呼び出し・state 統合はすべて本体側に集約する。
 */
import type {
  MembershipResponse,
  VillageFestivalCreateRequest,
  VillageFestivalResponse,
  VillageFestivalStatus,
  VillageFestivalUpdateRequest,
  VillageResponse,
} from '~/types/village'
import type { PostingIdentitySelection } from '~/components/VillagePostingIdentitySelector.vue'

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
const config = useRuntimeConfig()

// =====================================================================
// State — 村本体
// =====================================================================

const village = ref<VillageResponse | null>(null)
const loading = ref(true)
const notFound = ref(false)
const myMembership = ref<MembershipResponse | null>(null)

// =====================================================================
// State — お祭り一覧
// =====================================================================

type StatusFilter = VillageFestivalStatus | 'ALL'

const statusFilter = ref<StatusFilter>('ACTIVE')
const festivals = ref<VillageFestivalResponse[]>([])
const festivalsLoading = ref(false)

const canManage = computed(
  () => village.value?.myRole === 'HEADMAN' || village.value?.myRole === 'ELDER',
)

const statusFilterTabs: { value: StatusFilter, i18nKey: string }[] = [
  { value: 'ACTIVE', i18nKey: 'village.festival.status.ACTIVE' },
  { value: 'SCHEDULED', i18nKey: 'village.festival.status.SCHEDULED' },
  { value: 'ENDED', i18nKey: 'village.festival.status.ENDED' },
  { value: 'CANCELLED', i18nKey: 'village.festival.status.CANCELLED' },
  { value: 'ALL', i18nKey: 'village.festival.filterAll' },
]

async function loadFestivals() {
  festivalsLoading.value = true
  try {
    const status = statusFilter.value === 'ALL' ? undefined : statusFilter.value
    festivals.value = await villageApi.listFestivals(villageId, status)
  }
  catch (error) {
    festivals.value = []
    handleApiError(error, t('village.festival.loadFailed'))
  }
  finally {
    festivalsLoading.value = false
  }
}

function setStatusFilter(value: StatusFilter) {
  statusFilter.value = value
  loadFestivals()
}

// =====================================================================
// バナー画像 URL 組立
// =====================================================================

const r2PublicBase = computed<string>(() => {
  const url = config.public.r2PublicUrl as string | undefined
  return url ? url.replace(/\/$/, '') : ''
})

function buildBannerUrl(r2Key: string | null): string | null {
  if (!r2Key) return null
  if (!r2PublicBase.value) return null
  return `${r2PublicBase.value}/${r2Key}`
}

// =====================================================================
// CRUD Dialog
// =====================================================================

interface FestivalFormState {
  title: string
  description: string
  startsAt: string
  endsAt: string
  bannerR2Key: string
  themeColorHex: string
}

function emptyForm(): FestivalFormState {
  return {
    title: '',
    description: '',
    startsAt: '',
    endsAt: '',
    bannerR2Key: '',
    themeColorHex: '',
  }
}

const showCreateDialog = ref(false)
const createForm = ref<FestivalFormState>(emptyForm())
const createPostingIdentity = ref<PostingIdentitySelection | null>(null)

const showDetailDialog = ref(false)
const detailFestival = ref<VillageFestivalResponse | null>(null)

const showEditDialog = ref(false)
const editForm = ref<FestivalFormState>(emptyForm())
const editTargetId = ref<string | null>(null)

function openCreateDialog() {
  createForm.value = emptyForm()
  createPostingIdentity.value = null
  showCreateDialog.value = true
}

function openDetailDialog(f: VillageFestivalResponse) {
  detailFestival.value = f
  showDetailDialog.value = true
}

function openEditDialog(f: VillageFestivalResponse) {
  editForm.value = {
    title: f.title,
    description: f.description ?? '',
    startsAt: f.startsAt.slice(0, 16), // datetime-local
    endsAt: f.endsAt.slice(0, 16),
    bannerR2Key: f.bannerR2Key ?? '',
    themeColorHex: f.themeColorHex ?? '',
  }
  editTargetId.value = f.id
  showDetailDialog.value = false
  showEditDialog.value = true
}

async function submitCreate() {
  if (!createForm.value.startsAt || !createForm.value.endsAt) return
  const body: VillageFestivalCreateRequest = {
    title: createForm.value.title,
    description: createForm.value.description || null,
    // datetime-local の値（YYYY-MM-DDTHH:mm）に :00 を足して ISO 化
    startsAt: `${createForm.value.startsAt}:00`,
    endsAt: `${createForm.value.endsAt}:00`,
    bannerR2Key: createForm.value.bannerR2Key || null,
    themeColorHex: createForm.value.themeColorHex || null,
  }
  try {
    await villageApi.createFestival(villageId, body)
    showCreateDialog.value = false
    toast.add({
      severity: 'success',
      summary: t('village.festival.saveSuccess'),
      life: 3000,
    })
    await loadFestivals()
  }
  catch (error) {
    handleApiError(error, t('village.festival.create'))
  }
}

async function submitEdit() {
  if (!editTargetId.value) return
  const body: VillageFestivalUpdateRequest = {
    title: editForm.value.title || null,
    description: editForm.value.description || null,
    startsAt: editForm.value.startsAt ? `${editForm.value.startsAt}:00` : null,
    endsAt: editForm.value.endsAt ? `${editForm.value.endsAt}:00` : null,
    bannerR2Key: editForm.value.bannerR2Key || null,
    themeColorHex: editForm.value.themeColorHex || null,
  }
  try {
    await villageApi.updateFestival(villageId, editTargetId.value, body)
    showEditDialog.value = false
    toast.add({
      severity: 'success',
      summary: t('village.festival.saveSuccess'),
      life: 3000,
    })
    await loadFestivals()
  }
  catch (error) {
    handleApiError(error, t('village.festival.editTitle'))
  }
}

async function submitCancel(f: VillageFestivalResponse) {
  if (!window.confirm(t('village.festival.confirmCancel'))) return
  try {
    await villageApi.cancelFestival(villageId, f.id)
    showDetailDialog.value = false
    toast.add({
      severity: 'success',
      summary: t('village.festival.cancelSuccess'),
      life: 3000,
    })
    await loadFestivals()
  }
  catch (error) {
    handleApiError(error, t('village.festival.cancel'))
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
    await loadFestivals()
  }
  catch (error: unknown) {
    const status = (error as { statusCode?: number, response?: { status?: number } })
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
  const myUserId = authStore.currentUser?.id
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
    console.warn('[village/festivals] listMembers failed', error)
    myMembership.value = null
  }
}

async function onJoin() {
  const myUserId = authStore.currentUser?.id
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
        active-tab="festival"
        @join="onJoin"
        @request-join="onJoin"
        @leave="onLeave"
        @pin="onPin"
        @unpin="onUnpin"
        @report-click="onReportClick"
        @edit="onEdit"
      />

      <VillageFestivalListSection
        :festivals="festivals"
        :festivals-loading="festivalsLoading"
        :status-filter="statusFilter"
        :status-filter-tabs="statusFilterTabs"
        :can-manage="canManage"
        :build-banner-url="buildBannerUrl"
        @set-status-filter="setStatusFilter"
        @open-create-dialog="openCreateDialog"
        @open-detail-dialog="openDetailDialog"
      />

      <!-- 作成 Dialog（投稿主体 Selector 付き） -->
      <VillageFestivalCreateDialog
        v-model:visible="showCreateDialog"
        v-model:form="createForm"
        v-model:posting-identity="createPostingIdentity"
        :village-id="villageId"
        @submit="submitCreate"
      />

      <!-- 詳細 Dialog -->
      <VillageFestivalDetailDialog
        v-model:visible="showDetailDialog"
        :festival="detailFestival"
        :can-manage="canManage"
        :build-banner-url="buildBannerUrl"
        @edit="openEditDialog"
        @cancel-festival="submitCancel"
      />

      <!-- 編集 Dialog -->
      <VillageFestivalEditDialog
        v-model:visible="showEditDialog"
        v-model:form="editForm"
        @submit="submitEdit"
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
