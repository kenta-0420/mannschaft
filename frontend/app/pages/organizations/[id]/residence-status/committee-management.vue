<script setup lang="ts">
import type { ResidenceStatusDashboard } from '~/types/residenceStatus'
import ActivityScoreCard from '~/components/residenceStatus/ActivityScoreCard.vue'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const orgId = computed(() => String(route.params.id))

const { getDashboard } = useActivitySnapshotApi()

const dashboard = ref<ResidenceStatusDashboard | null>(null)
const loadingDashboard = ref(false)

// 委員会ID入力（文字列として保持し、遷移時に Number() 変換する）
const committeeIdInput = ref<string>('')

async function fetchDashboard() {
  loadingDashboard.value = true
  try {
    const res = await getDashboard(orgId.value)
    dashboard.value = res.data
  }
  catch (e) {
    console.error('ダッシュボード取得エラー:', e)
  }
  finally {
    loadingDashboard.value = false
  }
}

function goToVisitsReview() {
  navigateTo(
    `/organizations/${orgId.value}/residence-status/monitoring-visits-review?committeeId=${Number(committeeIdInput.value)}`,
  )
}

function goToVisitForm() {
  navigateTo(`/organizations/${orgId.value}/residence-status/monitoring-visit-form`)
}

onMounted(async () => {
  await fetchDashboard()
})
</script>

<template>
  <div class="flex flex-col gap-6 p-6">
    <div class="flex items-center justify-between">
      <h1 class="text-2xl font-bold">見守り委員会管理</h1>
    </div>

    <!-- ダッシュボード統計サマリ -->
    <section class="flex flex-col gap-3">
      <h2 class="text-lg font-semibold">リスク分布サマリ</h2>

      <div v-if="loadingDashboard" class="flex items-center justify-center py-8">
        <LoadingBounce />
      </div>

      <div v-else-if="dashboard" class="grid grid-cols-2 md:grid-cols-4 gap-4">
        <div class="border border-red-300 rounded-lg p-4 text-center bg-red-50 dark:bg-red-950/20">
          <div class="text-3xl font-bold text-red-500">
            {{ dashboard.highRiskCount }}
          </div>
          <div class="text-sm text-gray-500 mt-1">
            高リスク住民数
          </div>
        </div>
        <div class="border border-orange-300 rounded-lg p-4 text-center bg-orange-50 dark:bg-orange-950/20">
          <div class="text-3xl font-bold text-orange-400">
            {{ dashboard.midRiskCount }}
          </div>
          <div class="text-sm text-gray-500 mt-1">
            中リスク住民数
          </div>
        </div>
        <div class="border border-green-300 rounded-lg p-4 text-center bg-green-50 dark:bg-green-950/20">
          <div class="text-3xl font-bold text-green-500">
            {{ dashboard.lowRiskCount }}
          </div>
          <div class="text-sm text-gray-500 mt-1">
            低リスク住民数
          </div>
        </div>
        <div class="border rounded-lg p-4 text-center bg-white dark:bg-surface-800">
          <div class="text-3xl font-bold text-gray-400">
            {{ dashboard.totalResidents }}
          </div>
          <div class="text-sm text-gray-500 mt-1">
            総住民数
          </div>
        </div>
      </div>

      <div v-else class="text-center py-8 text-gray-400">
        データがありません
      </div>
    </section>

    <!-- ActivityScoreCard デモ表示 -->
    <section class="flex flex-col gap-3">
      <h2 class="text-lg font-semibold">リスクスコア表示例</h2>
      <div class="flex flex-wrap gap-4">
        <ActivityScoreCard
          :score="45"
          resident-name="見本 太郎"
          last-seen-at="2026-04-01T10:00:00Z"
        />
        <ActivityScoreCard
          :score="25"
          resident-name="見本 花子"
          last-seen-at="2026-05-01T08:30:00Z"
        />
        <ActivityScoreCard
          :score="10"
          resident-name="見本 三郎"
        />
      </div>
    </section>

    <!-- 委員会操作パネル -->
    <section class="flex flex-col gap-4">
      <h2 class="text-lg font-semibold">委員会操作</h2>

      <div class="border rounded-lg p-4 bg-white dark:bg-surface-800 flex flex-col gap-4">
        <div class="flex flex-col gap-2">
          <label class="text-sm font-medium">委員会ID</label>
          <div class="flex items-center gap-2 flex-wrap">
            <InputText
              v-model="committeeIdInput"
              type="number"
              placeholder="委員会IDを入力"
              style="width: 14rem"
            />
            <Button
              label="訪問履歴を見る"
              icon="pi pi-list"
              :disabled="!committeeIdInput || Number(committeeIdInput) <= 0"
              @click="goToVisitsReview"
            />
          </div>
          <p class="text-xs text-gray-400">
            委員会IDを入力して訪問履歴を確認できます
          </p>
        </div>

        <div class="border-t pt-4">
          <Button
            label="新規訪問記録を作成"
            icon="pi pi-plus"
            severity="success"
            @click="goToVisitForm"
          />
          <p class="text-xs text-gray-400 mt-2">
            見守り委員が実施した訪問の記録を新たに登録します
          </p>
        </div>
      </div>
    </section>
  </div>
</template>
