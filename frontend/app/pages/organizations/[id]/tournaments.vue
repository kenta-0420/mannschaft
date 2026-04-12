<script setup lang="ts">
import type { TournamentResponse } from '~/types/tournament'

definePageMeta({ middleware: 'auth' })
const route = useRoute()
const orgId = Number(route.params.id)

const { getTournaments } = useTournamentApi()
const { showError } = useNotification()

const tournaments = ref<TournamentResponse[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const res = await getTournaments(orgId)
    tournaments.value = res.data
  } catch {
    showError('大会一覧の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

function getStatusClass(s: string): string {
  switch (s) {
    case 'DRAFT':
      return 'bg-surface-100 text-surface-600'
    case 'OPEN':
      return 'bg-green-100 text-green-700'
    case 'IN_PROGRESS':
      return 'bg-blue-100 text-blue-700'
    case 'COMPLETED':
      return 'bg-purple-100 text-purple-700'
    default:
      return 'bg-surface-100'
  }
}

function getFormatLabel(f: string): string {
  switch (f) {
    case 'LEAGUE':
      return 'リーグ戦'
    case 'KNOCKOUT':
      return 'トーナメント'
    case 'GROUP_KNOCKOUT':
      return 'グループ+トーナメント'
    default:
      return f
  }
}

onMounted(() => load())
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <PageHeader title="大会・リーグ" />
      <Button label="大会を作成" icon="pi pi-plus" />
    </div>
    <PageLoading v-if="loading" size="40px" />
    <div v-else class="grid gap-4 sm:grid-cols-2">
      <div
        v-for="t in tournaments"
        :key="t.id"
        class="rounded-xl border border-surface-300 bg-surface-0 p-4"
      >
        <div class="mb-2 flex items-center gap-2">
          <span :class="getStatusClass(t.status)" class="rounded px-2 py-0.5 text-xs font-medium">{{
            t.status
          }}</span>
          <span class="rounded bg-surface-100 px-1.5 py-0.5 text-xs">{{
            getFormatLabel(t.format)
          }}</span>
          <span class="text-xs text-surface-400">{{ t.sportCategory }}</span>
        </div>
        <h3 class="text-sm font-semibold">{{ t.title }}</h3>
        <div class="mt-2 flex items-center gap-3 text-xs text-surface-400">
          <span>{{ t.seasonYear }}年度</span>
          <span>{{ t.divisions.length }}部門</span>
          <span>勝{{ t.winPoints }} 分{{ t.drawPoints }} 負{{ t.lossPoints }}</span>
        </div>
      </div>
      <DashboardEmptyState v-if="tournaments.length === 0" class="col-span-full" icon="pi pi-trophy" message="大会がありません" />
    </div>
  </div>
</template>
