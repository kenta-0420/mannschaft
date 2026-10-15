import type { Page, Route } from '@playwright/test'

/**
 * F02.5 行動メモ E2E 共通モックヘルパー（Phase 3 対応）。
 *
 * <p>方針:</p>
 * <ul>
 *   <li>API モック方式（page.route で `**\/api/v1/...` をモック）</li>
 *   <li>surveys/_helpers.ts のパターンを踏襲（layout 周辺 API 含む）</li>
 *   <li>fixture は関数で生成（overrides で一部上書き可能）</li>
 *   <li>後着優先: 本ヘルパー呼び出し後に spec 個別の page.route で振る舞いを上書き可能</li>
 * </ul>
 *
 * <p>Phase 3 で追加されたフィールド ({@code category} / {@code duration_minutes} /
 * {@code progress_rate} / {@code completes_todo} / {@code posted_team_id}) と
 * Phase 3 の追加 API ({@code POST /publish-to-team} / {@code POST /publish-daily-to-team} /
 * {@code GET /available-teams}) を本ヘルパーで一括ハンドルする。</p>
 *
 * <p>{@code todos/my} は {@code completes_todo} 反映に対応し、メモ保存後にチェックされた
 * 場合は対応する TODO ステータスを {@code COMPLETED} に切り替える（spec 側で
 * {@link MockState.completedTodoIds} を参照可能）。</p>
 */

// ---------------------------------------------------------------------------
// 認証セットアップ
// ---------------------------------------------------------------------------

/** 認証注入オプション。 */
export interface AuthOptions {
  /** ログイン中ユーザーの ID */
  userId: number
  /** 表示名（UI 表示用） */
  displayName: string
  /** ロール（バックエンド AccessRole 準拠） */
  role: 'SYSTEM_ADMIN' | 'ADMIN' | 'MEMBER' | 'GUEST'
}

/**
 * ログイン済み状態をシミュレートする（surveys/_helpers.ts と同じ流儀）。
 * accessToken / refreshToken / currentUser を localStorage に注入する。
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
  }, opts)
}

// ---------------------------------------------------------------------------
// MockState / fixture
// ---------------------------------------------------------------------------

/** Backend RawMemo に準拠（snake_case）。Phase 3 フィールド込み。 */
export interface MockMemo {
  id: number
  memo_date: string
  content: string
  mood: string | null
  related_todo_id: number | null
  timeline_post_id: number | null
  tags: Array<{
    id: number
    name: string
    color: string | null
    sort_order: number
    deleted: boolean
  }>
  created_at: string
  updated_at: string
  // Phase 3
  category: 'WORK' | 'PRIVATE' | 'OTHER'
  duration_minutes: number | null
  progress_rate: number | null
  completes_todo: boolean
  posted_team_id: number | null
}

/** Backend RawSettings に準拠（Phase 3 + Phase 4-β 込み）。 */
export interface MockSettings {
  mood_enabled: boolean
  default_post_team_id: number | null
  default_category: 'WORK' | 'PRIVATE' | 'OTHER'
  // Phase 4-β
  reminder_enabled: boolean
  reminder_time: string | null
}

/** Backend RawAvailableTeam に準拠。 */
export interface MockAvailableTeam {
  id: number
  name: string
  is_default: boolean
}

/** Backend timeline_posts の最小サブセット（Phase 3 二重出力検証用）。 */
export interface MockTimelinePost {
  id: number
  scope_type: 'PERSONAL' | 'TEAM'
  scope_id: number
  source_kind: 'ACTION_MEMO_DAILY' | 'ACTION_MEMO_TEAM'
  memo_ids: number[]
  posted_at: string
}

/** TODO の最小サブセット。 */
export interface MockTodo {
  id: number
  scopeType: string
  scopeId: number
  title: string
  status: string
  dueDate: string | null
}

