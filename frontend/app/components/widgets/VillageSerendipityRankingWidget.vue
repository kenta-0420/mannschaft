<script setup lang="ts">
/**
 * F17.1 村機能 — ご縁スコアランキングウィジェット
 *
 * 設計書: docs/features/F17.1_village_community.md §13.2（Phase 3 — ご縁スコア）
 *
 * 機能:
 *   - 指定された村のご縁スコアランキング上位 10 名を表示
 *   - 自分の順位を別途表示
 *
 * 個別村ページの「ご縁ランキング」セクションでの使用を想定。
 */
import type {
  VillageSerendipityRankingResponse,
  VillageSerendipityScoreResponse,
} from '~/types/village'

const props = defineProps<{
  villageId: string
  /** 表示件数 (デフォルト 10) */
  limit?: number
}>()

const { t } = useI18n()
const villageApi = useVillageApi()
const authStore = useAuthStore()
const { captureQuiet } = useErrorReport()

const loading = ref(true)
const error = ref<string | null>(null)
const ranking = ref<VillageSerendipityScoreResponse[]>([])
const myScore = ref<VillageSerendipityScoreResponse | null>(null)

const displayLimit = computed(() => props.limit ?? 10)
const currentUserId = computed<number | null>(() => authStore.currentUser?.id ?? null)

async function load() {
  loading.value = true
  error.value = null
  try {
    const res: VillageSerendipityRankingResponse = await villageApi.getSerendipityRanking(
      props.villageId,
      0,
      displayLimit.value,
    )
    ranking.value = res.items
  }
  catch (err) {
    captureQuiet(err, { context: 'VillageSerendipityRankingWidget: ranking取得失敗' })
    error.value = t('village.serendipity.loadFailed')
    ranking.value = []
  }
  try {
    myScore.value = await villageApi.getMyScore(props.villageId)
  }
  catch (err) {
    captureQuiet(err, { context: 'VillageSerendipityRankingWidget: my score取得失敗' })
    myScore.value = null
  }
  finally {
    loading.value = false
  }
}

function formatScore(score: number): string {
  return (score * 100).toFixed(1)
}

onMounted(load)
watch(() => props.villageId, load)
</script>

<template>
  <div>
    <div class="mb-3 flex items-center justify-between">
      <h3 class="text-sm font-semibold text-surface-700 dark:text-surface-200">
        {{ t('village.serendipity.title') }}
      </h3>
      <span class="text-xs text-surface-500">
        {{ t('village.serendipity.subtitle') }}
      </span>
    </div>

    <div v-if="loading" class="space-y-2">
      <Skeleton height="2rem" />
      <Skeleton height="2rem" />
      <Skeleton height="2rem" />
    </div>

    <div v-else-if="error" class="flex flex-col items-center gap-2 py-6">
      <i class="pi pi-exclamation-triangle text-2xl text-orange-400" />
      <p class="text-sm text-surface-500">
        {{ error }}
      </p>
      <Button
        :label="t('village.feed.retry')"
        icon="pi pi-refresh"
        size="small"
        text
        @click="load"
      />
    </div>

    <div v-else>
      <!-- 自分のスコア -->
      <div
        v-if="myScore"
        class="mb-3 rounded-md bg-primary-50 dark:bg-primary-950 p-3 flex items-center justify-between"
      >
        <div>
          <div class="text-xs text-surface-500">
            {{ t('village.serendipity.myScore') }}
          </div>
          <div class="text-lg font-bold">
            {{ formatScore(myScore.score) }}
            <span class="text-xs text-surface-500 ml-1">/ 100</span>
          </div>
        </div>
        <div v-if="myScore.rank !== null" class="text-right">
          <div class="text-xs text-surface-500">
            {{ t('village.serendipity.rank') }}
          </div>
          <div class="text-lg font-bold">
            #{{ myScore.rank }}
          </div>
        </div>
      </div>

      <!-- ランキング一覧 -->
      <DashboardEmptyState
        v-if="ranking.length === 0"
        icon="pi pi-users"
        :message="t('village.serendipity.empty')"
      />
      <ol v-else class="flex flex-col gap-1">
        <li
          v-for="(item, idx) in ranking"
          :key="`${item.userId}-${idx}`"
          class="flex items-center justify-between rounded p-2 text-sm"
          :class="currentUserId === item.userId
            ? 'bg-primary-50 dark:bg-primary-950 font-semibold'
            : 'hover:bg-surface-50 dark:hover:bg-surface-800'"
        >
          <div class="flex items-center gap-2">
            <span
              class="inline-flex w-7 h-7 items-center justify-center rounded-full text-xs"
              :class="idx < 3
                ? 'bg-primary text-primary-contrast font-bold'
                : 'bg-surface-100 dark:bg-surface-800 text-surface-600 dark:text-surface-300'"
            >
              {{ idx + 1 }}
            </span>
            <span>user #{{ item.userId }}</span>
          </div>
          <span class="text-surface-500">
            {{ formatScore(item.score) }}
          </span>
        </li>
      </ol>
    </div>
  </div>
</template>
