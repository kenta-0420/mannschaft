<script setup lang="ts">
// F08.10 ライブ記録（本機能の肝・04_frontend_and_ux.md §G.2）。
// 薄いオーケストレータ: 状態・ロジックは composable へ委譲（useMatchLiveSession が sender/recorder/
// timer/offline/score を結線）。ここではヘッダ(スコアボード)・タイマー操作・大ボタン行・
// ボトムシート・タイムライン・undo/offline/相手記録案内 を束ねるだけ。
import type { MatchEventResponse } from '~/types/match'
import { isNextCompleted } from '~/composables/match/useMatchTimer'

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

const session = useMatchLiveSession({ orgId, teamId, matchId, ownTeamSide })
const { timer, recorder } = session

const isCompleted = computed(
  () => matchStatus.value === 'COMPLETED' || timer.state.value === 'COMPLETED',
)

// === UI 状態 ===
const sheetVisible = ref(false)
const starterSetupVisible = ref(false)
const opponentNoticeVisible = ref(false)
const loading = ref(true)

// === undo / 削除 / 編集試行 ===
async function undo(): Promise<void> {
  try {
    await recorder.undoLast()
    notification.success(t('match.live.undo.done'))
    await session.refreshScore()
  } catch {
    notification.error(t('match.live.error.undo_failed'))
  }
}
async function onTimelineDelete(ev: MatchEventResponse): Promise<void> {
  if (!ev.id) return
  if (!window.confirm(t('match.live.timeline.delete_confirm'))) return
  try {
    await recorder.deleteEvent(ev.id)
    await session.refreshScore()
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
  try {
    const match = await matchApi.getMatch(orgId.value, teamId.value, matchId)
    matchStatus.value = match.status ?? null
    session.setMatchStatus(match.status ?? null)
    canRecord.value = match.canRecordTimeline ?? false
    ownTeamSide.value = match.homeAway === 'AWAY' ? 'AWAY' : 'HOME'
    opponentName.value = match.opponentName ?? null
    homePenaltyScore.value = match.homePenaltyScore ?? 0
    awayPenaltyScore.value = match.awayPenaltyScore ?? 0
  } catch {
    notification.error(t('match.live.error.load_match_failed'))
  }
  try {
    const res = await eventApi.listEvents(orgId.value, matchId)
    recorder.setEvents(res.events ?? [])
    session.applyDerivedScore(res)
  } catch {
    // 通知済み
  }
  await grid.loadPlayers(teamIdStr).catch(() => undefined)
  await wakeLock.acquireWakeLock().catch(() => undefined)
  window.addEventListener('online', session.flushOffline)
  loading.value = false
})

onBeforeUnmount(() => {
  wakeLock.releaseWakeLock()
  window.removeEventListener('online', session.flushOffline)
})

/**
 * @advance の集約ハンドラ。
 * 次状態が COMPLETED になる遷移（EXTRA_SECOND / PENALTY_SHOOTOUT からの advance）は
 * 必ず session.completeMatch() を経由させて BE の status を永続化する。
 * それ以外の通常 advance（WAITING→FIRST_HALF 等）は timer.advance() に委譲。
 * これにより「COMPLETED への到達は completeMatch() 経路のみ」を保証する（二重発火しない）。
 */
async function handleAdvance(): Promise<void> {
  if (isNextCompleted(timer.state.value)) {
    await session.completeMatch()
  } else {
    await timer.advance()
  }
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

    <PageLoading v-if="loading" size="40px" />

    <template v-else>
      <MatchScoreboard
        :home-score="session.homeScore.value"
        :away-score="session.awayScore.value"
        :home-penalty-score="homePenaltyScore"
        :away-penalty-score="awayPenaltyScore"
        :opponent-name="opponentName"
        :state="timer.state.value"
        :clock="timer.displayClock.value"
        :running="timer.isRunning.value"
        class="mb-3"
      />

      <!-- 記録権限がある場合のみタイマー操作を出す（閲覧専用は非表示）。 -->
      <MatchTimerControls
        v-if="canRecord"
        class="mb-3"
        :state="timer.state.value"
        :current-minute="timer.currentMinute.value"
        :running="timer.isRunning.value"
        @advance="handleAdvance()"
        @complete="session.completeMatch()"
        @extra="timer.goExtra()"
        @penalty="timer.goPenaltyShootout()"
      />

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
      <div v-if="canRecord && recorder.canUndo.value" class="mb-3 flex justify-end">
        <Button
          text
          severity="secondary"
          icon="pi pi-undo"
          :label="t('match.live.undo.button')"
          @click="undo"
        />
      </div>

      <MatchTimeline
        :events="recorder.events.value"
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

      <!-- ボトムシート（記録 3 タップ）— 記録権限がある場合のみマウント。 -->
      <MatchEventSheet
        v-if="canRecord"
        v-model:visible="sheetVisible"
        :players="grid.players.value"
        @goal="session.recordGoal"
        @assist="session.recordAssist"
        @card="session.recordCard"
        @substitution="session.recordSub"
        @other="session.recordOther"
      />

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
