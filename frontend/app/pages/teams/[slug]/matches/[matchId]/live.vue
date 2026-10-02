<script setup lang="ts">
// F08.10 ライブ記録 共通シェル（多競技対応・04_frontend_and_ux.md §G.2 / §G.16）。
//
// 【6-②b 共通シェル化】薄いオーケストレータ。競技非依存の骨格（スコアボード・タイマー操作の枠・
// 大ボタン・undo・タイムライン・相手記録案内・PK スコア入力）を持ち、競技固有の差分
// （タイマー状態機械・イベント入力シート）は `matches.sport` に応じて**競技モジュールを
// 動的 import（lazy-load）**して差し替える。サッカーしか開かないユーザーはバスケのロジック・
// 入力シートを初期バンドルに同梱しない（Vite コード分割・マスター懸念「重くなる」の解消）。
//
// 【6-④b 拡張】ターン制（SHOGI/GO）モジュールを追加。
// isTurnBasedModule で判定し、ターン制専用の入力シート（MatchEventSheetTurnBased）を
// component :is でマウントする（タイマー・タイムライン不要・最小結果入力 UI）。
import { effectScope } from 'vue'
import type { MatchEventResponse } from '~/types/match'
import {
  resolveSportModule,
  isContinuousModule,
  isSetBasedModule,
  isTurnBasedModule,
  isScoredModule,
  type SportLiveModule,
} from '~/composables/match/sport/sportModuleRegistry'
import type { MatchSetTrackerReturn } from '~/composables/match/sport/useMatchSetTracker'
import type { MatchTurnTrackerReturn } from '~/composables/match/sport/useMatchTurnTracker'
import type { MatchScoreEntryReturn } from '~/composables/match/useMatchScoreEntry'
import type { MatchScoredComponentsReturn } from '~/composables/match/useMatchScoredComponents'
import type { MatchScoreEntriesReturn } from '~/composables/match/useMatchScoreEntries'
import { buildTurnResultPayload } from '~/composables/match/useMatchTurnApi'
import type { BoardProgressItem } from '~/components/match/MatchBoardProgress.vue'

definePageMeta({ layout: 'team', middleware: 'auth' })

const route = useRoute()
const router = useRouter()
const teamSlug = String(route.params.slug)
const matchId = String(route.params.matchId)
const { t } = useI18n()
const notification = useNotification()

const { resolveContext } = useMatchOrgContext()
const matchApi = useMatchApi()
const eventApi = useMatchEventApi()
const turnApi = useMatchTurnApi()
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
/**
 * セット制（VOLLEYBALL 等）かどうか（§G.16 stateModel 分岐）。
 * セット制はタイマーを持たず、セットトラッカーをセットシートへ渡す。
 */
const isSetBased = computed(() => sportModule.value !== null && isSetBasedModule(sportModule.value!))
/** セットトラッカー（SET_BASED 競技時のみ非 null）。 */
const setTracker = shallowRef<MatchSetTrackerReturn | null>(null)

/**
 * ターン制（SHOGI/GO 等）かどうか（§G.16 stateModel 分岐・6-④b）。
 * ターン制はタイマー・タイムラインを持たず、最小結果入力シートを使う。
 */
const isTurnBased = computed(() => sportModule.value !== null && isTurnBasedModule(sportModule.value!))
/** ターントラッカー（TURN_BASED 競技時のみ非 null）。 */
const turnTracker = shallowRef<MatchTurnTrackerReturn | null>(null)

/**
 * 採点制（FIGURE_SKATING/GYMNASTICS）かどうか（§G.16 stateModel 分岐・SCORED）。
 * 採点制はタイマー・セット・ターントラッカーを持たず、合計点入力シートを使う（07_scored.md §3）。
 */
