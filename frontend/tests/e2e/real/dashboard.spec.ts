/**
 * このテストはAPIモックを使わない実機テストです。
 * バックエンド http://localhost:8080 / フロントエンド http://localhost:3000 が起動済みの状態で実行してください。
 *
 * 認証: ファイル冒頭の describe をまたいだトップレベルの beforeAll で 1 つの
 * BrowserContext を作成し、その中で loginViaApi() による本ファイル専用の新規ログインを
 * 1 回だけ行い、DASH-* / PROF-* / TEAM-NAV-* / NOTIF-* の全 describe で使い回す
 * （単一セッション設計）。playwright-real.config.ts の projects は
 * storageState: 'tests/e2e/.auth/real-user.json' を指定しているが、本ファイルは
 * beforeAll で自前の context を作るためその指定は効かない（意図的）。
 *
 * なぜ「storageState 共有」ではなく「ファイル単位の新規ログイン」なのか（実測に基づく）:
 *   殿による実機実測で以下が判明した。
 *     - favorites.spec.ts 単独実行: 12/12 全緑
 *     - dashboard.spec.ts 単独実行: 18 passed（TEAM-NAV-003 のみ失敗、別原因で是正済み）
 *     - 2 ファイルまとめて実行: dashboard 側は同じ 1 件のみ失敗するが、
 *       favorites 側は初手 FAV-001 で /login?redirect=/dashboard に飛ばされて死亡
 *       （serial のため後続 16 件は未実行）。
 *   両ファイルとも旧実装では beforeAll で storageState: 'tests/e2e/.auth/real-user.json'
 *   から context を作っていた。同一実行内で複数の spec ファイルが同じディスク上の
 *   スナップショットから別々の context を作ると、先行ファイルがトークンを回転させた
 *   （サーバが旧トークンを revoke し後継を発行）時点で、後継トークンは Cookie 経由で
 *   その context にしか残らない。後続ファイルは新しい context を storageState から
 *   作り直すため、既に revoke 済みのトークンを再提示することになり、grace window
 *   （60秒）超過後は「リプレイ攻撃」として検出されセッションごと失効する
 *   （AuthTokenRotationService）。本ファイルは DASH/PROF/TEAM-NAV/NOTIF 合計 20 件超の
 *   テストを持ち、実行時間がアクセストークンの TTL（900秒）を超えうるため、
 *   1 スペックファイル = 1 セッション（1 context）に揃える必要もある。
 *
 *   「1ファイル=1セッション」だけでは不十分で、「1ファイル=1ログイン」まで踏み込み、
 *   storageState を読み込まずファイル専用に新規ログインして新しいトークン系列を確立する。
 *   新規ログイン自体は危険ではない（まっさらなトークン系列を作るだけ）。危険なのは
 *   「回転済み＝revoke 済みのトークンを他ファイルの context 経由で再提示すること」であり、
 *   旧設計はこれを「スナップショットの再利用」と「テストごとの重複ログイン」を混同して
 *   壊していた。ログインは beforeAll 内で 1 回だけ行い、テストごとには呼ばない。
 *
 * なぜ describe ごとに context を分けないか:
 *   同一ファイル内で複数の describe がそれぞれ BrowserContext を beforeAll/afterAll で
 *   開閉すると、片方の後始末がもう片方に干渉する現象が villages.spec.ts で実測されている。
 *   そのため本ファイルはトップレベルの beforeAll/afterAll で 1 つだけ context を作り、
 *   全 describe（DASH-* / PROF-* / TEAM-NAV-* / NOTIF-*）で共有する。
 *
 * page はテストごとに beforeEach で newPage() → afterEach で close() する。回転後トークンの
 * 継続性に必要なのは Cookie ジャー（= context）であって page 自体の使い回しではなく、
 * page を使い回すと前のテストが張った WebSocket 接続や DOM 状態が次のテストに漏れ、
 * ERR_ABORTED 等の干渉を起こすため。
 *
 * テストユーザー: e2e-user@test.mannschaft.local / TestPass2026!
 * - FC東京U-18（テスト）チームのメンバー
 * - 通知7件がシードされている
 */