/** モック全体の状態。 */
export interface MockState {
  memos: MockMemo[]
  nextMemoId: number
  settings: MockSettings
  availableTeams: MockAvailableTeam[]
  todos: MockTodo[]
  /** publish-daily-to-team / publish-to-team で追加された timeline_posts。spec で件数や中身を検証する */
  timeline_posts: MockTimelinePost[]
  nextTimelinePostId: number
  /** completes_todo=true で完了状態に変更された TODO の id 集合 */
  completedTodoIds: Set<number>
  // Phase 4-β: 管理職ダッシュボード
  dashboardMemos: MockMemo[]
  dashboardNextCursor: string | null
  /** Phase 5-2: 組織スコープ投稿先候補 */
  availableOrgs: Array<{ id: number; name: string }>
  /** Phase 5-1: メモIDごとの監査ログ */
  auditLogs: Record<number, Array<{ id: number; eventType: string; actorId: number; createdAt: string; metadata: string | null }>>
  /** Phase 7: チームメンバー（teamId → pages）大規模チーム対応 */
  teamMembersPages: Record<number, Array<{ userId: number; displayName: string }[]>>
}

/** {@link buildMockState} のオプション。一部フィールドだけ上書きできる。 */
export type BuildMockStateOptions = Partial<{
  memos: MockMemo[]
  settings: Partial<MockSettings>
  availableTeams: MockAvailableTeam[]
  todos: MockTodo[]
  availableOrgs: Array<{ id: number; name: string }>
  /** Phase 7: チームメンバー（teamId → pages）大規模チーム対応 */
  teamMembersPages: Record<number, Array<{ userId: number; displayName: string }[]>>
}>

/** デフォルト値で {@link MockState} を構築する。 */
export function buildMockState(opts: BuildMockStateOptions = {}): MockState {
  return {
    memos: opts.memos ?? [],
    nextMemoId: 5000,
    settings: {
      mood_enabled: false,
      default_post_team_id: null,
      default_category: 'OTHER',
      reminder_enabled: false,
      reminder_time: null,
      ...(opts.settings ?? {}),
    },
    availableTeams: opts.availableTeams ?? [],
    todos: opts.todos ?? [],
    timeline_posts: [],
    nextTimelinePostId: 90000,
    completedTodoIds: new Set<number>(),
    // Phase 4-β
    dashboardMemos: [],
    dashboardNextCursor: null,
    // Phase 5-2
    availableOrgs: opts.availableOrgs ?? [],
    auditLogs: {},
    // Phase 7
    teamMembersPages: opts.teamMembersPages ?? {},
  }
}

/** JST 今日 (YYYY-MM-DD) */
export function todayJst(): string {
  const now = new Date()
  const jst = new Date(now.getTime() + 9 * 60 * 60 * 1000)
  return jst.toISOString().slice(0, 10)
}

function nowIso(): string {
  return new Date().toISOString().replace('Z', '').slice(0, 19)
}

/** WORK メモを 1 件作る簡易ファクトリ。 */
export function buildWorkMemo(opts: Partial<MockMemo> & { id: number; content: string }): MockMemo {
  return {
    id: opts.id,
    memo_date: opts.memo_date ?? todayJst(),
    content: opts.content,
    mood: opts.mood ?? null,
    related_todo_id: opts.related_todo_id ?? null,
    timeline_post_id: opts.timeline_post_id ?? null,
    tags: opts.tags ?? [],
    created_at: opts.created_at ?? nowIso(),
    updated_at: opts.updated_at ?? nowIso(),
    category: opts.category ?? 'WORK',
    duration_minutes: opts.duration_minutes ?? null,
    progress_rate: opts.progress_rate ?? null,
    completes_todo: opts.completes_todo ?? false,
    posted_team_id: opts.posted_team_id ?? null,
  }
}

// ---------------------------------------------------------------------------
// API モック登録
// ---------------------------------------------------------------------------

/**
 * F02.5 Phase 3 関連 API を一括モックする。
 *
 * <p>登録順序の都合で、より具体的な path から先に登録する（後着優先のため、後から
 * 登録した方が拾うため）。{@code /publish-daily-to-team} 等は親 path
 * {@code /api/v1/action-memos**} より具体性が高いので先に登録する。</p>
 *
 * <p>spec 側で本関数の呼び出し後に {@code page.route} を追加して特定エンドポイントだけ
 * 振る舞いを上書きすることも可能（後着優先）。</p>
 */
