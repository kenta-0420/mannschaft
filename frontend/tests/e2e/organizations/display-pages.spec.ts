import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { ORG_ID, mockOrg, mockOrgFeatureApis } from './helpers'

test.describe('ORG-DISP-001〜039: 組織各ページ表示確認', () => {
  test.beforeEach(async ({ page }) => {
    await mockOrg(page)
    await mockOrgFeatureApis(page)
  })

  test('ORG-DISP-001: 活動記録ページが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/activities`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '活動記録' })).toBeVisible({ timeout: 10_000 })
  })

  test('ORG-DISP-002: アクセス解析ページが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/analytics`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'アクセス解析' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('ORG-DISP-003: 年間行事計画ページが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/annual-plan`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '年間行事計画' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('ORG-DISP-004: 監査ログページが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/audit-logs`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '監査ログ' })).toBeVisible({ timeout: 10_000 })
  })

  test('ORG-DISP-005: ブログ・お知らせページが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/blog`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'ブログ・お知らせ' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('ORG-DISP-006: 予算・会計ページが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/budget`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '予算・会計' })).toBeVisible({ timeout: 10_000 })
  })

  test('ORG-DISP-007: 掲示板ページが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/bulletin`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '掲示板' })).toBeVisible({ timeout: 10_000 })
  })

  test('ORG-DISP-008: チャットページが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/chat`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'チャット' })).toBeVisible({ timeout: 10_000 })
  })

  test('ORG-DISP-009: 回覧板ページが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/circulation`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '回覧板' })).toBeVisible({ timeout: 10_000 })
  })

  test('ORG-DISP-010: ダイレクトメールページが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/direct-mail`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'ダイレクトメール' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('ORG-DISP-011: 備品管理ページが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/equipment`)
    await waitForHydration(page)
    await expect(page.getByText('備品管理')).toBeVisible({ timeout: 10_000 })
  })

  test('ORG-DISP-012: イベントページが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/events`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'イベント' })).toBeVisible({ timeout: 10_000 })
  })

  test('ORG-DISP-013: 共用施設予約ページが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/facilities`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '共用施設予約' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('ORG-DISP-014: ファイル共有ページが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/files`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'ファイル共有' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('ORG-DISP-015: フォームページが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/forms`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'フォーム' })).toBeVisible({ timeout: 10_000 })
  })

  test('ORG-DISP-016: フォームテンプレートページが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/forms/templates`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'フォームテンプレート' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('ORG-DISP-017: ギャラリーページが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/gallery`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'ギャラリー', level: 1 })).toBeVisible({ timeout: 10_000 })
  })

  test('ORG-DISP-018: ゲーミフィケーション設定ページが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/gamification`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'ゲーミフィケーション設定' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('ORG-DISP-019: インシデント管理ページが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/incidents`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'インシデント管理', level: 1 })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('ORG-DISP-020: ナレッジベースページが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/kb`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'ナレッジベース' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('ORG-DISP-021: QR会員証管理ページが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/member-cards`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'QR会員証管理' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('ORG-DISP-022: メンバー紹介ページが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/member-profiles`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'メンバー紹介' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('ORG-DISP-023: オンボーディング管理ページが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/onboarding`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'オンボーディング管理' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('ORG-DISP-024: 駐車場管理ページが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/parking`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '駐車場管理' })).toBeVisible({ timeout: 10_000 })
  })

  test('ORG-DISP-025: 支払い管理ページが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/payments`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '支払い管理' })).toBeVisible({ timeout: 10_000 })
  })

  test('ORG-DISP-026: 順番待ちページが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/queue`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '順番待ち' })).toBeVisible({ timeout: 10_000 })
  })

  test('ORG-DISP-027: 住民台帳ページが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/residents`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '住民台帳' })).toBeVisible({ timeout: 10_000 })
  })

  test('ORG-DISP-028: 安否確認ページが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/safety`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '安否確認' })).toBeVisible({ timeout: 10_000 })
  })

  test('ORG-DISP-029: スケジュールページが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/schedule`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'スケジュール' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('ORG-DISP-030: デジタルサイネージページが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/signage`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'デジタルサイネージ' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('ORG-DISP-031: アンケート・投票ページが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/surveys`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'アンケート・投票' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('ORG-DISP-032: タイムラインページが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/timeline`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'タイムライン' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('ORG-DISP-033: タイムラインダイジェストページが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/timeline-digest`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'タイムラインダイジェスト' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('ORG-DISP-034: 時間割設定ページが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/timetable`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: /時間割/ })).toBeVisible({ timeout: 10_000 })
  })

  test('ORG-DISP-035: TODOページが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/todos`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'TODO' })).toBeVisible({ timeout: 10_000 })
  })

  test('ORG-DISP-036: 翻訳管理ページが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/translations`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '翻訳管理' })).toBeVisible({ timeout: 10_000 })
  })

  test('ORG-DISP-037: 議決権行使ページが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/voting`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '議決権行使' })).toBeVisible({ timeout: 10_000 })
  })

  test('ORG-DISP-038: Webhookページが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/webhooks`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'Webhook' })).toBeVisible({ timeout: 10_000 })
  })

  test('ORG-DISP-039: ワークフローテンプレートページが表示される', async ({ page }) => {
    await page.goto(`/organizations/${ORG_ID}/workflows/templates`)
    await waitForHydration(page)
    await expect(page.getByRole('heading').first()).toBeVisible({ timeout: 10_000 })
  })
})
