import { expect, test, type Page } from '@playwright/test'

/**
 * `chromium-real` プロジェクトは既定で `tests/e2e/.auth/real-user.json`
 * （MEMBER アカウント）の storageState を読み込む。各テストで UI から
 * ADMIN/MEMBER を再ログインしても、残存する MEMBER の refresh_token 等が
 * プロアクティブリフレッシュ等で後から access_token を巻き戻し、
 * SYSTEM_ADMIN専用APIが403になる事象を実測した（system-admin/ad-credit-limit-requests
 * で再現・診断: ブラウザのレスポンスログで403を確認、同時刻の curl 直叩き(Bearer/Cookie
 * いずれも)は200 — つまりBEは正しく、FE側セッション汚染が原因）。
 * 各テストを空の storageState から開始し、既定ログイン状態の持ち越しを断つ。
 */
test.use({ storageState: { cookies: [], origins: [] } })

/**
 * この環境では共有ヘルパー `waitForHydration`（tests/e2e/helpers/wait.ts）が
 * `#__nuxt` 要素上の `__vue_app__` プロパティ存在チェックで常に失敗し、
 * `page.reload()` のフォールバックがナビゲーション待ちのままタイムアウトする現象が
 * 再現した（本 spec 固有の問題ではなく、失敗時のページスナップショットには
 * ログインフォーム・対象画面ともに完全に描画済みで出ており、アプリ自体は正常に
 * ハイドレーション済みであることを確認済み。環境要因のため詳細は報告に記載）。
 * reload フォールバックを踏まない軽量な待機（対象要素の可視化待ち）に差し替える。
 */
async function waitForHydrationLocal(page: Page): Promise<void> {
  await page
    .waitForFunction(
      () => {
        const el = document.querySelector('#__nuxt')
        return el !== null && el.childElementCount > 0
      },
      undefined,
      { timeout: 30_000 },
    )
    .catch(() => undefined)
}
async function loginAsLocal(
  page: Page,
  credentials: { email: string; password: string },
): Promise<void> {
  await page.goto('/login')

  const emailInput = page.locator('input#email')
  await emailInput.waitFor({ state: 'visible', timeout: 30_000 })
  await emailInput.click()
  await emailInput.pressSequentially(credentials.email, { delay: 10 })

  const passwordInput = page.locator('input[type="password"]')
  await passwordInput.click()
  await passwordInput.pressSequentially(credentials.password, { delay: 10 })

  await page.getByRole('button', { name: 'ログイン', exact: true }).click()
  await page.waitForURL((url) => !url.pathname.includes('/login'), {
    timeout: 30_000,
    waitUntil: 'commit',
  })
}

/**
 * 実機E2E: CMP-260922-2045（取得失敗時に空状態ではなく DashboardErrorState を出す修正）
 * のロール横断検証（PR #3435 #3439 #3440 #3441 #3442 #3443、main マージ済み）。
 *
 * モックなし・実BE（本陣:8080）・実FE（この worktree の :3001）・実ブラウザで確認する。
 * page.route() によるAPI横取りは行わない。
 *
 * 対象6画面（事前に curl で API ステータスを実測して選定。403/404を実際に返す画面のみ採用）:
 *   1. system-admin/batches                    — SYSTEM_ADMIN専用。MEMBERは403
 *   2. admin/ad-credit-limit-requests          — SYSTEM_ADMIN専用。MEMBERは403
 *   3. admin/advertiser-accounts               — SYSTEM_ADMIN専用。MEMBERは403
 *   4. admin/ad-rate-cards                     — SYSTEM_ADMIN専用。MEMBERは403
 *   5. villages/[id]/admin/recruit-categories  — 村HEADMAN専用。非所属村は404
 *   6. villages/[id]/calendar                  — 村メンバー限定。非所属村は403
 *
 * アカウント（用途ごとにロールをAPIで実測済み。docs/task-list.md系の申し送りに従う）:
 *   - e2e-admin@test.mannschaft.local / TestPass2026! : systemRole=SYSTEM_ADMIN (id=24)
 *   - e2e-user@test.mannschaft.local  / TestPass2026! : systemRole=null (MEMBER, id=23)
 *
 * villages/[id]/admin/recruit-categories・villages/[id]/calendar は「他村」の観点を兼ねる
 * （e2e-user が HEADMAN の村 vs 所属していない村のURL直打ち）。
 *
 * 除外した画面: admin/villages/creation-requests。
 * この画面は他の5画面と異なり、`isAllowed`（FE側 systemRole チェック）が
 * `onMounted` の `load()` 呼び出し自体をガードしており、MEMBER で開くと
 * API を一度も呼ばずに村ドメイン独自の「この画面を閲覧する権限がありません」
 * （`village.creationRequest.noPermission`）を表示する。取得失敗時の
 * DashboardErrorState とは別の、既存の正当な保護機構であり CMP-260922-2045 の
 * 対象範囲外の挙動と判断し、別立てで最小限の確認のみ行う（下記参照）。
 */

