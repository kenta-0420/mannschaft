/**
 * F08.10 採点競技（SCORED・フィギュア/体操）の<b>審判別/種目別採点内訳</b>入力 composable
 * （sports/07_scored.md §4B / §8 / §9）。
 *
 * MVP の合計点直接入力（useMatchScoreEntry）に対し、本 composable は<b>内訳入力（後段 Phase）</b>を担う。
 * フィギュア（TES + PCS − DEDUCTION・SP/FS セグメント別）・体操（D_SCORE + E_SCORE・種目別）の
 * 項目別点数を home/away の side 別に入力し、<b>全置換 PUT</b> でサーバーへ送る。サーバーが内訳を
 * HOME/AWAY ごとに符号付き集計して matches.home_score/away_score（整数スケール×1000）へ再導出する
 * （二層正本・§4B.2）。FE は合計をプレビュー表示するが、勝敗/合計の正本はサーバー（§B.1.2）。
 *
 * BE 契約（#1566・MatchRecordScoredComponentController）:
 *   PUT  /api/v1/organizations/{orgId}/matches/{matchId}/scored-components   全置換（内訳→合計再導出・冪等）
 *     body: MatchRecordScoredComponentRequest { components: MatchRecordScoredComponentLine[] }
 *     200:  ApiResponseMatchDetailResponse（再導出後の合計点を含む）
 *   GET  .../scored-components                                               内訳一覧（作成時刻昇順）
 *     200: ApiResponseListMatchRecordScoredComponentResponse
 *
 * 【型規律（生成型を消費・手書き契約型ゼロ）】
 * BE DTO（MatchRecordScoredComponentRequest / MatchRecordScoredComponentLine /
 * MatchRecordScoredComponentResponse / MatchDetailResponse）は OpenAPI 再生成で生成型 `Schemas` に
 * 反映済みのため、再エクスポートのエイリアスのみで手書きの契約型は作らない（生成型が正本・any なし）。
 *
 * 【減点（DEDUCTION）の符号】
 * 入力は常に非負（絶対値）で受ける（BE 契約 pointsScaled は @Min(0)）。DEDUCTION は集計時に
 * サーバーが減算する（§4B 1 明細の Javadoc）。FE の合計プレビューも同じ符号規約で減算する。
 */
import { computed, ref } from 'vue'
import type { components } from '~/types/generated'

type Schemas = components['schemas']

// ===== DTO 型（生成型の再エクスポート・手書き契約型ゼロ） =====

/** 採点内訳記録リクエスト（生成型 MatchRecordScoredComponentRequest・全置換）。 */
export type MatchScoredComponentsRequestPayload = Schemas['MatchRecordScoredComponentRequest']
/** 採点内訳 1 明細リクエスト（生成型 MatchRecordScoredComponentLine）。 */
export type MatchScoredComponentLinePayload = Schemas['MatchRecordScoredComponentLine']
/** 採点内訳 1 明細レスポンス（生成型 MatchRecordScoredComponentResponse）。 */
export type MatchScoredComponentResponse = Schemas['MatchRecordScoredComponentResponse']
/** 内訳→合計再導出後の試合詳細レスポンス（生成型 MatchDetailResponse）。 */
export type ScoredComponentsMatchResponse = Schemas['MatchDetailResponse']

/** 対戦 side（生成型 MatchRecordScoredComponentLine.competitorSide に整合）。 */
export type ScoredComponentSide = MatchScoredComponentLinePayload['competitorSide']
/** 項目種別（生成型 MatchRecordScoredComponentLine.componentType に整合・TES/PCS/DEDUCTION/D_SCORE/E_SCORE）。 */
export type ScoredComponentType = MatchScoredComponentLinePayload['componentType']
/** 種目/セグメント（生成型 MatchRecordScoredComponentLine.apparatus に整合・SP/FS/FLOOR…）。 */
export type ScoredApparatus = NonNullable<MatchScoredComponentLinePayload['apparatus']>

/** 整数スケール係数（×1000・§4.1）。小数 88.43 → 整数 88430。 */
export const SCORED_COMPONENT_SCALE_FACTOR = 1000

// ===== 競技別カタログ（BE ScoredComponentCatalog のミラー・§4B.2 / §10） =====

/**
 * 競技別の許容項目（component_type）。BE ScoredComponentCatalog.COMPONENT_TYPES のミラー。
 * フィギュア=TES/PCS/DEDUCTION・体操=D_SCORE/E_SCORE。当該競技外の値は BE が 400 で弾く（症状を隠さない）。
 */
