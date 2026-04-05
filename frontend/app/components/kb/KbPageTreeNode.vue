<script setup lang="ts">
import type { KbPageSummaryResponse } from '~/types/knowledgeBase'

interface TreeNode {
  page: KbPageSummaryResponse
  children: TreeNode[]
}

const props = defineProps<{
  node: TreeNode
  expandedNodes: Set<number>
}>()

const emit = defineEmits<{
  select: [page: KbPageSummaryResponse]
  toggle: [id: number]
}>()

const { relativeTime } = useRelativeTime()

const isExpanded = computed(() => props.expandedNodes.has(props.node.page.id))
const hasChildren = computed(() => props.node.children.length > 0)

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
</script>

<template>
  <div>
    <div
      class="group flex items-center gap-2 rounded-lg px-3 py-2 transition-colors hover:bg-surface-100 dark:hover:bg-surface-700"
      :style="{ paddingLeft: `${node.page.depth * 20 + 12}px` }"
    >
      <!-- Expand/collapse toggle -->
      <button
        v-if="hasChildren"
        class="flex h-5 w-5 items-center justify-center rounded text-surface-400 hover:text-surface-600 dark:hover:text-surface-300"
        @click.stop="emit('toggle', node.page.id)"
      >
        <i
          class="pi text-xs transition-transform"
          :class="isExpanded ? 'pi-chevron-down' : 'pi-chevron-right'"
        />
      </button>
      <span v-else class="w-5" />

      <!-- Page button -->
      <button
        class="flex min-w-0 flex-1 items-center gap-2 text-left"
        @click="emit('select', node.page)"
      >
        <span v-if="node.page.icon" class="text-base">{{ node.page.icon }}</span>
        <i v-else class="pi pi-file text-sm text-surface-400" />

        <span class="truncate text-sm font-medium">{{ node.page.title }}</span>

        <span
          :class="getStatusClass(node.page.status)"
          class="ml-1 shrink-0 rounded px-1.5 py-0.5 text-xs"
        >
          {{ getStatusLabel(node.page.status) }}
        </span>

        <span class="ml-auto shrink-0 text-xs text-surface-400 opacity-0 transition-opacity group-hover:opacity-100">
          {{ relativeTime(node.page.updatedAt) }}
        </span>
      </button>
    </div>

    <!-- Children -->
    <div v-if="hasChildren && isExpanded">
      <KbPageTreeNode
        v-for="child in node.children"
        :key="child.page.id"
        :node="child"
        :expanded-nodes="expandedNodes"
        @select="(p: KbPageSummaryResponse) => emit('select', p)"
        @toggle="(id: number) => emit('toggle', id)"
      />
    </div>
  </div>
</template>
