/**
 * 非 ja ロケールの日本語残存 — 実機 E2E（CMP-261004-1944）
 *
 * 翻訳と番人（locale-no-japanese-residue.spec.ts）は main に入っている。
 * 本 spec は「画面上で訳が実際に出ているか」をモックなしで確かめる。
 *
 * 対象 AC（言語: de/en/es/ko/zh、ja は対照）
 *   AC-11a: チームの非メンバーが公開ページで見る「参加申請」ボタンが、その言語で表示され日本語が出ない
 *   AC-11b: ADMIN / DEPUTY_ADMIN は「参加申請」タブ・見出し・空状態が訳で出る。
 *           MEMBER / 非メンバーにはタブが見えず、URL 直打ちでも審査パネルが出ない（ロール横断）
 *   AC-12:  billing / shift / receipt 系ページの本文に日本語が残らず、代表キーの訳文が出る
 *
 * ロケールの決め方: nuxt.config.ts の i18n は strategy: no_prefix・cookieKey: 'i18n_locale'。
 *   言語ごとに BrowserContext を作り直し、cookie `i18n_locale` と Accept-Language（context の locale）を入れる。
 *   表示言語が切り替わったことは <html lang> と、訳文そのものが画面に出ていることで確かめる。
 *
 * ログインの回数: AuthService.login() にはメール＋IP 単位で 10回/分 のレート制限（AUTH_044）がある。
 *   ケースごとに再ログインすると同じ ADMIN が1回の実行で数十回ログインし、偽の失敗になりうる。
 *   そこで beforeAll でロール（ADMIN / DEPUTY_ADMIN / MEMBER / OUTSIDER）ごとに1回だけ loginViaApi し、
 *   認証済みの storageState を保持する。各ケースは新しい context にその storageState を流し込む
 *   （ブラウザ側の隔離はケース単位、ログインはロール単位で1回）。ケース終了時に context の storageState
 *   を書き戻すため、アクセストークン失効後に FE が refresh でトークンを回転させても、次のケースは
 *   最新の refresh token を使う（古い refresh token の再利用検知に掛からない）。
 *   前提データの API 呼び出しも同じログインで得た access_token を使い、追加のログインはしない。
 *
 * 日本語残存の判定: ページ全体の innerText から判定する。ただし次の「ユーザーが入力・サーバーが保持するデータ」
 *   は翻訳の対象外なので除外する（除外対象は EXCLUDE_PATTERNS / 実行時のログイン者氏名に限る）。
 *   - ログイン者の氏名（users/me の姓名。ヘッダのアバター横などに出る）
 *   - BE が返すマスタ・ユーザーデータ（プラン名・ユーザーが作ったチーム名など）— 発見した場合は
 *     除外せず、行単位で報告して原因を切り分ける（黙って除外しない）。
 *
 * 前提データ: 実行ごとに PUBLIC チームを API で1つ作り（e2e-user=ADMIN）、招待トークンで
 *   e2e-dummy-10 を DEPUTY_ADMIN、e2e-dummy-6 を MEMBER にする。e2e-outsider は非メンバー。
 *   共有開発 DB への DML は直接打たない。作ったチームは残置してよい。
 *
 * 実行:
 *   BASE_URL=http://localhost:3003 API_BASE_URL=http://localhost:8080 \
 *     npx playwright test tests/e2e/real/locale-residue-1944.spec.ts \
 *     --config playwright-real.config.ts --project chromium-real --reporter=list
 */
import fs from 'node:fs'
import path from 'node:path'
import {
  test,
  expect,
  request as pwRequest,
  type APIRequestContext,
  type Browser,
  type BrowserContext,
  type Page,
} from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

test.use({ storageState: { cookies: [], origins: [] } })
test.setTimeout(180_000)

const API = process.env.API_BASE_URL ?? 'http://localhost:8080'
const PASSWORD = 'TestPass2026!'
const ACCOUNTS = {
  ADMIN: 'e2e-user@test.mannschaft.local',
  DEPUTY_ADMIN: 'e2e-dummy-10@test.mannschaft.local',
  MEMBER: 'e2e-dummy-6@test.mannschaft.local',
  OUTSIDER: 'e2e-outsider@test.mannschaft.local',
} as const
type Role = keyof typeof ACCOUNTS

const LANGS = ['de', 'en', 'es', 'ko', 'zh'] as const
const ALL_LANGS = [...LANGS, 'ja'] as const
type Lang = (typeof ALL_LANGS)[number]

