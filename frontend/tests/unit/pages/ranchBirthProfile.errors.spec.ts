// @vitest-environment nuxt
// 実際の出生フォーム/Pinia/command memoryを用い、外部HTTPだけを隔離する未実測草稿。
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { setActivePinia } from 'pinia'
import { effectScope } from 'vue'
import { useNuxtApp } from '#app'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import BirthProfilePage from '~/pages/my/ranch/birth-profile.vue'
import { useAuthStore } from '~/stores/useAuthStore'
import { useBirthProfile } from '~/composables/useBirthProfile'
import type { BirthProfile } from '~/types/ranch'

const external = vi.hoisted(() => ({ fetch: vi.fn<typeof fetch>(), report: vi.fn() }))
vi.mock('ofetch', async importOriginal => {
 const original = await importOriginal<typeof import('ofetch')>()
 return { ...original, ofetch: original.ofetch.create({}, { fetch: external.fetch }) }
})
vi.mock('~/composables/useApiBaseUrl', () => ({ resolveApiBaseUrl: () => 'http://synthetic.invalid' }))
mockNuxtImport('useErrorReport', () => () => ({ capture: external.report, captureQuiet: external.report }))
mockNuxtImport('useProxyDeskStore', () => () => ({ isPinned: false }))
mockNuxtImport('useGuardianshipSwitchStore', () => () => ({ isActingAs: false }))
mockNuxtImport('useAdminImpersonationStore', () => () => ({ isImpersonating: false }))
const wrappers: { unmount: () => void }[] = []
const scopes: ReturnType<typeof effectScope>[] = []
async function profileApi(): Promise<ReturnType<typeof useBirthProfile>> {
 const scope = effectScope(); scopes.push(scope)
 const api = await scope.run(() => useNuxtApp().runWithContext(() => useBirthProfile()))
 if (!api) throw new Error('PROFILE_API_SCOPE_NOT_CREATED')
 return api
}
const profile: BirthProfile = { lastName: 'Synthetic', firstName: 'Adult', lastNameKana: 'シンセティック', firstNameKana: 'アダルト', birthDate: '1990-01-01', revision: '1' }
const rawInput: BirthProfile = { ...profile, firstName: 'Edited' }
const json = (data: unknown) => new Response(JSON.stringify({ data }), { headers: { 'Content-Type': 'application/json' } })
const rejected = (code: string) => new Response(JSON.stringify({ error: { code, message: code } }), { status: 409, headers: { 'Content-Type': 'application/json' } })
const writes: string[] = []
const writeBodies: BirthProfile[] = []
let reads = 0
let errorCode = 'BIRTHPROFILE_008'
let failFirstWrite = true

