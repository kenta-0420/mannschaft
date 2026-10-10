import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useAuthStore } from '~/stores/useAuthStore'
import { useRanchVisibility } from '~/composables/useRanchVisibility'
const external = vi.hoisted(() => ({ api: vi.fn(), refresh: vi.fn() }))
mockNuxtImport('useApi', () => () => external.api)
mockNuxtImport('refreshNuxtData', () => external.refresh)
const user = (id: number) => ({ id, email: 'synthetic@example.invalid', fullName: 'Synthetic', profileImageUrl: null })
beforeEach(() => {
 setActivePinia(createPinia())
 external.api.mockReset(); external.refresh.mockReset()
})
describe('表示設定の遅延GETと本人境界', () => {
 it('A設定GETの途中でBへ切り替わった場合はB資格のPUTを送信しない', async () => {
  const auth = useAuthStore()
  vi.spyOn(auth, 'clearUserCaches').mockResolvedValue()
  await auth.setUser(user(1))
  let resolve: ((value: { data: { widgetKey: string; visible: boolean; sortOrder: number }[] }) => void) | undefined
  const delayed = new Promise<{ data: { widgetKey: string; visible: boolean; sortOrder: number }[] }>(done => { resolve = done })
  external.api.mockReturnValueOnce(delayed).mockResolvedValue({ data: {} })
  const old = useRanchVisibility().setVisible(true)
  const settled = expect(old).rejects.toThrow('COMMAND_ACCOUNT_CHANGED')
  await auth.setUser(user(2))
  resolve?.({ data: [{ widgetKey: 'PERSONAL_WEATHER', visible: false, sortOrder: 0 }] })
  await settled
  expect(auth.user?.id).toBe(2)
  expect(external.api).toHaveBeenCalledTimes(1)
  expect(external.refresh).not.toHaveBeenCalled()
 })

 it('refresh待機中の本人切替後は旧操作を成功として返さない', async () => {
  const auth = useAuthStore()
  vi.spyOn(auth, 'clearUserCaches').mockResolvedValue()
  await auth.setUser(user(1))
  external.api.mockResolvedValue({ data: [] })
  let startRefresh: (() => void) | undefined
  let finishRefresh: (() => void) | undefined
  const started = new Promise<void>(done => { startRefresh = done })
  const delayed = new Promise<void>(done => { finishRefresh = done })
  external.refresh.mockImplementationOnce(() => { startRefresh?.(); return delayed })
  const old = useRanchVisibility().setVisible(true)
  const rejected = expect(old).rejects.toThrow('COMMAND_ACCOUNT_CHANGED')
  try {
   await started
   await auth.setUser(user(2))
   finishRefresh?.()
   await rejected
   expect(auth.user?.id).toBe(2)
  } finally {
   finishRefresh?.()
   await Promise.allSettled([old, rejected])
  }
 })
})
