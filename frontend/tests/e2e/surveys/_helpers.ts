import type { Page, Route } from '@playwright/test'
import type {
  SurveyResponse,
  SurveyQuestion,
  SurveyDetailResponse,
  SurveyResultSummary,
  RespondentItem,
} from '../../../app/types/survey'

/**
 * F05.4 アンケート画面 E2E テスト 共通ヘルパー。
 *
 * <p>方針:</p>
 * <ul>
 *   <li>API モック方式（page.route で `**\/api/v1/...` をモック）</li>
 *   <li>shifts/_helpers.ts および recruitment/recruitment.spec.ts のパターンを踏襲</li>
 *   <li>Backend DTO（SurveyResponse / SurveyDetailResponse / SurveyResultSummary / RespondentItem）に準拠</li>
 *   <li>fixture は関数で生成（overrides で一部上書き可能）</li>
 *   <li>後着優先のため、テスト個別の page.route は本ヘルパー呼び出し後に上書き可能</li>
 * </ul>
 *
 * <p>認証は spec の {@code beforeEach} で {@link setupAuth} を呼び、localStorage に
 * accessToken / currentUser を注入する方式。confirmable/recruitment と同じ流儀。</p>
 */

// ---------------------------------------------------------------------------
// 認証セットアップ
// ---------------------------------------------------------------------------

/** 認証注入オプション。role / scope は backend の RoleAccess に合わせる。 */
export interface AuthOptions {
  /** ログイン中ユーザーの ID */
  userId: number
  /** displayName（UI 表示用） */
  displayName: string
  /** ロール（バックエンド AccessRole 準拠） */
  role: 'SYSTEM_ADMIN' | 'ADMIN' | 'MEMBER' | 'GUEST'
  /** scope 種別。currentScope を localStorage に書く場合に使用 */
  scopeType?: 'TEAM' | 'ORGANIZATION'
  /** scope ID。currentScope を localStorage に書く場合に使用 */
  scopeId?: number
}

/**
 * ログイン済み状態をシミュレートする。
 * accessToken / refreshToken / currentUser を localStorage に注入し、
 * scopeType / scopeId が指定されていれば currentScope も併せて注入する。
 */
export async function setupAuth(page: Page, opts: AuthOptions): Promise<void> {
  await page.addInitScript((args) => {
    localStorage.setItem(
      'accessToken',
      'eyJhbGciOiJIUzM4NCJ9.e2UyZV90ZXN0X3VzZXJ9.placeholder_for_e2e',
    )
    localStorage.setItem('refreshToken', 'e2e-refresh-token-placeholder')
    localStorage.setItem(
      'currentUser',
      JSON.stringify({
        id: args.userId,
        email: `e2e-${args.role.toLowerCase()}@example.com`,
        displayName: args.displayName,
        profileImageUrl: null,
        role: args.role,
      }),
    )
    if (args.scopeType && args.scopeId !== undefined) {
      localStorage.setItem(
        'currentScope',
        JSON.stringify({
          type: args.scopeType.toLowerCase(),
          id: args.scopeId,
          name: `e2e-${args.scopeType.toLowerCase()}-${args.scopeId}`,
        }),
      )
    }
  }, opts)
}

// ---------------------------------------------------------------------------
// ロケール切替
// ---------------------------------------------------------------------------

/** i18n が対応するロケールコード。 */
export type Locale = 'ja' | 'en' | 'zh' | 'ko' | 'es' | 'de'

/**
 * @nuxtjs/i18n のロケールを切り替える。
 * addInitScript で navigator.language を上書きし、cookie/localStorage 双方に
 * 値を入れることで初期表示前にロケール検出を確定させる。
 */
export async function setLocale(page: Page, locale: Locale): Promise<void> {
  await page.addInitScript((loc) => {
    try {
      // 一部の検出ロジック・自前 useLocale 用
      localStorage.setItem('i18n_redirected', loc)
      localStorage.setItem('i18n_locale', loc)
      document.cookie = `i18n_redirected=${loc}; path=/`
    } catch {
      // localStorage / document.cookie が使えない環境では黙ってスキップ
    }
  }, locale)
}

// ---------------------------------------------------------------------------
// fixture ビルダ（BE DTO 準拠）
// ---------------------------------------------------------------------------