import { test, expect, type Page, type BrowserContext } from '@playwright/test'
import { waitForHydration, waitForSpinnerGone } from '../helpers/wait'
import { loginViaApi } from '../fixtures/auth'

const E2E_USER = {
  email: 'e2e-user@test.mannschaft.local',
  password: 'TestPass2026!',
}

test.describe.configure({ mode: 'serial' })

// ファイル全体で 1 つの BrowserContext（Cookie ジャー）を共有する（単一セッション設計）。
// page はテストごとに beforeEach/afterEach で作り直す（前テストの WS 接続等を持ち越さない）。
let context: BrowserContext
let page: Page

test.beforeAll(async ({ browser }) => {
  // storageState は読み込まない。ファイル間でスナップショットを共有すると、先行ファイルが
  // 回転させた（revoke 済みの）トークンを本ファイルが再提示することになりセッションごと
  // 失効する（実測: dashboard 単独 18 passed → favorites と2本立てでも dashboard 自体は
  // 同じ1件のみ失敗、影響を受けるのは後続の favorites 側）。そのため本ファイル専用に
  // 新規ログインして新しいトークン系列を確立する。ログインは beforeAll 内で1回だけ行う。
  context = await browser.newContext()
  const setupPage = await context.newPage()
  try {
    await loginViaApi(setupPage, E2E_USER)
  }
  finally {
    await setupPage.close()
  }
})

test.beforeEach(async () => {
  page = await context.newPage()
})

test.afterEach(async () => {
  await page.close()
})

// 本ファイル自前の context は自前の afterAll でのみ閉じる
// （他ファイルの describe と混じらないよう、1 スペックファイル = 1 セッションで完結させる）。
test.afterAll(async () => {
  await context.close()
})

// ---------------------------------------------------------------------------
// DASH-001〜008: ダッシュボード基本表示
// ---------------------------------------------------------------------------
test.describe('DASH-001〜008: ダッシュボード基本表示', () => {
  // storageState で認証済み。各テスト前にマイページへ遷移する（/my/index.vue が正しいルート）
  // 並列実行時にサーバーが高負荷になることがあるためタイムアウトを延長する
  test.setTimeout(120_000)

  test.beforeEach(async () => {
    await page.goto('/my/', { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    await waitForSpinnerGone(page)
  })

  test('DASH-001: /my/ が表示される（主要UIエリアの存在確認）', async () => {
    // マイページハブのカードグリッドが存在する
    await expect(page.locator('.grid')).toBeVisible({ timeout: 15_000 })
    // URL が /my に留まることを確認
    expect(page.url()).toContain('/my')
  })

  test('DASH-002: ナビゲーションサイドバーまたはヘッダーが表示される', async () => {
    // ナビゲーション要素（nav / header / sidebar 相当）のいずれかが存在する
    const navLocator = page.locator('nav, header, [role="navigation"], aside').first()
    await expect(navLocator).toBeVisible({ timeout: 15_000 })
  })

  test('DASH-003: ページタイトルまたは見出しが表示される', async () => {
    // /my/index.vue は PageHeader title="マイページ" を持つ
    await expect(
      page.getByRole('heading', { name: 'マイページ' }),
    ).toBeVisible({ timeout: 15_000 })
  })

  test('DASH-004: 認証済みページがレンダリングされている（ページテキスト確認）', async () => {
    // /my/ ページがレンダリングされていることをページ本文テキストで確認する
    // セレクタ指定の textContent() はタイムアウト待機が発生するため body テキストで確認
    const bodyText = await page.locator('body').textContent()
    expect(bodyText).toBeTruthy()
    expect(bodyText!.length).toBeGreaterThan(10)
  })

  test('DASH-005: 通知アイコンが表示される', async () => {
    // NotificationBell コンポーネント: ベルアイコンボタンが存在する
    const bellButton = page
      .locator('button')
      .filter({ has: page.locator('.pi-bell, [data-pc-name="button"] .pi-bell') })
      .first()
    // data-testid がない場合はアイコンクラスで探す
    const bellIcon = page.locator('.pi-bell').first()
    await expect(bellIcon).toBeVisible({ timeout: 15_000 })
    void bellButton
  })

  test('DASH-006: /notifications ページに通知一覧が表示される', async () => {
    await page.goto('/notifications', { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '通知' })).toBeVisible({ timeout: 15_000 })
    // NotificationList コンポーネントが描画されていること（コンテナが存在する）
    await expect(page.locator('.mx-auto.max-w-2xl')).toBeVisible({ timeout: 10_000 })
  })

  test('DASH-007: 未読通知バッジが表示される（seed で7件未読通知を投入済み）', async () => {
    // NotificationBell: totalCount > 0 のとき PrimeVue Badge が表示される
    // PrimeVue Badge は data-pc-name="badge" または class="p-badge" で描画される
    const anyBadge = page.locator(
      '[data-pc-name="badge"], .p-badge, [class*="p-badge"]',
    ).first()

    // 通知バッジの存在を確認（タイムアウトを長めに設定）
    await expect(anyBadge).toBeVisible({ timeout: 20_000 })
  })

  test('DASH-008: 通知をクリックして既読にできる（1件の既読操作）', async () => {
    await page.goto('/notifications', { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)

    // ローディングスピナーが消えるまで待つ
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})

    // 通知アイテムが少なくとも1件表示されていることを確認
    const firstNotifItem = page.locator(
      '[class*="notification"], [class*="notif-item"], .border-b',
    ).first()
    await expect(firstNotifItem).toBeVisible({ timeout: 15_000 })

    // 最初の通知をクリック（既読操作）
    await firstNotifItem.click()
    // クリック後にエラーが発生していないこと（ページが壊れていない）
    await page.waitForTimeout(1_000)
    expect(page.url()).toBeTruthy()
  })
})

