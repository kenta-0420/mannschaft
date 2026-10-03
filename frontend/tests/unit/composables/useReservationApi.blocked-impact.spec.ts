import { describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useReservationApi } from '~/composables/useReservationApi'

const mockApi = vi.fn().mockResolvedValue({ data: { affectedCount: 0, reservations: [] } })

mockNuxtImport('useApi', () => () => mockApi)

describe('useReservationApi blocked-time impact', () => {
  it('endsNextDay=trueをimpact URL queryへ送る', async () => {
    await useReservationApi().getBlockedTimeImpact('team-slug', {
      date: '2026-08-12',
      resourceType: 'TEAM',
      startTime: '23:30',
      endTime: '00:00',
      endsNextDay: true,
    })

    const calledUrl = mockApi.mock.calls.at(-1)?.[0] as string
    const query = new URLSearchParams(calledUrl.split('?')[1])
    expect(query.get('endsNextDay')).toBe('true')
  })
})
