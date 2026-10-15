import { test, expect, type BrowserContext, type Page } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

/**
 * CMP-058 実機E2E: 組織タイムラインの「配下配信」と「ミュート」。
 *
 * <p><b>前提とする実機構成</b></p>
 * <ul>
 *   <li>BE: origin/main の native ビルド（`http://localhost:8080`）</li>
 *   <li>FE: origin/main の dev サーバー（`http://localhost:3001`）。
 *       <b>本陣 :3000 は main より古く CMP-058 の FE 実装を一切含まないため使えない。</b></li>
 * </ul>
 *
 * <p><b>テストデータ</b>（`seed.sql` で投入。既存シードの組織階層 1→2→4→9 を利用する）</p>
 * <ul>
 *   <li>投稿者 : user 90209（org 1 の ADMIN。既存）</li>
 *   <li>閲覧者 : user 990001（org 9 の member。org 1 から距離3 = DESCENDANTS のみ届く）</li>
 *   <li>一般   : user 990002（org 1 の member のみ。管理者ではない）</li>
 * </ul>
 *
 * <p><b>セッション設計</b>: ユーザーごとに context を1つだけ作り、ログインも1回だけ行う
 * （storageState 使い回しによるトークン回転リプレイ、および連続ログインのレート制限を避ける）。</p>
 *
 * <p><b>閲覧者ユーザーの用意（未投入の環境で走らせる場合）</b>。パスワードは既知の検証ユーザー
 * 90209 の行を複製して揃える（ハッシュを直に扱わない）。固定IDで入れ、後で同じIDだけ消す:</p>
 * <pre>
 * DROP TABLE IF EXISTS tmp_user_clone;
 * CREATE TABLE tmp_user_clone LIKE users;
 * INSERT INTO tmp_user_clone SELECT * FROM users WHERE id = 90209;
 * UPDATE tmp_user_clone SET id=990001, email='cmp058-desc@test.mannschaft.local',
 *        contact_handle='cmp058desc', last_name='配下', first_name='花子', display_name='CMP058配下花子';
 * INSERT INTO users SELECT * FROM tmp_user_clone; DELETE FROM tmp_user_clone WHERE id=990001;
 * INSERT INTO tmp_user_clone SELECT * FROM users WHERE id = 90209;
 * UPDATE tmp_user_clone SET id=990002, email='cmp058-plain@test.mannschaft.local',
 *        contact_handle='cmp058plain', last_name='一般', first_name='次郎', display_name='CMP058一般次郎';
 * INSERT INTO users SELECT * FROM tmp_user_clone; DROP TABLE tmp_user_clone;
 * INSERT INTO memberships (id,user_id,scope_type,scope_id,role_kind,joined_at,created_at,updated_at)
 * VALUES (990001,990001,'ORGANIZATION',9,'MEMBER',NOW(),NOW(),NOW()),
 *        (990002,990002,'ORGANIZATION',1,'MEMBER',NOW(),NOW(),NOW());
 * </pre>
 *
 * <p><b>実行方法</b>（本 spec は setup プロジェクト非依存で走る）:</p>
 * <pre>
 * BASE_URL=http://localhost:3003 API_BASE_URL=http://localhost:8080 \
 *   npx playwright test --config=playwright-real.config.ts \
 *   tests/e2e/real/timeline-delivery-scope-and-mute.spec.ts
 * </pre>
 * <p>FE の待ち受けポートは BE の CORS 許可オリジン（`application-local.yml` の
 * `mannschaft.allowed-origins`）に含まれるものを使うこと。許可外のポートで上げると
 * 全 API が 403 になり、機能の不具合に見える。</p>
 */

const ADMIN = { email: 'e2e-pwui-1782136885@test.mannschaft.local', password: 'Passw0rd!2026' }
const VIEWER = { email: 'cmp058-desc@test.mannschaft.local', password: 'Passw0rd!2026' }
const PLAIN = { email: 'cmp058-plain@test.mannschaft.local', password: 'Passw0rd!2026' }

/** 親組織（日本サッカー協会）。投稿元。 */
const PARENT_ORG_SLUG = 'org-000001'

const MARK = `CMP058UI${Date.now()}`

test.describe.configure({ mode: 'serial' })

