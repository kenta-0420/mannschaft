import { test, expect, type Page } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

/**
 * F01.10 マイページ履歴書・職務経歴書 E2E テスト（RESUME-001〜007）
 *
 * storageState（user.json）で認証済み状態を使用。
 * バックエンドが起動していることを前提とする。
 * resume の CRUD API は page.route でモックし、実データに依存しない。
 *
 * - RESUME-001: 一覧ページ表示（バージョンあり）
 * - RESUME-002: 一覧ページ・空状態表示
 * - RESUME-003: 新規作成ダイアログ → エディタへ遷移
 * - RESUME-004: 編集ボタン → エディタへ遷移（NuxtLink ラップ修正の確認）
 * - RESUME-005: エディタの「一覧に戻る」→ 一覧へ戻る
 * - RESUME-006: プレビューページの「一覧に戻る」→ 一覧へ戻る
 * - RESUME-007: プレビューページの「エディタに戻る」→ エディタへ戻る
 */

const RESUME_ID = 'resume-uuid-001'
const RESUME_ID_2 = 'resume-uuid-002'

// === モックデータ ===

function buildResumeSummary(id: string, title: string) {
  return {
    id,
    title,
    hasPhoto: false,
    eraFormat: 'WESTERN',
    updatedAt: '2026-05-25T10:00:00+09:00',
  }
}

function buildResumeDetail(id: string, title: string) {
  return {
    id,
    title,
    eraFormat: 'WESTERN',
    photoUrl: null,
    currentAddress: null,
    currentAddressKana: null,
    contactAddress: null,
    contactAddressKana: null,
    contactPhone: null,
    contactEmail: null,
    motivation: null,
    selfPr: null,
    personalRequest: null,
    commuteMinutes: null,
    dependentsCount: null,
    hasSpouse: null,
    spouseSupport: null,
    careerSummary: null,
    skillsSummary: null,
    version: 1,
    educations: [],
    careers: [],
    qualifications: [],
    skills: [],
  }
}

// === モック設定（resume API のみ。認証は storageState に任せる） ===

async function setupResumeMocks(page: Page, resumes: ReturnType<typeof buildResumeSummary>[]) {
  // GET /api/v1/resumes → 一覧 / POST → 新規作成
  await page.route('**/api/v1/resumes', async (route) => {
    const method = route.request().method()
    if (method === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: resumes }),
      })
    }
    else if (method === 'POST') {
      const newResume = buildResumeDetail(RESUME_ID, '下書き 2026-05-25')
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({ data: newResume }),
      })
    }
    else {
      await route.continue()
    }
  })

  // GET /api/v1/resumes/:id → 詳細 / PUT → 保存
  await page.route('**/api/v1/resumes/**', async (route) => {
    const method = route.request().method()
    const url = route.request().url()

    if (method === 'GET' && !url.includes('/preview') && !url.includes('/export') && !url.includes('/photo')) {
      const id = url.split('/resumes/')[1]?.split('?')[0] ?? RESUME_ID
      const title = id === RESUME_ID ? 'テスト履歴書 2026' : 'テスト履歴書 2'
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: buildResumeDetail(id, title) }),
      })
    }
    else if (method === 'PUT') {
      const id = url.split('/resumes/')[1]?.split('?')[0] ?? RESUME_ID
      const title = id === RESUME_ID ? 'テスト履歴書 2026' : 'テスト履歴書 2'
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: { ...buildResumeDetail(id, title), version: 2 } }),
      })
    }
    else if (url.includes('/preview')) {
      await route.fulfill({
        status: 200,
        contentType: 'application/pdf',
        body: Buffer.from('%PDF-1.0 test'),
      })
    }
    else {
      await route.continue()
    }
  })
}

// === テスト ===

