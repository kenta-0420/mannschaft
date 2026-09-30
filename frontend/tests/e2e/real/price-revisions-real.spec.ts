/**
 * 価格改定（price-revisions）・税コードマスタの実機 E2E（PR #3447 / F20.1 Billing Center）
 *
 * このテストは API モックを使わない実機テストです。実 BE（既定 :8080）と実 FE（BASE_URL）を
 * 実ブラウザで操作します。価格・税コードは必ず画面の正規経路（クリック・入力・送信）で登録し、
 * API の直接呼び出しはログイン・後始末の確認・権限の素通り検査（ロール横断）に限ります。
 * DB への直接投入は行いません。
 *
 * ロール:
 *   - 権限あり      : e2e-admin@test.mannschaft.local（SYSTEM_ADMIN）
 *   - 権限なし      : e2e-user@test.mannschaft.local（一般。システム権限なし）
 *                     ※ 同アカウントは組織「e2e」（id=71）の作成者・ADMIN でもあるため、
 *                        テナント ADMIN の検証にも用いる
 *   - 他テナント ADMIN: e2e-dummy-1@test.mannschaft.local（チーム fc-u-18 の ADMIN）
 *
 * 各テストは前のテストが作ったデータ（改定 ID など）を使うため、状態を一時ファイルへ書き出して
 * 引き継ぐ（1件の失敗で後続が skip されて「未検証なのに緑」に見えないよう、serial にはしない）。
 * 後始末（PR-10）はこの実行が作成した ID（State に記録済みのもの）に限る。商品枠は共有 DB であり、
 * 一覧に出る他人・運用者の未適用改定を状態だけで無差別に取り消してはならない。
 *
 * 実行方法:
 *   BASE_URL=http://localhost:3001 API_BASE_URL=http://localhost:8080 \
 *     npx playwright test tests/e2e/real/price-revisions-real.spec.ts \
 *     -c playwright-real.config.ts --project chromium-real --reporter=list
 */

import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import {
  test,
  expect,
  type Browser,
  type BrowserContext,
  type Page,
  type Response,
  type TestInfo,
} from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

test.use({ storageState: { cookies: [], origins: [] } })
test.describe.configure({ mode: 'default' })
test.setTimeout(240_000)

const BE = process.env.API_BASE_URL ?? 'http://localhost:8080'
const PR_API = `${BE}/api/v1/system-admin/billing/price-revisions`
const TAX_API = `${BE}/api/v1/system-admin/billing/tax-codes`

const ADMIN = { email: 'e2e-admin@test.mannschaft.local', password: 'TestPass2026!' }
const USER = { email: 'e2e-user@test.mannschaft.local', password: 'TestPass2026!' }
const OTHER_TENANT_ADMIN = { email: 'e2e-dummy-1@test.mannschaft.local', password: 'TestPass2026!' }
/** USER が作成者・ADMIN の組織（テナント ADMIN 検証用） */
const USER_ORG_SLUG = 'e2e'

/**
 * 検証対象の商品（productKey × scopeKind）の候補。未来予約は1商品1本までで、取り消し（cancel）が
 * 壊れていると枠が空かないため、実行ごとに「未来予約の無い枠」を候補から選ぶ（選択は一覧 API の読み取りのみ）。
 */
const SLOT_CANDIDATES: [string, string][] = [
  ['BASIC', 'TEAM'], ['FULL', 'TEAM'], ['BASIC', 'ORG'], ['FULL', 'ORG'], ['BASIC', 'USER'], ['FULL', 'USER'],
]
const FUTURE_STATUSES = ['DRAFT', 'PROVISIONING', 'PROVISION_FAILED', 'READY', 'SCHEDULED']
let PRODUCT_KEY = 'BASIC'
let SCOPE_KIND = 'TEAM'
const TAX_CODE = 'JP_STANDARD_10'
const NEW_AMOUNT = 3300

// ---------------------------------------------------------------------------
// 状態の引き継ぎ
// ---------------------------------------------------------------------------
type State = {
  productKey?: string
  scopeKind?: string
  draftAId?: string
  draftAEffectiveLocal?: string
  revisionBId?: string
  revisionBEffectiveLocal?: string
  revisionBStatus?: string
  cancelledIds?: string[]
  leftovers?: { id: string; status: string }[]
}
const STATE_FILE = path.join(os.tmpdir(), 'price-revisions-real-e2e-state.json')
function readState(): State {
  try {
    return JSON.parse(fs.readFileSync(STATE_FILE, 'utf8')) as State
  } catch {
    // eslint-disable-next-line no-restricted-syntax -- 初回実行では状態ファイルがまだ無いだけ（失敗要因ではない）
    return {}
  }
}
function writeState(patch: Partial<State>): State {
  const next = { ...readState(), ...patch }
  fs.writeFileSync(STATE_FILE, JSON.stringify(next, null, 2))
  return next
}
{
  // ワーカー再起動（失敗後）でもモジュール再評価時に選んだ枠を引き継ぐ
  const s0 = readState()
  if (s0.productKey) PRODUCT_KEY = s0.productKey
  if (s0.scopeKind) SCOPE_KIND = s0.scopeKind
}

// ---------------------------------------------------------------------------
// ヘルパー
// ---------------------------------------------------------------------------
async function newLoggedInPage(
  browser: Browser,
  creds: { email: string; password: string },
): Promise<{ context: BrowserContext; page: Page }> {
  const context = await browser.newContext({ locale: 'ja-JP', timezoneId: 'Asia/Tokyo' })
  const page = await context.newPage()
  await loginViaApi(page, creds, { apiBaseUrl: BE, deferNavigation: true })
  return { context, page }
}

