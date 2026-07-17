<script setup lang="ts">
/**
 * F17.1 村機能 — 村参加申請ページ（永続シェル子）
 *
 * 設計書: docs/features/F17.1_village_community.md §4.5
 * Backend Controller: VillageJoinRequestController
 *   - POST /api/v1/villages/{villageId}/join-requests            申請作成
 *   - GET  /api/v1/villages/{villageId}/join-requests            申請一覧（村長/長老）
 *   - POST /api/v1/villages/{villageId}/join-requests/{id}/approve|reject|withdraw
 *
 * # 導線
 *  VillageHeader の「参加する」ボタンは joinPolicy が FREE 以外（= APPROVAL）のとき
 *  `requestJoin` を emit し、親 `pages/villages/[id].vue` が本ページへ遷移させる。
 *  本ページが存在しないため 404 になっていた導線を解消するのが主目的。
 *
 * # 永続シェル方式（SPA）
 *  村データ・権限・VillageHeader は親 `pages/villages/[id].vue` が解決・常駐描画する。
 *  本ファイルは `useVillageContext()` で inject し、村の再フェッチは行わない。
 *  親シェルの middleware は「末尾セグメント === id」のときだけ bulletin へリダイレクトするため、
 *  `/villages/{id}/join-request` は素通りして本ページが子として描画される。
 *  VillageHeader の activeTab には新タブを追加しない（newsletter-settings.vue と同じ方針。
 *  未知セグメントは bulletin ハイライト既定に落ちる）。
 *
 * # 画面構成（相互排他）
 *  1. FREE 村           … 申請不要の案内（BE は VILLAGE_041 で拒否する）
 *  2. 村長 / 長老        … 参加申請の審査一覧（承認・却下）
 *  3. 非メンバー         … 申請フォーム（送信後は申請済みパネル＋取下げ）
 *  4. 村人（審査権限なし）… すでに村人である旨の案内（BE は VILLAGE_006 で拒否する）
 */
import type {
  JoinRequestCreateRequest,
  JoinRequestResponse,
  VillageRequestStatus,
} from '~/types/village'
import { useVillageContext } from '~/composables/useVillageContext'

// auth は各タブで明示宣言（本コードベースの規約。親シェルも auth を持つ）。
definePageMeta({ middleware: 'auth' })

const route = useRoute()
const { t } = useI18n()
const {
  createJoinRequest,
  listMyJoinRequests,
  listJoinRequests,
  approveJoinRequest,
  rejectJoinRequest,
  withdrawJoinRequest,
} = useVillageMembershipApi()
const { showSuccess, showError, showWarn } = useNotification()
const { formatDateTime } = useDatetime()

const villageId = computed<string>(() => String(route.params.id))

// 村本体・権限・ユーザー id は親シェルから inject（再フェッチしない）
const { village, perms, currentUserId, refresh } = useVillageContext()

// =============================================================================
// 定数
// =============================================================================

/** 志望動機の長さ上限（BE: JoinRequestCreateRequest#message @Size(max = 500)） */
const MESSAGE_MAX = 500
/** 審査コメントの長さ上限（BE: JoinRequestReviewRequest#reviewComment @Size(max = 500)） */
const REVIEW_COMMENT_MAX = 500
const PAGE_SIZE = 20

/**
 * 審査一覧のステータス絞り込み。
 * BE は status 未指定時に PENDING で絞り込むため「全件」は提供されていない（VillageJoinRequestService#listForReviewers）。
 * したがって「ALL」タブは設けず、常に具体的な status を送る。
 */
const STATUS_FILTERS: VillageRequestStatus[] = ['PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN']

// =============================================================================
// 画面モード
// =============================================================================

/** FREE 村は参加申請を受け付けない（BE: VILLAGE_041）。直接参加すべき。 */
const isFreeVillage = computed<boolean>(() => village.value?.joinPolicy === 'FREE')

/** 審査権限（村長 HEADMAN / 長老 ELDER）を持つか。 */
const isReviewer = computed<boolean>(() => perms.value.isAdmin)

