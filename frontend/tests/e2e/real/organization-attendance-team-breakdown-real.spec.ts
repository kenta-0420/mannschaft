/**
 * CMP-013: 組織予定の出欠チーム別内訳を、実 BE / DB と実 UI で確認する。
 *
 * 実行例:
 *   cd frontend
 *   BASE_URL=http://localhost:3001 API_BASE_URL=http://localhost:8081 \
 *     npx playwright test --config=playwright-real.config.ts \
 *     tests/e2e/real/organization-attendance-team-breakdown-real.spec.ts
 *
 * mock / page.route は使わない。予定の作成と後始末だけを API で行い、
 * /calendar で予定を実際に開いて内訳と CSV ダウンロードを検証する。
 */

import { expect, request as pwRequest, test, type APIRequestContext, type Download } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

const API_BASE_URL = process.env.API_BASE_URL ?? 'http://localhost:8080'
const API_V1 = `${API_BASE_URL}/api/v1`
const ADMIN = { email: 'e2e-admin@test.mannschaft.local', password: 'TestPass2026!' }

interface OrganizationSummary {
  id: number
  slug: string
  role: string
}

let api: APIRequestContext
let adminToken = ''
let organization: OrganizationSummary | undefined
let scheduleId: number | undefined
const title = `CMP013 組織出欠内訳 ${Date.now()}`

function headers(): Record<string, string> {
  return { Authorization: `Bearer ${adminToken}`, 'Content-Type': 'application/json' }
}

async function login(): Promise<string> {
  const response = await api.post(`${API_V1}/auth/login`, { data: ADMIN })
  expect(response.status(), 'e2e-admin の API ログイン').toBe(200)
  return ((await response.json()) as { data: { accessToken: string } }).data.accessToken
}

async function downloadText(download: Download): Promise<string> {
  const stream = await download.createReadStream()
  if (!stream) throw new Error('CSV ダウンロードのストリームを取得できませんでした')

  const chunks: Buffer[] = []
  for await (const chunk of stream) {
    chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk))
  }
  return Buffer.concat(chunks).toString('utf-8')
}

test.describe('CMP-013: 組織出欠チーム別内訳 real-tier', () => {
  test.describe.configure({ mode: 'serial' })
  test.setTimeout(90_000)

  test.beforeAll(async () => {
    api = await pwRequest.newContext()
    adminToken = await login()

    const organizationsResponse = await api.get(`${API_V1}/me/organizations`, { headers: headers() })
    expect(organizationsResponse.status(), '管理対象組織の取得').toBe(200)
    const organizations = ((await organizationsResponse.json()) as { data: OrganizationSummary[] }).data
    organization = organizations.find(item => item.role === 'ADMIN' || item.role === 'SYSTEM_ADMIN')
    expect(organization, 'e2e-admin が管理する組織が seed に存在する').toBeTruthy()

    const start = new Date(Date.now() + 10 * 60 * 1000)
    const end = new Date(start.getTime() + 60 * 60 * 1000)
    const createResponse = await api.post(`${API_V1}/organizations/${organization!.slug}/schedules`, {
      headers: headers(),
      data: {
        title,
        startAt: start.toISOString(),
        endAt: end.toISOString(),
        allDay: false,
        eventType: 'OTHER',
        attendanceRequired: true,
        teamBreakdownEnabled: true,
      },
    })
    expect(createResponse.status(), 'チーム別内訳を有効化した組織予定の作成').toBe(201)
    scheduleId = ((await createResponse.json()) as { data: { id: number } }).data.id

    await expect.poll(async () => {
      const response = await api.get(
        `${API_V1}/organizations/${organization!.slug}/schedules/${scheduleId}/attendances/team-breakdown`,
        { headers: headers() },
      )
      if (!response.ok()) return false
      const body = (await response.json()) as { data: { byTeam: unknown[] | null } }
      return (body.data.byTeam?.length ?? 0) > 0
    }, { timeout: 30_000, message: '出欠生成後にチーム別内訳が取得できる' }).toBe(true)
  })

  test.afterAll(async () => {
    try {
      if (organization && scheduleId) {
        const response = await api.delete(
          `${API_V1}/organizations/${organization.slug}/schedules/${scheduleId}?updateScope=THIS_ONLY`,
          { headers: headers() },
        )
        expect(response.status(), '実機試験で作成した組織予定の後始末').toBe(204)
      }
    }
    finally {
      await api?.dispose()
    }
  })

  test('管理者がカレンダーで予定を開くと内訳表を確認でき、CSV をダウンロードできる', async ({ page }) => {
    await loginViaApi(page, ADMIN, { apiBaseUrl: API_BASE_URL })
    await page.goto('/calendar')
    await waitForHydration(page)

    const calendarEvent = page.getByText(title, { exact: true }).first()
    await expect(calendarEvent, '作成した組織予定が /calendar に表示される').toBeVisible({ timeout: 30_000 })
    await calendarEvent.click()

    const panel = page.getByTestId('attendance-team-breakdown-panel')
    await expect(panel, '予定詳細にチーム別内訳パネルが表示される').toBeVisible({ timeout: 30_000 })
    const table = page.getByTestId('attendance-team-breakdown-table')
    await expect(table, 'チーム別内訳の表が表示される').toBeVisible({ timeout: 30_000 })
    await expect(table.locator('tbody tr'), '組織の所属チームごとの行がある').not.toHaveCount(0)

    const downloadPromise = page.waitForEvent('download')
    await panel.getByTestId('attendance-team-breakdown-export').click()
    const download = await downloadPromise
    expect(download.suggestedFilename()).toBe(`attendance-team-breakdown-${scheduleId}.csv`)

    const csv = await downloadText(download)
    expect(csv, 'CSV にチーム別出欠のヘッダーがある')
      .toContain('チーム名,出席,一部参加,欠席,未回答,合計')
    expect(csv, 'CSV に合計行がある').toContain('\n合計,')
  })
})