const isScored = computed(() => sportModule.value !== null && isScoredModule(sportModule.value!))
/** 採点入力トラッカー（合計直接入力・SCORED 競技時のみ非 null）。 */
const scoreEntry = shallowRef<MatchScoreEntryReturn | null>(null)
/** 採点内訳トラッカー（審判別/種目別・§4B・SCORED 競技時のみ非 null）。 */
const scoredComponents = shallowRef<MatchScoredComponentsReturn | null>(null)
/** 多人数順位制トラッカー（出場者 N 人→順位・§5B・SCORED 競技時のみ非 null）。 */
const scoredRanking = shallowRef<MatchScoreEntriesReturn | null>(null)
/**
 * 既存採点（内訳/順位）の読み込みに失敗したか（データ保護フラグ）。
 * true の間は内訳・順位の確定（全置換 PUT）を禁止する。ロード失敗を「内訳なし」とみなして
 * 空の全置換 PUT を送るとサーバ既存採点を上書き（破壊）する恐れがあるため、確定操作を止める。
 */
const scoreLoadFailed = ref(false)

/**
 * 団体戦の子ボード進捗（6-④c・GET /boards 由来）。
 * 空配列＝個人戦 or 親未確認。ボードがある場合は MatchBoardProgress を描画する。
 */
const boards = ref<BoardProgressItem[]>([])
/** 団体戦かどうか（子ボードが 1 件以上ある親 match）。 */
const isTeamMatch = computed(() => boards.value.length > 0)

/** ターン制競技の identifier（'SHOGI' | 'GO' | ...・MatchBoardProgress のラベル分岐用）。 */
const turnSport = computed<string>(() => sportModule.value?.sport ?? 'SHOGI')

/** 団体戦の子ボード一覧を読み込む（記録/閲覧いずれも・GET /boards）。 */
async function loadBoards(): Promise<void> {
  if (orgId.value === null) return
  try {
    const list = await turnApi.listBoards(orgId.value, matchId)
    boards.value = list.map((b) => ({
      boardNumber: b.boardNumber ?? 0,
      matchId: b.id ?? null,
      // 確定＝COMPLETED かつスコアが入っている（home/away いずれか 1 or 引分 0-0 でも COMPLETED）。
      confirmed: b.status === 'COMPLETED',
      winnerSide:
        (b.homeScore ?? 0) > (b.awayScore ?? 0)
          ? 'HOME'
          : (b.awayScore ?? 0) > (b.homeScore ?? 0)
            ? 'AWAY'
            : null,
      winMethod: b.winMethod ?? null,
      // 選手名・記録者名の本格表示は後続フェーズ（BE 拡張待ち）。現状は相手名のみ。
      homePlayerName: null,
      awayPlayerName: b.opponentName ?? null,
      recorderName: null,
      canRecordThisBoard: b.canRecordTimeline ?? false,
    }))
  } catch {
    // 通知済み（listBoards 内）。個人戦（404/空）でも空配列のまま続行する。
  }
}

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
  try {
    if (teamId.value === null) return
    grid.copyPreviousStarters(await eventApi.listAppearances(orgId.value, teamId.value, matchId))
  } catch {
    // 通知済み
  }
}
function addManualPlayer(p: { name: string; jerseyNumber: number | null }): void {
  grid.addManualPlayer(p.name, p.jerseyNumber)
}

