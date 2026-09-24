import { test, expect, type Page } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { mockFeatureFlags } from '../helpers/feature-flags'
import {
  TEAM_ID,
  SCHEDULE_ID,
  ADMIN_USER_ID,
  MEMBER_USER_ID,
  MEMBER2_USER_ID,
  SLOT_ID_1,
  setupAdminAuth,
  setupMemberAuth,
  mockCatchAllApis,
  mockTeamMembersApi,
  mockSlots,
  buildSlot,
} from './_helpers'

/**
 * F03.5 シフト交代募集「一往復」 E2E（SWAP-001〜005）。
 *
 * <p>方式②（手挙げ→依頼者が選ぶ）撤退後も、残る方式①「先着承諾」が
 * 依頼→表示→承諾まで動くことを固定する。</p>
 *
 * <ul>
 *   <li>SWAP-001: メンバーが /my/shift から交代依頼を作成し POST が実際に飛ぶ</li>
 *   <li>SWAP-002: ADMIN が /teams/{slug}/shifts で PENDING 依頼を「保留中」として見る</li>
 *   <li>SWAP-003: 承認ボタンで accept API が飛び、再取得後に「承認」へ変わる</li>
 *   <li>SWAP-004: 一覧 500 を握りつぶさず取得失敗が利用者に伝わる</li>
 *   <li>SWAP-005: 一覧 403（非ADMIN相当）では承認導線が現れない</li>
 * </ul>
 *
 * <p>設計上の注意（旧 CHANGE-005 の反省）: 要素が見つからない場合に
 * 「充足とみなす」分岐は書かない。呼ばれるべき API はフラグで実呼び出しを断言する。</p>
 */

const TEAM_SLUG = 'e2e-swap-team'
const SWAP_REQUEST_ID = 9101
const TEAM_SHIFTS_URL = `/teams/${TEAM_SLUG}/shifts`

/** ブラウザのタイムゾーン（Asia/Tokyo・playwright.config.ts）に揃えた「今日」の YYYY-MM-DD。 */
function todayKeyInTokyo(): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Tokyo' }).format(new Date())
}

/** ShiftSwapList コンポーネントのルート要素（h3 を直下に持つ div）。 */
function swapListRoot(page: Page) {
  return page.locator('div:has(> h3:text-is("シフト交換リクエスト"))')
}

/** SwapRequestResponse の雛形（BE DTO 準拠）。 */
function buildSwapRequest(
  overrides: { status?: string; accepterId?: number | null } = {},
) {
  return {
    id: SWAP_REQUEST_ID,
    slotId: SLOT_ID_1,
    requesterId: MEMBER_USER_ID,
    accepterId: overrides.accepterId ?? ADMIN_USER_ID,
    status: overrides.status ?? 'PENDING',
    reason: 'E2E: 交代をお願いします',
    createdAt: '2026-05-01T00:00:00Z',
    updatedAt: '2026-05-01T00:00:00Z',
  }
}

/**
 * インボックス件数サマリのモック。
 *
 * catch-all の `{ data: [] }` では `data.byState` が undefined になり、レイアウト常駐の
 * バッジが TypeError を投げ、画面右下にエラー報告パネルが常時被さって
 * 右下のボタン（交代依頼など）がクリックできなくなる。テスト対象と無関係な妨害を消す。
 */
async function mockInboxSummary(page: Page): Promise<void> {
  await page.route('**/api/v1/inbox/summary', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: { byState: {}, byPriority: {}, bySourceType: {} },
      }),
    })
  })
}

/** チーム詳細（slug → numericId 解決）のモック。ShiftSwapList は数値 teamId を要求する。 */
async function mockTeamDetail(page: Page): Promise<void> {
  await page.route(`**/api/v1/teams/${TEAM_SLUG}`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: {
          id: TEAM_SLUG,
          slug: TEAM_SLUG,
          numericId: TEAM_ID,
          basicInfo: { name: 'E2E交代チーム', nickname1: null },
          timezone: 'Asia/Tokyo',
        },
      }),
    })
  })
}