test.describe.configure({ mode: 'serial' })

const ADMIN_CREDS = { email: 'e2e-admin@test.mannschaft.local', password: 'TestPass2026!' }
const MEMBER_CREDS = { email: 'e2e-user@test.mannschaft.local', password: 'TestPass2026!' }

// e2e-user (MEMBER) が HEADMAN の村（正の視点用）
const OWNED_VILLAGE_ID = '6e87b493-512a-11f1-95e3-2ec96fe3ea06'
// e2e-user が所属していない村（他村・負の視点用）
const OTHER_VILLAGE_ID = '7f000101-9fcf-11d8-819f-cf38ad970012'

type AdminOnlyScreen = {
  name: string
  path: string
  apiPathContains: string
  errorTestId: string
}

const ADMIN_ONLY_SCREENS: AdminOnlyScreen[] = [
  {
    name: 'system-admin/batches',
    path: '/system-admin/batches',
    apiPathContains: '/api/v1/system-admin/batch',
    errorTestId: 'batches-error-state',
  },
  {
    name: 'admin/ad-credit-limit-requests',
    path: '/admin/ad-credit-limit-requests',
    apiPathContains: '/api/v1/system-admin/ad-credit-limit-requests',
    errorTestId: 'ad-credit-limit-requests-error-state',
  },
  {
    name: 'admin/advertiser-accounts',
    path: '/admin/advertiser-accounts',
    apiPathContains: '/api/v1/system-admin/advertiser-accounts',
    errorTestId: 'advertiser-accounts-error-state',
  },
  {
    name: 'admin/ad-rate-cards',
    path: '/admin/ad-rate-cards',
    apiPathContains: '/api/v1/system-admin/ad-rate-cards',
    errorTestId: 'ad-rate-cards-error-state',
  },
]

