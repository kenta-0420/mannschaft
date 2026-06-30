<script setup lang="ts">
import type {
  CirculationDocumentDetail,
  CirculationRecipient,
  CirculationComment,
  CirculationAttachment,
} from '~/types/circulation'
import type { ElectronicSeal } from '~/types/seal'
import { useTeamMembers } from '~/composables/team/useTeamMembers'

/**
 * 回覧板 詳細モーダル（③ 詳細モーダル＋押印UI）。
 *
 * <p>一覧（CirculationList）の `@select` でカードが選択されたときに開く。あて先ごとの
 * 押印状況・コメント・添付を表示し、自分があて先（PENDING）なら印鑑を選んで押印できる。
 * 別ページ遷移はしない（ダイアログ完結）。</p>
 *
 * <p>BE 応答の実形に合わせて実装している:</p>
 * <ul>
 *   <li>本体: `GET /api/v1/teams/{teamId}/circulations/{documentId}`（フラットな DocumentResponse・受信者は含まない）</li>
 *   <li>あて先: `GET /api/v1/circulations/{documentId}/recipients`（表示名なし → メンバー一覧で補完）</li>
 *   <li>コメント: `GET/POST /api/v1/circulations/{documentId}/comments`</li>
 *   <li>添付: `GET /api/v1/circulations/{documentId}/attachments`</li>
 *   <li>押印: `POST /api/v1/circulations/{documentId}/stamp`（StampRequest = {sealId, sealVariant?, tiltAngle?, isFlipped?}）</li>
 * </ul>
 */
const props = defineProps<{
  /** 表示中の回覧文書 ID。 */
  documentId: number | null
  /** スコープ（チーム）の数値 ID（詳細 EP のパス変数）。一覧 item.scopeId 由来。 */
  teamId: string
  /** チームの slug（メンバー一覧 EP・表示名解決に使用）。 */
  teamSlug: string
  /** v-model:visible。 */
  visible: boolean
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  /** 押印などでステータスが変わったとき。親が一覧を再取得できるよう通知する。 */
  stamped: []
}>()

const { t } = useI18n()
const { showError, showSuccess } = useNotification()
const { relativeTime } = useRelativeTime()
const authStore = useAuthStore()
const {
  getScopedCirculation,
  getRecipients,
  getComments,
  createComment,
  getAttachments,
  stampDocument,
} = useCirculationApi()
const { getSeals } = useSealApi()
const { getMembers } = useTeamMembers()

const currentUserId = computed(() => authStore.currentUser?.id ?? null)

const loading = ref(false)
/** 非あて先（非作成者）が開いたときの 403 を友好的に表示するためのフラグ。 */
const forbidden = ref(false)

const document = ref<CirculationDocumentDetail | null>(null)
const recipients = ref<CirculationRecipient[]>([])
const comments = ref<CirculationComment[]>([])
const attachments = ref<CirculationAttachment[]>([])
/** userId → displayName の解決マップ（メンバー一覧 EP で構築）。 */
const displayNames = ref<Record<number, string>>({})

// === 押印 UI 状態 ===
const seals = ref<ElectronicSeal[]>([])
const selectedSealId = ref<number | null>(null)
const stampComment = ref('')
const stampPanelOpen = ref(false)
const stamping = ref(false)

// === コメント投稿状態 ===
const commentBody = ref('')
const postingComment = ref(false)

/** 403 でない FetchError かどうかを判定する型ガード。 */
function isForbiddenError(err: unknown): boolean {
  const status = (err as { response?: { status?: number }, statusCode?: number })
  return status?.response?.status === 403 || status?.statusCode === 403
}

/** 現在のユーザーが「PENDING のあて先」かどうか（押印ボタンの表示条件）。 */
const myPendingRecipient = computed<CirculationRecipient | null>(() => {
  if (currentUserId.value === null) return null
  return recipients.value.find(
    r => r.userId === currentUserId.value && r.status === 'PENDING',
  ) ?? null
})

const stampedCount = computed(
  () => recipients.value.filter(r => r.status === 'STAMPED').length,
)
const totalRecipientCount = computed(() => recipients.value.length)

function displayNameFor(userId: number): string {
  return displayNames.value[userId] ?? t('circulation.detail.unknown_user')
}