async function gotoAndSettle(page: Page, url: string): Promise<void> {
  await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 180_000 })
  await waitForHydration(page)
  // PageLoading コンポーネント（PrimeVue ProgressSpinner）が消えるまで待つ。
  // .pi-spin は一覧・詳細内の個別スピナーで、画面全体のローディングシェルは
  // p-progressspinner を使う（adhd-ux-flows.spec.ts 等と同じ作法）。
  // eslint-disable-next-line no-restricted-syntax -- スピナーが初めから存在しないページでは待つ対象が無いだけ（失敗要因ではない）
  await page.locator('.p-progressspinner').first().waitFor({ state: 'detached', timeout: 30_000 }).catch(() => {})
  // eslint-disable-next-line no-restricted-syntax -- スピナーが初めから存在しないページでは待つ対象が無いだけ（失敗要因ではない）
  await page.locator('.pi-spin').first().waitFor({ state: 'detached', timeout: 30_000 }).catch(() => {})
}

async function shot(page: Page, testInfo: TestInfo, name: string): Promise<void> {
  const file = testInfo.outputPath(`${name}.png`)
  await page.screenshot({ path: file, fullPage: true })
  await testInfo.attach(name, { path: file, contentType: 'image/png' })
}

async function note(testInfo: TestInfo, name: string, body: unknown): Promise<void> {
  const text = typeof body === 'string' ? body : JSON.stringify(body, null, 2)
  console.log(`[${testInfo.title}] ${name}: ${text}`)
  await testInfo.attach(name, { body: text, contentType: 'text/plain' })
}

/** JST の壁時計で「今から minutes 分後（分単位切り上げ）」を datetime-local 形式で返す。 */
function jstLocalAfterMinutes(minutes: number): string {
  const ms = Math.ceil((Date.now() + minutes * 60_000) / 60_000) * 60_000
  const d = new Date(ms + 9 * 3600_000)
  return d.toISOString().slice(0, 16)
}

/** datetime-local（JST 壁時計）→ 画面の formatDateTime 表記（YYYY/MM/DD HH:mm）。 */
function toDisplay(local: string): string {
  return local.replace(/-/g, '/').replace('T', ' ')
}

/** datetime-local（JST 壁時計）→ epoch ms。 */
function jstLocalToEpoch(local: string): number {
  return Date.parse(`${local}:00+09:00`)
}

async function selectDropdown(page: Page, id: string, option: string): Promise<void> {
  await page.locator(`#${id}`).click()
  await page.getByRole('option', { name: option, exact: true }).click()
}

async function fillCreateDialog(page: Page, effectiveLocal: string): Promise<void> {
  await page.getByRole('button', { name: 'create-price-revision' }).click()
  const dialog = page.getByRole('dialog', { name: '価格改定を作成' })
  await expect(dialog).toBeVisible()
  // 商品種別は既定 PLAN。商品キーは自由入力欄（プラン選択ではない）
  await dialog.locator('#pr-product-key').fill(PRODUCT_KEY)
  await selectDropdown(page, 'pr-scope-kind', SCOPE_KIND)
  await dialog.locator('#pr-effective-from').fill(effectiveLocal)
  await selectDropdown(page, 'pr-tax-mode', 'EXCLUSIVE')
  const amount = dialog.locator('#pr-input-amount input, input#pr-input-amount').first()
  await amount.click()
  await amount.pressSequentially(String(NEW_AMOUNT))
  await amount.press('Tab')
  await dialog.locator('#pr-tax-code').fill(TAX_CODE)
}

async function submitCreate(page: Page): Promise<Response> {
  const [res] = await Promise.all([
    page.waitForResponse((r) => r.url().startsWith(PR_API) && r.request().method() === 'POST' && !/\/[^/]+\/(provision|retry-provision|reconcile-provision|activate|cancel)$/.test(new URL(r.url()).pathname)),
    page.getByRole('button', { name: 'submit-create-price-revision' }).click(),
  ])
  return res
}

async function detailStatus(page: Page): Promise<string> {
  const section = page.locator('section').first()
  return (await section.locator('.p-tag').first().innerText()).trim()
}

async function acceptConfirm(page: Page): Promise<void> {
  const dialog = page.locator('.p-confirmdialog, [role="alertdialog"]').first()
  await expect(dialog).toBeVisible()
  await dialog.locator('.p-button-danger, button.p-confirmdialog-accept-button').last().click()
}

async function cancelViaUi(page: Page, id: string, testInfo: TestInfo, label: string): Promise<Response> {
  await gotoAndSettle(page, `/system-admin/price-revisions/${id}`)
  await page.getByRole('button', { name: 'cancel-price-revision', exact: true }).click()
  const confirmText = page.getByText('この価格改定を取り消しますか？', { exact: false })
  await expect(confirmText).toBeVisible()
  await shot(page, testInfo, `${label}-cancel-confirm`)
  const [res] = await Promise.all([
    page.waitForResponse((r) => r.url() === `${PR_API}/${id}/cancel`),
    acceptConfirm(page),
  ])
  await note(testInfo, `${label}-cancel-response`, { status: res.status(), body: await res.text() })
  return res
}