export const SCORED_COMPONENT_TYPES: Readonly<Record<'FIGURE_SKATING' | 'GYMNASTICS', readonly ScoredComponentType[]>> = {
  FIGURE_SKATING: ['TES', 'PCS', 'DEDUCTION'],
  GYMNASTICS: ['D_SCORE', 'E_SCORE'],
}

/**
 * 競技別の許容種目/セグメント（apparatus）。BE ScoredComponentCatalog.APPARATUSES のミラー。
 * フィギュア=SP/FS（セグメント）・体操=FLOOR/POMMEL_HORSE…（種目）。apparatus は任意（NULL 許容）。
 */
export const SCORED_APPARATUSES: Readonly<Record<'FIGURE_SKATING' | 'GYMNASTICS', readonly ScoredApparatus[]>> = {
  FIGURE_SKATING: ['SP', 'FS'],
  GYMNASTICS: [
    'FLOOR',
    'POMMEL_HORSE',
    'RINGS',
    'VAULT',
    'PARALLEL_BARS',
    'HORIZONTAL_BAR',
    'UNEVEN_BARS',
    'BALANCE_BEAM',
  ],
}

/** 採点競技（SCORED）の競技識別（FE 表示ラベル/カタログ引きに使う）。 */
export type ScoredSport = 'FIGURE_SKATING' | 'GYMNASTICS'

/** 採点競技を正規化する（採点競技以外は FIGURE_SKATING へフォールバック・表示ラベル代表値）。 */
export function normalizeScoredSport(sport?: string | null): ScoredSport {
  return sport === 'GYMNASTICS' ? 'GYMNASTICS' : 'FIGURE_SKATING'
}

/** 当該項目が「減点（合計から差し引く）」項目か（フィギュアの DEDUCTION のみ）。 */
export function isDeduction(componentType: ScoredComponentType): boolean {
  return componentType === 'DEDUCTION'
}

// ===== ピュア関数（テスト可能・副作用なし） =====

/**
 * 小数の点数を整数スケール（×1000）へ変換する（§4.1）。
 * 浮動小数の誤差を避けるため四捨五入する（88.43 → 88430）。null/NaN/負値は null。
 */
export function toScaledPoints(value: number | null | undefined): number | null {
  if (value === null || value === undefined || Number.isNaN(value)) return null
  if (value < 0) return null
  return Math.round(value * SCORED_COMPONENT_SCALE_FACTOR)
}

/** 整数スケール（×1000）を小数へ復元する（表示用・§4.1）。null は null。 */
export function fromScaledPoints(scaled: number | null | undefined): number | null {
  if (scaled === null || scaled === undefined || Number.isNaN(scaled)) return null
  return scaled / SCORED_COMPONENT_SCALE_FACTOR
}

/**
 * 編集中の内訳 1 行（FE ローカル状態）。
 * points は小数（UI 入力）で保持し、送信時に整数スケール×1000 へ変換する。
 */
export interface ScoredComponentDraft {
  /** 一意キー（v-for/編集識別用・送信には含めない）。 */
  readonly key: string
  /** 対戦 side（HOME/AWAY）。 */
  side: ScoredComponentSide
  /** 項目（TES/PCS/DEDUCTION/D_SCORE/E_SCORE）。 */
  componentType: ScoredComponentType
  /** 種目/セグメント（任意・null=種目区別なし）。 */
  apparatus: ScoredApparatus | null
  /** 審判識別（任意・J1〜J9 等）。 */
  judgeLabel: string | null
  /** 点数（小数・UI 入力・送信時に×1000）。 */
  points: number | null
}

/**
 * 編集中の内訳行群から全置換ペイロードを構築する（送出 JSON 形・§4B）。
 * points は×1000 整数へ変換し、未入力（null）は 0 として送る（明示 0 点）。
 * 合計はサーバーが導出するため含めない（クライアントの合計主張を信頼しない・マスアサインメント防止）。
 */
export function buildScoredComponentsPayload(
  drafts: readonly ScoredComponentDraft[],
): MatchScoredComponentsRequestPayload {
  const lines: MatchScoredComponentLinePayload[] = drafts.map((d) => {
    const line: MatchScoredComponentLinePayload = {
      competitorSide: d.side,
      componentType: d.componentType,
      pointsScaled: toScaledPoints(d.points) ?? 0,
    }
    if (d.apparatus !== null) line.apparatus = d.apparatus
    if (d.judgeLabel !== null && d.judgeLabel.trim() !== '') line.judgeLabel = d.judgeLabel.trim()
    return line
  })
  return { components: lines }
}