// =============================================================================
// エラー抽出（members.vue / create-request.vue と同形）
// =============================================================================

interface ApiErrorBody {
  errorCode?: string
  message?: string
  code?: string
}

interface ApiErrorEnvelope {
  data?: ApiErrorBody
  status?: number
  statusCode?: number
  response?: { status?: number, _data?: ApiErrorBody }
}

function extractApiError(err: unknown): { code: string | null, status: number | null } {
  if (typeof err !== 'object' || err === null) {
    return { code: null, status: null }
  }
  const e = err as ApiErrorEnvelope
  const body: ApiErrorBody | undefined = e.data ?? e.response?._data
  const code = body?.errorCode ?? body?.code ?? null
  const status = e.status ?? e.statusCode ?? e.response?.status ?? null
  return { code, status }
}

function translateApiError(code: string | null, status: number | null): string {
  if (status === 429) return t('village.error.VILLAGE_009')
  if (code && code.startsWith('VILLAGE_')) {
    const key = `village.error.${code}`
    const msg = t(key)
    // i18n がキー未定義のときキー文字列をそのまま返すケースに備えて fallback
    if (msg && msg !== key) return msg
  }
  return t('village.error.generic')
}

// =============================================================================
// 申請フォーム（非メンバー向け）
// =============================================================================

const formMessage = ref('')
const submitting = ref(false)

/**
 * 審査待ちの自分の申請（取下げ導線の表示に使う）。
 *
 * BE の `GET /join-requests/me` から復元するため、**リロードしても状態が残る**。
 * （旧実装は「送信直後のレスポンスをメモリに保持」する暫定対応で、リロードすると
 *   取下げ導線が消えていた。BE 側に申請者向け EP を新設して根治済み。）
 */
const myPendingRequest = ref<JoinRequestResponse | null>(null)
const myRequestLoading = ref(false)

const messageError = computed<string | null>(() => {
  if (formMessage.value.length > MESSAGE_MAX) return t('village.error.VILLAGE_029')
  return null
})

const canSubmit = computed<boolean>(() => {
  if (submitting.value) return false
  if (messageError.value) return false
  // 主体は常にログインユーザー自身（USER）。id が無い＝未ログインは送信不可。
  return currentUserId.value != null
})

/**
 * 自分の審査待ち申請を BE から復元する（リロード後も取下げ導線を出すため）。
 *
 * BE は申請の履歴（APPROVED/REJECTED/WITHDRAWN 含む）を createdAt 降順で返すため、
 * 取下げ可能な PENDING だけを拾う（PENDING は同時に 1 件しか存在しない）。
 */
async function loadMyRequest() {
  // FREE 村は申請の概念が無い。村人・審査者はこのパネルを使わないので呼ばない。
  if (isFreeVillage.value || perms.value.isMember) return
  myRequestLoading.value = true
  try {
    const mine = await listMyJoinRequests(villageId.value)
    myPendingRequest.value = mine.find(r => r.status === 'PENDING') ?? null
  }
  catch (err) {
    const { code, status } = extractApiError(err)
    showError(translateApiError(code, status))
  }
  finally {
    myRequestLoading.value = false
  }
}

async function submit() {
  const myUserId = currentUserId.value
  if (!canSubmit.value || myUserId == null) return
  submitting.value = true
  try {
    const body: JoinRequestCreateRequest = {
      // 設計書 §4.5: 本画面から申請できるのは常にログインユーザー本人（USER）。
      // TEAM / ORGANIZATION としての申請は代表権限検証が必要なため本画面では扱わない。
      subjectType: 'USER',
      subjectId: myUserId,
      message: formMessage.value.trim() === '' ? null : formMessage.value.trim(),
    }
    myPendingRequest.value = await createJoinRequest(villageId.value, body)
    formMessage.value = ''
    showSuccess(t('village.joinRequest.submitted'))
  }
  catch (err) {
    const { code, status } = extractApiError(err)
    // すでに村人 / 審査待ち重複 / FREE 村は「操作ミス」であってシステム障害ではないため警告表示。
    if (code === 'VILLAGE_006' || code === 'VILLAGE_039' || code === 'VILLAGE_041') {
      showWarn(translateApiError(code, status))
      // 村人になっていた場合は親シェルの状態を同期して画面モードを正す
      if (code === 'VILLAGE_006') await refresh()
      // 審査待ち重複（別タブ等で既に申請済み）は BE の実状態を取り込んで画面を正す
      if (code === 'VILLAGE_039') await loadMyRequest()
    }
    else {
      showError(translateApiError(code, status))
    }
  }
  finally {
    submitting.value = false
  }
}

