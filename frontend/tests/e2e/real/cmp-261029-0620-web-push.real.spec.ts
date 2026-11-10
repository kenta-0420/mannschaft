/**
 * CMP-261029-0620: 本番ビルドの Service Worker に Web Push を CDP で投入し、通知の表示と遷移先を確かめる。
 *
 * - 本番ビルド（`npm run build` → `node .output/server/index.mjs`）でのみ意味がある。dev では SW が登録されない。
 * - 購読導線（PushManager.subscribe）は FE に未実装（別台帳）なので、push サービスを経由せず
 *   CDP `ServiceWorker.deliverPushMessage` で SW の push イベントを直接起こす。
 * - BE は不要（SW の push / notificationclick は BE を呼ばない）。
 *
 * 実行例: `BASE_URL=http://localhost:3007 npx playwright test -c playwright-web-push.config.ts`
 */
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { expect, test, type BrowserContext, type Page } from '@playwright/test'

interface WebPushContract {
  title: string
  body: string
  data: { notification_id: number; action_url: string | null; priority: string }
}
const CONTRACT = JSON.parse(
  readFileSync(
    resolve(__dirname, '../../../../backend/src/test/resources/contracts/web-push-payload.json'),
    'utf8',
  ),
) as WebPushContract

interface ShownNotification {
  title: string
  body: string
  data: unknown
}

async function readySw(page: Page, context: BrowserContext, origin: string): Promise<void> {
  await context.grantPermissions(['notifications'], { origin })
  await page.goto('/')
  await page.evaluate(async () => {
    await navigator.serviceWorker.ready
  })
}

type PushProbe = { deliver: (data: string) => Promise<void> }

/** CDP の ServiceWorker ドメインで登録 ID を得て、push を投入する関数を返す。 */
async function pushProbe(context: BrowserContext, page: Page, origin: string): Promise<PushProbe> {
  const cdp = await context.newCDPSession(page)
  const found = new Promise<string>((resolveId) => {
    cdp.on('ServiceWorker.workerRegistrationUpdated', (e) => {
      const reg = e.registrations.find((r) => !r.isDeleted && r.scopeURL.startsWith(origin))
      if (reg) resolveId(reg.registrationId)
    })
  })
  await cdp.send('ServiceWorker.enable')
  const registrationId = await found
  return {
    deliver: async (data: string) => {
      await cdp.send('ServiceWorker.deliverPushMessage', { origin, registrationId, data })
    },
  }
}

async function shownNotifications(page: Page): Promise<ShownNotification[]> {
  return page.evaluate(async () => {
    const reg = await navigator.serviceWorker.ready
    const list = await reg.getNotifications()
    return list.map((n) => ({ title: n.title, body: n.body, data: n.data as unknown }))
  })
}

async function closeAllNotifications(page: Page): Promise<void> {
  await page.evaluate(async () => {
    const reg = await navigator.serviceWorker.ready
    for (const n of await reg.getNotifications()) n.close()
  })
}

test.describe('CMP-261029-0620 Web Push（本番ビルドの SW）', () => {
  test('AC-14b: 登録された SW が importScripts で sw-custom.js を取り込み、CDP の push で通知が出て data.action_url が見本どおり', async ({
    page,
    context,
    baseURL,
  }) => {
    test.setTimeout(180_000)
    const origin = new URL(baseURL!).origin
    await readySw(page, context, origin)

    const swSource = await page.evaluate(async () => {
      const reg = await navigator.serviceWorker.ready
      const res = await fetch(reg.active!.scriptURL, { cache: 'no-store' })
      return res.text()
    })
    expect(swSource).toMatch(/importScripts\([^)]*sw-custom\.js/)

    const probe = await pushProbe(context, page, origin)
    await closeAllNotifications(page)
    await probe.deliver(JSON.stringify(CONTRACT))

    await expect.poll(() => shownNotifications(page), { timeout: 15_000 }).toEqual([
      { title: CONTRACT.title, body: CONTRACT.body, data: CONTRACT.data },
    ])
    const [shown] = await shownNotifications(page)
    expect((shown!.data as WebPushContract['data']).action_url).toBe(CONTRACT.data.action_url)
  })

  test('AC-14b: JSON でない push でも既定の題で通知が出る（SW が落ちない）', async ({ page, context, baseURL }) => {
    test.setTimeout(180_000)
    const origin = new URL(baseURL!).origin
    await readySw(page, context, origin)
    const probe = await pushProbe(context, page, origin)
    await closeAllNotifications(page)
    await probe.deliver('not json {')
    await expect
      .poll(async () => (await shownNotifications(page)).map((n) => n.title), { timeout: 15_000 })
      .toEqual(['Mannschaft'])
  })

  /*
   * AC-14c / AC-14d（通知クリックでの遷移）について、調べた結果:
   * - CDP には通知をクリックさせる命令が無い（ServiceWorker ドメインは deliverPushMessage /
   *   dispatchSyncEvent / dispatchPeriodicSyncEvent のみ。BackgroundService ドメインは記録だけ）。
   * - SW 内で `new NotificationEvent('notificationclick')` を合成して dispatchEvent しても、
   *   isTrusted=false の ExtendableEvent では waitUntil が InvalidStateError を投げ、
   *   clients.openWindow / WindowClient.focus はユーザー操作（通知クリック）由来でないため拒否される。
   * したがって実ブラウザでクリック遷移を自動検査する手段が無く、遷移の判定は単体試験
   * （frontend/tests/unit/sw/swCustom.spec.ts の AC-9〜13）で担う。下の試験は合成イベントの制約自体を記録する。
   */
  test('AC-14c/14d 調査記録: 合成した notificationclick では waitUntil が使えない（クリック遷移は単体 AC-9/12 で担う）', async ({
    page,
    context,
    baseURL,
  }) => {
    test.setTimeout(180_000)
    const origin = new URL(baseURL!).origin
    await readySw(page, context, origin)
    const worker = context.serviceWorkers()[0] ?? (await context.waitForEvent('serviceworker'))
    const result = await worker.evaluate(() => {
      const ev = new ExtendableEvent('probe')
      try {
        ev.waitUntil(Promise.resolve())
        return 'waitUntil-accepted'
      } catch (e) {
        return (e as Error).name
      }
    })
    expect(result).toBe('InvalidStateError')
  })
})
