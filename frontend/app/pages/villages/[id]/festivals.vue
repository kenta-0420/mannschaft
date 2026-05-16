<script setup lang="ts">
/**
 * F17.1 村機能 — 村詳細 / お祭りタブ
 *
 * 設計書: docs/features/F17.1_village_community.md §2.2 / §13.2
 *
 * 構成:
 *   - 上段: <VillageHeader activeTab="festival" />
 *   - 下段:
 *       - ステータスフィルタタブ（ACTIVE / SCHEDULED / ENDED / CANCELLED / ALL）
 *       - お祭りカード一覧（バナー画像 + タイトル + 期間 + ステータスバッジ）
 *       - HEADMAN/ELDER のみ「お祭りを企画」ボタン
 *       - カード -> 詳細 Dialog（編集 / 中止）
 *
 * Phase 2: 新規 Dialog では VillagePostingIdentitySelector を組み込み、
 * 投稿主体（個人 / チーム / 組織）を選べる UI を提供する。
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

const statusFilterTabs: { value: StatusFilter; i18nKey: string }[] = [
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

function severityForStatus(status: VillageFestivalStatus): 'success' | 'info' | 'warn' | 'danger' | 'secondary' {
  switch (status) {
    case 'ACTIVE':
      return 'success'
    case 'SCHEDULED':
      return 'info'
    case 'ENDED':
      return 'secondary'
    case 'CANCELLED':
      return 'danger'
  }
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

const showReportDialog = ref(false)
function onReportClick() {
  showReportDialog.value = true
}
function onEdit() {
  console.log('[village/festivals] edit requested for village', villageId)
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

      <div class="mx-auto max-w-4xl p-4 sm:p-6">
        <!-- ステータスフィルタ + 企画ボタン -->
        <div class="flex items-center justify-between flex-wrap gap-3 mb-4">
          <div class="flex items-center gap-2 flex-wrap">
            <Button
              v-for="tab in statusFilterTabs"
              :key="tab.value"
              :label="t(tab.i18nKey)"
              size="small"
              :severity="statusFilter === tab.value ? 'primary' : 'secondary'"
              :outlined="statusFilter !== tab.value"
              @click="setStatusFilter(tab.value)"
            />
          </div>
          <Button
            v-if="canManage"
            :label="t('village.festival.create')"
            icon="pi pi-plus"
            severity="primary"
            size="small"
            @click="openCreateDialog"
          />
        </div>

        <!-- お祭り一覧 -->
        <div v-if="festivalsLoading" class="text-center py-12 text-surface-500">
          <i class="pi pi-spin pi-spinner text-2xl" />
        </div>
        <DashboardEmptyState
          v-else-if="festivals.length === 0"
          icon="pi pi-star"
          :message="t('village.festival.empty')"
        />
        <div v-else class="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <button
            v-for="f in festivals"
            :key="f.id"
            type="button"
            class="village-festival__card flex flex-col rounded-lg border border-surface-200 overflow-hidden text-left transition hover:shadow-md dark:border-surface-700"
            :style="f.themeColorHex ? { borderTop: `4px solid ${f.themeColorHex}` } : undefined"
            @click="openDetailDialog(f)"
          >
            <div class="h-28 bg-surface-100 dark:bg-surface-800 flex items-center justify-center overflow-hidden">
              <img
                v-if="buildBannerUrl(f.bannerR2Key)"
                :src="buildBannerUrl(f.bannerR2Key) ?? undefined"
                :alt="f.title"
                class="w-full h-full object-cover"
              >
              <span v-else class="text-surface-400 text-sm">
                <i class="pi pi-image" /> {{ t('village.festival.noBanner') }}
              </span>
            </div>
            <div class="p-3 flex flex-col gap-1">
              <div class="flex items-center justify-between gap-2">
                <span class="font-semibold truncate">{{ f.title }}</span>
                <Badge
                  :value="t(`village.festival.status.${f.status}`)"
                  :severity="severityForStatus(f.status)"
                />
              </div>
              <div class="text-xs text-surface-500">
                {{ f.startsAt }} 〜 {{ f.endsAt }}
              </div>
            </div>
          </button>
        </div>
      </div>

      <!-- 作成 Dialog（投稿主体 Selector 付き） -->
      <Dialog
        v-model:visible="showCreateDialog"
        modal
        :draggable="false"
        :header="t('village.festival.createTitle')"
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
              {{ t('village.festival.festivalTitle') }}
            </label>
            <InputText v-model="createForm.title" class="w-full" />
          </div>
          <div>
            <label class="block text-sm font-medium mb-1">
              {{ t('village.festival.description') }}
            </label>
            <Textarea v-model="createForm.description" class="w-full" rows="3" />
          </div>
          <div class="grid grid-cols-2 gap-3">
            <div>
              <label class="block text-sm font-medium mb-1">
                {{ t('village.festival.starts') }}
              </label>
              <InputText
                v-model="createForm.startsAt"
                type="datetime-local"
                class="w-full"
              />
            </div>
            <div>
              <label class="block text-sm font-medium mb-1">
                {{ t('village.festival.ends') }}
              </label>
              <InputText
                v-model="createForm.endsAt"
                type="datetime-local"
                class="w-full"
              />
            </div>
          </div>
          <div>
            <label class="block text-sm font-medium mb-1">
              {{ t('village.festival.bannerImage') }}
            </label>
            <InputText v-model="createForm.bannerR2Key" class="w-full" />
          </div>
          <div>
            <label class="block text-sm font-medium mb-1">
              {{ t('village.festival.themeColor') }}
            </label>
            <InputText
              v-model="createForm.themeColorHex"
              type="color"
              class="w-full h-10"
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
        :header="detailFestival?.title ?? ''"
        :style="{ width: '32rem' }"
        :breakpoints="{ '640px': '92vw' }"
      >
        <div v-if="detailFestival" class="flex flex-col gap-3">
          <div
            v-if="buildBannerUrl(detailFestival.bannerR2Key)"
            class="h-40 bg-surface-100 dark:bg-surface-800 overflow-hidden rounded"
          >
            <img
              :src="buildBannerUrl(detailFestival.bannerR2Key) ?? undefined"
              :alt="detailFestival.title"
              class="w-full h-full object-cover"
            >
          </div>
          <div class="flex items-center gap-2">
            <Badge
              :value="t(`village.festival.status.${detailFestival.status}`)"
              :severity="severityForStatus(detailFestival.status)"
            />
            <span class="text-sm text-surface-500">
              {{ detailFestival.startsAt }} 〜 {{ detailFestival.endsAt }}
            </span>
          </div>
          <p v-if="detailFestival.description" class="whitespace-pre-wrap text-sm">
            {{ detailFestival.description }}
          </p>
        </div>
        <template #footer>
          <Button
            v-if="canManage && detailFestival"
            :label="t('village.festival.edit')"
            icon="pi pi-pencil"
            severity="secondary"
            outlined
            @click="openEditDialog(detailFestival)"
          />
          <Button
            v-if="canManage && detailFestival && detailFestival.status !== 'CANCELLED' && detailFestival.status !== 'ENDED'"
            :label="t('village.festival.cancel')"
            icon="pi pi-times"
            severity="danger"
            outlined
            @click="submitCancel(detailFestival)"
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
        :header="t('village.festival.editTitle')"
        :style="{ width: '36rem' }"
        :breakpoints="{ '640px': '92vw' }"
      >
        <div class="flex flex-col gap-3">
          <div>
            <label class="block text-sm font-medium mb-1">
              {{ t('village.festival.festivalTitle') }}
            </label>
            <InputText v-model="editForm.title" class="w-full" />
          </div>
          <div>
            <label class="block text-sm font-medium mb-1">
              {{ t('village.festival.description') }}
            </label>
            <Textarea v-model="editForm.description" class="w-full" rows="3" />
          </div>
          <div class="grid grid-cols-2 gap-3">
            <div>
              <label class="block text-sm font-medium mb-1">
                {{ t('village.festival.starts') }}
              </label>
              <InputText
                v-model="editForm.startsAt"
                type="datetime-local"
                class="w-full"
              />
            </div>
            <div>
              <label class="block text-sm font-medium mb-1">
                {{ t('village.festival.ends') }}
              </label>
              <InputText
                v-model="editForm.endsAt"
                type="datetime-local"
                class="w-full"
              />
            </div>
          </div>
          <div>
            <label class="block text-sm font-medium mb-1">
              {{ t('village.festival.bannerImage') }}
            </label>
            <InputText v-model="editForm.bannerR2Key" class="w-full" />
          </div>
          <div>
            <label class="block text-sm font-medium mb-1">
              {{ t('village.festival.themeColor') }}
            </label>
            <InputText
              v-model="editForm.themeColorHex"
              type="color"
              class="w-full h-10"
            />
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

      <!-- 通報ダイアログ プレースホルダー（FE5 担当） -->
      <div
        v-if="showReportDialog"
        class="fixed inset-0 z-40 flex items-center justify-center bg-black/40"
        @click="showReportDialog = false"
      >
        <div class="rounded-lg bg-surface-0 p-6 shadow-xl" @click.stop>
          <p class="mb-2 font-semibold">
            {{ t('village.report.dialog.title') }}
          </p>
          <p class="text-sm text-surface-600">
            VillageReportDialog (FE5) is not yet implemented.
          </p>
          <div class="mt-4 text-right">
            <Button
              :label="t('village.action.cancel')"
              size="small"
              text
              @click="showReportDialog = false"
            />
          </div>
        </div>
      </div>
    </template>
  </div>
</template>
