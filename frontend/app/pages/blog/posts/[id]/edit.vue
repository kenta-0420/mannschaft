<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

const route = useRoute()
const router = useRouter()
const postId = Number(route.params.id)

const { getMyPost, updateMyPost, publishMyPost } = useBlogApi()
const { success, error: showError } = useNotification()

const title = ref(route.query.title ? String(route.query.title) : '')
const body = ref('')
const status = ref('DRAFT')
const scopeType = ref<string | null>(route.query.scopeType ? String(route.query.scopeType) : null)
const scopeId = ref<number | null>(route.query.scopeId ? Number(route.query.scopeId) : null)
const loading = ref(true)
const saving = ref(false)
const publishing = ref(false)
// お知らせウィジェット表示フラグ（チーム/組織スコープのみ有効）
const displayInAnnouncement = ref(false)
const isTeamOrOrgScope = computed(
  () => (scopeType.value === 'TEAM' || scopeType.value === 'ORGANIZATION') && !!scopeId.value,
)

/**
 * BlogMediaUploader から送られてきたMarkdownテキストをエディタ末尾に挿入する。
 * カーソル位置への挿入はtextareaへの直接アクセスが必要なため、ここでは末尾追記とする。
 */
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
    title.value = post.title ?? ''
    const rawBody = post.body ?? ''
    body.value = rawBody === '.' ? '' : rawBody
    status.value = post.status ?? 'DRAFT'
    scopeType.value = post.scopeType ?? null
    scopeId.value = post.scopeId ?? null
  } catch {
    // タイトルはクエリパラメータから引き継いでいるので画面表示は継続
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

async function publish() {
  await save()
  publishing.value = true
  try {
    await publishMyPost(postId)
    status.value = 'PUBLISHED'
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
  } catch {
    showError('公開に失敗しました')
  } finally {
    publishing.value = false
  }
}

// Ctrl+S で保存
function onKeydown(e: KeyboardEvent) {
  if ((e.ctrlKey || e.metaKey) && e.key === 's') {
    e.preventDefault()
    save()
  }
}

onMounted(load)
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
          :value="status === 'PUBLISHED' ? '公開中' : status === 'SCHEDULED' ? '予約' : '下書き'"
          :severity="
            status === 'PUBLISHED' ? 'success' : status === 'SCHEDULED' ? 'info' : 'secondary'
          "
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
        <Button
          v-if="status !== 'PUBLISHED'"
          label="公開する"
          icon="pi pi-send"
          size="small"
          :loading="publishing"
          @click="publish"
        />
      </div>
    </div>

    <PageLoading v-if="loading" />

    <div v-else class="flex flex-col gap-3">
      <!-- タイトル入力 -->
      <InputText
        v-model="title"
        placeholder="タイトルを入力してください"
        class="w-full border-0 border-b-2 border-surface-200 bg-transparent px-1 py-2 text-2xl font-bold shadow-none outline-none focus:border-primary focus:ring-0"
        :unstyled="true"
        style="box-shadow: none"
      />

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
        <AnnouncementAnnouncementToggle v-model="displayInAnnouncement" />
        <p class="ml-6 mt-1 text-xs text-surface-400">
          ※「公開する」ボタン押下時に登録されます
        </p>
      </div>
    </div>
  </div>
</template>
