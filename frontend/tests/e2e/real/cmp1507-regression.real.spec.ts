import { test, type Browser, type Page } from '@playwright/test'
import { readWorld, type World } from './helpers/cmp1507-global-setup'
import { API, expect, gotoAndHydrate, must, openAs, recordAsset, responseOf, type Actor } from './helpers/n1p1-world'

/**
 * 実機E2E: CMP-261105-1507（N+1 戦役で見つかった既存不具合6件の修正）の確認。
 *   #4306 FE プロジェクト画面  ① 進捗率が100倍 / ⑤ 関連タスクが JSON 生表示 / ⑥ マイルストーン「完了数/総数」が「/」だけ
 *   #4311 BE チャット          ② 同じ秒のメッセージの並びを (created_at, id) で確定
 *   #4324 BE 時間割の学期      ③ academicYear 必須・sortOrder 省略可 / ④ 時間割が残る学期の削除は 409（TIMETABLE_022）
 *
 * モックなし（page.route 不使用）。API はログイン・前提データ作成（globalSetup）・後始末（globalTeardown）と、
 * 画面に導線が無い前提操作（時間割の作成・削除）にだけ使う。確かめる表示はすべて画面を開いて確かめる。
 * 実行: playwright-cmp1507.config.ts（検証 BE 8081 / 検証 FE 3017）。
 */

test.setTimeout(300_000)

let w: World
test.beforeAll(() => {
  w = readWorld()
})

async function shot(page: Page, name: string) {
  await page.screenshot({ path: `test-results/cmp1507/${name}.png`, fullPage: true })
}

async function withPage<T>(browser: Browser, actor: Actor, path: string | undefined, fn: (page: Page) => Promise<T>) {
  const { context, page, apiOrigins } = await openAs(browser, actor, path)
  try {
    const result = await fn(page)
    // 画面が叩いた API はすべて検証用 BE であること
    expect([...apiOrigins]).toEqual([new URL(API).origin])
    return result
  } finally {
    await context.close()
  }
}

// ───────────────────────── #4306 プロジェクト画面（①⑤⑥）─────────────────────────

