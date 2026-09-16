import { expect, test, type Page } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

function localDateTime(minutesFromNow: number): string {
  const value = new Date(Date.now() + minutesFromNow * 60_000)
  const pad = (part: number) => String(part).padStart(2, '0')
  return `${value.getFullYear()}-${pad(value.getMonth() + 1)}-${pad(value.getDate())}`
    + `T${pad(value.getHours())}:${pad(value.getMinutes())}`
}

async function fillDateTime(page: Page, id: string, value: string): Promise<void> {
  const input = page.locator(`input#${id}`)
  await input.fill(value)
  await input.dispatchEvent('change')
}

test('MARKET-FILTER-UI-001: 手助けモーダルを開いて市の主要操作を確認できる', async ({ page }) => {
  await page.goto('/market')
  await waitForHydration(page)

  await page.getByTestId('page-header-help').click()
  const modal = page.getByTestId('market-guide-modal')
  await expect(modal).toBeVisible()
  await expect(modal.getByText('市とは？', { exact: true })).toBeVisible()
  await expect(modal.getByText('検索ボタンを押して条件に合う札を表示します')).toBeVisible()
  await expect(modal.getByText('個人札を下書きから公開する', { exact: true })).toBeVisible()
  await expect(modal.getByText('公開範囲と表示名', { exact: true })).toBeVisible()
  await expect(modal.locator('h2')).toHaveCount(7)
})

test('MARKET-FILTER-UI-002: 条件変更では検索せず、検索ボタンで地域・締切順をまとめて適用する', async ({ page }) => {
  const initialResponsePromise = page.waitForResponse(response =>
    response.url().includes('/api/v1/public/market/listings')
    && response.request().method() === 'GET',
  )
  await page.goto('/market')
  await waitForHydration(page)
  expect((await initialResponsePromise).status()).toBe(200)

  const listingRequests: string[] = []
  page.on('request', (request) => {
    if (request.url().includes('/api/v1/public/market/listings')) {
      listingRequests.push(request.url())
    }
  })

  await page.getByTestId('market-prefecture-select').click()
  await page.getByRole('option', { name: '大分県', exact: true }).click()

  await page.getByTestId('market-category-select').click()
  const translatedGenre = page.getByRole('option', { name: '練習試合相手募集', exact: true })
  await expect(translatedGenre).toBeVisible()
  await translatedGenre.click()

  await page.getByTestId('market-sort-select').click()
  await page.getByRole('option', { name: '締切が近い順', exact: true }).click()
  await page.waitForTimeout(600)
  expect(listingRequests, '条件を選んだだけでは札一覧を再検索しない').toHaveLength(0)

  const searchResponsePromise = page.waitForResponse(response => {
    const url = new URL(response.url())
    return url.pathname.endsWith('/api/v1/public/market/listings')
      && url.searchParams.get('prefecture') === '44'
      && url.searchParams.get('include_region_none') === 'false'
      && url.searchParams.get('sort') === 'DEADLINE_ASC'
  })
  await page.getByTestId('market-search-button').click()
  const searchResponse = await searchResponsePromise
  expect(searchResponse.status()).toBe(200)
  await expect(page).toHaveURL(/prefecture=44/)
  await expect(page).toHaveURL(/sort=DEADLINE_ASC/)
})

test('MARKET-FILTER-UI-003: 開催場所は＊付き必須で、空白だけでは画面からPOSTしない', async ({ page }) => {
  await page.goto('/me/market?create=true')
  await waitForHydration(page)

  const form = page.locator('form').first()
  const location = form.locator('#location')
  await expect(location).toHaveAttribute('required', '')
  await expect(form.locator('label[for="location"]')).toContainText('＊')

  await form.locator('#title').fill(`E2E-開催場所必須-${Date.now()}`)
  await form.locator('#category').click()
  await page.getByRole('option').filter({ hasText: '練習試合相手募集' }).first().click()
  await fillDateTime(page, 'startAt', localDateTime(24 * 60))
  await fillDateTime(page, 'endAt', localDateTime(27 * 60))
  await fillDateTime(page, 'applicationDeadline', localDateTime(60))
  await fillDateTime(page, 'autoCancelAt', localDateTime(60))
  await form.locator('#capacity input').fill('1')
  await form.locator('#minCapacity input').fill('1')
  await location.fill('   ')

  let createRequests = 0
  page.on('request', (request) => {
    if (request.url().endsWith('/api/v1/me/market/listings') && request.method() === 'POST') {
      createRequests += 1
    }
  })
  await form.locator('button[type="submit"]').click()

  await expect(page.getByTestId('recruitment-form-validation-error')).toBeVisible()
  expect(createRequests).toBe(0)
})
