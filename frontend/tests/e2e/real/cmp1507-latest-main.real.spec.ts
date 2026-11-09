import { test, type BrowserContext, type Browser, type Page } from '@playwright/test'
import { readWorld, type World } from './helpers/cmp1507-global-setup'
import { API, FE, RUN, createActor, createTeam, expect, gotoAndHydrate, joinAsMember, must, openAs, recordAsset, responseOf, type Actor } from './helpers/n1p1-world'

/**
 * 実機E2E: CMP-261105-1507 の修正が main に入ったあとの再確認（cmp1507-regression の続き）。
 *   #4355 マイルストーン: 入力したタイトルで保存される／プロジェクト編集が保存される／de で日本語が出ない
 *   #4358 チームの時間割: ADMIN が学期を追加・削除（確認つき）／その学期で時間割を作成し DRAFT を削除／サイドバーから辿れる
 *         年度 27 は丸めずエラー／開始日>終了日は欄のエラー／MEMBER に導線なし／他テナントに見えない
 *   #4375 チャット: 本人の投稿を編集・削除／開くと未読バッジが 0／部外者の実在 ID と不在 ID が同じ表示・同じ応答
 *         de で日本語が出ない／DM から Zimmer を作った直後に OWNER が他人の投稿を削除できる
 *
 * モックなし。API はログイン・前提データ作成・後始末と、他人の発言・前提チャンネルの用意にだけ使う。
 * 確かめる表示と操作は画面から行う。前提は新規の使い捨てチーム v.t2（ASCII 名）に作る。
 * 実行: playwright-cmp1507.config.ts（検証 BE 8081 / 検証 FE 3017）。
 */

test.setTimeout(300_000)

let w: World
interface V {
  tag: string
  third: Actor
  t2: { slug: string; numericId: number; name: string }
  project: { id: number; title: string }
  channel: { id: number; name: string }
  prepTerm: { id: number; name: string }
}
let v: V

async function shot(page: Page, name: string) {
  await page.screenshot({ path: `test-results/cmp1507/${name}.png`, fullPage: true })
}

async function withPage<T>(browser: Browser, actor: Actor, fn: (page: Page) => Promise<T>) {
  const { context, page, apiOrigins } = await openAs(browser, actor)
  try {
    const result = await fn(page)
    expect([...apiOrigins]).toEqual([new URL(API).origin])
    return result
  } finally {
    await context.close()
  }
}

const KANA_KANJI = /[぀-ヿ㐀-鿿]/

/** 画面全体の文字列から、日本語を含む行を返す。ログイン者の氏名（姓「検証」）と、利用者が付けたチャンネル名（C1507）の行は利用者データなので除く。 */
async function japaneseLines(page: Page): Promise<string[]> {
  const text = await page.locator('body').innerText()
  return text
    .split('\n')
    .map((l) => l.trim())
    .filter((l) => l && KANA_KANJI.test(l) && !l.includes('検証') && !l.includes('C1507'))
}

async function asDe(context: BrowserContext) {
  await context.addCookies([{ name: 'i18n_locale', value: 'de', url: FE }])
}

/** スコープのサイドバー（ドロワー）をハンバーガーで開く。 */
async function openScopeSidebar(page: Page) {
  const toggle = page.getByTestId('scope-sidebar-toggle').first()
  await expect(toggle).toBeVisible({ timeout: 90_000 })
  await toggle.click()
  // 時間割は「スケジュール」カテゴリの中（既定は折りたたみ）。開く
  const link = page.locator(`a[href="/teams/${v.t2.slug}/timetable"]`).first()
  if (!(await link.isVisible().catch(() => false))) {
    await page.locator('.p-drawer').getByText('スケジュール', { exact: true }).first().click()
  }
}

/** アカウント言語を変える（前提操作。表示言語はアカウント言語が cookie に優先するため）。 */
async function setAccountLocale(actor: Actor, locale: string) {
  await must(actor, 'PUT', '/api/v1/users/me', { locale, timezone: 'Asia/Tokyo' })
}

async function confirmAccept(page: Page) {
  await page.locator('.p-confirmdialog-accept-button').click()
}

