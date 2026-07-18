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
  VillageMeetupVoteSummary,
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

// BE の VillageMeetupStatus は PLANNING / CONFIRMED / CANCELLED の 3 値のみ。
// 既定は PLANNING（投票受付中）。
const statusFilter = ref<StatusFilter>('PLANNING')
const meetups = ref<VillageMeetupResponse[]>([])
const meetupsLoading = ref(false)

const isVillager = computed(() => perms.value.isMember)

const statusFilterTabs: { value: StatusFilter, i18nKey: string }[] = [
  { value: 'PLANNING', i18nKey: 'village.meetup.status.PLANNING' },
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

/**
 * 候補日フォーム行。
 *
 * BE (`MeetupCreateRequest.candidateDates`) は object 配列 `{date, time?}`（#2357）。
 * `candidateTime` は任意（空文字は終日として送信時に省略する）。
 */
interface CandidateDateForm {
  candidateDate: string
  /** 時刻 (HH:mm)。空は終日 */
  candidateTime: string
}

interface MeetupFormState {
  title: string
  description: string
  /** BE のフィールド名は venue ではなく location */
  location: string
  candidateDates: CandidateDateForm[]
}

function emptyForm(): MeetupFormState {
  return {
    title: '',
    description: '',
    location: '',
    candidateDates: [{ candidateDate: '', candidateTime: '' }],
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
  location: createForm.value.location,
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
      location: saved.location ?? '',
      candidateDates: saved.candidateDates?.length
        ? saved.candidateDates.map(d => ({
            candidateDate: d.candidateDate ?? '',
            candidateTime: d.candidateTime ?? '',
          }))
        : emptyForm().candidateDates,
    }
  }
  showCreateDialog.value = true
}

function addCandidateDateRow() {
  createForm.value.candidateDates.push({ candidateDate: '', candidateTime: '' })
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
    location: createForm.value.location || null,
    // BE は object 配列 `{date, time?}` を受け取る（#2357）。空の時刻は省略（終日）。
    candidateDates: validDates.map(d => (
      d.candidateTime
        ? { date: d.candidateDate, time: d.candidateTime }
        : { date: d.candidateDate }
    )),
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
/**
 * 投票集計。候補日 DTO には票数が含まれない（BE: MeetupCandidateDateResponse は
 * `{id, meetupId, candidateDate, sortOrder}` のみ）ため、票数表示は投票集計 API
 * (`GET /meetups/{id}/votes`) から供給する。
 */
const voteSummary = ref<VillageMeetupVoteSummary | null>(null)

const isDetailOrganizer = computed(() => {
  if (!detailMeetup.value || !currentUserId.value) return false
  return detailMeetup.value.organizerUserId === currentUserId.value
})

/** 寄合詳細と投票集計をまとめて読み込む。 */
async function loadDetail(meetupId: string) {
  const [meetup, summary] = await Promise.all([
    villageApi.getMeetup(villageId.value, meetupId),
    villageApi.getVoteSummary(villageId.value, meetupId),
  ])
  detailMeetup.value = meetup
  voteSummary.value = summary
}

async function openDetailDialog(m: VillageMeetupResponse) {
  // 一覧 API は候補日を省略する（candidateDates=null）ため、詳細は必ず再取得する。
  detailMeetup.value = null
  voteSummary.value = null
  showDetailDialog.value = true
  try {
    await loadDetail(m.id)
  }
  catch (error) {
    detailMeetup.value = m
    handleApiError(error, t('village.meetup.loadFailed'))
  }
}

async function castVoteOn(candidate: VillageMeetupCandidateDateResponse, voteType: VillageMeetupVoteType) {
  if (!detailMeetup.value) return
  const meetupId = detailMeetup.value.id
  try {
    // BE は 204 No Content（本体なし）。投票後の最新状態は再取得して反映する。
    await villageApi.castVote(villageId.value, meetupId, candidate.id, { voteType })
    await loadDetail(meetupId)
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
      :vote-summary="voteSummary"
      :is-villager="isVillager"
      :is-detail-organizer="isDetailOrganizer"
      @cast-vote="castVoteOn"
      @confirm-candidate="confirmCandidate"
      @cancel-meetup="cancelMeetup"
    />
  </div>
</template>
