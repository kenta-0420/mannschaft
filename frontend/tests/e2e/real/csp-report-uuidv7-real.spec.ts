import { execFileSync } from 'child_process'
import { expect, test } from '@playwright/test'

const DOCUMENT_URI = 'http://localhost:8081/cmp008-csp-uuidv7'
const ENDPOINT = '/api/v1/security/csp-reports'

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
      'mannschaft',
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

  test('public browser reporting persists UUIDv7, aggregates duplicates, and exposes no read API', async ({ browser }) => {
    const context = await browser.newContext()
    const page = await context.newPage()
    await page.goto('/')

    const report = {
      'document-uri': DOCUMENT_URI,
      'blocked-uri': 'https://blocked.cmp008.example/script.js',
      'violated-directive': 'script-src',
      'effective-directive': 'script-src-elem',
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

    expect(await post(JSON.stringify({ 'csp-report': report }))).toBe(204)
    const first = mysql(
      `SELECT CONCAT(HEX(id), '\\t', occurrence_count) FROM csp_reports WHERE document_uri = '${DOCUMENT_URI}'`,
    ).split('\t')
    expect(first[0]).toHaveLength(32)
    expect(first[0]?.[12]).toBe('7')
    expect(first[0]?.[16]?.toLowerCase()).toMatch(/[89ab]/)
    expect(first[1]).toBe('1')

    expect(await post(JSON.stringify({ 'csp-report': report }))).toBe(204)
    const second = mysql(
      `SELECT CONCAT(HEX(id), '\\t', occurrence_count) FROM csp_reports WHERE document_uri = '${DOCUMENT_URI}'`,
    ).split('\t')
    expect(second[0]).toBe(first[0])
    expect(second[1]).toBe('2')
    expect(mysql(`SELECT COUNT(*) FROM csp_reports WHERE document_uri = '${DOCUMENT_URI}'`)).toBe('1')

    expect(await post('')).toBe(204)
    expect(await post('{invalid-json')).toBe(204)
    expect(mysql(`SELECT occurrence_count FROM csp_reports WHERE document_uri = '${DOCUMENT_URI}'`)).toBe('2')

    const getStatus = await page.evaluate(async endpoint => (await fetch(endpoint)).status, ENDPOINT)
    expect(getStatus).toBeGreaterThanOrEqual(400)

    await context.close()
  })
})