test.describe('RESUME-001〜007: 履歴書・職務経歴書', () => {
  test('RESUME-001: 一覧ページが表示される（バージョンあり）', async ({ page }) => {
    const resumes = [
      buildResumeSummary(RESUME_ID, 'テスト履歴書 2026'),
      buildResumeSummary(RESUME_ID_2, '職務経歴書 2026'),
    ]
    await setupResumeMocks(page, resumes)

    await page.goto('/my/resume')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '履歴書・職務経歴書' })).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('テスト履歴書 2026')).toBeVisible({ timeout: 5_000 })
    await expect(page.getByText('職務経歴書 2026')).toBeVisible({ timeout: 5_000 })

    const editButtons = page.getByRole('button', { name: '編集' })
    await expect(editButtons.first()).toBeVisible({ timeout: 5_000 })
  })

  test('RESUME-002: 一覧ページ・空状態が表示される', async ({ page }) => {
    await setupResumeMocks(page, [])

    await page.goto('/my/resume')
    await waitForHydration(page)

    await expect(page.getByRole('button', { name: '新規作成' })).toBeVisible({ timeout: 10_000 })
  })

  test('RESUME-003: 新規作成ダイアログ表示 → エディタへ遷移', async ({ page }) => {
    await setupResumeMocks(page, [])

    await page.goto('/my/resume')
    await waitForHydration(page)

    await page.getByRole('button', { name: '新規作成' }).first().click()

    await expect(page.getByRole('dialog')).toBeVisible({ timeout: 5_000 })

    await page.getByRole('dialog').getByRole('button', { name: '新規作成' }).click()

    await expect(page).toHaveURL(`/my/resume/${RESUME_ID}`, { timeout: 10_000 })
  })

  test('RESUME-004: 編集ボタン → エディタへ遷移（NuxtLink ラップ修正確認）', async ({ page }) => {
    const resumes = [buildResumeSummary(RESUME_ID, 'テスト履歴書 2026')]
    await setupResumeMocks(page, resumes)

    await page.goto('/my/resume')
    await waitForHydration(page)

    await page.getByRole('button', { name: '編集' }).first().click()

    await expect(page).toHaveURL(`/my/resume/${RESUME_ID}`, { timeout: 10_000 })
  })

  test('RESUME-005: エディタ「一覧に戻る」→ 一覧へ戻る', async ({ page }) => {
    await setupResumeMocks(page, [buildResumeSummary(RESUME_ID, 'テスト履歴書 2026')])

    await page.goto(`/my/resume/${RESUME_ID}`)
    await waitForHydration(page)

    await expect(page.getByText('テスト履歴書 2026')).toBeVisible({ timeout: 10_000 })

    await page.getByRole('link', { name: '一覧に戻る' }).click()

    await expect(page).toHaveURL('/my/resume', { timeout: 10_000 })
  })

  test('RESUME-006: プレビューページ「一覧に戻る」→ 一覧へ戻る', async ({ page }) => {
    await setupResumeMocks(page, [buildResumeSummary(RESUME_ID, 'テスト履歴書 2026')])

    await page.goto(`/my/resume/${RESUME_ID}/preview?type=rirekisho&format=pdf`)
    await waitForHydration(page)

    await expect(page.getByRole('link', { name: '一覧に戻る' })).toBeVisible({ timeout: 10_000 })
    await page.getByRole('link', { name: '一覧に戻る' }).click()

    await expect(page).toHaveURL('/my/resume', { timeout: 10_000 })
  })

  test('RESUME-007: プレビューページ「エディタに戻る」→ エディタへ戻る', async ({ page }) => {
    await setupResumeMocks(page, [buildResumeSummary(RESUME_ID, 'テスト履歴書 2026')])

    await page.goto(`/my/resume/${RESUME_ID}/preview?type=rirekisho&format=pdf`)
    await waitForHydration(page)

    await expect(page.getByRole('link', { name: 'エディタに戻る' })).toBeVisible({ timeout: 10_000 })
    await page.getByRole('link', { name: 'エディタに戻る' }).click()

    await expect(page).toHaveURL(`/my/resume/${RESUME_ID}`, { timeout: 10_000 })
  })
})