test.beforeAll(async () => {
  w = readWorld()
  const tag = `v${RUN}`
  const third = await createActor('Third')
  const t2 = await createTeam(w.admin, `Cmp1507 Team ${tag}`)
  await joinAsMember('teams', w.admin, t2.slug, w.member)
  await joinAsMember('teams', w.admin, t2.slug, third)
  // 時間割は OPTIONAL モジュール（既定 OFF）。サイドバーの導線を確かめるため ON にする
  const catalog = await must(w.admin, 'GET', `/api/v1/teams/${t2.slug}/modules/catalog`)
  const mod = (catalog.data.modules as Array<{ moduleId: number; slug: string }>).find((m) => m.slug === 'timetable')
  if (!mod) throw new Error('timetable モジュールがカタログに無い')
  await must(w.admin, 'PATCH', `/api/v1/teams/${t2.slug}/modules/${mod.moduleId}/toggle`, { moduleId: mod.moduleId, enabled: true })
  const project = (await must(w.admin, 'POST', `/api/v1/teams/${t2.slug}/projects`, { title: `Plan ${tag}` })).data
  const name = `Topic ${tag}`
  const created = await must(w.admin, 'POST', '/api/v1/chat/channels', { channelType: 'TEAM_PUBLIC', teamId: t2.numericId, name, memberUserIds: [w.member.id, third.id] })
  const channel = { id: created.data.id as number, name }
  recordAsset(`channel ${channel.id}`, `/api/v1/chat/channels/${channel.id}`, w.admin)
  // MEMBER / 他テナントが見る用の学期（画面では消さない。後始末で消す）
  const prep = (
    await must(w.admin, 'POST', `/api/v1/teams/${t2.slug}/timetable-terms`, { name: `Prep term ${tag}`, startDate: '2035-04-01', endDate: '2035-09-30', academicYear: 2035, sortOrder: 0 })
  ).data
  recordAsset(`term ${prep.id}`, `/api/v1/timetable-terms/${prep.id}`, w.admin)
  v = { tag, third, t2, project: { id: project.id, title: project.title }, channel, prepTerm: { id: prep.id, name: `Prep term ${tag}` } }
  console.log(`[cmp1507-latest] v: tag=${tag} t2=${t2.slug} project=${project.id} channel=${channel.id}`)
})

// ───────────── #4355 マイルストーン ─────────────

test('#4355 マイルストーン: 追加・更新が入力したタイトルで保存され、プロジェクト編集も保存される（ADMIN）', async ({ browser }) => {
  const ms1 = `MS first ${v.tag}`
  const ms2 = `MS renamed ${v.tag}`
  const projNew = `Plan edited ${v.tag}`
  await withPage(browser, w.admin, async (page) => {
    await gotoAndHydrate(page, `/teams/${v.t2.slug}/projects/${v.project.id}`)
    await expect(page.getByRole('heading', { name: v.project.title }).first()).toBeVisible({ timeout: 90_000 })
    // 追加
    await page.locator('h2:text-is("マイルストーン")').locator('xpath=..').getByRole('button', { name: '作成' }).click()
    const dialog = page.getByRole('dialog')
    await expect(dialog.getByText('マイルストーン追加')).toBeVisible()
    await dialog.getByPlaceholder('マイルストーン名').fill(ms1)
    await dialog.locator('input[type=date]').fill('2035-05-01')
    const post = page.waitForRequest((r) => r.method() === 'POST' && /\/milestones$/.test(new URL(r.url()).pathname))
    const postRes = page.waitForResponse((r) => r.request().method() === 'POST' && /\/milestones$/.test(new URL(r.url()).pathname))
    await dialog.getByRole('button', { name: '追加' }).click()
    expect(JSON.parse((await post).postData() ?? '{}').title).toBe(ms1)
    expect((await postRes).status()).toBe(201)
    await expect(page.getByText(ms1, { exact: true })).toBeVisible({ timeout: 30_000 })
    await shot(page, 'v1-milestone-added')
    // 更新
    const card = page.locator('div.rounded-xl').filter({ hasText: ms1 })
    await card.locator('button:has(.pi-pencil)').click()
    const edit = page.getByRole('dialog')
    await expect(edit.getByText('マイルストーン編集')).toBeVisible()
    await edit.locator('input[type=text]').first().fill(ms2)
    const put = page.waitForRequest((r) => ['PUT', 'PATCH'].includes(r.method()) && /\/milestones\/\d+$/.test(new URL(r.url()).pathname))
    const putRes = page.waitForResponse((r) => ['PUT', 'PATCH'].includes(r.request().method()) && /\/milestones\/\d+$/.test(new URL(r.url()).pathname))
    await edit.getByRole('button', { name: '更新' }).click()
    expect(JSON.parse((await put).postData() ?? '{}').title).toBe(ms2)
    expect((await putRes).status()).toBe(200)
    await expect(page.getByText(ms2, { exact: true })).toBeVisible({ timeout: 30_000 })
    await expect(page.getByText(ms1, { exact: true })).toHaveCount(0)
    // 再読込しても残る
    await page.reload()
    await expect(page.getByText(ms2, { exact: true })).toBeVisible({ timeout: 90_000 })
    await shot(page, 'v1-milestone-updated')
    // プロジェクト編集
    await page.getByRole('button', { name: '編集' }).first().click()
    const pd = page.getByRole('dialog')
    await expect(pd.getByText('プロジェクト編集')).toBeVisible()
    await pd.locator('input[type=text]').first().fill(projNew)
    const upd = page.waitForResponse((r) => ['PUT', 'PATCH'].includes(r.request().method()) && new RegExp(`/projects/${v.project.id}$`).test(new URL(r.url()).pathname))
    await pd.getByRole('button', { name: '更新' }).click()
    expect((await upd).status()).toBe(200)
    await expect(page.getByRole('heading', { name: projNew }).first()).toBeVisible({ timeout: 30_000 })
    await page.reload()
    await expect(page.getByRole('heading', { name: projNew }).first()).toBeVisible({ timeout: 90_000 })
    await shot(page, 'v1-project-edited')
    v.project.title = projNew
  })
})

