<script setup lang="ts">
/**
 * F08.7 順位UI Wave1: 個人ランキングページ（得点王等のカテゴリ別）。
 *
 * ルート `[id]`=組織 publicId / `[tId]`=tournamentId。
 * getRankings でカテゴリ一覧（statKey/ラベル/単位/首位）を取得し、
 * statKey 切替で getIndividualRankings を取得して上位を BaseChart(bar) で可視化＋表で表示する。
 * カテゴリ別 PDF ダウンロードに対応する。
 *
 * 可視性: 不可視大会は Wave0 BE が 404 を返す。FE は握り潰さず
 * 「閲覧権限がありません」を i18n で提示する。
 */
import type {
  TournamentResponse,
  RankingCategory,
  IndividualRanking,
} from '~/types/tournament'
import { buildRankingChartData, rankingValueText } from '~/utils/tournamentStandings'
import { downloadBlob } from '~/utils/downloadBlob'

definePageMeta({ layout: 'organization', middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const notification = useNotification()
const orgId = String(route.params.slug)
const tId = Number(route.params.tId)

const { getTournament } = useTournamentApi()
const { getRankings, getIndividualRankings, getRankingsPdf } = useTournamentBracket()

const tournament = ref<TournamentResponse | null>(null)
const categories = ref<RankingCategory[]>([])
const activeStatKey = ref<string | null>(null)

const loading = ref(true)
const loadError = ref<'forbidden' | 'error' | null>(null)

const rankingsMap = ref<Record<string, IndividualRanking[]>>({})
const rankingsLoading = ref<Record<string, boolean>>({})
const downloadingPdf = ref(false)

const activeCategory = computed<RankingCategory | null>(() =>
  categories.value.find((c) => c.statKey === activeStatKey.value) ?? null,
)
const activeRankings = computed<IndividualRanking[]>(() =>
  activeStatKey.value != null ? (rankingsMap.value[activeStatKey.value] ?? []) : [],
)

// userId → 表示名の解決手段が BE に無いため、現状は #userId 表示にフォールバックする。
// Wave2 で displayName が提供されたらここを差し替える。
function resolveName(): string | undefined {
  return undefined
}

const chartData = computed(() =>
  buildRankingChartData(
    activeRankings.value,
    activeCategory.value?.rankingLabel || t('tournament.rankings.value'),
    resolveName,
  ),
)

function valueText(r: IndividualRanking): string {
  return rankingValueText(r, activeCategory.value?.unit)
}

// ===== データ取得 =====

async function loadRankingsForStat(statKey: string) {
  if (rankingsMap.value[statKey] !== undefined) return
  rankingsLoading.value[statKey] = true
  try {
    const res = await getIndividualRankings(orgId, tId, statKey, { page: 0, size: 50 })
    rankingsMap.value[statKey] = res.data ?? []
  } catch {
    notification.error(t('tournament.rankings.loadFailed'))
  } finally {
    rankingsLoading.value[statKey] = false
  }
}

function onTabChange(statKey: string) {
  activeStatKey.value = statKey
  loadRankingsForStat(statKey)
}

async function onDownloadPdf() {
  if (activeStatKey.value == null || downloadingPdf.value) return
  const statKey = activeStatKey.value
  downloadingPdf.value = true
  try {
    const blob = await getRankingsPdf(orgId, tId, statKey)
    downloadBlob(blob, `rankings-${tId}-${statKey}.pdf`)
  } catch {
    notification.error(t('tournament.rankings.pdfFailed'))
  } finally {
    downloadingPdf.value = false
  }
}

async function load() {
  loading.value = true
  loadError.value = null
  try {
    const [tRes, rRes] = await Promise.all([
      getTournament(orgId, tId),
      getRankings(orgId, tId),
    ])
    tournament.value = tRes.data
    categories.value = rRes.data?.categories ?? []
    if (categories.value.length > 0) {
      const first = categories.value[0]!
      activeStatKey.value = first.statKey
      await loadRankingsForStat(first.statKey)
    }
  } catch (e) {
    const status = (e as { response?: { status?: number }; statusCode?: number })?.response?.status
      ?? (e as { statusCode?: number })?.statusCode
    loadError.value = status === 403 || status === 404 ? 'forbidden' : 'error'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between gap-3">
      <BackButton :to="`/organizations/${orgId}/tournaments/${tId}`" :label="$t('tournament.rankings.back')" />
    </div>

    <h1 class="mb-4 text-xl font-bold">
      {{ tournament?.content?.name ?? '' }}
      <span class="ml-1 text-base font-normal text-surface-500">{{ $t('tournament.rankings.title') }}</span>
    </h1>

    <PageLoading v-if="loading" size="40px" />

    <DashboardEmptyState
      v-else-if="loadError === 'forbidden'"
      icon="pi pi-lock"
      :message="$t('tournament.rankings.forbidden')"
    />

    <DashboardEmptyState
      v-else-if="loadError === 'error'"
      icon="pi pi-exclamation-triangle"
      :message="$t('tournament.rankings.loadFailed')"
    />

    <DashboardEmptyState
      v-else-if="categories.length === 0"
      icon="pi pi-chart-bar"
      :message="$t('tournament.rankings.noCategories')"
    />

    <template v-else>
      <Tabs
        v-if="activeStatKey != null"
        :value="activeStatKey"
        @update:value="onTabChange($event as string)"
      >
        <TabList>
          <Tab v-for="cat in categories" :key="cat.statKey" :value="cat.statKey">
            {{ cat.name }}
          </Tab>
        </TabList>

        <TabPanels>
          <TabPanel v-for="cat in categories" :key="cat.statKey" :value="cat.statKey">
            <div class="mb-3 mt-2 flex items-center justify-end">
              <Button
                :label="$t('tournament.rankings.downloadPdf')"
                icon="pi pi-file-pdf"
                size="small"
                outlined
                severity="secondary"
                :loading="downloadingPdf"
                data-testid="rankings-pdf-button"
                @click="onDownloadPdf"
              />
            </div>

            <PageLoading v-if="rankingsLoading[cat.statKey]" size="32px" />
            <template v-else>
              <DashboardEmptyState
                v-if="activeRankings.length === 0"
                icon="pi pi-chart-bar"
                :message="$t('tournament.rankings.empty')"
              />
              <template v-else>
                <!-- 上位の bar チャート可視化 -->
                <div class="mb-6">
                  <BaseChart
                    type="bar"
                    :data="chartData"
                    :empty-message="$t('tournament.rankings.empty')"
                    height-class="h-64"
                  />
                </div>

                <!-- ランキング表 -->
                <DataTable
                  :value="activeRankings"
                  class="text-sm"
                  striped-rows
                  data-testid="rankings-table"
                >
                  <Column :header="$t('tournament.rankings.rank')" style="width: 4rem">
                    <template #body="{ data }">
                      {{ data.rank ?? '-' }}
                    </template>
                  </Column>
                  <Column :header="$t('tournament.rankings.player')">
                    <template #body="{ data }">
                      {{ data.context?.userId != null ? `#${data.context.userId}` : '-' }}
                    </template>
                  </Column>
                  <Column :header="$t('tournament.rankings.matchesPlayed')" style="width: 6rem">
                    <template #body="{ data }">
                      {{ data.context?.matchesPlayed ?? 0 }}
                    </template>
                  </Column>
                  <Column :header="cat.rankingLabel || $t('tournament.rankings.value')" style="width: 7rem">
                    <template #body="{ data }">
                      <span class="font-semibold">{{ valueText(data) }}</span>
                    </template>
                  </Column>
                </DataTable>
              </template>
            </template>
          </TabPanel>
        </TabPanels>
      </Tabs>
    </template>
  </div>
</template>
