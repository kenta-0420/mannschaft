<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

const route = useRoute()
const teamId = Number(route.params.id)
const safetyApi = useSafetyCheckApi()
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamId)

interface SafetyCheck {
  id: number; title: string; status: string; isDrill: boolean
  responseStats: { total: number; responded: number; responseRate: number }
  createdAt: string; closedAt: string | null
}

const checks = ref<SafetyCheck[]>([])
const loading = ref(true)
const showTriggerDialog = ref(false)
const selectedCheckId = ref<number | null>(null)

async function loadChecks() {
  loading.value = true
  try {
    const res = await safetyApi.listSafetyChecks('team', teamId, { size: 20 })
    checks.value = res.data as SafetyCheck[]
  }
  catch { checks.value = [] }
  finally { loading.value = false }
}

function selectCheck(checkId: number) {
  selectedCheckId.value = checkId
}

function formatDate(dateStr: string): string {
  return new Date(dateStr).toLocaleString('ja-JP', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}

onMounted(async () => {
  await loadPermissions()
  await loadChecks()
})
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <h1 class="text-2xl font-bold">安否確認</h1>
      <Button v-if="isAdminOrDeputy" label="安否確認を発動" icon="pi pi-exclamation-triangle" severity="danger" @click="showTriggerDialog = true" />
    </div>

    <div class="grid grid-cols-1 gap-6 lg:grid-cols-3">
      <!-- 一覧 -->
      <div>
        <div v-if="loading" class="space-y-2">
          <Skeleton v-for="i in 3" :key="i" height="4rem" />
        </div>
        <div v-else-if="checks.length > 0" class="space-y-2">
          <div
            v-for="check in checks"
            :key="check.id"
            class="cursor-pointer rounded-lg border border-surface-200 p-3 transition-all hover:shadow-md dark:border-surface-700"
            :class="{ 'border-primary bg-primary/5': selectedCheckId === check.id }"
            @click="selectCheck(check.id)"
          >
            <div class="flex items-center justify-between">
              <p class="text-sm font-medium">
                <span v-if="check.isDrill" class="text-yellow-600">【訓練】</span>
                {{ check.title }}
              </p>
              <Tag
                :value="check.status === 'ACTIVE' ? '受付中' : '終了'"
                :severity="check.status === 'ACTIVE' ? 'danger' : 'secondary'"
                rounded
              />
            </div>
            <div class="mt-1 flex items-center gap-3 text-xs text-surface-500">
              <span>{{ formatDate(check.createdAt) }}</span>
              <span>回答率: {{ Math.round(check.responseStats.responseRate) }}%</span>
            </div>
          </div>
        </div>
        <DashboardEmptyState v-else icon="pi pi-shield" message="安否確認の履歴はありません" />
      </div>

      <!-- 詳細パネル -->
      <div class="lg:col-span-2">
        <div v-if="selectedCheckId" class="rounded-xl border border-surface-200 bg-surface-0 p-4 dark:border-surface-700 dark:bg-surface-800">
          <SafetyResultsDashboard scope-type="team" :scope-id="teamId" :check-id="selectedCheckId" />
        </div>
        <div v-else class="rounded-xl border border-surface-200 bg-surface-0 p-8 dark:border-surface-700 dark:bg-surface-800">
          <DashboardEmptyState icon="pi pi-shield" message="安否確認を選択してください" />
        </div>
      </div>
    </div>

    <SafetyCheckTrigger
      v-model:visible="showTriggerDialog"
      scope-type="team"
      :scope-id="teamId"
      @triggered="loadChecks"
    />
  </div>
</template>
