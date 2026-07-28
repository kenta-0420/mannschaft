<script setup lang="ts">
/**
 * F17.1 村機能 — 村詳細 / お祭りタブ（永続シェル子）
 *
 * 設計書: docs/features/F17.1_village_community.md §2.2 / §13.2
 *
 * 永続シェル方式（SPA）: 村データ・権限・VillageHeader・アクションは親
 * `pages/villages/[id].vue` に集約。本ファイルはお祭りパネル本体のみ。
 *
 * 構成:
 *   - <VillageFestivalListSection />（フィルタタブ + 一覧 + 企画ボタン）
 *   - 作成 Dialog: <VillageFestivalCreateDialog />（VillagePostingIdentitySelector 付き）
 *   - 詳細 Dialog: <VillageFestivalDetailDialog />
 *   - 編集 Dialog: <VillageFestivalEditDialog />
 */
import type {
  VillageFestivalCreateRequest,
  VillageFestivalLivePostResponse,
  VillageFestivalResponse,
  VillageFestivalRsvpResponse,
  VillageFestivalRsvpStatus,
  VillageFestivalStatus,
  VillageFestivalUpdateRequest,
} from '~/types/village'
import type { PostingIdentitySelection } from '~/components/VillagePostingIdentitySelector.vue'
import { useVillageContext } from '~/composables/useVillageContext'

// auth は各タブで明示宣言（本コードベースの規約。親シェルも auth を持つ）。
definePageMeta({ middleware: 'auth' })

const route = useRoute()
const villageId = computed(() => String(route.params.id))
const { t } = useI18n()
const villageApi = useVillageApi()
const { createPost: createTimelinePost } = useTimelineApi()
const { handleApiError } = useErrorHandler()
const { success } = useNotification()
const { confirmAction } = useConfirmDialog()

// 権限は親シェルから inject
const { perms, currentUserId } = useVillageContext()

// =====================================================================
// State — お祭り一覧
// =====================================================================

type StatusFilter = VillageFestivalStatus | 'ALL'

const statusFilter = ref<StatusFilter>('ACTIVE')
const festivals = ref<VillageFestivalResponse[]>([])
const festivalsLoading = ref(false)

const canManage = computed(() => perms.value.isAdmin)
const isVillager = computed(() => perms.value.isMember)

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
    festivals.value = await villageApi.listFestivals(villageId.value, status)
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

// === useFormDraft（ADHD配慮・フェスティバル作成の自動保存）===
const authStore = useAuthStore()
const festivalDraftKey = computed(
  () => `festival-create-draft-${authStore.currentUser?.id ?? 'guest'}-${villageId.value}`,
)
const festivalFormSnapshot = computed<FestivalFormState>(() => ({ ...createForm.value }))
const { clear: clearFestivalDraft, restore: restoreFestivalDraft } = useFormDraft<FestivalFormState>(
  festivalDraftKey.value,
  { source: festivalFormSnapshot, debounceMs: 1000 },
)

function openCreateDialog() {
  createForm.value = emptyForm()
  createPostingIdentity.value = null
  // 下書き復元
  const saved = restoreFestivalDraft()
  if (saved) {
    createForm.value = { ...emptyForm(), ...saved }
  }
  showCreateDialog.value = true
}

function openDetailDialog(f: VillageFestivalResponse) {
  detailFestival.value = f
  showDetailDialog.value = true
  resetParticipationState()
  if (f.status !== 'CANCELLED') void loadRsvps(f.id, { reset: true })
  if (f.status === 'ACTIVE') void loadLivePosts(f.id)
}