/**
 * 内訳行群から side 別の合計（整数スケール×1000）をプレビュー算出する（§4B.2 のサーバー集計のミラー）。
 * DEDUCTION は減算、それ以外は加算する。サーバーが正本だが、入力中の即時フィードバックに使う。
 */
export function sumScaledBySide(
  drafts: readonly ScoredComponentDraft[],
  side: ScoredComponentSide,
): number {
  return drafts
    .filter((d) => d.side === side)
    .reduce((acc, d) => {
      const scaled = toScaledPoints(d.points) ?? 0
      return isDeduction(d.componentType) ? acc - scaled : acc + scaled
    }, 0)
}

// ===== composable =====

/** useMatchScoredComponents の初期化オプション。 */
export interface UseMatchScoredComponentsOptions {
  /** 競技識別（FIGURE_SKATING / GYMNASTICS）。カタログ引き・表示ラベル出し分けに使う。 */
  sport?: string | null
}

let draftKeySeq = 0
/** 内訳行の一意キーを採番する（v-for/編集識別用・送信には含めない）。 */
function nextDraftKey(): string {
  draftKeySeq += 1
  return `scored-line-${draftKeySeq}`
}

/**
 * 採点競技の審判別/種目別採点内訳の入力・API 呼び出しを管理する composable（後段 Phase・§4B/§8/§9）。
 *
 * - 内訳行（drafts）を side 別に追加/更新/削除する。
 * - 全置換 PUT /scored-components で送信し、サーバーが合計を再導出する（二層正本）。
 * - GET で既存内訳を読み込み、ローカル draft へ復元する（再開時）。
 */
