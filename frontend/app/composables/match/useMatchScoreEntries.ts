/**
 * F08.10 採点競技（SCORED・フィギュア/体操）の<b>多人数順位制の出場者エントリ</b>入力 composable
 * （sports/07_scored.md §5/§5B/§8）。
 *
 * MVP の合計点直接入力（useMatchScoreEntry・2 者対戦）／審判別内訳（useMatchScoredComponents）に対し、
 * 本 composable は<b>多人数順位制（後段 Phase の本来形）</b>を担う。1 つの match を「種目（イベント）」とし、
 * N 人の出場者（team/player）が各自の合計点を持ち、サーバーが合計点降順で順位を導出する（§5B.1）。
 *
 * 【順位はサーバー算出】FE は順位（rankPosition）を送らない。N 行の合計点を全置換 PUT で送ると、
 * サーバーが標準順位法（同点同順位 1,2,2,4・§5B.2 / §6）で rank_position を算出して返す。
 * FE は受信した順位を表示するだけ（クライアントの順位主張を信頼しない・マスアサインメント防止）。
 *
 * BE 契約（#1570・MatchRecordScoreEntryController）:
 *   PUT  /api/v1/organizations/{orgId}/matches/{matchId}/score-entries   全置換（合計点降順で順位算出・冪等）
 *     body: MatchRecordScoreEntryRequest { entries: MatchRecordScoreEntryLine[] }
 *     200:  ApiResponseListMatchRecordScoreEntryResponse（順位算出済みのエントリ一覧・順位昇順）
 *   GET  .../score-entries                                              エントリ一覧（順位昇順・同順位は合計点降順）
 *     200: ApiResponseListMatchRecordScoreEntryResponse
 *
 * 【型規律（生成型を消費・手書き契約型ゼロ）】
 * BE DTO（MatchRecordScoreEntryRequest / MatchRecordScoreEntryLine / MatchRecordScoreEntryResponse）は
 * OpenAPI 再生成で生成型 `Schemas` に反映済みのため、再エクスポートのエイリアスのみで手書きの契約型は
 * 作らない（生成型が正本・any なし）。
 */
import { computed, ref } from 'vue'
import type { components } from '~/types/generated'

type Schemas = components['schemas']

// ===== DTO 型（生成型の再エクスポート・手書き契約型ゼロ） =====

/** 出場者エントリ記録リクエスト（生成型 MatchRecordScoreEntryRequest・全置換）。 */
export type MatchScoreEntriesRequestPayload = Schemas['MatchRecordScoreEntryRequest']
/** 出場者エントリ 1 明細リクエスト（生成型 MatchRecordScoreEntryLine）。 */
export type MatchScoreEntryLinePayload = Schemas['MatchRecordScoreEntryLine']
/** 出場者エントリ 1 明細レスポンス（生成型 MatchRecordScoreEntryResponse・順位算出済み）。 */
export type MatchScoreEntryResponseLine = Schemas['MatchRecordScoreEntryResponse']

/** 整数スケール係数（×1000・§4.1）。小数 198.45 → 整数 198450。 */
export const SCORE_ENTRY_SCALE_FACTOR = 1000

// ===== ピュア関数（テスト可能・副作用なし） =====

/**
 * 小数の合計点を整数スケール（×1000）へ変換する（§4.1）。
 * 浮動小数の誤差を避けるため四捨五入する（198.45 → 198450・85.332 → 85332）。
 * null/NaN/負値は null（未入力扱い・送信時にガード）。
 */
export function toScaledTotal(value: number | null | undefined): number | null {
  if (value === null || value === undefined || Number.isNaN(value)) return null
  if (value < 0) return null
  return Math.round(value * SCORE_ENTRY_SCALE_FACTOR)
}

/** 整数スケール（×1000）を小数の合計点へ復元する（表示用・§4.1）。null は null。 */
export function fromScaledTotal(scaled: number | null | undefined): number | null {
  if (scaled === null || scaled === undefined || Number.isNaN(scaled)) return null
  return scaled / SCORE_ENTRY_SCALE_FACTOR
}

/**
 * 編集中の出場者エントリ 1 行（FE ローカル状態）。
 * total は小数（UI 入力）で保持し、送信時に整数スケール×1000 へ変換する。
 */