// ===========================================================================
// PR-01 導線
// ===========================================================================
test('PR-01 導線: システム管理画面のメニューから価格改定一覧・税コード管理へ辿れる', async ({ browser }, testInfo) => {
  fs.rmSync(STATE_FILE, { force: true })
  const { context, page } = await newLoggedInPage(browser, ADMIN)
  try {
    await gotoAndSettle(page, '/system-admin')
    // 描画が完了してからリンクを数える（無ければ本当に欠陥として失敗させる。握りつぶさない）
    await page.locator('a[href*="/system-admin/price-revisions"]').first().waitFor({ state: 'visible', timeout: 30_000 })
    await shot(page, testInfo, 'system-admin-top')
    const onTop = await page.locator('a[href*="/system-admin/price-revisions"]').count()
    await gotoAndSettle(page, '/system-admin/billing')
    await page.locator('a[href*="/system-admin/price-revisions"]').first().waitFor({ state: 'visible', timeout: 30_000 })
    await shot(page, testInfo, 'system-admin-billing')
    const onBilling = await page.locator('a[href*="/system-admin/price-revisions"]').count()
    await note(testInfo, 'price-revisions への a[href] の数', { '/system-admin': onTop, '/system-admin/billing': onBilling })

    expect(onTop, '/system-admin の管理メニューに価格改定一覧へのリンクがある').toBeGreaterThan(0)
    expect(onBilling, '/system-admin/billing（課金マスタ管理）にも価格改定一覧へのリンクがある').toBeGreaterThan(0)

    // トップの管理メニューのリンクを辿って一覧 → 税コードマスタ（一覧画面のボタン）まで到達する
    await gotoAndSettle(page, '/system-admin')
    const link = page.locator('a[href*="/system-admin/price-revisions"]').first()
    await link.click()
    await page.waitForURL(/\/system-admin\/price-revisions$/)
    await expect(page.getByRole('heading', { name: '価格改定一覧' })).toBeVisible()
    await page.getByRole('button', { name: 'tax-codes' }).click()
    await expect(page.getByRole('dialog', { name: '税コードマスタ' })).toBeVisible()
  } finally {
    await context.close()
  }
})

// ===========================================================================
// PR-02 税コード登録（画面経由）
// ===========================================================================
test('PR-02a 税コード: 画面から税コードを登録できる', async ({ browser }, testInfo) => {
  const { context, page } = await newLoggedInPage(browser, ADMIN)
  try {
    await gotoAndSettle(page, '/system-admin/price-revisions')
    await page.getByRole('button', { name: 'tax-codes' }).click()
    const dialog = page.getByRole('dialog', { name: '税コードマスタ' })
    await expect(dialog).toBeVisible()
    await shot(page, testInfo, 'tax-code-dialog')

    const code = `E2E_PR_${Date.now()}`
    await dialog.getByLabel('new-tax-code-code').fill(code)
    await dialog.getByLabel('new-tax-code-display-name').fill('E2E 実機 標準10%')
    await dialog.locator('input[aria-label="new-tax-code-valid-from"]').fill(jstLocalAfterMinutes(-60 * 24))
    await dialog.getByLabel('new-tax-code-stripe-tax-code').fill('txcd_10000000')

    const [res] = await Promise.all([
      page.waitForResponse((r) => r.url() === TAX_API && r.request().method() === 'POST'),
      dialog.getByRole('button', { name: 'submit-create-tax-code' }).click(),
    ])
    const body = await res.text()
    await note(testInfo, 'POST tax-codes', { status: res.status(), body, requestBody: res.request().postData() })
    await shot(page, testInfo, 'tax-code-after-submit')
    expect(res.status(), '税コード登録 API は 201/200').toBeLessThan(300)
    expect(JSON.parse(res.request().postData() ?? '{}').stripeTaxCode, '入力した Stripe 税コードが送られる').toBe('txcd_10000000')
    await expect(dialog.getByRole('cell', { name: code, exact: true })).toBeVisible()
    // 一覧にも Stripe 税コードが表示される（null で保存されていないこと）
    const createdRow = dialog.getByRole('row').filter({ has: page.getByRole('cell', { name: code, exact: true }) })
    await expect(createdRow).toContainText('txcd_10000000')

    // 後始末: 登録した税コードを画面の無効化ボタンで無効化する
    const created = JSON.parse(body) as { id?: string; data?: { id: string } }
    const id = created.data?.id ?? created.id
    if (id) {
      const [del] = await Promise.all([
        page.waitForResponse((r) => r.url() === `${TAX_API}/${id}` && r.request().method() === 'DELETE'),
        dialog.getByRole('button', { name: `deactivate-tax-code-${id}` }).click(),
      ])
      await note(testInfo, 'DELETE tax-code (無効化)', { status: del.status() })
      expect(del.status(), '税コードの無効化は 2xx').toBeLessThan(300)
      await expect(dialog.getByRole('cell', { name: code, exact: true })).toHaveCount(0)
    }
  } finally {
    await context.close()
  }
})

