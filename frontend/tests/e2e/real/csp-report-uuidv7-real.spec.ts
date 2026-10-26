import { execFileSync } from 'child_process'
import { expect, test } from '@playwright/test'

const DOCUMENT_URI = new URL(
  '/login?cmp008-csp-uuidv7=1',
  process.env.BASE_URL ?? 'http://localhost:3001',
).toString()
const ENDPOINT = '/api/v1/security/csp-reports'
const DATABASE = process.env.E2E_MYSQL_DATABASE ?? 'mannschaft_cmp008_e2e'

if (!/^mannschaft_cmp008_e2e$/.test(DATABASE)) {
  throw new Error('CMP-008 E2E must use its isolated MySQL database')
}

function mysql(sql: string): string {
  return execFileSync(
    'wsl.exe',
    [
      '-e',
      'docker',
      'exec',
      'mannschaft-mysql',
      'mysql',
      '-uroot',
      '-proot',
      '--batch',
      '--skip-column-names',
      DATABASE,
      '-e',
      sql,
    ],
    { encoding: 'utf8' },
  ).trim()
}

function cleanup(): void {
  mysql(`DELETE FROM csp_reports WHERE document_uri = '${DOCUMENT_URI}'`)
}

test.describe('CMP-008 CSP report UUIDv7 actual browser E2E', () => {
  test.describe.configure({ mode: 'serial' })

  test.beforeAll(cleanup)
  test.afterAll(cleanup)

  test('ブラウザのCSP違反が自動報告され、UUIDv7で保存される', async ({ browser }) => {
    test.setTimeout(180_000)
    const context = await browser.newContext()
    const page = await context.newPage()
    const navigationResponse = await page.goto('/login?cmp008-csp-uuidv7=1', {
      waitUntil: 'commit',
    })
    expect(navigationResponse?.headers()['content-security-policy']).toContain(`report-uri ${ENDPOINT}`)
    await page.waitForFunction(() => document.body !== null)

    const reportResponse = page.waitForResponse(response =>
      response.url().includes(ENDPOINT)
        && response.request().method() === 'POST'
        && (response.request().postData() ?? '').includes('blocked.cmp008.example'),
    )
    await page.evaluate(() => {
      const frame = document.createElement('iframe')
      frame.src = 'https://blocked.cmp008.example/frame'
      document.body.appendChild(frame)
    })
    expect((await reportResponse).status()).toBe(204)

    const report = {
      'document-uri': DOCUMENT_URI,
      'blocked-uri': '',
      'violated-directive': '',
      'effective-directive': 'frame-src',
      disposition: 'enforce',
      'status-code': 200,
    }

    const post = async (body: string) => page.evaluate(
      async ({ endpoint, requestBody }) => {
        const response = await fetch(endpoint, {
          method: 'POST',
          headers: { 'Content-Type': 'application/csp-report' },
          body: requestBody,
        })
        return response.status
      },
      { endpoint: ENDPOINT, requestBody: body },
    )

    const reportFilter = `document_uri = '${DOCUMENT_URI}' AND blocked_uri LIKE 'https://blocked.cmp008.example%'`
    const first = mysql(
      `SELECT HEX(id), occurrence_count, blocked_uri, violated_directive FROM csp_reports WHERE ${reportFilter}`,
    ).split('\t')
    expect(first[0]).toHaveLength(32)
    expect(first[0]?.[12]).toBe('7')
    expect(first[0]?.[16]?.toLowerCase()).toMatch(/[89ab]/)
    expect(Number(first[1])).toBeGreaterThanOrEqual(1)
    report['blocked-uri'] = first[2] ?? ''
    report['violated-directive'] = first[3] ?? ''

    // 自動送信と同じ報告パターンを補助APIで再送し、集約の境界を確認する。
    expect(await post(JSON.stringify({ 'csp-report': report }))).toBe(204)
    const second = mysql(
      `SELECT HEX(id), occurrence_count FROM csp_reports WHERE ${reportFilter}`,
    ).split('\t')
    expect(second[0]).toBe(first[0])
    expect(Number(second[1])).toBeGreaterThan(Number(first[1]))
    expect(mysql(`SELECT COUNT(*) FROM csp_reports WHERE ${reportFilter}`)).toBe('1')

    expect(await post('')).toBe(204)
    expect(await post('{invalid-json')).toBe(204)
    expect(Number(mysql(`SELECT occurrence_count FROM csp_reports WHERE ${reportFilter}`)))
      .toBe(Number(second[1]))

    const getStatus = await page.evaluate(async endpoint => (await fetch(endpoint)).status, ENDPOINT)
    expect(getStatus).toBeGreaterThanOrEqual(400)

    await context.close()
  })
})
