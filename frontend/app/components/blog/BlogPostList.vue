<script setup lang="ts">
import type { BlogPostResponse, BlogTag } from '~/types/cms'

const props = withDefaults(
  defineProps<{
    scopeType?: string
    scopeId?: string
    canCreate?: boolean
    showCreate?: boolean
    /** ADMIN/DEPUTY_ADMIN 相当の管理権限。タグ更新・削除、他人の記事の公開切替・削除に使う。 */
    canManage?: boolean
  }>(),
  {
    scopeType: undefined,
    scopeId: undefined,
    canCreate: false,
    showCreate: undefined,
    canManage: false,
  },
)

const emit = defineEmits<{
  select: [post: BlogPostResponse]
}>()

const { t } = useI18n()
const { getPosts, createPost, changePublishStatus, deletePost, getTags, createTag, deleteTag } =
  useBlogApi()
const { error: showError, success } = useNotification()
const { relativeTime } = useRelativeTime()
const authStore = useAuthStore()

// scopeType が未指定（個人ブログ）の場合は常に作成可能。showCreate=false で明示的に非表示化
const showCreateButton = computed(
  () =>
    props.showCreate !== false &&
    (props.canCreate || !props.scopeType || props.scopeType === 'PERSONAL'),
)

// タグ機能はチーム/組織スコープのみ（blog_tags は team_id/organization_id を持つスコープ単位の語彙で、
// 個人ブログにはタグの概念が無い）。BE のガード実測に合わせ、一覧・作成はメンバー（canCreate）、
// 更新・削除は ADMIN/DEPUTY_ADMIN（canManage）に限定する。
const showTagSection = computed(
  () => (props.scopeType === 'TEAM' || props.scopeType === 'ORGANIZATION') && props.canCreate,
)

const posts = ref<BlogPostResponse[]>([])
const loading = ref(false)

// 新規作成ダイアログ
const showCreateDialog = ref(false)
const creating = ref(false)
const createForm = ref({ title: '' })

async function loadPosts() {
  loading.value = true
  try {
    const res = await getPosts({
      scope_type: props.scopeType,
      scope_id: props.scopeId,
      page: 0,
      size: 20,
    })
    posts.value = res.data
  } catch {
    showError(t('blog.post.loadListFailed'))
  } finally {
    loading.value = false
  }
}

function openCreateDialog() {
  createForm.value = { title: '' }
  showCreateDialog.value = true
}

async function submitCreate() {
  if (!createForm.value.title.trim()) return
  creating.value = true
  try {
    const res = await createPost({
      title: createForm.value.title,
      body: '.',
      status: 'DRAFT',
      scopeType: props.scopeType ?? null,
      scopeId: props.scopeId ?? null,
    })
    showCreateDialog.value = false
    const q = new URLSearchParams({ title: res.data.content?.title ?? '' })
    if (res.data.scopeType) q.set('scopeType', res.data.scopeType)
    if (res.data.scopeId != null) q.set('scopeId', String(res.data.scopeId))
    navigateTo(`/blog/posts/${res.data.id}/edit?${q.toString()}`)
  } catch {
    showError(t('blog.post.createFailed'))
  } finally {
    creating.value = false
  }
}

/**
 * 投稿者本人、または ADMIN/DEPUTY_ADMIN（canManage）のみ公開切替・削除できる（BE checkWriteAccess 相当）。
 * チーム/組織スコープでのみ表示する。個人ブログ（scopeType 未指定）の「みんなの投稿」には元々この操作が無く、
 * `/blog`（マイブログ）ページ側に専用の自分の投稿管理 UI（/users/me/blog/posts 系）が既にあるため、
 * ここで重複して出すと同じ操作の入口が二重になる。
 */
function canModeratePost(post: BlogPostResponse): boolean {
  if (props.scopeType !== 'TEAM' && props.scopeType !== 'ORGANIZATION') return false
  return props.canManage || post.author?.id === authStore.currentUser?.id
}

async function togglePublish(post: BlogPostResponse) {
  const currentStatus = post.meta?.status
  const newStatus = currentStatus === 'PUBLISHED' ? 'DRAFT' : 'PUBLISHED'
  try {
    await changePublishStatus(post.id, newStatus)
    success(newStatus === 'PUBLISHED' ? t('blog.post.publishSuccess') : t('blog.post.unpublishSuccess'))
    await loadPosts()
  } catch {
    showError(t('blog.post.publishStatusFailed'))
  }
}