test('PR-02b 税コード: Stripe 税コードを入力でき、形式違反（txcd_bad）が画面で弾かれ文言が出る', async ({ browser }, testInfo) => {
  const { context, page } = await newLoggedInPage(browser, ADMIN)
  try {
    await gotoAndSettle(page, '/system-admin/price-revisions')
    await page.getByRole('button', { name: 'tax-codes' }).click()
    const dialog = page.getByRole('dialog', { name: '税コードマスタ' })
    await expect(dialog).toBeVisible()
    const inputs = await dialog.locator('input').evaluateAll((els) =>
      els.map((e) => ({ type: (e as HTMLInputElement).type, aria: e.getAttribute('aria-label'), placeholder: e.getAttribute('placeholder') })))
    await note(testInfo, '税コードダイアログの入力欄', inputs)

    const stripeInput = dialog.getByLabel('new-tax-code-stripe-tax-code')
    await expect(stripeInput, 'Stripe 税コード（txcd_XXXXXXXX）の入力欄がある').toHaveCount(1, { timeout: 3_000 })

    const code = `E2E_PR_BAD_${Date.now()}`
    await dialog.getByLabel('new-tax-code-code').fill(code)
    await dialog.getByLabel('new-tax-code-display-name').fill('E2E 形式違反')
    await dialog.locator('input[aria-label="new-tax-code-valid-from"]').fill(jstLocalAfterMinutes(-60 * 24))
    await stripeInput.fill('txcd_bad')
    // 形式違反は画面で弾く（BE の PRICE_REVISION_021 と同じ文言）。登録ボタンは押せない＝API へ送らない。
    await expect(dialog.getByLabel('stripe-tax-code-format-error')).toHaveText('Stripe税コードの形式が不正です（txcd_ に続く数字8桁）')
    await expect(dialog.getByRole('button', { name: 'submit-create-tax-code' })).toBeDisabled()
    await shot(page, testInfo, 'tax-code-bad-format')

    // 正しい形式に直せばエラーが消え、登録ボタンが押せるようになる（登録はしない）
    await stripeInput.fill('txcd_10000000')
    await expect(dialog.getByLabel('stripe-tax-code-format-error')).toHaveCount(0)
    await expect(dialog.getByRole('button', { name: 'submit-create-tax-code' })).toBeEnabled()
  } finally {
    await context.close()
  }
})

// ===========================================================================
// PR-03 価格改定の作成
// ===========================================================================
test('PR-03 作成: DRAFT が作られ、詳細で値（適用開始日時）がずれず、2本目の未来予約は 409 で文言が出る', async ({ browser }, testInfo) => {
  const { context, page } = await newLoggedInPage(browser, ADMIN)
  try {
    // 未来予約の無い枠を選ぶ（一覧 API の読み取りのみ。登録は下の画面操作で行う）
    const listRes = await page.request.get(`${PR_API}?size=100`)
    const existing = ((await listRes.json()) as { data: { items: { id: string; productKey: string; scopeKind: string; status: string }[] } }).data.items
    await note(testInfo, '作成前の既存改定', existing)
    const free = SLOT_CANDIDATES.find(([k, s]) => !existing.some((e) => e.productKey === k && e.scopeKind === s && FUTURE_STATUSES.includes(e.status)))
    expect(free, '未来予約の無い商品枠が残っている').toBeTruthy()
    ;[PRODUCT_KEY, SCOPE_KIND] = free!
    writeState({ productKey: PRODUCT_KEY, scopeKind: SCOPE_KIND })

    await gotoAndSettle(page, '/system-admin/price-revisions')
    // 後続の activate で即時適用（ACTIVE）まで届かせるため、近い未来（12分後）にする
    const effective = jstLocalAfterMinutes(12)
    await fillCreateDialog(page, effective)
    await shot(page, testInfo, 'create-dialog-filled')
    const res = await submitCreate(page)
    const body = await res.text()
    await note(testInfo, 'POST price-revisions (A)', { status: res.status(), request: res.request().postData(), body })
    expect(res.status()).toBe(201)
    const id = (JSON.parse(body) as { data: { id: string } }).data.id
    // 取り消しが成功しなかった場合も provision/activate の検証を続けられるよう、既定では A を B として扱う
    writeState({ draftAId: id, draftAEffectiveLocal: effective, revisionBId: id, revisionBEffectiveLocal: effective, revisionBStatus: 'DRAFT' })

    await page.waitForURL(new RegExp(`/system-admin/price-revisions/${id}$`))
    // eslint-disable-next-line no-restricted-syntax -- スピナーが初めから存在しないページでは待つ対象が無いだけ（失敗要因ではない）
    await page.locator('.pi-spin').first().waitFor({ state: 'detached', timeout: 30_000 }).catch(() => {})
    await expect(page.getByRole('heading', { name: '価格改定の詳細' })).toBeVisible()
    await shot(page, testInfo, 'detail-draft')
    expect(await detailStatus(page)).toBe('DRAFT')
    const section = page.locator('section').first()
    await expect(section).toContainText(PRODUCT_KEY)
    await expect(section).toContainText(SCOPE_KIND)
    await expect(section, `適用開始日時が入力値 ${effective} のまま表示される`).toContainText(toDisplay(effective))
    await expect(page.getByRole('row').filter({ hasText: 'DRAFT' }).first()).toBeVisible()
    // reconcile は PROVISIONING 以外で出ない（PR-08）
    await expect(page.getByRole('button', { name: 'reconcile-provision-price-revision', exact: true })).toHaveCount(0)
    // 税抜 3300 + 10% → 税込 3630
    await expect(page.getByRole('cell', { name: '3630', exact: true })).toBeVisible()

    // 一覧に DRAFT で出る
    await page.getByRole('button', { name: 'back-to-list' }).click()
    await page.waitForURL(/\/system-admin\/price-revisions$/)
    // eslint-disable-next-line no-restricted-syntax -- スピナーが初めから存在しないページでは待つ対象が無いだけ（失敗要因ではない）
    await page.locator('.pi-spin').first().waitFor({ state: 'detached', timeout: 30_000 }).catch(() => {})
    const row = page.getByRole('row').filter({ has: page.getByRole('button', { name: `detail-${id}` }) })
    await expect(row).toBeVisible()
    await expect(row).toContainText('DRAFT')
    await expect(row).toContainText(toDisplay(effective))
    await shot(page, testInfo, 'list-with-draft')

    // 2本目の未来予約 → 409 と文言
    await fillCreateDialog(page, jstLocalAfterMinutes(60 * 24 * 5))
    const res2 = await submitCreate(page)
    const body2 = await res2.text()
    await note(testInfo, 'POST price-revisions (2本目)', { status: res2.status(), body: body2 })
    expect(res2.status()).toBe(409)
    const toast = page.locator('.p-toast-message').last()
    await expect(toast).toBeVisible()
    const toastText = await toast.innerText()
    await note(testInfo, '2本目 409 のトースト文言', toastText)
    await shot(page, testInfo, 'second-future-409')
    expect(toastText, '予約は1本までである旨の文言（errorFutureExists）').toContain('既に未来日程の価格改定が存在します')
  } finally {
    await context.close()
  }
})