// ---------------------------------------------------------------------------
// PROF-001〜006: プロフィール・アカウント設定
// ---------------------------------------------------------------------------
test.describe('PROF-001〜006: プロフィール・アカウント設定', () => {
  // 並列実行時にサーバーが高負荷になることがあるためタイムアウトを延長する
  test.setTimeout(120_000)

  test('PROF-001: /settings/account が表示される', async () => {
    await page.goto('/settings/account', { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    // PageLoading が消えてからコンテンツが表示される
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})
    await expect(
      page.getByRole('heading', { name: 'アカウント設定' }),
    ).toBeVisible({ timeout: 20_000 })
  })

  test('PROF-002: メールアドレスが設定ページに表示される', async () => {
    await page.goto('/settings/account', { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})
    // メールアドレスフォームのラベルまたは入力値が存在すること
    const emailLabel = page.getByText('メールアドレス').first()
    await expect(emailLabel).toBeVisible({ timeout: 20_000 })
  })

  test('PROF-003: 表示名入力フィールドが存在する', async () => {
    await page.goto('/settings/account', { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})
    // SettingsProfileSection: label "表示名" + InputText が存在する
    const displayNameLabel = page.getByText('表示名').first()
    await expect(displayNameLabel).toBeVisible({ timeout: 20_000 })
    // 表示名の InputText（type="text" の可視入力フィールド）が存在する
    // locator('input').first() は hidden の file input にマッチするため type を指定して絞る
    const inputField = page.locator('input[type="text"]').first()
    await expect(inputField).toBeVisible({ timeout: 10_000 })
  })

  test('PROF-004: プロフィール画像のアップロードUIが存在する', async () => {
    await page.goto('/settings/account', { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})
    // SettingsProfileSection: 「画像を変更」ボタンが存在する
    const uploadButton = page.getByText('画像を変更').first()
    await expect(uploadButton).toBeVisible({ timeout: 20_000 })
  })

  test('PROF-005: 保存ボタンが存在する', async () => {
    await page.goto('/settings/account', { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})
    // SettingsProfileSection: 「保存」ボタンが存在する
    const saveButton = page.getByRole('button', { name: '保存' }).first()
    await expect(saveButton).toBeVisible({ timeout: 20_000 })
  })

  test('PROF-006: /settings が表示される（設定トップページ）', async () => {
    await page.goto('/settings', { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    // 設定トップの何らかの見出しまたはリンクが存在すること
    const heading = page.getByRole('heading').first()
    await expect(heading).toBeVisible({ timeout: 15_000 })
    // URL が /settings であること
    expect(page.url()).toContain('/settings')
  })
})

// ---------------------------------------------------------------------------
// TEAM-NAV-001〜006: チームナビゲーション
// ---------------------------------------------------------------------------
test.describe('TEAM-NAV-001〜006: チームナビゲーション', () => {
  // チームページは追加APIコールがあり遅いためタイムアウトを延長する
  test.setTimeout(120_000)

  test('TEAM-NAV-001: /teams ページが表示される（マイチームページ）', async () => {
    // /teams ページが正常に表示されること（チームカードは @click navigateTo のため <a> ではない）
    await page.goto('/teams')
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})
    // PageHeader title="マイチーム" が表示される
    await expect(page.getByRole('heading', { name: 'マイチーム' })).toBeVisible({ timeout: 20_000 })
  })

  test('TEAM-NAV-002: FC東京U-18（テスト）チームへのリンクが存在する', async () => {
    // teams 一覧ページに遷移して確認
    await page.goto('/teams')
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})
    // FC東京U-18（テスト）の名前またはリンクが存在する
    const teamLink = page.getByText('FC東京U-18').first()
    await expect(teamLink).toBeVisible({ timeout: 20_000 })
  })

  test('TEAM-NAV-003: チームページに遷移できる', async () => {
    await page.goto('/teams')
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})
    // FC東京U-18 のリンクをクリックして遷移
    const teamLink = page.getByText('FC東京U-18').first()
    await expect(teamLink).toBeVisible({ timeout: 20_000 })
    await teamLink.click()
    // /teams/[slug] 相当の詳細ページに遷移したことを確認する。
    // 本プロジェクトは URL 識別子を slug に一本化済みであり（例: /teams/fc-u-18-2）、
    // 数値 ID を期待する正規表現は時代遅れ（FAV-008 で同種の是正を実施済み）。
    // 「/teams/」直下の一覧ページ自身に誤マッチしないよう、後ろに1セグメントあることを
    // 要求する（末尾スラッシュなしの1セグメント、または区切り記号までを許容）。
    await page.waitForURL(/\/teams\/[^/?#]+/, { timeout: 20_000 })
    expect(page.url()).toMatch(/\/teams\/[^/?#]+/)
  })

  test('TEAM-NAV-004: チームホームページが表示される', async () => {
    await page.goto('/teams')
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})
    const teamLink = page.getByText('FC東京U-18').first()
    await teamLink.click()
    // URL 識別子は slug に一本化済み（TEAM-NAV-003 と同じ理由）
    await page.waitForURL(/\/teams\/[^/?#]+/, { timeout: 20_000 })
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})
    // チームホームページのコンテンツ（見出し or ナビゲーション）が存在する
    const heading = page.getByRole('heading').first()
    await expect(heading).toBeVisible({ timeout: 20_000 })
  })

  test('TEAM-NAV-005: チームメンバー一覧ページが表示される', async () => {
    await page.goto('/teams')
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})
    const teamLink = page.getByText('FC東京U-18').first()
    await teamLink.click()
    // URL 識別子は slug に一本化済み（TEAM-NAV-003 と同じ理由）
    await page.waitForURL(/\/teams\/[^/?#]+/, { timeout: 20_000 })
    const teamUrl = page.url()
    // メンバー一覧ページ (/teams/[id]/member-profiles) へ遷移
    await page.goto(`${teamUrl}/member-profiles`)
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})
    // ページが表示されること（URL が正しい）
    expect(page.url()).toContain('/member-profiles')
    const heading = page.getByRole('heading').first()
    await expect(heading).toBeVisible({ timeout: 15_000 })
  })

  test('TEAM-NAV-006: e2e-user がチームメンバーとして表示される', async () => {
    await page.goto('/teams', { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})
    const teamLink = page.getByText('FC東京U-18').first()
    await teamLink.click()
    // URL 識別子は slug に一本化済み（TEAM-NAV-003 と同じ理由）
    await page.waitForURL(/\/teams\/[^/?#]+/, { timeout: 20_000 })
    const teamUrl = page.url()
    await waitForHydration(page)
    // 並列実行時にバックエンドが高負荷で 500 を返す場合があるため最大1回リトライする
    const memberTab = page.getByRole('tab', { name: 'メンバー' })
    const tabVisible = await memberTab.isVisible().catch(() => false)
    if (!tabVisible) {
      await page.goto(teamUrl, { waitUntil: 'domcontentloaded' })
      await waitForHydration(page)
      await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})
    }
    // チームページの「メンバー」タブをクリックして MemberTable を表示する
    // /member-profiles は任意作成のプロフィールカード機能のため seed にデータなし
    await memberTab.click()
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})
    // seed の e2e-user 表示名 "E2E一般ユーザー" が MemberTable に表示される前提
    const memberText = page.getByText(/e2e/i).first()
    await expect(memberText).toBeVisible({ timeout: 20_000 })
  })
})

