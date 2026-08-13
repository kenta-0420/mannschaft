import type {
  SurveyResponse,
  SurveyDetailResponse,
  SurveyResultSummary,
  RespondentsResponse,
  RemindRespondentsResponse,
  QuestionType,
  ResultsVisibility,
  BeResultsVisibility,
  SurveyQuestion,
  BeQuestionType,
  SurveyQuestionWire,
  SurveyResponseWire,
  SurveyDetailWire,
  SurveyResponseRowWire,
  MyResponseWire,
  CreateSurveyRequest,
  UpdateSurveyRequest,
} from '~/types/survey'
import type { components } from '~/types/generated'

// F05.4 (B) チーム別内訳（by_team）の生成型エイリアス。
// アンケート側は生成型が正準（survey.dto.SurveyTeamBreakdownResponse をそのまま反映している）。
export type SurveyTeamBreakdownResponse = components['schemas']['SurveyTeamBreakdownResponse']

// ---------------------------------------------------------------------------
// BE ↔ FE 翻訳層（純関数）
//
// 実 BE が返す入れ子・enum 値の生形（wire）を、画面/フォームが期待するフラット形へ変換する。
// 画面（pages/surveys/[surveyId].vue）と SurveyResponseForm.vue を無改修に保つための要。
// すべて副作用なしの純関数。テスト（_helpers.ts）で逆写像 mapQuestionTypeToBe を再利用するため
// それのみ export する。
// ---------------------------------------------------------------------------

/** BE questionType → FE QuestionType（FREE_TEXT→TEXT / SCALE→RATING、他は恒等）。 */
function mapQuestionTypeToFe(be: BeQuestionType): QuestionType {
  switch (be) {
    case 'FREE_TEXT':
      return 'TEXT'
    case 'SCALE':
      return 'RATING'
    case 'SINGLE_CHOICE':
      return 'SINGLE_CHOICE'
    case 'MULTIPLE_CHOICE':
      return 'MULTIPLE_CHOICE'
  }
}

/**
 * FE QuestionType → BE 値（TEXT→FREE_TEXT / RATING→SCALE、他は恒等）。
 * テスト用ビルダ（_helpers.ts）で FE 値を wire 形へ寄せるために export する。
 *
 * 'DATE' は BE の QuestionType enum に対応値が無い。そのまま送ると必ず 400 になるため
 * SurveyQuestionEditor は 'DATE' を選択肢から外してあり（同ファイルの NOTE 参照）、
 * この分岐は実質到達しない。写像を全域関数に保つため、日付を文字列で受ける
 * 'FREE_TEXT' に寄せる。BE に DATE 相当が入ったらここも 1:1 に戻すこと。
 */
export function mapQuestionTypeToBe(fe: QuestionType): BeQuestionType {
  switch (fe) {
    case 'TEXT':
    case 'DATE':
      return 'FREE_TEXT'
    case 'RATING':
      return 'SCALE'
    default:
      return fe
  }
}

/**
 * FE ResultsVisibility → BE `ResultsVisibility` enum 値。
 * {@link mapResultsVisibilityToFe} の逆写像で、**全単射**（1:1）である。
 *
 * Issue #2635 で BE に `ALWAYS`（PUBLISHED 以降、締切前も含めて配信対象スコープの所属者が
 * 中間集計を閲覧できる）が実装されたため、FE の `ALL_MEMBERS` を `ADMINS_ONLY` へ潰していた
 * 旧写像を `ALWAYS` へ改めた。あわせて `VIEWERS_ONLY` も自分自身へ写す。
 *
 * これで「読み込む→更新する」の往復で値が化けなくなった（Issue #2617-2）。
 * 旧実装では `VIEWERS_ONLY` を読むと `CREATOR_ONLY` になり、そのまま更新すると
 * `ADMINS_ONLY` として保存され、名簿指定の公開範囲が黙って失われていた。
 */
