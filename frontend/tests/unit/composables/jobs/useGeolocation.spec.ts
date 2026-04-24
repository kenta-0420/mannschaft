import { describe, it, expect, afterEach, vi } from 'vitest'
import { getCurrentSnapshot } from '~/composables/jobs/useGeolocation'

/**
 * F13.1 Phase 13.1.2 useGeolocation のユニットテスト。
 *
 * <p>navigator.geolocation をモック差し替えして成功／拒否ケースを検証する。</p>
 */

describe('getCurrentSnapshot', () => {
  const originalGeolocation = navigator.geolocation

  afterEach(() => {
    Object.defineProperty(navigator, 'geolocation', {
      value: originalGeolocation,
      configurable: true,
    })
  })

  it('成功時は GeolocationSnapshot を返す', async () => {
    const mockGeo = {
      getCurrentPosition: vi.fn((success: (pos: GeolocationPosition) => void) => {
        success({
          coords: {
            latitude: 35.6895,
            longitude: 139.6917,
            accuracy: 12,
            altitude: null,
            altitudeAccuracy: null,
            heading: null,
            speed: null,
          },
          timestamp: Date.now(),
        } as GeolocationPosition)
      }),
    }
    Object.defineProperty(navigator, 'geolocation', {
      value: mockGeo,
      configurable: true,
    })

    const snap = await getCurrentSnapshot()
    expect(snap).not.toBeNull()
    expect(snap?.latitude).toBeCloseTo(35.6895)
    expect(snap?.longitude).toBeCloseTo(139.6917)
    expect(snap?.accuracy).toBe(12)
    expect(typeof snap?.takenAt).toBe('number')
  })

  it('拒否／エラー時は null を返す', async () => {
    const mockGeo = {
      getCurrentPosition: vi.fn((_s: unknown, error: (e: GeolocationPositionError) => void) => {
        error({
          code: 1,
          message: 'User denied',
          PERMISSION_DENIED: 1,
          POSITION_UNAVAILABLE: 2,
          TIMEOUT: 3,
        } as GeolocationPositionError)
      }),
    }
    Object.defineProperty(navigator, 'geolocation', {
      value: mockGeo,
      configurable: true,
    })

    const snap = await getCurrentSnapshot()
    expect(snap).toBeNull()
  })

  it('navigator.geolocation 自体が無ければ null', async () => {
    Object.defineProperty(navigator, 'geolocation', {
      value: undefined,
      configurable: true,
    })
    const snap = await getCurrentSnapshot()
    expect(snap).toBeNull()
  })

  it('オプションが getCurrentPosition の 3 引数目に渡る', async () => {
    const positionOptionsCaptured: PositionOptions[] = []
    const mockGeo = {
      getCurrentPosition: vi.fn(
        (success: (pos: GeolocationPosition) => void, _e: unknown, opts?: PositionOptions) => {
          if (opts) positionOptionsCaptured.push(opts)
          success({
            coords: {
              latitude: 0,
              longitude: 0,
              accuracy: 1,
              altitude: null,
              altitudeAccuracy: null,
              heading: null,
              speed: null,
            },
            timestamp: Date.now(),
          } as GeolocationPosition)
        },
      ),
    }
    Object.defineProperty(navigator, 'geolocation', {
      value: mockGeo,
      configurable: true,
    })

    await getCurrentSnapshot({ enableHighAccuracy: true, timeout: 3000, maximumAge: 500 })
    expect(positionOptionsCaptured[0]).toMatchObject({
      enableHighAccuracy: true,
      timeout: 3000,
      maximumAge: 500,
    })
  })
})