async function removePost(id: number) {
  if (!confirm(t('blog.post.deleteConfirm'))) return
  try {
    await deletePost(id)
    success(t('blog.post.deleteSuccess'))
    await loadPosts()
  } catch {
    showError(t('blog.post.deleteFailed'))
  }
}

// --- Tags ---
const tags = ref<BlogTag[]>([])
const tagsLoading = ref(false)
const showTagDialog = ref(false)
const tagName = ref('')
const tagSaving = ref(false)

async function loadTags() {
  if (!showTagSection.value) return
  tagsLoading.value = true
  try {
    const res = await getTags({ scope_type: props.scopeType, scope_id: props.scopeId })
    tags.value = res.data
  } catch {
    showError(t('blog.tag.loadFailed'))
  } finally {
    tagsLoading.value = false
  }
}

function openTagDialog() {
  tagName.value = ''
  showTagDialog.value = true
}

async function saveTag() {
  if (!tagName.value.trim()) return
  tagSaving.value = true
  try {
    await createTag({ name: tagName.value, scopeType: props.scopeType, scopeId: props.scopeId })
    success(t('blog.tag.createSuccess'))
    showTagDialog.value = false
    tagName.value = ''
    await loadTags()
  } catch {
    showError(t('blog.tag.createFailed'))
  } finally {
    tagSaving.value = false
  }
}

async function removeTag(id: number) {
  try {
    await deleteTag(id)
    success(t('blog.tag.deleteSuccess'))
    await loadTags()
  } catch {
    showError(t('blog.tag.deleteFailed'))
  }
}

function getStatusClass(status: string): string {
  switch (status) {
    case 'DRAFT':
      return 'bg-surface-100 text-surface-600'
    case 'PUBLISHED':
      return 'bg-green-100 text-green-700'
    case 'SCHEDULED':
      return 'bg-blue-100 text-blue-700'
    default:
      return 'bg-surface-100'
  }
}

function getStatusLabel(status: string): string {
  switch (status) {
    case 'DRAFT':
      return t('blog.post.draft')
    case 'PUBLISHED':
      return t('blog.post.published')
    case 'SCHEDULED':
      return t('blog.post.scheduled')
    case 'ARCHIVED':
      return t('blog.post.archived')
    default:
      return status
  }
}

