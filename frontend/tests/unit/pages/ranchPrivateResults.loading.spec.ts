// @vitest-environment nuxt
// 実際の私的結果ページ/装飾ページ/Pinia/command memoryを用い、外部HTTPだけを隔離する未実測草稿。
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { setActivePinia } from 'pinia'
import { useNuxtApp } from '#app'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import ResultsPage from '~/pages/my/ranch/results/index.vue'
import ResultDetailPage from '~/pages/my/ranch/results/[resultId].vue'
import type { DiagnosisResult, DiagnosisSession } from '~/types/ranch'
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
// 公開DTO形だけの合成値。実結果・承認済み説明・BE green fixtureではない。
const resultShape: DiagnosisResult = {
 "id": "33333333-3333-4333-8333-333333333333",
 "method": "DIAGNOSIS",
 "completedAt": "2026-10-04T00:00:00.000001Z",
 "resultSchemaVersion": "diagnosis-result-v1",
 "questionnaireVersion": "draft-20261003-v1",
 "scoringVersion": "signed-centered-v1",
 "normalizationVersion": null,
 "ruleVersion": null,
 "mappingVersion": null,
 "typeCode": "000000",
 "axes": {
  "FAMILIAR_NEW": 0,
  "FOCUS_VARIETY": 0,
  "SPONTANEOUS_PLAN": 0,
  "SOLO_TOGETHER": 0,
  "EXPRESSION": 0,
  "NOTICE": 0
 },
 "numberSummary": null,
 "descriptionSnapshot": {
  "ja": "表示形の合成例",
  "en": "Synthetic shape example",
  "zh": "合成示例",
  "ko": "합성 예시",
  "es": "Ejemplo sintético",
  "de": "Synthetisches Beispiel"
 },
 "axisDescriptions": {
  "FAMILIAR_NEW": {
   "ja": "軸A",
   "en": "Axis A",
   "zh": "轴A",
   "ko": "축 A",
   "es": "Eje A",
   "de": "Achse A"
  },
  "FOCUS_VARIETY": {
   "ja": "軸B",
   "en": "Axis B",
   "zh": "轴B",
   "ko": "축 B",
   "es": "Eje B",
   "de": "Achse B"
  },
  "SPONTANEOUS_PLAN": {
   "ja": "軸C",
   "en": "Axis C",
   "zh": "轴C",
   "ko": "축 C",
   "es": "Eje C",
   "de": "Achse C"
  },
  "SOLO_TOGETHER": {
   "ja": "軸D",
   "en": "Axis D",
   "zh": "轴D",
   "ko": "축 D",
   "es": "Eje D",
   "de": "Achse D"
  },
  "EXPRESSION": {
   "ja": "軸E",
   "en": "Axis E",
   "zh": "轴E",
   "ko": "축 E",
   "es": "Eje E",
   "de": "Achse E"
  },
  "NOTICE": {
   "ja": "軸F",
   "en": "Axis F",
   "zh": "轴F",
   "ko": "축 F",
   "es": "Eje F",
   "de": "Achse F"
  }
 }
}
function syntheticResult(text: string): DiagnosisResult {
 return { ...resultShape, descriptionSnapshot: { ja: text, en: text, zh: text, ko: text, es: text, de: text } }
}

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
 it('同時1件の受付でも保存済み診断を再開でき、両方式の結果を取得する', async () => {
  const session: DiagnosisSession = {
   id: '44444444-4444-4444-8444-444444444444', status: 'STARTED', version: '2', answerRevision: '1',
   questionnaireVersion: 'draft-20261003-v1', scoringVersion: 'signed-centered-v1', questions: [],
   answers: [{ questionId: 'saved-question', value: 1 }], tieQuestions: [], resultId: null,
  }
  const requests: string[] = []; const rejected: string[] = []
  let occupied = false
  const releases: (() => void)[] = []
  // 各許可GETの応答を保持し、重複要求が同じ1permitで拒否される境界を作る。
  external.fetch.mockImplementation(async request => {
   const url = new URL(String(request)); const path = url.pathname
   requests.push(path === '/api/v1/me/diagnoses/results' ? `results:${url.searchParams.get('method')}` : path)
   if (occupied) {
    rejected.push(path)
    return new Response(JSON.stringify({ error: { code: 'AUTHOPERATION_001', message: 'SYNTHETIC_ADMISSION_BUSY' } }), { status: 503, headers: { 'Content-Type': 'application/json' } })
   }
   occupied = true
   await new Promise<void>(done => { releases.push(done) })
   occupied = false
   if (path === `/api/v1/me/diagnoses/sessions/${session.id}`) return json({ data: session })
   if (path === '/api/v1/me/diagnoses/results') return json(empty)
   throw new Error(`UNEXPECTED_TRANSPORT ${path}`)
  })
  try {
   const wrapper = await mountSuspended(ResultsPage, { route: `/my/ranch/results?session=${session.id}` })
   wrappers.push(wrapper); await flushPromises()
   for (let index = 0; index < 3; index += 1) { releases.shift()?.(); await flushPromises() }
   expect(wrapper.find(`a[href="/my/ranch/diagnosis?session=${session.id}"]`).exists()).toBe(true)
   expect(requests).toEqual([`/api/v1/me/diagnoses/sessions/${session.id}`, 'results:DIAGNOSIS', 'results:BIRTH_STYLE'])
   expect(rejected).toEqual([])
   expect(wrapper.text().split(useNuxtApp().$i18n.t('ranch.diagnosisResults.notCompleted')).length - 1).toBe(2)
  } finally { for (const release of releases.splice(0)) release(); await flushPromises() }
 }, 60000)
 it('未完了診断の取得失敗後も両方式を取得し、失敗を未診断にしない', async () => {
  const methods: (string | null)[] = []
  external.fetch.mockImplementation(async request => {
   const url = new URL(String(request))
   if (url.pathname === '/api/v1/me/diagnoses/sessions/pending') return new Response(JSON.stringify({ error: { code: 'COMMON_001', message: 'SYNTHETIC_PENDING_FAILURE' } }), { status: 400, headers: { 'Content-Type': 'application/json' } })
   expect(url.pathname).toBe('/api/v1/me/diagnoses/results')
   methods.push(url.searchParams.get('method')); return json(empty)
  })
  const wrapper = await mountSuspended(ResultsPage)
  wrappers.push(wrapper); await flushPromises()
  expect(methods).toEqual(['DIAGNOSIS', 'BIRTH_STYLE'])
  expect(wrapper.findComponent({ name: 'DashboardErrorState' }).exists()).toBe(true)
  expect(wrapper.text().split(useNuxtApp().$i18n.t('ranch.diagnosisResults.notCompleted')).length - 1).toBe(2)
 }, 60000)
 it('初回GET中にページを破棄したら後続の本人結果GETを送信しない', async () => {
  const requests: string[] = []
  let release: ((response: Response) => void) | undefined
  external.fetch.mockImplementation(async request => {
   const path = new URL(String(request)).pathname; requests.push(path)
   if (path === '/api/v1/me/diagnoses/sessions/pending') return new Promise<Response>(done => { release = done })
   if (path === '/api/v1/me/diagnoses/results') return json(empty)
   throw new Error(`UNEXPECTED_TRANSPORT ${path}`)
  })
  const wrapper = await mountSuspended(ResultsPage)
  wrappers.push(wrapper); await flushPromises()
  try {
   wrapper.unmount(); release?.(json({ data: null })); await flushPromises()
   expect(requests).toEqual(['/api/v1/me/diagnoses/sessions/pending'])
  } finally { release?.(json({ data: null })); await flushPromises() }
 }, 60000)
 it('本人結果が読めたら牧場GET待機中も結果本文を表示する', async () => {
  const result = syntheticResult('PRIVATE_RESULT_READY')
  let resolve: ((response: Response) => void) | undefined
  let startedResolve: (() => void) | undefined
  const started = new Promise<void>(done => { startedResolve = done })
  external.fetch.mockImplementation(async request => {
   const path = new URL(String(request)).pathname
   if (path.startsWith('/api/v1/me/diagnoses/results/')) return json({ data: result })
   if (path === '/api/v1/me/ranch') {
    startedResolve?.()
    return new Promise<Response>(done => { resolve = done })
   }
   throw new Error(`UNEXPECTED_TRANSPORT ${path}`)
  })
  const wrapper = await mountSuspended(ResultDetailPage, { route: '/my/ranch/results/33333333-3333-4333-8333-333333333333' })
  wrappers.push(wrapper); await started; await flushPromises()
  try {
   expect(wrapper.text()).toContain('PRIVATE_RESULT_READY')
  } finally {
   resolve?.(json({ data: null })); await flushPromises()
  }
 }, 60000)
 it('保持結果ページの旧A privateGET応答を本人Bに表示しない', async () => {
  let resolve: ((response: Response) => void) | undefined
  let startedResolve: (() => void) | undefined
  const started = new Promise<void>(done => { startedResolve = done })
  const owners: string[] = []
  external.fetch.mockImplementation(async (request, options) => {
   const path = new URL(String(request)).pathname
   if (path.startsWith('/api/v1/me/diagnoses/results/')) {
    const owner = new Headers(options?.headers).get('Authorization') ?? ''
    owners.push(owner)
    if (owner === 'Bearer A-access') {
     if (resolve) throw new Error('UNEXPECTED_DUPLICATE_A_RESULT_GET')
     startedResolve?.()
     return new Promise<Response>(done => { resolve = done })
    }
    if (owner === 'Bearer B-access') return new Response(JSON.stringify({ error: { message: 'SYNTHETIC_OTHER_OWNER_NOT_FOUND' } }), { status: 404, headers: { 'Content-Type': 'application/json' } })
    throw new Error('UNEXPECTED_PRIVATE_RESULT_AUTHORIZATION')
   }
   if (path === '/api/v1/me/ranch') return json({ data: null })
   throw new Error(`UNEXPECTED_TRANSPORT ${path}`)
  })
  const wrapper = await mountSuspended(ResultDetailPage, { route: '/my/ranch/results/33333333-3333-4333-8333-333333333333' })
  wrappers.push(wrapper); await started
  try {
   const auth = useAuthStore()
   auth.setTokens('B-access', 'B-refresh')
   await auth.setUser({ id: 2, email: 'synthetic-b@example.invalid', fullName: 'Synthetic B', profileImageUrl: null })
   await flushPromises()
   resolve?.(json({ data: syntheticResult('PRIVATE_A_ONLY') }))
   await flushPromises()
   expect(wrapper.text()).not.toContain('PRIVATE_A_ONLY')
   expect(owners).toContain('Bearer B-access')
   expect(wrapper.findComponent({ name: 'DashboardErrorState' }).exists()).toBe(true)
  } finally {
   resolve?.(json({ data: null })); await flushPromises()
  }
 }, 60000)
 it('各方式を既存method filterで取得し、混在ページだけから未診断を決めない', async () => {
  const methods: (string | null)[] = []
  external.fetch.mockImplementation(async request => {
   const url = new URL(String(request))
   if (url.pathname === '/api/v1/me/diagnoses/sessions/pending') return json({ data: null })
   expect(url.pathname).toBe('/api/v1/me/diagnoses/results')
   methods.push(url.searchParams.get('method'))
   return json(empty)
  })
  const wrapper = await mountSuspended(ResultsPage)
  wrappers.push(wrapper); await flushPromises()
  expect(methods.sort()).toEqual(['BIRTH_STYLE', 'DIAGNOSIS'])
  const noResult = useNuxtApp().$i18n.t('ranch.diagnosisResults.notCompleted')
  expect(wrapper.text().split(noResult).length - 1).toBe(2)
 }, 60000)
 it('片方式の失敗を未診断に変換せず、成功空の別方式は表示する', async () => {
  external.fetch.mockImplementation(async request => {
   const url = new URL(String(request))
   if (url.pathname === '/api/v1/me/diagnoses/sessions/pending') return json({ data: null })
   expect(url.pathname).toBe('/api/v1/me/diagnoses/results')
   const method = url.searchParams.get('method')
   if (method === 'BIRTH_STYLE') return json(empty)
   return new Response(JSON.stringify({ error: { code: 'COMMON_001', message: 'SYNTHETIC_FAILURE' } }), { status: 500, headers: { 'Content-Type': 'application/json' } })
  })
  const wrapper = await mountSuspended(ResultsPage)
  wrappers.push(wrapper); await flushPromises()
  const noResult = useNuxtApp().$i18n.t('ranch.diagnosisResults.notCompleted')
  expect(wrapper.text().split(noResult).length - 1).toBe(1)
  expect(wrapper.findComponent({ name: 'DashboardErrorState' }).exists()).toBe(true)
 }, 60000)
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
 }, 60000)
})
