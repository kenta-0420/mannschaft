/**
 * CMP-261104-0334 / 0335 / 0336 / 0337: 緊急休業画面の確定不備4件（ALIC-1〜4）の実機E2E（モックなし・実 BE・実 FE・実ブラウザ）。
 *
 * 0334 連打: 確認ダイアログの「送信する」を連打しても POST は1回・休業は1件・トーストも履歴も1件（FE 再入防止 + BE Idempotency-Key）
 * 0335 日付: de と ja で、対象期間・確認ダイアログ・履歴・プレビューの日付がロケールどおり（ja 直書き「m月d日（曜）」「〜」が de に出ない）
 * 0336 390px: md(768px) 未満は履歴・確認状況・プレビューがカード表示。文字が切れず横スクロールも出ず「確認状況を見る」に届く。1440px は表
 * 0337 STOMP: リロード・URL 直打ちの後でも ADMIN の確認状況リアルタイム購読が成立する（空 Bearer を送らず Cookie ハンドシェイク認証）
 * ロール横断: ADMIN(正) / DEPUTY_ADMIN(正・de) / MEMBER(負) / 部外者(負)。負の視点は URL 直打ちまで踏む
 *
 * 対象の操作（入力・送信・タブ移動・展開・リロード）はすべて画面で行う。API を使うのはログイン・前提データ作成・後始末だけ。
 * page.route によるモックは使わない。
 *
 * 実行例（検証用 BE=8083 / FE=3003。8080・8081・3000・3001 は別セッションが使うため触らない）:
 *   cd frontend && API_BASE_URL=http://localhost:8083 BASE_URL=http://localhost:3003 \
 *     node node_modules/playwright/cli.js test -c playwright-emergency-closure-cmp261104-real.config.ts
 * 前提: BE の MANNSCHAFT_ALLOWED_ORIGINS に FE オリジンを入れて起動し、OPTIONS で 200 を実測しておく。
 */
import { expect, test, request as pwRequest, type APIRequestContext, type Browser, type BrowserContext, type Page } from '@playwright/test'
import { mkdirSync, readFileSync, writeFileSync } from 'fs'
import { join } from 'path'
import {
  BE_API, FE_BASE, MEMBER, OUTSIDER, OWNER, apiLogin, apiMe, authHeaders, createThrowawayTeam,
  dbScalar, deleteNotificationsByIds, deleteTeam, gotoHydrated, maxNotificationId, notificationIdsSince,
  type Cred,
} from './helpers/g3457-notify'
import { loginViaApi } from '../fixtures/auth'

test.describe.configure({ mode: 'serial' })
test.setTimeout(480_000)

/** 副管理者（DEPUTY_ADMIN）。de ロケールの確認にも使う（前提として一時的にアカウント言語を de にし、後始末で戻す）。 */
const DEPUTY: Cred = {
  email: process.env.EC_DEPUTY_EMAIL ?? 'e2e-dummy-6@test.mannschaft.local',
  password: process.env.EC_DEPUTY_PASSWORD ?? 'TestPass2026!',
  label: '副管理者',
}

const SHOT_DIR = process.env.EC_SHOT_DIR ?? 'test-results/emergency-closure-cmp261104'
mkdirSync(SHOT_DIR, { recursive: true })

/** 画面文言はロケールファイルから引く（直書きの訳と食い違わないように）。 */
type Dict = Record<string, unknown>
function loadLocale(lang: 'ja' | 'de'): { rsv: Dict; common: Dict } {
  // Playwright は frontend/ を cwd として実行する（ESM のため __dirname は使えない）
  const dir = join(process.cwd(), 'app/locales', lang)
  return {
    // ロケールファイルには BOM 付きのものがある
    rsv: JSON.parse(readFileSync(join(dir, 'reservation.json'), 'utf8').replace(/^\uFEFF/, '')) as Dict,
    common: JSON.parse(readFileSync(join(dir, 'common.json'), 'utf8').replace(/^\uFEFF/, '')) as Dict,
  }
}
function pick(d: Dict, path: string): string {
  const v = path.split('.').reduce<unknown>((o, k) => (o as Dict | undefined)?.[k], d)
  if (typeof v !== 'string') throw new Error(`ロケールキーが無い: ${path}`)
  return v
}
function labels(lang: 'ja' | 'de') {
  const { rsv, common } = loadLocale(lang)
  return {
    tab: pick(rsv, 'reservation.tab.emergency_closure'),
    bookTab: pick(rsv, 'reservation.tab.book'),
    bulkSend: pick(rsv, 'emergency_closure.button.bulk_send'),
    sendConfirm: pick(rsv, 'emergency_closure.button.send_confirm'),
    checkPreview: pick(rsv, 'emergency_closure.button.check_preview'),
    viewConfirmations: pick(rsv, 'emergency_closure.button.view_confirmations'),
    maintenance: pick(rsv, 'emergency_closure.template.maintenance'),
    historyHeading: pick(rsv, 'emergency_closure.section.history'),
    previewHeading: pick(rsv, 'emergency_closure.section.preview'),
    live: pick(rsv, 'emergency_closure.connection.live'),
    denied: pick(rsv, 'emergency_closure.connection.denied'),
    unauthenticated: pick(rsv, 'emergency_closure.connection.unauthenticated'),
    sentCount: (n: number) => pick(rsv, 'emergency_closure.message.sent_count').replace('{count}', String(n)),
    confirmedCount: (c: number, t: number) => pick(rsv, 'emergency_closure.message.confirmed_count').replace('{confirmed}', String(c)).replace('{total}', String(t)),
    cancel: pick(common, 'button.cancel'),
    patientConfirm: pick(rsv, 'emergency_closure.confirm_button'),
  }
}
const JA = labels('ja')
const DE = labels('de')