function openEditDialog(f: VillageFestivalResponse) {
  editForm.value = {
    title: f.title,
    description: f.description ?? '',
    startsAt: f.startsAt.slice(0, 16), // datetime-local
    endsAt: f.endsAt.slice(0, 16),
    // バナーR2キー入力欄は「新しい値を入力する」欄として扱う（空欄プリフィル）。
    // VillageFestivalResponse は #2355 で署名済み表示 URL（bannerUrl）のみを返し、
    // 生キーは返さなくなったため、現在値をテキストとして再表示することはできない。
    // 空送信は BE 側で「未指定＝現状維持」として扱われる（updateFestival の null チェック）
    // ため、空欄プリフィルでも「変更しない」という既存の意味は壊れない（VillageEditDialog と同じ方針）。
    bannerR2Key: '',
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
    await villageApi.createFestival(villageId.value, body)
    clearFestivalDraft()
    showCreateDialog.value = false
    success(t('village.festival.saveSuccess'))
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
    await villageApi.updateFestival(villageId.value, editTargetId.value, body)
    showEditDialog.value = false
    success(t('village.festival.saveSuccess'))
    await loadFestivals()
  }
  catch (error) {
    handleApiError(error, t('village.festival.editTitle'))
  }
}

function submitCancel(f: VillageFestivalResponse) {
  confirmAction({
    message: t('village.festival.confirmCancel'),
    onAccept: async () => {
      try {
        await villageApi.cancelFestival(villageId.value, f.id)
        showDetailDialog.value = false
        success(t('village.festival.cancelSuccess'))
        await loadFestivals()
      }
      catch (error) {
        handleApiError(error, t('village.festival.cancel'))
      }
    },
  })
}

// =====================================================================
// F17.2 Wave2 ③お祭りの参加レイヤー（RSVP・実況）
// 設計書: docs/features/F17.2_village_events_activation.md §5
// =====================================================================

const RSVP_PAGE_SIZE = 50

const rsvps = ref<VillageFestivalRsvpResponse[]>([])
const rsvpsLoading = ref(false)
const rsvpsLoadingMore = ref(false)
const rsvpsPage = ref(0)
const rsvpsHasMore = ref(false)

const livePosts = ref<VillageFestivalLivePostResponse[]>([])
const livePostsLoading = ref(false)
const livePostPosting = ref(false)

/**
 * 自分の RSVP。`rsvps` 一覧（size 上限あり・AC-14b）は回答者が多いと自分の回答を
 * 取り逃す恐れがあるため、upsert 応答（自分の回答そのもの）を正として保持する
 * （寄合出欠の `myAttendanceRecord` と同じ設計判断）。
 */
const myRsvpRecord = ref<VillageFestivalRsvpResponse | null>(null)

const myRsvpStatus = computed<VillageFestivalRsvpStatus | null>(() => {
  if (myRsvpRecord.value) return myRsvpRecord.value.status
  if (!currentUserId.value) return null
  return rsvps.value.find(r => r.userId === currentUserId.value)?.status ?? null
})

const myRsvpRoleLabel = computed<string | null>(() => {
  if (myRsvpRecord.value) return myRsvpRecord.value.roleLabel
  if (!currentUserId.value) return null
  return rsvps.value.find(r => r.userId === currentUserId.value)?.roleLabel ?? null
})

function resetParticipationState() {
  rsvps.value = []
  rsvpsPage.value = 0
  rsvpsHasMore.value = false
  myRsvpRecord.value = null
  livePosts.value = []
}

async function loadRsvps(festivalId: string, opts: { reset: boolean }) {
  const page = opts.reset ? 0 : rsvpsPage.value + 1
  const loadingRef = opts.reset ? rsvpsLoading : rsvpsLoadingMore
  loadingRef.value = true
  try {
    const fetched = await villageApi.listRsvps(villageId.value, festivalId, {
      page,
      size: RSVP_PAGE_SIZE,
    })
    rsvps.value = opts.reset ? fetched : [...rsvps.value, ...fetched]
    rsvpsPage.value = page
    // BE はページ総数を返さないため、直前ページが size 丁度ならまだ続きがあるとみなす。
    rsvpsHasMore.value = fetched.length === RSVP_PAGE_SIZE
  }
  catch (error) {
    if (opts.reset) rsvps.value = []
    handleApiError(error, t('village.festival.rsvp.loadFailed'))
  }
  finally {
    loadingRef.value = false
  }
}

