<script setup lang="ts">
// F08.10 ライブ記録 共通シェル（多競技対応・04_frontend_and_ux.md §G.2 / §G.16）。
//
// 【6-②b 共通シェル化】薄いオーケストレータ。競技非依存の骨格（スコアボード・タイマー操作の枠・
// 大ボタン・undo・タイムライン・相手記録案内・PK スコア入力）を持ち、競技固有の差分
// （タイマー状態機械・イベント入力シート）は `matches.sport` に応じて**競技モジュールを
// 動的 import（lazy-load）**して差し替える。サッカーしか開かないユーザーはバスケのロジック・
// 入力シートを初期バンドルに同梱しない（Vite コード分割・マスター懸念「重くなる」の解消）。
import { effectScope } from 'vue'
import type { MatchEventResponse } from '~/types/match'
import { resolveSportModule, type SportLiveModule } from '~/composables/match/sport/sportModuleRegistry'

definePageMeta({ layout: 'team', middleware: 'auth' })

const route = useRoute()
const router = useRouter()
const teamIdStr = String(route.params.slug)
const matchId = String(route.params.matchId)
const { t } = useI18n()
const notification = useNotification()

const { resolveContext } = useMatchOrgContext()
const matchApi = useMatchApi()
const eventApi = useMatchEventApi()
const grid = useMatchPlayerGrid()
const wakeLock = useWakeLockWithFallback()

const orgId = ref<number | null>(null)
const teamId = ref<number | null>(null)
const ownTeamSide = ref<'HOME' | 'AWAY'>('HOME')
const opponentName = ref<string | null>(null)
const matchStatus = ref<string | null>(null)
/** ライブ記録の可否（BE MatchDetailResponse.canRecordTimeline）。false は閲覧専用。 */
const canRecord = ref(false)
const homePenaltyScore = ref(0)
const awayPenaltyScore = ref(0)

/** 解決済みの競技モジュール（動的 import の結果）。null=ロード前/未対応。 */
const sportModule = ref<SportLiveModule | null>(null)
/** 共通シェルが扱う「前後半系（サッカー/フットサル）」か「クォーター系（バスケ）」か。 */
const isBasketball = computed(() => sportModule.value?.sport === 'BASKETBALL')

// セッションは競技モジュールの createTimer を注入して結線する。
// モジュール解決（動的 import）は非同期＝setup 同期スコープ外で composable を作るため、
// onScopeDispose 等のスコープ依存 API が確実に効くよう専用の effectScope を管理する。
type Session = ReturnType<typeof useMatchLiveSession>
const session = shallowRef<Session | null>(null)
let sessionScope: ReturnType<typeof effectScope> | null = null

const timerState = computed(() => session.value?.timer.state.value ?? 'WAITING')
const isCompleted = computed(
  () => matchStatus.value === 'COMPLETED' || timerState.value === 'COMPLETED',
)

// === UI 状態 ===
const sheetVisible = ref(false)
const starterSetupVisible = ref(false)
const opponentNoticeVisible = ref(false)
const loading = ref(true)

// === undo / 削除 / 編集試行 ===
async function undo(): Promise<void> {
  const s = session.value
  if (!s) return
  try {
    await s.recorder.undoLast()
    notification.success(t('match.live.undo.done'))
    await s.refreshScore()
  } catch {
    notification.error(t('match.live.error.undo_failed'))
  }
}
async function onTimelineDelete(ev: MatchEventResponse): Promise<void> {
  const s = session.value
  if (!s || !ev.id) return
  if (!window.confirm(t('match.live.timeline.delete_confirm'))) return
  try {
    await s.recorder.deleteEvent(ev.id)
    await s.refreshScore()
  } catch {
    // 通知済み
  }
}
function onTimelineSelect(ev: MatchEventResponse): void {
  // 相手チーム記録は直接編集不可（§G.10）。自チーム分の編集 UI は 3-C で拡張。
  if (ev.teamSide != null && ev.teamSide !== ownTeamSide.value) {
    opponentNoticeVisible.value = true
  }
}

// === スタメン設定（前回先発コピー含む・§G.1c / §G.15a）===
async function copyPreviousStarters(): Promise<void> {
  if (orgId.value === null) return
  try {
    grid.copyPreviousStarters(await eventApi.listAppearances(orgId.value, matchId))
  } catch {
    // 通知済み
  }
}
function addManualPlayer(p: { name: string; jerseyNumber: number | null }): void {
  grid.addManualPlayer(p.name, p.jerseyNumber)
}

