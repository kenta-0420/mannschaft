<script setup lang="ts">
/**
 * F17.1 村機能 — 村詳細 / 寄合タブ
 *
 * 設計書: docs/features/F17.1_village_community.md §2.2 / §13.2（Phase 3）
 *
 * 構成:
 *   - 上段: <VillageHeader activeTab="meetup" />
 *   - 下段:
 *       - ステータスフィルタタブ（OPEN / CONFIRMED / CANCELLED / ALL）
 *       - 寄合カード一覧
 *       - 村人なら誰でも「寄合を企画」ボタン
 *       - カード -> 詳細 Dialog（候補日一覧 + 各候補日への投票 + 集計表示 + 確定ボタン（幹事のみ））
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

const statusFilterTabs: { value: StatusFilter; i18nKey: string }[] = [
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

function severityForStatus(
  status: VillageMeetupStatus,
): 'success' | 'info' | 'warn' | 'danger' | 'secondary' {
  switch (status) {
    case 'DRAFT':
      return 'secondary'
    case 'OPEN':
      return 'success'
    case 'CONFIRMED':
      return 'info'
    case 'CANCELLED':
      return 'danger'
    case 'CLOSED':
      return 'secondary'
  }
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

      <div class="mx-auto max-w-4xl p-4 sm:p-6">
        <!-- フィルタ + 作成ボタン -->
        <div class="flex items-center justify-between flex-wrap gap-3 mb-4">
          <div class="flex items-center gap-1 flex-wrap">
            <button
              v-for="tab in statusFilterTabs"
              :key="tab.value"
              type="button"
              class="px-3 py-1.5 rounded-md text-sm transition"
              :class="statusFilter === tab.value
                ? 'bg-primary text-primary-contrast font-semibold'
                : 'text-surface-600 dark:text-surface-300 hover:bg-surface-100 dark:hover:bg-surface-800'"
              @click="setStatusFilter(tab.value)"
            >
              {{ t(tab.i18nKey) }}
            </button>
          </div>
          <Button
            v-if="isVillager"
            :label="t('village.meetup.create')"
            icon="pi pi-plus"
            severity="primary"
            size="small"
            @click="openCreateDialog"
          />
        </div>

        <!-- 一覧 -->
        <div v-if="meetupsLoading" class="text-center py-12 text-surface-500">
          <i class="pi pi-spin pi-spinner text-2xl" />
        </div>
        <DashboardEmptyState
          v-else-if="meetups.length === 0"
          icon="pi pi-calendar-plus"
          :message="t('village.meetup.empty')"
        />
        <div v-else class="flex flex-col gap-3">
          <button
            v-for="m in meetups"
            :key="m.id"
            type="button"
            class="village-meetup__row flex flex-col gap-1 rounded-lg border border-surface-200 p-4 text-left transition hover:bg-surface-50 dark:border-surface-700 dark:hover:bg-surface-800"
            @click="openDetailDialog(m)"
          >
            <div class="flex items-center justify-between gap-2 flex-wrap">
              <div class="flex items-center gap-2">
                <Badge
                  :value="t(`village.meetup.status.${m.status}`)"
                  :severity="severityForStatus(m.status)"
                />
              </div>
              <span class="text-xs text-surface-500">
                <i class="pi pi-users mr-1" />{{ m.participantCount }}
              </span>
            </div>
            <span class="font-semibold truncate">{{ m.title }}</span>
            <div class="text-xs text-surface-500 flex items-center gap-3 flex-wrap">
              <span v-if="m.venue">
                <i class="pi pi-map-marker mr-1" />{{ m.venue }}
              </span>
              <span>
                <i class="pi pi-calendar mr-1" />
                {{ m.candidateDates.length }} {{ t('village.meetup.candidateDates') }}
              </span>
            </div>
          </button>
        </div>
      </div>

      <!-- 寄合作成 Dialog -->
      <Dialog
        v-model:visible="showCreateDialog"
        modal
        :draggable="false"
        :header="t('village.meetup.createTitle')"
        :style="{ width: '36rem' }"
        :breakpoints="{ '640px': '92vw' }"
      >
        <div class="flex flex-col gap-3">
          <div>
            <label class="block text-sm font-medium mb-1">
              {{ t('village.meetup.meetupTitle') }}
            </label>
            <InputText v-model="createForm.title" class="w-full" />
          </div>
          <div>
            <label class="block text-sm font-medium mb-1">
              {{ t('village.meetup.description') }}
            </label>
            <Textarea v-model="createForm.description" class="w-full" rows="3" />
          </div>
          <div>
            <label class="block text-sm font-medium mb-1">
              {{ t('village.meetup.venue') }}
            </label>
            <InputText v-model="createForm.venue" class="w-full" />
          </div>
          <div>
            <div class="flex items-center justify-between mb-2">
              <label class="block text-sm font-medium">
                {{ t('village.meetup.candidateDates') }}
              </label>
              <Button
                :label="t('village.meetup.addCandidateDate')"
                icon="pi pi-plus"
                size="small"
                text
                @click="addCandidateDateRow"
              />
            </div>
            <div class="flex flex-col gap-2">
              <div
                v-for="(c, idx) in createForm.candidateDates"
                :key="idx"
                class="grid grid-cols-[1fr_1fr_1fr_auto] gap-2 items-end"
              >
                <InputText
                  v-model="c.candidateDate"
                  type="date"
                  class="w-full"
                />
                <InputText
                  v-model="c.candidateTimeStart"
                  type="time"
                  class="w-full"
                />
                <InputText
                  v-model="c.candidateTimeEnd"
                  type="time"
                  class="w-full"
                />
                <Button
                  icon="pi pi-trash"
                  severity="danger"
                  text
                  size="small"
                  :disabled="createForm.candidateDates.length <= 1"
                  :aria-label="t('village.meetup.removeCandidateDate')"
                  @click="removeCandidateDateRow(idx)"
                />
              </div>
            </div>
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

      <!-- 詳細 + 投票 Dialog -->
      <Dialog
        v-model:visible="showDetailDialog"
        modal
        :draggable="false"
        :header="detailMeetup?.title ?? ''"
        :style="{ width: '42rem' }"
        :breakpoints="{ '640px': '92vw' }"
      >
        <div v-if="detailMeetup" class="flex flex-col gap-3">
          <div class="flex items-center gap-2 flex-wrap">
            <Badge
              :value="t(`village.meetup.status.${detailMeetup.status}`)"
              :severity="severityForStatus(detailMeetup.status)"
            />
            <span class="text-xs text-surface-500">
              <i class="pi pi-users mr-1" />{{ detailMeetup.participantCount }} {{ t('village.meetup.participantCount') }}
            </span>
          </div>
          <p v-if="detailMeetup.description" class="whitespace-pre-wrap text-sm">
            {{ detailMeetup.description }}
          </p>
          <div v-if="detailMeetup.venue" class="text-sm">
            <strong>{{ t('village.meetup.venue') }}:</strong> {{ detailMeetup.venue }}
          </div>

          <!-- 候補日一覧 + 投票 -->
          <div class="mt-2">
            <h3 class="font-semibold mb-2">
              {{ t('village.meetup.candidateDates') }}
            </h3>
            <div class="flex flex-col gap-2">
              <div
                v-for="c in detailMeetup.candidateDates"
                :key="c.id"
                class="rounded border p-3 text-sm"
                :class="c.isConfirmed
                  ? 'border-primary bg-primary-50 dark:bg-primary-950'
                  : 'border-surface-200 dark:border-surface-700'"
              >
                <div class="flex items-center justify-between gap-2 flex-wrap">
                  <div class="flex items-center gap-2">
                    <i class="pi pi-calendar" />
                    <span>{{ c.candidateDate }}</span>
                    <span v-if="c.candidateTimeStart">{{ c.candidateTimeStart }}</span>
                    <span v-if="c.candidateTimeEnd"> - {{ c.candidateTimeEnd }}</span>
                    <Badge
                      v-if="c.isConfirmed"
                      :value="t('village.meetup.confirmedDate')"
                      severity="info"
                    />
                  </div>
                  <div class="flex items-center gap-2 text-xs text-surface-500">
                    <span>
                      <i class="pi pi-check text-green-500" /> {{ c.voteCountYes }}
                    </span>
                    <span>
                      <i class="pi pi-question text-yellow-500" /> {{ c.voteCountMaybe }}
                    </span>
                    <span>
                      <i class="pi pi-times text-red-500" /> {{ c.voteCountNo }}
                    </span>
                  </div>
                </div>
                <div
                  v-if="isVillager && detailMeetup.status === 'OPEN'"
                  class="flex items-center gap-2 mt-2"
                >
                  <Button
                    :label="t('village.meetup.voteType.YES')"
                    size="small"
                    severity="success"
                    outlined
                    @click="castVoteOn(c, 'YES')"
                  />
                  <Button
                    :label="t('village.meetup.voteType.MAYBE')"
                    size="small"
                    severity="warn"
                    outlined
                    @click="castVoteOn(c, 'MAYBE')"
                  />
                  <Button
                    :label="t('village.meetup.voteType.NO')"
                    size="small"
                    severity="danger"
                    outlined
                    @click="castVoteOn(c, 'NO')"
                  />
                  <Button
                    v-if="isDetailOrganizer"
                    :label="t('village.meetup.confirm')"
                    icon="pi pi-check"
                    size="small"
                    severity="primary"
                    class="ml-auto"
                    @click="confirmCandidate(c)"
                  />
                </div>
              </div>
            </div>
          </div>
        </div>
        <template #footer>
          <Button
            v-if="
              detailMeetup
                && detailMeetup.status !== 'CANCELLED'
                && detailMeetup.status !== 'CLOSED'
                && isDetailOrganizer
            "
            :label="t('village.meetup.cancel')"
            icon="pi pi-times"
            severity="danger"
            outlined
            @click="cancelMeetup"
          />
          <Button
            :label="t('village.action.cancel')"
            severity="secondary"
            text
            @click="showDetailDialog = false"
          />
        </template>
      </Dialog>

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