// ===========================================================================
// PR-07 取り消し（DRAFT）→ 予約枠が空く
// ===========================================================================
test('PR-07 取り消し: DRAFT を確認ダイアログ経由で取り消すと CANCELLED になり、同じ商品で新しく作れる', async ({ browser }, testInfo) => {
  const state = readState()
  expect(state.draftAId, 'PR-03 で作った DRAFT の ID').toBeTruthy()
  const { context, page } = await newLoggedInPage(browser, ADMIN)
  try {
    const res = await cancelViaUi(page, state.draftAId!, testInfo, 'draftA')
    await page.waitForTimeout(1_000)
    await note(testInfo, '取り消し後のトースト・状態', { toasts: await page.locator('.p-toast-message').allInnerTexts(), status: await detailStatus(page) })
    await shot(page, testInfo, 'draftA-after-cancel')
    // 実機E2E（2026-09-29）では chk_bpbv_active 違反（SQL Error 3819）で常に 500 だった。
    expect(res.status(), 'DRAFT の取り消し API は 200').toBe(200)
    await expect.poll(() => detailStatus(page)).toBe('CANCELLED')
    await expect(page.getByRole('button', { name: 'cancel-price-revision', exact: true })).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'reconcile-provision-price-revision', exact: true })).toHaveCount(0)
    await shot(page, testInfo, 'draftA-cancelled')
    writeState({ cancelledIds: [...(readState().cancelledIds ?? []), state.draftAId!] })

    // 予約枠が空いた → 同じ商品で新しく作れる（この B を provision/activate に使う）
    await gotoAndSettle(page, '/system-admin/price-revisions')
    const effective = jstLocalAfterMinutes(3)
    await fillCreateDialog(page, effective)
    const res2 = await submitCreate(page)
    const body2 = await res2.text()
    await note(testInfo, 'POST price-revisions (B)', { status: res2.status(), body: body2 })
    expect(res2.status(), '取り消し後は同じ商品で新規作成できる').toBe(201)
    const idB = (JSON.parse(body2) as { data: { id: string } }).data.id
    writeState({ revisionBId: idB, revisionBEffectiveLocal: effective, revisionBStatus: 'DRAFT' })
    await page.waitForURL(new RegExp(`/system-admin/price-revisions/${idB}$`))
    // eslint-disable-next-line no-restricted-syntax -- スピナーが初めから存在しないページでは待つ対象が無いだけ（失敗要因ではない）
    await page.locator('.pi-spin').first().waitFor({ state: 'detached', timeout: 30_000 }).catch(() => {})
    expect(await detailStatus(page)).toBe('DRAFT')
    await shot(page, testInfo, 'revisionB-draft')
  } finally {
    await context.close()
  }
})

// ===========================================================================
// PR-04 provision
// ===========================================================================
test('PR-04 provision: 詳細画面から provision し READY（Stripe Price/Product ID 表示）になる', async ({ browser }, testInfo) => {
  const state = readState()
  expect(state.revisionBId, 'PR-07 で作った改定 B の ID').toBeTruthy()
  const id = state.revisionBId!
  const { context, page } = await newLoggedInPage(browser, ADMIN)
  try {
    await gotoAndSettle(page, `/system-admin/price-revisions/${id}`)
    const [res] = await Promise.all([
      page.waitForResponse((r) => r.url() === `${PR_API}/${id}/provision`, { timeout: 120_000 }),
      page.getByRole('button', { name: 'provision-price-revision', exact: true }).click(),
    ])
    const body = await res.text()
    await note(testInfo, 'POST provision', { status: res.status(), body })
    await page.waitForTimeout(1_000)
    const toasts = await page.locator('.p-toast-message').allInnerTexts()
    await note(testInfo, 'provision 後のトースト', toasts)
    await shot(page, testInfo, 'after-provision')
    const status = await detailStatus(page)
    writeState({ revisionBStatus: status })
    await note(testInfo, 'provision 後の状態', status)
    await expect(page.getByRole('button', { name: 'reconcile-provision-price-revision', exact: true }), 'PROVISIONING 以外では reconcile が出ない').toHaveCount(status === 'PROVISIONING' ? 1 : 0)

    if (status === 'PROVISION_FAILED') {
      const bandErr = await page.locator('[aria-label^="band-error-"]').allInnerTexts()
      await note(testInfo, 'band のエラー文言', bandErr)
      // retry 導線
      const [retry] = await Promise.all([
        page.waitForResponse((r) => r.url() === `${PR_API}/${id}/retry-provision`, { timeout: 120_000 }),
        page.getByRole('button', { name: 'retry-provision-price-revision', exact: true }).click(),
      ])
      await note(testInfo, 'POST retry-provision', { status: retry.status(), body: await retry.text() })
      await shot(page, testInfo, 'after-retry')
      writeState({ revisionBStatus: await detailStatus(page) })
    }
    expect(await detailStatus(page)).toBe('READY')
    // band ごとに Stripe の Price ID が画面に表示される。
    // Product ID は API 応答（PriceBandVersionResponse）に含まれない設計（02_api_design.md）のため検証しない。
    await expect(page.getByLabel('band-stripe-price-ref-1'), 'Stripe Price ID（price_…）が表示される').toHaveText(/^price_[A-Za-z0-9]+$/)
  } finally {
    await context.close()
  }
})

