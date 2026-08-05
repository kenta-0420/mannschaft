<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

const { t: $t } = useI18n()
const route = useRoute()
const router = useRouter()
const postId = Number(route.params.id)

const { getMyPost, updateMyPost, publishMyPost, selfReviewPost, autoSave } = useBlogApi()
const { success, info, error: showError } = useNotification()
const authStore = useAuthStore()
const api = useApi()
const { buildOffsetDateTimeStr } = useDatetime()

const title = ref(route.query.title ? String(route.query.title) : '')
const body = ref('')
const status = ref('DRAFT')
const scopeType = ref<string | null>(route.query.scopeType ? String(route.query.scopeType) : null)
const scopeId = ref<string | null>(route.query.scopeId ? String(route.query.scopeId) : null)
const rejectionReason = ref<string | null>(null)
const loading = ref(true)
const saving = ref(false)
const publishing = ref(false)
const selfReviewing = ref(false)
const adminReviewing = ref(false)

// 予約公開
const scheduledAt = ref<Date | null>(null)

// 管理者承認却下
const showRejectionInput = ref(false)
const rejectionReasonInput = ref('')

// 自動保存
const AUTO_SAVE_STORAGE_KEY = 'blog-autosave-enabled'
const AUTO_SAVE_INTERVAL_MS = 30_000
const autoSaveEnabled = ref(true)
const lastAutoSavedAt = ref<Date | null>(null)
let autoSaveTimer: ReturnType<typeof setInterval> | null = null