const BROWSER_LOCALE: Record<Lang, string> = {
  ja: 'ja-JP',
  en: 'en-US',
  de: 'de-DE',
  es: 'es-ES',
  ko: 'ko-KR',
  zh: 'zh-CN',
}

const SHOT_DIR = path.resolve(process.cwd(), 'test-results', 'jikki-1944')

// 仮名・漢字（共通）。zh は仮名のみを日本語残存とみなす（漢字は中国語でも正当）。
const RESIDUE_RE = /[぀-ヺー-ヿ一-鿿]/
const KANA_RE = /[぀-ヿ]/
/**
 * 除外するのは「ユーザーが入力したデータ」だけ（翻訳の対象外）。
 * - E2Eテスト用…: 検証用シードの組織名（/shift のスコープ選択肢に出る組織名。ユーザー入力データ）
 * - 住民1削除探索_<数字> / 探索テスト希望: 過去の E2E が /my/shifts に作った希望シフトの名称・メモ（ユーザー入力データ）
 */
const EXCLUDE_PATTERNS: RegExp[] = [
  /E2Eテスト用[^\s]*/g,
  // /my/shifts の希望シフト・住民探索で過去の E2E が作った入力データ（ユーザー入力の名称・メモ）
  /住民1削除探索_\d+/g,
  /探索テスト希望/g,
]

const residueRe = (lang: Lang) => (lang === 'zh' ? KANA_RE : RESIDUE_RE)

// ── ロケールファイル（訳文の期待値） ─────────────────────────────────────
const LOCALE_ROOT = path.resolve(process.cwd(), 'app', 'locales')
const bundleCache = new Map<string, Record<string, unknown>>()

function loadBundle(lang: string): Record<string, unknown> {
  const cached = bundleCache.get(lang)
  if (cached) return cached
  const dir = path.join(LOCALE_ROOT, lang)
  const merged: Record<string, unknown> = {}
  for (const f of fs.readdirSync(dir)) {
    if (!f.endsWith('.json')) continue
    const json = JSON.parse(fs.readFileSync(path.join(dir, f), 'utf8').replace(/^\uFEFF/, ''))
    for (const [k, v] of Object.entries(json)) merged[k] = v
  }
  bundleCache.set(lang, merged)
  return merged
}

/** ドット区切りキーの訳文を返す。無ければ例外（キーの取り違えを握りつぶさない）。 */
function tr(lang: string, key: string): string {
  let cur: unknown = loadBundle(lang)
  for (const seg of key.split('.')) {
    cur = (cur as Record<string, unknown> | undefined)?.[seg]
  }
  if (typeof cur !== 'string') throw new Error(`locale key not found: ${lang}:${key}`)
  return cur
}

// ── 前提データ ───────────────────────────────────────────────────────────
let api: APIRequestContext
let teamSlug = ''
const fullNames: Partial<Record<Role, string>> = {}

type AuthState = Awaited<ReturnType<BrowserContext['storageState']>>
/** ロールごとの認証済み storageState（beforeAll で1回だけログインして作る）。 */
const authStates = new Map<Role, AuthState>()
/** 1回の実行で行ったログインの回数（報告用。ロール数=4 を超えないことを確かめる）。 */
let loginCount = 0

/** ロールごとに1回だけ loginViaApi し、storageState と access_token（前提データ作成用）を得る。 */
async function loginOnce(browser: Browser, role: Role): Promise<string> {
  const ctx = await browser.newContext({ storageState: { cookies: [], origins: [] } })
  try {
    const page = await ctx.newPage()
    await loginViaApi(page, { email: ACCOUNTS[role], password: PASSWORD }, { apiBaseUrl: API })
    loginCount++
    authStates.set(role, await ctx.storageState())
    const token = (await ctx.cookies()).find((c) => c.name === 'access_token')?.value
    if (!token) throw new Error(`${role} の access_token Cookie が無い`)
    return token
  } finally {
    await ctx.close()
  }
}
const bearer = (t: string) => ({ Authorization: `Bearer ${t}`, 'Content-Type': 'application/json' })