function statusClass(status: string): string {
  switch (status) {
    case 'DRAFT': return 'bg-surface-100 text-surface-600'
    case 'ACTIVE': return 'bg-blue-100 text-blue-700'
    case 'COMPLETED': return 'bg-green-100 text-green-700'
    case 'CANCELLED': return 'bg-red-100 text-red-600'
    default: return 'bg-surface-100 text-surface-600'
  }
}

function statusLabel(status: string): string {
  return t(`circulation.status.${status}`)
}

function recipientStatusClass(status: string): string {
  switch (status) {
    case 'STAMPED': return 'bg-green-100 text-green-700'
    case 'SKIPPED': return 'bg-surface-200 text-surface-600'
    case 'REJECTED': return 'bg-red-100 text-red-600'
    default: return 'bg-amber-100 text-amber-700'
  }
}

function recipientStatusLabel(status: string): string {
  return t(`circulation.recipient_status.${status}`)
}

/** 表示名マップをメンバー一覧から構築する（ベストエフォート・失敗しても致命的でない）。 */
async function loadDisplayNames() {
  try {
    const res = await getMembers(props.teamSlug, { size: 200 })
    const map: Record<number, string> = {}
    for (const m of res.data) {
      map[m.userId] = m.displayName
    }
    displayNames.value = map
  } catch {
    // 表示名解決は補助情報。失敗時は「不明なユーザー」で代替する。
    displayNames.value = {}
  }
}

/** 自分の印鑑一覧を取得し、1 つだけなら自動選択する。 */
async function loadSeals() {
  if (currentUserId.value === null) return
  try {
    const list = await getSeals(currentUserId.value)
    seals.value = list
    if (list.length === 1) {
      selectedSealId.value = list[0]!.sealId
    }
  } catch {
    seals.value = []
  }
}

async function loadAll() {
  if (props.documentId === null) return
  loading.value = true
  forbidden.value = false
  document.value = null
  recipients.value = []
  comments.value = []
  attachments.value = []
  stampPanelOpen.value = false
  stampComment.value = ''
  selectedSealId.value = null
  const docId = props.documentId
  try {
    // 本体取得。非あて先（非作成者）は F00 可視性層で 403 になり得る。
    const detail = await getScopedCirculation('team', props.teamId, docId)
    document.value = detail.data
  } catch (err) {
    if (isForbiddenError(err)) {
      forbidden.value = true
      loading.value = false
      return
    }
    showError(t('circulation.detail.load_failed'))
    loading.value = false
    return
  }

  // 以降は本体取得に成功した（＝閲覧権あり）あとの付随情報。個別失敗は握りつぶさず
  // ベストエフォートで空表示にとどめる（本体が見えていれば致命的でない）。
  await Promise.all([
    loadDisplayNames(),
    loadSeals(),
    (async () => {
      try {
        const res = await getRecipients(docId)
        recipients.value = res.data
      } catch {
        recipients.value = []
      }
    })(),
    (async () => {
      try {
        const res = await getComments(docId)
        comments.value = res.data
      } catch {
        comments.value = []
      }
    })(),
    (async () => {
      try {
        const res = await getAttachments(docId)
        attachments.value = res.data
      } catch {
        attachments.value = []
      }
    })(),
  ])
  loading.value = false
}

async function submitStamp() {
  if (props.documentId === null) return
  if (selectedSealId.value === null) {
    showError(t('circulation.detail.stamp.select_seal'))
    return
  }
  stamping.value = true
  try {
    const seal = seals.value.find(s => s.sealId === selectedSealId.value)
    await stampDocument(props.documentId, {
      sealId: selectedSealId.value,
      sealVariant: seal?.variant,
      tiltAngle: 0,
      isFlipped: false,
    })
    // 押印任意コメントが入力されていればコメントとして添える。
    const trimmed = stampComment.value.trim()
    if (trimmed.length > 0) {
      try {
        await createComment(props.documentId, trimmed)
      } catch {
        // コメント添付の失敗は押印自体の成功を覆さない（押印は完了済み）。
      }
    }
    showSuccess(t('circulation.detail.stamp.success'))
    stampPanelOpen.value = false
    stampComment.value = ''
    // 状況を再取得して STAMPED 反映・進捗更新する。
    await reloadAfterStamp()
    emit('stamped')
  } catch {
    showError(t('circulation.detail.stamp.failed'))
  } finally {
    stamping.value = false
  }
}

