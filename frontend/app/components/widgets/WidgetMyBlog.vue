<script setup lang="ts">
import type { BlogPostResponse } from '~/types/cms'

const { getUserPosts, createMyPost } = useBlogApi()
const { captureQuiet } = useErrorReport()
const authStore = useAuthStore()
const teamStore = useTeamStore()
const orgStore = useOrganizationStore()
const notification = useNotification()

const posts = ref<BlogPostResponse[]>([])
const loading = ref(true)

const showCreate = ref(false)
const creating = ref(false)
const form = ref({ title: '', scopeType: 'PERSONAL', scopeId: null as number | null })

const scopeOptions = computed(() => {
  const opts: Array<{ label: string; scopeType: string; scopeId: number | null }> = [
    { label: '個人', scopeType: 'PERSONAL', scopeId: null },
  ]
  for (const t of teamStore.myTeams) {
    opts.push({ label: `チーム: ${t.nickname1 || t.name}`, scopeType: 'TEAM', scopeId: t.id })
  }
  for (const o of orgStore.myOrganizations) {
    opts.push({ label: `組織: ${o.nickname1 || o.name}`, scopeType: 'ORGANIZATION', scopeId: o.id })
  }
  return opts
})

const selectedScope = computed({
  get: () =>
    scopeOptions.value.find(
      (o) => o.scopeType === form.value.scopeType && o.scopeId === form.value.scopeId,
    ) ?? scopeOptions.value[0],
  set: (v) => {
    form.value.scopeType = v?.scopeType ?? 'PERSONAL'
    form.value.scopeId = v?.scopeId ?? null
  },
})

const statusLabel: Record<string, string> = {
  DRAFT: '下書き',
  PUBLISHED: '公開',
  SCHEDULED: '予約',
}
const statusSeverity: Record<string, string> = {
  DRAFT: 'secondary',
  PUBLISHED: 'success',
  SCHEDULED: 'info',
}

async function load() {
  loading.value = true
  try {
    const userId = authStore.currentUser?.id
    if (!userId) return
    const res = await getUserPosts(userId, { size: 5 })
    posts.value = res.data
  } catch (error) {
    captureQuiet(error, { context: 'WidgetMyBlog: ブログ記事取得' })
    posts.value = []
  } finally {
    loading.value = false
  }
}

function openCreate() {
  form.value = { title: '', scopeType: 'PERSONAL', scopeId: null }
  showCreate.value = true
}

async function submitCreate() {
  if (!form.value.title.trim()) return
  creating.value = true
  try {
    const res = await createMyPost({
      title: form.value.title.trim(),
      body: '.',
      status: 'DRAFT',
      scopeType: form.value.scopeType,
      scopeId: form.value.scopeId,
    })
    showCreate.value = false
    navigateTo(`/blog/posts/${res.data.id}/edit`)
  } catch (error) {
    captureQuiet(error, { context: 'WidgetMyBlog: ブログ記事作成' })
    const msg = (
      error as { data?: { error?: { fieldErrors?: { field: string; message: string }[] } } }
    )?.data?.error?.fieldErrors
      ?.map((f) => `${f.field}: ${f.message}`)
      .join(', ')
    notification.error('記事の作成に失敗しました', msg || '時間をおいて再試行してください')
  } finally {
    creating.value = false
  }
}

onMounted(load)
</script>

<template>
  <DashboardWidgetCard
    title="マイブログ"
    icon="pi pi-book"
    :loading="loading"
    :col-span="2"
    refreshable
    @refresh="load"
  >
    <template #default>
      <div class="mb-3 flex justify-end">
        <Button label="新規作成" icon="pi pi-plus" size="small" @click="openCreate" />
      </div>

      <div v-if="posts.length > 0" class="space-y-2">
        <div
          v-for="post in posts"
          :key="post.id"
          class="flex cursor-pointer items-center gap-3 rounded-lg px-2 py-2 transition-colors hover:bg-surface-50 dark:hover:bg-surface-700/50"
          @click="navigateTo(`/blog/posts/${post.id}/edit`)"
        >
          <div class="min-w-0 flex-1">
            <p class="truncate text-sm font-medium">{{ post.title }}</p>
            <p class="text-xs text-surface-400">
              {{ new Date(post.publishedAt || post.createdAt).toLocaleDateString('ja-JP') }}
            </p>
          </div>
          <Tag
            :value="statusLabel[post.status] ?? post.status"
            :severity="statusSeverity[post.status] ?? 'secondary'"
            rounded
          />
        </div>
      </div>
      <DashboardEmptyState v-else icon="pi pi-book" message="まだ記事がありません" />
    </template>
  </DashboardWidgetCard>

  <!-- 新規作成ダイアログ -->
  <Dialog v-model:visible="showCreate" header="記事を作成" modal :style="{ width: '420px' }">
    <div class="space-y-4">
      <div>
        <label class="mb-1 block text-sm font-medium"
          >タイトル <span class="text-red-500">*</span></label
        >
        <InputText v-model="form.title" class="w-full" placeholder="記事のタイトル" />
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">投稿先</label>
        <Select
          v-model="selectedScope"
          :options="scopeOptions"
          option-label="label"
          class="w-full"
        />
      </div>
      <p class="text-xs text-surface-500">
        ※ 下書きとして保存されます。公開はブログページから行えます。
      </p>
    </div>
    <template #footer>
      <Button label="キャンセル" text @click="showCreate = false" />
      <Button
        label="作成"
        icon="pi pi-check"
        :loading="creating"
        :disabled="!form.title.trim()"
        @click="submitCreate"
      />
    </template>
  </Dialog>
</template>