export function mapResultsVisibilityToBe(fe: ResultsVisibility): BeResultsVisibility {
  switch (fe) {
    case 'RESPONDENTS':
      return 'AFTER_RESPONSE'
    case 'AFTER_CLOSE':
      return 'AFTER_CLOSE'
    case 'ALL_MEMBERS':
      return 'ALWAYS'
    case 'VIEWERS_ONLY':
      return 'VIEWERS_ONLY'
    case 'CREATOR_ONLY':
      return 'ADMINS_ONLY'
  }
}

/**
 * BE resultsVisibility → FE ResultsVisibility。{@link mapResultsVisibilityToBe} の逆写像。
 *
 * BE 5 値と FE 5 値が 1:1 に対応するため畳み込みは無い。
 * 型に無い値（BE に新しい enum が増えたが FE が追随していない場合）だけは、
 * 意図より広く公開して漏洩を作らないよう最も閉じた `CREATOR_ONLY` に倒す。
 */
function mapResultsVisibilityToFe(be: BeResultsVisibility): ResultsVisibility {
  switch (be) {
    case 'AFTER_RESPONSE':
      return 'RESPONDENTS'
    case 'AFTER_CLOSE':
      return 'AFTER_CLOSE'
    case 'ALWAYS':
      return 'ALL_MEMBERS'
    case 'VIEWERS_ONLY':
      return 'VIEWERS_ONLY'
    case 'ADMINS_ONLY':
      return 'CREATOR_ONLY'
    default:
      // 未知値は最も閉じた CREATOR_ONLY に倒す（漏洩を作らない）
      return 'CREATOR_ONLY'
  }
}

/** BE の CreateSurveyRequest / CreateQuestionRequest / CreateOptionRequest 形（生成型が正準）。 */
type WireCreateSurvey = components['schemas']['CreateSurveyRequest']
type WireCreateQuestion = components['schemas']['CreateQuestionRequest']
type WireCreateOption = components['schemas']['CreateOptionRequest']
type WireUpdateSurvey = components['schemas']['UpdateSurveyRequest']

/** FE ドメイン形の設問入力（作成ダイアログ・DRAFT 詳細の設問追加で共通）。 */
export type SurveyQuestionInput = NonNullable<CreateSurveyRequest['questions']>[number]

/**
 * FE 設問 → BE `CreateQuestionRequest`。
 * `sortOrder` は BE では `displayOrder`、`questionType` は BE enum 値へ写す。
 *
 * 作成（createSurvey）と設問追加（addQuestion）の両経路が BE の同一 DTO を使うため、
 * 翻訳はこの 1 箇所に集約する。
 */
export function toWireQuestion(q: SurveyQuestionInput): WireCreateQuestion {
  const options: WireCreateOption[] | undefined = q.options?.map((o) => ({
    optionText: o.optionText,
    displayOrder: o.sortOrder,
  }))
  return {
    questionText: q.questionText,
    questionType: mapQuestionTypeToBe(q.questionType),
    isRequired: q.isRequired ?? false,
    displayOrder: q.sortOrder,
    ...(options ? { options } : {}),
  }
}

/**
 * FE `CreateSurveyRequest` → BE `CreateSurveyRequest`（送信ボディ）。
 *
 * BE 必須の `distributionMode` を必ず載せ（対象者を明示しない限り `ALL`）、
 * 締切は BE のフィールド名 `expiresAt` で送る。enum 値・設問の項目名も BE 形へ翻訳する。
 */
export function toWireCreateBody(body: CreateSurveyRequest): WireCreateSurvey {
  return {
    title: body.title,
    ...(body.description !== undefined ? { description: body.description } : {}),
    isAnonymous: body.isAnonymous ?? false,
    allowMultipleSubmissions: body.allowMultipleSubmissions ?? false,
    ...(body.teamBreakdownEnabled !== undefined
      ? { teamBreakdownEnabled: body.teamBreakdownEnabled }
      : {}),
    // BE 必須（openapi の required）。未指定は最も閉じた CREATOR_ONLY 相当に倒す
    // （既定で広く公開して漏洩を作らない）。
    resultsVisibility: mapResultsVisibilityToBe(body.resultsVisibility ?? 'CREATOR_ONLY'),
    ...(body.unrespondedVisibility ? { unrespondedVisibility: body.unrespondedVisibility } : {}),
    // BE は @NotBlank。対象者を絞り込む UI がまだ無いため常に全員配信。
    distributionMode: body.distributionMode ?? 'ALL',
    ...(body.expiresAt ? { expiresAt: body.expiresAt } : {}),
    questions: (body.questions ?? []).map(toWireQuestion),
  }
}

