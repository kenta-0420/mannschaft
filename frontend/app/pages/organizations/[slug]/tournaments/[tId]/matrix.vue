<script setup lang="ts">
/**
 * F08.7 順位UI Wave1: 対戦マトリクス（総当たり表）ページ。
 *
 * ルート `[id]`=組織 publicId / `[tId]`=tournamentId。
 * ディビジョンごとに getMatrix を取得し、participant × participant の総当たり表
 * （行=home・列=away・セル=スコア/結果）を表示する。マトリクス PDF DL に対応。
 *
 * セルキーは BE と同じ `${homeParticipantId}_${awayParticipantId}`（buildMatrixGrid で整形）。
 * 可視性: 不可視大会は Wave0 BE が 404 を返す。FE は握り潰さず i18n で提示する。
 */
import type {
  TournamentResponse,
  TournamentDivision,
  TournamentMatrix,
} from '~/types/tournament'
import {
  buildMatrixGrid,
  matrixCellScoreText,
  type MatrixGrid,
  type MatrixGridCell,
} from '~/utils/tournamentStandings'
import { downloadBlob } from '~/utils/downloadBlob'

definePageMeta({ layout: 'organization', middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const notification = useNotification()
const orgId = String(route.params.slug)
const tId = Number(route.params.tId)

const { getTournament, getDivisions } = useTournamentApi()
const { getMatrix, getMatrixPdf } = useTournamentBracket()

const tournament = ref<TournamentResponse | null>(null)
const divisions = ref<TournamentDivision[]>([])
const activeDivisionId = ref<number | null>(null)

const loading = ref(true)
const loadError = ref<'forbidden' | 'error' | null>(null)

const matrixMap = ref<Record<number, TournamentMatrix>>({})
const matrixLoading = ref<Record<number, boolean>>({})
const downloadingPdf = ref(false)

const activeGrid = computed<MatrixGrid>(() => {
  if (activeDivisionId.value == null) return { columns: [], rows: [] }
  return buildMatrixGrid(matrixMap.value[activeDivisionId.value])
})

function cellText(c: MatrixGridCell): string {
  if (c.isDiagonal) return '—'
  const text = matrixCellScoreText(c.cell)
  return text || t('tournament.matrix.notPlayed')
}

// ===== データ取得 =====

async function loadMatrixForDivision(divId: number) {
  if (matrixMap.value[divId] !== undefined) return
  matrixLoading.value[divId] = true
  try {
    const res = await getMatrix(orgId, tId, divId)
    matrixMap.value[divId] = res.data
  } catch {
    notification.error(t('tournament.matrix.loadFailed'))
  } finally {
    matrixLoading.value[divId] = false
  }
}

function onTabChange(divId: number) {
  activeDivisionId.value = divId
  loadMatrixForDivision(divId)
}

async function onDownloadPdf() {
  if (activeDivisionId.value == null || downloadingPdf.value) return
  const divId = activeDivisionId.value
  downloadingPdf.value = true
  try {
    const blob = await getMatrixPdf(orgId, tId, divId)
    downloadBlob(blob, `matrix-${tId}-${divId}.pdf`)
  } catch {
    notification.error(t('tournament.matrix.pdfFailed'))
  } finally {
    downloadingPdf.value = false
  }
}

async function load() {
  loading.value = true
  loadError.value = null
  try {
    const [tRes, divRes] = await Promise.all([
      getTournament(orgId, tId),
      getDivisions(orgId, tId),
    ])
    tournament.value = tRes.data
    divisions.value = divRes.data ?? []
    if (divisions.value.length > 0) {
      const first = divisions.value[0]!
      activeDivisionId.value = first.id
      await loadMatrixForDivision(first.id)
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
      <BackButton :to="`/organizations/${orgId}/tournaments/${tId}`" :label="$t('tournament.matrix.back')" />
    </div>

    <h1 class="mb-4 text-xl font-bold">
      {{ tournament?.content?.name ?? '' }}
      <span class="ml-1 text-base font-normal text-surface-500">{{ $t('tournament.matrix.title') }}</span>
    </h1>

    <PageLoading v-if="loading" size="40px" />

    <DashboardEmptyState
      v-else-if="loadError === 'forbidden'"
      icon="pi pi-lock"
      :message="$t('tournament.matrix.forbidden')"
    />

    <DashboardEmptyState
      v-else-if="loadError === 'error'"
      icon="pi pi-exclamation-triangle"
      :message="$t('tournament.matrix.loadFailed')"
    />

    <DashboardEmptyState
      v-else-if="divisions.length === 0"
      icon="pi pi-sitemap"
      :message="$t('tournament.matrix.noDivisions')"
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
            <div class="mb-3 mt-2 flex items-center justify-end">
              <Button
                :label="$t('tournament.matrix.downloadPdf')"
                icon="pi pi-file-pdf"
                size="small"
                outlined
                severity="secondary"
                :loading="downloadingPdf"
                data-testid="matrix-pdf-button"
                @click="onDownloadPdf"
              />
            </div>

            <PageLoading v-if="matrixLoading[div.id]" size="32px" />
            <template v-else>
              <DashboardEmptyState
                v-if="activeGrid.rows.length === 0"
                icon="pi pi-table"
                :message="$t('tournament.matrix.empty')"
              />
              <div v-else class="overflow-x-auto">
                <table class="min-w-full border-collapse text-center text-sm" data-testid="matrix-table">
                  <thead>
                    <tr>
                      <th class="sticky left-0 z-10 border border-surface-200 bg-surface-50 px-2 py-1.5 text-left dark:border-surface-700 dark:bg-surface-800">
                        {{ $t('tournament.matrix.homeAway') }}
                      </th>
                      <th
                        v-for="col in activeGrid.columns"
                        :key="`col-${col.participantId}`"
                        class="border border-surface-200 bg-surface-50 px-2 py-1.5 dark:border-surface-700 dark:bg-surface-800"
                      >
                        {{ col.teamName }}
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="row in activeGrid.rows" :key="`row-${row.participantId}`">
                      <th class="sticky left-0 z-10 border border-surface-200 bg-surface-50 px-2 py-1.5 text-left font-medium dark:border-surface-700 dark:bg-surface-800">
                        {{ row.teamName }}
                      </th>
                      <td
                        v-for="(c, ci) in row.cells"
                        :key="`cell-${row.participantId}-${ci}`"
                        class="border border-surface-200 px-2 py-1.5 dark:border-surface-700"
                        :class="c.isDiagonal ? 'bg-surface-100 text-surface-400 dark:bg-surface-800' : ''"
                      >
                        {{ cellText(c) }}
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </template>
          </TabPanel>
        </TabPanels>
      </Tabs>
    </template>
  </div>
</template>