test('#4355 マイルストーン 権限なし／他テナント: MEMBER に作成・編集の導線なし、他組織は URL 直打ちでも中身が出ない', async ({ browser }) => {
  await withPage(browser, w.member, async (page) => {
    await gotoAndHydrate(page, `/teams/${v.t2.slug}/projects/${v.project.id}`)
    await expect(page.getByRole('heading', { name: v.project.title }).first()).toBeVisible({ timeout: 90_000 })
    await expect(page.getByText(`MS renamed ${v.tag}`, { exact: true })).toBeVisible()
    await expect(page.locator('h2:text-is("マイルストーン")').locator('xpath=..').getByRole('button', { name: '作成' })).toHaveCount(0)
    await expect(page.getByRole('button', { name: '編集' })).toHaveCount(0)
    await expect(page.locator('button:has(.pi-pencil)')).toHaveCount(0)
    await shot(page, 'v1-milestone-member')
  })
  await withPage(browser, w.outsider, async (page) => {
    const detail = responseOf(page, new RegExp(`/api/v1/teams/${v.t2.slug}/projects/${v.project.id}$`))
    await gotoAndHydrate(page, `/teams/${v.t2.slug}/projects/${v.project.id}`)
    const status = (await detail).status()
    console.log(`[cmp1507] #4355 他組織 GET project -> ${status}`)
    expect([403, 404]).toContain(status)
    await expect(page.getByText(`MS renamed ${v.tag}`)).toHaveCount(0)
    await expect(page.getByText(v.project.title)).toHaveCount(0)
    await shot(page, 'v1-milestone-outsider')
  })
})

test('#4355 マイルストーン de: プロジェクト一覧・詳細・各ダイアログに日本語が出ない（ADMIN）', async ({ browser }) => {
  await setAccountLocale(w.admin, 'de')
  const { context, page } = await openAs(browser, w.admin)
  try {
    await asDe(context)
    await gotoAndHydrate(page, `/teams/${v.t2.slug}/projects`)
    await expect(page.getByText(v.project.title)).toBeVisible({ timeout: 90_000 })
    expect(await page.locator('html').getAttribute('lang')).toBe('de')
    const list = await japaneseLines(page)
    console.log(`[cmp1507] #4355 de 一覧の日本語行: ${JSON.stringify(list)}`)
    await shot(page, 'v1-de-list')
    await page.getByText(v.project.title).click()
    await expect(page).toHaveURL(new RegExp(`/projects/${v.project.id}`))
    await expect(page.getByText('Meilensteine').first()).toBeVisible({ timeout: 90_000 })
    const detail = await japaneseLines(page)
    console.log(`[cmp1507] #4355 de 詳細の日本語行: ${JSON.stringify(detail)}`)
    await shot(page, 'v1-de-detail')
    await page.locator('h2:text-is("Meilensteine")').locator('xpath=..').getByRole('button').first().click()
    await expect(page.getByRole('dialog').getByText('Meilenstein hinzufügen')).toBeVisible()
    const ms = await japaneseLines(page)
    console.log(`[cmp1507] #4355 de マイルストーンダイアログの日本語行: ${JSON.stringify(ms)}`)
    await shot(page, 'v1-de-milestone-dialog')
    await page.getByRole('dialog').getByRole('button', { name: 'Abbrechen' }).click()
    await page.getByRole('button', { name: 'Bearbeiten' }).first().click()
    await expect(page.getByRole('dialog').getByText('Projekt bearbeiten')).toBeVisible()
    const pe = await japaneseLines(page)
    console.log(`[cmp1507] #4355 de 編集ダイアログの日本語行: ${JSON.stringify(pe)}`)
    await shot(page, 'v1-de-edit-dialog')
    expect([...list, ...detail, ...ms, ...pe], '日本語が残っている').toEqual([])
  } finally {
    await context.close()
    await setAccountLocale(w.admin, 'ja')
  }
})