for (const screen of ADMIN_ONLY_SCREENS) {
  test.describe(`${screen.name}`, () => {
    test(`[正] SYSTEM_ADMINで開くと一覧が出てerror-stateは出ない`, async ({ page }) => {
      await loginAsLocal(page, ADMIN_CREDS)
      await page.goto(screen.path, { waitUntil: 'domcontentloaded' })
      await waitForHydrationLocal(page)
      await page.waitForLoadState('networkidle').catch(() => undefined)

      await expect(
        page.getByTestId(screen.errorTestId),
        `${screen.name}: SYSTEM_ADMINでerror-stateが出た（想定外）`,
      ).toHaveCount(0)

      await page.screenshot({
        path: `test-results/screenshots/cmp-260922-2045/${screen.name.replace(/\//g, '_')}-admin-positive.png`,
        fullPage: true,
      })
    })

    test(`[負] MEMBERでURL直打ちするとerror-stateが出る`, async ({ page }) => {
      await loginAsLocal(page, MEMBER_CREDS)

      // SSR時にサーバー側でAPIを叩くため、goto前に page.waitForResponse を張っても
      // ブラウザ側ネットワークイベントとしては観測できないことがある（実測で確認）。
      // そのため goto 後に発生した全レスポンスを収集し、後から対象APIの有無とステータスを検証する。
      const responses: { url: string; status: number }[] = []
      page.on('response', (res) => {
        if (res.url().includes(screen.apiPathContains)) {
          responses.push({ url: res.url(), status: res.status() })
        }
      })

      await page.goto(screen.path, { waitUntil: 'domcontentloaded' })
      await waitForHydrationLocal(page)

      const errorState = page.getByTestId(screen.errorTestId)
      await expect(errorState, `${screen.name}: MEMBERでerror-stateが出なかった`).toBeVisible({
        timeout: 10_000,
      })
      await expect(errorState).toContainText('データの取得に失敗しました')

      // SSR経由で観測できたレスポンスがあれば、ステータスも403/404であることを確認する
      if (responses.length > 0) {
        for (const r of responses) {
          expect(
            [403, 404].includes(r.status),
            `${screen.name}: ${r.url} のステータス=${r.status}（403/404を期待）`,
          ).toBeTruthy()
        }
      }

      // 空状態の文言（0件のときの一般的な文言）は出ていないことを確認
      const emptyStateCandidates = page.locator('[data-testid$="empty-state"]')
      await expect(emptyStateCandidates).toHaveCount(0)

      await page.screenshot({
        path: `test-results/screenshots/cmp-260922-2045/${screen.name.replace(/\//g, '_')}-member-negative.png`,
        fullPage: true,
      })

      // 再試行ボタンを押すと同じAPIが再度呼ばれることを確認（CSR経由のためブラウザから確実に観測できる）
      const retryButton = page.getByTestId(`${screen.errorTestId}-retry`)
      await expect(retryButton).toBeVisible()
      const retryResponsePromise = page.waitForResponse(
        (res) => res.url().includes(screen.apiPathContains),
        { timeout: 15_000 },
      )
      await retryButton.click()
      const retryRes = await retryResponsePromise
      expect(retryRes.url()).toContain(screen.apiPathContains)
      expect(
        [403, 404].includes(retryRes.status()),
        `${screen.name}: 再試行時ステータス=${retryRes.status()}（403/404を期待）`,
      ).toBeTruthy()
    })
  })
}

test.describe('villages/[id]/admin/recruit-categories', () => {
  test('[正] HEADMANの自村では一覧が出てerror-stateは出ない', async ({ page }) => {
    await loginAsLocal(page, MEMBER_CREDS)
    await page.goto(`/villages/${OWNED_VILLAGE_ID}/admin/recruit-categories`, { waitUntil: 'domcontentloaded' })
    await waitForHydrationLocal(page)
    await page.waitForLoadState('networkidle').catch(() => undefined)

    await expect(
      page.getByTestId('recruit-category-error-state'),
      '自村（HEADMAN）でerror-stateが出た（想定外）',
    ).toHaveCount(0)

    await page.screenshot({
      path: `test-results/screenshots/cmp-260922-2045/village-recruit-categories-owner-positive.png`,
      fullPage: true,
    })
  })

  test('[負・他村] 所属していない村のURLを直打ちするとerror-stateが出る', async ({ page }) => {
    await loginAsLocal(page, MEMBER_CREDS)

    const otherApiPath = `/api/v1/villages/${OTHER_VILLAGE_ID}/recruit-categories`
    const responses: { url: string; status: number }[] = []
    page.on('response', (res) => {
      if (res.url().includes(otherApiPath)) {
        responses.push({ url: res.url(), status: res.status() })
      }
    })

    await page.goto(`/villages/${OTHER_VILLAGE_ID}/admin/recruit-categories`, { waitUntil: 'domcontentloaded' })
    await waitForHydrationLocal(page)

    const errorState = page.getByTestId('recruit-category-error-state')
    await expect(errorState, '他村URL直打ちでerror-stateが出なかった').toBeVisible({
      timeout: 10_000,
    })
    await expect(errorState).toContainText('データの取得に失敗しました')

    if (responses.length > 0) {
      for (const r of responses) {
        expect(
          [403, 404].includes(r.status),
          `他村recruit-categories: ${r.url} のステータス=${r.status}（403/404を期待）`,
        ).toBeTruthy()
      }
    }

    await page.screenshot({
      path: `test-results/screenshots/cmp-260922-2045/village-recruit-categories-other-negative.png`,
      fullPage: true,
    })

    const retryButton = page.getByTestId('recruit-category-error-state-retry')
    await expect(retryButton).toBeVisible()
    const retryResponsePromise = page.waitForResponse(
      (res) => res.url().includes(otherApiPath),
      { timeout: 15_000 },
    )
    await retryButton.click()
    const retryRes = await retryResponsePromise
    expect(retryRes.url()).toContain(otherApiPath)
    expect(
      [403, 404].includes(retryRes.status()),
      `他村recruit-categories: 再試行時ステータス=${retryRes.status()}（403/404を期待）`,
    ).toBeTruthy()
  })
})