// ===========================================================================
// PR-05 activate
// ===========================================================================
test('PR-05 activate: 確認ダイアログを経て SCHEDULED/ACTIVE になる', async ({ browser }, testInfo) => {
  test.setTimeout(1_200_000)
  const state = readState()
  expect(state.revisionBId).toBeTruthy()
  expect(state.revisionBStatus, 'PR-04 で READY になっている').toBe('READY')
  const id = state.revisionBId!
  // 即時適用（ACTIVE）で下流へ届かせるため、適用開始日時を過ぎるまで待つ
  const waitMs = jstLocalToEpoch(state.revisionBEffectiveLocal!) - Date.now() + 5_000
  if (waitMs > 0) await new Promise((r) => setTimeout(r, Math.min(waitMs, 900_000)))
  const { context, page } = await newLoggedInPage(browser, ADMIN)
  try {
    await gotoAndSettle(page, `/system-admin/price-revisions/${id}`)
    await page.getByRole('button', { name: 'activate-price-revision', exact: true }).click()
    await expect(page.getByText('この価格改定を適用しますか？', { exact: false })).toBeVisible()
    await shot(page, testInfo, 'activate-confirm')
    const [res] = await Promise.all([
      page.waitForResponse((r) => r.url() === `${PR_API}/${id}/activate`, { timeout: 60_000 }),
      acceptConfirm(page),
    ])
    const body = await res.text()
    await note(testInfo, 'POST activate', { status: res.status(), body })
    expect(res.status()).toBe(200)
    await expect.poll(() => detailStatus(page)).toMatch(/^(SCHEDULED|ACTIVE)$/)
    const status = await detailStatus(page)
    writeState({ revisionBStatus: status, leftovers: [{ id, status }] })
    await expect(page.getByRole('button', { name: 'reconcile-provision-price-revision', exact: true })).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'cancel-price-revision', exact: true }), 'SCHEDULED/ACTIVE は取り消し不可').toHaveCount(0)
    await shot(page, testInfo, 'after-activate')
  } finally {
    await context.close()
  }
})

// ===========================================================================
// PR-06 下流（テナント側のプラン変更プレビュー）
// ===========================================================================
// 既知の欠陥（CMP-260930-1931）: 料金表のカタログ API（GET /api/v1/billing/plans）が
// 旧 plan_price_bands を読み続けており、ACTIVATE した新価格（本テストの改定 B）が
// カタログ・Billing Center のプラン変更プレビューへ反映されない。
//
// Codex 検分（P2）指摘: 旧 PR-06 は下流全体を test.fail() していたため、既知の欠陥に
// 無関係な失敗（ログイン・API 応答形式・Billing Center への到達・画面の描画）まで
// 「期待どおりの失敗」として success 扱いになり、回帰を検出できなかった。
// → 既知の欠陥に依存しない検証は PR-06a（通常テスト）に残し、既知の欠陥に直接・必然的に
//   依存する検証だけを PR-06b（test.fail、CMP-260930-1931）に切り出す。
test('PR-06a 下流: テナント ADMIN が Billing Center に到達し、カタログ API・画面が壊れずに応答する', async ({ browser }, testInfo) => {
  const state = readState()
  const { context, page } = await newLoggedInPage(browser, USER)
  try {
    // カタログ API 自体は 200 で応答する（新価格が反映されるかは PR-06b の検証範囲）
    const catalog = await page.request.get(`${BE}/api/v1/billing/plans`)
    const catalogJson = (await catalog.json()) as { data: { plans: { planKey: string; priceBands: { scopeKind: string; bandNo: number; monthlyPriceJpy: number | null }[] }[] } }
    const fullTeam = catalogJson.data.plans.find((p) => p.planKey === PRODUCT_KEY)?.priceBands.filter((b) => b.scopeKind === SCOPE_KIND)
    await note(testInfo, `GET /billing/plans ${PRODUCT_KEY}/${SCOPE_KIND} バンド`, { status: catalog.status(), revisionBStatus: state.revisionBStatus, bands: fullTeam })
    expect(catalog.status(), 'カタログ API は 200 で応答する').toBe(200)
    expect(state.revisionBStatus, 'PR-05 で ACTIVE になっている').toBe('ACTIVE')

    await gotoAndSettle(page, `/organizations/${USER_ORG_SLUG}/settings/billing`)
    await page.waitForTimeout(2_000)
    await shot(page, testInfo, 'tenant-billing-center')
    const text = await page.locator('main, body').first().innerText()
    await note(testInfo, 'Billing Center 本文（先頭 1500 字）', text.slice(0, 1500))
    // 画面が例外で落ちずに描画されていること（内容がプラン新価格を反映しているかは PR-06b）
    await expect(page.locator('main, body').first()).not.toContainText('エラーが発生しました')
    await expect(page.getByText(/Billing Center|お支払い|プラン/).first()).toBeVisible({ timeout: 5_000 })
  } finally {
    await context.close()
  }
})

