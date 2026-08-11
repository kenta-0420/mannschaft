/**
 * このテストは API モックを使わない実機テストです。
 *
 * 実際に起動しているバックエンド・フロントエンドに対して、ブラウザが画面を表示し
 * タブをクリックし、遷移先の内容を検証する。`page.route` によるモックは使用しない。
 *
 * 対象: F19.1 公開ページ 第三陣（PR #2678 / マスター御裁可 2026-08-06）
 *   - 公開チーム／組織ページを <section> 縦積みから **横並びの実タブ**へ作り替えた
 *   - 「活動記録」タブを新設し、詳細遷移は公開ページ専用ページを新設せず
 *     既存 `/activity/{id}`（PR #2551 で SSR 化済み）を共用する
 *
 * 設計書: docs/features/F19.1_public_pages_identity_disclosure.md §2.1 / §5.2.1
 *         docs/features/F06.4_activity_records.md「画面」章
 *
 * ⚠️ 前提データ: seed の TEAM slug `fc-u-18`（id=1）に PUBLIC かつ PUBLISHED の
 * 活動記録が投入されていること。フィクスチャが見つからない場合は skip ではなく
 * **fail** させる（偽の緑を作らないため。CLAUDE.md 障害対応の原則）。
 */

import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

// 全テストを未認証状態で実行する（公開ページは未ログインで到達できることが要件）
test.use({ storageState: { cookies: [], origins: [] } })

const TEAM_SLUG = 'fc-u-18'
// seed の組織には slug が未設定のため数値 ID で到達する（ScopeSlugIdConverter は両方を解決する）
const ORG_IDENTIFIER = '1'

/**
 * 活動記録タブを「実際に選択されるまで」押す。
 *
 * ⚠️ 本プロジェクトの dev サーバーはハイドレーションが遅く（本番2秒に対し dev は十数秒）、
 * ハイドレーション完了前のクリックは**静かに捨てられる**。1回押して待つだけの実装だと
 * 実装は正しいのにテストだけが落ちる（実際にそれで「実装の欠陥」と誤認しかけた）。
 * よって `aria-selected` が true になるまでポーリングして押し直す。
 */
async function openActivitiesTab(page: import('@playwright/test').Page) {
  const tab = page.getByRole('tab', { name: '活動記録' })
  await expect(tab).toBeVisible({ timeout: 30_000 })
  // クリック自体が落ちた理由は握りつぶさず、最後の失敗を失敗メッセージに載せる
  let lastClickError = ''
  await expect
    .poll(
      async () => {
        try {
          await tab.click({ trial: false })
        }
        catch (e) {
          lastClickError = e instanceof Error ? e.message.split('\n')[0]! : String(e)
        }
        return tab.getAttribute('aria-selected')
      },
      {
        message: `活動記録タブが選択状態になること（ハイドレーション完了待ちを含む）/ 直近のクリック失敗: ${lastClickError || 'なし'}`,
        timeout: 60_000,
        intervals: [1000, 2000, 3000],
      },
    )
    .toBe('true')
}