/**
 * FE `UpdateSurveyRequest` → BE `UpdateSurveyRequest`。未指定キーは送らない（PATCH セマンティクス）。
 *
 * `description` / `expiresAt` は明示的な `null` を「値を消す」意図として素通しする。
 * 生成型はこの nullable を表現していない（BE DTO に `@Schema` 注記が無いため）ので、
 * その 2 キーだけ null を許す形に広げている。
 */
export function toWireUpdateBody(
  body: UpdateSurveyRequest,
): Omit<WireUpdateSurvey, 'description' | 'expiresAt'> & {
  description?: string | null
  expiresAt?: string | null
} {
  return {
    ...(body.title !== undefined ? { title: body.title } : {}),
    ...(body.description !== undefined ? { description: body.description } : {}),
    ...(body.isAnonymous !== undefined ? { isAnonymous: body.isAnonymous } : {}),
    ...(body.allowMultipleSubmissions !== undefined
      ? { allowMultipleSubmissions: body.allowMultipleSubmissions }
      : {}),
    ...(body.resultsVisibility
      ? { resultsVisibility: mapResultsVisibilityToBe(body.resultsVisibility) }
      : {}),
    ...(body.unrespondedVisibility ? { unrespondedVisibility: body.unrespondedVisibility } : {}),
    ...(body.expiresAt !== undefined ? { expiresAt: body.expiresAt } : {}),
  }
}

/** BE 設問（入れ子）→ FE 設問（フラット）。RATING は options 空のため Form の 1〜5 フォールバックが効く。 */
function adaptQuestion(w: SurveyQuestionWire): SurveyQuestion {
  return {
    id: w.id,
    questionText: w.content.questionText,
    questionType: mapQuestionTypeToFe(w.questionType),
    isRequired: w.content.isRequired,
    sortOrder: w.content.displayOrder,
    options: (w.options ?? []).map((o) => ({
      id: o.id,
      optionText: o.optionText,
      sortOrder: o.displayOrder,
    })),
  }
}

/**
 * BE survey（フラット）→ FE survey。policy.resultsVisibility のみ FE 値へ写す。
 *
 * 一覧・詳細・作成・更新のすべてがこの 1 箇所を通るようにしてある。
 * 経路ごとに翻訳するかしないかが割れると、画面によって `AFTER_RESPONSE` の生値が
 * 素通しされ、FE 値で分岐している判定（pages/surveys/[surveyId].vue の canViewResults 等）が
 * 黙って default に落ちる。
 */
function adaptSurvey(wire: SurveyResponseWire): SurveyResponse {
  return {
    ...wire,
    policy: {
      ...wire.policy,
      resultsVisibility: mapResultsVisibilityToFe(wire.policy.resultsVisibility),
    },
  }
}

/**
 * BE 詳細 → FE 詳細（hasResponded 付与）。
 *
 * Issue #2635 で BE の `SurveyDetailResponse` がフラット化され、`data.survey` の入れ子が
 * 消えた。旧実装は `wire.survey.policy` を読んでいたため、TypeError で詳細画面が落ちていた。
 */
function adaptDetail(
  wire: SurveyDetailWire['data'],
  hasResponded: boolean,
): SurveyDetailResponse['data'] {
  const { questions, ...survey } = wire
  return {
    ...adaptSurvey(survey),
    questions: (questions ?? []).map(adaptQuestion),
    hasResponded,
  }
}

/** Form の buildPayload() が作る FE answer entry の形。 */
interface FeAnswerEntry {
  questionId: number
  optionId?: number
  optionIds?: number[]
  textValue?: string
  ratingValue?: number
  dateValue?: string
}

/** 送信ボディの BE 形（optionIds 複数形・textResponse のみ）。 */
interface BeSubmitBody {
  answers: Array<{ questionId: number; optionIds?: number[]; textResponse?: string }>
}