// ───────────── #4358 チームの時間割 ─────────────

test('#4358 チームの時間割（ADMIN）: サイドバーから辿れ、年度27・開始日>終了日は欄のエラー、学期を追加→時間割作成→DRAFT削除→学期削除（確認つき）', async ({ browser }) => {
  const termName = `Term ${v.tag}`
  const ttName = `Timetable ${v.tag}`
  await withPage(browser, w.admin, async (page) => {
    // サイドバーから辿る（モジュール ON）
    await gotoAndHydrate(page, `/teams/${v.t2.slug}/projects`)
    await openScopeSidebar(page)
    const link = page.locator(`a[href="/teams/${v.t2.slug}/timetable"]`).first()
    await expect(link).toBeVisible({ timeout: 90_000 })
    await expect(link).toContainText('時間割')
    await shot(page, 'v2-sidebar-admin')
    await link.click()
    await expect(page).toHaveURL(new RegExp(`/teams/${v.t2.slug}/timetable`))
    const terms = responseOf(page, new RegExp(`/api/v1/teams/${v.t2.slug}/timetable-terms$`))
    await page.getByTestId('timetable-tab-term').click()
    expect((await terms).status()).toBe(200)
    await expect(page.getByRole('cell', { name: v.prepTerm.name, exact: true })).toBeVisible({ timeout: 30_000 })

    const posted: string[] = []
    page.on('request', (r) => {
      if (r.method() === 'POST' && /\/timetable-terms$/.test(new URL(r.url()).pathname)) posted.push(r.postData() ?? '')
    })
    await page.getByTestId('term-add-button').click()
    const dialog = page.getByRole('dialog')
    await dialog.getByTestId('term-name-input').fill(termName)
    await dialog.getByTestId('term-start-input').fill('2036-04-01')
    await dialog.getByTestId('term-end-input').fill('2036-09-30')
    // 年度 27 は 2000 に丸めず、欄のエラー（送信もしない）
    await dialog.getByTestId('term-year-input').fill('27')
    await dialog.getByTestId('term-dialog-submit').click()
    await expect(dialog.getByTestId('term-academic-year-error')).toBeVisible()
    console.log(`[cmp1507] #4358 年度27の欄エラー: ${await dialog.getByTestId('term-academic-year-error').innerText()}／入力欄の値=${await dialog.getByTestId('term-year-input').inputValue()}／送信=${posted.length}件`)
    expect(await dialog.getByTestId('term-year-input').inputValue()).toBe('27')
    expect(posted).toHaveLength(0)
    await shot(page, 'v2-term-year-27')
    // 開始日 > 終了日 は 500 でなく欄のエラー
    await dialog.getByTestId('term-year-input').fill('2036')
    await dialog.getByTestId('term-start-input').fill('2036-10-01')
    await dialog.getByTestId('term-end-input').fill('2036-04-01')
    await dialog.getByTestId('term-dialog-submit').click()
    await expect(dialog.getByTestId('term-end-date-error')).toBeVisible()
    console.log(`[cmp1507] #4358 開始日>終了日の欄エラー: ${await dialog.getByTestId('term-end-date-error').innerText()}／送信=${posted.length}件`)
    expect(posted).toHaveLength(0)
    await shot(page, 'v2-term-start-after-end')
    // 正しい日付で追加
    await dialog.getByTestId('term-start-input').fill('2036-04-01')
    await dialog.getByTestId('term-end-input').fill('2036-09-30')
    const created = page.waitForResponse((r) => r.request().method() === 'POST' && /\/timetable-terms$/.test(new URL(r.url()).pathname))
    await dialog.getByTestId('term-dialog-submit').click()
    const createdRes = await created
    expect(createdRes.status()).toBe(201)
    const termId = (await createdRes.json()).data.id as number
    // この学期には時間割（のちに論理削除）が付くため、仕様（TIMETABLE_022）上あとから消せない。後始末の対象にせず、残骸として証拠に書く
    expect(JSON.parse(posted[0]!).academicYear).toBe(2036)
    await expect(page.getByRole('cell', { name: termName, exact: true })).toBeVisible({ timeout: 30_000 })
    await shot(page, 'v2-term-created')

    // その学期で時間割を作る（ヘッダの「時間割作成」→ 学期を選ぶ）
    await page.getByTestId('timetable-create-button').click()
    const cd = page.getByRole('dialog')
    await cd.getByPlaceholder('時間割名').fill(ttName)
    await cd.getByRole('combobox').first().click()
    await page.getByRole('option', { name: termName, exact: true }).click()
    await cd.locator('input[type=date]').first().fill('2036-04-01')
    const ttPost = page.waitForResponse((r) => r.request().method() === 'POST' && /\/teams\/[^/]+\/timetables$/.test(new URL(r.url()).pathname))
    await cd.getByRole('button', { name: '作成' }).click()
    const ttRes = await ttPost
    expect(ttRes.status()).toBe(201)
    const ttId = (await ttRes.json()).data.id as number
    await shot(page, 'v2-timetable-created')

    // 一覧タブで DRAFT を削除（確認つき。キャンセルでは消えない）
    await page.getByTestId('timetable-tab-list').click()
    await expect(page.getByRole('row').filter({ hasText: ttName })).toContainText('下書き', { timeout: 30_000 })
    await page.getByTestId(`timetable-delete-${ttId}`).click()
    await expect(page.locator('.p-confirmdialog')).toBeVisible()
    await page.locator('.p-confirmdialog-reject-button').click()
    await expect(page.getByRole('row').filter({ hasText: ttName })).toHaveCount(1)
    await page.getByTestId(`timetable-delete-${ttId}`).click()
    const del = page.waitForResponse((r) => r.request().method() === 'DELETE' && /\/timetables\/\d+$/.test(new URL(r.url()).pathname))
    await confirmAccept(page)
    expect((await del).status()).toBe(204)
    await expect(page.getByRole('row').filter({ hasText: ttName })).toHaveCount(0, { timeout: 30_000 })
    await shot(page, 'v2-timetable-deleted')

    // 学期の削除（確認つき）。この学期の時間割は論理削除済み（#4324 の仕様で 409 になる想定）。観測する
    await page.getByTestId('timetable-tab-term').click()
    await page.getByTestId(`term-delete-${termId}`).click()
    await page.locator('.p-confirmdialog-reject-button').click()
    await expect(page.getByRole('cell', { name: termName, exact: true })).toHaveCount(1)
    await page.getByTestId(`term-delete-${termId}`).click()
    const tdel = page.waitForResponse((r) => r.request().method() === 'DELETE' && /\/timetable-terms\/\d+$/.test(new URL(r.url()).pathname))
    await confirmAccept(page)
    const tdelRes = await tdel
    console.log(`[cmp1507] #4358 時間割を削除した学期の削除 -> ${tdelRes.status()} ${(await tdelRes.text()).slice(0, 200)}`)
    const toast = page.locator('.p-toast-message').last()
    await expect(toast).toBeVisible({ timeout: 15_000 })
    console.log(`[cmp1507] #4358 学期削除トースト: ${(await toast.innerText()).replace(/\s+/g, ' ')}`)
    await shot(page, 'v2-term-delete-after-timetable')
    // 時間割のない学期を作って、確認つきで削除できること（年度は全角で入力→半角に正規化）
    const freeName = `Free ${v.tag}`
    await page.getByTestId('term-add-button').click()
    const d2 = page.getByRole('dialog')
    await d2.getByTestId('term-name-input').fill(freeName)
    await d2.getByTestId('term-start-input').fill('2037-04-01')
    await d2.getByTestId('term-end-input').fill('2037-09-30')
    await d2.getByTestId('term-year-input').fill('２０３７')
    const c2 = page.waitForResponse((r) => r.request().method() === 'POST' && /\/timetable-terms$/.test(new URL(r.url()).pathname))
    await d2.getByTestId('term-dialog-submit').click()
    const c2Res = await c2
    expect(c2Res.status()).toBe(201)
    const freeId = (await c2Res.json()).data.id as number
    recordAsset(`term ${freeId}`, `/api/v1/timetable-terms/${freeId}`, w.admin)
    await expect(page.getByRole('cell', { name: freeName, exact: true })).toBeVisible({ timeout: 30_000 })
    await page.getByTestId(`term-delete-${freeId}`).click()
    const fdel = page.waitForResponse((r) => r.request().method() === 'DELETE' && /\/timetable-terms\/\d+$/.test(new URL(r.url()).pathname))
    await confirmAccept(page)
    expect((await fdel).status()).toBe(204)
    await expect(page.getByRole('cell', { name: freeName, exact: true })).toHaveCount(0, { timeout: 30_000 })
    await shot(page, 'v2-term-deleted')
  })
})