test.beforeAll(async ({ browser }) => {
  api = await pwRequest.newContext({ baseURL: API })
  const adminTok = await loginOnce(browser, 'ADMIN')
  const deputyTok = await loginOnce(browser, 'DEPUTY_ADMIN')
  const memberTok = await loginOnce(browser, 'MEMBER')
  const outsiderTok = await loginOnce(browser, 'OUTSIDER')

  for (const [role, tok] of [
    ['ADMIN', adminTok],
    ['DEPUTY_ADMIN', deputyTok],
    ['MEMBER', memberTok],
    ['OUTSIDER', outsiderTok],
  ] as const) {
    const me = await api.get('/api/v1/users/me', { headers: bearer(tok) })
    const d = (await me.json()).data as { lastName: string; firstName: string }
    fullNames[role] = `${d.lastName} ${d.firstName}`
  }

  const stamp = new Date().toISOString().replace(/\D/g, '').slice(4, 14)
  teamSlug = `jikki1944-${stamp}`
  const created = await api.post('/api/v1/teams', {
    headers: bearer(adminTok),
    data: { name: `Jikki1944 ${stamp}`, slug: teamSlug, visibility: 'PUBLIC', template: 'SPORTS' },
  })
  expect(created.status(), 'チーム作成').toBeLessThan(300)

  const inv = await api.post(`/api/v1/teams/${teamSlug}/invite-tokens`, {
    headers: bearer(adminTok),
    data: { roleId: 4, maxUses: 2 },
  })
  expect(inv.status(), '招待トークン作成').toBeLessThan(300)
  const token = ((await inv.json()).data as { token: string }).token
  for (const tok of [deputyTok, memberTok]) {
    const j = await api.post(`/api/v1/invite/${token}/join`, { headers: bearer(tok) })
    expect(j.status(), '招待参加').toBe(200)
  }
  const deputyId = (
    (await (await api.get('/api/v1/users/me', { headers: bearer(deputyTok) })).json()).data as {
      id: number
    }
  ).id
  const role = await api.patch(`/api/v1/teams/${teamSlug}/members/${deputyId}/role`, {
    headers: bearer(adminTok),
    data: { roleId: 3 },
  })
  expect(role.status(), 'DEPUTY_ADMIN へ昇格').toBe(200)
  console.log(`[locale-residue-1944] team=${teamSlug}`)
})

test.afterAll(async () => {
  console.log(`[locale-residue-1944] loginCount=${loginCount}`)
  expect(loginCount, 'ログインはロールごとに1回だけ').toBeLessThanOrEqual(Object.keys(ACCOUNTS).length)
  await api?.dispose()
})

// ── 画面ヘルパー ─────────────────────────────────────────────────────────
/**
 * 言語ごとに新しい context を作り、ロールの認証済み storageState を流し込む（ここではログインしない）。
 * ロケールは cookie `i18n_locale` と context の locale で決める。
 */
async function openAs(browser: Browser, lang: Lang, role: Role): Promise<{ ctx: BrowserContext; page: Page }> {
  const state = authStates.get(role)
  if (!state) throw new Error(`${role} の storageState が無い（beforeAll のログインが失敗している）`)
  const ctx = await browser.newContext({ locale: BROWSER_LOCALE[lang], storageState: state })
  await ctx.addCookies([{ name: 'i18n_locale', value: lang, domain: 'localhost', path: '/' }])
  const page = await ctx.newPage()
  return { ctx, page }
}

/** context を閉じる前に storageState を書き戻す（FE の refresh で回転したトークンを次のケースへ渡す）。 */
async function closeAs(ctx: BrowserContext, role: Role): Promise<void> {
  try {
    const state = await ctx.storageState()
    // i18n_locale はケースごとに入れ直すため、保持する状態からは外す
    authStates.set(role, { ...state, cookies: state.cookies.filter((c) => c.name !== 'i18n_locale') })
  } finally {
    await ctx.close()
  }
}