export interface ScoreEntryDraft {
  /** 一意キー（v-for/編集識別用・送信には含めない）。 */
  readonly key: string
  /** 出場選手ユーザー ID（登録選手・任意・null=未登録）。 */
  competitorUserId: number | null
  /** 未登録選手名（competitorUserId 未指定時の表示名・任意）。 */
  competitorName: string | null
  /** 所属チーム ID（団体採点時・任意）。 */
  competitorTeamId: number | null
  /** 合計点（小数・UI 入力・送信時に×1000）。 */
  total: number | null
}

/**
 * 編集中の出場者行群から全置換ペイロードを構築する（送出 JSON 形・§5B.1）。
 * total は×1000 整数へ変換し、未入力（null）は 0 として送る（明示 0 点）。
 * 順位（rankPosition）はサーバーが導出するため含めない（クライアントの順位主張を信頼しない）。
 */
export function buildScoreEntriesPayload(
  drafts: readonly ScoreEntryDraft[],
): MatchScoreEntriesRequestPayload {
  const entries: MatchScoreEntryLinePayload[] = drafts.map((d) => {
    const line: MatchScoreEntryLinePayload = {
      totalScaled: toScaledTotal(d.total) ?? 0,
    }
    if (d.competitorUserId !== null) line.competitorUserId = d.competitorUserId
    if (d.competitorTeamId !== null) line.competitorTeamId = d.competitorTeamId
    const name = d.competitorName?.trim()
    if (name) line.competitorName = name
    return line
  })
  return { entries }
}

/**
 * 出場者の表示名を解決する（competitorName 優先・無ければユーザー/チーム ID・最終フォールバック空文字）。
 * 表示専用（送信には使わない）。
 */
export function resolveCompetitorDisplayName(
  entry: {
    competitorName?: string | null
    competitorUserId?: number | null
    competitorTeamId?: number | null
  },
  fallbackUser: (id: number) => string,
  fallbackTeam: (id: number) => string,
): string {
  const name = entry.competitorName?.trim()
  if (name) return name
  if (entry.competitorUserId !== null && entry.competitorUserId !== undefined) {
    return fallbackUser(entry.competitorUserId)
  }
  if (entry.competitorTeamId !== null && entry.competitorTeamId !== undefined) {
    return fallbackTeam(entry.competitorTeamId)
  }
  return ''
}

// ===== composable =====

/** useMatchScoreEntries の初期化オプション。 */
export interface UseMatchScoreEntriesOptions {
  /** 競技識別（FIGURE_SKATING / GYMNASTICS）。表示ラベル出し分けに使う。 */
  sport?: string | null
}

let entryDraftKeySeq = 0
/** 出場者行の一意キーを採番する（v-for/編集識別用・送信には含めない）。 */
function nextEntryDraftKey(): string {
  entryDraftKeySeq += 1
  return `score-entry-${entryDraftKeySeq}`
}

/**
 * 採点競技の多人数順位制（出場者エントリ）の入力・API 呼び出しを管理する composable
 * （後段 Phase の本来形・§5/§5B/§8）。
 *
 * - 出場者行（drafts）を追加/更新/削除する（N 人）。
 * - 全置換 PUT /score-entries で送信し、サーバーが合計点降順で順位を導出する。
 * - 受信した順位算出済みエントリ（ranked）を保持し、順位表として表示する。
 * - GET で既存エントリを読み込み、ローカル draft へ復元する（再開時）。
 */