// === 初期ロード ===
onMounted(async () => {
  const ctx = await resolveContext(teamIdStr)
  orgId.value = ctx?.orgId ?? null
  teamId.value = ctx?.teamId ?? null
  if (orgId.value === null || teamId.value === null) {
    loading.value = false
    return
  }
  let sport: string | null = null
  try {
    const match = await matchApi.getMatch(orgId.value, teamId.value, matchId)
    matchStatus.value = match.status ?? null
    canRecord.value = match.canRecordTimeline ?? false
    ownTeamSide.value = match.homeAway === 'AWAY' ? 'AWAY' : 'HOME'
    opponentName.value = match.opponentName ?? null
    homePenaltyScore.value = match.homePenaltyScore ?? 0
    awayPenaltyScore.value = match.awayPenaltyScore ?? 0
    sport = match.sport ?? null
  } catch {
    notification.error(t('match.live.error.load_match_failed'))
  }

  // 競技モジュールを動的 import（lazy-load）で解決し、その createTimer をセッションへ注入。
  const mod = await resolveSportModule(sport)
  sportModule.value = mod
  sessionScope = effectScope()
  const s = sessionScope.run(() =>
    useMatchLiveSession({
      orgId,
      teamId,
      matchId,
      ownTeamSide,
      createTimer: mod?.createTimer,
    }),
  )
  if (!s) {
    loading.value = false
    return
  }
  s.setMatchStatus(matchStatus.value)
  session.value = s

  try {
    const res = await eventApi.listEvents(orgId.value, matchId)
    s.recorder.setEvents(res.events ?? [])
    s.applyDerivedScore(res)
  } catch {
    // 通知済み
  }
  await grid.loadPlayers(teamIdStr).catch(() => undefined)
  await wakeLock.acquireWakeLock().catch(() => undefined)
  window.addEventListener('online', flushOffline)
  loading.value = false
})

onBeforeUnmount(() => {
  wakeLock.releaseWakeLock()
  window.removeEventListener('online', flushOffline)
  sessionScope?.stop()
})

function flushOffline(): void {
  void session.value?.flushOffline()
}

/** PK 戦中か（前後半系のみ・completeMatch へ PK スコアを渡すか判定）。 */
const isPenaltyShootout = computed(() => timerState.value === 'PENALTY_SHOOTOUT')

function penaltyPayload(): { home: number; away: number } | null {
  return isPenaltyShootout.value
    ? { home: homePenaltyScore.value, away: awayPenaltyScore.value }
    : null
}

async function complete(): Promise<void> {
  await session.value?.completeMatch(penaltyPayload())
}

/**
 * @advance の集約ハンドラ（競技非依存）。
 * 次状態が COMPLETED になる遷移は completeMatch() を経由させ BE status を永続化する
 * （競技モジュールの isNextCompleted で判定＝サッカーは EXTRA_SECOND/PK、バスケは Q4/OT）。
 * それ以外の通常 advance は timer.advance() に委譲（COMPLETED 到達は completeMatch 経路のみ）。
 */
async function handleAdvance(): Promise<void> {
  const s = session.value
  const mod = sportModule.value
  if (!s || !mod) return
  if (mod.isNextCompleted(s.timer.state.value)) {
    await complete()
  } else {
    await s.timer.advance()
  }
}

function goExtra(): void {
  void session.value?.timer.goExtra?.()
}
function goPenalty(): void {
  void session.value?.timer.goPenaltyShootout?.()
}
function goOvertime(): void {
  void session.value?.timer.goOvertime?.()
}

function back(): void {
  void router.push(`/teams/${teamIdStr}/matches`)
}
</script>