// 既知の欠陥（CMP-260930-1931）に直接・必然的に依存する検証のみをここに置く。マスター裁可により
// 別戦役として切り出し済みのため、test.fail() で「失敗する」ことを明示する
// （直った場合は逆に緑→赤の反転で気付ける。skip にはしない）。
test.fail('PR-06b 下流: Billing Center のプラン変更プレビューに新価格が出る（既知の欠陥 CMP-260930-1931）', async ({ browser }, testInfo) => {
  const state = readState()
  const { context, page } = await newLoggedInPage(browser, USER)
  try {
    // 下流の一次証拠: テナント側が読むプランカタログ API に、適用した新価格（税込）が出ているか
    const catalog = await page.request.get(`${BE}/api/v1/billing/plans`)
    const catalogJson = (await catalog.json()) as { data: { plans: { planKey: string; priceBands: { scopeKind: string; bandNo: number; monthlyPriceJpy: number | null }[] }[] } }
    const fullTeam = catalogJson.data.plans.find((p) => p.planKey === PRODUCT_KEY)?.priceBands.filter((b) => b.scopeKind === SCOPE_KIND)
    await note(testInfo, `GET /billing/plans ${PRODUCT_KEY}/${SCOPE_KIND} バンド`, { status: catalog.status(), revisionBStatus: state.revisionBStatus, bands: fullTeam })
    expect(fullTeam?.find((b) => b.bandNo === 1)?.monthlyPriceJpy, 'カタログのバンド1に新価格（税込 3630）が出る').toBe(3630)

    await gotoAndSettle(page, `/organizations/${USER_ORG_SLUG}/settings/billing`)
    await page.waitForTimeout(2_000)
    await shot(page, testInfo, 'tenant-billing-center-preview')
    const changeButtons = page.getByRole('button', { name: /プラン.*変更|変更/ })
    await note(testInfo, 'プラン変更ボタン数', await changeButtons.count())
    // カタログのバンド価格が null のままだと、契約中プランが「フリー」表示になりボタンが0件になる
    // （実機E2E 2026-09-30 実測）。この可視性検証は新価格反映に必然的に依存する。
    await expect(changeButtons.first(), 'プラン変更の導線がある').toBeVisible({ timeout: 5_000 })
    await changeButtons.first().click()
    const dialog = page.getByTestId('billing-plan-change-dialog')
    await expect(dialog).toBeVisible()
    await page.getByTestId('plan-change-target-select').selectOption(PRODUCT_KEY)
    await expect(page.getByTestId('plan-change-amount-excl-tax')).toContainText(String(NEW_AMOUNT))
    await shot(page, testInfo, 'plan-change-preview')
  } finally {
    await context.close()
  }
})

// ===========================================================================
// PR-09 ロール横断
// ===========================================================================
test('PR-09a 一般ユーザー: メニューに価格改定・税コードの導線が出ない／直打ちは弾かれる', async ({ browser }, testInfo) => {
  test.setTimeout(600_000)
  const state = readState()
  const targetId = state.revisionBId ?? state.draftAId
  expect(targetId, '直打ち対象の改定 ID').toBeTruthy()
  const { context, page } = await newLoggedInPage(browser, USER)
  try {
    const apiStatuses: { url: string; status: number }[] = []
    page.on('response', (r) => {
      if (r.url().includes('/system-admin/billing/')) apiStatuses.push({ url: r.url(), status: r.status() })
    })
    await gotoAndSettle(page, '/dashboard')
    await expect(page.locator('a[href="/system-admin"], a[href*="/system-admin/price-revisions"]')).toHaveCount(0)
    await shot(page, testInfo, 'user-dashboard-nav')

    await gotoAndSettle(page, '/system-admin/price-revisions')
    await page.waitForTimeout(1_500)
    await shot(page, testInfo, 'user-direct-list')
    await note(testInfo, '一覧直打ち URL', page.url())
    await expect(page.getByText('この画面を表示する権限がありません')).toBeVisible()
    await expect(page.getByRole('button', { name: 'tax-codes' })).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'create-price-revision' })).toHaveCount(0)

    await gotoAndSettle(page, `/system-admin/price-revisions/${targetId}`)
    await page.waitForTimeout(1_500)
    await shot(page, testInfo, 'user-direct-detail')
    const toasts = await page.locator('.p-toast-message').allInnerTexts()
    await note(testInfo, '詳細直打ち URL・トースト・発生した admin API', { url: page.url(), toasts, apiStatuses })
    await expect(page.getByText('この画面を表示する権限がありません')).toBeVisible()
    await expect(page.getByRole('button', { name: 'provision-price-revision', exact: true })).toHaveCount(0)
    for (const s of apiStatuses) expect(s.status, `${s.url} は 401/403`).toBeGreaterThanOrEqual(401)
  } finally {
    await context.close()
  }
})

