import { describe, it, expect, beforeEach, vi } from 'vitest'
import { ref } from 'vue'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'

/**
 * CMP-261001-0630 AC-18: 学校出欠の権限判定結果による出し分け。
 *   - FE は独自判定せず、権限判定 API の値のみを使う
 *   - 403 は握りつぶさず forbidden として保持する（共通エラーハンドラへは渡さない）
 *   - 403 以外の失敗は fail-closed（全て false）にしつつ共通エラーハンドラへ渡す
 */
const mockGetPermissions = vi.fn()
const handleApiError = vi.fn()

vi.mock('~/composables/useAttendancePermissionsApi', () => ({
  useAttendancePermissionsApi: () => ({ getPermissions: mockGetPermissions }),
}))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError, handleError: handleApiError }))

const { useAttendancePermissions, isForbiddenError } = await import(
  '~/composables/useAttendancePermissions'
)

beforeEach(() => {
  mockGetPermissions.mockReset()
  handleApiError.mockReset()
})

describe('useAttendancePermissions', () => {
  it('判定結果の true / false をそのまま公開する', async () => {
    mockGetPermissions.mockResolvedValue({
      teamId: 1,
      canView: true,
      canRecordDaily: false,
      canRecordPeriod: true,
    })
    const p = useAttendancePermissions(ref('t1'))
    await p.loadPermissions()
    expect(p.loaded.value).toBe(true)
    expect(p.forbidden.value).toBe(false)
    expect(p.canView.value).toBe(true)
    expect(p.canRecordDaily.value).toBe(false)
    expect(p.canRecordPeriod.value).toBe(true)
  })

  it('権限なし（全 false の 200）では入口も登録も許可しない', async () => {
    mockGetPermissions.mockResolvedValue({
      teamId: 1,
      canView: false,
      canRecordDaily: false,
      canRecordPeriod: false,
    })
    const p = useAttendancePermissions(ref('t1'))
    await p.loadPermissions()
    expect(p.loaded.value).toBe(true)
    expect(p.canView.value).toBe(false)
    expect(p.canRecordDaily.value).toBe(false)
    expect(p.canRecordPeriod.value).toBe(false)
  })

  it('403 は forbidden として保持し、握りつぶさず全て false', async () => {
    mockGetPermissions.mockRejectedValue({ statusCode: 403, data: { error: { code: 'COMMON_002' } } })
    const p = useAttendancePermissions(ref('t1'))
    await p.loadPermissions()
    expect(p.forbidden.value).toBe(true)
    expect(p.loaded.value).toBe(true)
    expect(p.loadFailed.value).toBe(false)
    expect(p.ready.value).toBe(true)
    expect(p.canView.value).toBe(false)
    expect(handleApiError).not.toHaveBeenCalled()
  })

  it('403 以外の失敗は fail-closed で共通エラーハンドラへ渡す', async () => {
    const err = { statusCode: 500 }
    mockGetPermissions.mockRejectedValue(err)
    const p = useAttendancePermissions(ref('t1'))
    await p.loadPermissions()
    expect(p.forbidden.value).toBe(false)
    // 取得失敗は拒否と区別する（loadFailed）。照会は完了扱いにしない（ready=false）
    expect(p.loadFailed.value).toBe(true)
    expect(p.ready.value).toBe(false)
    expect(p.canView.value).toBe(false)
    expect(p.canRecordDaily.value).toBe(false)
    expect(handleApiError).toHaveBeenCalledTimes(1)
    expect(handleApiError.mock.calls[0]![0]).toBe(err)
  })

  it('照会中は ready=false、再試行で成功すると loadFailed が解除される', async () => {
    mockGetPermissions.mockRejectedValueOnce({ statusCode: 500 })
    const p = useAttendancePermissions(ref('t1'))
    await p.loadPermissions()
    expect(p.loadFailed.value).toBe(true)

    let resolve: (v: unknown) => void = () => {}
    mockGetPermissions.mockReturnValueOnce(new Promise((r) => (resolve = r)))
    const pending = p.loadPermissions()
    expect(p.ready.value).toBe(false)
    expect(p.loadFailed.value).toBe(false)
    resolve({ teamId: 1, canView: true, canRecordDaily: false, canRecordPeriod: false })
    await pending
    expect(p.ready.value).toBe(true)
    expect(p.canView.value).toBe(true)
  })

  it('isForbiddenError は statusCode / status の 403 のみ true', () => {
    expect(isForbiddenError({ statusCode: 403 })).toBe(true)
    expect(isForbiddenError({ status: 403 })).toBe(true)
    expect(isForbiddenError({ statusCode: 404 })).toBe(false)
    expect(isForbiddenError(null)).toBe(false)
  })
})
