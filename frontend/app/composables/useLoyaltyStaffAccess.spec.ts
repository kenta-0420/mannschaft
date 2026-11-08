// @vitest-environment node
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { computed, ref, shallowRef, watch, nextTick } from 'vue'
import { useLoyaltyStaffAccess } from './useLoyaltyStaffAccess'
const mocks = vi.hoisted(() => ({ getTeam: vi.fn(), catalog: vi.fn() }))
vi.mock('~/composables/useTeamApi', () => ({ useTeamApi: () => ({ getTeam: mocks.getTeam }) }))
vi.mock('~/composables/useTeamLoyaltyApi', () => ({
  useTeamLoyaltyApi: () => ({ catalog: mocks.catalog }),
}))
vi.stubGlobal('useTeamApi', () => ({ getTeam: mocks.getTeam }))
vi.stubGlobal('useTeamLoyaltyApi', () => ({ catalog: mocks.catalog }))
vi.stubGlobal('ref', ref)
vi.stubGlobal('computed', computed)
vi.stubGlobal('shallowRef', shallowRef)
vi.stubGlobal('watch', watch)
vi.stubGlobal('onUnmounted', vi.fn())
describe('チーム営業導線の権限（AC05/06/32）', () => {
  beforeEach(() => {
    mocks.getTeam.mockReset()
    mocks.catalog.mockReset()
    mocks.getTeam.mockResolvedValue({ data: { numericId: 42 } })
  })
  it('無委任DEPUTYに導線を表示せず通常ロールでcatalogを追加取得しない', () => {
    const access = useLoyaltyStaffAccess(ref('team'), ref('DEPUTY_ADMIN'), ref(false), () => false)
    expect(access.allowed.value).toBe(false)
    expect(mocks.catalog).not.toHaveBeenCalled()
  })
  it('SYSTEM_ADMINの全体昇格では許可せずcatalogの403と障害を区別する', async () => {
    mocks.catalog.mockRejectedValue({ statusCode: 403 })
    const access = useLoyaltyStaffAccess(ref('team'), ref('SYSTEM_ADMIN'), ref(false), () => true)
    await access.refresh()
    expect(access.allowed.value).toBe(false)
    expect(access.error.value).toBeNull()
    mocks.catalog.mockRejectedValue({ statusCode: 500 })
    await access.refresh()
    expect(access.allowed.value).toBe(false)
    expect(access.error.value).toEqual({ statusCode: 500 })
  })
  it('team変更後の古い成功を反映しない', async () => {
    let resolve!: () => void
    mocks.catalog.mockImplementationOnce(
      () =>
        new Promise<void>((done) => {
          resolve = done
        }),
    )
    const slug = ref('first')
    const access = useLoyaltyStaffAccess(slug, ref('SYSTEM_ADMIN'), ref(false), () => true)
    await nextTick()
    await Promise.resolve()
    mocks.catalog.mockRejectedValue({ statusCode: 403 })
    slug.value = 'second'
    await nextTick()
    await access.refresh()
    resolve()
    await Promise.resolve()
    await Promise.resolve()
    expect(access.allowed.value).toBe(false)
  })
})