onMounted(() => {
  loadPosts()
  loadTags()
})
defineExpose({ refresh: loadPosts })
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <h2 class="text-lg font-semibold">{{ $t('blog.post.listHeading') }}</h2>
      <Button
        v-if="showCreateButton"
        :label="$t('blog.post.createButton')"
        icon="pi pi-plus"
        data-testid="blog-post-create-button"
        @click="openCreateDialog"
      />
    </div>

    <div v-if="loading" class="flex justify-center py-8">
      <LoadingBounce />
    </div>

    <div v-else class="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
      <div
        v-for="post in posts"
        :key="post.id"
        data-testid="blog-post-card"
        class="overflow-hidden rounded-xl border border-surface-300 bg-surface-0 text-left transition-shadow hover:shadow-md dark:border-surface-600 dark:bg-surface-800"
      >
        <button
          class="block w-full text-left"
          @click="emit('select', post); navigateTo(`/blog/posts/${post.id}/edit`)"
        >
          <img v-if="post.content?.coverImageUrl" :src="post.content.coverImageUrl" class="h-40 w-full object-cover" >
          <div class="p-4">
            <div class="mb-2 flex flex-wrap items-center gap-2">
              <span
                :class="getStatusClass(post.meta?.status ?? 'DRAFT')"
                class="rounded px-2 py-0.5 text-xs font-medium"
                >{{ getStatusLabel(post.meta?.status ?? 'DRAFT') }}</span
              >
              <span
                v-for="tag in post.tags.slice(0, 3)"
                :key="tag.id"
                class="rounded bg-surface-100 px-1.5 py-0.5 text-xs text-surface-500"
                >{{ tag.name }}</span
              >
            </div>
            <h3 class="mb-1 text-sm font-semibold line-clamp-2">{{ post.content?.title }}</h3>
            <p v-if="post.content?.excerpt" class="mb-2 text-xs text-surface-400 line-clamp-2">
              {{ post.content.excerpt }}
            </p>
            <div class="flex items-center gap-2 text-xs text-surface-400">
              <span v-if="post.author?.displayName">{{ post.author.displayName }}</span>
              <span>{{ relativeTime(post.audit?.publishedAt || post.audit?.createdAt || '') }}</span>
              <span v-if="post.stats?.viewCount"><i class="pi pi-eye" /> {{ post.stats.viewCount }}</span>
            </div>
          </div>
        </button>

        <div
          v-if="canModeratePost(post)"
          class="flex justify-end gap-1 border-t border-surface-200 px-2 py-1 dark:border-surface-700"
        >
          <Button
            :label="post.meta?.status === 'PUBLISHED' ? $t('blog.post.unpublishButton') : $t('blog.post.publishButton')"
            size="small"
            :severity="post.meta?.status === 'PUBLISHED' ? 'secondary' : 'success'"
            text
            :data-testid="`blog-post-toggle-publish-${post.id}`"
            @click.stop="togglePublish(post)"
          />
          <Button
            icon="pi pi-trash"
            size="small"
            severity="danger"
            text
            :aria-label="$t('blog.post.deletePost')"
            :data-testid="`blog-post-delete-${post.id}`"
            @click.stop="removePost(post.id)"
          />
        </div>
      </div>
    </div>

    <DashboardEmptyState v-if="!loading && posts.length === 0" icon="pi pi-book" :message="$t('blog.post.noPost')" />

    <!-- 新規作成ダイアログ -->
    <Dialog
      v-model:visible="showCreateDialog"
      :header="$t('blog.post.createDialogTitle')"
      :style="{ width: '480px' }"
      modal
    >
      <div class="flex flex-col gap-4">
        <div class="flex flex-col gap-1">
          <label class="text-sm font-medium">{{ $t('blog.post.titleLabel') }} <span class="text-red-500">*</span></label>
          <InputText v-model="createForm.title" :placeholder="$t('blog.post.titlePlaceholder')" class="w-full" />
        </div>
        <p class="text-xs text-surface-500">
          {{ $t('blog.post.createDialogHint') }}
        </p>
      </div>
      <template #footer>
        <Button :label="$t('button.cancel')" text @click="showCreateDialog = false" />
        <Button
          :label="$t('button.create')"
          icon="pi pi-check"
          :loading="creating"
          :disabled="!createForm.title.trim()"
          data-testid="blog-post-create-submit"
          @click="submitCreate"
        />
      </template>
    </Dialog>

    <!-- タグ（チーム/組織スコープのみ） -->
    <div v-if="showTagSection" class="mt-8">
      <div class="mb-3 flex items-center justify-between">
        <h2 class="text-lg font-semibold">{{ $t('blog.tag.heading') }}</h2>
        <Button
          :label="$t('blog.tag.addButton')"
          icon="pi pi-plus"
          size="small"
          data-testid="blog-tag-add-button"
          @click="openTagDialog"
        />
      </div>
      <div v-if="tagsLoading" class="flex justify-center py-4">
        <LoadingBounce />
      </div>
      <div v-else class="flex flex-wrap gap-2" data-testid="blog-tag-list">
        <div
          v-for="tag in tags"
          :key="tag.id"
          data-testid="blog-tag-chip"
          class="flex items-center gap-1 rounded-full bg-surface-100 px-3 py-1 text-sm dark:bg-surface-700"
        >
          <span>{{ tag.name }}</span>
          <button
            v-if="canManage"
            class="ml-1 text-surface-400 hover:text-red-500"
            :aria-label="$t('button.delete')"
            :data-testid="`blog-tag-delete-${tag.id}`"
            @click="removeTag(tag.id)"
          >
            <i class="pi pi-times text-xs" />
          </button>
        </div>
        <div v-if="tags.length === 0" class="text-surface-500">{{ $t('blog.tag.empty') }}</div>
      </div>

      <!-- タグ追加ダイアログ -->
      <Dialog v-model:visible="showTagDialog" :header="$t('blog.tag.addDialogTitle')" :style="{ width: '340px' }" modal>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ $t('blog.tag.nameLabel') }} <span class="text-red-500">*</span></label>
          <InputText v-model="tagName" class="w-full" :placeholder="$t('blog.tag.namePlaceholder')" />
        </div>
        <template #footer>
          <Button :label="$t('button.cancel')" severity="secondary" text @click="showTagDialog = false" />
          <Button
            :label="$t('button.create')"
            :loading="tagSaving"
            :disabled="!tagName.trim()"
            data-testid="blog-tag-create-submit"
            @click="saveTag"
          />
        </template>
      </Dialog>
    </div>
  </div>
</template>