/** prefill 用に getMyResponse が返す FE 形。 */
interface MyResponseFe {
  answers: Array<{
    questionId: number
    optionId?: number
    optionIds?: number[]
    textValue?: string
    ratingValue?: number
    dateValue?: string
  }>
}

/**
 * FE 送信ボディ → BE 送信ボディ。
 * optionId は optionIds:[optionId] に正規化、textValue/ratingValue/dateValue は textResponse に集約。
 * 空フィールドは付けない。
 */
function adaptSubmit(feBody: { answers?: FeAnswerEntry[] }): BeSubmitBody {
  const answers = (feBody.answers ?? []).map((a) => {
    const entry: { questionId: number; optionIds?: number[]; textResponse?: string } = {
      questionId: a.questionId,
    }
    if (Array.isArray(a.optionIds)) {
      entry.optionIds = a.optionIds
    } else if (typeof a.optionId === 'number') {
      entry.optionIds = [a.optionId]
    }
    if (typeof a.textValue === 'string') {
      entry.textResponse = a.textValue
    } else if (typeof a.ratingValue === 'number') {
      entry.textResponse = String(a.ratingValue)
    } else if (typeof a.dateValue === 'string') {
      entry.textResponse = a.dateValue
    }
    return entry
  })
  return { answers }
}

/**
 * BE「自分の回答」行配列 → FE prefill 形。
 * questionId でグループ化し、optionId 群を optionIds に集約（先頭を optionId にも）。
 * textResponse は数値なら ratingValue にも、文字列として textValue/dateValue にも詰める
 * （Form 側が questionType で適切なものを選ぶため、全部入れて構わない）。
 */
function adaptMyResponse(rows: SurveyResponseRowWire[]): MyResponseFe {
  const byQuestion = new Map<number, MyResponseFe['answers'][number]>()
  const list = Array.isArray(rows) ? rows : []
  for (const row of list) {
    let entry = byQuestion.get(row.questionId)
    if (!entry) {
      entry = { questionId: row.questionId }
      byQuestion.set(row.questionId, entry)
    }
    if (typeof row.optionId === 'number') {
      if (!entry.optionIds) entry.optionIds = []
      entry.optionIds.push(row.optionId)
      if (entry.optionId === undefined) entry.optionId = row.optionId
    }
    if (typeof row.textResponse === 'string' && row.textResponse.length > 0) {
      entry.textValue = row.textResponse
      const n = Number(row.textResponse)
      if (!Number.isNaN(n)) entry.ratingValue = n
      entry.dateValue = row.textResponse
    }
  }
  return { answers: Array.from(byQuestion.values()) }
}

