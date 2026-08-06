import type {
  SurveyResponse,
  SurveyDetailResponse,
  SurveyResultSummary,
  RespondentsResponse,
  RemindRespondentsResponse,
  QuestionType,
  ResultsVisibility,
  SurveyQuestion,
  BeQuestionType,
  SurveyQuestionWire,
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
 * 'DATE' は BE に対応値が無いが将来用にそのまま返す。
 * テスト用ビルダ（_helpers.ts）で FE 値を wire 形へ寄せるために export する。
 */
export function mapQuestionTypeToBe(fe: QuestionType): string {
  switch (fe) {
    case 'TEXT':
      return 'FREE_TEXT'
    case 'RATING':
      return 'SCALE'
    default:
      return fe
  }
}

/**
 * FE ResultsVisibility → BE `ResultsVisibility` enum 値。
 * {@link mapResultsVisibilityToFe} の逆写像。
 *
 * BE の enum は `AFTER_RESPONSE / AFTER_CLOSE / ADMINS_ONLY / VIEWERS_ONLY` の 4 値しか無く、
 * FE の `ALL_MEMBERS`（締切前から全員閲覧可）に対応する値は存在しない。そのため作成ダイアログの
 * 選択肢からは `ALL_MEMBERS` を外し、BE が表現できる `AFTER_CLOSE` を提示している。
 * ここで `ALL_MEMBERS` を受けるのは localStorage に残った旧下書きの復元経路のみで、
 * その場合は「未知値は最も閉じた値に倒す」既存方針（mapResultsVisibilityToFe 参照）に従い
 * `ADMINS_ONLY` にフォールバックする（意図より広く公開して漏洩を作らないため）。
 */
export function mapResultsVisibilityToBe(fe: ResultsVisibility): string {
  switch (fe) {
    case 'RESPONDENTS':
      return 'AFTER_RESPONSE'
    case 'AFTER_CLOSE':
      return 'AFTER_CLOSE'
    case 'CREATOR_ONLY':
    case 'ALL_MEMBERS':
      return 'ADMINS_ONLY'
  }
}

/**
 * BE resultsVisibility → FE ResultsVisibility。
 * AFTER_RESPONSE→RESPONDENTS / AFTER_CLOSE→AFTER_CLOSE /
 * ADMINS_ONLY・VIEWERS_ONLY→CREATOR_ONLY。既に FE 値ならそのまま返す（防御）。
 */
function mapResultsVisibilityToFe(be: string): ResultsVisibility {
  switch (be) {
    case 'AFTER_RESPONSE':
      return 'RESPONDENTS'
    case 'AFTER_CLOSE':
      return 'AFTER_CLOSE'
    case 'ADMINS_ONLY':
    case 'VIEWERS_ONLY':
      return 'CREATOR_ONLY'
    case 'CREATOR_ONLY':
    case 'RESPONDENTS':
    case 'ALL_MEMBERS':
      return be
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
    ...(body.resultsVisibility
      ? { resultsVisibility: mapResultsVisibilityToBe(body.resultsVisibility) }
      : {}),
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

/** BE 詳細（survey/questions 分離）→ FE 詳細（フラット展開 + hasResponded 付与）。 */
function adaptDetail(
  wire: SurveyDetailWire['data'],
  hasResponded: boolean,
): SurveyDetailResponse['data'] {
  return {
    ...wire.survey,
    policy: {
      ...wire.survey.policy,
      resultsVisibility: mapResultsVisibilityToFe(wire.survey.policy.resultsVisibility),
    },
    questions: wire.questions.map(adaptQuestion),
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
    return api<{
      data: SurveyResponse[]
      meta: { page: number; size: number; totalElements: number; totalPages: number }
    }>(`/api/v1/${toPathSegment(scopeType)}/${scopeId}/surveys?${qs}`)
  }

  async function getSurveyStats(scopeType: string, scopeId: string) {
    return api(`/api/v1/${toPathSegment(scopeType)}/${scopeId}/surveys/stats`)
  }

  async function getSurvey(
    scopeType: string,
    scopeId: string,
    surveyId: number,
  ): Promise<SurveyDetailResponse> {
    // 実 BE は survey/questions を分離した入れ子・enum 生値で返すため wire 型で受ける。
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
   */
  async function createSurvey(scopeType: string, scopeId: string, body: CreateSurveyRequest) {
    return api<{ data: SurveyResponse }>(`/api/v1/${toPathSegment(scopeType)}/${scopeId}/surveys`, {
      method: 'POST',
      body: toWireCreateBody(body),
    })
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
  ) {
    return api<{ data: SurveyResponse }>(
      `/api/v1/${toPathSegment(scopeType)}/${scopeId}/surveys/${surveyId}`,
      { method: 'PATCH', body: toWireUpdateBody(body) },
    )
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
