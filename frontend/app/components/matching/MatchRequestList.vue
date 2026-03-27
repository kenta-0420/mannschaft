<script setup lang="ts">
import type { MatchRequestResponse, MatchRequestSearchParams } from '~/types/matching'

const props = defineProps<{ teamId?: number }>()
const emit = defineEmits<{ select: [req: MatchRequestResponse]; create: [] }>()

const { searchRequests, getTeamRequests, getPrefectures } = useMatchingApi()
const { showError } = useNotification()
const { relativeTime } = useRelativeTime()

const requests = ref<MatchRequestResponse[]>([])
const loading = ref(false)
const searchParams = ref<MatchRequestSearchParams>({})

async function load() {
  loading.value = true
  try {
    if (props.teamId) {
      const res = await getTeamRequests(props.teamId)
      requests.value = res.data
    } else {
      const res = await searchRequests(searchParams.value)
      requests.value = res.data
    }
  } catch { showError('募集一覧の取得に失敗しました') }
  finally { loading.value = false }
}

function getStatusClass(s: string): string {
  switch (s) { case 'OPEN': return 'bg-green-100 text-green-700'; case 'MATCHED': return 'bg-blue-100 text-blue-700'; case 'CANCELLED': return 'bg-red-100 text-red-600'; default: return 'bg-surface-100 text-surface-500' }
}

onMounted(() => load())
defineExpose({ refresh: load })
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <h2 class="text-lg font-semibold">対戦・交流募集</h2>
      <Button v-if="teamId" label="募集を作成" icon="pi pi-plus" @click="emit('create')" />
    </div>
    <div v-if="loading" class="flex justify-center py-8"><ProgressSpinner style="width: 40px; height: 40px" /></div>
    <div v-else class="flex flex-col gap-3">
      <button v-for="req in requests" :key="req.id" class="rounded-xl border border-surface-200 bg-surface-0 p-4 text-left transition-shadow hover:shadow-sm" @click="emit('select', req)">
        <div class="mb-2 flex items-center gap-2">
          <span :class="getStatusClass(req.status)" class="rounded px-2 py-0.5 text-xs font-medium">{{ req.status }}</span>
          <span class="rounded bg-surface-100 px-1.5 py-0.5 text-xs">{{ req.activity_type }}</span>
          <span v-if="req.level !== 'ANY'" class="text-xs text-surface-400">{{ req.level }}</span>
        </div>
        <h3 class="mb-1 text-sm font-semibold">{{ req.title }}</h3>
        <div class="flex items-center gap-3 text-xs text-surface-400">
          <span>{{ req.team.name }}</span>
          <span v-if="req.team.average_rating"><i class="pi pi-star-fill text-amber-400" /> {{ req.team.average_rating?.toFixed(1) }}</span>
          <span>{{ relativeTime(req.created_at) }}</span>
          <span><i class="pi pi-users" /> {{ req.proposal_count }}件の応募</span>
        </div>
      </button>
      <div v-if="requests.length === 0" class="py-12 text-center"><i class="pi pi-search mb-3 text-4xl text-surface-300" /><p class="text-surface-400">募集がありません</p></div>
    </div>
  </div>
</template>
