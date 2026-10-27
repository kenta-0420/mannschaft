/** ウォレットの操作領域と案内だけを実UIで確認する。APIは認証・言語の前提設定と復元のみ。 */
import { test, expect, type Locator, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

const API = process.env.API_BASE_URL
if (API !== 'http://localhost:8083' || process.env.BASE_URL !== 'http://localhost:3004') {
  throw new Error('The dedicated frontend3004/API8083 must be selected explicitly')
}
if (!process.env.LOYALTY_E2E_FIXTURE) throw new Error('Dedicated private fixture required')
const fixture = JSON.parse(readFileSync(process.env.LOYALTY_E2E_FIXTURE, 'utf8')) as {
  actors: { individual: { email: string; password: string } }
}

// 新しい辞書キーが未実装でも、実画面の欠落をassertionで検出する。
const guides = {
  ja: {
    title: 'チームポイントを使う',
    balance:
      'チームごとに残高が分かれています。ほかのチームや外部カードのポイントとは合算されません。',
    memberQr: '共通の「会員QR」をスタッフに見せると、ポイント付与や特典交換に使えます。',
    reception:
      '掲示された受付QRを読み取り、内容を確認して確定してください。チームの設定に応じて、その場で加算されるか、スタッフの承認後に加算されます。',
    requests:
      '「ポイント申請」で状態を確認し、承認前の申請を取り消せます。チームの詳細では、特典までの残りポイントと履歴を確認できます。',
    consent:
      'チームポイントと会員QRは、外部ポイントカードの利用開始や規約への同意をしなくても使えます。',
  },
  en: {
    title: 'Use team points',
    balance:
      'Each team has a separate balance. Points are not combined with other teams or external cards.',
    memberQr: 'Show your shared member QR to staff to receive points or redeem rewards.',
    reception:
      'Scan the displayed reception QR, check the details and confirm. Depending on the team settings, points are added immediately or after staff approval.',
    requests:
      'Check the status under Point requests and cancel requests before approval. Open the team details to see points remaining for rewards and your history.',
    consent:
      'You can use team points and your member QR without enabling external point cards or accepting their terms.',
  },
  zh: {
    title: '使用团队积分',
    balance: '每个团队的余额分别计算，不与其他团队或外部积分卡的积分合并。',
    memberQr: '向工作人员出示通用会员二维码，即可用于领取积分或兑换奖励。',
    reception:
      '扫描现场展示的接待二维码，核对内容后确认。根据团队设置，积分会立即到账，或在工作人员批准后到账。',
    requests:
      '在“积分申请”中查看状态，并可在批准前取消申请。打开团队详情可查看兑换奖励还差多少积分以及积分记录。',
    consent: '无需启用外部积分卡或同意其条款，也可以使用团队积分和会员二维码。',
  },
  ko: {
    title: '팀 포인트 사용하기',
    balance:
      '팀마다 잔액이 따로 관리되며, 다른 팀이나 외부 포인트 카드의 포인트와 합산되지 않습니다.',
    memberQr: '공통 회원 QR을 직원에게 보여 주면 포인트를 받거나 혜택으로 교환할 수 있습니다.',
    reception:
      '게시된 접수 QR을 스캔하고 내용을 확인한 뒤 확정하세요. 팀 설정에 따라 즉시 적립되거나 직원 승인 후 적립됩니다.',
    requests:
      '포인트 신청에서 상태를 확인하고 승인 전에 신청을 취소할 수 있습니다. 팀 상세 화면에서는 혜택까지 남은 포인트와 내역을 확인할 수 있습니다.',
    consent:
      '외부 포인트 카드를 활성화하거나 해당 약관에 동의하지 않아도 팀 포인트와 회원 QR을 사용할 수 있습니다.',
  },
  es: {
    title: 'Usar los puntos del equipo',
    balance:
      'Cada equipo tiene un saldo independiente. Los puntos no se suman a los de otros equipos ni a los de tarjetas externas.',
    memberQr:
      'Muestra tu QR de miembro compartido al personal para recibir puntos o canjear recompensas.',
    reception:
      'Escanea el QR de recepción expuesto, revisa los datos y confirma. Según la configuración del equipo, los puntos se añaden al instante o después de la aprobación del personal.',
    requests:
      'Consulta el estado en Solicitudes de puntos y cancela las solicitudes antes de su aprobación. Abre los detalles del equipo para ver los puntos que faltan para las recompensas y tu historial.',
    consent:
      'Puedes usar los puntos del equipo y tu QR de miembro sin activar las tarjetas de puntos externas ni aceptar sus condiciones.',
  },
  de: {
    title: 'Teampunkte nutzen',
    balance:
      'Jedes Team hat ein eigenes Guthaben. Die Punkte werden nicht mit denen anderer Teams oder externer Karten zusammengezählt.',
    memberQr:
      'Zeige dem Personal deinen gemeinsamen Mitglieds-QR, um Punkte zu erhalten oder Prämien einzulösen.',
    reception:
      'Scanne den ausgehängten Empfangs-QR, prüfe die Angaben und bestätige sie. Je nach Teameinstellung werden die Punkte sofort oder nach Freigabe durch das Personal gutgeschrieben.',
    requests:
      'Prüfe den Status unter Punkteanträge und storniere Anträge vor der Freigabe. In den Teamdetails siehst du die fehlenden Punkte bis zur Prämie und deinen Verlauf.',
    consent:
      'Du kannst Teampunkte und deinen Mitglieds-QR nutzen, ohne externe Punktekarten zu aktivieren oder deren Bedingungen zuzustimmen.',
  },
} as const
type Locale = keyof typeof guides

test.use({
  storageState: { cookies: [], origins: [] },
  viewport: { width: 360, height: 844 },
  isMobile: true,
  hasTouch: true,
  actionTimeout: 15_000,
  trace: 'off',
  screenshot: 'off',
})

async function wallet(page: Page, locale: Locale, run: () => Promise<void>) {
  await loginViaApi(page, fixture.actors.individual, { apiBaseUrl: API })
  const before = await page.request.get(`${API}/api/v1/users/me`)
  expect(before.status()).toBe(200)
  const profile = (await before.json()).data as { locale: string | null; timezone: string | null }
  try {
    const saved = await page.request.put(`${API}/api/v1/users/me`, {
      data: { locale, timezone: 'Asia/Tokyo' },
    })
    expect(saved.status()).toBe(200)
    const current = await page.request.get(`${API}/api/v1/users/me`)
    expect(current.status()).toBe(200)
    expect((await current.json()).data.locale).toBe(locale)
    await loginViaApi(page, fixture.actors.individual, { apiBaseUrl: API })
    await page.goto('/wallet', { waitUntil: 'commit' })
    await waitForHydration(page, { reloadOnTimeout: false })
    await run()
  } finally {
    const restored = await page.request.put(`${API}/api/v1/users/me`, {
      data: { locale: profile.locale ?? 'ja', timezone: profile.timezone ?? 'Asia/Tokyo' },
    })
    expect(restored.status()).toBe(200)
  }
}

async function target44(locator: Locator) {
  const box = await locator.boundingBox()
  expect.soft(box).not.toBeNull()
  expect.soft(box?.width ?? 0).toBeGreaterThanOrEqual(44)
  expect.soft(box?.height ?? 0).toBeGreaterThanOrEqual(44)
}

test('AL回帰: 360pxの財布ヘッダーと閉じる操作を押しやすく表示する', async ({ page }) => {
  test.setTimeout(90_000)
  await wallet(page, 'ja', async () => {
    const help = page.getByTestId('page-header-help')
    await expect(help).toBeVisible()
    await target44(help)
    await target44(
      page
        .getByRole('link', { name: '設定', exact: true })
        .and(page.locator('a[href="/wallet/settings"]')),
    )
    const lineCount = await help.locator('.p-button-label').evaluate((label) => {
      const range = document.createRange()
      range.selectNodeContents(label)
      return new Set([...range.getClientRects()].map((rect) => Math.round(rect.top))).size
    })
    expect.soft(lineCount).toBe(1)
    expect
      .soft(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= document.documentElement.clientWidth,
        ),
      )
      .toBe(true)
    await help.click()
    const dialog = page.getByTestId('wallet-guide-modal')
    await expect(dialog).toBeVisible()
    await target44(dialog.locator('.p-dialog-close-button'))
    await target44(
      dialog.locator('.p-dialog-footer').getByRole('button', { name: '閉じる', exact: true }),
    )
    await dialog
      .locator('.p-dialog-footer')
      .getByRole('button', { name: '閉じる', exact: true })
      .click()
    await expect(dialog).toBeHidden()
  })
})

for (const locale of Object.keys(guides) as Locale[]) {
  test(`AL回帰: ${locale}の使い方でチームポイントを案内する`, async ({ page }) => {
    test.setTimeout(90_000)
    await wallet(page, locale, async () => {
      await page.getByTestId('page-header-help').click()
      const dialog = page.getByTestId('wallet-guide-modal')
      await expect(dialog).toBeVisible()
      const expected = guides[locale]
      expect
        .soft(await dialog.getByRole('heading', { name: expected.title, exact: true }).isVisible())
        .toBe(true)
      for (const text of [
        expected.balance,
        expected.memberQr,
        expected.reception,
        expected.requests,
        expected.consent,
      ]) {
        expect.soft(await dialog.getByText(text, { exact: true }).isVisible()).toBe(true)
      }
    })
  })
}
