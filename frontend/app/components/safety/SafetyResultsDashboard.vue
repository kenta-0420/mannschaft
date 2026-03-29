<script setup lang="ts">
const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: number
  checkId: number
}>()

const safetyApi = useSafetyCheckApi()
const notification = useNotification()

interface Results {
  safetyCheck: {
    id: number
    title: string
    status: string
    isDrill: boolean
    responseStats: {
      total: number
      responded: number
      safe: number
      needSupport: number
      other: number
      responseRate: number
    }
    createdAt: string
    closedAt: string | null
  }
  responses: Array<{
    id: number
    userId: number
    displayName: string
    avatarUrl: string | null
    status: string
    message: string | null
    respondedAt: string
  }>
  unrespondedMembers: Array<{ userId: number; displayName: string; avatarUrl: string | null }>
  followups: Array<{
    id: number
    responseId: number
    userId: number
    displayName: string
    status: string
    note: string | null
  }>
}

const results = ref<Results | null>(null)
const loading = ref(true)

async function loadResults() {
  loading.value = true
  try {
    const res = await safetyApi.getSafetyCheckResults(props.checkId)
    results.value = res.data as Results
  } catch {
    notification.error('結果の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

async function closeSafetyCheck() {
  if (!confirm('安否確認を終了しますか？')) return
  try {
    await safetyApi.closeSafetyCheck(props.checkId)
    notification.success('安否確認を終了しました')
    await loadResults()
  } catch {
    notification.error('終了に失敗しました')
  }
}

async function sendReminder() {
  try {
    await safetyApi.sendReminder(props.checkId)
    notification.success('リマインダーを送信しました')
  } catch {
    notification.error('送信に失敗しました')
  }
}

const responseRate = computed(() => results.value?.safetyCheck.responseStats.responseRate ?? 0)

onMounted(loadResults)
</script>

<template>
  <div v-if="loading" class="space-y-4">
    <Skeleton height="4rem" />
    <Skeleton height="8rem" />
  </div>

  <div v-else-if="results" class="space-y-6">
    <!-- ヘッダー -->
    <div class="flex items-start justify-between">
      <div>
        <h2 class="text-xl font-bold">
          <span v-if="results.safetyCheck.isDrill" class="text-yellow-600">【訓練】</span>
          {{ results.safetyCheck.title }}
        </h2>
        <Tag
          :value="results.safetyCheck.status === 'ACTIVE' ? '受付中' : '終了'"
          :severity="results.safetyCheck.status === 'ACTIVE' ? 'danger' : 'secondary'"
          rounded
        />
      </div>
      <div v-if="results.safetyCheck.status === 'ACTIVE'" class="flex gap-2">
        <Button
          label="リマインダー送信"
          icon="pi pi-bell"
          size="small"
          outlined
          @click="sendReminder"
        />
        <Button
          label="終了する"
          icon="pi pi-times"
          size="small"
          severity="secondary"
          @click="closeSafetyCheck"
        />
      </div>
    </div>

    <!-- 回答率 -->
    <div class="grid grid-cols-2 gap-4 md:grid-cols-5">
      <div class="rounded-lg border border-surface-200 p-3 text-center dark:border-surface-700">
        <p class="text-2xl font-bold text-primary">{{ Math.round(responseRate) }}%</p>
        <p class="text-xs text-surface-500">回答率</p>
      </div>
      <div class="rounded-lg border border-surface-200 p-3 text-center dark:border-surface-700">
        <p class="text-2xl font-bold text-green-600">
          {{ results.safetyCheck.responseStats.safe }}
        </p>
        <p class="text-xs text-surface-500">無事</p>
      </div>
      <div class="rounded-lg border border-surface-200 p-3 text-center dark:border-surface-700">
        <p class="text-2xl font-bold text-red-600">
          {{ results.safetyCheck.responseStats.needSupport }}
        </p>
        <p class="text-xs text-surface-500">支援必要</p>
      </div>
      <div class="rounded-lg border border-surface-200 p-3 text-center dark:border-surface-700">
        <p class="text-2xl font-bold text-yellow-600">
          {{ results.safetyCheck.responseStats.other }}
        </p>
        <p class="text-xs text-surface-500">その他</p>
      </div>
      <div class="rounded-lg border border-surface-200 p-3 text-center dark:border-surface-700">
        <p class="text-2xl font-bold text-surface-400">{{ results.unrespondedMembers.length }}</p>
        <p class="text-xs text-surface-500">未回答</p>
      </div>
    </div>

    <!-- 回答一覧 -->
    <div>
      <h3 class="mb-2 text-sm font-semibold">回答一覧</h3>
      <div class="space-y-2">
        <div
          v-for="res in results.responses"
          :key="res.id"
          class="flex items-center gap-3 rounded-lg border border-surface-200 p-3 dark:border-surface-700"
        >
          <Avatar
            :image="res.avatarUrl"
            :label="res.avatarUrl ? undefined : res.displayName.charAt(0)"
            shape="circle"
          />
          <div class="min-w-0 flex-1">
            <p class="text-sm font-medium">{{ res.displayName }}</p>
            <p v-if="res.message" class="truncate text-xs text-surface-500">{{ res.message }}</p>
          </div>
          <Tag
            :value="
              res.status === 'SAFE' ? '無事' : res.status === 'NEED_SUPPORT' ? '支援必要' : 'その他'
            "
            :severity="
              res.status === 'SAFE' ? 'success' : res.status === 'NEED_SUPPORT' ? 'danger' : 'warn'
            "
            rounded
          />
        </div>
      </div>
    </div>

    <!-- 未回答者 -->
    <div v-if="results.unrespondedMembers.length > 0">
      <h3 class="mb-2 text-sm font-semibold text-red-600">
        未回答（{{ results.unrespondedMembers.length }}名）
      </h3>
      <div class="flex flex-wrap gap-2">
        <div
          v-for="member in results.unrespondedMembers"
          :key="member.userId"
          class="flex items-center gap-2 rounded-full bg-red-50 px-3 py-1 dark:bg-red-900/20"
        >
          <Avatar
            :image="member.avatarUrl"
            :label="member.avatarUrl ? undefined : member.displayName.charAt(0)"
            shape="circle"
            size="small"
          />
          <span class="text-sm">{{ member.displayName }}</span>
        </div>
      </div>
    </div>
  </div>
</template>