async function respondRsvp(status: VillageFestivalRsvpStatus, roleLabel: string | null) {
  if (!detailFestival.value) return
  const festivalId = detailFestival.value.id
  try {
    myRsvpRecord.value = await villageApi.upsertRsvp(villageId.value, festivalId, { status, roleLabel })
    await loadRsvps(festivalId, { reset: true })
    success(t('village.festival.rsvp.saveSuccess'))
  }
  catch (error) {
    handleApiError(error, t('village.festival.rsvp.saveFailed'))
  }
}

async function cancelRsvp() {
  if (!detailFestival.value) return
  const festivalId = detailFestival.value.id
  try {
    await villageApi.deleteRsvp(villageId.value, festivalId)
    myRsvpRecord.value = null
    await loadRsvps(festivalId, { reset: true })
    success(t('village.festival.rsvp.cancelSuccess'))
  }
  catch (error) {
    handleApiError(error, t('village.festival.rsvp.cancelFailed'))
  }
}

function loadMoreRsvps() {
  if (!detailFestival.value || rsvpsLoadingMore.value || !rsvpsHasMore.value) return
  void loadRsvps(detailFestival.value.id, { reset: false })
}

async function loadLivePosts(festivalId: string) {
  livePostsLoading.value = true
  try {
    livePosts.value = await villageApi.listLivePosts(villageId.value, festivalId)
  }
  catch (error) {
    livePosts.value = []
    handleApiError(error, t('village.festival.live.loadFailed'))
  }
  finally {
    livePostsLoading.value = false
  }
}

/** 実況として投稿する。VILLAGE スコープへ投稿 → 返った投稿 ID を祭へタグ付けする（§5.4 案B）。 */
async function submitLivePost(content: string) {
  if (!detailFestival.value) return
  const festivalId = detailFestival.value.id
  livePostPosting.value = true
  try {
    const posted = await createTimelinePost({
      scopeType: 'VILLAGE',
      scopeId: 0,
      scopeVillageId: villageId.value,
      content,
    })
    if (posted?.data?.id) {
      await villageApi.tagLivePost(villageId.value, festivalId, { timelinePostId: posted.data.id })
    }
    await loadLivePosts(festivalId)
    success(t('village.festival.live.tagSuccess'))
  }
  catch (error) {
    handleApiError(error, t('village.festival.live.tagFailed'))
  }
  finally {
    livePostPosting.value = false
  }
}

// =====================================================================
// Init
// =====================================================================

onMounted(() => {
  void loadFestivals()
})
</script>

<template>
  <div>
    <VillageFestivalListSection
      :festivals="festivals"
      :festivals-loading="festivalsLoading"
      :status-filter="statusFilter"
      :status-filter-tabs="statusFilterTabs"
      :can-manage="canManage"
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

    <!-- 詳細 Dialog（F17.2 Wave2 ③ RSVP・実況 込み） -->
    <VillageFestivalDetailDialog
      v-model:visible="showDetailDialog"
      :festival="detailFestival"
      :can-manage="canManage"
      :is-villager="isVillager"
      :rsvps="rsvps"
      :my-rsvp-status="myRsvpStatus"
      :my-rsvp-role-label="myRsvpRoleLabel"
      :rsvps-loading="rsvpsLoading"
      :rsvps-has-more="rsvpsHasMore"
      :rsvps-loading-more="rsvpsLoadingMore"
      :live-posts="livePosts"
      :live-posts-loading="livePostsLoading"
      :live-post-posting="livePostPosting"
      @edit="openEditDialog"
      @cancel-festival="submitCancel"
      @respond-rsvp="respondRsvp"
      @cancel-rsvp="cancelRsvp"
      @load-more-rsvps="loadMoreRsvps"
      @submit-live-post="submitLivePost"
    />

    <!-- 編集 Dialog -->
    <VillageFestivalEditDialog
      v-model:visible="showEditDialog"
      v-model:form="editForm"
      @submit="submitEdit"
    />
  </div>
</template>