test('#4358 チームの時間割 権限なし（MEMBER）: 学期は読めるが追加・削除・時間割作成の導線なし／サイドバーには出る', async ({ browser }) => {
  await withPage(browser, w.member, async (page) => {
    await gotoAndHydrate(page, `/teams/${v.t2.slug}/projects`)
    await openScopeSidebar(page)
    await expect(page.locator(`a[href="/teams/${v.t2.slug}/timetable"]`).first()).toBeVisible({ timeout: 90_000 })
    const terms = responseOf(page, new RegExp(`/api/v1/teams/${v.t2.slug}/timetable-terms$`))
    await gotoAndHydrate(page, `/teams/${v.t2.slug}/timetable`)
    await page.getByTestId('timetable-tab-term').click()
    expect((await terms).status()).toBe(200)
    await expect(page.getByRole('cell', { name: v.prepTerm.name, exact: true })).toBeVisible({ timeout: 30_000 })
    await expect(page.getByTestId('term-add-button')).toHaveCount(0)
    await expect(page.locator('[data-testid^="term-delete-"]')).toHaveCount(0)
    await expect(page.getByTestId('timetable-create-button')).toHaveCount(0)
    await page.getByTestId('timetable-tab-list').click()
    await expect(page.locator('[data-testid^="timetable-delete-"]')).toHaveCount(0)
    await shot(page, 'v2-member')
  })
})

