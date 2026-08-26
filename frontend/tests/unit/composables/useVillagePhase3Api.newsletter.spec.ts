import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'

/**
 * useVillagePhase3Api（ニュースレター）の BE 契約テスト（課題D・19 件目の契約不一致）。
 *
 * 背景: FE の手書き型・実装が BE (`VillageNewsletterController` / `VillageNewsletterService`)
 * の実契約から逸脱しており、GET の Select が常に空・PUT が必ず 400 になっていた。
 * 旧実装は「頻度を 1 つ選ぶ」フラット単一形状を前提に、PUT body を `{frequency}` のみ送り、
 * opt-out/opt-in の 204（本体なし）から `res.data` を触ろうとしていた。
 *
 * 本テストは **BE の実物**（Controller / DTO を直接確認したもの）を正としてモックし、
 * FE が投げる HTTP verb / パス / body と、応答形状の取り回しが BE 契約と一致することを検証する。
 *
 * BE 実体（backend/.../village/controller/VillageNewsletterController.java）:
 * - GET    /api/v1/villages/{vid}/newsletter            — `{villageId, settings: [...], optedOut}`
 * - PUT    /api/v1/villages/{vid}/newsletter            — body `{frequency, isEnabled}` → 単一 setting
 * - POST   /api/v1/villages/{vid}/newsletter/opt-out    — 204 No Content（本体なし）
 * - DELETE /api/v1/villages/{vid}/newsletter/opt-out    — 204 No Content（本体なし）
 * - GET    /api/v1/villages/{vid}/newsletter/send-logs?frequency=  — 配信ログ配列
 */

interface ApiCall {
  path: string
  method: string
  body?: unknown
}

const calls: ApiCall[] = []
let nextResponse: unknown = null

const mockApi = vi.fn((path: string, opts?: { method?: string, body?: unknown }) => {
  calls.push({ path, method: opts?.method ?? 'GET', body: opts?.body })
  return Promise.resolve(nextResponse)
})

mockNuxtImport('useApi', () => () => mockApi)

const { useVillagePhase3Api } = await import('~/composables/village/useVillagePhase3Api')

const VILLAGE_ID = '01900000-0000-7000-8000-000000000001'
const SETTING_ID = '01900000-0000-7000-8300-000000000001'

beforeEach(() => {
  calls.length = 0
  nextResponse = null
  mockApi.mockClear()
})

describe('useVillagePhase3Api — ニュースレターの BE 契約', () => {
  describe('getNewsletterSettings', () => {
    it('GET /newsletter を呼び、{villageId, settings, optedOut} を返す', async () => {
      const { getNewsletterSettings } = useVillagePhase3Api()
      nextResponse = {
        data: {
          villageId: VILLAGE_ID,
          settings: [
            {
              id: SETTING_ID,
              villageId: VILLAGE_ID,
              frequency: 'WEEKLY',
              isEnabled: true,
              lastSentAt: '2026-07-10T18:00:00',
              nextScheduledAt: null,
              createdAt: '2026-07-01T00:00:00',
              updatedAt: '2026-07-10T18:00:00',
              version: 1,
            },
          ],
          optedOut: false,
        },
      }

      const res = await getNewsletterSettings(VILLAGE_ID)

      expect(calls[0]!.method).toBe('GET')
      expect(calls[0]!.path).toBe(`/api/v1/villages/${VILLAGE_ID}/newsletter`)
      // 配列形状（settings）を持つこと。フラット単一 frequency ではない。
      expect(res.settings).toHaveLength(1)
      expect(res.settings[0]!.frequency).toBe('WEEKLY')
      expect(res.settings[0]!.isEnabled).toBe(true)
      expect(res.optedOut).toBe(false)
    })
  })

  describe('updateNewsletterSettings', () => {
    it('PUT /newsletter に body {frequency, isEnabled} を送る（frequency のみは BE で 400）', async () => {
      const { updateNewsletterSettings } = useVillagePhase3Api()
      nextResponse = {
        data: {
          id: SETTING_ID,
          villageId: VILLAGE_ID,
          frequency: 'WEEKLY',
          isEnabled: true,
          lastSentAt: null,
          nextScheduledAt: null,
          createdAt: '2026-07-01T00:00:00',
          updatedAt: '2026-07-17T00:00:00',
          version: 2,
        },
      }

      const saved = await updateNewsletterSettings(VILLAGE_ID, {
        frequency: 'WEEKLY',
        isEnabled: true,
      })

      expect(calls[0]!.method).toBe('PUT')
      expect(calls[0]!.path).toBe(`/api/v1/villages/${VILLAGE_ID}/newsletter`)
      expect(calls[0]!.body).toEqual({ frequency: 'WEEKLY', isEnabled: true })
      // 戻りは upsert した単一 setting（配列ではない）
      expect(saved.frequency).toBe('WEEKLY')
      expect(saved.isEnabled).toBe(true)
    })

    it('MONTHLY を OFF にする更新も frequency/isEnabled を送る', async () => {
      const { updateNewsletterSettings } = useVillagePhase3Api()
      nextResponse = { data: { id: SETTING_ID, villageId: VILLAGE_ID, frequency: 'MONTHLY', isEnabled: false, lastSentAt: null, nextScheduledAt: null, createdAt: null, updatedAt: null, version: 3 } }

      await updateNewsletterSettings(VILLAGE_ID, { frequency: 'MONTHLY', isEnabled: false })

      expect(calls[0]!.body).toEqual({ frequency: 'MONTHLY', isEnabled: false })
    })
  })

  describe('optOut / optIn', () => {
    it('optOut は POST /opt-out・204（本体なし）でも例外にならず undefined を返す', async () => {
      const { optOut } = useVillagePhase3Api()
      nextResponse = null

      await expect(optOut(VILLAGE_ID)).resolves.toBeUndefined()
      expect(calls[0]!.method).toBe('POST')
      expect(calls[0]!.path).toBe(`/api/v1/villages/${VILLAGE_ID}/newsletter/opt-out`)
    })

    it('optIn は DELETE /opt-out・204（本体なし）でも例外にならず undefined を返す', async () => {
      const { optIn } = useVillagePhase3Api()
      nextResponse = null

      await expect(optIn(VILLAGE_ID)).resolves.toBeUndefined()
      expect(calls[0]!.method).toBe('DELETE')
      expect(calls[0]!.path).toBe(`/api/v1/villages/${VILLAGE_ID}/newsletter/opt-out`)
    })
  })

  describe('listSendLogs', () => {
    it('GET /send-logs?frequency= を呼び、配信ログ配列を返す', async () => {
      const { listSendLogs } = useVillagePhase3Api()
      nextResponse = {
        data: [
          {
            id: SETTING_ID,
            newsletterId: SETTING_ID,
            sentAt: '2026-07-10T18:00:00',
            recipientCount: 12,
            successCount: 12,
            failureCount: 0,
          },
        ],
      }

      const logs = await listSendLogs(VILLAGE_ID, 'WEEKLY')

      expect(calls[0]!.method).toBe('GET')
      expect(calls[0]!.path).toBe(`/api/v1/villages/${VILLAGE_ID}/newsletter/send-logs?frequency=WEEKLY`)
      expect(logs[0]!.recipientCount).toBe(12)
      expect(logs[0]!.successCount).toBe(12)
    })
  })
})