/** {@link buildSurvey} の overrides 引数。 */
export type BuildSurveyOptions = Partial<SurveyResponse>

/**
 * SurveyResponse の雛形を生成する。
 * 全フィールドにデフォルト値を持たせ、opts でフィールド単位に上書き可能。
 */
export function buildSurvey(opts: BuildSurveyOptions = {}): SurveyResponse {
  return {
    id: 1,
    scopeType: 'TEAM',
    scopeId: 1,
    title: 'E2Eテスト用アンケート',
    description: 'これは E2E テスト用のアンケートです。',
    status: 'PUBLISHED',
    isAnonymous: false,
    allowMultipleSubmissions: false,
    resultsVisibility: 'ALL_MEMBERS',
    unrespondedVisibility: 'CREATOR_AND_ADMIN',
    deadline: '2026-05-31T23:59:59Z',
    createdBy: { id: 1, displayName: 'e2e_admin' },
    responseCount: 0,
    targetCount: 10,
    hasResponded: false,
    createdAt: '2026-04-20T00:00:00Z',
    updatedAt: '2026-04-20T00:00:00Z',
    ...opts,
  }
}

/** {@link buildQuestion} の引数。 */
export interface BuildQuestionOptions {
  id: number
  questionText: string
  questionType: 'SINGLE_CHOICE' | 'MULTIPLE_CHOICE' | 'TEXT' | 'RATING' | 'DATE'
  isRequired?: boolean
  sortOrder?: number
  options?: Array<{ id: number; optionText: string; sortOrder: number }>
}

/** SurveyQuestion の雛形を生成する（選択肢なしのデフォルトは空配列）。 */
export function buildQuestion(opts: BuildQuestionOptions): SurveyQuestion {
  return {
    id: opts.id,
    questionText: opts.questionText,
    questionType: opts.questionType,
    isRequired: opts.isRequired ?? false,
    sortOrder: opts.sortOrder ?? 1,
    options: opts.options ?? [],
  }
}

/**
 * SurveyDetailResponse 形式（API レスポンス全体）を生成する。
 * Backend は `{ data: SurveyResponse & { questions } }` 構造で返すため
 * fulfill 用の body にそのまま JSON.stringify できる。
 */
export function buildSurveyDetail(
  survey: SurveyResponse,
  questions: SurveyQuestion[],
): SurveyDetailResponse {
  return {
    data: {
      ...survey,
      questions,
    },
  }
}

/** {@link buildResultSummary} の引数。 */
export interface BuildResultOptions {
  questionId: number
  questionText: string
  questionType: 'SINGLE_CHOICE' | 'MULTIPLE_CHOICE' | 'TEXT' | 'RATING' | 'DATE'
  totalResponses: number
  optionResults?: Array<{
    optionId: number
    optionText: string
    count: number
    percentage: number
  }>
  textResponses?: string[]
}

/** SurveyResultSummary（質問単位の集計）の雛形を生成する。 */
export function buildResultSummary(opts: BuildResultOptions): SurveyResultSummary {
  return {
    questionId: opts.questionId,
    questionText: opts.questionText,
    questionType: opts.questionType,
    totalResponses: opts.totalResponses,
    optionResults: opts.optionResults ?? [],
    textResponses: opts.textResponses,
  }
}

/** RespondentItem の雛形を生成する。 */
export function buildRespondent(
  opts: Partial<RespondentItem> & { userId: number; displayName: string },
): RespondentItem {
  return {
    userId: opts.userId,
    displayName: opts.displayName,
    avatarUrl: opts.avatarUrl ?? null,
    hasResponded: opts.hasResponded ?? false,
    respondedAt: opts.respondedAt ?? null,
  }
}

// ---------------------------------------------------------------------------
// API モック
// ---------------------------------------------------------------------------