/** 押印後に本体・あて先・コメントを取り直して STAMPED/COMPLETED を反映する。 */
async function reloadAfterStamp() {
  if (props.documentId === null) return
  const docId = props.documentId
  try {
    const detail = await getScopedCirculation('team', props.teamId, docId)
    document.value = detail.data
  } catch {
    // 本体再取得失敗は無視（直前まで見えていた本体を保持）。
  }
  try {
    const res = await getRecipients(docId)
    recipients.value = res.data
  } catch {
    // あて先再取得失敗は無視。
  }
  try {
    const res = await getComments(docId)
    comments.value = res.data
  } catch {
    // コメント再取得失敗は無視。
  }
}

async function submitComment() {
  if (props.documentId === null) return
  const body = commentBody.value.trim()
  if (body.length === 0) return
  postingComment.value = true
  try {
    await createComment(props.documentId, body)
    commentBody.value = ''
    const res = await getComments(props.documentId)
    comments.value = res.data
  } catch {
    showError(t('circulation.detail.comment.failed'))
  } finally {
    postingComment.value = false
  }
}

function close() {
  emit('update:visible', false)
}

// visible が true になり documentId が確定したら取得を開始する。
watch(
  () => [props.visible, props.documentId] as const,
  ([isVisible, docId]) => {
    if (isVisible && docId !== null) {
      loadAll()
    }
  },
  { immediate: true },
)
</script>

