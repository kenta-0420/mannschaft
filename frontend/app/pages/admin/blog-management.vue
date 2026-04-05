<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

const scopeStore = useScopeStore()
const scopeType = computed(() => scopeStore.current.type as 'team' | 'organization')
const scopeId = computed(() => scopeStore.current.id ?? 0)
const { success, error: showError } = useNotification()
const {
  getPosts,
  changePublishStatus,
  deletePost,
  getTags,
  createTag,
  deleteTag,
} = useBlogApi()

const activeTab = ref('posts')

// --- Posts ---
const posts = ref<Record<string, unknown>[]>([])
const postsLoading = ref(false)
const postsTotal = ref(0)
const postsPage = ref(0)

async function loadPosts() {
  postsLoading.value = true
  try {
    const res = await getPosts({ scopeType: scopeType.value, scopeId: scopeId.value, page: postsPage.value, size: 20 })
    posts.value = (res as unknown as { data: Record<string, unknown>[]; meta?: { totalElements?: number } }).data
    postsTotal.value = (res as unknown as { meta?: { totalElements?: number } }).meta?.totalElements ?? posts.value.length
  } catch {
    showError('記事一覧の取得に失敗しました')
  } finally {
    postsLoading.value = false
  }
}

async function togglePublish(post: Record<string, unknown>) {
  const newStatus = post.status === 'PUBLISHED' ? 'DRAFT' : 'PUBLISHED'
  try {
    await changePublishStatus(post.id as number, newStatus)
    success(newStatus === 'PUBLISHED' ? '公開しました' : '非公開にしました')
    await loadPosts()
  } catch {
    showError('ステータスの変更に失敗しました')
  }
}

async function removePost(id: number) {
  if (!confirm('この記事を削除しますか？')) return
  try {
    await deletePost(id)
    success('記事を削除しました')
    await loadPosts()
  } catch {
    showError('削除に失敗しました')
  }
}

// --- Tags ---
const tags = ref<Record<string, unknown>[]>([])
const tagsLoading = ref(false)
const showTagDialog = ref(false)
const tagName = ref('')
const tagSaving = ref(false)

async function loadTags() {
  tagsLoading.value = true
  try {
    const res = await getTags({ scopeType: scopeType.value, scopeId: scopeId.value })
    tags.value = (res as unknown as { data: Record<string, unknown>[] }).data
  } catch {
    showError('タグの取得に失敗しました')
  } finally {
    tagsLoading.value = false
  }
}

async function saveTag() {
  if (!tagName.value) return
  tagSaving.value = true
  try {
    await createTag({ name: tagName.value })
    success('タグを作成しました')
    showTagDialog.value = false
    tagName.value = ''
    await loadTags()
  } catch {
    showError('作成に失敗しました')
  } finally {
    tagSaving.value = false
  }
}

async function removeTag(id: number) {
  try {
    await deleteTag(id)
    success('タグを削除しました')
    await loadTags()
  } catch {
    showError('削除に失敗しました')
  }
}

function onTabChange(val: string | number) {
  activeTab.value = String(val)
  if (val === 'posts' && posts.value.length === 0) loadPosts()
  if (val === 'tags' && tags.value.length === 0) loadTags()
}

watch(scopeId, (v) => { if (v) loadPosts() })
onMounted(() => { if (scopeId.value) loadPosts() })
</script>

<template>
  <div class="mx-auto max-w-6xl">
    <h1 class="mb-6 text-2xl font-bold">ブログ管理</h1>

    <Tabs :value="activeTab" @update:value="onTabChange">
      <TabList>
        <Tab value="posts">記事一覧</Tab>
        <Tab value="tags">タグ</Tab>
      </TabList>
      <TabPanels>
        <!-- 記事一覧 -->
        <TabPanel value="posts">
          <PageLoading v-if="postsLoading" />
          <DataTable
            v-else
            :value="posts"
            :lazy="true"
            :paginator="true"
            :rows="20"
            :total-records="postsTotal"
            :first="postsPage * 20"
            striped-rows
            data-key="id"
            @page="(e: { page: number }) => { postsPage = e.page; loadPosts() }"
          >
            <template #empty><div class="py-8 text-center text-surface-500">記事がありません</div></template>
            <Column field="title" header="タイトル" />
            <Column header="ステータス" style="width: 100px">
              <template #body="{ data }">
                <Tag
                  :value="data.status === 'PUBLISHED' ? '公開' : '下書き'"
                  :severity="data.status === 'PUBLISHED' ? 'success' : 'secondary'"
                />
              </template>
            </Column>
            <Column header="更新日" style="width: 140px">
              <template #body="{ data }">
                <span class="text-sm">{{ data.updatedAt ? new Date(data.updatedAt as string).toLocaleDateString('ja-JP') : '-' }}</span>
              </template>
            </Column>
            <Column header="操作" style="width: 200px">
              <template #body="{ data }">
                <div class="flex gap-1">
                  <Button
                    :label="data.status === 'PUBLISHED' ? '非公開' : '公開'"
                    size="small"
                    :severity="data.status === 'PUBLISHED' ? 'secondary' : 'success'"
                    outlined
                    @click="togglePublish(data)"
                  />
                  <Button
                    icon="pi pi-trash"
                    size="small"
                    severity="danger"
                    text
                    @click="removePost(data.id as number)"
                  />
                </div>
              </template>
            </Column>
          </DataTable>
        </TabPanel>

        <!-- タグ -->
        <TabPanel value="tags">
          <div class="mb-4 flex justify-end">
            <Button label="タグを追加" icon="pi pi-plus" @click="showTagDialog = true" />
          </div>
          <PageLoading v-if="tagsLoading" />
          <div v-else class="flex flex-wrap gap-2">
            <div
              v-for="tag in tags"
              :key="tag.id as number"
              class="flex items-center gap-1 rounded-full bg-surface-100 px-3 py-1 text-sm dark:bg-surface-700"
            >
              <span>{{ tag.name }}</span>
              <button
                class="ml-1 text-surface-400 hover:text-red-500"
                @click="removeTag(tag.id as number)"
              >
                <i class="pi pi-times text-xs" />
              </button>
            </div>
            <div v-if="tags.length === 0" class="text-surface-500">タグがありません</div>
          </div>
        </TabPanel>
      </TabPanels>
    </Tabs>

    <!-- タグ追加ダイアログ -->
    <Dialog v-model:visible="showTagDialog" header="タグを追加" :style="{ width: '340px' }" modal>
      <div>
        <label class="mb-1 block text-sm font-medium">タグ名 <span class="text-red-500">*</span></label>
        <InputText v-model="tagName" class="w-full" placeholder="例: お知らせ" />
      </div>
      <template #footer>
        <Button label="キャンセル" severity="secondary" text @click="showTagDialog = false" />
        <Button label="追加" :loading="tagSaving" :disabled="!tagName" @click="saveTag" />
      </template>
    </Dialog>
  </div>
</template>
