<script setup lang="ts">
/**
 * F17.1 村機能 — 村詳細 / 寄合タブ（永続シェル子）
 *
 * 設計書: docs/features/F17.1_village_community.md §2.2 / §13.2（Phase 3）
 *
 * 永続シェル方式（SPA）: 村データ・権限・VillageHeader・アクションは親
 * `pages/villages/[id].vue` に集約。本ファイルは寄合パネル本体のみ。
 *
 * 構成:
 *   - <VillageMeetupListSection />（フィルタタブ + 一覧 + 作成ボタン）
 *   - 作成 Dialog: <VillageMeetupCreateDialog />
 *   - 詳細 + 投票 Dialog: <VillageMeetupDetailDialog />
 */
import type {
  VillageMeetupCandidateDateResponse,
  VillageMeetupCreateRequest,
  VillageMeetupResponse,
  VillageMeetupStatus,
  VillageMeetupVoteType,
} from '~/types/village'
import { useVillageContext } from '~/composables/useVillageContext'

// auth は各タブで明示宣言（本コードベースの規約。親シェルも auth を持つ）。
definePageMeta({ middleware: 'auth' })

const route = useRoute()
const villageId = computed(() => String(route.params.id))
const { t } = useI18n()
const villageApi = useVillageApi()
const { handleApiError } = useErrorHandler()
const { success } = useNotification()
const { confirmAction } = useConfirmDialog()

// 権限・ユーザー id は親シェルから inject
const { perms, currentUserId } = useVillageContext()

// =====================================================================
// State — 寄合一覧
// =====================================================================

type StatusFilter = VillageMeetupStatus | 'ALL'

const statusFilter = ref<StatusFilter>('OPEN')
const meetups = ref<VillageMeetupResponse[]>([])
const meetupsLoading = ref(false)

const isVillager = computed(() => perms.value.isMember)

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
    meetups.value = await villageApi.listMeetups(villageId.value, {
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

// === useFormDraft（ADHD配慮・寄合作成の自動保存）===
const authStore = useAuthStore()
const meetupDraftKey = computed(
  () => `meetup-create-draft-${authStore.currentUser?.id ?? 'guest'}-${villageId.value}`,
)
const meetupFormSnapshot = computed<MeetupFormState>(() => ({
  title: createForm.value.title,
  description: createForm.value.description,
  venue: createForm.value.venue,
  // candidateDates は配列なのでシャローコピー
  candidateDates: [...createForm.value.candidateDates.map(d => ({ ...d }))],
}))
const { clear: clearMeetupDraft, restore: restoreMeetupDraft } = useFormDraft<MeetupFormState>(
  meetupDraftKey.value,
  { source: meetupFormSnapshot, debounceMs: 1000 },
)

function openCreateDialog() {
  createForm.value = emptyForm()
  // 下書き復元
  const saved = restoreMeetupDraft()
  if (saved) {
    createForm.value = {
      title: saved.title ?? '',
      description: saved.description ?? '',
      venue: saved.venue ?? '',
      candidateDates: saved.candidateDates?.length
        ? saved.candidateDates
        : emptyForm().candidateDates,
    }
  }
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
    await villageApi.createMeetup(villageId.value, body)
    clearMeetupDraft()
    showCreateDialog.value = false
    success(t('village.meetup.saveSuccess'))
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
    detailMeetup.value = await villageApi.getMeetup(villageId.value, m.id)
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
    detailMeetup.value = await villageApi.castVote(villageId.value, detailMeetup.value.id, {
      candidateDateId: candidate.id,
      voteType,
    })
    success(t('village.meetup.voteSuccess'))
  }
  catch (error) {
    handleApiError(error, t('village.meetup.vote'))
  }
}

async function confirmCandidate(candidate: VillageMeetupCandidateDateResponse) {
  if (!detailMeetup.value) return
  try {
    detailMeetup.value = await villageApi.confirmMeetup(
      villageId.value,
      detailMeetup.value.id,
      candidate.id,
    )
    success(t('village.meetup.confirmSuccess'))
    await loadMeetups()
  }
  catch (error) {
    handleApiError(error, t('village.meetup.confirm'))
  }
}

function cancelMeetup() {
  if (!detailMeetup.value) return
  const meetup = detailMeetup.value
  confirmAction({
    message: t('village.meetup.confirmCancel'),
    onAccept: async () => {
      try {
        await villageApi.cancelMeetup(villageId.value, meetup.id)
        showDetailDialog.value = false
        success(t('village.meetup.cancelSuccess'))
        await loadMeetups()
      }
      catch (error) {
        handleApiError(error, t('village.meetup.cancel'))
      }
    },
  })
}

// =====================================================================
// Init
// =====================================================================

onMounted(() => {
  void loadMeetups()
})
</script>

<template>
  <div>
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
  </div>
</template>