export function useSurveyApi() {
  const api = useApi()

  function buildQuery(params: Record<string, unknown>): string {
    const query = new URLSearchParams()
    for (const [key, value] of Object.entries(params)) {
      if (value !== undefined && value !== null) query.set(key, String(value))
    }
    return query.toString()
  }

  /** Convert 'TEAM'/'ORGANIZATION' or 'teams'/'organizations' to path segment */
  function toPathSegment(scopeType: string): string {
    const s = scopeType.toLowerCase()
    if (s === 'team' || s === 'teams') return 'teams'
    if (s === 'organization' || s === 'organizations') return 'organizations'
    return s
  }

  // === Surveys CRUD ===
  async function getSurveys(
    scopeType: string,
    scopeId: string,
    params?: Record<string, unknown> | string,
  ) {
    const resolvedParams = typeof params === 'string' ? { status: params } : params || {}
    const qs = buildQuery(resolvedParams)
    // 一覧は従来どおりフラットな SurveyResponse の配列。enum だけ FE 値へ写す。
    const raw = await api<{
      data: SurveyResponseWire[]
      meta: { page: number; size: number; totalElements: number; totalPages: number }
    }>(`/api/v1/${toPathSegment(scopeType)}/${scopeId}/surveys?${qs}`)
    return { ...raw, data: (raw.data ?? []).map(adaptSurvey) }
  }

  async function getSurveyStats(scopeType: string, scopeId: string) {
    return api(`/api/v1/${toPathSegment(scopeType)}/${scopeId}/surveys/stats`)
  }

  async function getSurvey(
    scopeType: string,
    scopeId: string,
    surveyId: number,
  ): Promise<SurveyDetailResponse> {
    // 実 BE はフラットな詳細形・enum 生値で返すため wire 型で受ける。
    const raw = await api<SurveyDetailWire>(
      `/api/v1/${toPathSegment(scopeType)}/${scopeId}/surveys/${surveyId}`,
    )
    // 実 BE は hasResponded を返さないため、自分の回答（行配列）の有無から導出する。
    let responded = false
    try {
      const mine = await api<MyResponseWire>(`/api/v1/surveys/${surveyId}/responses/me`)
      responded = Array.isArray(mine.data) && mine.data.length > 0
    } catch {
      // 404（未回答）等は未回答扱い。可視性/モード判定は false で安全側に倒れる。
      responded = false
    }
    return { data: adaptDetail(raw.data, responded) }
  }

  /**
   * アンケート新規作成。
   *
   * FE ドメイン形（{@link CreateSurveyRequest}）を受け取り、{@link toWireCreateBody} で
   * BE の `CreateSurveyRequest` 形へ翻訳してから送信する。呼び出し側は BE の enum 値や
   * フィールド名（displayOrder / FREE_TEXT 等）を意識しなくてよい。
   *
   * 作成 POST のレスポンスは詳細 GET と同じフラットな `SurveyDetailResponse`（Issue #2635）。
   * 作成直後に回答は存在しないため hasResponded は false。
   */
  async function createSurvey(
    scopeType: string,
    scopeId: string,
    body: CreateSurveyRequest,
  ): Promise<SurveyDetailResponse> {
    const raw = await api<SurveyDetailWire>(
      `/api/v1/${toPathSegment(scopeType)}/${scopeId}/surveys`,
      { method: 'POST', body: toWireCreateBody(body) },
    )
    return { data: adaptDetail(raw.data, false) }
  }

  /**
   * アンケート更新。
   *
   * FE ドメイン形（{@link UpdateSurveyRequest}）を {@link toWireUpdateBody} で BE 形へ翻訳して送る。
   */
  async function updateSurvey(
    scopeType: string,
    scopeId: string,
    surveyId: number,
    body: UpdateSurveyRequest,
  ): Promise<{ data: SurveyResponse }> {
    // 更新はフラットな SurveyResponse を返す（詳細のようなフラット化の影響は受けない）。
    const raw = await api<{ data: SurveyResponseWire }>(
      `/api/v1/${toPathSegment(scopeType)}/${scopeId}/surveys/${surveyId}`,
      { method: 'PATCH', body: toWireUpdateBody(body) },
    )
    return { data: adaptSurvey(raw.data) }
  }

  async function deleteSurvey(scopeType: string, scopeId: string, surveyId: number) {
    return api(`/api/v1/${toPathSegment(scopeType)}/${scopeId}/surveys/${surveyId}`, {
      method: 'DELETE',
    })
  }

  async function publishSurvey(scopeType: string, scopeId: string, surveyId: number) {
    return api(`/api/v1/${toPathSegment(scopeType)}/${scopeId}/surveys/${surveyId}/publish`, {
      method: 'POST',
    })
  }

  async function closeSurvey(scopeType: string, scopeId: string, surveyId: number) {
    return api(`/api/v1/${toPathSegment(scopeType)}/${scopeId}/surveys/${surveyId}/close`, {
      method: 'POST',
    })
  }

  // === Questions ===
  /**
   * 設問追加（DRAFT 詳細の「設問を保存して公開」経路）。
   *
   * BE は作成時と同じ `CreateQuestionRequest` を受けるため、翻訳も
   * {@link toWireQuestion} に寄せる。FE ドメイン形（`questionType: 'TEXT'`・`sortOrder`）を
   * そのまま送ると `questionType` は 400、`sortOrder` は BE が読まず
   * displayOrder が全設問 0 になって並び順が失われる。
   */
  async function addQuestion(
    scopeType: string,
    scopeId: string,
    surveyId: number,
    body: SurveyQuestionInput,
  ) {
    return api(`/api/v1/${toPathSegment(scopeType)}/${scopeId}/surveys/${surveyId}/questions`, {
      method: 'POST',
      body: toWireQuestion(body),
    })
  }

  async function deleteQuestion(
    scopeType: string,
    scopeId: string,
    surveyId: number,
    questionId: number,
  ) {
    return api(
      `/api/v1/${toPathSegment(scopeType)}/${scopeId}/surveys/${surveyId}/questions/${questionId}`,
      { method: 'DELETE' },
    )
  }

  // === Responses ===
  async function submitResponse(surveyId: number, body: Record<string, unknown>) {
    // Form は FE 形（optionId/textValue/ratingValue/dateValue）で渡してくるため
    // BE 形（optionIds/textResponse）へ整形して送る。
    return api(`/api/v1/surveys/${surveyId}/responses`, {
      method: 'POST',
      body: adaptSubmit(body as { answers?: Array<{ questionId: number }> }),
    })
  }

  async function getMyResponse(surveyId: number): Promise<{ data: MyResponseFe }> {
    // 実 BE は行配列で返すため、Form の prefill が期待する {data:{answers:[...]}} 形へ畳み込む。
    const raw = await api<MyResponseWire>(`/api/v1/surveys/${surveyId}/responses/me`)
    return { data: adaptMyResponse(raw.data ?? []) }
  }

  // === Targets ===
  async function setTargets(surveyId: number, body: Record<string, unknown>) {
    return api(`/api/v1/surveys/${surveyId}/targets`, { method: 'POST', body })
  }

  // === Result Viewers ===
  async function setResultViewers(surveyId: number, body: Record<string, unknown>) {
    return api(`/api/v1/surveys/${surveyId}/result-viewers`, { method: 'POST', body })
  }

  // === Results ===
  async function getResults(surveyId: number) {
    return api<{ data: SurveyResultSummary[] }>(`/api/v1/surveys/${surveyId}/results`)
  }

  /**
   * F05.4 (B) アンケート結果のチーム別内訳（by_team）を取得する。
   * 認可: 組織 ADMIN 限定（非 ADMIN は 403）。トグル OFF / 非組織 / 匿名のときは byTeam が空/未定義。
   * 設計書: docs/features/F05.4_survey_vote.md / PR #1679
   */
  async function getTeamBreakdown(surveyId: number) {
    return api<{ data: SurveyTeamBreakdownResponse }>(
      `/api/v1/surveys/${surveyId}/results/team-breakdown`,
    )
  }

  // === Respondents (未回答者一覧の可視化) ===
  /**
   * 回答者・未回答者一覧を取得する。
   * 認可分岐は Backend 側で unrespondedVisibility に応じて行う。
   */
  async function getRespondents(scopeType: string, scopeId: string, surveyId: number) {
    return api<RespondentsResponse>(
      `/api/v1/${toPathSegment(scopeType)}/${scopeId}/surveys/${surveyId}/respondents`,
    )
  }

  /**
   * 未回答メンバーへの手動リマインド送信。
   * 認可: 作成者 / ADMIN+ のみ（Backend 側で判定）。
   * 制約: 手動送信は最大3回まで・前回送信から24時間経過必須・PUBLISHED のみ。
   * 設計書: docs/features/F05.4_survey_vote.md `POST /api/v1/surveys/{id}/remind`
   *
   * NOTE: Backend は Jackson デフォルト命名（camelCase）で返す。
   * 設計書の snake_case 例示はドキュメント側の不整合のため、レスポンスは camelCase で受ける。
   */
  async function remindRespondents(surveyId: number) {
    return api<RemindRespondentsResponse>(`/api/v1/surveys/${surveyId}/remind`, { method: 'POST' })
  }

  return {
    getSurveys,
    getSurveyStats,
    getSurvey,
    createSurvey,
    updateSurvey,
    deleteSurvey,
    publishSurvey,
    closeSurvey,
    addQuestion,
    deleteQuestion,
    submitResponse,
    getMyResponse,
    setTargets,
    setResultViewers,
    getResults,
    getTeamBreakdown,
    getRespondents,
    remindRespondents,
  }
}
