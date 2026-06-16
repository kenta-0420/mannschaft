<script setup lang="ts">
/**
 * F08.7 順位UI Wave2: 管理者向けスコア入力グリッド。
 *
 * division → matchday を選び、その節の対戦カード一覧（home/away 参加チーム名）に
 * スコア入力欄（home/away）を並べ、一括保存で batchUpdateScores を呼ぶ。
 * 各 fixture の楽観ロック version を必ず同梱する（version 不一致は BE が 409 を返す）。
 *
 * 保存後は StandingsRecalc 経路（BE が batch 内で StandingsRecalculationEvent を発火）で
 * 順位が自動反映されるため、保存成功イベントを親へ通知して順位表側の再取得を促す。
 *
 * 認可: 本コンポーネントは org 管理者（ADMIN/DEPUTY_ADMIN）にのみ表示される前提。
 * 最終防衛は Wave0 の @PreAuthorize（BE）。FE は症状を隠さず 409/権限エラーを i18n で提示する。
 */
import type {
  TournamentDivision,
  TournamentMatchday,
  TournamentMatrix,
  TournamentScoringDto,
} from '~/types/tournament'
import {
  buildParticipantNameMap,
  buildScoreEntryRows,
  buildBatchScorePayload,
  deriveScoreEntryColumnFlags,
  isConflictError,
  isForbiddenError,
  type ScoreEntryRow,
} from '~/utils/tournamentScoreEntry'

const props = defineProps<{
  orgId: string
  tournamentId: number
  divisions: TournamentDivision[]
  /** 大会の採点設定。PK 入力欄の出し分けに使う（延長別欄は廃止・セット制は別系統）。 */
  scoring?: TournamentScoringDto | null
}>()

// PK/セット 入力欄の出し分けフラグ（hasPenalties / hasSets 由来）。延長別欄は Phase 5b-3 で廃止。
const columnFlags = computed(() => deriveScoreEntryColumnFlags(props.scoring))

// セット入力列の本数（行ごとのセット枠数の最大。通常は全行同数だが取りこぼし行に追従）。
const setColumnCount = computed(() =>
  rows.value.reduce((max, r) => Math.max(max, r.sets.length), 0),
)
// 0..setColumnCount-1 の列インデックス配列（v-for 用）。
const setColumnIndexes = computed(() =>
  Array.from({ length: setColumnCount.value }, (_, i) => i),
)

const emit = defineEmits<{
  /** 一括保存が成功し順位が再計算された（親で順位表/マトリクスを再取得する用）。 */
  (e: 'saved', divisionId: number): void
}>()

const { t } = useI18n()
const notification = useNotification()
const { getMatchdays, getMatrix, batchUpdateScores } = useTournamentBracket()

const activeDivisionId = ref<number | null>(null)
const matchdays = ref<TournamentMatchday[]>([])
const matrix = ref<TournamentMatrix | null>(null)
const activeMatchdayId = ref<number | null>(null)
const rows = ref<ScoreEntryRow[]>([])

const loadingMatchdays = ref(false)
const saving = ref(false)

// ===== データ取得 =====

async function loadMatchdays(divId: number) {
  loadingMatchdays.value = true
  matchdays.value = []
  activeMatchdayId.value = null
  rows.value = []
  try {
    // 参加チーム名の解決はマトリクス（participantId→teamName）を正本にする。
    const [mdRes, matrixRes] = await Promise.all([
      getMatchdays(props.orgId, props.tournamentId, divId),
      getMatrix(props.orgId, props.tournamentId, divId),
    ])
    matchdays.value = mdRes.data ?? []
    matrix.value = matrixRes.data ?? null
    if (matchdays.value.length > 0) {
      selectMatchday(matchdays.value[0]!.id)
    }
  } catch (e) {
    if (isForbiddenError(e)) {
      notification.error(t('tournament.scoreEntry.forbidden'))
    } else {
      notification.error(t('tournament.scoreEntry.loadFailed'))
    }
  } finally {
    loadingMatchdays.value = false
  }
}

function selectMatchday(mdId: number) {
  activeMatchdayId.value = mdId
  const md = matchdays.value.find((m) => m.id === mdId)
  const nameMap = buildParticipantNameMap(matrix.value)
  rows.value = buildScoreEntryRows(md?.matches ?? [], nameMap, columnFlags.value)
}

function onDivisionChange(divId: number) {
  activeDivisionId.value = divId
  loadMatchdays(divId)
}

// ===== 一括保存 =====