// === 初期ロード ===
onMounted(async () => {
  const ctx = await resolveContext(teamSlug)
  orgId.value = ctx?.orgId ?? null
  teamId.value = ctx?.teamId ?? null
  if (ctx === null || teamId.value === null) {
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

  // 記録権限がなければ観戦ビュー（read-only）へ。記録セッション（タイマー/グリッド/wakeLock）は
  // 構築しない（観戦は MatchSpectatorView が STOMP 購読＋初期スナップショットを自前で行う・§G.17 / 07 §J）。
  if (!canRecord.value) {
    loading.value = false
    return
  }

  // 競技モジュールを動的 import（lazy-load）で解決し、その createTimer をセッションへ注入。
  const mod = await resolveSportModule(sport)
  sportModule.value = mod

  // セット制: セットトラッカーを初期化（SET_BASED 競技時のみ）。
  if (mod !== null && isSetBasedModule(mod)) {
    setTracker.value = mod.createSetTracker()
  }

  // ターン制: ターントラッカーを初期化（TURN_BASED 競技時のみ・6-④b）。
  if (mod !== null && isTurnBasedModule(mod)) {
    turnTracker.value = mod.createTurnTracker()
    // 団体戦の子ボード進捗を読み込む（個人戦なら空配列・6-④c）。
    await loadBoards()
  }

  // 採点制: 採点入力トラッカーを初期化（SCORED 競技時のみ・07_scored.md §9）。
  // 実際の競技（フィギュア/体操）を渡して表示ラベルを出し分ける（モジュールは共有のため）。
  if (mod !== null && isScoredModule(mod)) {
    const scoredSport = (sport ?? 'FIGURE_SKATING') as typeof mod.sport
    scoreEntry.value = mod.createScoreEntry(scoredSport)
    // 審判別/種目別採点内訳トラッカー（§4B）。既存内訳を読み込み、あれば内訳が正本になる（stale 整合）。
    const componentsTracker = mod.createComponentEntry(scoredSport)
    scoredComponents.value = componentsTracker
    // 多人数順位制トラッカー（§5B）。既存エントリを読み込み、あれば多人数が正本になる（stale 整合）。
    const rankingTracker = mod.createRankingEntry(scoredSport)
    scoredRanking.value = rankingTracker
    if (orgId.value !== null) {
      // 既存採点（内訳/順位）の読み込み。失敗を握りつぶすと「内訳なし」の空トラッカーのまま
      // 確定（全置換 PUT）へ進み、サーバ既存採点を上書き（破壊）する恐れがある。
      // よって失敗時はフラグを立て、確定操作を後段でブロックする（根治原則）。
      try {
        await componentsTracker.load(orgId.value, matchId)
      } catch {
        scoreLoadFailed.value = true
        console.error('[live] 採点内訳（scored-components）の読み込みに失敗しました。確定を禁止します。')
      }
      try {
        await rankingTracker.load(orgId.value, matchId)
      } catch {
        scoreLoadFailed.value = true
        console.error('[live] 多人数順位エントリ（score-entries）の読み込みに失敗しました。確定を禁止します。')
      }
    }
  }

  sessionScope = effectScope()
  const s = sessionScope.run(() =>
    useMatchLiveSession({
      orgId,
      teamId,
      matchId,
      ownTeamSide,
      createTimer: mod !== null && isContinuousModule(mod) ? mod.createTimer.bind(mod) : undefined,
    }),
  )
  if (!s) {
    loading.value = false
    return
  }
  s.setMatchStatus(matchStatus.value)
  session.value = s

  try {
    const res = await eventApi.listEvents(orgId.value, teamId.value, matchId)
    s.recorder.setEvents(res.events ?? [])
    s.applyDerivedScore(res)
  } catch {
    // 通知済み
  }
  // 選手名簿の読み込み。失敗を沈黙させると空名簿になり記録できないため通知する
  // （データ破壊はないので確定ブロックまでは不要・通知＋ログで表面化させる）。
  try {
    await grid.loadPlayers(teamSlug)
  } catch {
    notification.error(t('match.live.error.load_players_failed'))
  }
  // wakeLock 取得はブラウザに拒否されうる（想定内・データ影響なし）。取得失敗は無視してよい。
  // eslint-disable-next-line no-restricted-syntax -- wakeLock はブラウザ拒否が想定内（データ影響なし）。取得失敗を無視するのが正しい
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
 * ターン制（将棋/囲碁）の対局結果を確定する（6-④c 配線）。
 *
 * 球技用 completeMatch（finalizeScore=ゴールイベント由来＝将棋では常に 0-0）ではなく、
 * BE のターン制結果エンドポイント PUT /result を呼んで勝者・勝ち方・総手数を送信する。
 * BE が home/away_score（1-0/0-1/0-0）を確定し、子ボードなら親の勝ち星を再集計する。
 * その後 changeStatus(COMPLETED) で順位連携（MatchCompletedEvent）を発火させる。
 * 引分時は win_method を送らない（buildTurnResultPayload が落とす・🟡 BE MATCH_028 整合）。
 */
async function completeTurnResult(): Promise<void> {
  const tracker = turnTracker.value
  if (!tracker || orgId.value === null || teamId.value === null) return
  if (matchStatus.value === 'COMPLETED') return
  const payload = buildTurnResultPayload(
    tracker.winnerSide.value,
    tracker.winMethod.value,
    tracker.totalMoves.value,
  )
  try {
    await turnApi.recordResult(orgId.value, matchId, payload)
  } catch {
    // recordResult 内でトースト済み。結果保存に失敗したら status 遷移はしない（根治原則）。
    // eslint-disable-next-line no-restricted-syntax -- 保存関数内で通知済み・失敗時は確定へ進めない意図的な早期return
    return
  }
  try {
    await matchApi.changeStatus(orgId.value, teamId.value, matchId, { status: 'COMPLETED' })
    matchStatus.value = 'COMPLETED'
    session.value?.setMatchStatus('COMPLETED')
    // 団体戦なら親の勝ち星再集計が走るためボード進捗を再取得する。
    if (isTeamMatch.value) await loadBoards()
  } catch {
    notification.warn(t('match.live.error.complete_failed'))
  }
}

/**
 * 採点競技（フィギュア/体操）の採点結果を確定する（07_scored.md §9 配線）。
 *
 * 球技用 completeMatch（ゴールイベント由来＝採点競技では常に 0-0）ではなく、
 * BE の採点結果エンドポイント PUT /scored-result を呼んで home/away の合計点（整数スケール×1000）を
 * 送信する。BE が home/away_score を確定し、大小から勝敗（W/D/L）を導出する（§4.2）。
 * その後 changeStatus(COMPLETED) で順位連携（MatchCompletedEvent）を発火させる。
 */
async function completeScoredResult(): Promise<void> {
  const entry = scoreEntry.value
  if (!entry || orgId.value === null || teamId.value === null) return
  if (matchStatus.value === 'COMPLETED') return
  let res: Awaited<ReturnType<typeof entry.submit>>
  try {
    res = await entry.submit(orgId.value, matchId)
  } catch {
    // submit（recordScore）内でトースト済み。スコア保存に失敗したら status 遷移はしない（根治原則）。
    // eslint-disable-next-line no-restricted-syntax -- 保存関数内で通知済み・失敗時は確定へ進めない意図的な早期return
    return
  }
  if (res === null) {
    // canSubmit を満たさない（合計点未入力）。status 遷移はしない。
    return
  }
  try {
    await matchApi.changeStatus(orgId.value, teamId.value, matchId, { status: 'COMPLETED' })
    matchStatus.value = 'COMPLETED'
    session.value?.setMatchStatus('COMPLETED')
  } catch {
    notification.warn(t('match.live.error.complete_failed'))
  }
}

/**
 * 採点競技（フィギュア/体操）の審判別/種目別採点内訳を確定する（07_scored.md §4B 配線）。
 *
 * 直接入力（completeScoredResult）ではなく、内訳の全置換 PUT /scored-components を呼ぶ。
 * BE が internal の内訳を HOME/AWAY ごとに符号付き集計して home/away_score を再導出し
 * （二層正本・§4B.2）、大小から勝敗（W/D/L）を導出する。その後 changeStatus(COMPLETED) で
 * 順位連携（MatchCompletedEvent）を発火させる。
 */
async function completeScoredComponents(): Promise<void> {
  const tracker = scoredComponents.value
  if (!tracker || orgId.value === null || teamId.value === null) return
  if (matchStatus.value === 'COMPLETED') return
  if (!tracker.canSubmit.value) return
  if (scoreLoadFailed.value) {
    // 既存採点の読み込みに失敗している。全置換 PUT でサーバ既存採点を上書き（破壊）しないよう確定を止める。
    notification.error(t('match.live.error.score_load_failed'))
    return
  }
  try {
    await tracker.save(orgId.value, matchId)
  } catch {
    // save 内でトースト済み。内訳保存に失敗したら status 遷移はしない（根治原則）。
    // eslint-disable-next-line no-restricted-syntax -- 保存関数内で通知済み・失敗時は確定へ進めない意図的な早期return
    return
  }
  try {
    await matchApi.changeStatus(orgId.value, teamId.value, matchId, { status: 'COMPLETED' })
    matchStatus.value = 'COMPLETED'
    session.value?.setMatchStatus('COMPLETED')
  } catch {
    notification.warn(t('match.live.error.complete_failed'))
  }
}

/**
 * 採点競技（フィギュア/体操）の多人数順位制エントリを確定する（07_scored.md §5B 配線）。
 *
 * 直接入力/内訳ではなく、出場者 N 人の全置換 PUT /score-entries を呼ぶ。BE が合計点降順で
 * 順位（rank_position）を算出し、最上位の合計点を matches.home_score へ補助的に再導出する
 * （二層正本・§5B.2）。FE は順位を送らず、受信した順位算出済みエントリを順位表に反映する。
 * その後 changeStatus(COMPLETED) で順位連携（MatchCompletedEvent）を発火させる。
 */
async function completeScoredRanking(): Promise<void> {
  const tracker = scoredRanking.value
  if (!tracker || orgId.value === null || teamId.value === null) return
  if (matchStatus.value === 'COMPLETED') return
  if (!tracker.canSubmit.value) return
  if (scoreLoadFailed.value) {
    // 既存採点の読み込みに失敗している。全置換 PUT でサーバ既存採点を上書き（破壊）しないよう確定を止める。
    notification.error(t('match.live.error.score_load_failed'))
    return
  }
  try {
    await tracker.save(orgId.value, matchId)
  } catch {
    // save 内でトースト済み。エントリ保存に失敗したら status 遷移はしない（根治原則）。
    // eslint-disable-next-line no-restricted-syntax -- 保存関数内で通知済み・失敗時は確定へ進めない意図的な早期return
    return
  }
  try {
    await matchApi.changeStatus(orgId.value, teamId.value, matchId, { status: 'COMPLETED' })
    matchStatus.value = 'COMPLETED'
    session.value?.setMatchStatus('COMPLETED')
  } catch {
    notification.warn(t('match.live.error.complete_failed'))
  }
}

/**
 * 局面写真をアップロードする（presign → ストレージ PUT → confirm → 表示 URL 取得）。
 * 成功したらトラッカーへ追加し、ターン制シートのプレビューに反映する。
 */
async function onUploadPositionPhoto(file: File): Promise<void> {
  const tracker = turnTracker.value
  if (!tracker || orgId.value === null) return
  try {
    const { attachment, displayUrl } = await turnApi.uploadPositionPhoto(orgId.value, matchId, file)
    tracker.addPositionPhoto({
      key: attachment.id,
      url: displayUrl,
      filename: attachment.originalFilename ?? file.name,
    })
    notification.success(t('match.turn.photo.upload_success'))
  } catch {
    // uploadPositionPhoto 内でトースト済み。
  }
}

/**
 * 局面写真を削除する（DELETE /attachments/{id}）。
 * 成功したらトラッカーからも除去する。
 */
async function onRemovePositionPhoto(key: string): Promise<void> {
  const tracker = turnTracker.value
  if (!tracker || orgId.value === null) return
  try {
    await turnApi.deleteAttachment(orgId.value, matchId, key)
    tracker.removePositionPhoto(key)
  } catch {
    // deleteAttachment 内でトースト済み。
  }
}

/**
 * 団体戦の子ボードを記録するため対象ボードの live へ遷移する（6-④c）。
 * 子 match が未作成（matchId=null）の場合はここでは作成導線のみ（本格作成 UI は後続）。
 */
function onRecordBoard(_boardNumber: number, boardMatchId: string | null): void {
  if (boardMatchId === null) {
    notification.info(t('match.board.create_pending_notice'))
    return
  }
  void router.push(`/teams/${teamSlug}/matches/${boardMatchId}/live`)
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
  // SET_BASED競技（バレーボール等）はタイマーを持たないため handleAdvance は早期リターン
  if (!isContinuousModule(mod)) return
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
</script>

<template>
  <div class="mx-auto max-w-2xl pb-28">
    <div class="mb-2 flex items-center gap-2">
      <PageHeader
        :title="canRecord ? t('match.live.title') : t('match.live.spectator.title')"
        size="sm"
        :back-to="`/teams/${teamSlug}/matches`"
      />
    </div>

    <PageLoading v-if="loading" size="40px" />

    <!-- 観戦ビュー（記録権限なし＝read-only・STOMP 購読＋初期スナップショット差分追従・§G.17 / 07 §J） -->
    <MatchSpectatorView
      v-else-if="!canRecord"
      :org-id="orgId"
      :team-id="teamId"
      :match-id="matchId"
      :own-team-side="ownTeamSide"
      :opponent-name="opponentName"
    />

    <template v-else-if="session">
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
      <!-- 採点制はタイマーを持たないため除外（07_scored.md §3）。 -->
      <template v-if="canRecord && !isSetBased && !isScored">
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

      <p v-if="isCompleted" class="mb-3 rounded bg-surface-100 p-2 text-xs text-surface-500">
        {{ t('match.live.completed_notice') }}
      </p>

      <!--
        大ボタン行（記録開始・スタメン設定）— 球技（連続時間制・セット制）のみ。
        ターン制（SHOGI/GO）は結果入力シートが別途表示されるためタイムライン不要。
      -->
      <div v-if="canRecord && !isTurnBased && !isScored" class="mb-4 flex flex-col gap-2">
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

      <!-- undo 帯（記録権限がある場合のみ・ターン制は不要） -->
      <div v-if="canRecord && !isTurnBased && !isScored && session.recorder.canUndo.value" class="mb-3 flex justify-end">
        <Button
          text
          severity="secondary"
          icon="pi pi-undo"
          :label="t('match.live.undo.button')"
          @click="undo"
        />
      </div>

      <!-- タイムライン（球技のみ・ターン制/採点制は不要） -->
      <MatchTimeline
        v-if="!isTurnBased && !isScored"
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

      <!--
        ボトムシート（記録 3 タップ）— 競技別シートを動的 import で出し分け（§G.16 完全準拠）。
        静的 auto-import の直書きをやめ、解決済み競技モジュールの eventSheet
        （defineAsyncComponent）を <component :is> で描画する。これによりサッカーユーザーは
        バスケシート Vue を初期バンドルに同梱しない（Vite コード分割に乗る）。
        emit はサッカー（goal/assist/card）とバスケ（score/stat/foul）で異なるが、各シートは
        自分が emit するイベントだけを発火するため、両系統のハンドラを束ねて結線して問題ない。
        記録権限がある場合のみマウント。ターン制・セット制は別パス（下記）なのでここは除外。
      -->
      <component
        :is="sportModule.eventSheet"
        v-if="canRecord && sportModule && !isSetBased && !isTurnBased && !isScored"
        v-model:visible="sheetVisible"
        :players="grid.players.value"
        @goal="session.recordGoal"
        @assist="session.recordAssist"
        @card="session.recordCard"
        @score="session.recordBasketScore"
        @stat="session.recordBasketStat"
        @foul="session.recordBasketFoul"
        @substitution="session.recordSub"
        @other="session.recordOther"
      />

      <!--
        バレーボール セット入力シート（SET_BASED 競技のみ表示）（§G.16a）。
        タイマーコントロールの代わりに常時表示され、セット進行・デュース判定・試合終了を制御する。
        記録権限がある場合のみマウント。
      -->
      <MatchEventSheetVolleyball
        v-if="canRecord && isSetBased && setTracker"
        :tracker="setTracker!"
        :own-team-side="ownTeamSide"
        :opponent-name="opponentName"
        :can-record="canRecord"
        class="mb-4"
        @complete-match="complete()"
      />

      <!--
        団体戦ボード進捗（TURN_BASED かつ子ボードあり・6-④c）（§G.16a・§4.3）。
        親 match を開いたとき、各ボードの確定状況（n/N）と記録導線を表示する。
        個人戦（boards 空）では描画しない。
      -->
      <MatchBoardProgress
        v-if="isTurnBased && isTeamMatch"
        :boards="boards"
        :sport="turnSport"
        :can-record-all="canRecord"
        class="mb-4"
        @record-board="onRecordBoard"
      />

      <!--
        ターン制 結果入力シート（TURN_BASED 競技のみ表示・6-④b/6-④c）（§G.16a）。
        将棋/囲碁は「タイマー・タイムライン・選手グリッド」を使わず、
        最小結果入力（勝者選択・勝ち方任意・手数任意・局面写真任意）UI で完結する。
        記録権限の有無に関わらずマウント（canRecord prop で内部出し分け）。
        完了は球技用 complete() ではなく completeTurnResult()（PUT /result + COMPLETED・6-④c 配線）。
        局面写真は presign 方式で uploadPositionPhoto / deleteAttachment へ結線する。
        団体戦の親（isTeamMatch）では結果入力シートは出さない（各ボード live で記録する）。
      -->
      <component
        :is="sportModule.eventSheet"
        v-if="isTurnBased && turnTracker && sportModule && !isTeamMatch"
        :tracker="turnTracker!"
        :own-team-side="ownTeamSide"
        :opponent-name="opponentName"
        :can-record="canRecord"
        class="mb-4"
        @complete-match="completeTurnResult()"
        @upload-photo="onUploadPositionPhoto"
        @remove-photo="onRemovePositionPhoto"
      />

      <!-- 既存採点の読み込み失敗警告（確定ブロック中・データ保護）。 -->
      <p
        v-if="isScored && scoreLoadFailed"
        class="mb-3 rounded border border-red-300 bg-red-50 p-2 text-xs text-red-700"
        role="alert"
      >
        {{ t('match.live.error.score_load_failed') }}
      </p>

      <!--
        採点制 採点結果入力シート（SCORED 競技のみ表示・07_scored.md §9）。
        フィギュア/体操は「タイマー・タイムライン・選手グリッド・セット」を使わず、
        合計点入力（自/相手の Total Segment Score / 個人総合）UI で完結する（2 者対戦・MVP）。
        記録権限の有無に関わらずマウント（canRecord prop で内部出し分け）。
        完了は球技用 complete() ではなく completeScoredResult()（PUT /scored-result + COMPLETED）。
        勝敗は合計点の大小で BE が導出する（FE は両合計点を送るだけ・§4.2）。
      -->
      <component
        :is="sportModule.eventSheet"
        v-if="isScored && scoreEntry && scoredComponents && scoredRanking && sportModule"
        :tracker="scoreEntry!"
        :component-tracker="scoredComponents!"
        :ranking-tracker="scoredRanking!"
        :own-team-side="ownTeamSide"
        :opponent-name="opponentName"
        :can-record="canRecord"
        class="mb-4"
        @complete-match="completeScoredResult()"
        @complete-components="completeScoredComponents()"
        @complete-ranking="completeScoredRanking()"
      />

      <!-- スタメン設定シート（記録権限がある場合のみマウント・ターン制/採点制は不要） -->
      <MatchStarterSetup
        v-if="canRecord && !isTurnBased && !isScored"
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
