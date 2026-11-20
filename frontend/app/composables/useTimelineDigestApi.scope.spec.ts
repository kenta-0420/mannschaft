// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useTimelineDigestApi } from '~/composables/useTimelineDigestApi'

const { api } = vi.hoisted(() => ({ api: vi.fn() }))
mockNuxtImport('useApi', () => () => api)

describe('CMP-260918-0024 ダイジェストの必須スコープ契約', () => {
  beforeEach(() => {
    api.mockReset()
    api.mockResolvedValue({ data: [] })
  })

  it.each(['TEAM', 'ORGANIZATION'] as const)(
    '%s 一覧は数値内部IDとページ条件を送る',
    async (scopeType) => {
      const client = useTimelineDigestApi()
      // red段階では旧引数型と一致しない。契約変更後も実関数の送信URLを観測する。
      await Reflect.apply(client.listDigests, client, [scopeType, 101, '88', 20])
      const url = new URL(api.mock.calls[0]![0] as string, 'http://localhost')
      expect(url.pathname).toBe('/api/v1/timeline-digest')
      expect(Object.fromEntries(url.searchParams)).toEqual({
        scopeType,
        scopeId: '101',
        cursor: '88',
        limit: '20',
      })
    },
  )

  it.each(['TEAM', 'ORGANIZATION'] as const)('%s 設定GETは数値内部IDを送る', async (scopeType) => {
    const client = useTimelineDigestApi()
    await Reflect.apply(client.getConfig, client, [scopeType, 101])
    const url = new URL(api.mock.calls[0]![0] as string, 'http://localhost')
    expect(url.pathname).toBe('/api/v1/timeline-digest/config')
    expect(Object.fromEntries(url.searchParams)).toEqual({ scopeType, scopeId: '101' })
  })
})