test.describe('CMP-058 配下配信とミュート（実機）', () => {
  let adminCtx: BrowserContext
  let viewerCtx: BrowserContext
  let plainCtx: BrowserContext
  let adminPage: Page
  let viewerPage: Page
  let plainPage: Page

  test.beforeAll(async ({ browser }) => {
    adminCtx = await browser.newContext()
    adminPage = await adminCtx.newPage()
    await loginViaApi(adminPage, ADMIN)

    viewerCtx = await browser.newContext()
    viewerPage = await viewerCtx.newPage()
    await loginViaApi(viewerPage, VIEWER)

    plainCtx = await browser.newContext()
    plainPage = await plainCtx.newPage()
    await loginViaApi(plainPage, PLAIN)

    // 前回の失敗実行が残したミュートを必ず落としてから始める。
    // ミュートが残っているとマイフィードが空になり、A3（本命）が「配信されていない」に
    // 見えてしまう＝実装バグと誤診する。実行順・前回結果に依存しない状態から始めること。
    const api = process.env.API_BASE_URL ?? 'http://localhost:8080'
    const existing = await viewerPage.request.get(`${api}/api/v1/timeline/mutes`)
    if (existing.ok()) {
      const body = await existing.json() as { data?: Array<{ mutedType: string, mutedId: number }> }
      for (const m of body.data ?? []) {
        // DELETE /timeline/mutes は @RequestParam（クエリ）で受ける。
        // ボディで送っても無視され、ミュートが残ったまま「配信されていない」ように見える。
        const del = await viewerPage.request.delete(
          `${api}/api/v1/timeline/mutes?mutedType=${m.mutedType}&mutedId=${m.mutedId}`,
        )
        expect(del.status(), '前回残りのミュート解除').toBeLessThan(300)
      }
    }
  })

  test.afterAll(async () => {
    await adminCtx?.close()
    await viewerCtx?.close()
    await plainCtx?.close()
  })

  /** 組織タイムラインを開き、ハイドレーション完了まで待つ。 */
  async function openOrgTimeline(page: Page) {
    await page.goto(`/organizations/${PARENT_ORG_SLUG}/timeline`)
    await waitForHydration(page)
    await expect(page.getByTestId('team-timeline-composer')).toBeVisible({ timeout: 30_000 })
  }

  /** 組織タイムラインから1件投稿する。deliveryScope 未指定なら既定（DIRECT）のまま送る。 */
  async function postFromOrgTimeline(page: Page, body: string, scope?: 'CHILDREN' | 'DESCENDANTS') {
    await page.getByTestId('team-timeline-composer').click()
    await page.getByTestId('team-timeline-composer').fill(body)
    if (scope) {
      await page.getByTestId(`timeline-delivery-scope-${scope}`).click()
    }
    const created = page.waitForResponse(
      (r) => r.url().includes('/api/v1/timeline/posts') && r.request().method() === 'POST',
    )
    await page.getByTestId('team-timeline-submit').click()
    const res = await created
    expect(res.status(), `投稿API が 201 を返すこと (${body})`).toBe(201)
  }

  /**
   * 指定本文の投稿カードのケバブメニューを開く。
   * マイタイムラインはウィジェット内に描画されるため、カードを本文で特定してから
   * そのカード内の `team-timeline-post-menu` を押す（他カードのメニューを開かないため）。
   */
  async function openMuteMenuFor(page: Page, body: string) {
    const card = page
      .locator('div')
      .filter({ has: page.getByTestId('team-timeline-post-menu') })
      .filter({ hasText: body })
      .last()
    await card.getByTestId('team-timeline-post-menu').click()
  }

  // ─────────────────────────────────────────────────────────
  // A. 配下配信
  // ─────────────────────────────────────────────────────────

  test('A1: 組織管理者には配信範囲の3択が出て、既定が「この団体のメンバーだけ」である', async () => {
    await openOrgTimeline(adminPage)

    const fieldset = adminPage.getByTestId('timeline-delivery-scope')
    await expect(fieldset).toBeVisible()

    // 3択がすべて出ていること（文言はロケール ja/common.json の timeline.deliveryScope.options）
    await expect(fieldset.getByText('この団体のメンバーだけ')).toBeVisible()
    await expect(fieldset.getByText('直下の子団体まで')).toBeVisible()
    await expect(fieldset.getByText('配下のすべての団体')).toBeVisible()

    // 既定は DIRECT が選択済み
    await expect(adminPage.locator('input#deliveryScope-DIRECT')).toBeChecked()
    await expect(adminPage.locator('input#deliveryScope-CHILDREN')).not.toBeChecked()
    await expect(adminPage.locator('input#deliveryScope-DESCENDANTS')).not.toBeChecked()

    // 既定のときは補足文を出さない（段階開示）
    await expect(adminPage.getByTestId('timeline-delivery-scope-hint')).toHaveCount(0)
  })

  test('A5: 管理者でない一般メンバーには配信範囲の選択肢が出ない', async () => {
    await openOrgTimeline(plainPage)
    // 投稿フォーム自体は出るが、配信範囲の選択肢は出ない
    await expect(plainPage.getByTestId('timeline-delivery-scope')).toHaveCount(0)
  })

  test('A2: 「配下のすべての団体」を選んで投稿できる', async () => {
    await openOrgTimeline(adminPage)
    await postFromOrgTimeline(adminPage, `${MARK}-DESCENDANTS`, 'DESCENDANTS')
  })

  test('A3(本命): 配下組織の所属者のマイタイムラインに、配下配信の投稿が現れる', async () => {
    await viewerPage.goto('/')
    await waitForHydration(viewerPage)
    await expect(
      viewerPage.getByText(`${MARK}-DESCENDANTS`).first(),
      '配下組織(org 9)の所属者のダッシュボードに DESCENDANTS 投稿が届くこと',
    ).toBeVisible({ timeout: 30_000 })
  })

  test('A4(陰性対照): 「この団体のメンバーだけ」の投稿は配下の人に現れない', async () => {
    await openOrgTimeline(adminPage)
    await postFromOrgTimeline(adminPage, `${MARK}-DIRECT`)

    await viewerPage.goto('/')
    await waitForHydration(viewerPage)
    // 直前の DESCENDANTS 投稿は見えている（＝フィード自体は描画済み）ことを先に確かめ、
    // 「まだ読み込まれていないだけ」を PASS と誤読しないようにする。
    await expect(viewerPage.getByText(`${MARK}-DESCENDANTS`).first()).toBeVisible({ timeout: 30_000 })
    await expect(viewerPage.getByText(`${MARK}-DIRECT`)).toHaveCount(0)
  })

  // ─────────────────────────────────────────────────────────
  // B. ミュート
  // ─────────────────────────────────────────────────────────

  test('B8-1: ミュート0件のときは「非表示中」チップが出ない', async () => {
    await viewerPage.goto('/')
    await waitForHydration(viewerPage)
    await expect(viewerPage.getByText(`${MARK}-DESCENDANTS`).first()).toBeVisible({ timeout: 30_000 })
    await expect(viewerPage.getByTestId('timeline-muted-chip')).toHaveCount(0)
  })

  test('B6/B7: 投稿カードからミュートすると即座に消え、「元に戻す」で復活する', async () => {
    await viewerPage.goto('/')
    await waitForHydration(viewerPage)
    const target = viewerPage.getByText(`${MARK}-DESCENDANTS`).first()
    await expect(target).toBeVisible({ timeout: 30_000 })

    // 投稿カードのメニューから「この団体の投稿を非表示にする」
    await openMuteMenuFor(viewerPage, `${MARK}-DESCENDANTS`)
    await viewerPage.getByRole('menuitem', { name: 'この団体の投稿を非表示にする' }).click()

    // 即座に消えること
    await expect(viewerPage.getByText(`${MARK}-DESCENDANTS`)).toHaveCount(0, { timeout: 10_000 })

    // トーストの「元に戻す」で復活すること
    await viewerPage.getByText('元に戻す').click()
    await expect(viewerPage.getByText(`${MARK}-DESCENDANTS`).first()).toBeVisible({ timeout: 15_000 })
  })

  test('B8-2: 非表示中があるとチップが出て、そこから個別に解除できる', async () => {
    await viewerPage.goto('/')
    await waitForHydration(viewerPage)
    const target = viewerPage.getByText(`${MARK}-DESCENDANTS`).first()
    await expect(target).toBeVisible({ timeout: 30_000 })

    await openMuteMenuFor(viewerPage, `${MARK}-DESCENDANTS`)
    await viewerPage.getByRole('menuitem', { name: 'この団体の投稿を非表示にする' }).click()
    await expect(viewerPage.getByText(`${MARK}-DESCENDANTS`)).toHaveCount(0, { timeout: 10_000 })

    // チップが出る（トーストの「元に戻す」は押さない）
    const chip = viewerPage.getByTestId('timeline-muted-chip')
    await expect(chip).toBeVisible({ timeout: 10_000 })
    await expect(chip).toContainText('非表示中')

    // チップから一覧を開いて解除
    await chip.click()
    await expect(viewerPage.getByText('非表示にしている相手')).toBeVisible()
    await viewerPage.getByText('表示に戻す').first().click()

    // 解除後は投稿が戻る
    await viewerPage.goto('/')
    await waitForHydration(viewerPage)
    await expect(viewerPage.getByText(`${MARK}-DESCENDANTS`).first()).toBeVisible({ timeout: 30_000 })
    await expect(viewerPage.getByTestId('timeline-muted-chip')).toHaveCount(0)
  })

  test('B9: ミュートしても検索ではヒットする（仕様）', async () => {
    // API レベルで確認する（検索UIの有無に依存させない）。
    const api = process.env.API_BASE_URL ?? 'http://localhost:8080'

    // まずミュートする
    const muteRes = await viewerPage.request.post(`${api}/api/v1/timeline/mutes`, {
      data: { mutedType: 'ORGANIZATION', mutedId: 1 },
      headers: { 'Content-Type': 'application/json' },
    })
    expect(muteRes.status(), 'ミュート追加').toBeLessThan(300)

    try {
      // マイフィードからは消える
      const feed = await viewerPage.request.get(`${api}/api/v1/timeline/my?page=0&size=50`)
      expect(feed.ok()).toBeTruthy()
      expect(await feed.text()).not.toContain(`${MARK}-DESCENDANTS`)

      // 検索にはヒットする
      const search = await viewerPage.request.get(
        `${api}/api/v1/timeline/search?q=${encodeURIComponent(MARK)}&limit=50`,
      )
      expect(search.ok()).toBeTruthy()
      expect(
        await search.text(),
        'ミュート中でも検索にはヒットすること（ミュートは表示設定であり認可ではない）',
      ).toContain(`${MARK}-DESCENDANTS`)
    }
    finally {
      await viewerPage.request.delete(
        `${api}/api/v1/timeline/mutes?mutedType=ORGANIZATION&mutedId=1`,
      )
    }
  })
})