async function onSave() {
  if (saving.value || activeDivisionId.value == null || activeMatchdayId.value == null) return
  const payload = buildBatchScorePayload(rows.value, columnFlags.value)
  if (payload === null) {
    // 不正入力（片方のみ・非数値・負数等）。症状を隠さず明示的に弾く。
    notification.error(t('tournament.scoreEntry.invalidInput'))
    return
  }
  if (payload.scores.length === 0) {
    notification.info(t('tournament.scoreEntry.nothingToSave'))
    return
  }
  saving.value = true
  try {
    // batchUpdateScores の body は Record<string, unknown>。型付きの BatchScorePayload を
    // そのまま渡せないため、構造を保ったまま Record として渡す（JSON シリアライズは同一）。
    await batchUpdateScores(
      props.orgId,
      props.tournamentId,
      activeDivisionId.value,
      activeMatchdayId.value,
      payload as unknown as Record<string, unknown>,
    )
    notification.success(t('tournament.scoreEntry.saved'))
    emit('saved', activeDivisionId.value)
    // version が進むため当該節を再取得して最新 version を入力欄へ反映する。
    await refreshActiveMatchday()
  } catch (e) {
    if (isConflictError(e)) {
      // 楽観ロック衝突。握り潰さず再取得を促すメッセージを出す。
      notification.error(t('tournament.scoreEntry.conflict'))
      await refreshActiveMatchday()
    } else if (isForbiddenError(e)) {
      notification.error(t('tournament.scoreEntry.forbidden'))
    } else {
      notification.error(t('tournament.scoreEntry.saveFailed'))
    }
  } finally {
    saving.value = false
  }
}

/** 現在の division/matchday を再取得して version・スコアを最新化する。 */
async function refreshActiveMatchday() {
  if (activeDivisionId.value == null) return
  const divId = activeDivisionId.value
  const mdId = activeMatchdayId.value
  try {
    const mdRes = await getMatchdays(props.orgId, props.tournamentId, divId)
    matchdays.value = mdRes.data ?? []
    if (mdId != null && matchdays.value.some((m) => m.id === mdId)) {
      selectMatchday(mdId)
    } else if (matchdays.value.length > 0) {
      selectMatchday(matchdays.value[0]!.id)
    }
  } catch {
    // 再取得失敗は致命的ではない（次回操作で再試行される）。サイレント継続。
  }
}

/** CSV 取込成功時: 当該節を再取得し、親へ順位再計算（saved）を通知する。 */
async function onCsvImported() {
  await refreshActiveMatchday()
  if (activeDivisionId.value != null) {
    emit('saved', activeDivisionId.value)
  }
}

// CSV 取込成功時に親→子へ通知して再取得させる用の公開メソッド。
defineExpose({ refreshActiveMatchday })

onMounted(() => {
  if (props.divisions.length > 0) {
    onDivisionChange(props.divisions[0]!.id)
  }
})
</script>