function formatTime(date: Date): string {
  return date.toLocaleTimeString('ja-JP', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

function onAutoSaveToggle() {
  if (typeof localStorage !== 'undefined') {
    localStorage.setItem(AUTO_SAVE_STORAGE_KEY, String(autoSaveEnabled.value))
  }
  if (autoSaveEnabled.value) {
    info($t('blog.autoSave.enabled'))
  } else {
    info($t('blog.autoSave.disabled'))
  }
}

async function runAutoSave() {
  if (!autoSaveEnabled.value || saving.value || !postId) return
  if (typeof document !== 'undefined' && document.visibilityState === 'hidden') return
  try {
    await autoSave(postId, { title: title.value, body: body.value || '.', excerpt: null, version: null })
    lastAutoSavedAt.value = new Date()
    info($t('blog.autoSave.saved'))
  } catch {
    console.warn('[blog] 自動保存に失敗しました')
  }
}

// お知らせウィジェット表示フラグ（チーム/組織スコープのみ有効）
const displayInAnnouncement = ref(false)
const isTeamOrOrgScope = computed(
  () => (scopeType.value === 'TEAM' || scopeType.value === 'ORGANIZATION') && !!scopeId.value,
)

function onMediaInserted(markdownText: string) {
  body.value = body.value ? `${body.value}\n\n${markdownText}` : markdownText
}

function publishRedirectPath(): string {
  if (scopeType.value === 'TEAM' && scopeId.value) return `/teams/${scopeId.value}/blog`
  if (scopeType.value === 'ORGANIZATION' && scopeId.value) return `/organizations/${scopeId.value}`
  return '/dashboard'
}

async function load() {
  loading.value = true
  try {
    const res = await getMyPost(postId)
    const post = res.data
    title.value = post.content?.title ?? ''
    const rawBody = post.content?.body ?? ''
    body.value = rawBody === '.' ? '' : rawBody
    status.value = post.meta?.status ?? 'DRAFT'
    scopeType.value = post.scope?.organizationId ? 'ORGANIZATION' : post.scope?.teamId ? 'TEAM' : null
    const rawScopeId = post.scope?.organizationId ?? post.scope?.teamId ?? null
    scopeId.value = rawScopeId != null ? String(rawScopeId) : null
    rejectionReason.value = (post as unknown as Record<string, unknown>).rejectionReason as string | null ?? null
  } catch (err) {
    console.error('[blog] 記事読み込みに失敗しました:', err)
    showError($t('blog.post.loadFailed'))
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!title.value.trim()) return
  saving.value = true
  try {
    await updateMyPost(postId, {
      title: title.value,
      body: body.value || '.',
    })
    success('保存しました')
  } catch {
    showError('保存に失敗しました')
  } finally {
    saving.value = false
  }
}

// 今すぐ公開 / 予約公開
async function publish() {
  await save()
  publishing.value = true
  try {
    // BE の PostStatus に SCHEDULED は無い。予約公開も status=PUBLISHED + 未来の publishedAt。
    const publishBody = buildBlogPublishBody(scheduledAt.value, (d) => buildOffsetDateTimeStr(d))
    await publishMyPost(postId, publishBody)
    status.value = 'PUBLISHED'

    if (scheduledAt.value) {
      success('記事を予約公開しました')
    } else {
      // お知らせウィジェットに表示する場合、公開後に登録
      if (displayInAnnouncement.value && isTeamOrOrgScope.value && scopeId.value) {
        const { createAnnouncement } = useAnnouncementFeed(
          scopeType.value as 'TEAM' | 'ORGANIZATION',
          scopeId.value,
        )
        await createAnnouncement({
          sourceType: 'BLOG_POST',
          sourceId: postId,
        }).catch(() => {
          showError('お知らせへの登録に失敗しました。後から手動で登録してください。')
        })
      }
      success('記事を公開しました')
      await navigateTo(publishRedirectPath())
    }
  } catch {
    showError('公開に失敗しました')
  } finally {
    publishing.value = false
  }
}

// セルフレビュー: 公開申請
async function selfReviewPublish() {
  selfReviewing.value = true
  try {
    await selfReviewPost(postId, { action: 'PUBLISH' })
    status.value = 'PENDING_REVIEW'
    success('公開申請しました')
  } catch {
    showError('公開申請に失敗しました')
  } finally {
    selfReviewing.value = false
  }
}

// セルフレビュー: 下書きに戻す
async function selfReviewDraft() {
  selfReviewing.value = true
  try {
    await selfReviewPost(postId, { action: 'DRAFT' })
    status.value = 'DRAFT'
    success('下書きに戻しました')
  } catch {
    showError('操作に失敗しました')
  } finally {
    selfReviewing.value = false
  }
}

// セルフレビュー: 削除
async function selfReviewDelete() {
  if (!confirm('この投稿を削除してよろしいですか？この操作は取り消せません。')) return
  selfReviewing.value = true
  try {
    await selfReviewPost(postId, { action: 'DELETE' })
    success('投稿を削除しました')
    await navigateTo(publishRedirectPath())
  } catch {
    showError('削除に失敗しました')
  } finally {
    selfReviewing.value = false
  }
}

// 管理者承認: 承認して公開
async function adminApprove() {
  adminReviewing.value = true
  try {
    // TODO: adminPublishPost が useBlogApi に追加されたら差し替えること
    await api(`/api/v1/blog/posts/${postId}/publish`, {
      method: 'PATCH',
      body: { status: 'PUBLISHED' },
    })
    status.value = 'PUBLISHED'
    success('記事を承認・公開しました')
    await navigateTo(publishRedirectPath())
  } catch {
    showError('承認に失敗しました')
  } finally {
    adminReviewing.value = false
  }
}

// 管理者承認: 却下
async function adminReject() {
  if (!rejectionReasonInput.value.trim()) {
    showError('却下理由を入力してください')
    return
  }
  adminReviewing.value = true
  try {
    // TODO: adminPublishPost が useBlogApi に追加されたら差し替えること
    await api(`/api/v1/blog/posts/${postId}/publish`, {
      method: 'PATCH',
      body: { status: 'REJECTED', rejection_reason: rejectionReasonInput.value.trim() },
    })
    status.value = 'REJECTED'
    rejectionReason.value = rejectionReasonInput.value.trim()
    showRejectionInput.value = false
    rejectionReasonInput.value = ''
    success('却下しました')
  } catch {
    showError('却下処理に失敗しました')
  } finally {
    adminReviewing.value = false
  }
}

// Ctrl+S で保存
function onKeydown(e: KeyboardEvent) {
  if ((e.ctrlKey || e.metaKey) && e.key === 's') {
    e.preventDefault()
    save()
  }
}

// ステータス表示用
const statusLabel = computed(() => {
  switch (status.value) {
    case 'PUBLISHED': return '公開中'
    case 'SCHEDULED': return '予約'
    case 'PENDING_SELF_REVIEW': return 'セルフレビュー待ち'
    case 'PENDING_REVIEW': return 'レビュー待ち'
    case 'REJECTED': return '却下'
    case 'ARCHIVED': return 'アーカイブ'
    default: return '下書き'
  }
})

const statusSeverity = computed(() => {
  switch (status.value) {
    case 'PUBLISHED': return 'success'
    case 'SCHEDULED': return 'info'
    case 'PENDING_SELF_REVIEW': return 'warn'
    case 'PENDING_REVIEW': return 'warn'
    case 'REJECTED': return 'danger'
    case 'ARCHIVED': return 'secondary'
    default: return 'secondary'
  }
})

const isAdmin = computed(() => authStore.isSystemAdmin)

onMounted(() => {
  load()
  if (typeof localStorage !== 'undefined') {
    const stored = localStorage.getItem(AUTO_SAVE_STORAGE_KEY)
    if (stored !== null) autoSaveEnabled.value = stored !== 'false'
  }
  autoSaveTimer = setInterval(runAutoSave, AUTO_SAVE_INTERVAL_MS)
})

onUnmounted(() => {
  if (autoSaveTimer !== null) clearInterval(autoSaveTimer)
})
</script>

<template>
  <div class="flex h-full flex-col" @keydown="onKeydown">
    <!-- ヘッダー -->
    <div class="mb-3 flex items-center justify-between gap-3">
      <div class="flex items-center gap-2">
        <Button icon="pi pi-arrow-left" text rounded @click="router.back()" />
        <span class="text-base font-semibold text-surface-600">ブログ編集</span>
        <Tag
          v-if="!loading"
          :value="statusLabel"
          :severity="statusSeverity"
          rounded
        />
      </div>
      <div class="flex items-center gap-2">
        <span class="hidden text-xs text-surface-400 lg:block">Ctrl+S で保存</span>
        <Button
          label="保存"
          icon="pi pi-save"
          outlined
          size="small"
          :loading="saving"
          @click="save"
        />
        <!-- 通常の公開ボタン: DRAFT または REJECTED 時に表示 -->
        <Button
          v-if="status === 'DRAFT' || status === 'REJECTED'"
          label="今すぐ公開"
          icon="pi pi-send"
          size="small"
          :loading="publishing && !scheduledAt"
          @click="publish"
        />
        <Button
          v-if="status === 'DRAFT' || status === 'REJECTED'"
          label="予約公開"
          icon="pi pi-clock"
          size="small"
          severity="secondary"
          :disabled="!scheduledAt"
          :loading="publishing && !!scheduledAt"
          @click="publish"
        />
      </div>
    </div>

    <PageLoading v-if="loading" />

    <div v-else class="flex flex-col gap-4">
      <!-- 却下理由表示 (REJECTED ステータス時) -->
      <div v-if="status === 'REJECTED' && rejectionReason" class="rounded-lg border border-red-200 bg-red-50 p-4">
        <p class="mb-1 font-semibold text-red-700">■ 却下理由</p>
        <p class="text-sm text-red-600">{{ rejectionReason }}</p>
      </div>

      <!-- 予約公開日時 (DRAFT または REJECTED 時に表示) -->
      <div v-if="status === 'DRAFT' || status === 'REJECTED'" class="flex items-center gap-3">
        <span class="text-sm text-surface-600">予約公開日時</span>
        <DatePicker
          v-model="scheduledAt"
          show-time
          hour-format="24"
          date-format="yy/mm/dd"
          placeholder="日時を選択すると予約公開"
          show-button-bar
        />
      </div>

      <!-- セルフレビューセクション (PENDING_SELF_REVIEW 時) -->
      <div v-if="status === 'PENDING_SELF_REVIEW'" class="rounded-lg border border-yellow-200 bg-yellow-50 p-4">
        <p class="mb-1 font-semibold text-yellow-800">■ セルフレビュー</p>
        <p class="mb-3 text-sm text-yellow-700">投稿者本人による最終確認が必要です。</p>
        <div class="flex flex-wrap gap-2">
          <Button
            label="公開申請する"
            icon="pi pi-check"
            size="small"
            severity="success"
            :loading="selfReviewing"
            @click="selfReviewPublish"
          />
          <Button
            label="下書きに戻す"
            icon="pi pi-undo"
            size="small"
            severity="secondary"
            outlined
            :loading="selfReviewing"
            @click="selfReviewDraft"
          />
          <Button
            label="削除する"
            icon="pi pi-trash"
            size="small"
            severity="danger"
            outlined
            :loading="selfReviewing"
            @click="selfReviewDelete"
          />
        </div>
      </div>

      <!-- 管理者レビューセクション (PENDING_REVIEW かつ管理者) -->
      <div v-if="status === 'PENDING_REVIEW' && isAdmin" class="rounded-lg border border-blue-200 bg-blue-50 p-4">
        <p class="mb-1 font-semibold text-blue-800">■ 管理者レビュー</p>
        <div class="flex flex-wrap gap-2">
          <Button
            label="承認して公開"
            icon="pi pi-check-circle"
            size="small"
            severity="success"
            :loading="adminReviewing"
            @click="adminApprove"
          />
          <Button
            v-if="!showRejectionInput"
            label="却下"
            icon="pi pi-times-circle"
            size="small"
            severity="danger"
            outlined
            @click="showRejectionInput = true"
          />
        </div>
        <!-- 却下理由入力フォーム -->
        <div v-if="showRejectionInput" class="mt-3 flex flex-col gap-2">
          <Textarea
            v-model="rejectionReasonInput"
            placeholder="却下理由を入力してください"
            rows="3"
            class="w-full"
          />
          <div class="flex gap-2">
            <Button
              label="却下する"
              icon="pi pi-times"
              size="small"
              severity="danger"
              :loading="adminReviewing"
              @click="adminReject"
            />
            <Button
              label="キャンセル"
              size="small"
              severity="secondary"
              text
              @click="showRejectionInput = false; rejectionReasonInput = ''"
            />
          </div>
        </div>
      </div>

      <!-- タイトル入力 -->
      <InputText
        v-model="title"
        placeholder="タイトルを入力してください"
        class="w-full border-0 border-b-2 border-surface-200 bg-transparent px-1 py-2 text-2xl font-bold shadow-none outline-none focus:border-primary focus:ring-0"
        :unstyled="true"
        style="box-shadow: none"
      />

      <!-- 自動保存トグル -->
      <div class="flex items-center gap-2 text-sm text-gray-500">
        <input
          id="autosave-toggle"
          v-model="autoSaveEnabled"
          type="checkbox"
          class="h-4 w-4 cursor-pointer rounded"
          @change="onAutoSaveToggle"
        />
        <label for="autosave-toggle" class="cursor-pointer select-none">
          {{ $t('blog.autoSave.label') }}
        </label>
        <span v-if="lastAutoSavedAt" class="text-xs text-gray-400">
          {{ $t('blog.autoSave.lastSaved', { time: formatTime(lastAutoSavedAt) }) }}
        </span>
      </div>

      <!-- メディアアップロード（画像・動画挿入） -->
      <BlogMediaUploader
        v-if="scopeType && scopeId"
        :scope-type="scopeType"
        :scope-id="scopeId"
        :blog-post-id="postId"
        @inserted="onMediaInserted"
      />

      <!-- Markdownエディタ（ツールバー + 編集/プレビュー） -->
      <MarkdownEditor v-model="body" />

      <!-- お知らせウィジェット表示フラグ（チーム/組織スコープのみ） -->
      <div v-if="isTeamOrOrgScope" class="rounded-lg border border-surface-200 p-3 dark:border-surface-700">
        <AnnouncementToggle v-model="displayInAnnouncement" />
        <p class="ml-6 mt-1 text-xs text-surface-400">
          ※「公開する」ボタン押下時に登録されます
        </p>
      </div>
    </div>
  </div>
</template>