const DAY_CODES = ['SUN', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT'] as const
function jstDay(offsetDays: number): { iso: string; dayCode: (typeof DAY_CODES)[number] } {
  const d = new Date(Date.now() + 9 * 3600_000 + offsetDays * 86400_000)
  const iso = `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, '0')}-${String(d.getUTCDate()).padStart(2, '0')}`
  return { iso, dayCode: DAY_CODES[d.getUTCDay()]! }
}
/** 予約がある日（プレビューに1件出る）と、範囲表記を確かめるための翌日 */
const day1 = jstDay(2)
const day2 = jstDay(3)

let ownerCtx: APIRequestContext
let memberCtx: APIRequestContext
let deputyCtx: APIRequestContext
let ownerToken = ''
let memberToken = ''
let deputyToken = ''
let memberId = 0
let deputyId = 0
let memberFullName = ''
let deputyOriginal: { locale: string; timezone: string } | null = null
let team: { slug: string; id: number; name: string }
const baseline = { id: 0 }
const contexts: BrowserContext[] = []
const log: string[] = []
function note(line: string): void { log.push(line); console.log(line) }

async function openSession(browser: Browser, c: Cred, viewport = { width: 1440, height: 900 }): Promise<{ context: BrowserContext; page: Page }> {
  const context = await browser.newContext({ baseURL: FE_BASE, locale: 'ja-JP', timezoneId: 'Asia/Tokyo', viewport })
  contexts.push(context)
  const page = await context.newPage()
  await loginViaApi(page, { email: c.email, password: c.password }, { apiBaseUrl: process.env.API_BASE_URL })
  return { context, page }
}

/** 予約画面を開いて緊急休業タブへ移る。L はその利用者の表示言語の文言 */
async function openClosureTab(page: Page, L: typeof JA, opts: { creator?: boolean } = {}): Promise<void> {
  await gotoHydrated(page, `/teams/${team.slug}/reservations`)
  const tab = page.getByRole('tab', { name: L.tab })
  await expect(tab).toBeVisible({ timeout: 90_000 })
  if (opts.creator) {
    // 新規チームの作成者に遅れて出る「メンバーの権限を初期設定」ダイアログを、通常操作（あとで決める）で閉じる
    const later = page.getByRole('button', { name: 'あとで決める' })
    if (await later.waitFor({ state: 'visible', timeout: 30_000 }).then(() => true, () => false)) {
      await later.click()
      await expect(later).toBeHidden({ timeout: 10_000 })
    }
  }
  await tab.click()
  await expect(page.getByTestId('closure-reason')).toBeVisible({ timeout: 30_000 })
}

async function setPeriod(page: Page, from: string, to: string): Promise<void> {
  const dates = page.locator('input[type="date"]:visible')
  await dates.nth(0).fill(from)
  await dates.nth(1).fill(to)
}

async function pageOverflowPx(page: Page): Promise<number> {
  return page.evaluate(() => Math.max(document.documentElement.scrollWidth, document.body.scrollWidth) - window.innerWidth)
}

async function shot(page: Page, name: string): Promise<void> {
  await page.screenshot({ path: `${SHOT_DIR}/${name}.png`, fullPage: true })
}

function closureCount(): number {
  return Number(dbScalar(`select count(*) from emergency_closures where team_id = ${team.id}`))
}

/** 画面と同じ整形規則で期待値を作る（Intl はブラウザ側で評価し、ICU の差を持ち込まない） */
async function expectedDates(page: Page, lang: 'ja' | 'de'): Promise<{ range: string; day1: string; preview: string }> {
  return page.evaluate(([l, a, b]) => {
    const ymd = (s: string) => { const [y, m, d] = s.split('-').map(Number); return new Date(Date.UTC(y!, m! - 1, d!)) }
    const s = ymd(a!)
    const e = ymd(b!)
    if (l === 'ja') {
      const ja = (d: Date) => `${d.getUTCMonth() + 1}月${d.getUTCDate()}日（${new Intl.DateTimeFormat('ja-JP', { weekday: 'short', timeZone: 'UTC' }).format(d)}）`
      return { range: `${ja(s)}〜${ja(e)}`, day1: ja(s), preview: `${ja(s)} 10:00〜10:30` }
    }
    const f = new Intl.DateTimeFormat('de-DE', { month: 'short', day: 'numeric', weekday: 'short', timeZone: 'UTC' })
    return { range: f.formatRange(s, e), day1: f.format(s), preview: `${f.format(s)} 10:00 – 10:30` }
  }, [lang, day1.iso, day2.iso] as const)
}

/** STOMP の送受信フレームを記録する（CONNECT の Authorization と ERROR フレームの有無を確かめる） */
function recordStomp(page: Page): { sent: string[]; received: string[] } {
  const rec = { sent: [] as string[], received: [] as string[] }
  page.on('websocket', ws => {
    if (!/\/ws/.test(ws.url())) return
    ws.on('framesent', f => { rec.sent.push(String(f.payload)) })
    ws.on('framereceived', f => { rec.received.push(String(f.payload)) })
  })
  return rec
}
const frameCmd = (f: string) => f.replace(/^\s+/, '').split('\n')[0]!.trim()

test.beforeAll(async () => {
  baseline.id = maxNotificationId()
  ownerCtx = await pwRequest.newContext()
  memberCtx = await pwRequest.newContext()
  deputyCtx = await pwRequest.newContext()
  ownerToken = await apiLogin(ownerCtx, OWNER)
  memberToken = await apiLogin(memberCtx, MEMBER)
  deputyToken = await apiLogin(deputyCtx, DEPUTY)
  const me = await memberCtx.get(`${BE_API}/users/me`, { headers: authHeaders(memberToken) })
  const md = (await me.json()).data as { id: number; lastName: string; firstName: string }
  memberId = md.id
  memberFullName = `${md.lastName} ${md.firstName}`
  deputyId = (await apiMe(deputyCtx, deputyToken)).id
  const dme = ((await (await deputyCtx.get(`${BE_API}/users/me`, { headers: authHeaders(deputyToken) })).json()).data) as { locale: string; timezone: string }
  deputyOriginal = { locale: dme.locale, timezone: dme.timezone }
  team = await createThrowawayTeam(ownerCtx, ownerToken, 'ec0334')

  async function join(ctx: APIRequestContext, token: string, roleId: number): Promise<void> {
    const inv = await ownerCtx.post(`${BE_API}/teams/${team.slug}/invite-tokens`, { headers: authHeaders(ownerToken), data: { roleId, expiresIn: '7d', maxUses: 10 } })
    if (!inv.ok()) throw new Error(`招待作成失敗 role=${roleId}: ${inv.status()} ${await inv.text()}`)
    const t = ((await inv.json()).data as { token: string }).token
    const j = await ctx.post(`${BE_API}/invite/${t}/join`, { headers: authHeaders(token), data: {} })
    if (!j.ok()) throw new Error(`招待参加失敗 role=${roleId}: ${j.status()} ${await j.text()}`)
  }
  await join(memberCtx, memberToken, 4)
  // 副管理者ロールの招待は宛先指定のため、MEMBER で参加させて管理者が昇格する（前提データ作成）
  await join(deputyCtx, deputyToken, 4)
  const promote = await ownerCtx.patch(`${BE_API}/teams/${team.slug}/members/${deputyId}/role`, { headers: authHeaders(ownerToken), data: { roleId: 3 } })
  if (!promote.ok()) throw new Error(`副管理者への昇格失敗: ${promote.status()} ${await promote.text()}`)

  const cat = await ownerCtx.get(`${BE_API}/teams/${team.slug}/modules/catalog`, { headers: authHeaders(ownerToken) })
  const mod = ((await cat.json()).data.modules as Array<{ moduleId: number; slug: string }>).find(m => m.slug === 'reservation')!
  const tg = await ownerCtx.patch(`${BE_API}/teams/${team.slug}/modules/${mod.moduleId}/toggle`, { headers: authHeaders(ownerToken), data: { moduleId: mod.moduleId, enabled: true } })
  expect(tg.ok(), await tg.text()).toBeTruthy()
  const hours = DAY_CODES.map(code => ({ dayOfWeek: code, isOpen: true, openTime: '08:00:00', closeTime: '22:00:00' }))
  const h = await ownerCtx.put(`${BE_API}/teams/${team.slug}/reservation-settings/business-hours`, { headers: authHeaders(ownerToken), data: { hours } })
  expect(h.ok(), await h.text()).toBeTruthy()
  const line = await ownerCtx.post(`${BE_API}/teams/${team.slug}/reservation-lines`, { headers: authHeaders(ownerToken), data: { name: '面談室' } })
  expect(line.ok(), await line.text()).toBeTruthy()
  const lineId = (await line.json()).data.id as number
  const tpl = await ownerCtx.post(`${BE_API}/teams/${team.slug}/reservation-slot-templates`, {
    headers: authHeaders(ownerToken),
    data: { lineId, dayOfWeek: day1.dayCode, startTime: '10:00:00', endTime: '10:30:00', capacity: 3 },
  })
  expect(tpl.ok(), await tpl.text()).toBeTruthy()
  const sl = await ownerCtx.get(`${BE_API}/teams/${team.slug}/reservation-slots?from=${day1.iso}&to=${day1.iso}`, { headers: authHeaders(ownerToken) })
  const rows = (await sl.json()).data as Array<{ id: number; lineId: number | null; basic: { startTime: string } }>
  const slot = rows.find(r => r.lineId === lineId && r.basic?.startTime?.startsWith('10:00'))
  if (!slot) throw new Error(`枠が見つからない ${day1.iso}`)
  const rsv = await memberCtx.post(`${BE_API}/teams/${team.slug}/reservations`, { headers: authHeaders(memberToken), data: { reservationSlotId: slot.id, lineId, userNote: 'EC0334予約者' } })
  expect(rsv.ok(), await rsv.text()).toBeTruthy()
  note(`[前提] team=${team.slug}(id=${team.id}) member=${memberId}(${memberFullName}) deputy=${deputyId}(元locale=${deputyOriginal.locale}) 予約日=${day1.iso} 範囲=${day1.iso}..${day2.iso}`)
})

// ブラウザのセッションはテストごとに閉じる（メモリ節約。失敗時の画面写真は Playwright が先に撮る）
test.afterEach(async () => {
  for (const c of contexts.splice(0)) await c.close().catch(e => console.log('[後始末] context close 失敗', String(e)))
})

test.afterAll(async () => {
  for (const c of contexts) await c.close().catch(e => console.log('[後始末] context close 失敗', String(e)))
  try {
    if (deputyOriginal) {
      const tok = await apiLogin(deputyCtx, DEPUTY)
      const r = await deputyCtx.put(`${BE_API}/users/me`, { headers: authHeaders(tok), data: deputyOriginal })
      note(`[後始末] 副管理者の言語を ${deputyOriginal.locale} に戻す: ${r.status()}`)
    }
  } catch (e) { note(`[後始末] 副管理者の言語復元失敗 ${String(e)}`) }
  try {
    if (!team) throw new Error('team 未作成のため DB 後始末は不要')
    // 自分が作ったチーム（ID 確認済み）の行を ID で列挙してから、その ID だけを消す
    const ids = (sql: string) => dbScalar(sql).split(/\s+/).filter(Boolean).map(Number)
    const closureIds = ids(`select id from emergency_closures where team_id = ${team.id}`)
    const rsvIds = ids(`select id from reservations where team_id = ${team.id}`)
    const slotIds = ids(`select id from reservation_slots where team_id = ${team.id}`)
    // テンプレートの主キーは UUID(binary16)。hex で列挙し unhex で消す
    const tplIds = dbScalar(`select hex(id) from reservation_slot_templates where team_id = ${team.id}`).split(/\s+/).filter(s => /^[0-9A-F]{32}$/.test(s))
    const notifIds = [
      ...notificationIdsSince(baseline.id, memberId, 'EMERGENCY_CLOSURE', { scopeId: team.id }),
      ...notificationIdsSince(baseline.id, deputyId, 'EMERGENCY_CLOSURE', { scopeId: team.id }),
    ]
    note(`[後始末] team=${team.id} closures=${closureIds.join(',')} reservations=${rsvIds.join(',')} slots=${slotIds.join(',')} templates=${tplIds.join(',')} notifications=${notifIds.join(',')}`)
    deleteNotificationsByIds(notifIds)
    if (closureIds.length) {
      dbScalar(`delete from emergency_closure_confirmations where emergency_closure_id in (${closureIds.join(',')})`)
      dbScalar(`delete from emergency_closures where id in (${closureIds.join(',')})`)
    }
    if (rsvIds.length) dbScalar(`delete from reservations where id in (${rsvIds.join(',')})`)
    if (slotIds.length) dbScalar(`delete from reservation_slots where id in (${slotIds.join(',')})`)
    if (tplIds.length) dbScalar(`delete from reservation_slot_templates where id in (${tplIds.map(h => `unhex('${h}')`).join(',')})`)
  } catch (e) { note(`[後始末] DB 削除失敗 ${String(e)}`) }
  if (team) await deleteTeam(ownerCtx, await apiLogin(ownerCtx, OWNER), team.slug)
  writeFileSync(`${SHOT_DIR}/run-log.txt`, log.join('\n') + '\n')
  await ownerCtx.dispose(); await memberCtx.dispose(); await deputyCtx.dispose()
})

/**
 * 0334 + 0335(ja): ADMIN が確認ダイアログの「送信する」を連打する。
 * mode=mouse: 実マウスで3連打（clickCount=3）。mode=sync: 同一タスク内で DOM の click() を3回（最も速い連打）
 */
async function burstSend(page: Page, mode: 'mouse' | 'sync', subject: string): Promise<{ posts: Array<{ key: string | null }>; statuses: number[] }> {
  const posts: Array<{ key: string | null }> = []
  const statuses: number[] = []
  const isPost = (u: string, m: string) => m === 'POST' && /\/emergency-closures$/.test(new URL(u).pathname)
  page.on('request', r => { if (isPost(r.url(), r.method())) posts.push({ key: r.headers()['idempotency-key'] ?? null }) })
  page.on('response', r => { if (isPost(r.url(), r.request().method())) statuses.push(r.status()) })

  await page.getByRole('button', { name: JA.maintenance }).click()
  await page.getByTestId('closure-subject').fill(subject)
  await page.getByRole('button', { name: JA.bulkSend }).first().click()
  const dlg = page.locator('.p-dialog')
  await expect(dlg).toBeVisible({ timeout: 10_000 })
  const send = dlg.getByRole('button', { name: JA.sendConfirm })
  // トーストは数秒で消えるため、出現したトーストを DOM 監視で数える（後から数えると消えていて 0 になる）
  await page.evaluate(() => {
    const w = window as unknown as { __toastTexts: string[] }
    w.__toastTexts = []
    new MutationObserver(muts => {
      for (const m of muts) {
        m.addedNodes.forEach(n => {
          if (n instanceof HTMLElement) {
            const els = n.matches('.p-toast-message') ? [n] : Array.from(n.querySelectorAll('.p-toast-message'))
            els.forEach(e => w.__toastTexts.push((e as HTMLElement).innerText))
          }
        })
      }
    }).observe(document.body, { childList: true, subtree: true })
  })
  if (mode === 'mouse') {
    await send.click({ clickCount: 3, delay: 0 })
  } else {
    await send.evaluate((el: HTMLElement) => { el.click(); el.click(); el.click() })
  }
  await expect(page.getByText(JA.sentCount(1)).first()).toBeVisible({ timeout: 60_000 })
  // 遅れて届く重複送信も拾えるよう、少し待ってから数える
  await page.waitForTimeout(5_000)
  return { posts, statuses }
}

for (const run of [
  { mode: 'mouse' as const, vp: { name: '1440x900', width: 1440, height: 900 } },
  { mode: 'sync' as const, vp: { name: '390x844', width: 390, height: 844 } },
]) {
  test(`0334/0335(ja) ADMIN ${run.vp.name}: 「送信する」連打(${run.mode})で POST1回・休業1件・トースト1件・履歴1件。日付は ja 表記`, async ({ browser }) => {
    const { page } = await openSession(browser, OWNER, { width: run.vp.width, height: run.vp.height })
    await openClosureTab(page, JA, { creator: true })
    const exp = await expectedDates(page, 'ja')
    const before = closureCount()
    await setPeriod(page, day1.iso, day2.iso)
    // 0335(ja) 対象期間
    await expect(page.getByText(exp.range).first()).toBeVisible()
    // 0335(ja) プレビュー
    await page.getByRole('button', { name: JA.checkPreview }).click()
    await expect(page.getByText('1件の予約に通知します')).toBeVisible({ timeout: 30_000 })
    const prevSel = run.vp.width < 768 ? 'closure-preview-cards' : 'closure-preview-table'
    await expect(page.getByTestId(prevSel)).toContainText(exp.preview)
    await expect(page.getByTestId(prevSel)).toContainText(memberFullName)

    const subject = `E2E-burst-${run.mode}-${Date.now()}`
    const dlgTextP = (async () => {
      const dlg = page.locator('.p-dialog')
      await expect(dlg).toBeVisible({ timeout: 10_000 })
      return dlg.innerText()
    })()
    const res = await burstSend(page, run.mode, subject)
    const dlgText = await dlgTextP
    expect(dlgText, '0335(ja) 確認ダイアログの期間').toContain(exp.range)

    const after = closureCount()
    const toastTexts = await page.evaluate(() => (window as unknown as { __toastTexts: string[] }).__toastTexts)
    const toasts = toastTexts.filter(t => t.includes(JA.sentCount(1))).length
    note(`[0334 ${run.mode} ${run.vp.name}] POST=${res.posts.length} statuses=${res.statuses.join(',')} key=${res.posts[0]?.key} 休業 ${before}->${after} トースト=${toasts}`)
    await shot(page, `0334-${run.mode}-after-send-${run.vp.name}`)
    expect(res.posts.length, 'POST は1回').toBe(1)
    expect(res.posts[0]!.key, 'Idempotency-Key ヘッダが付く').toMatch(/^[0-9a-f-]{36}$/i)
    expect(res.statuses).toEqual([201])
    expect(after - before, '休業は1件だけ増える').toBe(1)
    expect(toasts, 'トーストは1件').toBe(1)

    // 履歴に件名（＝今回の送信）は1件だけ。リロードして DB 由来の表示で確かめる
    await page.reload({ waitUntil: 'domcontentloaded' })
    await openClosureTab(page, JA)
    const histSel = run.vp.width < 768 ? 'closure-history-card' : 'closure-history-table'
    if (run.vp.width < 768) {
      await expect(page.getByTestId(histSel).first()).toBeVisible({ timeout: 30_000 })
      await expect(page.getByTestId(histSel)).toHaveCount(after)
    } else {
      await expect(page.getByTestId(histSel).locator('tbody > tr').first()).toBeVisible({ timeout: 30_000 })
      await expect(page.getByTestId(histSel).locator('tbody > tr')).toHaveCount(after)
    }
    // 0335(ja) 履歴: 期間と送信日時の書式
    const histRoot = page.getByTestId(run.vp.width < 768 ? 'closure-history-cards' : 'closure-history-table')
    await expect(histRoot).toContainText(exp.range)
    expect(await histRoot.innerText()).toMatch(/\d{4}\/\d{2}\/\d{2} \d{2}:\d{2}/)
    await shot(page, `0334-history-${run.vp.name}`)
  })
}

test('0335(de)/DEPUTY_ADMIN 1440: de では対象期間・確認ダイアログ・履歴・プレビューが de 表記で ja 直書きが出ない。送信もできる', async ({ browser }) => {
  // 前提: 副管理者のアカウント言語を de にする（後始末で元に戻す）
  const tok = await apiLogin(deputyCtx, DEPUTY)
  const r = await deputyCtx.put(`${BE_API}/users/me`, { headers: authHeaders(tok), data: { locale: 'de', timezone: deputyOriginal!.timezone } })
  expect(r.ok(), await r.text()).toBeTruthy()
  const { page } = await openSession(browser, DEPUTY)
  await openClosureTab(page, DE)
  const exp = await expectedDates(page, 'de')
  note(`[0335 de] 期待 range="${exp.range}" preview="${exp.preview}"`)
  await setPeriod(page, day1.iso, day2.iso)
  const period = page.locator('section').filter({ has: page.locator('input[type="date"]') }).first()
  await expect(period).toContainText(exp.range)
  await page.getByRole('button', { name: DE.checkPreview }).click()
  const prev = page.getByTestId('closure-preview-table')
  await expect(prev).toContainText(exp.preview, { timeout: 30_000 })
  await expect(prev).toContainText(memberFullName)
  // 送信成功でプレビューは閉じるため、送信前に本文を控える
  const prevText = await prev.innerText()
  await shot(page, '0335-de-preview-1440')
  await page.getByRole('button', { name: DE.maintenance }).click()
  await page.getByTestId('closure-subject').fill(`E2E-de-${Date.now()}`)
  await page.getByRole('button', { name: DE.bulkSend }).first().click()
  const dlg = page.locator('.p-dialog')
  await expect(dlg).toBeVisible({ timeout: 10_000 })
  await expect(dlg).toContainText(exp.range)
  const dlgText = await dlg.innerText()
  await shot(page, '0335-de-dialog-1440')
  const resp = page.waitForResponse(x => x.request().method() === 'POST' && /\/emergency-closures$/.test(new URL(x.url()).pathname))
  await dlg.getByRole('button', { name: DE.sendConfirm }).click()
  expect((await resp).status()).toBe(201)
  await expect(page.getByText(DE.sentCount(1)).first()).toBeVisible({ timeout: 30_000 })
  const hist = page.getByTestId('closure-history-table')
  await expect(hist).toContainText(exp.range, { timeout: 30_000 })
  const histText = await hist.innerText()
  const periodText = await period.innerText()
  note(`[0335 de] period="${periodText.replace(/\s+/g, ' ').slice(0, 160)}" dialog="${dlgText.replace(/\s+/g, ' ').slice(0, 200)}" hist="${histText.replace(/\s+/g, ' ').slice(0, 200)}" prev="${prevText.replace(/\s+/g, ' ').slice(0, 160)}"`)
  for (const [where, text] of [['期間', periodText], ['ダイアログ', dlgText], ['履歴', histText], ['プレビュー', prevText]] as const) {
    expect(text, `de の${where}に ja の日付表記が出ない`).not.toMatch(/\d+月\d+日|（[月火水木金土日]）|〜/)
  }
  expect(histText, 'de の履歴の送信日時は dd.MM.yyyy HH:mm').toMatch(/\d{2}\.\d{2}\.\d{4} \d{2}:\d{2}/)
  await shot(page, '0335-de-history-1440')
})

for (const vp of [{ name: '390x844', width: 390, height: 844 }, { name: '1440x900', width: 1440, height: 900 }]) {
  test(`0336 ADMIN ${vp.name}: ${vp.width < 768 ? 'カード表示で文字が切れず横スクロールなし・確認状況を見るに届く' : '表表示'}`, async ({ browser }) => {
    const { page } = await openSession(browser, OWNER, { width: vp.width, height: vp.height })
    await openClosureTab(page, JA, { creator: true })
    const mobile = vp.width < 768
    const cards = page.getByTestId('closure-history-cards')
    const table = page.getByTestId('closure-history-table')
    if (mobile) {
      await expect(cards).toBeVisible({ timeout: 30_000 })
      await expect(table).toBeHidden()
    } else {
      await expect(table).toBeVisible({ timeout: 30_000 })
      await expect(cards).toBeHidden()
      await expect(table.locator('thead th')).toHaveCount(5)
    }
    expect(await pageOverflowPx(page), `${vp.name} 履歴でページが横にはみ出す`).toBeLessThanOrEqual(0)
    if (mobile) {
      // カード内の各項目が切れていない（要素の内容幅が表示幅を超えない・カードが画面内）
      const clipped = await page.evaluate(() => {
        const out: string[] = []
        document.querySelectorAll('[data-testid="closure-history-card"]').forEach((card, i) => {
          const r = card.getBoundingClientRect()
          if (r.left < 0 || r.right > window.innerWidth + 0.5) out.push(`card${i} 画面外 ${r.left}-${r.right}`)
          card.querySelectorAll('dd, button').forEach(el => {
            const e = el as HTMLElement
            if (e.scrollWidth > e.clientWidth + 1) out.push(`card${i} ${e.dataset.field ?? e.tagName} scroll=${e.scrollWidth} client=${e.clientWidth}`)
          })
        })
        return out
      })
      note(`[0336 ${vp.name}] 切れ判定: ${clipped.length ? clipped.join(' / ') : 'なし'}`)
      expect(clipped).toEqual([])
    }
    await shot(page, `0336-history-collapsed-${vp.name}`)

    // 「確認状況を見る」に届き、押すと確認状況が出る
    const toggle = mobile ? page.getByTestId('closure-card-toggle').first() : table.getByRole('button', { name: JA.viewConfirmations }).first()
    await toggle.scrollIntoViewIfNeeded()
    await expect(toggle).toBeInViewport()
    const box = (await toggle.boundingBox())!
    note(`[0336 ${vp.name}] 確認状況を見る ${Math.round(box.width)}x${Math.round(box.height)} x=${Math.round(box.x)}`)
    expect(box.width).toBeGreaterThanOrEqual(44)
    expect(box.height).toBeGreaterThanOrEqual(44)
    expect(box.x + box.width).toBeLessThanOrEqual(vp.width)
    await toggle.click()
    if (mobile) {
      const conf = page.getByTestId('closure-card-confirmation').first()
      await expect(conf).toBeVisible({ timeout: 30_000 })
      await expect(conf.locator('[data-field="name"]')).toHaveText(memberFullName)
    } else {
      await expect(table.locator('table table tbody tr').first().locator('td').first()).toHaveText(memberFullName, { timeout: 30_000 })
    }
    expect(await pageOverflowPx(page), `${vp.name} 確認状況の展開でページが横にはみ出す`).toBeLessThanOrEqual(0)
    await shot(page, `0336-history-expanded-${vp.name}`)
    await toggle.click()

    // プレビュー
    await setPeriod(page, day1.iso, day1.iso)
    await page.getByRole('button', { name: JA.checkPreview }).click()
    await expect(page.getByText('1件の予約に通知します')).toBeVisible({ timeout: 30_000 })
    if (mobile) {
      await expect(page.getByTestId('closure-preview-cards')).toBeVisible()
      await expect(page.getByTestId('closure-preview-table')).toBeHidden()
      const pc = page.getByTestId('closure-preview-card').first()
      await expect(pc).toContainText(memberFullName)
      const prevClip = await pc.evaluate(el => {
        const r = el.getBoundingClientRect()
        const bad: string[] = []
        if (r.right > window.innerWidth + 0.5) bad.push(`画面外 right=${r.right}`)
        el.querySelectorAll('*').forEach(c => { const h = c as HTMLElement; if (h.children.length === 0 && h.scrollWidth > h.clientWidth + 1 && h.clientWidth > 0) bad.push(`${h.tagName} scroll=${h.scrollWidth} client=${h.clientWidth} "${h.innerText}"`) })
        return bad
      })
      note(`[0336 ${vp.name}] プレビューカード 切れ判定: ${prevClip.length ? prevClip.join(' / ') : 'なし'}`)
      expect(prevClip).toEqual([])
    } else {
      await expect(page.getByTestId('closure-preview-table')).toBeVisible()
      await expect(page.getByTestId('closure-preview-cards')).toBeHidden()
    }
    expect(await pageOverflowPx(page), `${vp.name} プレビューでページが横にはみ出す`).toBeLessThanOrEqual(0)
    await shot(page, `0336-preview-${vp.name}`)
  })
}

/** 展開した確認状況の接続表示が LIVE になり、STOMP に空 Bearer・ERROR が無いことを確かめる */
async function expectLive(page: Page, stomp: { sent: string[]; received: string[] }, label: string, mobile: boolean): Promise<void> {
  const toggle = mobile
    ? page.getByTestId('closure-card-toggle').first()
    : page.getByTestId('closure-history-table').getByRole('button', { name: JA.viewConfirmations }).first()
  await toggle.click()
  const panel = mobile ? page.getByTestId('closure-card-confirmations') : page.getByTestId('closure-history-table')
  await expect(panel.getByText(JA.live)).toBeVisible({ timeout: 45_000 })
  await page.waitForTimeout(4_000) // 拒否が遅れて届かないか見届ける
  await expect(panel.getByText(JA.live)).toBeVisible()
  await expect(panel.getByText(JA.denied)).toHaveCount(0)
  await expect(panel.getByText(JA.unauthenticated)).toHaveCount(0)
  const connects = stomp.sent.filter(f => /^(CONNECT|STOMP)$/.test(frameCmd(f)))
  const subs = stomp.sent.filter(f => frameCmd(f) === 'SUBSCRIBE' && /emergency-closures\/\d+\/confirmations/.test(f))
  const errors = stomp.received.filter(f => frameCmd(f) === 'ERROR')
  const emptyBearer = connects.filter(f => /^Authorization:\s*Bearer\s*$/m.test(f))
  note(`[0337 ${label}] CONNECT=${connects.length}(空Bearer=${emptyBearer.length}, Authorization有=${connects.filter(f => /^Authorization:/m.test(f)).length}) SUBSCRIBE(confirmations)=${subs.length} ERROR=${errors.length} CONNECTED=${stomp.received.filter(f => frameCmd(f) === 'CONNECTED').length}`)
  expect(connects.length).toBeGreaterThanOrEqual(1)
  expect(emptyBearer, '空 Bearer を送らない').toEqual([])
  expect(subs.length).toBeGreaterThanOrEqual(1)
  expect(errors, 'ERROR フレームが無い').toEqual([])
}

test('0337 ADMIN 1440: リロード後・URL 直打ち後でも確認状況のリアルタイム購読が成立し、患者の確認が再読込なしで反映される', async ({ browser }) => {
  const { page } = await openSession(browser, OWNER)
  // (a) URL 直打ち（ログイン直後に予約画面の URL を直接開く）
  const s1 = recordStomp(page)
  await openClosureTab(page, JA, { creator: true })
  await expectLive(page, s1, 'URL直打ち', false)
  await shot(page, '0337-live-direct-1440')

  // (b) リロード（アクセストークンがメモリから消える状態）
  const s2 = recordStomp(page)
  await page.reload({ waitUntil: 'domcontentloaded' })
  await openClosureTab(page, JA)
  await expectLive(page, s2, 'リロード後', false)
  await shot(page, '0337-live-after-reload-1440')

  // (c) 患者（MEMBER）が通知一覧から「確認する」を押す → ADMIN の画面が再読込なしで 1/N に変わる
  const latestClosureId = Number(dbScalar(`select max(id) from emergency_closures where team_id = ${team.id}`))
  const total = Number(dbScalar(`select count(*) from emergency_closure_confirmations where emergency_closure_id = ${latestClosureId}`))
  const subject = dbScalar(`select subject from emergency_closures where id = ${latestClosureId}`).trim()
  const { page: mp } = await openSession(browser, MEMBER)
  await gotoHydrated(mp, '/notifications')
  const item = mp.locator('li, article, [role="listitem"], div.p-card, div').filter({ hasText: subject }).filter({ has: mp.getByRole('button', { name: JA.patientConfirm }) }).last()
  await expect(item).toBeVisible({ timeout: 60_000 })
  await item.getByRole('button', { name: JA.patientConfirm }).click()
  await shot(mp, '0337-member-confirmed')
  await expect(page.getByTestId('closure-history-table').getByText(JA.confirmedCount(1, total))).toBeVisible({ timeout: 30_000 })
  note(`[0337 リアルタイム] closure=${latestClosureId} 患者確認後に ADMIN 画面が ${JA.confirmedCount(1, total)} に更新（再読込なし）`)
  await shot(page, '0337-live-updated-1440')
})

test('0337 ADMIN 390: リロード後もカードの確認状況でリアルタイム購読が成立する', async ({ browser }) => {
  const { page } = await openSession(browser, OWNER, { width: 390, height: 844 })
  await openClosureTab(page, JA, { creator: true })
  const s = recordStomp(page)
  await page.reload({ waitUntil: 'domcontentloaded' })
  await openClosureTab(page, JA)
  await expectLive(page, s, 'リロード後390', true)
  await shot(page, '0337-live-after-reload-390')
})

/** 負の視点: 期待する到達状態を待ってから、緊急休業のタブ・送信ボタン・休業の文字が 0 件であることを確かめる */
async function recordNegativeView(page: Page, label: string, url: string, settled: () => Promise<void>): Promise<void> {
  await page.goto(url, { waitUntil: 'commit', timeout: 240_000 })
  await settled()
  const body = (await page.locator('body').innerText()).replace(/\s+/g, ' ').slice(0, 160)
  const hasTab = await page.getByRole('tab', { name: JA.tab }).count()
  const hasSend = await page.getByRole('button', { name: JA.bulkSend }).count()
  const hasHist = await page.getByText(JA.historyHeading).count()
  note(`[${label}] ${url} -> ${new URL(page.url()).pathname} tab=${hasTab} send=${hasSend} history=${hasHist} body="${body}"`)
  await shot(page, `role-${label.replace(/[^A-Za-z0-9_-]/g, '_')}`)
  expect(hasTab).toBe(0)
  expect(hasSend).toBe(0)
  expect(hasHist).toBe(0)
}

test('ロール MEMBER(負): 予約画面に緊急休業のタブ・送信・履歴が出ない。管理画面の URL 直打ちはチームトップへ戻される', async ({ browser }) => {
  const { page } = await openSession(browser, MEMBER)
  for (const vp of [{ width: 1440, height: 900 }, { width: 390, height: 844 }]) {
    await page.setViewportSize(vp)
    await recordNegativeView(page, `MEMBER-reservations-${vp.width}`, `/teams/${team.slug}/reservations`, async () => {
      await expect(page.getByRole('tab', { name: JA.bookTab })).toBeVisible({ timeout: 150_000 })
      await page.waitForTimeout(3_000)
    })
  }
  await recordNegativeView(page, 'MEMBER-admin-reservations', `/teams/${team.slug}/admin/reservations`, async () => {
    await page.waitForURL(u => u.pathname === `/teams/${team.slug}`, { timeout: 150_000 })
  })
})

test('ロール OUTSIDER 部外者(負): 対象チームの予約画面・管理画面とも緊急休業が出ない', async ({ browser }) => {
  const { page } = await openSession(browser, OUTSIDER)
  const teamResp = page.waitForResponse(r => r.request().method() === 'GET' && new URL(r.url()).pathname === `/api/v1/teams/${team.slug}`, { timeout: 150_000 })
  await recordNegativeView(page, 'OUTSIDER-reservations', `/teams/${team.slug}/reservations`, async () => {
    const st = (await teamResp).status()
    note(`[OUTSIDER] GET /api/v1/teams/${team.slug} status=${st}`)
    expect(st).toBe(403)
    await expect(page.getByText('情報を取得できませんでした')).toBeVisible({ timeout: 30_000 })
    await page.waitForTimeout(2_000)
  })
  await recordNegativeView(page, 'OUTSIDER-admin-reservations', `/teams/${team.slug}/admin/reservations`, async () => {
    await page.waitForURL(u => u.pathname === `/teams/${team.slug}`, { timeout: 150_000 })
  })
})