<template>
  <div data-testid="score-entry-grid">
    <DashboardEmptyState
      v-if="divisions.length === 0"
      icon="pi pi-sitemap"
      :message="$t('tournament.scoreEntry.noDivisions')"
    />

    <template v-else>
      <!-- ディビジョン選択 -->
      <Tabs
        v-if="activeDivisionId != null"
        :value="activeDivisionId"
        @update:value="onDivisionChange($event as number)"
      >
        <TabList>
          <Tab v-for="div in divisions" :key="div.id" :value="div.id">
            {{ div.name }}
          </Tab>
        </TabList>

        <TabPanels>
          <TabPanel v-for="div in divisions" :key="div.id" :value="div.id">
            <PageLoading v-if="loadingMatchdays" size="32px" />

            <template v-else>
              <DashboardEmptyState
                v-if="matchdays.length === 0"
                icon="pi pi-calendar"
                :message="$t('tournament.scoreEntry.noMatchdays')"
              />

              <template v-else>
                <!-- 節選択 -->
                <div class="mb-3 mt-2 flex flex-wrap items-center gap-2">
                  <label class="text-sm text-surface-600">{{ $t('tournament.scoreEntry.matchday') }}</label>
                  <Select
                    :model-value="activeMatchdayId"
                    :options="matchdays"
                    option-label="name"
                    option-value="id"
                    class="min-w-[12rem]"
                    data-testid="score-entry-matchday-select"
                    @update:model-value="selectMatchday($event as number)"
                  />
                </div>

                <DashboardEmptyState
                  v-if="rows.length === 0"
                  icon="pi pi-list"
                  :message="$t('tournament.scoreEntry.noMatches')"
                />

                <template v-else>
                  <DataTable
                    :value="rows"
                    class="text-sm"
                    striped-rows
                    data-testid="score-entry-table"
                  >
                    <Column :header="$t('tournament.scoreEntry.home')">
                      <template #body="{ data }">
                        {{ data.homeName }}
                      </template>
                    </Column>
                    <!-- 本戦 home/away 得点欄（セット制では非表示＝合計はセットから自動算出） -->
                    <Column
                      v-if="!columnFlags.showSets"
                      :header="$t('tournament.scoreEntry.homeScore')"
                      style="width: 7rem"
                    >
                      <template #body="{ data }">
                        <InputText
                          v-model="data.homeScore"
                          inputmode="numeric"
                          class="w-16 text-center"
                          data-testid="score-entry-home-input"
                        />
                      </template>
                    </Column>
                    <Column
                      v-if="!columnFlags.showSets"
                      :header="$t('tournament.scoreEntry.awayScore')"
                      style="width: 7rem"
                    >
                      <template #body="{ data }">
                        <InputText
                          v-model="data.awayScore"
                          inputmode="numeric"
                          class="w-16 text-center"
                          data-testid="score-entry-away-input"
                        />
                      </template>
                    </Column>
                    <!-- セット得点欄（hasSets 大会のみ・先取制ゆえ可変本数） -->
                    <Column
                      v-for="si in setColumnIndexes"
                      :key="`set-col-${si}`"
                      :header="$t('tournament.scoreEntry.setHeader', { n: si + 1 })"
                      style="width: 9rem"
                    >
                      <template #body="{ data }">
                        <div
                          v-if="data.sets[si]"
                          class="flex items-center justify-center gap-1"
                        >
                          <InputText
                            v-model="data.sets[si].home"
                            inputmode="numeric"
                            class="w-12 text-center"
                            :aria-label="$t('tournament.scoreEntry.setHomeAria', { n: si + 1 })"
                            data-testid="score-entry-set-home-input"
                          />
                          <span class="text-surface-400">-</span>
                          <InputText
                            v-model="data.sets[si].away"
                            inputmode="numeric"
                            class="w-12 text-center"
                            :aria-label="$t('tournament.scoreEntry.setAwayAria', { n: si + 1 })"
                            data-testid="score-entry-set-away-input"
                          />
                        </div>
                        <span v-else class="text-surface-300">-</span>
                      </template>
                    </Column>
                    <Column :header="$t('tournament.scoreEntry.away')">
                      <template #body="{ data }">
                        {{ data.awayName }}
                      </template>
                    </Column>
                    <!-- 延長別スコア欄は Phase 5b-3 で廃止（延長得点は本戦スコアへ合算済み・05 §H.1 移行表）。 -->
                    <!-- PK スコア（hasPenalties 大会のみ） -->
                    <Column
                      v-if="columnFlags.showPenalties"
                      :header="$t('tournament.scoreEntry.homePenaltyScore')"
                      style="width: 7rem"
                    >
                      <template #body="{ data }">
                        <InputText
                          v-model="data.homePenaltyScore"
                          inputmode="numeric"
                          class="w-16 text-center"
                          data-testid="score-entry-home-penalty-input"
                        />
                      </template>
                    </Column>
                    <Column
                      v-if="columnFlags.showPenalties"
                      :header="$t('tournament.scoreEntry.awayPenaltyScore')"
                      style="width: 7rem"
                    >
                      <template #body="{ data }">
                        <InputText
                          v-model="data.awayPenaltyScore"
                          inputmode="numeric"
                          class="w-16 text-center"
                          data-testid="score-entry-away-penalty-input"
                        />
                      </template>
                    </Column>
                  </DataTable>

                  <!-- PK の入力補助（PK は本戦同点時のみ意味を持つ等） -->
                  <p
                    v-if="columnFlags.showPenalties"
                    class="mt-2 text-xs text-surface-500"
                    data-testid="score-entry-extra-penalty-hint"
                  >
                    {{ $t('tournament.scoreEntry.extraPenaltyHint') }}
                  </p>
                  <!-- セット制の入力補助（勝敗は勝セット数で判定・空セットは送信しない） -->
                  <p
                    v-if="columnFlags.showSets"
                    class="mt-2 text-xs text-surface-500"
                    data-testid="score-entry-sets-hint"
                  >
                    {{ $t('tournament.scoreEntry.setsHint', { n: columnFlags.setsToWin }) }}
                  </p>

                  <div class="mt-4 flex items-center justify-end gap-2">
                    <Button
                      :label="$t('tournament.scoreEntry.save')"
                      icon="pi pi-save"
                      :loading="saving"
                      data-testid="score-entry-save-button"
                      @click="onSave"
                    />
                  </div>

                  <!-- CSV 取込（同じ節に対して。セット制は CSV 様式が未対応のため非表示） -->
                  <ScoreCsvImport
                    v-if="!columnFlags.showSets && activeDivisionId != null && activeMatchdayId != null"
                    :org-id="orgId"
                    :tournament-id="tournamentId"
                    :division-id="activeDivisionId"
                    :matchday-id="activeMatchdayId"
                    :rows="rows"
                    class="mt-6"
                    @imported="onCsvImported"
                  />
                </template>
              </template>
            </template>
          </TabPanel>
        </TabPanels>
      </Tabs>
    </template>
  </div>
</template>