test('①⑤⑥ プロジェクト一覧: 進捗率 50%（5000% でない）、詳細: 進捗・タスク・マイルストーン件数と関連タスク行（MEMBER）', async ({ browser }) => {
  await withPage(browser, w.member, undefined, async (page) => {
    const listApi = responseOf(page, new RegExp(`/api/v1/teams/${w.t1.slug}/projects$`))
    await gotoAndHydrate(page, `/teams/${w.t1.slug}/projects`)
    expect((await listApi).status()).toBe(200)
    // ① 一覧カード
    await expect(page.getByText(w.project.title)).toBeVisible({ timeout: 60_000 })
    const card = page.getByText('1/2 タスク').locator('xpath=ancestor::*[contains(., "%")][1]')
    await expect(card).toContainText('50%')
    await expect(card).not.toContainText('5000%')
    await shot(page, 'p1-list-member')

    // 詳細へ（画面の導線: カードのタイトルをクリック）
    await page.getByText(w.project.title).click()
    await expect(page).toHaveURL(new RegExp(`/projects/${w.project.id}`))
    await expect(page.getByRole('heading', { name: w.project.title }).first()).toBeVisible({ timeout: 60_000 })
    // ① 詳細の進捗
    const progress = page.locator('p:text-is("進捗") + p')
    await expect(progress).toHaveText('50%')
    // ⑥ 「タスク 1/2」「マイルストーン 1/2」（以前は「/」だけだった）
    await expect(page.locator('p:text-is("タスク") + p')).toHaveText('1/2')
    await expect(page.locator('p:text-is("マイルストーン") + p')).toHaveText('1/2')
    // ⑤ 関連タスクは行として出る（タイトル・状態・期限・担当者）。JSON の生データではない
    const rows = page.getByTestId('project-related-todo-row')
    await expect(rows).toHaveCount(2, { timeout: 30_000 })
    const doneRow = rows.filter({ hasText: w.todos.done })
    const openRow = rows.filter({ hasText: w.todos.open })
    await expect(doneRow).toHaveCount(1)
    await expect(openRow).toHaveCount(1)
    await expect(doneRow).toContainText(w.todos.dueDate)
    await expect(doneRow).toContainText(w.member.nickname)
    await expect(openRow).toContainText(w.todos.dueDate)
    await expect(openRow).toContainText(w.admin.nickname)
    // 完了は取り消し線、未完了は取り消し線なし
    await expect(doneRow.locator('a.line-through')).toHaveCount(1)
    await expect(openRow.locator('.line-through')).toHaveCount(0)
    for (const row of [doneRow, openRow]) {
      const text = await row.innerText()
      expect(text).not.toMatch(/[{}"]|statusLabel|assignees/)
    }
    await shot(page, 'p1-detail-member')
  })
})

test('①⑤⑥ プロジェクト 他テナント: 他組織利用者には一覧・詳細 URL 直打ちとも中身が出ない', async ({ browser }) => {
  await withPage(browser, w.outsider, undefined, async (page) => {
    const detail = responseOf(page, new RegExp(`/api/v1/teams/${w.t1.slug}/projects/${w.project.id}$`))
    await gotoAndHydrate(page, `/teams/${w.t1.slug}/projects/${w.project.id}`)
    const status = (await detail).status()
    console.log(`[cmp1507] 他組織 GET /teams/{t1}/projects/{id} -> ${status}`)
    expect([403, 404]).toContain(status)
    await expect(page.getByText(w.project.title)).toHaveCount(0)
    await expect(page.getByText(w.todos.done)).toHaveCount(0)
    await expect(page.getByText(w.milestones.open)).toHaveCount(0)
    await shot(page, 'p1-detail-outsider')
  })
})

// ───────────────────────── #4311 チャット（②）─────────────────────────

test('② チャット: 続けて投稿したメッセージが画面で投稿順に並ぶ（再読込後の履歴も）（MEMBER）', async ({ browser }) => {
  // 同じ秒の投稿が実際に起きたことを合格の条件にする（起きるまで最大 40 件まで続けて投稿する）
  const texts: string[] = []
  const nextText = () => {
    const text = `C1507順${String(texts.length + 1).padStart(2, '0')} ${w.tag}`
    texts.push(text)
    return text
  }
  const secondsOf = (created: string[]) => created.map((c) => c.slice(0, 19))
  const sameSecondPairs = (created: string[]) => created.length - new Set(secondsOf(created)).size
  await withPage(browser, w.member, undefined, async (page) => {
    const created: string[] = []
    page.on('response', async (response) => {
      if (response.request().method() === 'POST' && new RegExp(`/api/v1/chat/channels/${w.channel.id}/messages$`).test(new URL(response.url()).pathname)) {
        const body = await response.text().catch(() => '')
        created.push(body.match(/"(?:createdAt|created_at)":"([^"]+)"/)?.[1] ?? `?${body.slice(0, 80)}`)
      }
    })
    const history = responseOf(page, new RegExp(`/api/v1/chat/channels/${w.channel.id}/messages$`))
    await gotoAndHydrate(page, `/chat?channel=${w.channel.id}`)
    expect((await history).status()).toBe(200)
    const input = page.getByTestId('team-chat-input')
    await expect(input).toBeVisible({ timeout: 60_000 })
    // 画面から続けて投稿する。表示の確認を待たず、送信が済んで入力欄が空になったら直ちに次を送る
    while (texts.length < 8 || (sameSecondPairs(created) === 0 && texts.length < 40)) {
      await input.fill(nextText())
      await input.press('Enter')
      await expect(input).toHaveValue('', { timeout: 30_000 })
      await expect.poll(() => created.length, { timeout: 30_000 }).toBe(texts.length)
    }
    for (const text of texts) await expect(page.getByTestId('chat-message').filter({ hasText: text })).toHaveCount(1, { timeout: 30_000 })
    const sameSecond = sameSecondPairs(created)
    console.log(`[cmp1507] ② 投稿 ${created.length} 件。同じ秒に重なった件数=${sameSecond}（createdAt 秒: ${secondsOf(created).join(' ')}）`)
    // 守りたい不具合（同じ秒の並び）が実際に起きた場合だけ合格にする
    expect(sameSecond, '同じ秒の投稿が1組も起きていない（不具合を再現できていない）').toBeGreaterThan(0)

    const order = async () => {
      const all = await page.getByTestId('chat-message').allInnerTexts()
      return texts.map((t) => all.findIndex((m) => m.includes(t)))
    }
    // 画面上の並びが投稿順（各メッセージの DOM 位置が単調に変化する。昇順・降順どちらの表示でも「連続している」ことを確かめる）
    const first = await order()
    expect(first.every((i) => i >= 0)).toBe(true)
    const ascending = first.every((v, i) => i === 0 || v === first[i - 1]! + 1)
    const descending = first.every((v, i) => i === 0 || v === first[i - 1]! - 1)
    expect(ascending || descending, `並び順が投稿順でない: ${first.join(',')}`).toBe(true)
    await shot(page, 'c2-chat-live-member')

    // 再読込（履歴取得経路）でも同じ向きで投稿順
    const again = responseOf(page, new RegExp(`/api/v1/chat/channels/${w.channel.id}/messages$`))
    await page.reload()
    expect((await again).status()).toBe(200)
    await expect(page.getByTestId('chat-message').filter({ hasText: texts[texts.length - 1]! })).toHaveCount(1, { timeout: 60_000 })
    const reloaded = await order()
    expect(reloaded.every((i) => i >= 0)).toBe(true)
    const asc2 = reloaded.every((v, i) => i === 0 || v === reloaded[i - 1]! + 1)
    const desc2 = reloaded.every((v, i) => i === 0 || v === reloaded[i - 1]! - 1)
    expect(asc2 || desc2, `再読込後の並び順が投稿順でない: ${reloaded.join(',')}`).toBe(true)
    // 向きは画面の他のメッセージの並び（古い→新しい、上→下）と同じ
    expect(asc2, `新しい発言が上に出ている（降順）: ${reloaded.join(',')}`).toBe(true)
    await shot(page, 'c2-chat-reloaded-member')
  })
})

test('② チャット 他テナント: 他組織利用者は ?channel= 直打ちでも開けず、投稿も見えない', async ({ browser }) => {
  await withPage(browser, w.outsider, undefined, async (page) => {
    const detail = responseOf(page, new RegExp(`/api/v1/chat/channels/${w.channel.id}$`))
    await gotoAndHydrate(page, `/chat?channel=${w.channel.id}`)
    const status = (await detail).status()
    console.log(`[cmp1507] 他組織 GET /chat/channels/{id} -> ${status}`)
    expect([403, 404]).toContain(status)
    await expect(page.getByText('指定されたチャンネルにアクセスできません').first()).toBeVisible({ timeout: 60_000 })
    await expect(page.getByText('C1507順01')).toHaveCount(0)
    await expect(page.getByTestId('team-chat-input')).toHaveCount(0)
    await shot(page, 'c2-chat-outsider')
  })
})

// ───────────────────────── #4324 時間割の学期（③④）─────────────────────────

const TERM_PAGE = (slug: string) => `/organizations/${slug}/timetable`

async function openTermTab(page: Page) {
  const tab = page.getByRole('tab', { name: '学期テンプレート' })
  await expect(tab).toBeVisible({ timeout: 90_000 })
  await tab.click()
}

test('③ 学期作成: 年度を空にして送ると 400 で作成されない／年度を入れれば作成できる（並び順の入力欄は画面に無く 0 で作成）（ADMIN）', async ({ browser }) => {
  const name = `C1507学期A ${w.tag}`
  await withPage(browser, w.admin, undefined, async (page) => {
    await gotoAndHydrate(page, TERM_PAGE(w.orgA.slug))
    await openTermTab(page)
    await page.getByRole('button', { name: '学期追加' }).click()
    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()
    await dialog.getByPlaceholder('例: 1学期').fill(name)
    await dialog.locator('input[type=date]').nth(0).fill('2031-04-01')
    await dialog.locator('input[type=date]').nth(1).fill('2031-09-30')
    // 年度を空にする
    const year = dialog.getByRole('spinbutton')
    await year.click()
    await year.press('Control+A')
    await year.press('Delete')
    await year.press('Tab')
    await expect(year).toHaveValue('')
    await shot(page, 't3-term-form-year-empty')

    // 送信 → BE は 500 でなく 400（検証エラー）を返す
    const post = page.waitForResponse((r) => r.request().method() === 'POST' && /\/organizations\/[^/]+\/timetable-terms$/.test(new URL(r.url()).pathname))
    await dialog.getByRole('button', { name: '学期追加' }).click()
    const response = await post
    console.log(`[cmp1507] ③ 年度なし POST -> ${response.status()} ${(await response.text()).slice(0, 300)}`)
    expect(response.status()).toBe(400)
    // 画面には失敗が出て、ダイアログは開いたまま、一覧に学期は増えない
    const toast = page.locator('.p-toast-message').last()
    await expect(toast).toBeVisible({ timeout: 15_000 })
    // 観測: 欄ごとのバリデーションメッセージではなく、汎用の失敗トースト（BE の fieldErrors は画面に出ない）
    console.log(`[cmp1507] ③ 年度なし 画面表示（トースト）: ${(await toast.innerText()).replace(/\s+/g, ' ')}`)
    await expect(toast).toContainText('学期の作成に失敗しました')
    await expect(dialog.getByText('必須')).toHaveCount(0)
    await expect(dialog).toBeVisible()
    await shot(page, 't3-term-year-empty-error')

    // 年度を入れ直して送れば作成できる（並び順は画面に入力欄が無く、省略扱い＝既定 0）
    await year.click()
    await year.pressSequentially('2031')
    await year.press('Tab')
    const created = page.waitForResponse((r) => r.request().method() === 'POST' && /\/organizations\/[^/]+\/timetable-terms$/.test(new URL(r.url()).pathname))
    await dialog.getByRole('button', { name: '学期追加' }).click()
    const ok = await created
    expect(ok.status()).toBe(201)
    const termId = (await ok.json()).data.id as number
    recordAsset(`term ${termId}`, `/api/v1/timetable-terms/${termId}`, w.admin)
    await expect(page.getByText('学期を作成しました')).toBeVisible({ timeout: 15_000 })
    await expect(page.getByRole('cell', { name })).toBeVisible({ timeout: 15_000 })
    await shot(page, 't3-term-created')
  })
})

test('④ 学期削除: 時間割が残る学期（論理削除済みを含む）は 409 で消えず、時間割の無い学期は消せる（ADMIN）', async ({ browser }) => {
  const nameKept = `C1507学期B ${w.tag}`
  const nameFree = `C1507学期C ${w.tag}`
  await withPage(browser, w.admin, undefined, async (page) => {
    await gotoAndHydrate(page, TERM_PAGE(w.orgA.slug))
    await openTermTab(page)

    const addTerm = async (name: string, start: string, end: string) => {
      await page.getByRole('button', { name: '学期追加' }).click()
      const dialog = page.getByRole('dialog')
      await dialog.getByPlaceholder('例: 1学期').fill(name)
      await dialog.locator('input[type=date]').nth(0).fill(start)
      await dialog.locator('input[type=date]').nth(1).fill(end)
      const year = dialog.getByRole('spinbutton')
      await year.click()
      await year.press('Control+A')
      await year.pressSequentially('2032')
      await year.press('Tab')
      const created = page.waitForResponse((r) => r.request().method() === 'POST' && /\/organizations\/[^/]+\/timetable-terms$/.test(new URL(r.url()).pathname))
      await dialog.getByRole('button', { name: '学期追加' }).click()
      const response = await created
      expect(response.status()).toBe(201)
      await expect(page.getByRole('cell', { name })).toBeVisible({ timeout: 15_000 })
      await expect(dialog).toBeHidden({ timeout: 15_000 })
      return (await response.json()).data.id as number
    }
    const keptId = await addTerm(nameKept, '2032-04-01', '2032-09-30')
    const freeId = await addTerm(nameFree, '2032-10-01', '2033-03-31')
    // 時間割の無い学期は後始末で消す（時間割が残る学期は仕様上消せないので記録しない。証拠に残骸として書く）
    recordAsset(`term ${freeId}`, `/api/v1/timetable-terms/${freeId}`, w.admin)

    // 前提: 時間割を作る（画面にチーム側から学期を選べる導線が無いため API で作成）
    const timetable = await must(w.admin, 'POST', `/api/v1/teams/${w.t1.slug}/timetables`, {
      name: `C1507時間割 ${w.tag}`,
      termId: keptId,
      effectiveFrom: '2032-04-01',
      visibility: 'MEMBERS_ONLY',
    })
    const timetableId = timetable.data.id as number
    console.log(`[cmp1507] ④ 前提: 学期 ${keptId} に時間割 ${timetableId} を作成`)

    const row = (name: string) => page.getByRole('row').filter({ hasText: name })
    const clickTrash = async (name: string) => {
      const del = page.waitForResponse((r) => r.request().method() === 'DELETE' && /\/timetable-terms\/\d+$/.test(new URL(r.url()).pathname))
      await row(name).locator('button:has(.pi-trash)').click()
      return del
    }

    // (a) 時間割が生きている学期 → 409
    const live = await clickTrash(nameKept)
    console.log(`[cmp1507] ④(a) 時間割あり DELETE -> ${live.status()} ${(await live.text()).slice(0, 300)}`)
    expect(live.status()).toBe(409)
    const liveToast = page.locator('.p-toast-message').last()
    await expect(liveToast).toBeVisible({ timeout: 15_000 })
    // 観測: 409 専用の文言ではなく汎用のエラートースト
    console.log(`[cmp1507] ④(a) 画面表示（トースト）: ${(await liveToast.innerText()).replace(/\s+/g, ' ')}`)
    await expect(row(nameKept)).toHaveCount(1)
    await shot(page, 't4-term-delete-409-live')

    // (b) 時間割を削除（論理削除）した後も、その学期は 409（論理削除済みも参照元として残る）
    await must(w.admin, 'DELETE', `/api/v1/teams/${w.t1.slug}/timetables/${timetableId}`)
    const soft = await clickTrash(nameKept)
    console.log(`[cmp1507] ④(b) 時間割削除後 DELETE -> ${soft.status()} ${(await soft.text()).slice(0, 300)}`)
    expect(soft.status()).toBe(409)
    await expect(row(nameKept)).toHaveCount(1)
    await shot(page, 't4-term-delete-409-soft')

    // (c) 時間割の無い学期は削除できる
    const free = await clickTrash(nameFree)
    expect(free.status()).toBe(204)
    await expect(row(nameFree)).toHaveCount(0, { timeout: 15_000 })
    await shot(page, 't4-term-delete-ok')
  })
})

test('③④ 学期 権限なし（MEMBER）: 追加・削除の導線が出ない／他テナント: 一覧が見えない', async ({ browser }) => {
  // このテスト専用の前提（他のテストが作る学期に頼らない）。時間割は付けないので後始末で消せる
  const term = await must(w.admin, 'POST', `/api/v1/organizations/${w.orgA.slug}/timetable-terms`, {
    name: `C1507学期M ${w.tag}`,
    startDate: '2034-04-01',
    endDate: '2034-09-30',
    academicYear: 2034,
  })
  recordAsset(`term ${term.data.id}`, `/api/v1/timetable-terms/${term.data.id}`, w.admin)
  await withPage(browser, w.member, undefined, async (page) => {
    const terms = responseOf(page, /\/organizations\/[^/]+\/timetable-terms$/)
    await gotoAndHydrate(page, TERM_PAGE(w.orgA.slug))
    expect((await terms).status()).toBe(200)
    await openTermTab(page)
    await expect(page.getByText(/C1507学期/).first()).toBeVisible({ timeout: 30_000 })
    await expect(page.getByRole('button', { name: '学期追加' })).toHaveCount(0)
    await expect(page.locator('button:has(.pi-trash)')).toHaveCount(0)
    await shot(page, 't34-term-member')
  })
  await withPage(browser, w.outsider, undefined, async (page) => {
    const terms = responseOf(page, /\/organizations\/[^/]+\/timetable-terms$/)
    await gotoAndHydrate(page, TERM_PAGE(w.orgA.slug))
    const status = (await terms).status()
    console.log(`[cmp1507] 他組織 GET /organizations/{orgA}/timetable-terms -> ${status}`)
    expect([403, 404]).toContain(status)
    await expect(page.getByText(/C1507学期/)).toHaveCount(0)
    await expect(page.getByRole('button', { name: '学期追加' })).toHaveCount(0)
    await shot(page, 't34-term-outsider')
  })
})
