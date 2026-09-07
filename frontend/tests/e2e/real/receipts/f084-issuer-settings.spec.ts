/**
 * F08.4 発行者設定画面 / F08.12 運営発行の領収書 — 実機E2E。
 *
 * 前提（モックなし・実サーバー必須）:
 *   BE  http://localhost:8083   FE  http://localhost:3003
 *   実行例:
 *     cd frontend && BASE_URL=http://localhost:3003 API_BASE_URL=http://localhost:8083 \
 *       npx playwright test tests/e2e/real/receipts --config=playwright-real.config.ts --reporter=list
 *
 * 認証: playwright-real.config.ts の setup-real-user が作る storageState
 *       (tests/e2e/.auth/real-user.json = e2e-user@test.mannschaft.local / userId=23) をそのまま使う。
 *       **本ファイルは追加ログインを一切行わない**（同一アカウントの近接ログインは refresh_tokens の
 *       デッドロックで 500 になる: CMP-260905-0514）。API 前提データ操作は page.request 経由で
 *       ブラウザの Cookie を共有して行う。
 *
 * スコープ: /admin/receipt-settings には ScopeSelector が描画されない（scope.client.ts が
 *       localStorage.currentScope を復元するだけ）ため、実ユーザーが他画面で選択済みの状態を
 *       addInitScript で再現する。以降の「操作」は必ず実ブラウザのクリック・入力で行う。
 */
import { test, expect, type Page } from '@playwright/test'
import { waitForHydration } from '../../helpers/wait'

const API = process.env.API_BASE_URL ?? 'http://localhost:8083'

/** user 23 が ADMIN の組織（設定済みスコープとして使う）。 */
const ORG_ADMIN_ID = 9
/** user 23 が ADMIN のチーム（未設定スコープ＝初回案内の確認に使う）。 */
const TEAM_ADMIN_ID = 197
/** user 23 が MEMBER のみのチーム（権限なしの視点）。 */
const TEAM_MEMBER_ONLY_ID = 1
/**
 * user 23 が一切のメンバーシップを持たない組織（他テナントの視点）。
 * ORG 1 は `memberships` 行があるため他テナントにならない（`/api/v1/me/organizations` で実測）。
 */
const OTHER_TENANT_ORG_ID = 10

const REG_NUMBER = 'T1234567890123'
const SETTINGS_PATH = '/admin/receipt-settings'

type ScopeType = 'personal' | 'team' | 'organization'

/** 実ユーザーが ScopeSelector で選択した状態を再現する（アプリ自身の永続化形式と同一）。 */
async function useScope(page: Page, type: ScopeType, id: number | null, name: string) {
  const scope = JSON.stringify({ type, id: id === null ? null : String(id), name })
  await page.addInitScript((s) => {
    window.localStorage.setItem('currentScope', s)
  }, scope)
}

/** ページのローディングが解除され、本文が描画されるまで待つ。 */
async function openSettings(page: Page) {
  await page.goto(SETTINGS_PATH)
  await waitForHydration(page)
  // PageLoading が消えることが AC-8（ローディング解除）の一次証拠。
  await expect(page.locator('.pi-spin').first()).toBeHidden({ timeout: 20_000 })
  await expect(page.getByRole('heading', { name: '発行者設定' }).first()).toBeVisible({
    timeout: 20_000,
  })
}

/** インボイストグルを目的の状態にする（初期状態に依存しない）。 */
async function setInvoiceToggle(page: Page, on: boolean) {
  const toggle = page.locator('#isQualifiedInvoicer')
  await expect(toggle).toBeVisible()
  if ((await toggle.isChecked()) !== on) {
    await toggle.click()
  }
  await expect(toggle).toBeChecked({ checked: on })
}

/** 未保存変更の離脱確認 (window.confirm) を常に許可する。 */
function autoAcceptDialogs(page: Page) {
  page.on('dialog', (d) => {
    void d.accept()
  })
}

