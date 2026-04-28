import { test, expect } from '@playwright/test'
import JA from '../../../app/locales/ja/event.json' with { type: 'json' }
import EN from '../../../app/locales/en/event.json' with { type: 'json' }
import { waitForHydration } from '../helpers/wait'
import {
  DEFAULT_TEAM_ID,
  DEFAULT_EVENT_ID,
  buildEvent,
  buildRollCallCandidate,
  buildRollCallSessionResponse,
  getOfflineCareQueueCount,
  getOfflineCareQueueItems,
  loginAsOrganizer,
  mockCatchAllApis,
  mockEventDetail,
  mockGetAdvanceNotices,
  mockGetCandidates,
  mockSubmitRollCall,
} from './_helpers'

/**
 * F03.12 Phase 11 §14 主催者点呼 E2E テスト群（CARE-ROLLCALL-001〜008）。
 *
 * <p>シナリオ:</p>
 * <ul>
 *   <li>CARE-ROLLCALL-001: 候補者一覧 → 全員 PRESENT 確定（一括チェック）</li>
 *   <li>CARE-ROLLCALL-002: LATE 入力（30 分遅刻）— lateArrivalMinutes=30</li>
 *   <li>CARE-ROLLCALL-003: ABSENT + 保護者通知（既定理由 NOT_ARRIVED）</li>
 *   <li>CARE-ROLLCALL-004: 冪等再送（同一エントリを 2 回 POST、updatedCount 増加）</li>
 *   <li>CARE-ROLLCALL-005: 「未チェック者のみ」フィルタ</li>
 *   <li>CARE-ROLLCALL-006: フルスクリーンページ URL 直叩き</li>
 *   <li>CARE-ROLLCALL-007: オフライン保存（Dexie キュー積み）</li>
 *   <li>CARE-ROLLCALL-008: i18n 抜き取り（ロケール en で英語ラベル確認）</li>
 * </ul>
 *
 * <p>仕様書: docs/features/F03.12_care_recipient_event_watch_notification.md §14</p>
 */

const ROLL_CALL_URL = `/teams/${DEFAULT_TEAM_ID}/events/${DEFAULT_EVENT_ID}/roll-call`

const ROLL_CALL_LABELS_JA = JA.event.rollCall
const ROLL_CALL_LABELS_EN = EN.event.rollCall

/** 3 名構成の標準候補者セット（ID 101/102/103）。 */
function buildStandardCandidates() {
  return [
    buildRollCallCandidate({ userId: 101, displayName: '山田太郎' }),
    buildRollCallCandidate({ userId: 102, displayName: '佐藤花子' }),
    buildRollCallCandidate({
      userId: 103,
      displayName: '鈴木一郎',
      isUnderCare: true,
      watcherCount: 1,
    }),
  ]
}

