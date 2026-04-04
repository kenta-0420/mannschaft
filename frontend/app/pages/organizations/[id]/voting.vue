<script setup lang="ts">
import type { VoteSessionResponse } from '~/types/voting'

definePageMeta({ middleware: 'auth' })
const route = useRoute()
const orgId = Number(route.params.id)

const { getSessions } = useVotingApi()
const { showError } = useNotification()
const { relativeTime } = useRelativeTime()

const sessions = ref<VoteSessionResponse[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const res = await getSessions({ scope_type: 'ORGANIZATION', scope_id: orgId })
    sessions.value = res.data
  } catch {
    showError('投票セッションの取得に失敗しました')
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
    case 'CLOSED':
      return 'bg-yellow-100 text-yellow-700'
    case 'FINALIZED':
      return 'bg-blue-100 text-blue-700'
    default:
      return 'bg-surface-100'
  }
}

onMounted(() => load())
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <h1 class="text-2xl font-bold">議決権行使</h1>
      <Button label="セッション作成" icon="pi pi-plus" />
    </div>
    <PageLoading v-if="loading" size="40px" />
    <div v-else class="flex flex-col gap-3">
      <div
        v-for="s in sessions"
        :key="s.id"
        class="rounded-xl border border-surface-200 bg-surface-0 p-4"
      >
        <div class="mb-2 flex items-center gap-2">
          <span :class="getStatusClass(s.status)" class="rounded px-2 py-0.5 text-xs font-medium">{{
            s.status
          }}</span>
          <span class="rounded bg-surface-100 px-1.5 py-0.5 text-xs">{{ s.votingMode }}</span>
          <span v-if="s.isAnonymous" class="text-xs text-surface-400"
            ><i class="pi pi-eye-slash" /> 無記名</span
          >
        </div>
        <h3 class="text-sm font-semibold">{{ s.title }}</h3>
        <div class="mt-1 flex items-center gap-3 text-xs text-surface-400">
          <span>{{ s.motions.length }}議案</span>
          <span>投票 {{ s.votedCount }}/{{ s.eligibleCount }}</span>
          <span>委任 {{ s.delegatedCount }}</span>
          <span>{{ relativeTime(s.createdAt) }}</span>
        </div>
      </div>
      <div v-if="sessions.length === 0" class="py-12 text-center">
        <i class="pi pi-check-square mb-3 text-4xl text-surface-300" />
        <p class="text-surface-400">投票セッションがありません</p>
      </div>
    </div>
  </div>
</template>