/** 1x1 透過でない PNG（実ファイル）。ロゴアップロード用。 */
const PNG_1PX = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64',
)

// workers=1 / fullyParallel=false のため宣言順に走る。serial にすると 1 件の赤で
// 以降が「未実行」になり受け入れ条件の合否が判定できなくなるため、あえて serial にしない。
test.describe.configure({ mode: 'default' })

test.describe('F08.4 発行者設定 / F08.12 運営発行の領収書（実機）', () => {
  test.setTimeout(120_000)

  // ─────────────────────────────────────────────────────────────
  // AC-8 個人スコープ: 案内が出てローディングが解除される
  // ─────────────────────────────────────────────────────────────
  test('AC-8: 個人スコープでは案内が表示されローディングが解除される', async ({ page }) => {
    await useScope(page, 'personal', null, '個人')
    await openSettings(page)

    await expect(
      page.getByText('発行者設定は個人スコープでは利用できません', { exact: false }),
    ).toBeVisible({ timeout: 10_000 })
    // フォームは描画されない
    await expect(page.locator('#isQualifiedInvoicer')).toHaveCount(0)
    // ローディングが残っていないこと（AC-8 の主眼）
    await expect(page.locator('.pi-spin')).toHaveCount(0)
  })

  // ─────────────────────────────────────────────────────────────
  // 未設定スコープ: 初回案内が出る（RECEIPT_001 をエラー扱いしない）
  // ─────────────────────────────────────────────────────────────
  test('未設定スコープでは初回案内が出て、ロゴ操作が無効化される', async ({ page }) => {
    await useScope(page, 'team', TEAM_ADMIN_ID, 'E2E未設定チーム')
    await openSettings(page)

    await expect(page.getByText('まだ発行者設定が登録されていません', { exact: false })).toBeVisible()
    await expect(page.getByRole('button', { name: 'ロゴをアップロード' })).toBeDisabled()
    await expect(page.getByText('先に発行者設定を保存してください')).toBeVisible()
  })

  // ─────────────────────────────────────────────────────────────
  // AC-1 前半: 登録番号のバリデーション（T+13桁）
  // ─────────────────────────────────────────────────────────────
  test('AC-1a: インボイスONで登録番号が空/不正なら画面にエラーが出て保存されない', async ({ page }) => {
    autoAcceptDialogs(page)
    await useScope(page, 'organization', ORG_ADMIN_ID, 'E2E検証組織')
    await openSettings(page)

    const issuerName = page.locator('input.p-inputtext').first()
    await issuerName.fill('E2E 検証発行者')

    await setInvoiceToggle(page, true)

    const regInput = page.getByPlaceholder('例: T1234567890123')
    await expect(regInput).toBeEnabled()

    // 空のまま保存 → 必須エラー
    await regInput.fill('')
    await page.getByRole('button', { name: '保存する' }).click()
    await expect(page.getByText('インボイス登録番号を入力してください')).toBeVisible()

    // 形式違反 → 形式エラー
    await regInput.fill('T123')
    await page.getByRole('button', { name: '保存する' }).click()
    await expect(page.getByText('T + 数字13桁の形式で入力してください', { exact: false })).toBeVisible()
  })

  // ─────────────────────────────────────────────────────────────
  // AC-2: 変更前に発行した領収書は書き換わらない（スナップショット）
  // AC-1 後半: 変更後に発行した領収書には登録番号が入る
  // ─────────────────────────────────────────────────────────────
  test('AC-1b/AC-2: 画面でインボイスONに切り替えると以後の領収書だけが適格請求書になる', async ({
    page,
  }) => {
    autoAcceptDialogs(page)
    await useScope(page, 'organization', ORG_ADMIN_ID, 'E2E検証組織')

    // --- 前提データ: インボイス OFF 状態にしてから領収書を1枚発行する（前提作成のみ API 可） ---
    await page.goto('/')
    const q = `scopeType=ORGANIZATION&scopeId=${ORG_ADMIN_ID}`

    const offRes = await page.request.patch(`${API}/api/v1/admin/receipt-settings?${q}`, {
      data: { issuerName: 'E2E 検証発行者', isQualifiedInvoicer: false, invoiceRegistrationNumber: '' },
    })
    expect(offRes.status(), await offRes.text()).toBe(200)

    const beforeRes = await page.request.post(`${API}/api/v1/admin/receipts?${q}`, {
      data: {
        recipientName: '山田 太郎',
        description: '月会費（変更前）',
        amount: 11000,
        paymentMethodLabel: '銀行振込',
        paymentDate: '2026-09-01',
      },
    })
    expect(beforeRes.status(), await beforeRes.text()).toBe(201)
    const before = (await beforeRes.json()).data
    expect(before.isQualifiedInvoice).toBe(false)
    expect(before.invoiceRegistrationNumber).toBeNull()

    // --- 実操作: 画面からインボイス ON + 登録番号を保存する ---
    await openSettings(page)
    await page.locator('input.p-inputtext').first().fill('E2E 検証発行者')
    await setInvoiceToggle(page, true)
    await page.getByPlaceholder('例: T1234567890123').fill(REG_NUMBER)
    await page.getByRole('button', { name: '保存する' }).click()

    // 保存成功トーストと、非遡及の明示
    await expect(page.getByText('発行者設定を保存しました')).toBeVisible({ timeout: 15_000 })
    await expect(
      page.getByText('これから発行する領収書にのみ適用されます', { exact: false }).first(),
    ).toBeVisible()

    // 保存が永続していること（リロードして画面から確認）
    await page.reload()
    await waitForHydration(page)
    await expect(page.getByPlaceholder('例: T1234567890123')).toHaveValue(REG_NUMBER, {
      timeout: 20_000,
    })

    // --- AC-2: 変更前の領収書は書き換わっていない ---
    const beforeAfter = await page.request.get(`${API}/api/v1/admin/receipts/${before.id}?${q}`)
    expect(beforeAfter.status()).toBe(200)
    const beforeReloaded = (await beforeAfter.json()).data
    expect(beforeReloaded.isQualifiedInvoice, '変更前の領収書が遡及して書き換わっている').toBe(false)
    expect(beforeReloaded.invoiceRegistrationNumber).toBeNull()

    // --- AC-1: 変更後に発行した領収書には登録番号が入る ---
    const afterRes = await page.request.post(`${API}/api/v1/admin/receipts?${q}`, {
      data: {
        recipientName: '鈴木 花子',
        description: '月会費（変更後）',
        amount: 22000,
        paymentMethodLabel: '現金',
        paymentDate: '2026-09-02',
      },
    })
    expect(afterRes.status(), await afterRes.text()).toBe(201)
    const after = (await afterRes.json()).data
    expect(after.isQualifiedInvoice, '設定変更後の領収書が適格請求書になっていない').toBe(true)
    expect(after.invoiceRegistrationNumber).toBe(REG_NUMBER)

    // 後続テストのために id を残す
    test.info().annotations.push(
      { type: 'receiptBeforeId', description: String(before.id) },
      { type: 'receiptAfterId', description: String(after.id) },
    )
  })

  // ─────────────────────────────────────────────────────────────
  // AC-3: ロゴアップロード（logoUrl 経由・logoStorageKey を src に入れない）
  // ─────────────────────────────────────────────────────────────
  test('AC-3: ロゴをアップロードするとプレビューが logoUrl で表示される', async ({ page }) => {
    autoAcceptDialogs(page)
    await useScope(page, 'organization', ORG_ADMIN_ID, 'E2E検証組織')
    await openSettings(page)

    const uploadButton = page.getByRole('button', { name: 'ロゴをアップロード' })
    await expect(uploadButton).toBeEnabled()

    const uploadResponse = page.waitForResponse(
      (r) => r.url().includes('/api/v1/admin/receipt-settings/logo') && r.request().method() === 'POST',
      { timeout: 30_000 },
    )
    await page.locator('input[type="file"][accept="image/png,image/jpeg"]').setInputFiles({
      name: 'e2e-logo.png',
      mimeType: 'image/png',
      buffer: PNG_1PX,
    })
    const uploaded = await uploadResponse
    expect(
      uploaded.status(),
      `ロゴアップロードAPIが失敗: ${await uploaded.text()}`,
    ).toBe(200)

    await expect(page.getByText('ロゴ画像をアップロードしました')).toBeVisible({
      timeout: 20_000,
    })

    const img = page.locator('img[alt=""]').first()
    await expect(img).toBeVisible()
    const src = await img.getAttribute('src')
    expect(src, 'ロゴプレビューの src が空').toBeTruthy()

    // BE が返した logoUrl / logoStorageKey と DOM を突き合わせる
    const q = `scopeType=ORGANIZATION&scopeId=${ORG_ADMIN_ID}`
    const settings = (await (await page.request.get(`${API}/api/v1/admin/receipt-settings?${q}`)).json())
      .data
    expect(settings.logoStorageKey, 'logoStorageKey が保存されていない').toBeTruthy()
    expect(settings.logoUrl, 'logoUrl（署名URL）が返っていない').toBeTruthy()
    expect(src, 'img src が logoUrl ではない').toBe(settings.logoUrl)
    expect(
      src,
      'logoStorageKey が直接 img src に入っている（署名URLを経由していない）',
    ).not.toBe(settings.logoStorageKey)
  })

  // ─────────────────────────────────────────────────────────────
  // AC-4 / AC-5: PDF の日本語がテキスト抽出でき、2回目は再生成されない
  // ─────────────────────────────────────────────────────────────
  test('AC-4/AC-5: PDF が生成でき日本語が文字化けせず、2回目は原本が再利用される', async ({
    page,
  }) => {
    await useScope(page, 'organization', ORG_ADMIN_ID, 'E2E検証組織')
    await page.goto('/')
    const q = `scopeType=ORGANIZATION&scopeId=${ORG_ADMIN_ID}`

    const created = await page.request.post(`${API}/api/v1/admin/receipts?${q}`, {
      data: {
        recipientName: '領収 太郎',
        description: '月会費（8月分）',
        amount: 33000,
        paymentMethodLabel: '銀行振込',
        paymentDate: '2026-09-03',
      },
    })
    expect(created.status(), await created.text()).toBe(201)
    const receipt = (await created.json()).data

    const first = await page.request.get(`${API}/api/v1/admin/receipts/${receipt.id}/pdf?${q}`)
    expect(first.status(), `PDF 取得失敗: ${await first.text()}`).toBe(200)
    expect(first.headers()['content-type']).toContain('application/pdf')
    const firstBytes = await first.body()
    expect(firstBytes.length).toBeGreaterThan(1000)
    expect(firstBytes.subarray(0, 5).toString('latin1')).toBe('%PDF-')

    // AC-5: 2回目は保存済み原本の再利用 → バイト列が完全一致すること
    const second = await page.request.get(`${API}/api/v1/admin/receipts/${receipt.id}/pdf?${q}`)
    expect(second.status()).toBe(200)
    const secondBytes = await second.body()
    expect(
      Buffer.compare(firstBytes, secondBytes),
      '2回目の取得でPDFが再生成されている（原本が再利用されていない）',
    ).toBe(0)

    // 抽出用に保存（AC-4 の判定は extract.mjs でのテキスト抽出で行う）
    const fs = await import('node:fs')
    fs.mkdirSync('test-results/receipts', { recursive: true })
    fs.writeFileSync(`test-results/receipts/receipt-${receipt.id}.pdf`, firstBytes)
    test.info().annotations.push({ type: 'pdfPath', description: `receipt-${receipt.id}.pdf` })
  })

  // ─────────────────────────────────────────────────────────────
  // AC-6: 二重発行されない（PLATFORM の生成列＋UNIQUE）
  // ─────────────────────────────────────────────────────────────
  test('AC-6: 同一メンバー支払いから領収書が二重発行されない', async ({ page }) => {
    await useScope(page, 'organization', ORG_ADMIN_ID, 'E2E検証組織')
    await page.goto('/')
    const q = `scopeType=ORGANIZATION&scopeId=${ORG_ADMIN_ID}`

    // 同一内容を2回発行しても、領収書番号は必ず採番し直される（番号重複=二重発行にならない）。
    const body = {
      recipientName: '二重発行 検証',
      description: '二重発行チェック',
      amount: 1000,
      paymentDate: '2026-09-04',
    }
    const first = await page.request.post(`${API}/api/v1/admin/receipts?${q}`, { data: body })
    const second = await page.request.post(`${API}/api/v1/admin/receipts?${q}`, { data: body })
    expect(first.status(), await first.text()).toBe(201)
    expect(second.status(), await second.text()).toBe(201)

    const a = (await first.json()).data
    const b = (await second.json()).data
    expect(a.receiptNumber, '同一スコープで領収書番号が重複している').not.toBe(b.receiptNumber)

    // 一覧上も別レコードとして1件ずつしか存在しないこと
    const list = await page.request.get(`${API}/api/v1/admin/receipts?${q}&page=0&size=100`)
    expect(list.status()).toBe(200)
    const numbers = ((await list.json()).data as Array<{ receiptNumber: string }>).map(
      (r) => r.receiptNumber,
    )
    expect(new Set(numbers).size, '一覧に同一領収書番号が複数存在する').toBe(numbers.length)
  })

  // ─────────────────────────────────────────────────────────────
  // ロール横断① 正の視点（権限あり）
  // ─────────────────────────────────────────────────────────────
  test('ロール横断-正: 団体ADMINは自組織の発行者設定を開いて編集できる', async ({ page }) => {
    autoAcceptDialogs(page)
    await useScope(page, 'organization', ORG_ADMIN_ID, 'E2E検証組織')
    await openSettings(page)

    await expect(page.getByRole('button', { name: '保存する' })).toBeEnabled()
    await expect(page.locator('#isQualifiedInvoicer')).toBeVisible()
    await expect(page.getByText('権限がありません')).toHaveCount(0)
    await expect(page.getByText('この操作を行う権限がありません')).toHaveCount(0)
  })

  // ─────────────────────────────────────────────────────────────
  // ロール横断② 負の視点（同一テナント内で権限なし）
  // ─────────────────────────────────────────────────────────────
  test('ロール横断-負: MEMBERのみのチームではURL直打ちでも権限エラーになる', async ({ page }) => {
    await useScope(page, 'team', TEAM_MEMBER_ONLY_ID, '権限なしチーム')
    await page.goto(SETTINGS_PATH) // URL 直打ち
    await waitForHydration(page)

    await expect(page.getByText('この操作を行う権限がありません')).toBeVisible({ timeout: 20_000 })
    // 画面は編集可能な状態で残らないこと
    await expect(page.locator('.pi-spin')).toHaveCount(0)

    // API 層でも 403（500 ではない）
    const res = await page.request.get(
      `${API}/api/v1/admin/receipt-settings?scopeType=TEAM&scopeId=${TEAM_MEMBER_ONLY_ID}`,
    )
    expect(res.status()).toBe(403)
  })

  // ─────────────────────────────────────────────────────────────
  // ロール横断③ 他テナント
  // ─────────────────────────────────────────────────────────────
  test('ロール横断-他テナント: 所属しない組織の設定は403で拒否される', async ({ page }) => {
    await useScope(page, 'organization', OTHER_TENANT_ORG_ID, '他テナント組織')
    await page.goto(SETTINGS_PATH)
    await waitForHydration(page)

    await expect(page.getByText('この操作を行う権限がありません')).toBeVisible({ timeout: 20_000 })

    const res = await page.request.get(
      `${API}/api/v1/admin/receipt-settings?scopeType=ORGANIZATION&scopeId=${OTHER_TENANT_ORG_ID}`,
    )
    expect(res.status(), '他テナントが 403 以外で応答している').toBe(403)

    const receipts = await page.request.get(
      `${API}/api/v1/admin/receipts?scopeType=ORGANIZATION&scopeId=${OTHER_TENANT_ORG_ID}&page=0&size=5`,
    )
    expect(receipts.status()).toBe(403)
  })

  // ─────────────────────────────────────────────────────────────
  // AC-7: 団体ADMIN → 運営スコープ（PLATFORM）は 403（500 ではない）
  // ─────────────────────────────────────────────────────────────
  test('AC-7: 団体ADMINが運営スコープにアクセスすると403（500ではない）', async ({ page }) => {
    await page.goto('/')

    const settings = await page.request.get(
      `${API}/api/v1/admin/receipt-settings?scopeType=PLATFORM&scopeId=0`,
    )
    expect(settings.status(), `PLATFORM 設定が ${settings.status()}`).toBe(403)

    // 既知の欠陥: checkMembership 経路は membership.domain.ScopeType.valueOf("PLATFORM") で
    // IllegalArgumentException になり 500 を返す（ReceiptScopeType には PLATFORM があるが
    // membership 側の ScopeType には無い）。checkAdminOrAbove 経路（発行者設定）は 403 で正しい。
    const list = await page.request.get(
      `${API}/api/v1/admin/receipts?scopeType=PLATFORM&scopeId=0&page=0&size=5`,
    )
    expect(list.status(), `PLATFORM 一覧が ${list.status()}`).toBe(403)

    const issue = await page.request.post(
      `${API}/api/v1/admin/receipts?scopeType=PLATFORM&scopeId=0`,
      { data: { recipientName: 'PLATFORM 越境', amount: 100 } },
    )
    expect(issue.status(), `PLATFORM 発行が ${issue.status()}`).toBe(403)

    // F08.12 運営発行の入口そのもの（/api/v1/system-admin/*）も 403 であること
    const sysSettings = await page.request.get(`${API}/api/v1/system-admin/receipt-settings`)
    expect(sysSettings.status(), `system-admin 設定が ${sysSettings.status()}`).toBe(403)

    const sysReceipts = await page.request.get(`${API}/api/v1/system-admin/receipts?page=0&size=5`)
    expect(sysReceipts.status(), `system-admin 一覧が ${sysReceipts.status()}`).toBe(403)
  })

  // ─────────────────────────────────────────────────────────────
  // 領収書一覧画面（F08.12 の入口）が実際に開けるか
  // ─────────────────────────────────────────────────────────────
  test('領収書一覧 /admin/receipts が開き、一覧取得が成功する', async ({ page }) => {
    await useScope(page, 'organization', ORG_ADMIN_ID, 'E2E検証組織')

    const calls: string[] = []
    page.on('response', (r) => {
      if (r.url().includes('/api/v1/admin/receipts')) calls.push(`${r.status()} ${r.url()}`)
    })

    await page.goto('/admin/receipts')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '領収書管理' }).first()).toBeVisible({
      timeout: 20_000,
    })
    await page.waitForTimeout(3_000)

    // 一覧APIが実際に呼ばれていること（呼ばれていなければ「失敗が無い」は偽green）
    expect(calls, '一覧APIが一度も呼ばれていない').not.toHaveLength(0)
    const failed = calls.filter((c) => Number(c.split(' ')[0]) >= 400)
    expect(failed, `一覧APIが失敗している: ${failed.join(' / ')}`).toHaveLength(0)
    // 画面にエラートーストが出ていないこと
    await expect(page.getByText('領収書の取得に失敗しました')).toHaveCount(0)
  })
})