/** 審査待ちの自分の申請を取り下げる（申請者本人のみ・PENDING のみ）。 */
async function withdrawMine() {
  const req = myPendingRequest.value
  if (!req || submitting.value) return
  submitting.value = true
  try {
    await withdrawJoinRequest(villageId.value, req.id)
    myPendingRequest.value = null
    showSuccess(t('village.joinRequest.withdrawSuccess'))
  }
  catch (err) {
    const { code, status } = extractApiError(err)
    showError(translateApiError(code, status))
    // 取下げ失敗（既に審査済み等）は BE の実状態へ寄せる
    await loadMyRequest()
  }
  finally {
    submitting.value = false
  }
}

// =============================================================================
// 審査一覧（村長 / 長老向け）— listJoinRequests の最初の消費者
// =============================================================================

const statusFilter = ref<VillageRequestStatus>('PENDING')
const requests = ref<JoinRequestResponse[]>([])
const listLoading = ref(false)
const totalElements = ref(0)
const page = ref(0)

async function loadRequests() {
  if (!isReviewer.value) return
  listLoading.value = true
  try {
    const res = await listJoinRequests(villageId.value, statusFilter.value, {
      page: page.value,
      size: PAGE_SIZE,
    })
    requests.value = res.content
    totalElements.value = res.totalElements
  }
  catch (err) {
    requests.value = []
    totalElements.value = 0
    const { code, status } = extractApiError(err)
    showError(translateApiError(code, status))
  }
  finally {
    listLoading.value = false
  }
}

function onPageChange(event: { page: number }) {
  page.value = event.page
  void loadRequests()
}

watch(statusFilter, () => {
  page.value = 0
  void loadRequests()
})

// =============================================================================
// 承認 / 却下 Dialog
// =============================================================================

const approveVisible = ref(false)
const approveTarget = ref<JoinRequestResponse | null>(null)
const approveComment = ref('')

const rejectVisible = ref(false)
const rejectTarget = ref<JoinRequestResponse | null>(null)
const rejectComment = ref('')
const rejectError = ref<string | null>(null)

const reviewing = ref(false)

function openApprove(req: JoinRequestResponse) {
  approveTarget.value = req
  approveComment.value = ''
  approveVisible.value = true
}

function openReject(req: JoinRequestResponse) {
  rejectTarget.value = req
  rejectComment.value = ''
  rejectError.value = null
  rejectVisible.value = true
}

async function submitApprove() {
  const target = approveTarget.value
  if (!target || reviewing.value) return
  reviewing.value = true
  try {
    await approveJoinRequest(villageId.value, target.id, {
      reviewComment: approveComment.value.trim() === '' ? null : approveComment.value.trim(),
    })
    showSuccess(t('village.joinRequest.approveSuccess'))
    approveVisible.value = false
    await loadRequests()
  }
  catch (err) {
    const { code, status } = extractApiError(err)
    showError(translateApiError(code, status))
  }
  finally {
    reviewing.value = false
  }
}

