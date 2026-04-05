<script setup lang="ts">
import type { KbPageSummaryResponse } from '~/types/knowledgeBase'
import type { KbScopeType } from '~/composables/useKnowledgeBaseApi'

const props = defineProps<{
  scopeType: KbScopeType
  scopeId: number
  canManage?: boolean
}>()

const emit = defineEmits<{
  select: [page: KbPageSummaryResponse]
  create: []
}>()

const {
  getPages,
  searchPages,
  getRecentPages,
  getPinnedPages,
  getFavoritePages,
} = useKnowledgeBaseApi(props.scopeType)
const { error: showError } = useNotification()
const { relativeTime } = useRelativeTime()

const pages = ref<KbPageSummaryResponse[]>([])
const loading = ref(false)
const searchQuery = ref('')
const activeTab = ref<'tree' | 'recent' | 'pinned' | 'favorites'>('tree')
const expandedNodes = ref<Set<number>>(new Set())

// Build tree structure from flat list
interface TreeNode {
  page: KbPageSummaryResponse
  children: TreeNode[]
}

const pageTree = computed<TreeNode[]>(() => {
  const map = new Map<number | null, KbPageSummaryResponse[]>()
  for (const page of pages.value) {
    const parentId = page.parentId
    if (!map.has(parentId)) map.set(parentId, [])
    map.get(parentId)!.push(page)
  }

  function buildChildren(parentId: number | null): TreeNode[] {
    const children = map.get(parentId) || []
    return children.map(page => ({
      page,
      children: buildChildren(page.id),
    }))
  }

  return buildChildren(null)
})

async function loadPages() {
  loading.value = true
  try {
    let res: { data: KbPageSummaryResponse[] }
    if (searchQuery.value.trim()) {
      res = await searchPages(props.scopeId, searchQuery.value.trim())
    } else if (activeTab.value === 'recent') {
      res = await getRecentPages(props.scopeId)
    } else if (activeTab.value === 'pinned') {
      res = await getPinnedPages(props.scopeId)
    } else if (activeTab.value === 'favorites') {
      res = await getFavoritePages(props.scopeId)
    } else {
      res = await getPages(props.scopeId)
    }
    pages.value = res.data
  } catch {
    showError('ページの取得に失敗しました')
  } finally {
    loading.value = false
  }
}

function toggleNode(nodeId: number) {
  if (expandedNodes.value.has(nodeId)) {
    expandedNodes.value.delete(nodeId)
  } else {
    expandedNodes.value.add(nodeId)
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
  const labels: Record<string, string> = { ALL_MEMBERS: '全員', ADMIN_ONLY: '管理者のみ', CUSTOM: 'カスタム' }
  return labels[level] || level
}

watch([activeTab, searchQuery], () => loadPages())
onMounted(() => loadPages())

defineExpose({ refresh: () => loadPages() })
</script>

<template>
  <div>
    <!-- Header -->
    <div class="mb-4 flex flex-wrap items-center gap-3">
      <InputText v-model="searchQuery" placeholder="ページを検索..." class="w-48" />
      <div class="ml-auto flex items-center gap-2">
        <Button
          v-if="canManage"
          label="新規ページ"
          icon="pi pi-plus"
          @click="emit('create')"
        />
      </div>
    </div>

    <!-- Tabs -->
    <div class="mb-4 flex gap-1 rounded-lg bg-surface-100 p-1 dark:bg-surface-800">
      <button
        v-for="tab in ([
          { key: 'tree', label: 'ページツリー', icon: 'pi pi-sitemap' },
          { key: 'recent', label: '最近', icon: 'pi pi-clock' },
          { key: 'pinned', label: 'ピン留め', icon: 'pi pi-thumbtack' },
          { key: 'favorites', label: 'お気に入り', icon: 'pi pi-star' },
        ] as const)"
        :key="tab.key"
        class="flex-1 rounded-md px-3 py-1.5 text-sm font-medium transition-colors"
        :class="activeTab === tab.key
          ? 'bg-surface-0 shadow-sm dark:bg-surface-700'
          : 'text-surface-500 hover:text-surface-700 dark:text-surface-400 dark:hover:text-surface-200'"
        @click="activeTab = tab.key"
      >
        <i :class="tab.icon" class="mr-1" />
        {{ tab.label }}
      </button>
    </div>

    <!-- Loading -->
    <div v-if="loading" class="flex justify-center py-8">
      <ProgressSpinner style="width: 40px; height: 40px" />
    </div>

    <!-- Tree View -->
    <div v-else-if="activeTab === 'tree' && !searchQuery.trim()" class="flex flex-col gap-1">
      <template v-if="pageTree.length > 0">
        <KbPageTreeNode
          v-for="node in pageTree"
          :key="node.page.id"
          :node="node"
          :expanded-nodes="expandedNodes"
          @select="(p: KbPageSummaryResponse) => emit('select', p)"
          @toggle="toggleNode"
        />
      </template>
      <div v-else class="py-12 text-center">
        <i class="pi pi-book mb-3 text-4xl text-surface-300" />
        <p class="text-surface-400">ページがありません</p>
      </div>
    </div>

    <!-- Flat list (search results, recent, pinned, favorites) -->
    <div v-else class="flex flex-col gap-2">
      <button
        v-for="page in pages"
        :key="page.id"
        class="flex items-start gap-3 rounded-xl border border-surface-300 bg-surface-0 p-4 text-left transition-shadow hover:shadow-sm dark:border-surface-600 dark:bg-surface-800"
        @click="emit('select', page)"
      >
        <span v-if="page.icon" class="mt-0.5 text-lg">{{ page.icon }}</span>
        <i v-else class="pi pi-file mt-1 text-surface-400" />

        <div class="min-w-0 flex-1">
          <div class="mb-1 flex flex-wrap items-center gap-2">
            <span
              :class="getStatusClass(page.status)"
              class="rounded px-1.5 py-0.5 text-xs font-medium"
            >
              {{ getStatusLabel(page.status) }}
            </span>
            <span class="text-xs text-surface-400 dark:text-surface-500">
              {{ getAccessLabel(page.accessLevel) }}
            </span>
          </div>

          <h3 class="text-sm font-semibold">{{ page.title }}</h3>

          <div class="mt-1 flex items-center gap-3 text-xs text-surface-400">
            <span>{{ relativeTime(page.updatedAt) }}</span>
            <span v-if="page.viewCount"><i class="pi pi-eye" /> {{ page.viewCount }}</span>
          </div>
        </div>
      </button>

      <div v-if="pages.length === 0" class="py-12 text-center">
        <i class="pi pi-book mb-3 text-4xl text-surface-300" />
        <p class="text-surface-400">ページがありません</p>
      </div>
    </div>
  </div>
</template>
