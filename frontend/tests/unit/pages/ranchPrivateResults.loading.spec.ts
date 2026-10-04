// @vitest-environment nuxt
// 実際の私的結果ページ/装飾ページ/Pinia/command memoryを用い、外部HTTPだけを隔離する未実測草稿。
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { setActivePinia } from 'pinia'
import { useNuxtApp } from '#app'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import ResultsPage from '~/pages/my/ranch/results/index.vue'
import ResultDetailPage from '~/pages/my/ranch/results/[resultId].vue'
import type { DiagnosisResult } from '~/types/ranch'
import DecorationsPage from '~/pages/my/ranch/decorations.vue'
import { useAuthStore } from '~/stores/useAuthStore'

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
const json = (body: unknown) => new Response(JSON.stringify(body), { headers: { 'Content-Type': 'application/json' } })
const empty = { data: [], meta: { hasNext: false, nextCursor: null, limit: 20 } }
beforeEach(async () => {
 setActivePinia(useNuxtApp().$pinia)
 const auth = useAuthStore()
 vi.spyOn(auth, 'clearUserCaches').mockResolvedValue()
 // 各caseで本人世代を作り直して前ケースのstateを持ち越さない。
 auth.$reset()
 await auth.setUser({ id: 1, email: 'synthetic@example.invalid', fullName: 'Synthetic', profileImageUrl: null })
 auth.setTokens('A-access', 'A-refresh')
 external.fetch.mockReset(); external.report.mockReset()
})
afterEach(() => { for (const wrapper of wrappers.splice(0)) wrapper.unmount(); vi.restoreAllMocks() })
describe('独立診断結果と装飾loadingの実ページ先行赤候補', () => {
 it('本人結果が読めたら牧場GET待機中も結果本文を表示する', async () => {
  const result: DiagnosisResult = { id: 'synthetic-result', method: 'DIAGNOSIS', completedAt: '2026-10-04T00:00:00Z', resultSchemaVersion: '1', descriptionSnapshot: { ja: 'PRIVATE_RESULT_READY', en: 'PRIVATE_RESULT_READY', zh: 'PRIVATE_RESULT_READY', ko: 'PRIVATE_RESULT_READY', es: 'PRIVATE_RESULT_READY', de: 'PRIVATE_RESULT_READY' } }
  let resolve: ((response: Response) => void) | undefined
  let startedResolve: (() => void) | undefined
  const started = new Promise<void>(done => { startedResolve = done })
  external.fetch.mockImplementation(async request => {
   const path = new URL(String(request)).pathname
   if (path.startsWith('/api/v1/me/ranch/diagnosis-results/')) return json({ data: result })
   if (path === '/api/v1/me/ranch') {
    startedResolve?.()
    return new Promise<Response>(done => { resolve = done })
   }
   throw new Error(`UNEXPECTED_TRANSPORT ${path}`)
  })
  const wrapper = await mountSuspended(ResultDetailPage, { route: '/my/ranch/results/synthetic-result' })
  wrappers.push(wrapper); await started; await flushPromises()
  try {
   expect(wrapper.text()).toContain('PRIVATE_RESULT_READY')
  } finally {
   resolve?.(json({ data: null })); await flushPromises()
  }
 })
 it('保持結果ページの旧A privateGET応答を本人Bに表示しない', async () => {
  let resolve: ((response: Response) => void) | undefined
  let startedResolve: (() => void) | undefined
  const started = new Promise<void>(done => { startedResolve = done })
  external.fetch.mockImplementation(async request => {
   const path = new URL(String(request)).pathname
   if (path.startsWith('/api/v1/me/ranch/diagnosis-results/')) {
    startedResolve?.()
    return new Promise<Response>(done => { resolve = done })
   }
   if (path === '/api/v1/me/ranch') return json({ data: null })
   throw new Error(`UNEXPECTED_TRANSPORT ${path}`)
  })
  const wrapper = await mountSuspended(ResultDetailPage, { route: '/my/ranch/results/synthetic-result' })
  wrappers.push(wrapper); await started
  try {
   const auth = useAuthStore()
   await auth.setUser({ id: 2, email: 'synthetic-b@example.invalid', fullName: 'Synthetic B', profileImageUrl: null })
   auth.setTokens('B-access', 'B-refresh')
   await flushPromises()
   resolve?.(json({ data: { id: 'synthetic-result', method: 'DIAGNOSIS', completedAt: '2026-10-04T00:00:00Z', resultSchemaVersion: '1', descriptionSnapshot: { ja: 'PRIVATE_A_ONLY', en: 'PRIVATE_A_ONLY', zh: 'PRIVATE_A_ONLY', ko: 'PRIVATE_A_ONLY', es: 'PRIVATE_A_ONLY', de: 'PRIVATE_A_ONLY' } } }))
   await flushPromises()
   expect(wrapper.text()).not.toContain('PRIVATE_A_ONLY')
  } finally {
   resolve?.(json({ data: null })); await flushPromises()
  }
 })
 it('各方式を既存method filterで取得し、混在ページだけから未診断を決めない', async () => {
  const methods: (string | null)[] = []
  external.fetch.mockImplementation(async request => {
   const url = new URL(String(request))
   expect(url.pathname).toBe('/api/v1/me/ranch/diagnosis-results')
   methods.push(url.searchParams.get('method'))
   return json(empty)
  })
  const wrapper = await mountSuspended(ResultsPage)
  wrappers.push(wrapper); await flushPromises()
  expect(methods.sort()).toEqual(['BIRTH_STYLE', 'DIAGNOSIS'])
  const noResult = useNuxtApp().$i18n.t('ranch.diagnosisResults.notCompleted')
  expect(wrapper.text().split(noResult).length - 1).toBe(2)
 })
 it('片方式の失敗を未診断に変換せず、成功空の別方式は表示する', async () => {
  external.fetch.mockImplementation(async request => {
   const method = new URL(String(request)).searchParams.get('method')
   if (method === 'BIRTH_STYLE') return json(empty)
   return new Response(JSON.stringify({ error: { code: 'COMMON_001', message: 'SYNTHETIC_FAILURE' } }), { status: 500, headers: { 'Content-Type': 'application/json' } })
  })
  const wrapper = await mountSuspended(ResultsPage)
  wrappers.push(wrapper); await flushPromises()
  const noResult = useNuxtApp().$i18n.t('ranch.diagnosisResults.notCompleted')
  expect(wrapper.text().split(noResult).length - 1).toBe(1)
  expect(wrapper.findComponent({ name: 'DashboardErrorState' }).exists()).toBe(true)
 })
 it('装飾の初回GET待機中に空成功と仮の残高0を表示しない', async () => {
  let resolve: ((response: Response) => void) | undefined
  let markStarted: (() => void) | undefined
  const started = new Promise<void>(done => { markStarted = done })
  external.fetch.mockImplementation(async request => {
   const path = new URL(String(request)).pathname
   if (path === '/api/v1/me/ranch') {
    markStarted?.()
    return new Promise<Response>(done => { resolve = done })
   }
   if (path.endsWith('/shop')) return json({ data: [] })
   if (path.endsWith('/collectibles')) return json(empty)
   throw new Error(`UNEXPECTED_TRANSPORT ${path}`)
  })
  const wrapper = await mountSuspended(DecorationsPage)
  wrappers.push(wrapper); await started; await flushPromises()
  try {
   expect(wrapper.findComponent({ name: 'PageLoading' }).exists()).toBe(true)
   expect(wrapper.text()).not.toContain(useNuxtApp().$i18n.t('ranch.decorations.noItems'))
   expect(wrapper.text()).not.toContain(useNuxtApp().$i18n.t('ranch.decorations.balance', { balance: '0' }))
  } finally {
   resolve?.(json({ data: null })); await flushPromises()
  }
 })
})