/** {@link mockSurveyApi} のオプション。 */
export interface MockSurveyApiOptions {
  /** 一覧 API（GET /surveys）で返すアンケート群 */
  surveys?: SurveyResponse[]
  /** 詳細 API（GET /surveys/{id}）で返すレスポンス（id をキーに分岐） */
  detailById?: Record<number, SurveyDetailResponse>
  /** 集計 API（GET /surveys/{id}/results）で返すレスポンス（id をキー） */
  resultsById?: Record<number, SurveyResultSummary[]>
  /** 回答者 API（GET .../respondents）で返すレスポンス（id をキー） */
  respondentsById?: Record<number, RespondentItem[]>
  /** 督促 API（POST /surveys/{id}/remind）のレスポンス制御 */
  remindResponse?: { ok: boolean; status?: number; body?: unknown }
  /** 作成 API（POST /surveys）のレスポンスを動的に決めたい場合のフック */
  onCreate?: (body: unknown) => SurveyResponse
}

/**
 * F05.4 Survey 関連 API を一括モックする。
 *
 * <p>登録順序の都合で「未マッチを 200 で空返し」する catch-all は登録しない
 * （recruitment.spec.ts 側で必要なら spec で追加すること）。本関数は
 * Survey 系エンドポイントのみピンポイントでハンドルする。</p>
 *
 * <p>後着優先のため、本関数の呼び出し後に spec 個別の page.route で
 * 振る舞いを上書きできる。</p>
 */
export async function mockSurveyApi(page: Page, opts: MockSurveyApiOptions): Promise<void> {
  // ----- 一覧（チーム / 組織）-----
  const listHandler = async (route: Route): Promise<void> => {
    if (route.request().method() !== 'GET') {
      await route.continue()
      return
    }
    const data = opts.surveys ?? []
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data,
        meta: {
          page: 0,
          size: 50,
          totalElements: data.length,
          totalPages: data.length === 0 ? 0 : 1,
        },
      }),
    })
  }
  await page.route('**/api/v1/teams/*/surveys', listHandler)
  await page.route('**/api/v1/teams/*/surveys?*', listHandler)
  await page.route('**/api/v1/organizations/*/surveys', listHandler)
  await page.route('**/api/v1/organizations/*/surveys?*', listHandler)

  // ----- 詳細（チーム / 組織）-----
  // path: /api/v1/{scope}/{scopeId}/surveys/{surveyId}（GET のみ）
  const detailHandler = async (route: Route): Promise<void> => {
    if (route.request().method() !== 'GET') {
      await route.continue()
      return
    }
    const url = new URL(route.request().url())
    const match = url.pathname.match(/\/surveys\/(\d+)$/)
    if (!match) {
      await route.continue()
      return
    }
    const id = Number(match[1])
    const detail = opts.detailById?.[id]
    if (!detail) {
      await route.fulfill({
        status: 404,
        contentType: 'application/json',
        body: JSON.stringify({
          error: { code: 'SURVEY_NOT_FOUND', message: 'survey not found' },
        }),
      })
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(detail),
    })
  }
  await page.route('**/api/v1/teams/*/surveys/*', detailHandler)
  await page.route('**/api/v1/organizations/*/surveys/*', detailHandler)

  // ----- 作成（POST /{scope}/{id}/surveys）-----
  if (opts.onCreate) {
    const createHandler = async (route: Route): Promise<void> => {
      if (route.request().method() !== 'POST') {
        await route.continue()
        return
      }
      let body: unknown = null
      try {
        body = route.request().postDataJSON() as unknown
      } catch {
        body = null
      }
      const created = opts.onCreate!(body)
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({ data: created }),
      })
    }
    // 一覧 GET と作成 POST は同一 path のため、handler 内で method 分岐する形で
    // listHandler を上書きするには「同じ path に新たに route を被せる」必要がある。
    // Playwright は後勝ちなので、listHandler 後にこちらを登録すれば POST はこちらが拾う。
    await page.route('**/api/v1/teams/*/surveys', async (route) => {
      if (route.request().method() === 'POST') {
        await createHandler(route)
        return
      }
      await listHandler(route)
    })
    await page.route('**/api/v1/organizations/*/surveys', async (route) => {
      if (route.request().method() === 'POST') {
        await createHandler(route)
        return
      }
      await listHandler(route)
    })
  }

  // ----- 集計（GET /api/v1/surveys/{id}/results）-----
  await page.route('**/api/v1/surveys/*/results', async (route) => {
    if (route.request().method() !== 'GET') {
      await route.continue()
      return
    }
    const url = new URL(route.request().url())
    const match = url.pathname.match(/\/surveys\/(\d+)\/results$/)
    const id = match ? Number(match[1]) : NaN
    const results = opts.resultsById?.[id] ?? []
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: results }),
    })
  })

  // ----- 回答者一覧（GET /{scope}/{id}/surveys/{surveyId}/respondents）-----
  const respondentsHandler = async (route: Route): Promise<void> => {
    if (route.request().method() !== 'GET') {
      await route.continue()
      return
    }
    const url = new URL(route.request().url())
    const match = url.pathname.match(/\/surveys\/(\d+)\/respondents$/)
    const id = match ? Number(match[1]) : NaN
    const respondents = opts.respondentsById?.[id] ?? []
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: respondents }),
    })
  }
  await page.route('**/api/v1/teams/*/surveys/*/respondents', respondentsHandler)
  await page.route('**/api/v1/organizations/*/surveys/*/respondents', respondentsHandler)

  // ----- 督促（POST /api/v1/surveys/{id}/remind）-----
  if (opts.remindResponse) {
    const remind = opts.remindResponse
    await page.route('**/api/v1/surveys/*/remind', async (route) => {
      if (route.request().method() !== 'POST') {
        await route.continue()
        return
      }
      if (remind.ok) {
        await route.fulfill({
          status: remind.status ?? 200,
          contentType: 'application/json',
          body: JSON.stringify(
            remind.body ?? {
              data: {
                survey_id: 1,
                reminded_count: 1,
                remaining_remind_quota: 2,
                message: 'reminded',
              },
            },
          ),
        })
      } else {
        await route.fulfill({
          status: remind.status ?? 400,
          contentType: 'application/json',
          body: JSON.stringify(
            remind.body ?? {
              error: { code: 'SURVEY_REMIND_FAILED', message: 'failed' },
            },
          ),
        })
      }
    })
  }
}