async function submitReject() {
  const target = rejectTarget.value
  if (!target || reviewing.value) return
  const trimmed = rejectComment.value.trim()
  // BE: reject は reviewComment 必須（VillageJoinRequestService#reject → COMMON_001）。
  if (!trimmed) {
    rejectError.value = t('village.joinRequest.rejectCommentRequired')
    return
  }
  if (trimmed.length > REVIEW_COMMENT_MAX) {
    rejectError.value = t('village.error.VILLAGE_029')
    return
  }
  reviewing.value = true
  try {
    await rejectJoinRequest(villageId.value, target.id, { reviewComment: trimmed })
    showSuccess(t('village.joinRequest.rejectSuccess'))
    rejectVisible.value = false
    await loadRequests()
  }
  catch (err) {
    const { code, status } = extractApiError(err)
    showError(translateApiError(code, status))
  }
  finally {
    reviewing.value = false
  }
}

// =============================================================================
// 表示ヘルパ
// =============================================================================

function statusLabel(status: VillageRequestStatus): string {
  switch (status) {
    case 'PENDING':
      return t('village.joinRequest.pending')
    case 'APPROVED':
      return t('village.joinRequest.approved')
    case 'REJECTED':
      return t('village.joinRequest.rejected')
    case 'WITHDRAWN':
      return t('village.joinRequest.withdrawn')
  }
}

function statusSeverity(status: VillageRequestStatus): 'warn' | 'success' | 'danger' | 'secondary' {
  switch (status) {
    case 'PENDING':
      return 'warn'
    case 'APPROVED':
      return 'success'
    case 'REJECTED':
      return 'danger'
    case 'WITHDRAWN':
      return 'secondary'
  }
}

function filterLabel(s: VillageRequestStatus): string {
  return statusLabel(s)
}

// =============================================================================
// 使い方モーダル
// =============================================================================

const showGuide = ref(false)

// =============================================================================
// 初期化
// =============================================================================

onMounted(() => {
  void loadRequests()
  void loadMyRequest()
})

// 親シェルは村取得をクライアントで行うため、権限・joinPolicy の確定が本ページのマウント後に
// なりうる。村が解決した時点で、その時のモードに応じた取得を行う。
watch(village, (v) => {
  if (!v) return
  void loadRequests()
  void loadMyRequest()
})
</script>