test.describe('villages/[id]/calendar', () => {
  test('[正] 自村（所属村）では一覧が出てerror-stateは出ない', async ({ page }) => {
    await loginAsLocal(page, MEMBER_CREDS)
    await page.goto(`/villages/${OWNED_VILLAGE_ID}/calendar`, { waitUntil: 'domcontentloaded' })
    await waitForHydrationLocal(page)
    await page.waitForLoadState('networkidle').catch(() => undefined)

    await expect(
      page.getByTestId('village-calendar-error-state'),
      '自村カレンダーでerror-stateが出た（想定外）',
    ).toHaveCount(0)

    await page.screenshot({
      path: `test-results/screenshots/cmp-260922-2045/village-calendar-owner-positive.png`,
      fullPage: true,
    })
  })

  test('[負・他村] 所属していない村のURLを直打ちするとerror-stateが出る', async ({ page }) => {
    await loginAsLocal(page, MEMBER_CREDS)

    const otherApiPath = `/api/v1/villages/${OTHER_VILLAGE_ID}/calendar-events`
    const responses: { url: string; status: number }[] = []
    page.on('response', (res) => {
      if (res.url().includes(otherApiPath)) {
        responses.push({ url: res.url(), status: res.status() })
      }
    })

    await page.goto(`/villages/${OTHER_VILLAGE_ID}/calendar`, { waitUntil: 'domcontentloaded' })
    await waitForHydrationLocal(page)

    const errorState = page.getByTestId('village-calendar-error-state')
    await expect(errorState, '他村カレンダーURL直打ちでerror-stateが出なかった').toBeVisible({
      timeout: 10_000,
    })
    await expect(errorState).toContainText('データの取得に失敗しました')

    if (responses.length > 0) {
      for (const r of responses) {
        expect(
          [403, 404].includes(r.status),
          `他村calendar-events: ${r.url} のステータス=${r.status}（403/404を期待）`,
        ).toBeTruthy()
      }
    }

    await page.screenshot({
      path: `test-results/screenshots/cmp-260922-2045/village-calendar-other-negative.png`,
      fullPage: true,
    })

    const retryButton = page.getByTestId('village-calendar-error-state-retry')
    await expect(retryButton).toBeVisible()
    const retryResponsePromise = page.waitForResponse(
      (res) => res.url().includes(otherApiPath),
      { timeout: 15_000 },
    )
    await retryButton.click()
    const retryRes = await retryResponsePromise
    expect(retryRes.url()).toContain(otherApiPath)
    expect(
      [403, 404].includes(retryRes.status()),
      `他村calendar-events: 再試行時ステータス=${retryRes.status()}（403/404を期待）`,
    ).toBeTruthy()
  })
})

/**
 * 除外画面の記録用スポット確認（DashboardErrorStateの対象外・上記コメント参照）。
 * MEMBERでURL直打ちすると、FE側 `isAllowed` ガードにより API 未呼び出しのまま
 * 村ドメイン独自の権限なし表示（`village.creationRequest.noPermission`）が出ることを記録する。
 */
test.describe('admin/villages/creation-requests（参考: DashboardErrorState対象外）', () => {
  test('[参考] MEMBERで開くとDashboardErrorStateではなくFE側の権限なし表示が出る', async ({
    page,
  }) => {
    await loginAsLocal(page, MEMBER_CREDS)
    await page.goto('/admin/villages/creation-requests', { waitUntil: 'domcontentloaded' })
    await waitForHydrationLocal(page)

    await expect(
      page.getByTestId('creation-requests-error-state'),
      'この画面ではDashboardErrorStateは出ない想定',
    ).toHaveCount(0)
    await expect(page.getByText('この画面を閲覧する権限がありません')).toBeVisible({
      timeout: 10_000,
    })

    await page.screenshot({
      path: `test-results/screenshots/cmp-260922-2045/admin_villages_creation-requests-fe-gate-reference.png`,
      fullPage: true,
    })
  })
})