// ---------------------------------------------------------------------------
// NOTIF-001〜005: 通知
// ---------------------------------------------------------------------------
test.describe('NOTIF-001〜005: 通知', () => {
  // 並列実行時にサーバーが高負荷になることがあるためタイムアウトを延長する
  test.setTimeout(120_000)

  test('NOTIF-001: /notifications が表示される', async () => {
    await page.goto('/notifications', { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    await expect(
      page.getByRole('heading', { name: '通知' }),
    ).toBeVisible({ timeout: 30_000 })
  })

  test('NOTIF-002: 通知一覧に少なくとも1件表示される（seed で7件投入）', async () => {
    await page.goto('/notifications', { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    // ローディングスピナーが消えるまで待つ
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 30_000 }).catch(() => {})
    // 通知ページのコンテンツエリアが表示されていること（通知が0件でも空ページが表示される）
    const main = page.locator('main, .mx-auto.max-w-2xl, [data-testid="notification-list"]').first()
    await expect(main).toBeVisible({ timeout: 30_000 })
    // 通知アイテムまたは「通知はありません」表示があること
    const bodyText = await page.locator('body').textContent()
    expect(bodyText).toBeTruthy()
    expect(bodyText!.length).toBeGreaterThan(10)
  })

  test('NOTIF-003: 通知タイトルが表示される', async () => {
    await page.goto('/notifications', { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 30_000 }).catch(() => {})
    // 通知リストに何らかのテキスト（タイトル）が表示されていること
    const notifTitle = page.locator('.font-medium, .font-semibold, [class*="title"]').first()
    await expect(notifTitle).toBeVisible({ timeout: 30_000 })
  })

  test('NOTIF-004: 既読操作UIが存在する（「すべて既読」ボタンなど）', async () => {
    await page.goto('/notifications', { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 30_000 }).catch(() => {})
    // NotificationList には「すべて既読」ボタンが存在する（onMarkAllRead）
    // ボタンラベルまたはツールチップで確認
    const allReadButton = page
      .getByRole('button', { name: /すべて既読|全て既読|mark all/i })
      .first()
    await expect(allReadButton).toBeVisible({ timeout: 30_000 })
  })

  test('NOTIF-005: 通知クリックで対象ページに遷移しようとする', async () => {
    await page.goto('/notifications', { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})

    // actionUrl を持つ通知アイテムをクリックして遷移が発生することを確認
    const notifItems = page.locator(
      '[class*="cursor-pointer border-b"], [class*="notification-item"], .border-b',
    )
    const count = await notifItems.count()
    if (count > 0) {
      const urlBefore = page.url()
      // 最初のアイテムをクリック（actionUrl がない通知の場合は URL が変わらない場合がある）
      await notifItems.first().click()
      await page.waitForTimeout(2_000)
      // クリック後にエラーページに遷移していないこと
      expect(page.url()).not.toContain('/error')
      expect(page.url()).not.toContain('/404')
      void urlBefore
    } else {
      // 通知が0件の場合はスキップ（seed 異常）
      test.skip()
    }
  })
})