test('PR-09b 一般ユーザー（組織 e2e の ADMIN でもある）・他テナント ADMIN: 価格改定・税コード API を直接叩くと 401/403', async ({ browser }, testInfo) => {
  const state = readState()
  const targetId = state.revisionBId ?? state.draftAId ?? '00000000-0000-0000-0000-000000000000'
  for (const creds of [USER, OTHER_TENANT_ADMIN]) {
    const { context, page } = await newLoggedInPage(browser, creds)
    try {
      const me = await page.request.get(`${BE}/api/v1/users/me`)
      expect(me.status(), `${creds.email} のセッションが有効`).toBe(200)
      const calls: { name: string; status: number; body: string }[] = []
      const record = async (name: string, p: Promise<import('@playwright/test').APIResponse>) => {
        const r = await p
        calls.push({ name, status: r.status(), body: (await r.text()).slice(0, 200) })
      }
      const hdr = { 'Idempotency-Key': crypto.randomUUID(), 'Content-Type': 'application/json' }
      await record('GET list', page.request.get(PR_API))
      await record('GET detail', page.request.get(`${PR_API}/${targetId}`))
      await record('POST create', page.request.post(PR_API, {
        headers: hdr,
        data: { productKind: 'PLAN', productKey: PRODUCT_KEY, scopeKind: SCOPE_KIND, effectiveFrom: new Date(Date.now() + 7 * 86400_000).toISOString(), effectiveUntil: null, bands: [{ bandNo: 1, minMembers: 1, maxMembers: null, inputAmount: 1, taxBehavior: 'EXCLUSIVE', taxCode: TAX_CODE }] },
      }))
      await record('POST provision', page.request.post(`${PR_API}/${targetId}/provision`, { headers: hdr, data: { lockVersion: 0 } }))
      await record('POST activate', page.request.post(`${PR_API}/${targetId}/activate`, { headers: hdr, data: { lockVersion: 0 } }))
      await record('POST cancel', page.request.post(`${PR_API}/${targetId}/cancel`, { headers: hdr, data: { lockVersion: 0 } }))
      await record('GET tax-codes', page.request.get(TAX_API))
      await record('POST tax-codes', page.request.post(TAX_API, { headers: hdr, data: { code: 'E2E_DENY', displayName: 'x', rateBasisPoints: 1000, stripeTaxCode: 'txcd_10000000', validFrom: new Date().toISOString(), validUntil: null, enabled: true } }))
      await note(testInfo, `${creds.email} の直接 API 結果`, calls)
      for (const c of calls) expect([401, 403], `${creds.email} ${c.name}`).toContain(c.status)
    } finally {
      await context.close()
    }
  }
})

test('PR-09c 他テナント ADMIN: メニュー導線が無く、直打ちは権限なし表示になる', async ({ browser }, testInfo) => {
  const state = readState()
  const targetId = state.revisionBId ?? state.draftAId
  const { context, page } = await newLoggedInPage(browser, OTHER_TENANT_ADMIN)
  try {
    await gotoAndSettle(page, '/system-admin/price-revisions')
    await page.waitForTimeout(1_500)
    await shot(page, testInfo, 'tenant-admin-direct-list')
    await expect(page.getByText('この画面を表示する権限がありません')).toBeVisible()
    if (targetId) {
      await gotoAndSettle(page, `/system-admin/price-revisions/${targetId}`)
      await page.waitForTimeout(1_500)
      await shot(page, testInfo, 'tenant-admin-direct-detail')
      await expect(page.getByText('この画面を表示する権限がありません')).toBeVisible()
    }
  } finally {
    await context.close()
  }
})

// ===========================================================================
// PR-10 後始末: このテストが作った改定のみ画面で取り消す（共有DBの他人の改定には触れない）
// ===========================================================================
test('PR-10 後始末: 自テストが作成した改定に限り、取り消せるものを画面で取り消す', async ({ browser }, testInfo) => {
  const state = readState()
  // このテスト実行が作成した ID のみを後始末対象にする（一覧 API は現在の状態を知るためだけに使う）。
  const alreadyCancelled = new Set(state.cancelledIds ?? [])
  const ownIds = [...new Set([state.draftAId, state.revisionBId, ...(state.leftovers ?? []).map((l) => l.id)]
    .filter((id): id is string => !!id && !alreadyCancelled.has(id)))]
  const { context, page } = await newLoggedInPage(browser, ADMIN)
  try {
    const list = await page.request.get(`${PR_API}?size=100`)
    const items = ((await list.json()) as { data: { items: { id: string; status: string; scopeKind: string }[] } }).data.items
    const ownItems = items.filter((i) => ownIds.includes(i.id))
    for (const it of ownItems.filter((i) => ['DRAFT', 'READY', 'PROVISION_FAILED'].includes(i.status))) {
      const res = await cancelViaUi(page, it.id, testInfo, `cleanup-${it.id}`)
      expect(res.status()).toBe(200)
    }
    const after = await page.request.get(`${PR_API}?size=100`)
    const allRemaining = ((await after.json()) as { data: { items: { id: string; status: string; scopeKind: string; effectiveFrom: string }[] } }).data.items
    const ownRemaining = allRemaining.filter((i) => ownIds.includes(i.id))
    await note(testInfo, '自テストが作成した改定のうち残存するもの', ownRemaining)
    await note(testInfo, '一覧の全件数（他人・運用者の改定を含む。参考のみ）', allRemaining.length)
    expect(ownRemaining.filter((i) => ['DRAFT', 'READY', 'PROVISION_FAILED'].includes(i.status))).toHaveLength(0)
  } finally {
    await context.close()
  }
})
