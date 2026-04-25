<script setup lang="ts">
import type { BulletinThreadResponse } from '~/types/bulletin'

const teamStore = useTeamStore()
const { getScopedThreads } = useBulletinApi()
const { listTodos } = useTodoApi()
const { relativeTime } = useRelativeTime()

interface FamilyTodo {
  id: number
  title: string
  completed: boolean
  dueDate: string | null
  createdAt: string
}

interface FamilyData {
  teamId: number
  familyName: string
  announcements: BulletinThreadResponse[]
  todos: FamilyTodo[]
}

const familyTeams = computed(() => teamStore.myTeams.filter((t) => t.template === 'FAMILY'))

const familyData = ref<FamilyData[]>([])
const loading = ref(false)
const selectedId = ref<number | null>(null)

const selectedFamily = computed(
  () => familyData.value.find((f) => f.teamId === selectedId.value) ?? familyData.value[0],
)

const priorityConfig: Record<string, { label: string; class: string }> = {
  CRITICAL: { label: '緊急', class: 'bg-red-100 text-red-700' },
  IMPORTANT: { label: '重要', class: 'bg-orange-100 text-orange-700' },
  WARNING: { label: '注意', class: 'bg-yellow-100 text-yellow-700' },
  INFO: { label: '通知', class: 'bg-blue-100 text-blue-700' },
}

async function load() {
  if (familyTeams.value.length === 0) return
  loading.value = true
  try {
    const results = await Promise.all(
      familyTeams.value.map(async (team) => {
        const name = team.nickname1 || team.name
        const [announcementsRes, todosRes] = await Promise.allSettled([
          getScopedThreads('teams', team.id, { page: 0, size: 4 }),
          listTodos('team', team.id, { size: 5 }),
        ])
        return {
          teamId: team.id,
          familyName: name,
          announcements: announcementsRes.status === 'fulfilled' ? announcementsRes.value.data : [],
          todos:
            todosRes.status === 'fulfilled'
              ? (todosRes.value.data as FamilyTodo[]).filter((t) => !t.completed).slice(0, 4)
              : [],
        }
      }),
    )
    familyData.value = results
    if (selectedId.value === null && results.length > 0) {
      selectedId.value = results[0]!.teamId
    }
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <DashboardWidgetCard
    title="家族"
    icon="pi pi-home"
    to="/teams"
    :loading="loading"
    :col-span="2"
    refreshable
    @refresh="load"
  >
    <!-- 家族チームなし -->
    <DashboardEmptyState
      v-if="familyTeams.length === 0"
      icon="pi pi-home"
      message="家族チームに参加していません"
    />

    <template v-else>
      <!-- 家族セレクター (複数の場合) -->
      <div v-if="familyTeams.length > 1" class="mb-3 flex flex-wrap gap-2">
        <button
          v-for="f in familyData"
          :key="f.teamId"
          class="rounded-full px-3 py-1 text-xs font-semibold transition-colors"
          :class="
            selectedId === f.teamId
              ? 'bg-primary text-white'
              : 'bg-surface-100 text-surface-600 hover:bg-surface-200 dark:bg-surface-700 dark:text-surface-300'
          "
          @click="selectedId = f.teamId"
        >
          <i class="pi pi-home mr-1 text-[10px]" />{{ f.familyName }}
        </button>
      </div>

      <template v-if="selectedFamily">
        <!-- 単一家族の場合はヘッダーにファミリー名を表示 -->
        <p v-if="familyTeams.length === 1" class="mb-3 text-xs font-semibold text-surface-500">
          <i class="pi pi-home mr-1" />{{ selectedFamily.familyName }}
        </p>

        <div class="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <!-- お知らせ -->
          <div>
            <div class="mb-2 flex items-center justify-between">
              <span class="text-xs font-semibold text-surface-500">
                <i class="pi pi-megaphone mr-1" />お知らせ
              </span>
              <NuxtLink
                :to="`/teams/${selectedFamily.teamId}/bulletin`"
                class="text-[11px] text-primary hover:underline"
              >
                すべて表示
              </NuxtLink>
            </div>
            <div v-if="selectedFamily.announcements.length > 0" class="space-y-1.5">
              <NuxtLink
                v-for="item in selectedFamily.announcements"
                :key="item.id"
                :to="`/teams/${selectedFamily.teamId}/bulletin`"
                class="flex items-start gap-2 rounded-lg px-2 py-1.5 transition-colors hover:bg-surface-50 dark:hover:bg-surface-700/50"
              >
                <i
                  v-if="item.isPinned"
                  class="pi pi-thumbtack mt-0.5 shrink-0 text-[10px] text-orange-400"
                />
                <div class="min-w-0 flex-1">
                  <div class="flex flex-wrap items-center gap-1">
                    <span
                      v-if="item.priority && item.priority !== 'INFO'"
                      :class="priorityConfig[item.priority]?.class"
                      class="rounded px-1 py-0.5 text-[10px] font-medium"
                    >
                      {{ priorityConfig[item.priority]?.label }}
                    </span>
                    <span
                      class="truncate text-xs font-medium text-surface-700 dark:text-surface-200"
                    >
                      {{ item.title }}
                    </span>
                  </div>
                  <span class="text-[11px] text-surface-400">{{
                    relativeTime(item.createdAt)
                  }}</span>
                </div>
              </NuxtLink>
            </div>
            <p v-else class="text-xs text-surface-400">お知らせはありません</p>
          </div>

          <!-- TODO -->
          <div>
            <div class="mb-2 flex items-center justify-between">
              <span class="text-xs font-semibold text-surface-500">
                <i class="pi pi-check-square mr-1" />TODO
              </span>
              <NuxtLink
                :to="`/teams/${selectedFamily.teamId}/todos`"
                class="text-[11px] text-primary hover:underline"
              >
                すべて表示
              </NuxtLink>
            </div>
            <div v-if="selectedFamily.todos.length > 0" class="space-y-1.5">
              <NuxtLink
                v-for="todo in selectedFamily.todos"
                :key="todo.id"
                :to="`/teams/${selectedFamily.teamId}/todos`"
                class="flex items-center gap-2 rounded-lg px-2 py-1.5 transition-colors hover:bg-surface-50 dark:hover:bg-surface-700/50"
              >
                <i class="pi pi-circle shrink-0 text-xs text-surface-400" />
                <div class="min-w-0 flex-1">
                  <p class="truncate text-xs font-medium text-surface-700 dark:text-surface-200">
                    {{ todo.title }}
                  </p>
                  <p v-if="todo.dueDate" class="text-[11px] text-surface-400">
                    <i class="pi pi-clock mr-0.5" />{{ relativeTime(todo.dueDate) }}
                  </p>
                </div>
              </NuxtLink>
            </div>
            <p v-else class="text-xs text-surface-400">未完了のTODOはありません</p>
          </div>
        </div>
      </template>
    </template>
  </DashboardWidgetCard>
</template>