test('#4358 チームの時間割 他テナント: 学期・時間割が見えない（URL 直打ち）', async ({ browser }) => {
  await withPage(browser, w.outsider, async (page) => {
    const apis: string[] = []
    page.on('response', (r) => {
      const p = new URL(r.url()).pathname
      if (p.startsWith(`/api/v1/teams/${v.t2.slug}/`)) apis.push(`${r.request().method()} ${p.replace(v.t2.slug, '{t2}')} -> ${r.status()}`)
    })
    await gotoAndHydrate(page, `/teams/${v.t2.slug}/timetable`)
    await page.waitForTimeout(8_000)
    console.log(`[cmp1507] #4358 他組織が叩いた API: ${JSON.stringify(apis)}`)
    await expect(page.getByText(v.prepTerm.name)).toHaveCount(0)
    await expect(page.getByTestId('term-add-button')).toHaveCount(0)
    await expect(page.getByTestId('timetable-create-button')).toHaveCount(0)
    await expect(page.locator(`a[href="/teams/${v.t2.slug}/timetable"]`)).toHaveCount(0)
    // 時間割・学期の API は 200 で中身を返さない（me/permissions は自分の権限の問い合わせで、他組織には空の応答）
    for (const line of apis.filter((l) => /timetable/.test(l))) expect(line, '他組織に時間割・学期が 200 で返っている').not.toMatch(/-> 200$/)
    await shot(page, 'v2-outsider')
  })
})

// ───────────── #4375 チャット ─────────────

test('#4375 チャット: 本人の投稿を画面から編集・削除でき、他人の投稿には削除が出ない（MEMBER）', async ({ browser }) => {
  const mine = `Mine ${v.tag}`
  const edited = `Edited ${v.tag}`
  const others = `Admin said ${v.tag}`
  await must(w.admin, 'POST', `/api/v1/chat/channels/${v.channel.id}/messages`, { body: others })
  await withPage(browser, w.member, async (page) => {
    await gotoAndHydrate(page, `/chat?channel=${v.channel.id}`)
    const input = page.getByTestId('team-chat-input')
    await expect(input).toBeVisible({ timeout: 90_000 })
    await input.fill(mine)
    await input.press('Enter')
    const msg = page.getByTestId('chat-message').filter({ hasText: mine })
    await expect(msg).toHaveCount(1, { timeout: 30_000 })
    // 他人（ADMIN）の投稿に削除は出ない
    const other = page.getByTestId('chat-message').filter({ hasText: others })
    await other.hover()
    await expect(other.getByTestId('chat-message-delete')).toHaveCount(0)
    // 編集（⋯ メニュー）
    await msg.hover()
    await msg.locator('button:has(.pi-ellipsis-h), button:has(.pi-ellipsis-v)').first().click()
    await page.getByText('編集', { exact: true }).click()
    const dlg = page.getByRole('dialog')
    await expect(dlg.getByText('メッセージを編集')).toBeVisible()
    await dlg.getByTestId('chat-message-edit-body').fill(edited)
    const patch = page.waitForResponse((r) => r.request().method() === 'PATCH' && /\/chat\/messages\/\d+$/.test(new URL(r.url()).pathname))
    await dlg.getByTestId('chat-message-edit-save').click()
    expect((await patch).status()).toBe(200)
    const editedMsg = page.getByTestId('chat-message').filter({ hasText: edited })
    await expect(editedMsg).toHaveCount(1, { timeout: 30_000 })
    await expect(editedMsg).toContainText('(編集済み)')
    await shot(page, 'v3-chat-edited')
    // 再読込しても編集後の本文
    await page.reload()
    await expect(page.getByTestId('chat-message').filter({ hasText: edited })).toHaveCount(1, { timeout: 90_000 })
    // 削除（ホバーのゴミ箱）
    const target = page.getByTestId('chat-message').filter({ hasText: edited })
    await target.hover()
    const del = page.waitForResponse((r) => r.request().method() === 'DELETE' && /\/chat\/messages\/\d+$/.test(new URL(r.url()).pathname))
    await target.getByTestId('chat-message-delete').click()
    expect([200, 204]).toContain((await del).status())
    await expect(page.getByText(edited)).toHaveCount(0, { timeout: 30_000 })
    await expect(page.getByTestId('chat-message-deleted').first()).toBeVisible()
    await shot(page, 'v3-chat-deleted')
  })
})