async function gotoSettled(page: Page, url: string): Promise<void> {
  await page.goto(url, { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  await waitSpinnersGone(page)
}

/** .pi-spin と .p-progressspinner の両方が消えるまで待つ（全画面ローディングの偽 FAIL 防止）。 */
async function waitSpinnersGone(page: Page): Promise<void> {
  await expect(page.locator('.pi-spin, .p-progressspinner')).toHaveCount(0, { timeout: 45_000 })
}

/**
 * 新規チームの初回表示で ADMIN/DEPUTY に出る「初期メンバー権限」ダイアログ（scopeGuide.setup）を閉じる。
 * 出るかどうかはロール次第の条件付き UI のため、現れた場合だけ「あとで決める」を押す
 * （前面に残るとタブが inert になり、後続の操作が偽 FAIL になる）。ボタン文言は訳文で探す。
 */
async function dismissInitialSetup(page: Page, lang: Lang): Promise<void> {
  const later = page.getByRole('button', { name: tr(lang, 'scopeGuide.setup.later'), exact: true })
  const shown = await later
    .waitFor({ state: 'visible', timeout: 10_000 })
    .then(() => true)
    .catch(() => false) // 非表示のままなのは正常系（このロールには出ない）
  if (shown) {
    await later.click()
    await expect(later).toHaveCount(0)
  }
}

/** 永続シェルのタブ（Tab は role=tab のため getByRole('link') では取れない。ラベルは訳文で完全一致）。 */
function joinRequestsTab(page: Page, lang: Lang) {
  const label = tr(lang, 'teamShell.tab.joinRequests').replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return page.locator('.scope-shell__tab').filter({ hasText: new RegExp(`^\\s*${label}\\s*$`) })
}

async function assertLangApplied(page: Page, lang: Lang): Promise<void> {
  await expect(page.locator('html')).toHaveAttribute('lang', lang === 'zh' ? /^zh/ : lang, {
    timeout: 20_000,
  })
}

async function shot(page: Page, name: string): Promise<void> {
  fs.mkdirSync(SHOT_DIR, { recursive: true })
  await page.screenshot({ path: path.join(SHOT_DIR, `${name}.png`), fullPage: true })
}

/**
 * ページ本文から日本語残存の行を返す。
 * 除外: ログイン者の氏名（ユーザー入力データ）。それ以外は除外せず行ごと報告する。
 */
async function residueLines(page: Page, lang: Lang, role: Role): Promise<string[]> {
  const text = await page.evaluate(() => document.body.innerText)
  const name = fullNames[role]
  const re = residueRe(lang)
  return text
    .split('\n')
    .map((l) => (name ? l.split(name).join('') : l))
    .map((l) => EXCLUDE_PATTERNS.reduce((acc, ex) => acc.replace(ex, ''), l))
    .filter((l) => re.test(l))
}

// ── AC-11a ───────────────────────────────────────────────────────────────
for (const lang of ALL_LANGS) {
  test(`AC-11a [${lang}] 非メンバーの公開チームページで参加申請ボタンが訳で出る`, async ({ browser }) => {
    const { ctx, page } = await openAs(browser, lang, 'OUTSIDER')
    try {
      await gotoSettled(page, `/teams/${teamSlug}`)
      await assertLangApplied(page, lang)
      const btn = page.getByTestId('join-request-apply-button')
      await expect(btn).toBeVisible({ timeout: 30_000 })
      await expect(btn).toBeEnabled({ timeout: 30_000 })
      await expect(btn).toContainText(tr(lang, 'joinRequest.apply'))
      const label = (await btn.innerText()).trim()
      if (lang !== 'ja') expect(label, `ボタン文言(${lang})に日本語が無い`).not.toMatch(residueRe(lang))
      await shot(page, `${lang}-AC11a-outsider-team-public`)
      if (lang !== 'ja') {
        const lines = await residueLines(page, lang, 'OUTSIDER')
        expect(lines, `ページ本文の日本語残存行(${lang})`).toEqual([])
      }
    } finally {
      await closeAs(ctx, 'OUTSIDER')
    }
  })
}

// ── AC-11b ───────────────────────────────────────────────────────────────
for (const lang of ALL_LANGS) {
  for (const role of ['ADMIN', 'DEPUTY_ADMIN'] as const) {
    test(`AC-11b [${lang}] ${role} は参加申請タブ・見出し・空状態が訳で出る`, async ({ browser }) => {
      const { ctx, page } = await openAs(browser, lang, role)
      try {
        await gotoSettled(page, `/teams/${teamSlug}`)
        await assertLangApplied(page, lang)
        await dismissInitialSetup(page, lang)
        const tab = joinRequestsTab(page, lang)
        await expect(tab, 'タブ名が訳で見える').toBeVisible({ timeout: 30_000 })
        await tab.click()
        await expect(page).toHaveURL(new RegExp(`/teams/${teamSlug}/join-requests`))
        await waitSpinnersGone(page)
        await expect(page.getByRole('heading', { name: tr(lang, 'joinRequest.admin.pendingTitle') })).toBeVisible({
          timeout: 30_000,
        })
        await expect(page.getByText(tr(lang, 'joinRequest.admin.empty'), { exact: true })).toBeVisible({
          timeout: 30_000,
        })
        await shot(page, `${lang}-AC11b-${role}-join-requests`)
        if (lang !== 'ja') {
          const lines = await residueLines(page, lang, role)
          expect(lines, `参加申請管理画面の日本語残存行(${lang}/${role})`).toEqual([])
        }
      } finally {
        await closeAs(ctx, role)
      }
    })
  }

  for (const role of ['MEMBER', 'OUTSIDER'] as const) {
    test(`AC-11b [${lang}] ${role} にはタブが見えず URL 直打ちでも審査パネルが出ない`, async ({ browser }) => {
      const { ctx, page } = await openAs(browser, lang, role)
      try {
        await gotoSettled(page, `/teams/${teamSlug}`)
        await assertLangApplied(page, lang)
        // 肯定的な終端表示（権限ロード完了）を待ってから否定を確認する
        if (role === 'OUTSIDER') {
          await expect(page.getByTestId('join-request-apply-button')).toBeEnabled({ timeout: 30_000 })
        } else {
          await expect(page.locator('.p-tag').first()).toBeVisible({ timeout: 30_000 })
        }
        await expect(joinRequestsTab(page, lang)).toHaveCount(0)
        // 比較対象が空振りでないこと: 同じシェルの別タブ（ダッシュボード）は見えている
        await expect(page.locator('.scope-shell__tab').first()).toBeVisible()
        await shot(page, `${lang}-AC11b-${role}-no-tab`)

        await gotoSettled(page, `/teams/${teamSlug}/join-requests`)
        await page.waitForTimeout(2_000)
        await expect(page.getByText(tr(lang, 'joinRequest.admin.pendingTitle'))).toHaveCount(0)
        await expect(page.getByText(tr(lang, 'joinRequest.admin.empty'))).toHaveCount(0)
        await shot(page, `${lang}-AC11b-${role}-direct-url`)
      } finally {
        await closeAs(ctx, role)
      }
    })
  }
}

// ── AC-12 ────────────────────────────────────────────────────────────────
// billing / shift / receipt の画面。ADMIN(e2e-user) のセッションで開く。
//
// 偽 green 対策: 文字種の検査は「データ表示後の終端状態」で行う。固定の待ち時間は使わない。
//   - 初期値 loading=false の画面（/shift・/my/shifts・receipt）は、SSR とマウント直後に空状態や
//     スピナー0件が先に出るため、スピナー0件・見出しだけでは終端の証拠にならない。
//     画面が onMounted で叩く API の応答完了（waitForResponse・goto 前に登録）を待ち、
//     そのうえで終端の DOM（一覧の行または空状態）が可視であることをアサーションで固定する。
//   - /shift は onMounted が先に teamStore.fetchMyTeams() を待ち、選択チームが決まってから
//     一覧 API（/api/v1/shifts/schedules?teamId=）を呼ぶ。この一覧 API の応答完了は
//     「所属チームの取得が終わり、チームが選ばれた」ことを含む。
type TerminalCheck = (page: Page, lang: Lang) => Promise<void>

/** /shift の終端: チーム選択肢があり選択済み、ローディング無し、一覧の行または空状態が可視。 */
const shiftIndexTerminal: TerminalCheck = async (page, lang) => {
  const teamSelect = page.locator('.p-select').first()
  const label = teamSelect.locator('.p-select-label')
  await expect(label, 'チームが選択済み（プレースホルダではない）').not.toHaveClass(/p-placeholder/)
  await expect(label).not.toHaveText(tr(lang, 'shift.index.selectTeam'))
  await expect(label).toHaveText(/\S/)
  await teamSelect.click()
  await expect(page.locator('.p-select-overlay .p-select-option').first(), 'チーム選択肢が出ている').toBeVisible()
  await page.keyboard.press('Escape')
  await expect(page.locator('.p-select-overlay')).toHaveCount(0)
  await expect(page.locator('.p-progressspinner')).toHaveCount(0)
  await expect(
    page
      .locator('.grid > .cursor-pointer.rounded-xl')
      .or(page.getByText(tr(lang, 'shift.index.noSchedule'), { exact: true }))
      .first(),
    '選択チームの一覧（行または空状態）が可視',
  ).toBeVisible()
}

/** /my/shifts の終端: ローディング無し、希望シフトの行または空状態が可視。 */
const myShiftsTerminal: TerminalCheck = async (page, lang) => {
  await expect(page.locator('.p-progressspinner')).toHaveCount(0)
  await expect(
    page
      .locator('[data-testid^="my-shift-request-"]')
      .or(page.getByText(tr(lang, 'shift.myShifts.empty'), { exact: true }))
      .first(),
    '希望シフトの行または空状態が可視',
  ).toBeVisible()
}

/** 領収書の終端: ローディング無し、空状態文言が可視（OUTSIDER は支払い 0 件）。 */
const receiptTerminal: TerminalCheck = async (page, lang) => {
  await expect(page.locator('.p-progressspinner')).toHaveCount(0)
  await expect(page.getByText(tr(lang, 'payment.receipt.noReceipts'), { exact: true })).toBeVisible()
}

/** /billing/plans の終端: プラン一覧の領域（loading 解除後の v-else）が可視。 */
const billingPlansTerminal: TerminalCheck = async (page) => {
  await expect(page.locator('.p-progressspinner')).toHaveCount(0)
  await expect(page.locator('.fade-in.space-y-6')).toBeVisible()
}

/** /settings/billing の終端: 契約パネル・β特典ともローディング解除済み（初期値 loading=true のため真の終端）。 */
const billingSettingsTerminal: TerminalCheck = async (page) => {
  await expect(page.locator('.p-progressspinner')).toHaveCount(0)
}

const AC12_PAGES: ReadonlyArray<{
  name: string
  url: string
  key: string
  role?: Role
  /** goto 前に登録し、応答完了を待つ API（画面が onMounted で叩くもの） */
  apis: RegExp[]
  terminal: TerminalCheck
}> = [
  {
    name: 'billing-plans',
    url: '/billing/plans',
    key: 'billing.plans.title',
    apis: [/\/api\/v1\/billing\/plans(\?|$)/, /\/api\/v1\/me\/entitlements(\?|$)/],
    terminal: billingPlansTerminal,
  },
  {
    name: 'billing-settings',
    url: '/settings/billing',
    key: 'billing.manage.personalTitle',
    apis: [/\/api\/v1\/me\/entitlements(\?|$)/],
    terminal: billingSettingsTerminal,
  },
  {
    name: 'shift-index',
    url: '/shift',
    key: 'shift.index.title',
    apis: [/\/api\/v1\/shifts\/schedules\?teamId=/],
    terminal: shiftIndexTerminal,
  },
  {
    name: 'shift-my',
    url: '/my/shifts',
    key: 'shift.myShifts.title',
    apis: [/\/api\/v1\/shifts\/my\/requests(\?|$)/],
    terminal: myShiftsTerminal,
  },
  // 領収書は支払い済みの行を持つアカウント(e2e-user)だと FE がクラッシュする（既知の別欠陥・報告書参照:
  // GET /api/v1/me/payments が paymentItem/scope を返さず receipts.vue が描画で落ちる）。
  // 翻訳の検証は「支払い 0 件」の OUTSIDER で、見出し + 空状態文言(noReceipts)を見る。
  {
    name: 'receipt',
    url: '/me/payments/receipts',
    key: 'payment.receipt.title',
    role: 'OUTSIDER',
    apis: [/\/api\/v1\/me\/payments(\?|$)/],
    terminal: receiptTerminal,
  },
]

for (const lang of ALL_LANGS) {
  for (const pg of AC12_PAGES) {
    test(`AC-12 [${lang}] ${pg.name} (${pg.url}) 本文に日本語が残らず代表キーの訳が出る`, async ({ browser }) => {
      const role: Role = pg.role ?? 'ADMIN'
      const { ctx, page } = await openAs(browser, lang, role)
      try {
        // goto 前に登録する（応答を取り逃がさない）。待ちの時計は goto の前から進むため、
        // goto・hydration が遅い回でも切れないよう、テスト全体の上限（180s）近くまで取る。
        const responses = pg.apis.map((re) =>
          page.waitForResponse((r) => r.request().method() === 'GET' && re.test(r.url()), { timeout: 150_000 }),
        )
        await gotoSettled(page, pg.url)
        for (const [i, res] of (await Promise.all(responses)).entries()) {
          expect(res.ok(), `${pg.apis[i]} の応答 ${res.status()}`).toBe(true)
        }
        await assertLangApplied(page, lang)
        await expect(page.locator('body')).toContainText(tr(lang, pg.key), { timeout: 30_000 })
        // データ表示後の終端状態を確かめてから判定する
        await pg.terminal(page, lang)
        await shot(page, `${lang}-AC12-${pg.name}`)
        if (lang !== 'ja') {
          const lines = await residueLines(page, lang, role)
          expect(lines, `${pg.url} の日本語残存行(${lang})`).toEqual([])
        }
      } finally {
        await closeAs(ctx, role)
      }
    })
  }
}