// ---------------------------------------------------------------------------
// 待機ヘルパー
// ---------------------------------------------------------------------------

/**
 * Nuxt SSR ページで Vue のクライアントサイドハイドレーション完了を待つ。
 * helpers/wait.ts の waitForHydration と同等。survey 系から直接呼ぶための再 export。
 */
export async function waitForHydration(page: Page): Promise<void> {
  await page.waitForFunction(() => {
    const el = document.querySelector('#__nuxt')
    return el !== null && '__vue_app__' in el
  })
}

/**
 * SurveyList のレンダリング完了を待つ。
 * data-testid="survey-list-container" を持つコンテナの出現と hydration を待機する。
 */
export async function waitForSurveyList(page: Page): Promise<void> {
  await waitForHydration(page)
  await page.waitForSelector('[data-testid="survey-list-container"]', { timeout: 10_000 })
}

/**
 * Survey 詳細ページのレンダリング完了を待つ。
 * data-testid="survey-detail-page" を持つコンテナの出現と hydration を待機する。
 */
export async function waitForSurveyDetail(page: Page, _surveyId: number): Promise<void> {
  await waitForHydration(page)
  await page.waitForSelector('[data-testid="survey-detail-page"]', { timeout: 10_000 })
}

// ---------------------------------------------------------------------------
// ナビゲーションヘルパー
// ---------------------------------------------------------------------------

/** チームのアンケート一覧ページへ遷移する（`/teams/{teamId}/surveys`）。 */
export async function gotoTeamSurveys(page: Page, teamId: number): Promise<void> {
  await page.goto(`/teams/${teamId}/surveys`)
}

/** 組織のアンケート一覧ページへ遷移する（`/organizations/{orgId}/surveys`）。 */
export async function gotoOrgSurveys(page: Page, orgId: number): Promise<void> {
  await page.goto(`/organizations/${orgId}/surveys`)
}

/**
 * アンケート詳細ページへ遷移する。
 * `/surveys/{surveyId}?scope={team|organization}&scopeId={scopeId}` 形式。
 */
export async function gotoSurveyDetail(
  page: Page,
  surveyId: number,
  scopeType: 'team' | 'organization',
  scopeId: number,
): Promise<void> {
  await page.goto(`/surveys/${surveyId}?scope=${scopeType}&scopeId=${scopeId}`)
}