beforeEach(async () => {
 setActivePinia(useNuxtApp().$pinia)
 const auth = useAuthStore()
 vi.spyOn(auth, 'clearUserCaches').mockResolvedValue()
 await auth.setUser({ id: 1, email: 'synthetic@example.invalid', fullName: 'Synthetic', profileImageUrl: null })
 auth.setTokens('A-access', 'A-refresh')
 const currentApi = await profileApi()
 currentApi.command.discardRejected()
 external.fetch.mockReset(); external.report.mockReset(); writes.length = 0; writeBodies.length = 0
 errorCode = 'BIRTHPROFILE_008'; failFirstWrite = true; reads = 0
 external.fetch.mockImplementation(async (request, options) => {
  if (!String(request).endsWith('/api/v1/me/birth-profile')) throw new Error(`UNEXPECTED_TRANSPORT ${String(request)}`)
  if ((options?.method ?? 'GET') === 'GET') { reads += 1; return json(useAuthStore().user?.id === 2 ? { ...profile, firstName: 'SYNTHETIC_B_PROFILE', revision: '2' } : profile) }
  writes.push(new Headers(options?.headers).get('Idempotency-Key') ?? '')
  writeBodies.push(JSON.parse(String(options?.body)) as BirthProfile)
  if (failFirstWrite) { failFirstWrite = false; throw new TypeError('SYNTHETIC_RESPONSE_LOST') }
  if (writes.length > 2) return json({ revision: '2' })
  return rejected(errorCode)
 })
})
afterEach(() => { for (const wrapper of wrappers.splice(0)) wrapper.unmount(); for (const scope of scopes.splice(0)) scope.stop(); vi.restoreAllMocks() })
async function mountPendingProfile(input: BirthProfile = rawInput) {
 const api = await profileApi()
 await expect(api.save(input)).rejects.toThrow()
 expect(api.command.pending.value).not.toBeNull()
 const wrapper = await mountSuspended(BirthProfilePage)
 wrappers.push(wrapper); await flushPromises()
 const retry = wrapper.findAll('button').find(item => item.text() === useNuxtApp().$i18n.t('ranch.retry'))
 if (!retry) throw new Error('RETRY_BUTTON_NOT_FOUND')
 await retry.trigger('click'); await flushPromises()
 return { wrapper, api }
}
describe('出生プロフィールの専用409導線（先行赤候補）', () => {
 it('保持ページのA GET遅延応答を本人Bへ表示しない', async () => {
  let resolve: ((response: Response) => void) | undefined
  let startedResolve: (() => void) | undefined
  const started = new Promise<void>(done => { startedResolve = done })
  external.fetch.mockImplementationOnce(() => {
   startedResolve?.()
   return new Promise<Response>(done => { resolve = done })
  })
  const wrapper = await mountSuspended(BirthProfilePage)
  wrappers.push(wrapper)
  await started
  try {
   const auth = useAuthStore()
   auth.setTokens('B-access', 'B-refresh')
   await auth.setUser({ id: 2, email: 'synthetic-b@example.invalid', fullName: 'Synthetic B', profileImageUrl: null })
   await flushPromises()
   resolve?.(json({ ...profile, firstName: 'PRIVATE_A_ONLY' }))
   await flushPromises()
   expect(wrapper.text()).not.toContain('PRIVATE_A_ONLY')
   const inputValues = wrapper.findAll('input').map(input => (input.element as HTMLInputElement).value)
   expect(inputValues).not.toContain('PRIVATE_A_ONLY')
   expect(inputValues).toContain('SYNTHETIC_B_PROFILE')
  } finally {
   resolve?.(json(profile)); await flushPromises()
  }
 })
 it('008だけを鍵交代として示し、同じkeyを自動反復せず明示的再読込へ進める', async () => {
  const { wrapper, api } = await mountPendingProfile()
  expect(writes).toHaveLength(2)
  expect(writes[0]).toBe(writes[1])
  expect(wrapper.text()).toContain(useNuxtApp().$i18n.t('ranch.birth.keyRotated'))
  const reload = wrapper.findAll('button').find(item => item.text() === useNuxtApp().$i18n.t('ranch.birth.reloadProfile'))
  if (!reload) throw new Error('EXPLICIT_PROFILE_RELOAD_BUTTON_NOT_FOUND')
  expect(reads).toBe(1)
  await reload.trigger('click'); await flushPromises()
  expect(reads).toBe(2)
  expect(api.command.pending.value).toBeNull()
  expect(writes).toHaveLength(2)
  // 再読込で旧編集は破棄。表示された本人情報から本人が再入力して明示的に保存する。
  const firstNameInput = wrapper.findAll('input').find(input => (input.element as HTMLInputElement).value === profile.firstName)
  if (!firstNameInput) throw new Error('RELOADED_FIRST_NAME_INPUT_NOT_FOUND')
  await firstNameInput.setValue('EditedAfterReload')
  await wrapper.get('form').trigger('submit')
  // handleSubmitの非同期schema検証を待つ。保存回数/キーの期待は緩めない。
  await vi.waitFor(() => expect(writes).toHaveLength(3))
  expect(writes[2]).not.toBe(writes[0])
  expect(writeBodies[2]).toEqual({ ...profile, firstName: 'EditedAfterReload' })
 })
 it('009は既存保護者同意への入口を示し、自動再保存しない', async () => {
  errorCode = 'BIRTHPROFILE_009'
  const { wrapper } = await mountPendingProfile({ ...rawInput, birthDate: '2018-01-01' })
  expect(writes).toHaveLength(2)
  expect(wrapper.find('a[href="/parental-consent/pending"]').exists()).toBe(true)
  expect(wrapper.text()).toContain(useNuxtApp().$i18n.t('ranch.birth.parentalConsentRequired'))
 })
 it('005/006等を008の鍵交代表示へ変換しない', async () => {
  errorCode = 'BIRTHPROFILE_006'
  const { wrapper } = await mountPendingProfile()
  expect(writes).toHaveLength(2)
  expect(wrapper.text()).not.toContain(useNuxtApp().$i18n.t('ranch.birth.keyRotated'))
  expect(wrapper.find('a[href="/parental-consent/pending"]').exists()).toBe(false)
 })
})
