<script setup lang="ts">
import type { KbPageResponse, KbPageRevisionSummaryResponse } from '~/types/knowledgeBase'
import type { KbScopeType } from '~/composables/useKnowledgeBaseApi'

const props = defineProps<{
  scopeType: KbScopeType
  scopeId: number
  pageId: number
  canManage?: boolean
}>()

const emit = defineEmits<{
  back: []
  edit: [page: KbPageResponse]
  deleted: []
  updated: []
}>()

const {
  getPage,
  deletePage,
  publishPage,
  archivePage,
  pinPage,
  unpinPage,
  favoritePage,
  unfavoritePage,
  getRevisions,
  restoreRevision,
} = useKnowledgeBaseApi(props.scopeType)
const { success: showSuccess, error: showError } = useNotification()
const { relativeTime } = useRelativeTime()

const page = ref<KbPageResponse | null>(null)
const revisions = ref<KbPageRevisionSummaryResponse[]>([])
const showRevisions = ref(false)
const isPinned = ref(false)
const isFavorited = ref(false)

async function loadPage() {
  try {
    const res = await getPage(props.scopeId, props.pageId)
    page.value = res.data
  } catch {
    showError('ページの取得に失敗しました')
  }
}

async function onPublish() {
  if (!page.value) return
  try {
    const res = await publishPage(props.scopeId, props.pageId)
    page.value = res.data
    showSuccess('ページを公開しました')
    emit('updated')
  } catch {
    showError('公開に失敗しました')
  }
}

async function onArchive() {
  if (!page.value) return
  try {
    const res = await archivePage(props.scopeId, props.pageId)
    page.value = res.data
    showSuccess('ページをアーカイブしました')
    emit('updated')
  } catch {
    showError('アーカイブに失敗しました')
  }
}

async function onDelete() {
  try {
    await deletePage(props.scopeId, props.pageId)
    showSuccess('ページを削除しました')
    emit('deleted')
  } catch {
    showError('削除に失敗しました')
  }
}

async function onTogglePin() {
  try {
    if (isPinned.value) {
      await unpinPage(props.scopeId, props.pageId)
      isPinned.value = false
      showSuccess('ピン留めを解除しました')
    } else {
      await pinPage(props.scopeId, props.pageId)
      isPinned.value = true
      showSuccess('ピン留めしました')
    }
  } catch {
    showError('操作に失敗しました')
  }
}

async function onToggleFavorite() {
  try {
    if (isFavorited.value) {
      await unfavoritePage(props.scopeId, props.pageId)
      isFavorited.value = false
      showSuccess('お気に入りを解除しました')
    } else {
      await favoritePage(props.scopeId, props.pageId)
      isFavorited.value = true
      showSuccess('お気に入りに追加しました')
    }
  } catch {
    showError('操作に失敗しました')
  }
}

async function loadRevisions() {
  try {
    const res = await getRevisions(props.scopeId, props.pageId)
    revisions.value = res.data
  } catch {
    showError('履歴の取得に失敗しました')
  }
}

async function onRestoreRevision(revisionId: number) {
  try {
    const res = await restoreRevision(props.scopeId, props.pageId, revisionId)
    page.value = res.data
    showSuccess('バージョンを復元しました')
    emit('updated')
  } catch {
    showError('復元に失敗しました')
  }
}

function toggleRevisions() {
  showRevisions.value = !showRevisions.value
  if (showRevisions.value && revisions.value.length === 0) {
    loadRevisions()
  }
}

function getStatusClass(status: string): string {
  switch (status) {
    case 'PUBLISHED': return 'bg-green-100 text-green-700 dark:bg-green-900 dark:text-green-300'
    case 'DRAFT': return 'bg-surface-100 text-surface-500 dark:bg-surface-700 dark:text-surface-300'
    case 'ARCHIVED': return 'bg-yellow-100 text-yellow-700 dark:bg-yellow-900 dark:text-yellow-300'
    default: return 'bg-surface-100 text-surface-500'
  }
}

function getStatusLabel(status: string): string {
  const labels: Record<string, string> = { DRAFT: '下書き', PUBLISHED: '公開', ARCHIVED: 'アーカイブ' }
  return labels[status] || status
}

function getAccessLabel(level: string): string {
  const labels: Record<string, string> = { ALL_MEMBERS: '全メンバー', ADMIN_ONLY: '管理者のみ', CUSTOM: 'カスタム' }
  return labels[level] || level
}

function getAccessIcon(level: string): string {
  switch (level) {
    case 'ALL_MEMBERS': return 'pi pi-users'
    case 'ADMIN_ONLY': return 'pi pi-shield'
    case 'CUSTOM': return 'pi pi-cog'
    default: return 'pi pi-users'
  }
}