test('#4375 チャット: チャンネルを開くと一覧の未読バッジが 0 になる（MEMBER）', async ({ browser }) => {
  for (let i = 1; i <= 3; i++) await must(w.admin, 'POST', `/api/v1/chat/channels/${v.channel.id}/messages`, { body: `Unread ${i} ${v.tag}` })
  await withPage(browser, w.member, async (page) => {
    await gotoAndHydrate(page, '/chat')
    const item = page.getByTestId(`chat-channel-${v.channel.id}`)
    await expect(item).toBeVisible({ timeout: 90_000 })
    const badge = item.getByTestId('chat-channel-unread')
    await expect(badge).toBeVisible({ timeout: 30_000 })
    console.log(`[cmp1507] #4375 開く前の未読バッジ: ${(await badge.innerText()).trim()}`)
    await shot(page, 'v3-unread-before')
    const read = page.waitForResponse((r) => r.request().method() === 'POST' && new RegExp(`/chat/channels/${v.channel.id}/read$`).test(new URL(r.url()).pathname))
    await item.click()
    expect((await read).status()).toBeLessThan(300)
    await expect(item.getByTestId('chat-channel-unread')).toHaveCount(0, { timeout: 30_000 })
    await shot(page, 'v3-unread-after')
    // 再読込しても 0（サーバー側の既読）
    await gotoAndHydrate(page, '/chat')
    await expect(page.getByTestId(`chat-channel-${v.channel.id}`)).toBeVisible({ timeout: 90_000 })
    await expect(page.getByTestId(`chat-channel-${v.channel.id}`).getByTestId('chat-channel-unread')).toHaveCount(0)
  })
})

test('#4375 チャット 他テナント: 実在チャンネルと不在 ID の ?channel= 直打ちで、画面の表示も API の status・error.code も同じ', async ({ browser }) => {
  const observe = async (channelId: number) =>
    withPage(browser, w.outsider, async (page) => {
      const seen: Array<{ url: string; status: number; code: string | null }> = []
      page.on('response', async (r) => {
        const p = new URL(r.url()).pathname
        if (!p.startsWith('/api/v1/chat/')) return
        let code: string | null = null
        try {
          const j = await r.json()
          code = j?.error?.code ?? j?.code ?? null
        } catch {
          code = null
        }
        seen.push({ url: p.replace(String(channelId), '{id}'), status: r.status(), code })
      })
      const detail = responseOf(page, new RegExp(`/api/v1/chat/channels/${channelId}$`))
      await gotoAndHydrate(page, `/chat?channel=${channelId}`)
      const res = await detail
      await expect(page.getByText('指定されたチャンネルにアクセスできません').first()).toBeVisible({ timeout: 60_000 })
      await page.waitForTimeout(2_000)
      const text = (await page.locator('body').innerText()).replace(String(channelId), '{id}').replace(/\s+/g, ' ').trim()
      await shot(page, `v3-conceal-${channelId === v.channel.id ? 'real' : 'absent'}`)
      const body = await res.json().catch(() => null)
      return {
        status: res.status(),
        code: (body?.error?.code ?? body?.code ?? null) as string | null,
        body: JSON.stringify(body).replace(String(channelId), '{id}'),
        text,
        seen: [...seen].sort((a, b) => a.url.localeCompare(b.url)),
      }
    })
  const real = await observe(v.channel.id)
  const absent = await observe(987654321)
  console.log(`[cmp1507] #4375 実在: ${JSON.stringify({ s: real.status, c: real.code, seen: real.seen })}`)
  console.log(`[cmp1507] #4375 不在: ${JSON.stringify({ s: absent.status, c: absent.code, seen: absent.seen })}`)
  console.log(`[cmp1507] #4375 実在 画面: ${real.text.slice(0, 300)}`)
  console.log(`[cmp1507] #4375 不在 画面: ${absent.text.slice(0, 300)}`)
  expect(real.status).toBe(absent.status)
  expect(real.code).toBe(absent.code)
  expect(real.body).toBe(absent.body)
  expect(real.text).toBe(absent.text)
  expect(real.seen).toEqual(absent.seen)
  expect(real.text).not.toContain(v.channel.name)
})