export function useMatchScoredComponents(options: UseMatchScoredComponentsOptions = {}) {
  const sport = normalizeScoredSport(options.sport)

  const base = (orgId: number, matchId: string) =>
    `/api/v1/organizations/${orgId}/matches/${matchId}/scored-components`

  /** 編集中の内訳行（home/away 混在・side で区別）。 */
  const drafts = ref<ScoredComponentDraft[]>([])
  /** 送信中フラグ（二重送信防止）。 */
  const saving = ref(false)

  /** この競技で許容される項目（カタログ・UI 選択肢）。 */
  const allowedComponentTypes = computed<readonly ScoredComponentType[]>(
    () => SCORED_COMPONENT_TYPES[sport],
  )
  /** この競技で許容される種目/セグメント（カタログ・UI 選択肢）。 */
  const allowedApparatuses = computed<readonly ScoredApparatus[]>(() => SCORED_APPARATUSES[sport])

  /** フィギュアか（表示ラベル出し分け）。 */
  const isFigureSkating = computed<boolean>(() => sport === 'FIGURE_SKATING')
  /** 体操か。 */
  const isGymnastics = computed<boolean>(() => sport === 'GYMNASTICS')

  /** 内訳が 1 件以上あるか（内訳入力モードが「正本」になっている目印・stale 整合判定）。 */
  const hasComponents = computed<boolean>(() => drafts.value.length > 0)

  /** HOME 側の内訳行。 */
  const homeDrafts = computed<ScoredComponentDraft[]>(() =>
    drafts.value.filter((d) => d.side === 'HOME'),
  )
  /** AWAY 側の内訳行。 */
  const awayDrafts = computed<ScoredComponentDraft[]>(() =>
    drafts.value.filter((d) => d.side === 'AWAY'),
  )

  /** HOME 合計（整数スケール×1000・DEDUCTION 減算済み・プレビュー）。 */
  const homeTotalScaled = computed<number>(() => sumScaledBySide(drafts.value, 'HOME'))
  /** AWAY 合計（整数スケール×1000・プレビュー）。 */
  const awayTotalScaled = computed<number>(() => sumScaledBySide(drafts.value, 'AWAY'))
  /** HOME 合計（小数復元・表示）。 */
  const homeTotal = computed<number>(() => homeTotalScaled.value / SCORED_COMPONENT_SCALE_FACTOR)
  /** AWAY 合計（小数復元・表示）。 */
  const awayTotal = computed<number>(() => awayTotalScaled.value / SCORED_COMPONENT_SCALE_FACTOR)

  /**
   * 送信可能か。
   * - 1 件以上の内訳行
   * - 全行に点数が入力済み（0 以上・null 不可）かつ項目が選択済み
   * - 送信中でない
   */
  const canSubmit = computed<boolean>(() => {
    if (saving.value) return false
    if (drafts.value.length === 0) return false
    return drafts.value.every((d) => d.points !== null && d.points >= 0)
  })

  // ===== 操作（ローカル draft） =====

  /** 内訳行を 1 件追加する（既定項目＝当該競技の先頭・既定 side=HOME）。 */
  function addLine(side: ScoredComponentSide = 'HOME'): ScoredComponentDraft {
    const firstType = allowedComponentTypes.value[0]
    // カタログは必ず 1 件以上を持つ（FIGURE_SKATING/GYMNASTICS）。万一空ならガード（noUncheckedIndexedAccess）。
    if (firstType === undefined) {
      throw new Error('useMatchScoredComponents: empty component-type catalog for sport ' + sport)
    }
    const draft: ScoredComponentDraft = {
      key: nextDraftKey(),
      side,
      componentType: firstType,
      apparatus: null,
      judgeLabel: null,
      points: null,
    }
    drafts.value = [...drafts.value, draft]
    return draft
  }

  /** 指定キーの内訳行を部分更新する（不変更新・型安全）。 */
  function updateLine(key: string, patch: Partial<Omit<ScoredComponentDraft, 'key'>>): void {
    drafts.value = drafts.value.map((d) => (d.key === key ? { ...d, ...patch } : d))
  }

  /** 指定キーの内訳行を削除する。 */
  function removeLine(key: string): void {
    drafts.value = drafts.value.filter((d) => d.key !== key)
  }

  /** すべての内訳行をクリアする（内訳入力を破棄して直接入力へ戻す導線・§4B.2 stale 整合）。 */
  function clearLines(): void {
    drafts.value = []
  }

  /** レスポンス内訳行をローカル draft へ復元する（再開時・GET 由来）。 */
  function restore(components: readonly MatchScoredComponentResponse[]): void {
    drafts.value = components.map((c) => ({
      key: nextDraftKey(),
      // レスポンスの side/componentType は OpenAPI で optional。欠落は不正データだが、
      // 表示の落ちどころとして HOME / カタログ先頭へ寄せる（症状は隠さず後続の保存で正される）。
      side: c.competitorSide ?? 'HOME',
      componentType: c.componentType ?? allowedComponentTypes.value[0] ?? 'TES',
      apparatus: c.apparatus ?? null,
      judgeLabel: c.judgeLabel ?? null,
      points: fromScaledPoints(c.pointsScaled),
    }))
  }

  // ===== API =====

  /** 既存の採点内訳を取得し、ローカル draft へ復元する（再開時）。 */
  async function load(orgId: number, matchId: string): Promise<MatchScoredComponentResponse[]> {
    const api = useApi()
    try {
      const res = await api<{ data: MatchScoredComponentResponse[] }>(base(orgId, matchId))
      const list = res.data ?? []
      restore(list)
      return list
    } catch (err) {
      const notification = useNotification()
      const { t } = useI18n()
      notification.error(t('match.scored.components.error.load_failed'))
      throw err
    }
  }

  /**
   * 採点内訳を全置換で記録/更新する（PUT /scored-components・冪等）。
   * サーバーが side 別に符号付き集計して home/away_score を再導出する（二層正本・§4B.2）。
   * status の COMPLETED 遷移は呼び出し側（live.vue）が別途 changeStatus で行う。
   */
  async function save(orgId: number, matchId: string): Promise<ScoredComponentsMatchResponse> {
    const api = useApi()
    const notification = useNotification()
    const { t } = useI18n()
    saving.value = true
    try {
      const payload = buildScoredComponentsPayload(drafts.value)
      const res = await api<{ data: ScoredComponentsMatchResponse }>(base(orgId, matchId), {
        method: 'PUT',
        body: payload,
      })
      return res.data
    } catch (err) {
      notification.error(t('match.scored.components.error.save_failed'))
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
    allowedComponentTypes,
    allowedApparatuses,
    // 状態
    drafts,
    saving,
    // 算出
    homeDrafts,
    awayDrafts,
    homeTotalScaled,
    awayTotalScaled,
    homeTotal,
    awayTotal,
    hasComponents,
    canSubmit,
    // 操作
    addLine,
    updateLine,
    removeLine,
    clearLines,
    restore,
    // API
    load,
    save,
  }
}

export type MatchScoredComponentsReturn = ReturnType<typeof useMatchScoredComponents>