test.describe('SWAP-001〜005: シフト交代募集の一往復（依頼→承諾）', () => {
  test('SWAP-001: メンバーが交代依頼を作成すると POST /shifts/swap-requests が飛ぶ', async ({
    page,
  }) => {
    // /my/shift・チームシェル配下は dev サーバーの初回 SSR/最適化が重く、
    // 既定 60 秒ではハイドレーション待ちだけで枯れることがある（実測）。
    test.setTimeout(150_000)
    await setupMemberAuth(page)
    await mockCatchAllApis(page)
    await mockFeatureFlags(page)
    await mockInboxSummary(page)
    await mockTeamMembersApi(page)
    await mockSlots(page, [buildSlot(SLOT_ID_1)])

    const today = todayKeyInTokyo()
    await page.route('**/api/v1/shifts/my/confirmed-slots**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: [
            {
              slotId: SLOT_ID_1,
              slotDate: today,
              startTime: '09:00:00',
              endTime: '17:00:00',
              teamId: TEAM_ID,
              teamName: 'E2E交代チーム',
              scheduleId: SCHEDULE_ID,
              scheduleName: 'E2Eテスト用シフトスケジュール',
              positionName: null,
            },
          ],
        }),
      })
    })

    // 交代依頼の作成 API（実呼び出しをフラグとボディで検証する）
    let createCalled = false
    let createdBody: Record<string, unknown> = {}
    await page.route('**/api/v1/shifts/swap-requests**', async (route) => {
      if (route.request().method() === 'POST') {
        createCalled = true
        createdBody = JSON.parse(route.request().postData() ?? '{}')
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify({ data: buildSwapRequest() }),
        })
      } else {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: [] }),
        })
      }
    })

    await page.goto('/my/shift')
    await waitForHydration(page)

    // 「今日」を押して詳細パネルを開く（本日のスロットをモック済み）
    const todayBtn = page.getByRole('button', { name: '今日' })
    await expect(todayBtn).toBeVisible({ timeout: 15_000 })
    await todayBtn.click()

    // 交代依頼ボタン（i18n shift.swap.create）
    const swapBtn = page.getByRole('button', { name: '交代依頼を作成' }).first()
    await expect(swapBtn).toBeVisible({ timeout: 10_000 })
    await swapBtn.click()

    // ダイアログが開く
    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible({ timeout: 10_000 })
    await expect(dialog.getByText('送信先')).toBeVisible()

    // 既定モード SPECIFIC。交代候補者としてメンバーを1名選ぶ
    const memberLabel = dialog.locator('label', { hasText: 'e2e_member2' }).first()
    await expect(memberLabel).toBeVisible({ timeout: 10_000 })
    await memberLabel.click()
    await expect(dialog.getByText('1名を選択中')).toBeVisible()

    // 提出（i18n shift.action.submit）
    const submitBtn = dialog.getByRole('button', { name: '提出' })
    await expect(submitBtn).toBeEnabled()
    await submitBtn.click()

    await expect.poll(() => createCalled, { timeout: 10_000 }).toBe(true)
    expect(createdBody.slotId).toBe(SLOT_ID_1)
    expect(createdBody.targetUserIds).toEqual([MEMBER2_USER_ID])
  })

  test('SWAP-002: ADMIN のシフト交換タブに PENDING 依頼が「保留中」として並ぶ', async ({
    page,
  }) => {
    // /my/shift・チームシェル配下は dev サーバーの初回 SSR/最適化が重く、
    // 既定 60 秒ではハイドレーション待ちだけで枯れることがある（実測）。
    test.setTimeout(150_000)
    await setupAdminAuth(page)
    await mockCatchAllApis(page)
    await mockFeatureFlags(page)
    await mockInboxSummary(page)
    await mockTeamDetail(page)

    let listCalled = false
    await page.route('**/api/v1/shifts/swap-requests?**', async (route) => {
      listCalled = true
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [buildSwapRequest({ status: 'PENDING' })] }),
      })
    })

    await page.goto(TEAM_SHIFTS_URL)
    await waitForHydration(page)

    await page.getByRole('tab', { name: 'シフト交換' }).click()

    const list = swapListRoot(page)
    await expect(list.getByText(`申請者 #${MEMBER_USER_ID}`)).toBeVisible({ timeout: 15_000 })
    await expect(list.getByText(`スロット #${SLOT_ID_1}`)).toBeVisible()
    await expect(list.getByText('承諾待ち')).toBeVisible()
    expect(listCalled).toBe(true)
  })

  // 注: 承諾ボタンの表示条件は「PENDING かつ申請者本人でないこと」であり、BE の
  // ShiftSwapService#acceptSwapRequest の認可条件に揃えている（CMP-260908-2116）。
  // 旧実装は「ログインユーザーが accepterId であること」を条件にしていたが、accepterId は
  // 承諾した瞬間に初めて確定するため、承諾前は常に null で誰にも押せなかった。
  test('SWAP-003: 承諾ボタンで accept API が飛び、再取得後に状態が進む', async ({ page }) => {
    // /my/shift・チームシェル配下は dev サーバーの初回 SSR/最適化が重く、
    // 既定 60 秒ではハイドレーション待ちだけで枯れることがある（実測）。
    test.setTimeout(150_000)
    await setupAdminAuth(page)
    await mockCatchAllApis(page)
    await mockFeatureFlags(page)
    await mockInboxSummary(page)
    await mockTeamDetail(page)

    let currentStatus = 'PENDING'
    await page.route('**/api/v1/shifts/swap-requests?**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [buildSwapRequest({ status: currentStatus })] }),
      })
    })

    let acceptCalled = false
    await page.route(
      `**/api/v1/shifts/swap-requests/${SWAP_REQUEST_ID}/accept`,
      async (route) => {
        expect(route.request().method()).toBe('POST')
        acceptCalled = true
        currentStatus = 'ACCEPTED'
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: buildSwapRequest({ status: 'ACCEPTED' }) }),
        })
      },
    )

    await page.goto(TEAM_SHIFTS_URL)
    await waitForHydration(page)
    await page.getByRole('tab', { name: 'シフト交換' }).click()

    const list = swapListRoot(page)
    await expect(list.getByText('承諾待ち')).toBeVisible({ timeout: 15_000 })

    const acceptBtn = list.locator(`[data-testid="swap-accept-${SWAP_REQUEST_ID}"]`)
    await expect(acceptBtn).toBeVisible()
    await acceptBtn.click()

    await expect.poll(() => acceptCalled, { timeout: 10_000 }).toBe(true)
    await expect(list.getByText('承認待ち')).toBeVisible({ timeout: 10_000 })
    await expect(list.getByText('承諾待ち')).toHaveCount(0)
  })

  test('SWAP-004: 一覧取得が 500 のとき取得失敗が利用者に伝わる（空表示に潰さない）', async ({
    page,
  }) => {
    // /my/shift・チームシェル配下は dev サーバーの初回 SSR/最適化が重く、
    // 既定 60 秒ではハイドレーション待ちだけで枯れることがある（実測）。
    test.setTimeout(150_000)
    await setupAdminAuth(page)
    await mockCatchAllApis(page)
    await mockFeatureFlags(page)
    await mockInboxSummary(page)
    await mockTeamDetail(page)

    await page.route('**/api/v1/shifts/swap-requests?**', async (route) => {
      await route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ message: 'internal error' }),
      })
    })

    await page.goto(TEAM_SHIFTS_URL)
    await waitForHydration(page)
    await page.getByRole('tab', { name: 'シフト交換' }).click()

    const list = swapListRoot(page)
    await expect(list.getByText('交換リクエストを取得できませんでした')).toBeVisible({
      timeout: 15_000,
    })
    await expect(list.getByRole('button', { name: '再読み込み' })).toBeVisible()
    // 「0件」の空表示に潰していないこと
    await expect(list.getByText('交換リクエストはありません')).toHaveCount(0)
  })

  test('SWAP-005: 一覧取得が失敗したとき、空表示に潰さずエラー面を出し操作導線を出さない', async ({
    page,
  }) => {
    // 403 を例に取るが、本テストが測るのは「一覧が取れなかったときの FE の振る舞い」であって
    // 認可そのものではない（500 でも同じエラー面になるため、FE 側では両者を区別できない）。
    // BE の認可は ShiftSwapScopeContractIT で担保している。
    // /my/shift・チームシェル配下は dev サーバーの初回 SSR/最適化が重く、
    // 既定 60 秒ではハイドレーション待ちだけで枯れることがある（実測）。
    test.setTimeout(150_000)
    await setupMemberAuth(page)
    await mockCatchAllApis(page)
    await mockFeatureFlags(page)
    await mockInboxSummary(page)
    await mockTeamDetail(page)

    await page.route('**/api/v1/shifts/swap-requests?**', async (route) => {
      await route.fulfill({
        status: 403,
        contentType: 'application/json',
        body: JSON.stringify({ message: 'forbidden' }),
      })
    })

    await page.goto(TEAM_SHIFTS_URL)
    await waitForHydration(page)
    await page.getByRole('tab', { name: 'シフト交換' }).click()

    const list = swapListRoot(page)
    await expect(list.getByText('交換リクエストを取得できませんでした')).toBeVisible({
      timeout: 15_000,
    })
    // 承認・却下の導線も、依頼行そのものも出ない
    await expect(list.locator('[data-testid^="swap-accept-"]')).toHaveCount(0)
    await expect(list.locator('[data-testid^="swap-reject-"]')).toHaveCount(0)
    await expect(list.locator('[data-testid^="swap-approve-"]')).toHaveCount(0)
    await expect(list.getByText(/申請者 #/)).toHaveCount(0)
  })
})