<template>
  <div class="mx-auto max-w-2xl pb-28">
    <div class="mb-2 flex items-center gap-2">
      <BackButton :to="`/teams/${teamIdStr}/matches`" @click="back" />
      <PageHeader :title="t('match.live.title')" size="sm" />
    </div>

    <PageLoading v-if="loading || !session" size="40px" />

    <template v-else>
      <MatchScoreboard
        :home-score="session.homeScore.value"
        :away-score="session.awayScore.value"
        :home-penalty-score="homePenaltyScore"
        :away-penalty-score="awayPenaltyScore"
        :opponent-name="opponentName"
        :state="session.timer.state.value"
        :clock="session.timer.displayClock.value"
        :running="session.timer.isRunning.value"
        class="mb-3"
      />

      <!-- タイマー操作（記録権限がある場合のみ）。競技で出し分け（前後半系 / クォーター系）。 -->
      <template v-if="canRecord">
        <MatchTimerControlsBasketball
          v-if="isBasketball"
          class="mb-3"
          :state="session.timer.state.value"
          :current-minute="session.timer.currentMinute.value"
          :running="session.timer.isRunning.value"
          @advance="handleAdvance()"
          @complete="complete()"
          @overtime="goOvertime()"
        />
        <MatchTimerControls
          v-else
          class="mb-3"
          :state="session.timer.state.value"
          :current-minute="session.timer.currentMinute.value"
          :running="session.timer.isRunning.value"
          @advance="handleAdvance()"
          @complete="complete()"
          @extra="goExtra()"
          @penalty="goPenalty()"
        />
      </template>

      <!-- PK 戦スコア入力（前後半系・PENALTY_SHOOTOUT 中・記録権限あり時のみ）。 -->
      <div
        v-if="canRecord && !isBasketball && session.timer.state.value === 'PENALTY_SHOOTOUT'"
        class="mb-3 rounded-lg border border-surface-200 bg-surface-50 p-3"
      >
        <p class="mb-2 text-sm font-medium">{{ t('match.live.penalty.title') }}</p>
        <div class="flex items-center justify-center gap-4">
          <div class="flex flex-col items-center gap-1">
            <span class="text-xs text-surface-500">{{ t('match.live.scoreboard.home') }}</span>
            <InputNumber
              v-model="homePenaltyScore"
              show-buttons
              button-layout="horizontal"
              :min="0"
              :max="99"
              :input-style="{ width: '3rem', textAlign: 'center' }"
              :aria-label="t('match.live.penalty.home_label')"
            />
          </div>
          <span class="text-lg font-bold text-surface-400">-</span>
          <div class="flex flex-col items-center gap-1">
            <span class="text-xs text-surface-500">{{ opponentName ?? t('match.live.scoreboard.away') }}</span>
            <InputNumber
              v-model="awayPenaltyScore"
              show-buttons
              button-layout="horizontal"
              :min="0"
              :max="99"
              :input-style="{ width: '3rem', textAlign: 'center' }"
              :aria-label="t('match.live.penalty.away_label')"
            />
          </div>
        </div>
        <p class="mt-2 text-center text-xs text-surface-500">{{ t('match.live.penalty.hint') }}</p>
      </div>

      <!-- 閲覧専用案内（記録権限なし・§G.9 認可連動） -->
      <p
        v-if="!canRecord"
        class="mb-3 flex items-center gap-2 rounded bg-surface-100 p-2 text-xs text-surface-500"
      >
        <i class="pi pi-eye" />
        {{ t('match.live.read_only_notice') }}
      </p>

      <p v-if="isCompleted" class="mb-3 rounded bg-surface-100 p-2 text-xs text-surface-500">
        {{ t('match.live.completed_notice') }}
      </p>

      <!-- 大ボタン行（記録開始・スタメン設定）— 記録権限がある場合のみ。 -->
      <div v-if="canRecord" class="mb-4 flex flex-col gap-2">
        <Button
          class="w-full !min-h-[3.5rem]"
          :label="t('match.live.title')"
          icon="pi pi-plus-circle"
          @click="sheetVisible = true"
        />
        <Button
          class="w-full"
          severity="secondary"
          outlined
          icon="pi pi-users"
          :label="t('match.live.grid.setup_button')"
          @click="starterSetupVisible = true"
        />
      </div>

      <!-- undo 帯（記録権限がある場合のみ） -->
      <div v-if="canRecord && session.recorder.canUndo.value" class="mb-3 flex justify-end">
        <Button
          text
          severity="secondary"
          icon="pi pi-undo"
          :label="t('match.live.undo.button')"
          @click="undo"
        />
      </div>

      <MatchTimeline
        :events="session.recorder.events.value"
        :own-team-side="ownTeamSide"
        @select="onTimelineSelect"
        @delete="onTimelineDelete"
      />

      <!-- 相手記録の編集案内 -->
      <Dialog
        v-model:visible="opponentNoticeVisible"
        modal
        :header="t('match.live.opponent_notice.title', { team: opponentName ?? t('match.live.scoreboard.away') })"
        class="w-[90vw] max-w-md"
      >
        <MatchOpponentEventNotice
          :team-name="opponentName ?? t('match.live.scoreboard.away')"
          @close="opponentNoticeVisible = false"
        />
      </Dialog>

      <!-- ボトムシート（記録 3 タップ）— 競技別シートを動的 import で出し分け。記録権限がある場合のみ。 -->
      <template v-if="canRecord">
        <MatchEventSheetBasketball
          v-if="isBasketball"
          v-model:visible="sheetVisible"
          :players="grid.players.value"
          @score="session.recordBasketScore"
          @stat="session.recordBasketStat"
          @foul="session.recordBasketFoul"
          @substitution="session.recordSub"
          @other="session.recordOther"
        />
        <MatchEventSheet
          v-else
          v-model:visible="sheetVisible"
          :players="grid.players.value"
          @goal="session.recordGoal"
          @assist="session.recordAssist"
          @card="session.recordCard"
          @substitution="session.recordSub"
          @other="session.recordOther"
        />
      </template>

      <!-- スタメン設定シート（記録権限がある場合のみマウント） -->
      <MatchStarterSetup
        v-if="canRecord"
        v-model:visible="starterSetupVisible"
        :players="grid.players.value"
        @toggle-starter="grid.toggleStarter($event)"
        @all-starters="grid.setAllStarters()"
        @clear-starters="grid.clearStarters()"
        @copy-previous="copyPreviousStarters()"
        @add-manual="addManualPlayer"
      />
    </template>
  </div>
</template>
