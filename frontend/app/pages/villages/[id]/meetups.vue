<script setup lang="ts">
/**
 * F17.1 村機能 — 村詳細 / 寄合タブ
 *
 * 設計書: docs/features/F17.1_village_community.md §2.2 / §13.2（Phase 3）
 *
 * 構成:
 *   - 上段: <VillageHeader activeTab="meetup" />
 *   - 下段: <VillageMeetupListSection />（フィルタタブ + 一覧 + 作成ボタン）
 *   - 作成 Dialog: <VillageMeetupCreateDialog />
 *   - 詳細 + 投票 Dialog: <VillageMeetupDetailDialog />
 *
 * 子コンポーネントは表示専用。API 呼び出し・state 統合はすべて本体側に集約する。
 */
import type {
  MembershipResponse,
  VillageMeetupCandidateDateResponse,
  VillageMeetupCreateRequest,
  VillageMeetupResponse,
  VillageMeetupStatus,
  VillageMeetupVoteType,
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
// State — 寄合一覧
// =====================================================================

type StatusFilter = VillageMeetupStatus | 'ALL'

const statusFilter = ref<StatusFilter>('OPEN')
const meetups = ref<VillageMeetupResponse[]>([])
const meetupsLoading = ref(false)

const isVillager = computed(() => !!village.value?.isMember)
const currentUserId = computed<number | null>(() => authStore.currentUser?.id ?? null)

const statusFilterTabs: { value: StatusFilter, i18nKey: string }[] = [
  { value: 'OPEN', i18nKey: 'village.meetup.status.OPEN' },
  { value: 'CONFIRMED', i18nKey: 'village.meetup.status.CONFIRMED' },
  { value: 'CANCELLED', i18nKey: 'village.meetup.status.CANCELLED' },
  { value: 'ALL', i18nKey: 'village.matchRecruit.filterAllStatus' },
]

async function loadMeetups() {
  meetupsLoading.value = true
  try {
    const status = statusFilter.value === 'ALL' ? undefined : statusFilter.value
    meetups.value = await villageApi.listMeetups(villageId, {
      status,
      page: 0,
      size: 50,
    })
  }
  catch (error) {
    meetups.value = []
    handleApiError(error, t('village.meetup.loadFailed'))
  }
  finally {
    meetupsLoading.value = false
  }
}

function setStatusFilter(value: StatusFilter) {
  statusFilter.value = value
  loadMeetups()
}

// =====================================================================
// 寄合作成 Dialog
// =====================================================================

interface CandidateDateForm {
  candidateDate: string
  candidateTimeStart: string
  candidateTimeEnd: string
}

interface MeetupFormState {
  title: string
  description: string
  venue: string
  candidateDates: CandidateDateForm[]
}

function emptyForm(): MeetupFormState {
  return {
    title: '',
    description: '',
    venue: '',
    candidateDates: [{ candidateDate: '', candidateTimeStart: '', candidateTimeEnd: '' }],
  }
}

const showCreateDialog = ref(false)
const createForm = ref<MeetupFormState>(emptyForm())

function openCreateDialog() {
  createForm.value = emptyForm()
  showCreateDialog.value = true
}

function addCandidateDateRow() {
  createForm.value.candidateDates.push({
    candidateDate: '',
    candidateTimeStart: '',
    candidateTimeEnd: '',
  })
}

function removeCandidateDateRow(index: number) {
  if (createForm.value.candidateDates.length <= 1) return
  createForm.value.candidateDates.splice(index, 1)
}

async function submitCreate() {
  const validDates = createForm.value.candidateDates.filter(d => d.candidateDate)
  if (!createForm.value.title || validDates.length === 0) return
  const body: VillageMeetupCreateRequest = {
    title: createForm.value.title,
    description: createForm.value.description || null,
    venue: createForm.value.venue || null,
    candidateDates: validDates.map(d => ({
      candidateDate: d.candidateDate,
      candidateTimeStart: d.candidateTimeStart || null,
      candidateTimeEnd: d.candidateTimeEnd || null,
    })),
  }
  try {
    await villageApi.createMeetup(villageId, body)
    showCreateDialog.value = false
    toast.add({
      severity: 'success',
      summary: t('village.meetup.saveSuccess'),
      life: 3000,
    })
    await loadMeetups()
  }
  catch (error) {
    handleApiError(error, t('village.meetup.create'))
  }
}

// =====================================================================
// 詳細 + 投票 Dialog
// =====================================================================

const showDetailDialog = ref(false)
const detailMeetup = ref<VillageMeetupResponse | null>(null)

const isDetailOrganizer = computed(() => {
  if (!detailMeetup.value || !currentUserId.value) return false
  return detailMeetup.value.organizerUserId === currentUserId.value
})

async function openDetailDialog(m: VillageMeetupResponse) {
  try {
    detailMeetup.value = await villageApi.getMeetup(villageId, m.id)
  }
  catch (error) {
    detailMeetup.value = m
    handleApiError(error, t('village.meetup.loadFailed'))
  }
  showDetailDialog.value = true
}

async function castVoteOn(candidate: VillageMeetupCandidateDateResponse, voteType: VillageMeetupVoteType) {
  if (!detailMeetup.value) return
  try {
    detailMeetup.value = await villageApi.castVote(villageId, detailMeetup.value.id, {
      candidateDateId: candidate.id,
      voteType,
    })
    toast.add({
      severity: 'success',
      summary: t('village.meetup.voteSuccess'),
      life: 2500,
    })
  }
  catch (error) {
    handleApiError(error, t('village.meetup.vote'))
  }
}

async function confirmCandidate(candidate: VillageMeetupCandidateDateResponse) {
  if (!detailMeetup.value) return
  try {
    detailMeetup.value = await villageApi.confirmMeetup(
      villageId,
      detailMeetup.value.id,
      candidate.id,
    )
    toast.add({
      severity: 'success',
      summary: t('village.meetup.confirmSuccess'),
      life: 3000,
    })
    await loadMeetups()
  }
  catch (error) {
    handleApiError(error, t('village.meetup.confirm'))
  }
}

async function cancelMeetup() {
  if (!detailMeetup.value) return
  if (!window.confirm(t('village.meetup.confirmCancel'))) return
  try {
    await villageApi.cancelMeetup(villageId, detailMeetup.value.id)
    showDetailDialog.value = false
    toast.add({
      severity: 'success',
      summary: t('village.meetup.cancelSuccess'),
      life: 3000,
    })
    await loadMeetups()
  }
  catch (error) {
    handleApiError(error, t('village.meetup.cancel'))
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
    await loadMeetups()
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
    console.warn('[village/meetups] listMembers failed', error)
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

const showReportDialog = ref(false)
function onReportClick() {
  showReportDialog.value = true
}

const showVillageEditDialog = ref(false)
function onEdit() {
  showVillageEditDialog.value = true
}

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
        active-tab="meetup"
        @join="onJoin"
        @request-join="onJoin"
        @leave="onLeave"
        @pin="onPin"
        @unpin="onUnpin"
        @report-click="onReportClick"
        @edit="onEdit"
      />

      <VillageMeetupListSection
        :meetups="meetups"
        :meetups-loading="meetupsLoading"
        :status-filter="statusFilter"
        :status-filter-tabs="statusFilterTabs"
        :is-villager="isVillager"
        @set-status-filter="setStatusFilter"
        @open-create-dialog="openCreateDialog"
        @open-detail-dialog="openDetailDialog"
      />

      <VillageMeetupCreateDialog
        v-model:visible="showCreateDialog"
        v-model:form="createForm"
        @add-candidate-date-row="addCandidateDateRow"
        @remove-candidate-date-row="removeCandidateDateRow"
        @submit="submitCreate"
      />

      <VillageMeetupDetailDialog
        v-model:visible="showDetailDialog"
        :detail-meetup="detailMeetup"
        :is-villager="isVillager"
        :is-detail-organizer="isDetailOrganizer"
        @cast-vote="castVoteOn"
        @confirm-candidate="confirmCandidate"
        @cancel-meetup="cancelMeetup"
      />

      <!-- 通報ダイアログ -->
      <VillageReportDialog
        v-model:visible="showReportDialog"
        :village-id="village.id"
        target-type="VILLAGE"
        :target-ref-id="village.id"
      />

      <!-- 編集ダイアログ -->
      <VillageEditDialog
        v-model:visible="showVillageEditDialog"
        :village="village"
        @updated="onVillageUpdated"
      />
    </template>
  </div>
</template>