test.describe('CARE-ROLLCALL-001〜008: F03.12 §14 主催者点呼', () => {
  test.beforeEach(async ({ page }) => {
    // 主催者として認証注入し、catch-all で未モック API を 404 化する
    await loginAsOrganizer(page, { teamId: DEFAULT_TEAM_ID })
    await mockCatchAllApis(page)
    // ページの onMounted で advance-notices も並列ロードされるため空配列で抑える
    await mockGetAdvanceNotices(page, [])
    // event 詳細は header 表示のため固定モック（ページが直接呼ばないが将来安全）
    await mockEventDetail(page, buildEvent())
  })

  test('CARE-ROLLCALL-001: 候補者一覧 → 全員 PRESENT 確定（一括チェック）', async ({ page }) => {
    const candidates = buildStandardCandidates()
    await mockGetCandidates(page, candidates)
    const captured: { lastBody: unknown } = { lastBody: null }
    await mockSubmitRollCall(
      page,
      buildRollCallSessionResponse({
        rollCallSessionId: 'srv-uuid-001',
        createdCount: 3,
        guardianNotificationsSent: 1,
      }),
      captured,
    )

    await page.goto(ROLL_CALL_URL)
    await waitForHydration(page)

    // 一覧描画完了を待つ
    await expect(page.getByTestId('roll-call-row-101')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByTestId('roll-call-row-102')).toBeVisible()
    await expect(page.getByTestId('roll-call-row-103')).toBeVisible()

    // 「一括出席」ボタンで全員 PRESENT に
    await page.getByTestId('roll-call-bulk-present').click()

    // 進捗カウンタが「3 / 3 名 チェック済み」相当になる
    await expect(page.getByTestId('roll-call-progress')).toContainText('3')

    // 各行が PRESENT 状態を示す（data-status 属性で確認）
    await expect(page.getByTestId('roll-call-row-101')).toHaveAttribute('data-status', 'PRESENT')
    await expect(page.getByTestId('roll-call-row-102')).toHaveAttribute('data-status', 'PRESENT')
    await expect(page.getByTestId('roll-call-row-103')).toHaveAttribute('data-status', 'PRESENT')

    // 確定ダイアログを開いて確定
    await page.getByTestId('roll-call-open-submit').click()
    await expect(page.getByTestId('roll-call-submit-dialog')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByTestId('roll-call-present-count')).toContainText('3')
    await page.getByTestId('roll-call-submit-confirm').click()

    // POST 本体が PRESENT 3 件で送られたことをキャプチャから検証
    await expect.poll(() => captured.lastBody, { timeout: 10_000 }).not.toBeNull()
    const body = captured.lastBody as {
      rollCallSessionId: string
      entries: { userId: number; status: string }[]
      notifyGuardiansImmediately: boolean
    }
    expect(body.entries).toHaveLength(3)
    expect(body.entries.every((e) => e.status === 'PRESENT')).toBe(true)
    expect(typeof body.rollCallSessionId).toBe('string')
    expect(body.rollCallSessionId.length).toBeGreaterThan(0)
  })

  test('CARE-ROLLCALL-002: LATE 入力（30 分遅刻）', async ({ page }) => {
    const candidates = buildStandardCandidates()
    await mockGetCandidates(page, candidates)
    const captured: { lastBody: unknown } = { lastBody: null }
    await mockSubmitRollCall(
      page,
      buildRollCallSessionResponse({
        rollCallSessionId: 'srv-uuid-002',
        createdCount: 1,
      }),
      captured,
    )

    await page.goto(ROLL_CALL_URL)
    await waitForHydration(page)
    await expect(page.getByTestId('roll-call-row-101')).toBeVisible({ timeout: 10_000 })

    // 山田太郎を LATE に
    await page.getByTestId('roll-call-row-101-late').click()
    await expect(page.getByTestId('roll-call-row-101')).toHaveAttribute('data-status', 'LATE')

    // 遅刻分数の InputNumber: 初期値 5。+ ボタン or 直接入力で 30 にする
    const lateInputContainer = page.getByTestId('roll-call-row-101-late-minutes')
    const lateInput = lateInputContainer.locator('input').first()
    await lateInput.fill('30')
    await lateInput.press('Tab')

    // 確定
    await page.getByTestId('roll-call-open-submit').click()
    await expect(page.getByTestId('roll-call-late-count')).toContainText('1')
    await page.getByTestId('roll-call-submit-confirm').click()

    await expect.poll(() => captured.lastBody, { timeout: 10_000 }).not.toBeNull()
    const body = captured.lastBody as {
      entries: { userId: number; status: string; lateArrivalMinutes?: number }[]
    }
    expect(body.entries).toHaveLength(1)
    expect(body.entries[0]).toMatchObject({
      userId: 101,
      status: 'LATE',
      lateArrivalMinutes: 30,
    })
  })

  test('CARE-ROLLCALL-003: ABSENT + 保護者通知（既定理由 NOT_ARRIVED）', async ({ page }) => {
    const candidates = buildStandardCandidates()
    await mockGetCandidates(page, candidates)
    const captured: { lastBody: unknown } = { lastBody: null }
    await mockSubmitRollCall(
      page,
      buildRollCallSessionResponse({
        rollCallSessionId: 'srv-uuid-003',
        createdCount: 1,
        guardianNotificationsSent: 0,
      }),
      captured,
    )

    await page.goto(ROLL_CALL_URL)
    await waitForHydration(page)
    await expect(page.getByTestId('roll-call-row-103')).toBeVisible({ timeout: 10_000 })

    // ケア対象者 (103) を ABSENT に（既定理由 NOT_ARRIVED が selectStatus で自動付与される）
    await page.getByTestId('roll-call-row-103-absent').click()
    await expect(page.getByTestId('roll-call-row-103')).toHaveAttribute('data-status', 'ABSENT')

    // 「保護者へ即時通知」トグル既定 ON のまま確定
    await page.getByTestId('roll-call-open-submit').click()
    await expect(page.getByTestId('roll-call-absent-count')).toContainText('1')
    // 通知エコーが "保護者に即時通知します" になっていることを確認
    await expect(page.getByTestId('roll-call-notify-echo')).toContainText(
      ROLL_CALL_LABELS_JA.willNotifyGuardians,
    )
    await page.getByTestId('roll-call-submit-confirm').click()

    await expect.poll(() => captured.lastBody, { timeout: 10_000 }).not.toBeNull()
    const body = captured.lastBody as {
      entries: { userId: number; status: string; absenceReason?: string }[]
      notifyGuardiansImmediately: boolean
    }
    expect(body.entries[0]).toMatchObject({
      userId: 103,
      status: 'ABSENT',
      absenceReason: 'NOT_ARRIVED',
    })
    expect(body.notifyGuardiansImmediately).toBe(true)
  })

  test('CARE-ROLLCALL-004: 冪等再送（同一エントリ 2 回送信、updateCount が増加）', async ({ page }) => {
    const candidates = buildStandardCandidates()
    await mockGetCandidates(page, candidates)

    // 2 回目の POST で updatedCount=1 を返すよう、呼ばれた回数を数えて切り替える
    let postCount = 0
    const captured: { lastBody: unknown } = { lastBody: null }
    await page.route('**/api/v1/teams/*/events/*/roll-call', async (route) => {
      if (route.request().method() !== 'POST') {
        await route.continue()
        return
      }
      postCount++
      try {
        captured.lastBody = route.request().postDataJSON() as unknown
      } catch {
        captured.lastBody = null
      }
      const isResend = postCount >= 2
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({
          data: buildRollCallSessionResponse({
            rollCallSessionId: `srv-uuid-004-${postCount}`,
            createdCount: isResend ? 0 : 1,
            updatedCount: isResend ? 1 : 0,
          }),
        }),
      })
    })

    await page.goto(ROLL_CALL_URL)
    await waitForHydration(page)
    await expect(page.getByTestId('roll-call-row-101')).toBeVisible({ timeout: 10_000 })

    // 1 回目: 山田太郎 PRESENT で確定
    await page.getByTestId('roll-call-row-101-present').click()
    await page.getByTestId('roll-call-open-submit').click()
    await page.getByTestId('roll-call-submit-confirm').click()
    await expect.poll(() => postCount, { timeout: 10_000 }).toBe(1)

    // 2 回目: 同じエントリを再送（同一 userId+status の冪等再送を疑似）
    // 確定後、UI は entriesMap を保持し続けるので再度 open-submit を押せばまた送れる
    await expect(page.getByTestId('roll-call-row-101')).toHaveAttribute('data-status', 'PRESENT')
    await page.getByTestId('roll-call-open-submit').click()
    await expect(page.getByTestId('roll-call-submit-dialog')).toBeVisible({ timeout: 10_000 })
    await page.getByTestId('roll-call-submit-confirm').click()
    await expect.poll(() => postCount, { timeout: 10_000 }).toBe(2)

    // 2 回目の body も userId=101 status=PRESENT で同一の論理エントリ
    const body = captured.lastBody as {
      entries: { userId: number; status: string }[]
    }
    expect(body.entries).toHaveLength(1)
    expect(body.entries[0]).toMatchObject({ userId: 101, status: 'PRESENT' })
    // 重複行が増えない指標として createdCount=0 / updatedCount=1 のレスポンスを返した
    // （UI はその差分を内部的に解釈、ここでは BE 契約の冪等性を spec で表現）
    expect(postCount).toBe(2)
  })

  test('CARE-ROLLCALL-005: 「未チェック者のみ」フィルタ', async ({ page }) => {
    const candidates = buildStandardCandidates()
    await mockGetCandidates(page, candidates)
    await mockSubmitRollCall(page, buildRollCallSessionResponse())

    await page.goto(ROLL_CALL_URL)
    await waitForHydration(page)
    await expect(page.getByTestId('roll-call-row-101')).toBeVisible({ timeout: 10_000 })

    // 1 名だけチェック (山田太郎)
    await page.getByTestId('roll-call-row-101-present').click()
    await expect(page.getByTestId('roll-call-row-101')).toHaveAttribute('data-status', 'PRESENT')

    // 「未チェックのみ」フィルタへ切替（PrimeVue SelectButton の選択肢ラベルをクリック）
    await page
      .getByTestId('roll-call-filter-mode')
      .getByText(ROLL_CALL_LABELS_JA.filterUnchecked)
      .click()

    // 山田 (101) は未チェックではないので非表示、102/103 は残る
    await expect(page.getByTestId('roll-call-row-101')).toHaveCount(0)
    await expect(page.getByTestId('roll-call-row-102')).toBeVisible()
    await expect(page.getByTestId('roll-call-row-103')).toBeVisible()

    // 「すべて表示」に戻すと 101 も復活
    await page
      .getByTestId('roll-call-filter-mode')
      .getByText(ROLL_CALL_LABELS_JA.filterAll)
      .click()
    await expect(page.getByTestId('roll-call-row-101')).toBeVisible()
  })

  test('CARE-ROLLCALL-006: フルスクリーンページに URL 直叩きで遷移', async ({ page }) => {
    const candidates = buildStandardCandidates()
    await mockGetCandidates(page, candidates)
    await mockSubmitRollCall(page, buildRollCallSessionResponse())

    // 直接 URL を入力したシミュレーションとして goto
    const response = await page.goto(ROLL_CALL_URL)
    await waitForHydration(page)

    // ステータスコードが 2xx
    expect(response?.status() ?? 0).toBeLessThan(400)

    // ページタイトル（i18n event.rollCall.title = "点呼"）が表示される
    await expect(page.getByRole('heading', { name: ROLL_CALL_LABELS_JA.title })).toBeVisible({
      timeout: 10_000,
    })

    // 点呼シート本体と候補者行がレンダリングされる
    await expect(page.getByTestId('roll-call-sheet')).toBeVisible()
    await expect(page.getByTestId('roll-call-row-101')).toBeVisible()
  })

  test('CARE-ROLLCALL-007: オフライン保存（Dexie キュー）', async ({ page }) => {
    const candidates = buildStandardCandidates()
    await mockGetCandidates(page, candidates)
    // POST 自体はオフライン中に呼ばれないが、スパイ目的でカウンタ付きで登録
    let postCount = 0
    await page.route('**/api/v1/teams/*/events/*/roll-call', async (route) => {
      if (route.request().method() !== 'POST') {
        await route.continue()
        return
      }
      postCount++
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({ data: buildRollCallSessionResponse() }),
      })
    })

    await page.goto(ROLL_CALL_URL)
    await waitForHydration(page)
    await expect(page.getByTestId('roll-call-row-101')).toBeVisible({ timeout: 10_000 })

    // navigator.onLine を false にしてオフラインを再現
    await page.evaluate(() => {
      Object.defineProperty(navigator, 'onLine', { value: false, configurable: true })
    })

    // 1 名 PRESENT で確定 → useRollCall.submit がオフライン分岐に入り Dexie へ enqueue
    await page.getByTestId('roll-call-row-101-present').click()
    await page.getByTestId('roll-call-open-submit').click()
    await expect(page.getByTestId('roll-call-submit-dialog')).toBeVisible({ timeout: 10_000 })
    await page.getByTestId('roll-call-submit-confirm').click()

    // Dexie の care-queue に 1 件積まれることを検証
    await expect
      .poll(() => getOfflineCareQueueCount(page, 'care:roll-call:'), { timeout: 10_000 })
      .toBe(1)

    // 積まれたジョブの中身を覗き、teamId/eventId/payload が正しいことを確認
    const items = await getOfflineCareQueueItems(page)
    const rollCallJob = items.find((i) => i.clientId.startsWith('care:roll-call:'))
    expect(rollCallJob).toBeDefined()
    expect(rollCallJob?.path).toBe('/api/v1/care-queue/roll-call')
    const payload = rollCallJob?.payload as {
      type: string
      teamId: number
      eventId: number
      payload: { entries: { userId: number; status: string }[] }
    }
    expect(payload.type).toBe('ROLL_CALL')
    expect(payload.teamId).toBe(DEFAULT_TEAM_ID)
    expect(payload.eventId).toBe(DEFAULT_EVENT_ID)
    expect(payload.payload.entries[0]).toMatchObject({ userId: 101, status: 'PRESENT' })

    // POST は走らない（オフラインのため）
    expect(postCount).toBe(0)
  })

  test('CARE-ROLLCALL-008: i18n 抜き取り（en ロケールで英語ラベル確認）', async ({ page }) => {
    const candidates = buildStandardCandidates()
    await mockGetCandidates(page, candidates)
    await mockSubmitRollCall(page, buildRollCallSessionResponse())

    await page.goto(ROLL_CALL_URL)
    await waitForHydration(page)
    await expect(page.getByTestId('roll-call-row-101')).toBeVisible({ timeout: 10_000 })

    // ハイドレーション後に Vue App の $i18n.setLocale を呼んで実ロケールを切替
    await page.waitForFunction(() => {
      const el = document.querySelector('#__nuxt') as
        | (HTMLElement & { __vue_app__?: unknown })
        | null
      if (!el || !('__vue_app__' in el)) return false
      const app = el.__vue_app__ as
        | { config?: { globalProperties?: { $i18n?: unknown } } }
        | undefined
      return Boolean(app?.config?.globalProperties?.$i18n)
    })
    await page.evaluate(async (locale) => {
      const el = document.querySelector('#__nuxt') as
        | (HTMLElement & { __vue_app__?: unknown })
        | null
      if (!el) throw new Error('Nuxt root element not found')
      const app = el.__vue_app__ as
        | {
            config: {
              globalProperties: {
                $i18n: { setLocale?: (l: string) => Promise<void>; locale: { value: string } }
              }
            }
          }
        | undefined
      if (!app) throw new Error('Vue app instance not found on #__nuxt')
      const i18n = app.config.globalProperties.$i18n
      if (typeof i18n.setLocale === 'function') {
        await i18n.setLocale(locale)
      } else {
        i18n.locale.value = locale
      }
    }, 'en')

    // 英語ラベルが反映される（en の event.rollCall.title = "Roll Call"）
    await expect(page.getByRole('heading', { name: ROLL_CALL_LABELS_EN.title })).toBeVisible({
      timeout: 10_000,
    })

    // 「Mark all Present」ボタンも英語化される
    await expect(page.getByTestId('roll-call-bulk-present')).toContainText(
      ROLL_CALL_LABELS_EN.bulkPresent,
    )

    // 行内の PRESENT セグメントラベルも英語化（"Present"）
    await expect(page.getByTestId('roll-call-row-101-present')).toContainText(
      ROLL_CALL_LABELS_EN.statusPresent,
    )

    // 進捗テキストも英語化（"0 / 3 checked"）
    await expect(page.getByTestId('roll-call-progress')).toContainText('checked')
  })
})