export function useMatchScoreEntries(options: UseMatchScoreEntriesOptions = {}) {
  const sport = options.sport === 'GYMNASTICS' ? 'GYMNASTICS' : 'FIGURE_SKATING'

  const base = (orgId: number, matchId: string) =>
    `/api/v1/organizations/${orgId}/matches/${matchId}/score-entries`

  /** 編集中の出場者行（N 人）。 */
  const drafts = ref<ScoreEntryDraft[]>([])
  /** 順位算出済みのエントリ一覧（サーバー算出・順位昇順・直近の PUT/GET の結果）。 */
  const ranked = ref<MatchScoreEntryResponseLine[]>([])
  /** 送信中フラグ（二重送信防止）。 */
  const saving = ref(false)

  /** フィギュアか（表示ラベル出し分け）。 */
  const isFigureSkating = computed<boolean>(() => sport === 'FIGURE_SKATING')
  /** 体操か。 */
  const isGymnastics = computed<boolean>(() => sport === 'GYMNASTICS')

  /** 出場者エントリが 1 件以上あるか（多人数順位制が「正本」になっている目印・stale 整合判定）。 */
  const hasEntries = computed<boolean>(() => drafts.value.length > 0 || ranked.value.length > 0)

  /**
   * 送信可能か。
   * - 2 件以上の出場者行（多人数順位制＝最低 2 人で順位が成立する）
   * - 全行に合計点が入力済み（0 以上・null 不可）
   * - 各行に競技者の識別（user/team/name のいずれか）が入力済み
   * - 送信中でない
   */
  const canSubmit = computed<boolean>(() => {
    if (saving.value) return false
    if (drafts.value.length < 2) return false
    return drafts.value.every(
      (d) =>
        d.total !== null &&
        d.total >= 0 &&
        (d.competitorUserId !== null ||
          d.competitorTeamId !== null ||
          (d.competitorName !== null && d.competitorName.trim() !== '')),
    )
  })

  // ===== 操作（ローカル draft・不変更新） =====

  /** 出場者行を 1 件追加する。 */
  function addEntry(): ScoreEntryDraft {
    const draft: ScoreEntryDraft = {
      key: nextEntryDraftKey(),
      competitorUserId: null,
      competitorName: null,
      competitorTeamId: null,
      total: null,
    }
    drafts.value = [...drafts.value, draft]
    return draft
  }

  /** 指定キーの出場者行を部分更新する（不変更新・型安全）。 */
  function updateEntry(key: string, patch: Partial<Omit<ScoreEntryDraft, 'key'>>): void {
    drafts.value = drafts.value.map((d) => (d.key === key ? { ...d, ...patch } : d))
  }

  /** 指定キーの出場者行を削除する。 */
  function removeEntry(key: string): void {
    drafts.value = drafts.value.filter((d) => d.key !== key)
  }

  /** すべての出場者行・順位表をクリアする（多人数入力を破棄する導線・stale 整合）。 */
  function clearEntries(): void {
    drafts.value = []
    ranked.value = []
  }

  /** レスポンスのエントリ一覧をローカル draft／順位表へ復元する（再開時・GET/PUT 由来）。 */
  function restore(entries: readonly MatchScoreEntryResponseLine[]): void {
    ranked.value = [...entries]
    drafts.value = entries.map((e) => ({
      key: nextEntryDraftKey(),
      competitorUserId: e.competitorUserId ?? null,
      competitorName: e.competitorName ?? null,
      competitorTeamId: e.competitorTeamId ?? null,
      total: fromScaledTotal(e.totalScaled),
    }))
  }

  // ===== API =====

  /** 既存の出場者エントリを取得し、ローカル draft／順位表へ復元する（再開時）。 */
  async function load(orgId: number, matchId: string): Promise<MatchScoreEntryResponseLine[]> {
    const api = useApi()
    try {
      const res = await api<{ data: MatchScoreEntryResponseLine[] }>(base(orgId, matchId))
      const list = res.data ?? []
      restore(list)
      return list
    } catch (err) {
      const notification = useNotification()
      const { t } = useI18n()
      notification.error(t('match.scored.ranking.error.load_failed'))
      throw err
    }
  }

  /**
   * 出場者エントリを全置換で記録/更新する（PUT /score-entries・冪等）。
   * サーバーが合計点降順で順位を算出して返す（標準順位法・§5B.2）。
   * 返ってきた順位算出済みエントリで ranked を更新する。FE は順位を送らない。
   * status の COMPLETED 遷移は呼び出し側（live.vue）が別途 changeStatus で行う。
   */
  async function save(orgId: number, matchId: string): Promise<MatchScoreEntryResponseLine[]> {
    const api = useApi()
    const notification = useNotification()
    const { t } = useI18n()
    saving.value = true
    try {
      const payload = buildScoreEntriesPayload(drafts.value)
      const res = await api<{ data: MatchScoreEntryResponseLine[] }>(base(orgId, matchId), {
        method: 'PUT',
        body: payload,
      })
      const list = res.data ?? []
      ranked.value = [...list]
      return list
    } catch (err) {
      notification.error(t('match.scored.ranking.error.save_failed'))
      throw err
    } finally {
      saving.value = false
    }
  }

  return {
    // 設定
    sport,
    isFigureSkating,
    isGymnastics,
    // 状態
    drafts,
    ranked,
    saving,
    // 算出
    hasEntries,
    canSubmit,
    // 操作
    addEntry,
    updateEntry,
    removeEntry,
    clearEntries,
    restore,
    // API
    load,
    save,
  }
}

export type MatchScoreEntriesReturn = ReturnType<typeof useMatchScoreEntries>