test.describe('公開チームページ: 実タブ構成', () => {
  test('PUBTAB-001: 公開チームページが未認証で表示され、タブが横並びで存在する', async ({ page }) => {
    const res = await page.goto(`/public/teams/${TEAM_SLUG}`)
    expect(res?.status(), '公開チームページは未認証で 200 を返すこと').toBe(200)
    await waitForHydration(page)

    // タブ（tablist / tab ロール）が実在すること = 「縦積みセクション」ではないことの証明
    const tablist = page.getByRole('tablist')
    await expect(tablist, 'タブリストが存在すること').toBeVisible({ timeout: 15_000 })

    await expect(
      page.getByRole('tab', { name: '投稿' }),
      '「投稿」タブが存在すること',
    ).toBeVisible({ timeout: 5_000 })
    await expect(
      page.getByRole('tab', { name: '活動記録' }),
      '「活動記録」タブが存在すること（第三陣の新設分）',
    ).toBeVisible({ timeout: 5_000 })
  })

  test('PUBTAB-002: 初期表示では活動記録パネルは開いておらず、投稿タブが選択されている', async ({ page }) => {
    await page.goto(`/public/teams/${TEAM_SLUG}`)
    await waitForHydration(page)

    await expect(
      page.getByRole('tab', { name: '投稿' }),
      '初期選択は投稿タブであること',
    ).toHaveAttribute('aria-selected', 'true', { timeout: 15_000 })

    await expect(
      page.getByTestId('public-activities-section'),
      '活動記録セクションは初期状態では表示されないこと',
    ).toBeHidden({ timeout: 5_000 })
  })

  test('PUBTAB-003: 「活動記録」タブをクリックすると活動記録セクションが表示される', async ({ page }) => {
    await page.goto(`/public/teams/${TEAM_SLUG}`)
    await waitForHydration(page)

    await openActivitiesTab(page)

    await expect(
      page.getByTestId('public-activities-section'),
      'タブ切替で活動記録セクションが現れること',
    ).toBeVisible({ timeout: 10_000 })
  })

  test('PUBTAB-004: 活動記録タブに実データのカードが1件以上表示される', async ({ page }) => {
    await page.goto(`/public/teams/${TEAM_SLUG}`)
    await waitForHydration(page)
    await openActivitiesTab(page)

    const items = page.getByTestId('public-activity-item')
    await expect(items.first(), '活動記録カードが表示されること').toBeVisible({
      timeout: 10_000,
    })

    const count = await items.count()
    // フィクスチャ不在は skip ではなく fail（偽の緑を作らない）
    expect(
      count,
      `seed の TEAM ${TEAM_SLUG} に PUBLIC/PUBLISHED な活動記録が必要（0件ならフィクスチャ不足）`,
    ).toBeGreaterThan(0)
  })

  test('PUBTAB-005: カードをクリックすると既存の /activity/{id} 詳細ページへ遷移する', async ({ page }) => {
    // このテストだけは「一覧のハイドレーション待ち」＋「詳細ルートの初回 SSR コンパイル」を
    // 続けて踏むため、dev サーバーでは既定枠に収まらない。実装の遅さではなく dev の
    // オンデマンドビルド由来なので、枠を広げたうえで詳細ルートを先に温めておく。
    test.setTimeout(300_000)
    // 詳細ルートの初回コンパイルをクリック前に済ませる（遷移の待ち時間から切り離す）
    await page.request.get('/activity/1')

    await page.goto(`/public/teams/${TEAM_SLUG}`)
    await waitForHydration(page)
    await openActivitiesTab(page)

    const first = page.getByTestId('public-activity-item').first()
    await expect(first).toBeVisible({ timeout: 10_000 })
    const title = (await first.locator('h3').innerText()).trim()

    await first.click()

    // 公開スコープ配下の複製ページではなく、既存の /activity/{id} を共用していること
    await expect(page, '遷移先が /activity/{id} であること').toHaveURL(
      /\/activity\/\d+$/,
      { timeout: 15_000 },
    )
    await waitForHydration(page)

    // 詳細ページの見出し（h1）で照合する。seed には同名タイトルの記録が複数あるため
    // 見出しロール名だけで引くと一覧側のカード見出し（h3）まで巻き込んでしまう。
    // クライアント遷移では詳細ルートの JS チャンクを dev がその場でビルドするため、
    // 初回だけ十数秒かかる。SSR 側の描画は PUBTAB-006 で別途裏取り済み。
    const detailHeading = page.locator('h1')
    await expect(
      detailHeading,
      '一覧でクリックした記録の詳細が表示されること',
    ).toHaveText(title, { timeout: 60_000 })
  })

  test('PUBTAB-006: 活動記録の詳細は SSR HTML に本文が載る（CSR 専用ではない）', async ({ request }) => {
    // 一覧から実在 ID を取得してから、その詳細を JS 無効相当（生 HTML）で取得する
    const listRes = await request.get('/public/teams/' + TEAM_SLUG)
    expect(listRes.status()).toBe(200)

    const detailRes = await request.get('/activity/1')
    expect(detailRes.status(), '公開活動記録の詳細は 200 を返すこと').toBe(200)

    const html = await detailRes.text()
    expect(
      html,
      'SSR された HTML に記録タイトルが含まれること（PR #2551 の SSR 化が効いている証明）',
    ).toContain('春季合宿2026')
  })

  test('PUBTAB-007: 存在しない活動記録の詳細は SSR で 404 を返す', async ({ request }) => {
    const res = await request.get('/activity/9999999')
    expect(res.status(), '不在の記録は 404（CSR 後の空表示ではない）').toBe(404)
  })
})

test.describe('公開組織ページ: 実タブ構成', () => {
  // 組織ページはチームページと同一の Tabs 実装を持つ。同じ v-model 是正を入れているため、
  // 切替機構そのものが機能することをこちらでも実測する。
  // seed の組織には公開活動記録が無いので、件数ではなく**タブが実際に切り替わること**を検証する。
  test('PUBTAB-008: 組織ページでも活動記録タブが実際に選択状態へ切り替わる', async ({ page }) => {
    const res = await page.goto(`/public/organizations/${ORG_IDENTIFIER}`)
    expect(res?.status(), '公開組織ページは未認証で 200 を返すこと').toBe(200)
    await waitForHydration(page)

    await expect(
      page.getByRole('tab', { name: '投稿' }),
      '初期選択は投稿タブであること',
    ).toHaveAttribute('aria-selected', 'true', { timeout: 15_000 })

    await openActivitiesTab(page)

    await expect(
      page.getByTestId('public-activities-section'),
      'タブ切替で活動記録セクションが現れること（組織ページでも v-model 是正が効いている証明）',
    ).toBeVisible({ timeout: 10_000 })
  })
})
