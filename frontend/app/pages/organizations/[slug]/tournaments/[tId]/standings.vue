<script setup lang="ts">
/**
 * F08.7 順位UI Wave1: 大会順位表ページ（ディビジョンタブ切替）。
 *
 * ルート `[id]`=組織 publicId / `[tId]`=tournamentId。
 * ディビジョンごとに getStandings を取得し、順位 / チーム / 試合数 / 勝分敗 /
 * 得失点差 / 勝点 / 直近フォーム を表示する。
 *
 * 可視性: 不可視大会は Wave0 BE が 404 を返す。FE は握り潰さず
 * 「閲覧権限がありません」を i18n で提示する（症状を隠さない）。
 * 再計算（recalculate）は書込系のため org 管理者（ADMIN/DEPUTY_ADMIN）のみ表示する。
 */
import type {
  TournamentResponse,
  TournamentDivision,
  TournamentStanding,
} from '~/types/tournament'
import { downloadBlob } from '~/utils/downloadBlob'

definePageMeta({ layout: 'organization', middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const notification = useNotification()
const orgId = String(route.params.slug)
const tId = Number(route.params.tId)

const { getTournament, getDivisions } = useTournamentApi()
const { getStandings, getStandingsPdf, recalculateStandings } = useTournamentBracket()
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('organization', orgId)

const tournament = ref<TournamentResponse | null>(null)
const divisions = ref<TournamentDivision[]>([])
const activeDivisionId = ref<number | null>(null)

const loading = ref(true)
/** 'forbidden' = 403/404（閲覧権限なし）、'error' = その他取得失敗 */
const loadError = ref<'forbidden' | 'error' | null>(null)

const standingsMap = ref<Record<number, TournamentStanding[]>>({})
const standingsLoading = ref<Record<number, boolean>>({})
const recalculating = ref(false)
const downloadingPdf = ref(false)

const activeStandings = computed<TournamentStanding[]>(() =>
  activeDivisionId.value != null ? (standingsMap.value[activeDivisionId.value] ?? []) : [],
)

// ===== 表示ヘルパー =====

/** 直近フォーム文字列（例 "WWLDW"）を1文字ずつ配列化（Tag 表示用）。 */
function formChars(form: string | null | undefined): string[] {
  if (!form) return []
  return form.split('')
}

function formSeverity(c: string): string {
  switch (c.toUpperCase()) {
    case 'W':
      return 'success'
    case 'D':
      return 'warn'
    case 'L':
      return 'danger'
    default:
      return 'secondary'
  }
}

// ===== データ取得 =====

async function loadStandingsForDivision(divId: number) {
  if (standingsMap.value[divId] !== undefined) return
  standingsLoading.value[divId] = true
  try {
    const res = await getStandings(orgId, tId, divId)
    standingsMap.value[divId] = res.data ?? []
  } catch {
    notification.error(t('tournament.standings.loadFailed'))
  } finally {
    standingsLoading.value[divId] = false
  }
}

function onTabChange(divId: number) {
  activeDivisionId.value = divId
  loadStandingsForDivision(divId)
}

async function onRecalculate() {
  if (activeDivisionId.value == null || recalculating.value) return
  const divId = activeDivisionId.value
  recalculating.value = true
  try {
    await recalculateStandings(orgId, tId, divId)
    // 再計算後はキャッシュを破棄して再取得する（divId のエントリを除外して再構築）。
    standingsMap.value = Object.fromEntries(
      Object.entries(standingsMap.value).filter(([k]) => Number(k) !== divId),
    )
    await loadStandingsForDivision(divId)
    notification.success(t('tournament.standings.recalculated'))
  } catch {
    notification.error(t('tournament.standings.recalculateFailed'))
  } finally {
    recalculating.value = false
  }
}

async function onDownloadPdf() {
  if (activeDivisionId.value == null || downloadingPdf.value) return
  const divId = activeDivisionId.value
  downloadingPdf.value = true
  try {
    const blob = await getStandingsPdf(orgId, tId, divId)
    downloadBlob(blob, `standings-${tId}-${divId}.pdf`)
  } catch {
    notification.error(t('tournament.standings.pdfFailed'))
  } finally {
    downloadingPdf.value = false
  }
}

async function load() {
  loading.value = true
  loadError.value = null
  try {
    await loadPermissions()
    const [tRes, divRes] = await Promise.all([
      getTournament(orgId, tId),
      getDivisions(orgId, tId),
    ])
    tournament.value = tRes.data
    divisions.value = divRes.data ?? []
    if (divisions.value.length > 0) {
      const first = divisions.value[0]!
      activeDivisionId.value = first.id
      await loadStandingsForDivision(first.id)
    }
  } catch (e) {
    // 403/404 は「閲覧権限なし」として扱う（不可視大会は BE が 404）。
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
      <BackButton :to="`/organizations/${orgId}/tournaments/${tId}`" :label="$t('tournament.standings.back')" />
    </div>

    <h1 class="mb-4 text-xl font-bold">
      {{ tournament?.content?.name ?? '' }}
      <span class="ml-1 text-base font-normal text-surface-500">{{ $t('tournament.standings.title') }}</span>
    </h1>

    <PageLoading v-if="loading" size="40px" />

    <DashboardEmptyState
      v-else-if="loadError === 'forbidden'"
      icon="pi pi-lock"
      :message="$t('tournament.standings.forbidden')"
    />

    <DashboardEmptyState
      v-else-if="loadError === 'error'"
      icon="pi pi-exclamation-triangle"
      :message="$t('tournament.standings.loadFailed')"
    />

    <DashboardEmptyState
      v-else-if="divisions.length === 0"
      icon="pi pi-sitemap"
      :message="$t('tournament.standings.noDivisions')"
    />

    <template v-else>
      <Tabs
        v-if="activeDivisionId != null"
        :value="activeDivisionId"
        @update:value="onTabChange($event as number)"
      >
        <TabList>
          <Tab v-for="div in divisions" :key="div.id" :value="div.id">
            {{ div.name }}
          </Tab>
        </TabList>

        <TabPanels>
          <TabPanel v-for="div in divisions" :key="div.id" :value="div.id">
            <div class="mb-3 mt-2 flex items-center justify-end gap-2">
              <Button
                :label="$t('tournament.standings.downloadPdf')"
                icon="pi pi-file-pdf"
                size="small"
                outlined
                severity="secondary"
                :loading="downloadingPdf"
                data-testid="standings-pdf-button"
                @click="onDownloadPdf"
              />
              <Button
                v-if="isAdminOrDeputy"
                :label="$t('tournament.standings.recalculate')"
                icon="pi pi-refresh"
                size="small"
                outlined
                :loading="recalculating"
                data-testid="standings-recalculate-button"
                @click="onRecalculate"
              />
            </div>

            <PageLoading v-if="standingsLoading[div.id]" size="32px" />
            <template v-else>
              <DashboardEmptyState
                v-if="activeStandings.length === 0"
                icon="pi pi-list"
                :message="$t('tournament.standings.empty')"
              />
              <DataTable
                v-else
                :value="activeStandings"
                class="text-sm"
                striped-rows
                data-testid="standings-table"
              >
                <Column :header="$t('tournament.standings.rank')" style="width: 4rem">
                  <template #body="{ data }">
                    {{ data.team?.rank ?? '-' }}
                  </template>
                </Column>
                <Column :header="$t('tournament.standings.team')">
                  <template #body="{ data }">
                    {{ data.team?.teamName ?? '-' }}
                  </template>
                </Column>
                <Column :header="$t('tournament.standings.played')" style="width: 4rem">
                  <template #body="{ data }">
                    {{ data.record?.played ?? 0 }}
                  </template>
                </Column>
                <Column :header="$t('tournament.standings.winDrawLoss')" style="width: 7rem">
                  <template #body="{ data }">
                    {{ data.record?.wins ?? 0 }}/{{ data.record?.draws ?? 0 }}/{{ data.record?.losses ?? 0 }}
                  </template>
                </Column>
                <Column :header="$t('tournament.standings.goalDiff')" style="width: 5rem">
                  <template #body="{ data }">
                    {{ data.score?.scoreDifference ?? 0 }}
                  </template>
                </Column>
                <Column :header="$t('tournament.standings.points')" style="width: 4rem">
                  <template #body="{ data }">
                    <span class="font-semibold">{{ data.score?.points ?? 0 }}</span>
                  </template>
                </Column>
                <Column :header="$t('tournament.standings.form')" style="width: 8rem">
                  <template #body="{ data }">
                    <div class="flex gap-1">
                      <Tag
                        v-for="(c, i) in formChars(data.form)"
                        :key="i"
                        :value="c"
                        :severity="formSeverity(c)"
                        rounded
                        class="!px-1.5 !py-0 !text-xs"
                      />
                      <span v-if="formChars(data.form).length === 0" class="text-surface-400">-</span>
                    </div>
                  </template>
                </Column>
              </DataTable>
            </template>
          </TabPanel>
        </TabPanels>
      </Tabs>
    </template>
  </div>
</template>