onMounted(() => loadPage())
watch(() => props.pageId, () => {
  showRevisions.value = false
  revisions.value = []
  loadPage()
})
</script>

<template>
  <div v-if="page">
    <!-- Back button + Actions -->
    <div class="mb-4 flex items-center justify-between">
      <Button icon="pi pi-arrow-left" label="一覧へ戻る" text size="small" @click="emit('back')" />
      <div class="flex items-center gap-1">
        <!-- Favorite toggle (available to all) -->
        <Button
          :icon="isFavorited ? 'pi pi-star-fill' : 'pi pi-star'"
          :label="isFavorited ? 'お気に入り解除' : 'お気に入り'"
          text
          size="small"
          @click="onToggleFavorite"
        />

        <!-- Admin actions -->
        <template v-if="canManage">
          <Button
            :icon="isPinned ? 'pi pi-thumbtack' : 'pi pi-thumbtack'"
            :label="isPinned ? 'ピン解除' : 'ピン留め'"
            text
            size="small"
            @click="onTogglePin"
          />
          <Button
            icon="pi pi-pencil"
            label="編集"
            text
            size="small"
            @click="emit('edit', page!)"
          />
          <Button
            v-if="page.status === 'DRAFT'"
            icon="pi pi-check-circle"
            label="公開"
            text
            size="small"
            severity="success"
            @click="onPublish"
          />
          <Button
            v-if="page.status === 'PUBLISHED'"
            icon="pi pi-inbox"
            label="アーカイブ"
            text
            size="small"
            severity="warn"
            @click="onArchive"
          />
          <Button
            icon="pi pi-history"
            label="履歴"
            text
            size="small"
            @click="toggleRevisions"
          />
          <Button
            icon="pi pi-trash"
            label="削除"
            text
            size="small"
            severity="danger"
            @click="onDelete"
          />
        </template>
      </div>
    </div>

    <!-- Page content -->
    <div class="rounded-xl border border-surface-300 bg-surface-0 p-6 dark:border-surface-600 dark:bg-surface-800">
      <!-- Title & Meta -->
      <div class="mb-4">
        <div class="mb-2 flex items-center gap-2">
          <span v-if="page.icon" class="text-2xl">{{ page.icon }}</span>
          <h2 class="text-xl font-bold">{{ page.title }}</h2>
        </div>
        <div class="flex flex-wrap items-center gap-3 text-sm text-surface-400">
          <span
            :class="getStatusClass(page.status)"
            class="rounded px-1.5 py-0.5 text-xs font-medium"
          >
            {{ getStatusLabel(page.status) }}
          </span>
          <span class="flex items-center gap-1">
            <i :class="getAccessIcon(page.accessLevel)" class="text-xs" />
            {{ getAccessLabel(page.accessLevel) }}
          </span>
          <span>{{ relativeTime(page.updatedAt) }}</span>
          <span><i class="pi pi-eye" /> {{ page.viewCount }}</span>
          <span class="text-xs text-surface-300 dark:text-surface-500">v{{ page.version }}</span>
        </div>
      </div>

      <!-- Body -->
      <div
        v-if="page.body"
        class="prose max-w-none text-sm leading-relaxed dark:prose-invert"
        v-html="sanitizeHtml(page.body)"
      />
      <div v-else class="py-8 text-center text-surface-400">
        <i class="pi pi-file mb-2 text-3xl" />
        <p>まだ内容がありません</p>
      </div>
    </div>

    <!-- Revisions panel -->
    <div v-if="showRevisions" class="mt-4 rounded-xl border border-surface-300 bg-surface-0 p-4 dark:border-surface-600 dark:bg-surface-800">
      <h3 class="mb-3 text-sm font-semibold text-surface-500">更新履歴</h3>
      <div v-if="revisions.length > 0" class="flex flex-col gap-2">
        <div
          v-for="rev in revisions"
          :key="rev.id"
          class="flex items-center justify-between rounded-lg border border-surface-100 p-3 dark:border-surface-700"
        >
          <div class="text-sm">
            <span class="font-medium">v{{ rev.version }}</span>
            <span class="ml-2 text-surface-400">{{ relativeTime(rev.createdAt) }}</span>
          </div>
          <Button
            v-if="canManage && rev.version !== page!.version"
            label="復元"
            text
            size="small"
            @click="onRestoreRevision(rev.id)"
          />
        </div>
      </div>
      <p v-else class="text-sm text-surface-400">履歴がありません</p>
    </div>
  </div>

  <div v-else class="flex justify-center py-12">
    <ProgressSpinner style="width: 40px; height: 40px" />
  </div>
</template>
