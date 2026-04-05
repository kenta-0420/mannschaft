<script setup lang="ts">
import type { PointSummary, UserBadge, RankingEntry } from '~/types/gamification'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const teamId = computed(() => Number(route.params.id))
const gamificationApi = useGamificationApi()
const notification = useNotification()

const points = ref<PointSummary | null>(null)
const badges = ref<UserBadge[]>([])
const rankings = ref<RankingEntry[]>([])
const loading = ref(true)
const activeTab = ref('0')
const rankingPeriod = ref<'WEEKLY' | 'MONTHLY' | 'YEARLY'>('MONTHLY')

async function loadData() {
  loading.value = true
  try {
    const [pts, bdg, rnk] = await Promise.all([
      gamificationApi.getMyPoints(teamId.value),
      gamificationApi.getMyBadges(teamId.value),
      gamificationApi.getRankings(teamId.value, rankingPeriod.value),
    ])
    points.value = pts
    badges.value = bdg
    rankings.value = rnk
  } catch {
    notification.error('ゲーミフィケーションデータの取得に失敗しました')
  } finally {
    loading.value = false
  }
}

async function loadRankings() {
  try {
    rankings.value = await gamificationApi.getRankings(teamId.value, rankingPeriod.value)
  } catch {
    notification.error('ランキングの取得に失敗しました')
  }
}

watch(rankingPeriod, loadRankings)
onMounted(loadData)
</script>

<template>
  <div class="mx-auto max-w-4xl">
    <h1 class="mb-6 text-2xl font-bold">ポイント・バッジ</h1>

    <PageLoading v-if="loading" />

    <template v-else>
      <div v-if="points" class="mb-6 grid gap-4 md:grid-cols-3">
        <Card>
          <template #content>
            <p class="text-sm text-surface-500">累計ポイント</p>
            <p class="text-3xl font-bold text-primary">{{ points.totalPoints.toLocaleString() }}</p>
          </template>
        </Card>
        <Card>
          <template #content>
            <p class="text-sm text-surface-500">今月のポイント</p>
            <p class="text-3xl font-bold text-green-600">
              {{ points.monthlyPoints.toLocaleString() }}
            </p>
          </template>
        </Card>
        <Card>
          <template #content>
            <p class="text-sm text-surface-500">獲得バッジ</p>
            <p class="text-3xl font-bold text-yellow-600">{{ points.badgeCount }}</p>
          </template>
        </Card>
      </div>

      <Tabs v-model:value="activeTab">
        <TabList>
          <Tab value="0">バッジ</Tab>
          <Tab value="1">ランキング</Tab>
        </TabList>
        <TabPanels>
          <TabPanel value="0">
            <div v-if="badges.length === 0" class="py-8 text-center text-surface-500">
              まだバッジを獲得していません
            </div>
            <div v-else class="grid gap-4 sm:grid-cols-3 lg:grid-cols-4">
              <div
                v-for="ub in badges"
                :key="ub.id"
                class="flex flex-col items-center rounded-xl border border-surface-300 bg-surface-0 p-4 dark:border-surface-600 dark:bg-surface-800"
              >
                <img
                  v-if="ub.badge.iconUrl"
                  :src="ub.badge.iconUrl"
                  alt=""
                  class="mb-2 h-16 w-16"
                />
                <div
                  v-else
                  class="mb-2 flex h-16 w-16 items-center justify-center rounded-full bg-yellow-100 text-2xl"
                >
                  <i class="pi pi-star-fill text-yellow-500" />
                </div>
                <p class="text-sm font-semibold text-center">{{ ub.badge.name }}</p>
                <p class="text-xs text-surface-500 text-center">{{ ub.badge.description }}</p>
                <p class="mt-1 text-xs text-surface-400">
                  {{ new Date(ub.awardedAt).toLocaleDateString('ja-JP') }}
                </p>
              </div>
            </div>
          </TabPanel>
          <TabPanel value="1">
            <div class="mb-4 flex justify-end">
              <SelectButton
                v-model="rankingPeriod"
                :options="[
                  { label: '週間', value: 'WEEKLY' },
                  { label: '月間', value: 'MONTHLY' },
                  { label: '年間', value: 'YEARLY' },
                ]"
                option-label="label"
                option-value="value"
              />
            </div>
            <div v-if="rankings.length === 0" class="py-8 text-center text-surface-500">
              ランキングデータがありません
            </div>
            <div v-else class="space-y-2">
              <div
                v-for="entry in rankings"
                :key="entry.userId"
                class="flex items-center gap-4 rounded-xl border border-surface-300 bg-surface-0 p-3 dark:border-surface-600 dark:bg-surface-800"
              >
                <span
                  class="flex h-8 w-8 items-center justify-center rounded-full text-sm font-bold"
                  :class="
                    entry.rank <= 3
                      ? 'bg-yellow-100 text-yellow-700'
                      : 'bg-surface-100 text-surface-600'
                  "
                >
                  {{ entry.rank }}
                </span>
                <img
                  v-if="entry.avatarUrl"
                  :src="entry.avatarUrl"
                  alt=""
                  class="h-10 w-10 rounded-full object-cover"
                />
                <div
                  v-else
                  class="flex h-10 w-10 items-center justify-center rounded-full bg-primary/10 text-primary"
                >
                  <i class="pi pi-user" />
                </div>
                <div class="flex-1">
                  <p class="font-medium">{{ entry.displayName }}</p>
                </div>
                <p class="text-lg font-bold text-primary">{{ entry.points.toLocaleString() }} pt</p>
              </div>
            </div>
          </TabPanel>
        </TabPanels>
      </Tabs>
    </template>
  </div>
</template>