test('#4375 チャット de: 一覧・チャンネル画面に日本語が出ない（MEMBER）', async ({ browser }) => {
  await setAccountLocale(w.member, 'de')
  const { context, page } = await openAs(browser, w.member)
  try {
    await asDe(context)
    await gotoAndHydrate(page, `/chat?channel=${v.channel.id}`)
    await expect(page.getByTestId('team-chat-input')).toBeVisible({ timeout: 90_000 })
    await expect(page.getByTestId(`chat-channel-${v.channel.id}`)).toBeVisible({ timeout: 30_000 })
    expect(await page.locator('html').getAttribute('lang')).toBe('de')
    const lines = await japaneseLines(page)
    console.log(`[cmp1507] #4375 de チャット画面の日本語行: ${JSON.stringify(lines)}`)
    await shot(page, 'v3-de-chat')
    const plus = page.locator('button:has(.pi-plus)').first()
    if (await plus.isVisible().catch(() => false)) {
      await plus.click()
      await page.waitForTimeout(1_500)
      const dlg = await japaneseLines(page)
      console.log(`[cmp1507] #4375 de 作成ダイアログの日本語行: ${JSON.stringify(dlg)}`)
      await shot(page, 'v3-de-chat-create')
      lines.push(...dlg)
    }
    expect(lines, '日本語が残っている').toEqual([])
  } finally {
    await context.close()
    await setAccountLocale(w.member, 'ja')
  }
})

test('#4375 チャット: DM から Zimmer を作った直後に、作成者（OWNER）が他人の投稿を削除できる（ADMIN）', async ({ browser }) => {
  // FE の getOrCreateDm は POST /chat/channels/dm を呼ぶが BE に無く 405（欠陥として報告）。前提は BE にある /conversations で作る
  const dm = (await must(w.admin, 'POST', '/api/v1/chat/channels/conversations', { userIds: [w.member.id] })).data
  recordAsset(`dm ${dm.id}`, `/api/v1/chat/channels/${dm.id}`, w.admin)
  const memberLine = `From member ${v.tag}`
  await must(w.member, 'POST', `/api/v1/chat/channels/${dm.id}/messages`, { body: memberLine })
  await withPage(browser, w.admin, async (page) => {
    // メンバー候補を出せるのはスコープ付きのチャット（チームのチャット画面）。汎用の /chat では候補が空になる（欠陥として報告）
    await gotoAndHydrate(page, `/teams/${v.t2.slug}/chat`)
    await page.getByTestId(`chat-channel-${dm.id}`).click({ timeout: 90_000 })
    await expect(page.getByTestId('chat-message').filter({ hasText: memberLine })).toHaveCount(1, { timeout: 90_000 })
    await page.locator('button:has(.pi-user-plus)').first().click()
    const dlg = page.getByRole('dialog')
    await expect(dlg.getByText('Zimmerに招待する')).toBeVisible()
    await dlg.getByPlaceholder('名前で検索...').fill(v.third.nickname)
    await dlg.getByText(v.third.nickname).first().click()
    await dlg.getByText('共有する', { exact: true }).click()
    const invite = page.waitForResponse((r) => r.request().method() === 'POST' && /\/invite-to-zimmer$/.test(new URL(r.url()).pathname))
    const refetch = page.waitForResponse((r) => r.request().method() === 'GET' && /\/api\/v1\/chat\/channels\/\d+$/.test(new URL(r.url()).pathname) && !r.url().endsWith(`/channels/${dm.id}`))
    await dlg.getByRole('button', { name: 'Zimmerを作成' }).click()
    const inviteRes = await invite
    expect(inviteRes.status()).toBeLessThan(300)
    const zimmerId = (await inviteRes.json()).data.id as number
    recordAsset(`zimmer ${zimmerId}`, `/api/v1/chat/channels/${zimmerId}`, w.admin)
    const refetchRes = await refetch
    console.log(`[cmp1507] #4375 Zimmer ${zimmerId} 作成直後の詳細取り直し -> ${refetchRes.status()}`)
    // 作成直後の画面で、他人（MEMBER）の投稿が見え、OWNER に削除が出る
    const theirs = page.getByTestId('chat-message').filter({ hasText: memberLine })
    await expect(theirs).toHaveCount(1, { timeout: 30_000 })
    await theirs.hover()
    await expect(theirs.getByTestId('chat-message-delete')).toBeVisible()
    await shot(page, 'v3-zimmer-owner-delete')
    const del = page.waitForResponse((r) => r.request().method() === 'DELETE' && /\/chat\/messages\/\d+$/.test(new URL(r.url()).pathname))
    await theirs.getByTestId('chat-message-delete').click()
    expect([200, 204]).toContain((await del).status())
    await expect(page.getByTestId('chat-message').filter({ hasText: memberLine })).toHaveCount(0, { timeout: 30_000 })
    await expect(page.getByTestId('chat-message-deleted').first()).toBeVisible()
  })
})