export async function mockActionMemoApi(page: Page, state: MockState): Promise<void> {
  // ----- CORS プリフライト（OPTIONS）を一括処理 -----
  // クロスオリジンリクエスト（Authorization ヘッダーを含む GET/POST/PATCH 等）に対して
  // ブラウザが送る OPTIONS プリフライトをモックで処理する。
  // バックエンドが起動していない環境でも全テストが動作するようにするため、
  // **/api/v1/** に一致するすべての OPTIONS をここで消化する。
  await page.route('**/api/v1/**', async (route) => {
    if (route.request().method() === 'OPTIONS') {
      await route.fulfill({
        status: 204,
        headers: {
          'Access-Control-Allow-Origin': '*',
          'Access-Control-Allow-Methods': 'GET, POST, PUT, PATCH, DELETE, OPTIONS',
          'Access-Control-Allow-Headers': 'Content-Type, Authorization',
          'Access-Control-Max-Age': '86400',
        },
      })
      return
    }
    await route.fallback()
  })

  // ----- layout 共通の周辺 API（NotificationBell / mentions / chat / refresh）-----
  await page.route('**/api/v1/notifications/unread-count', async (route) => {
    if (route.request().method() !== 'GET') {
      await route.continue()
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: { total: 0 } }),
    })
  })
  await page.route('**/api/v1/chat/channels**', async (route) => {
    if (route.request().method() !== 'GET') {
      await route.continue()
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: [] }),
    })
  })
  await page.route('**/api/v1/mentions', async (route) => {
    if (route.request().method() !== 'GET') {
      await route.continue()
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: [] }),
    })
  })
  await page.route('**/api/v1/auth/refresh', async (route) => {
    if (route.request().method() !== 'POST') {
      await route.continue()
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: {
          accessToken: 'eyJhbGciOiJIUzM4NCJ9.e2UyZV90ZXN0X3VzZXJ9.placeholder_for_e2e',
          refreshToken: 'e2e-refresh-token-placeholder',
        },
      }),
    })
  })

  // ----- 設定 API -----
  await page.route('**/api/v1/action-memo-settings', async (route) => {
    const method = route.request().method()
    // OPTIONS プリフライト（クロスオリジン PATCH 前にブラウザが送る）をモックで応答する
    if (method === 'OPTIONS') {
      await route.fulfill({
        status: 204,
        headers: {
          'Access-Control-Allow-Origin': '*',
          'Access-Control-Allow-Methods': 'GET, PATCH, OPTIONS',
          'Access-Control-Allow-Headers': 'Content-Type, Authorization',
        },
      })
      return
    }
    if (method === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: state.settings }),
      })
      return
    }
    if (method === 'PATCH') {
      const body = JSON.parse(route.request().postData() ?? '{}') as {
        mood_enabled?: boolean
        default_post_team_id?: number | null
        default_category?: 'WORK' | 'PRIVATE' | 'OTHER'
        // Phase 4-β
        reminder_enabled?: boolean
        reminder_time?: string | null
      }
      if (typeof body.mood_enabled === 'boolean') {
        state.settings.mood_enabled = body.mood_enabled
      }
      if (Object.prototype.hasOwnProperty.call(body, 'default_post_team_id')) {
        state.settings.default_post_team_id = body.default_post_team_id ?? null
      }
      if (body.default_category !== undefined) {
        state.settings.default_category = body.default_category
      }
      // Phase 4-β
      if (typeof body.reminder_enabled === 'boolean') {
        state.settings.reminder_enabled = body.reminder_enabled
      }
      if (Object.prototype.hasOwnProperty.call(body, 'reminder_time')) {
        state.settings.reminder_time = body.reminder_time ?? null
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: state.settings }),
      })
      return
    }
    await route.continue()
  })

  // ----- タグ API（最小モック）-----
  await page.route('**/api/v1/action-memo-tags**', async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
      return
    }
    await route.continue()
  })

  // ----- TODO API（completes_todo 反映可能）-----
  // NOTE: closing.vue は GET /api/v1/todos/my を直叩き、scopeType=PERSONAL かつ
  //   dueDate=今日 のものだけ表示する。MockState.todos に渡された全件を返す。
  //   completes_todo=true でメモが作成された場合、対応する TODO は status を
  //   COMPLETED に書き換えて返す。
  await page.route('**/api/v1/todos/my', async (route) => {
    if (route.request().method() !== 'GET') {
      await route.continue()
      return
    }
    const list = state.todos.map((t) => {
      if (state.completedTodoIds.has(t.id)) {
        return { ...t, status: 'COMPLETED' }
      }
      return t
    })
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: list }),
    })
  })

  // ----- メモ CRUD（catch-all。具体的な path は後で登録される後着優先のため後勝ちになる）-----
  // GET /api/v1/action-memos?date=...
  // POST /api/v1/action-memos
  // PATCH /api/v1/action-memos/{id}
  // DELETE /api/v1/action-memos/{id}
  // POST /api/v1/action-memos/publish-daily（既存 Phase 2）
  await page.route('**/api/v1/action-memos**', async (route: Route) => {
    const method = route.request().method()
    const url = route.request().url()

    if (method === 'POST' && /\/action-memos\/publish-daily(?!-to-team)/.test(url)) {
      const body = JSON.parse(route.request().postData() ?? '{}') as { memo_date?: string }
      const memoDate = body.memo_date ?? todayJst()
      const list = state.memos.filter((m) => m.memo_date === memoDate)
      if (list.length === 0) {
        await route.fulfill({
          status: 400,
          contentType: 'application/json',
          body: JSON.stringify({ message: 'no memos' }),
        })
        return
      }
      const postId = state.nextTimelinePostId++
      state.memos = state.memos.map((m) =>
        m.memo_date === memoDate ? { ...m, timeline_post_id: postId } : m,
      )
      state.timeline_posts.push({
        id: postId,
        scope_type: 'PERSONAL',
        scope_id: 0,
        source_kind: 'ACTION_MEMO_DAILY',
        memo_ids: list.map((m) => m.id),
        posted_at: nowIso(),
      })
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({
          data: {
            timeline_post_id: postId,
            memo_count: list.length,
            memo_date: memoDate,
          },
        }),
      })
      return
    }

    if (method === 'GET') {
      // Phase 7: date クエリパラメータで絞り込む（ディープリンク対応）
      const dateParam = new URL(route.request().url()).searchParams.get('date')
      const filtered = dateParam
        ? state.memos.filter((m) => m.memo_date === dateParam)
        : state.memos
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: filtered, next_cursor: null }),
      })
      return
    }

    if (method === 'POST' && /\/action-memos$/.test(url.split('?')[0] ?? url)) {
      const body = JSON.parse(route.request().postData() ?? '{}') as {
        content: string
        memo_date?: string
        mood?: string | null
        related_todo_id?: number | null
        category?: 'WORK' | 'PRIVATE' | 'OTHER'
        duration_minutes?: number | null
        progress_rate?: number | null
        completes_todo?: boolean
      }
      const memo: MockMemo = {
        id: state.nextMemoId++,
        memo_date: body.memo_date ?? todayJst(),
        content: body.content,
        mood: state.settings.mood_enabled ? (body.mood ?? null) : null,
        related_todo_id: body.related_todo_id ?? null,
        timeline_post_id: null,
        tags: [],
        created_at: nowIso(),
        updated_at: nowIso(),
        category: body.category ?? state.settings.default_category ?? 'OTHER',
        duration_minutes: body.duration_minutes ?? null,
        progress_rate: body.progress_rate ?? null,
        completes_todo: body.completes_todo ?? false,
        posted_team_id: null,
      }
      state.memos.unshift(memo)
      // completes_todo=true なら関連 TODO を完了状態に切り替える
      if (memo.completes_todo && memo.related_todo_id !== null) {
        state.completedTodoIds.add(memo.related_todo_id)
      }
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({ data: memo }),
      })
      return
    }

    if (method === 'PATCH' && /\/action-memos\/\d+$/.test(url.split('?')[0] ?? url)) {
      const idMatch = url.match(/\/action-memos\/(\d+)/)
      const id = idMatch ? Number(idMatch[1]) : 0
      const idx = state.memos.findIndex((m) => m.id === id)
      if (idx < 0) {
        await route.fulfill({
          status: 404,
          contentType: 'application/json',
          body: JSON.stringify({ message: 'not found' }),
        })
        return
      }
      const body = JSON.parse(route.request().postData() ?? '{}') as Record<string, unknown>
      const target = state.memos[idx]!
      const updated: MockMemo = {
        ...target,
        content: (body.content as string | undefined) ?? target.content,
        category: ((body.category as MockMemo['category']) ?? target.category) as MockMemo['category'],
        duration_minutes:
          body.duration_minutes !== undefined
            ? (body.duration_minutes as number | null)
            : target.duration_minutes,
        progress_rate:
          body.progress_rate !== undefined
            ? (body.progress_rate as number | null)
            : target.progress_rate,
        completes_todo:
          body.completes_todo !== undefined ? Boolean(body.completes_todo) : target.completes_todo,
        updated_at: nowIso(),
      }
      state.memos[idx] = updated
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: updated }),
      })
      return
    }

    if (method === 'DELETE' && /\/action-memos\/\d+$/.test(url.split('?')[0] ?? url)) {
      const idMatch = url.match(/\/action-memos\/(\d+)/)
      const id = idMatch ? Number(idMatch[1]) : 0
      state.memos = state.memos.filter((m) => m.id !== id)
      await route.fulfill({ status: 204, body: '' })
      return
    }

    // それ以外
    await route.fulfill({
      status: 404,
      contentType: 'application/json',
      body: JSON.stringify({ message: 'not found' }),
    })
  })

  // ----- Phase 3: チーム投稿先候補（catch-all 後に登録 = 後勝ち）-----
  await page.route('**/api/v1/action-memos/available-teams', async (route) => {
    if (route.request().method() !== 'GET') {
      await route.continue()
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: state.availableTeams }),
    })
  })

  // ----- Phase 3: publish-daily-to-team -----
  await page.route('**/api/v1/action-memos/publish-daily-to-team', async (route) => {
    if (route.request().method() !== 'POST') {
      await route.continue()
      return
    }
    const body = JSON.parse(route.request().postData() ?? '{}') as { team_id?: number }
    const teamId = body.team_id ?? state.settings.default_post_team_id ?? state.availableTeams[0]?.id
    if (!teamId) {
      await route.fulfill({
        status: 400,
        contentType: 'application/json',
        body: JSON.stringify({ message: 'team_id required' }),
      })
      return
    }
    const today = todayJst()
    const workMemos = state.memos.filter(
      (m) => m.memo_date === today && m.category === 'WORK' && m.posted_team_id === null,
    )
    if (workMemos.length === 0) {
      await route.fulfill({
        status: 400,
        contentType: 'application/json',
        body: JSON.stringify({ message: 'no work memos' }),
      })
      return
    }
    // 各 WORK メモに posted_team_id を埋め、TEAM スコープ timeline_post を 1 件追加
    const memoIds = workMemos.map((m) => m.id)
    const postId = state.nextTimelinePostId++
    state.memos = state.memos.map((m) =>
      memoIds.includes(m.id) ? { ...m, posted_team_id: teamId } : m,
    )
    state.timeline_posts.push({
      id: postId,
      scope_type: 'TEAM',
      scope_id: teamId,
      source_kind: 'ACTION_MEMO_TEAM',
      memo_ids: memoIds,
      posted_at: nowIso(),
    })
    await route.fulfill({
      status: 201,
      contentType: 'application/json',
      body: JSON.stringify({
        data: {
          timeline_post_id: postId,
          team_id: teamId,
          memo_count: memoIds.length,
        },
      }),
    })
  })

  // ----- Phase 5-2: 組織スコープ投稿先候補 -----
  await page.route('**/api/v1/action-memos/available-orgs', async (route) => {
    if (route.request().method() !== 'GET') {
      await route.continue()
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: state.availableOrgs }),
    })
  })

  // ----- Phase 5-1: 監査ログ -----
  await page.route(/.*\/api\/v1\/action-memos\/\d+\/audit-logs$/, async (route) => {
    if (route.request().method() !== 'GET') {
      await route.continue()
      return
    }
    const idMatch = route.request().url().match(/\/action-memos\/(\d+)\/audit-logs/)
    const id = idMatch ? Number(idMatch[1]) : 0
    const logs = state.auditLogs[id] ?? []
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: logs }),
    })
  })

  // ----- Phase 7: チームメンバー一覧（ページング対応）-----
  await page.route(/.*\/api\/v1\/teams\/\d+\/members(\?.*)?$/, async (route) => {
    if (route.request().method() !== 'GET') {
      await route.continue()
      return
    }
    const url = new URL(route.request().url())
    const idMatch = route.request().url().match(/\/teams\/(\d+)\/members/)
    const teamId = idMatch ? Number(idMatch[1]) : 0
    const pageParam = Number(url.searchParams.get('page') ?? '0')
    const pages = state.teamMembersPages[teamId] ?? [[]]
    const pageData = pages[pageParam] ?? []
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: pageData,
        meta: {
          total: pages.flat().length,
          page: pageParam,
          size: 500,
          totalPages: pages.length,
        },
      }),
    })
  })

  // ----- Phase 4-β: 管理職ダッシュボード -----
  await page.route(/.*\/api\/v1\/teams\/\d+\/members\/\d+\/action-memos.*/, async (route) => {
    if (route.request().method() !== 'GET') {
      await route.continue()
      return
    }
    const url = route.request().url()
    const params = new URL(url).searchParams
    const cursor = params.get('cursor')
    // cursor がある場合は dashboardMemos を空で返す（ページ 2 以降のシミュレーション）
    // spec 側で振る舞いを上書きすること
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: cursor ? [] : state.dashboardMemos,
        next_cursor: cursor ? null : state.dashboardNextCursor,
      }),
    })
  })

  // ----- Phase 3: 個別メモ publish-to-team（POST /api/v1/action-memos/{id}/publish-to-team）-----
  await page.route(/.*\/api\/v1\/action-memos\/\d+\/publish-to-team$/, async (route) => {
    if (route.request().method() !== 'POST') {
      await route.continue()
      return
    }
    const url = route.request().url()
    const idMatch = url.match(/\/action-memos\/(\d+)\/publish-to-team/)
    const id = idMatch ? Number(idMatch[1]) : 0
    const body = JSON.parse(route.request().postData() ?? '{}') as {
      team_id?: number
      extra_comment?: string
    }
    const teamId = body.team_id ?? state.settings.default_post_team_id ?? state.availableTeams[0]?.id
    const idx = state.memos.findIndex((m) => m.id === id)
    if (idx < 0) {
      await route.fulfill({
        status: 404,
        contentType: 'application/json',
        body: JSON.stringify({ message: 'memo not found' }),
      })
      return
    }
    const target = state.memos[idx]!
    if (target.category !== 'WORK') {
      await route.fulfill({
        status: 400,
        contentType: 'application/json',
        body: JSON.stringify({ message: 'only WORK category can be posted' }),
      })
      return
    }
    if (target.posted_team_id !== null) {
      await route.fulfill({
        status: 400,
        contentType: 'application/json',
        body: JSON.stringify({ message: 'already posted' }),
      })
      return
    }
    if (!teamId) {
      await route.fulfill({
        status: 400,
        contentType: 'application/json',
        body: JSON.stringify({ message: 'team_id required' }),
      })
      return
    }
    state.memos[idx] = { ...target, posted_team_id: teamId }
    const postId = state.nextTimelinePostId++
    state.timeline_posts.push({
      id: postId,
      scope_type: 'TEAM',
      scope_id: teamId,
      source_kind: 'ACTION_MEMO_TEAM',
      memo_ids: [id],
      posted_at: nowIso(),
    })
    await route.fulfill({
      status: 201,
      contentType: 'application/json',
      body: JSON.stringify({
        data: {
          timeline_post_id: postId,
          team_id: teamId,
        },
      }),
    })
  })
}

// ---------------------------------------------------------------------------
// 待機ヘルパー
// ---------------------------------------------------------------------------

/**
 * Nuxt SSR ページで Vue のクライアントサイドハイドレーション完了を待つ。
 * helpers/wait.ts と同等。本ファイル単独で完結させるため再エクスポートする。
 */
export async function waitForHydration(page: Page): Promise<void> {
  await page.waitForFunction(() => {
    const el = document.querySelector('#__nuxt')
    return el !== null && '__vue_app__' in el
  })
}

