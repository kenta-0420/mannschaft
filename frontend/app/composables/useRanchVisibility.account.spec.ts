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
  const settled = old.catch(() => undefined)
  await auth.setUser(user(2))
  resolve?.({ data: [{ widgetKey: 'PERSONAL_WEATHER', visible: false, sortOrder: 0 }] })
  await settled
  expect(auth.user?.id).toBe(2)
  expect(external.api).toHaveBeenCalledTimes(1)
  expect(external.refresh).not.toHaveBeenCalled()
 })
})
