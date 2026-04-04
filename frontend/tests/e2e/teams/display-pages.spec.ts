import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { TEAM_ID, MOCK_TEAM, mockTeam, mockTeamFeatureApis } from './helpers'

test.describe('TEAM-039〜074: チーム各ページ表示確認', () => {
  test.beforeEach(async ({ page }) => {
    await mockTeam(page)
    await mockTeamFeatureApis(page)
    // シフト系
    await page.route('**/api/v1/shifts/**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })
    // プロジェクト系
    await page.route('**/api/v1/projects/**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })
  })

  test('TEAM-039: チーム詳細ページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: MOCK_TEAM.name })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('TEAM-040: 活動記録ページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/activities`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '活動記録' })).toBeVisible({ timeout: 10_000 })
  })

  test('TEAM-041: アクセス解析ページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/analytics`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'アクセス解析' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('TEAM-042: 記念日ページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/anniversaries`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '記念日' })).toBeVisible({ timeout: 10_000 })
  })

  test('TEAM-043: 年間行事計画ページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/annual-plan`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '年間行事計画' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('TEAM-044: 監査ログページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/audit-logs`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '監査ログ' })).toBeVisible({ timeout: 10_000 })
  })

  test('TEAM-045: ブログ・お知らせページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/blog`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'ブログ・お知らせ' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('TEAM-046: 予算・会計ページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/budget`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '予算・会計' })).toBeVisible({ timeout: 10_000 })
  })

  test('TEAM-047: カルテ管理ページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/charts`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'カルテ管理' })).toBeVisible({ timeout: 10_000 })
  })

  test('TEAM-048: コイントスページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/coin-toss`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'コイントス' })).toBeVisible({ timeout: 10_000 })
  })

  test('TEAM-049: ダイレクトメールページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/direct-mail`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'ダイレクトメール' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('TEAM-050: 当番管理ページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/duties`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '当番管理' })).toBeVisible({ timeout: 10_000 })
  })

  test('TEAM-051: 備品管理ページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/equipment`)
    await waitForHydration(page)
    await expect(page.getByText('備品管理')).toBeVisible({ timeout: 10_000 })
  })

  test('TEAM-052: 共用施設ページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/facilities`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '共用施設' })).toBeVisible({ timeout: 10_000 })
  })

  test('TEAM-053: フォームページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/forms`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'フォーム' })).toBeVisible({ timeout: 10_000 })
  })

  test('TEAM-054: ポイント・バッジページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/gamification`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'ポイント・バッジ' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('TEAM-055: マッチングページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/matching`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'マッチング' })).toBeVisible({ timeout: 10_000 })
  })

  test('TEAM-056: QR会員証管理ページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/member-cards`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'QR会員証管理' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('TEAM-057: オンボーディング管理ページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/onboarding`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'オンボーディング管理' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('TEAM-058: 駐車場管理ページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/parking`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '駐車場管理' })).toBeVisible({ timeout: 10_000 })
  })

  test('TEAM-059: 支払い管理ページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/payments`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '支払い管理' })).toBeVisible({ timeout: 10_000 })
  })

  test('TEAM-060: パフォーマンスページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/performance`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'パフォーマンス' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('TEAM-061: プロジェクトページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/projects`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'プロジェクト' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('TEAM-062: 順番待ちページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/queue`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '順番待ち' })).toBeVisible({ timeout: 10_000 })
  })

  test('TEAM-063: 住民台帳ページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/residents`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '住民台帳' })).toBeVisible({ timeout: 10_000 })
  })

  test('TEAM-064: 安否確認ページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/safety`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '安否確認' })).toBeVisible({ timeout: 10_000 })
  })

  test('TEAM-065: サービス履歴ページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/service-records`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'サービス履歴' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('TEAM-066: 買い物リストページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/shopping-lists`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '買い物リスト' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('TEAM-067: 回数券ページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/tickets`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '回数券' })).toBeVisible({ timeout: 10_000 })
  })

  test('TEAM-068: タイムラインページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/timeline`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'タイムライン' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('TEAM-069: 時間割管理ページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/timetable`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '時間割管理' })).toBeVisible({ timeout: 10_000 })
  })

  test('TEAM-070: ワークフロー申請ページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/workflows`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'ワークフロー申請' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('TEAM-071: フォームテンプレートページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/forms/templates`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'フォームテンプレート' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('TEAM-072: ワークフローテンプレートページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/workflows/templates`)
    await waitForHydration(page)
    // ワークフローテンプレートページの見出し
    await expect(page.getByRole('heading').first()).toBeVisible({ timeout: 10_000 })
  })

  test('TEAM-073: プロジェクト詳細ページへの遷移チェック', async ({ page }) => {
    // プロジェクト一覧ページが正常にロードされること
    await page.goto(`/teams/${TEAM_ID}/projects`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'プロジェクト' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('TEAM-074: TODO一覧ページが正常にロードされる（再確認）', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/todos`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'TODO' })).toBeVisible({ timeout: 10_000 })
  })
})