<template>
  <div class="mx-auto max-w-3xl p-6">
    <PageHeader
      :title="t('village.joinRequest.title')"
      size="sm"
      help
      @help="showGuide = true"
    />

    <!-- 1. FREE 村: 申請不要（BE は VILLAGE_041 で拒否する） -->
    <Message v-if="isFreeVillage" severity="info" :closable="false">
      {{ t('village.error.VILLAGE_041') }}
    </Message>

    <!-- 2. 村長 / 長老: 審査一覧 -->
    <div v-else-if="isReviewer" class="flex flex-col gap-4">
      <p class="text-sm text-surface-600 dark:text-surface-300">
        {{ t('village.joinRequest.reviewDescription') }}
      </p>

      <Tabs v-model:value="statusFilter">
        <TabList>
          <Tab v-for="s in STATUS_FILTERS" :key="s" :value="s">
            {{ filterLabel(s) }}
          </Tab>
        </TabList>
      </Tabs>

      <DataTable
        :value="requests"
        :loading="listLoading"
        data-key="id"
        striped-rows
        lazy
        paginator
        :rows="PAGE_SIZE"
        :total-records="totalElements"
        :first="page * PAGE_SIZE"
        responsive-layout="scroll"
        data-testid="join-request-review-table"
        @page="onPageChange"
      >
        <template #empty>
          <div class="py-12 text-center">
            <i class="pi pi-inbox mb-3 text-4xl text-surface-300" aria-hidden="true" />
            <p class="text-sm text-surface-400">
              {{ t('village.joinRequest.empty') }}
            </p>
          </div>
        </template>

        <Column :header="t('village.joinRequest.column.requester')" style="width: 120px">
          <template #body="{ data }">
            <span class="text-sm">#{{ (data as JoinRequestResponse).subjectId }}</span>
          </template>
        </Column>

        <Column :header="t('village.joinRequest.column.message')">
          <template #body="{ data }">
            <span class="whitespace-pre-wrap text-sm">
              {{ (data as JoinRequestResponse).message || '—' }}
            </span>
          </template>
        </Column>

        <Column :header="t('village.field.createdAt')" style="width: 160px">
          <template #body="{ data }">
            <span class="text-sm">{{ formatDateTime((data as JoinRequestResponse).createdAt) }}</span>
          </template>
        </Column>

        <Column :header="t('village.joinRequest.column.status')" style="width: 110px">
          <template #body="{ data }">
            <Tag
              :value="statusLabel((data as JoinRequestResponse).status)"
              :severity="statusSeverity((data as JoinRequestResponse).status)"
            />
          </template>
        </Column>

        <Column :header="t('village.joinRequest.column.actions')" style="width: 180px">
          <template #body="{ data }">
            <div v-if="(data as JoinRequestResponse).status === 'PENDING'" class="flex gap-2">
              <Button
                :label="t('village.action.approve')"
                icon="pi pi-check"
                size="small"
                severity="success"
                @click="openApprove(data as JoinRequestResponse)"
              />
              <Button
                :label="t('village.action.reject')"
                icon="pi pi-times"
                size="small"
                severity="danger"
                @click="openReject(data as JoinRequestResponse)"
              />
            </div>
            <span v-else class="text-xs text-surface-400">—</span>
          </template>
        </Column>
      </DataTable>
    </div>

    <!-- 3. 非メンバー: 申請フォーム / 申請済みパネル -->
    <div v-else-if="!perms.isMember" class="flex flex-col gap-4">
      <!-- 3-0. 自分の申請の復元中（リロード直後にフォームが一瞬見えるのを防ぐ） -->
      <div v-if="myRequestLoading" class="py-12 text-center text-surface-500">
        <i class="pi pi-spin pi-spinner text-2xl" aria-hidden="true" />
      </div>

      <!-- 3-b. 審査待ち（GET /join-requests/me から復元するためリロードしても残る） -->
      <SectionCard
        v-else-if="myPendingRequest"
        :title="t('village.joinRequest.submittedTitle')"
      >
        <div class="flex flex-col gap-4">
          <div class="flex items-center gap-2">
            <Tag
              :value="statusLabel(myPendingRequest.status)"
              :severity="statusSeverity(myPendingRequest.status)"
            />
            <span class="text-sm text-surface-500">
              {{ formatDateTime(myPendingRequest.createdAt) }}
            </span>
          </div>
          <p class="text-sm text-surface-600 dark:text-surface-300">
            {{ t('village.joinRequest.submittedBody') }}
          </p>
          <div v-if="myPendingRequest.message" class="rounded-lg bg-surface-50 p-3 dark:bg-surface-900">
            <p class="whitespace-pre-wrap text-sm">
              {{ myPendingRequest.message }}
            </p>
          </div>
          <div class="flex justify-end">
            <Button
              :label="t('village.action.withdraw')"
              icon="pi pi-undo"
              severity="secondary"
              outlined
              :loading="submitting"
              data-testid="join-request-withdraw"
              @click="withdrawMine"
            />
          </div>
        </div>
      </SectionCard>

      <!-- 3-a. 申請フォーム -->
      <SectionCard v-else :title="t('village.joinRequest.formTitle')">
        <form class="flex flex-col gap-4" @submit.prevent="submit">
          <p class="text-sm text-surface-600 dark:text-surface-300">
            {{ t('village.joinRequest.description') }}
          </p>

          <div>
            <label for="join-request-message" class="mb-1 block text-sm font-medium">
              {{ t('village.joinRequest.message') }}
            </label>
            <Textarea
              id="join-request-message"
              v-model="formMessage"
              :maxlength="MESSAGE_MAX"
              :placeholder="t('village.joinRequest.messagePlaceholder')"
              :auto-resize="true"
              rows="4"
              class="w-full"
              :invalid="!!messageError"
              data-testid="join-request-message"
            />
            <p v-if="messageError" class="mt-1 text-xs text-red-600">
              {{ messageError }}
            </p>
            <p class="mt-1 text-xs text-surface-500">
              {{ formMessage.length }} / {{ MESSAGE_MAX }}
            </p>
          </div>

          <div class="flex justify-end">
            <Button
              type="submit"
              :label="t('village.action.submit')"
              icon="pi pi-send"
              :disabled="!canSubmit"
              :loading="submitting"
              data-testid="join-request-submit"
            />
          </div>
        </form>
      </SectionCard>
    </div>

    <!-- 4. 村人（審査権限なし）: すでにメンバー（BE は VILLAGE_006 で拒否する） -->
    <Message v-else severity="info" :closable="false">
      {{ t('village.error.VILLAGE_006') }}
    </Message>

    <!-- 承認 Dialog -->
    <Dialog
      v-model:visible="approveVisible"
      :header="t('village.action.approve')"
      modal
      class="w-full max-w-md"
    >
      <div v-if="approveTarget" class="flex flex-col gap-4">
        <p class="text-sm">
          {{ t('village.joinRequest.confirm.approve') }}
        </p>
        <div class="rounded-lg bg-surface-50 p-3 dark:bg-surface-900">
          <p class="text-sm font-medium">
            #{{ approveTarget.subjectId }}
          </p>
          <p class="whitespace-pre-wrap text-xs text-surface-500">
            {{ approveTarget.message || '—' }}
          </p>
        </div>
        <div>
          <label for="approve-comment" class="mb-1 block text-sm font-medium">
            {{ t('village.joinRequest.reviewComment') }}
          </label>
          <Textarea
            id="approve-comment"
            v-model="approveComment"
            :maxlength="REVIEW_COMMENT_MAX"
            :placeholder="t('village.joinRequest.reviewCommentPlaceholder')"
            rows="3"
            class="w-full"
          />
        </div>
      </div>
      <template #footer>
        <Button
          :label="t('village.action.cancel')"
          severity="secondary"
          :disabled="reviewing"
          @click="approveVisible = false"
        />
        <Button
          :label="t('village.action.approve')"
          severity="success"
          :loading="reviewing"
          @click="submitApprove"
        />
      </template>
    </Dialog>

    <!-- 却下 Dialog（審査コメント必須） -->
    <Dialog
      v-model:visible="rejectVisible"
      :header="t('village.action.reject')"
      modal
      class="w-full max-w-md"
    >
      <div v-if="rejectTarget" class="flex flex-col gap-4">
        <p class="text-sm">
          {{ t('village.joinRequest.confirm.reject') }}
        </p>
        <div class="rounded-lg bg-surface-50 p-3 dark:bg-surface-900">
          <p class="text-sm font-medium">
            #{{ rejectTarget.subjectId }}
          </p>
          <p class="whitespace-pre-wrap text-xs text-surface-500">
            {{ rejectTarget.message || '—' }}
          </p>
        </div>
        <div>
          <label for="reject-comment" class="mb-1 block text-sm font-medium">
            {{ t('village.joinRequest.reviewComment') }}
            <span class="text-red-600">*</span>
          </label>
          <Textarea
            id="reject-comment"
            v-model="rejectComment"
            :maxlength="REVIEW_COMMENT_MAX"
            :placeholder="t('village.joinRequest.reviewCommentPlaceholder')"
            rows="4"
            class="w-full"
            :invalid="!!rejectError"
          />
          <p v-if="rejectError" class="mt-1 text-xs text-red-600">
            {{ rejectError }}
          </p>
        </div>
      </div>
      <template #footer>
        <Button
          :label="t('village.action.cancel')"
          severity="secondary"
          :disabled="reviewing"
          @click="rejectVisible = false"
        />
        <Button
          :label="t('village.action.reject')"
          severity="danger"
          :loading="reviewing"
          @click="submitReject"
        />
      </template>
    </Dialog>

    <!-- 使い方モーダル -->
    <VillageJoinRequestGuideModal v-model:visible="showGuide" />
  </div>
</template>
