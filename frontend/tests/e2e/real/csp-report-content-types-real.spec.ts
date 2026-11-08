import { execFileSync } from 'child_process'
import { randomUUID } from 'crypto'
import { expect, test } from '@playwright/test'

const ENDPOINT = '/api/v1/security/csp-reports'
const CONTAINER = 'october0839-0338-20261026-mysql'
const DATABASE = 'october_first_wave'
const BASE_URL = process.env.BASE_URL ?? 'http://localhost:3001'
const DOCUMENT_URI = new URL(`/login?cmp2610010839=${randomUUID()}`, BASE_URL).toString()

if (!['http://localhost:3001', 'http://127.0.0.1:3001'].includes(new URL(BASE_URL).origin)) {
  throw new Error('CMP-261001-0839の実機試験は専有ローカルFEだけで実行する')
}

function mysql(sql: string): string {
  // 秘密は既存の専有コンテナ内で環境へ渡し、Nodeの引数やログへ取り出さない。
  const args = [
    'exec',
    CONTAINER,
    'sh',
    '-c',
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --skip-column-names "$@"',
    'mysql',
    DATABASE,
    '-e',
    sql,
  ]
  return execFileSync(
    process.platform === 'win32' ? 'wsl.exe' : 'docker',
    process.platform === 'win32' ? ['-e', 'docker', ...args] : args,
    { encoding: 'utf8' },
  ).trim()
}

function cleanup(): void {
  // UUID付きdocument-uriに限り、自分の報告だけを削除する。
  mysql(`DELETE FROM csp_reports WHERE document_uri LIKE '${DOCUMENT_URI}%'`)
}

test.describe('CMP-261001-0839 CSP Content-Type実機契約', () => {
  test.beforeAll(cleanup)
  test.afterAll(cleanup)

  test('相対report-uriの自動報告と3種のMIME型が204となり実DBへ保存される', async ({ browser }) => {
    test.setTimeout(180_000)
    const context = await browser.newContext()
    const page = await context.newPage()
    try {
      const navigation = await page.goto(DOCUMENT_URI, { waitUntil: 'commit' })
      expect(navigation?.headers()['content-security-policy']).toContain(`report-uri ${ENDPOINT}`)
      await page.waitForFunction(() => document.body !== null)

      const automaticReport = page.waitForResponse(
        (response) =>
          response.url().includes(ENDPOINT) &&
          response.request().method() === 'POST' &&
          (response.request().postData() ?? '').includes('blocked.cmp2610010839.example'),
      )
      await page.evaluate(() => {
        const frame = document.createElement('iframe')
        frame.src = 'https://blocked.cmp2610010839.example/frame'
        document.body.appendChild(frame)
      })
      const automaticResponse = await automaticReport
      expect(automaticResponse.status()).toBe(204)
      expect(automaticResponse.request().headers()['content-type']).toContain(
        'application/csp-report',
      )
      expect(new URL(automaticResponse.url()).origin).toBe(new URL(BASE_URL).origin)

      const automaticFilter = `document_uri = '${DOCUMENT_URI}' AND blocked_uri LIKE 'https://blocked.cmp2610010839.example%'`
      await expect
        .poll(() => Number(mysql(`SELECT COUNT(*) FROM csp_reports WHERE ${automaticFilter}`)))
        .toBeGreaterThanOrEqual(1)

      for (const contentType of [
        'application/csp-report',
        'application/reports+json',
        'application/json',
      ]) {
        const documentUri = `${DOCUMENT_URI}&manual=${encodeURIComponent(contentType)}`
        const body = JSON.stringify({
          'csp-report': {
            'document-uri': documentUri,
            'blocked-uri': 'https://blocked.cmp2610010839.example/manual.js',
            'violated-directive': 'script-src',
          },
        })
        const result = await page.evaluate(
          async ({ endpoint, mime, payload }) => {
            const response = await fetch(endpoint, {
              method: 'POST',
              headers: { 'Content-Type': mime },
              body: payload,
            })
            return { status: response.status, body: await response.text() }
          },
          { endpoint: ENDPOINT, mime: contentType, payload: body },
        )
        expect(result).toEqual({ status: 204, body: '' })
        expect(
          mysql(`SELECT COUNT(*) FROM csp_reports WHERE document_uri = '${documentUri}'`),
        ).toBe('1')
      }

      for (const body of ['', 'null', '{invalid-json', '[]']) {
        const status = await page.evaluate(
          async ({ endpoint, payload }) => {
            const response = await fetch(endpoint, {
              method: 'POST',
              headers: { 'Content-Type': 'application/reports+json' },
              body: payload,
            })
            return response.status
          },
          { endpoint: ENDPOINT, payload: body },
        )
        expect(status).toBe(204)
      }
    } finally {
      await context.close()
    }
  })
})