<template>
  <Dialog
    :visible="visible"
    :header="t('circulation.detail.title')"
    :modal="true"
    :closable="true"
    :style="{ width: '640px', maxWidth: '95vw' }"
    :draggable="false"
    @update:visible="close"
  >
    <!-- 403: 非あて先（非作成者）が開いた場合 -->
    <div v-if="forbidden" class="py-10 text-center">
      <i class="pi pi-lock mb-3 text-4xl text-surface-300" />
      <p class="text-surface-500">{{ t('circulation.detail.forbidden') }}</p>
      <Button
        :label="t('circulation.detail.close')"
        text
        class="mt-4"
        @click="close"
      />
    </div>

    <div v-else-if="loading" class="flex justify-center py-12">
      <LoadingBounce />
    </div>

    <div v-else-if="document" class="flex flex-col gap-5 py-1">
      <!-- ヘッダー: ステータス・タイトル・締切・進捗 -->
      <div>
        <div class="mb-1 flex items-center gap-2">
          <span
            :class="statusClass(document.status)"
            class="rounded px-2 py-0.5 text-xs font-medium"
          >
            {{ statusLabel(document.status) }}
          </span>
          <h2 class="text-base font-semibold">{{ document.title }}</h2>
        </div>
        <div class="flex flex-wrap items-center gap-3 text-xs text-surface-400">
          <span v-if="document.createdByName">
            {{ t('circulation.detail.created_by', { name: document.createdByName }) }}
          </span>
          <span>{{ relativeTime(document.createdAt) }}</span>
          <span v-if="document.dueDate">
            <i class="pi pi-clock" /> {{ t('circulation.detail.due', { date: document.dueDate }) }}
          </span>
        </div>
      </div>

      <!-- 進捗 -->
      <div class="flex items-center gap-2 text-sm">
        <i class="pi pi-check-circle text-green-600" />
        <span class="font-medium">{{ stampedCount }} / {{ totalRecipientCount }}</span>
        <span class="text-surface-400">{{ t('circulation.detail.progress') }}</span>
      </div>

      <!-- 本文 -->
      <div v-if="document.body" class="whitespace-pre-wrap rounded-lg bg-surface-50 p-3 text-sm">
        {{ document.body }}
      </div>

      <!-- 押印パネル（自分が PENDING のあて先のときのみ） -->
      <div
        v-if="myPendingRecipient"
        class="rounded-lg border border-primary-200 bg-primary-50 p-3"
      >
        <Button
          v-if="!stampPanelOpen"
          :label="t('circulation.detail.stamp.open')"
          icon="pi pi-pencil"
          @click="stampPanelOpen = true"
        />
        <div v-else class="flex flex-col gap-3">
          <p class="text-sm font-medium">{{ t('circulation.detail.stamp.choose_seal') }}</p>

          <div v-if="seals.length === 0" class="text-sm text-surface-500">
            {{ t('circulation.detail.stamp.no_seal') }}
          </div>
          <div v-else class="grid gap-2 sm:grid-cols-3">
            <button
              v-for="seal in seals"
              :key="seal.sealId"
              type="button"
              class="flex flex-col items-center rounded-lg border p-2 transition-colors"
              :class="selectedSealId === seal.sealId
                ? 'border-primary-500 bg-primary-100'
                : 'border-surface-300 bg-surface-0 hover:border-primary-300'"
              @click="selectedSealId = seal.sealId"
            >
              <!-- eslint-disable-next-line vue/no-v-html -->
              <div class="mb-1 flex h-16 w-16 items-center justify-center" v-html="sanitizeHtml(seal.svgData, { allowSvg: true })" />
              <span class="text-xs">{{ seal.displayText }}</span>
            </button>
          </div>

          <Textarea
            v-model="stampComment"
            :placeholder="t('circulation.detail.stamp.comment_placeholder')"
            rows="2"
            auto-resize
            class="w-full text-sm"
          />

          <div class="flex justify-end gap-2">
            <Button
              :label="t('circulation.detail.cancel')"
              text
              :disabled="stamping"
              @click="stampPanelOpen = false"
            />
            <Button
              :label="t('circulation.detail.stamp.submit')"
              icon="pi pi-check"
              :loading="stamping"
              :disabled="selectedSealId === null || seals.length === 0"
              @click="submitStamp"
            />
          </div>
        </div>
      </div>

      <!-- あて先リスト -->
      <div>
        <h3 class="mb-2 text-sm font-semibold">{{ t('circulation.detail.recipients') }}</h3>
        <div v-if="recipients.length === 0" class="text-sm text-surface-400">
          {{ t('circulation.detail.no_recipients') }}
        </div>
        <ul v-else class="flex flex-col gap-1">
          <li
            v-for="r in recipients"
            :key="r.id"
            class="flex items-center justify-between rounded-lg border border-surface-200 px-3 py-2 text-sm"
          >
            <span class="truncate">{{ displayNameFor(r.userId) }}</span>
            <span class="flex items-center gap-2">
              <span
                :class="recipientStatusClass(r.status)"
                class="rounded px-2 py-0.5 text-xs font-medium"
              >
                {{ recipientStatusLabel(r.status) }}
              </span>
              <span v-if="r.stampedAt" class="text-xs text-surface-400">
                {{ relativeTime(r.stampedAt) }}
              </span>
            </span>
          </li>
        </ul>
      </div>

      <!-- 添付 -->
      <div v-if="attachments.length > 0">
        <h3 class="mb-2 text-sm font-semibold">{{ t('circulation.detail.attachments') }}</h3>
        <ul class="flex flex-col gap-1">
          <li
            v-for="a in attachments"
            :key="a.id"
            class="flex items-center gap-2 rounded-lg border border-surface-200 px-3 py-2 text-sm"
          >
            <i class="pi pi-paperclip text-surface-400" />
            <span class="truncate">{{ a.originalFilename ?? a.fileKey }}</span>
          </li>
        </ul>
      </div>

      <!-- コメント -->
      <div>
        <h3 class="mb-2 text-sm font-semibold">{{ t('circulation.detail.comments') }}</h3>
        <div v-if="comments.length === 0" class="mb-2 text-sm text-surface-400">
          {{ t('circulation.detail.no_comments') }}
        </div>
        <ul v-else class="mb-3 flex flex-col gap-2">
          <li
            v-for="c in comments"
            :key="c.id"
            class="rounded-lg bg-surface-50 px-3 py-2 text-sm"
          >
            <div class="mb-0.5 flex items-center gap-2 text-xs text-surface-400">
              <span class="font-medium text-surface-600">{{ displayNameFor(c.userId) }}</span>
              <span>{{ relativeTime(c.createdAt) }}</span>
            </div>
            <p class="whitespace-pre-wrap">{{ c.body }}</p>
          </li>
        </ul>
        <div class="flex flex-col gap-2">
          <Textarea
            v-model="commentBody"
            :placeholder="t('circulation.detail.comment.placeholder')"
            rows="2"
            auto-resize
            class="w-full text-sm"
          />
          <div class="flex justify-end">
            <Button
              :label="t('circulation.detail.comment.submit')"
              icon="pi pi-send"
              size="small"
              :loading="postingComment"
              :disabled="commentBody.trim().length === 0"
              @click="submitComment"
            />
          </div>
        </div>
      </div>
    </div>
  </Dialog>
</template>
