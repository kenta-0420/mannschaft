import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useAuthStore } from '~/stores/useAuthStore'
import { useRanchState } from '~/composables/useRanchState'
import type { RanchState } from '~/types/ranch'
const external = vi.hoisted(() => ({ state: vi.fn(), report: vi.fn() }))
mockNuxtImport('useRanchApi', () => () => ({ state: external.state }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: external.report }))
const user = (id: number) => ({ id, email: 'synthetic@example.invalid', fullName: 'Synthetic', profileImageUrl: null })
const oldState: RanchState = {
 featureStatus: 'AVAILABLE', deliveryPaused: false, rewardsStatus: 'ENABLED', shopAvailable: false,
 owner: { id: 'synthetic-owner-A', status: 'ACTIVE', balance: '0', version: '1' },
 dinosaur: null, settings: null, roomSlots: [], serverTime: '2026-10-04T00:00:00Z', policyVersion: null,
 careBudget: null, weekBudget: null, assignment: null,
}
beforeEach(() => {
 setActivePinia(createPinia())
 external.state.mockReset(); external.report.mockReset()
})
describe('維持された牧場画面のprivate GET本人境界', () => {
 it('Aの遅延GET成功をB画面のstateへ適用しない', async () => {
  const auth = useAuthStore()
  vi.spyOn(auth, 'clearUserCaches').mockResolvedValue()
  await auth.setUser(user(1))
  let resolve: ((state: RanchState) => void) | undefined
  external.state.mockReturnValueOnce(new Promise<RanchState>(done => { resolve = done }))
  const ranch = useRanchState()
  const request = ranch.load()
  await auth.setUser(user(2))
  resolve?.(oldState)
  await request
  expect(auth.user?.id).toBe(2)
  expect(ranch.state.value).toBeNull()
  expect(external.report).not.toHaveBeenCalled()
 })
})
