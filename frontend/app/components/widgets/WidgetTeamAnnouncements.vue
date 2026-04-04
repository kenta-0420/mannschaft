<script setup lang="ts">
import type { BulletinThreadResponse } from '~/types/bulletin'

const teamStore = useTeamStore()
const { getScopedThreads } = useBulletinApi()
const { relativeTime } = useRelativeTime()
const { captureQuiet } = useErrorReport()

interface ThreadWithScope extends BulletinThreadResponse {
  scopeName: string
  scopeId: number
}

const threads = ref<ThreadWithScope[]>([])
const loading = ref(false)

const priorityConfig: Record<string, { label: string; class: string }> = {
  CRITICAL: { label: '緊急', class: 'bg-red-100 text-red-700' },
  IMPORTANT: { label: '重要', class: 'bg-orange-100 text-orange-700' },
  WARNING: { label: '注意', class: 'bg-yellow-100 text-yellow-700' },
  INFO: { label: '通知', class: 'bg-blue-100 text-blue-700' },
  LOW: { label: '低', class: 'bg-surface-100 text-surface-500' },
}

async function load() {
  if (teamStore.myTeams.length === 0) return
  loading.value = true
  try {
    const results = await Promise.all(
      teamStore.myTeams.slice(0, 5).map((team) =>
        getScopedThreads('teams', team.id, { page: 0, size: 3 })
          .then((res) =>
            res.data.map((t) => ({
              ...t,
              scopeName: team.nickname1 || team.name,
              scopeId: team.id,
            })),
          )
          .catch((error) => {
            captureQuiet(error, {
              context: `WidgetTeamAnnouncements: チームID=${team.id} 掲示板取得`,
            })
            return [] as ThreadWithScope[]
          }),
      ),
    )
    threads.value = results
      .flat()
      .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())
      .slice(0, 8)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <DashboardWidgetCard
    title="チームのお知らせ"
    icon="pi pi-users"
    :loading="loading"
    refreshable
    @refresh="load"
  >
    <div v-if="teamStore.myTeams.length === 0">
      <DashboardEmptyState icon="pi pi-users" message="チームに参加していません" />
    </div>
    <div v-else-if="threads.length === 0">
      <DashboardEmptyState icon="pi pi-clipboard" message="チームからのお知らせはありません" />
    </div>
    <div v-else class="divide-y divide-surface-100 dark:divide-surface-700">
      <NuxtLink
        v-for="thread in threads"
        :key="`${thread.scopeType}-${thread.id}`"
        :to="`/teams/${thread.scopeId}/bulletin`"
        class="flex items-start gap-2 py-2.5 transition-colors hover:bg-surface-50 dark:hover:bg-surface-700/50"
      >
        <i v-if="thread.isPinned" class="pi pi-thumbtack mt-0.5 shrink-0 text-xs text-orange-500" />
        <div class="min-w-0 flex-1">
          <div class="mb-0.5 flex flex-wrap items-center gap-1.5">
            <span
              v-if="thread.priority !== 'INFO'"
              :class="priorityConfig[thread.priority]?.class"
              class="rounded px-1.5 py-0.5 text-xs font-medium"
            >
              {{ priorityConfig[thread.priority]?.label }}
            </span>
            <span class="truncate text-sm font-medium text-surface-700 dark:text-surface-200">
              {{ thread.title }}
            </span>
          </div>
          <div class="flex items-center gap-2 text-xs text-surface-400">
            <Tag :value="thread.scopeName" severity="info" class="text-xs" />
            <span>{{ relativeTime(thread.createdAt) }}</span>
            <span v-if="thread.replyCount > 0">
              <i class="pi pi-comment" /> {{ thread.replyCount }}
            </span>
          </div>
        </div>
      </NuxtLink>
    </div>
  </DashboardWidgetCard>
</template>
