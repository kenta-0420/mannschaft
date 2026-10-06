// @vitest-environment nuxt
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope } from 'vue'
import { setActivePinia } from 'pinia'
import { useNuxtApp } from '#app'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useAuthStore } from '~/stores/useAuthStore'
import type { paths } from '~/types/generated'
import { useDiagnosisApi } from './useDiagnosisApi'

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

const listPath = '/api/v1/me/diagnoses/results' satisfies keyof paths
const detailPath = '/api/v1/me/diagnoses/results/{resultId}' satisfies keyof paths
const resultId = '33333333-3333-4333-8333-333333333333'
const scopes: ReturnType<typeof effectScope>[] = []
const source = (path: string) => readFileSync(resolve(process.cwd(), path), 'utf8')
const controller = source('../backend/src/main/java/com/mannschaft/app/diagnosis/controller/DiagnosisController.java')
const controllerBase = controller.match(/@RequestMapping\("([^"]+)"\)/)?.[1]
const controllerGets = [...controller.matchAll(/@GetMapping\("([^"]+)"\)/g)]
  .map(match => `${controllerBase}${match[1]}`)

beforeEach(async () => {
  setActivePinia(useNuxtApp().$pinia)
  const auth = useAuthStore()
  auth.$reset()
  vi.spyOn(auth, 'clearUserCaches').mockResolvedValue()
  await auth.setUser({ id: 1, email: 'synthetic@example.invalid', fullName: 'Synthetic', profileImageUrl: null })
  auth.setTokens('synthetic-access', 'synthetic-refresh')
  external.fetch.mockReset()
  external.report.mockReset()
})
afterEach(() => {
  for (const scope of scopes.splice(0)) scope.stop()
  useAuthStore().$reset()
  vi.restoreAllMocks()
})

describe('診断結果GETと実バックエンド契約', () => {
  it('controller・OpenAPI・生成pathsが一覧と詳細のGET契約で一致する', () => {
    const document = JSON.parse(source('../docs/openapi.json')) as {
      paths: Record<string, { get?: { operationId?: string } }>
    }
    const generated = source('app/types/generated/index.ts')
    const contracts = [[listPath, 'listResults'], [detailPath, 'getResult']] as const
    for (const [path, operation] of contracts) {
      expect(controllerGets).toContain(path)
      expect(document.paths[path]?.get?.operationId).toBe(operation)
      const generatedPath = generated.split(`    "${path}": {`)[1]?.split('\n    "')[0]
      expect(generatedPath).toContain(`get: operations["${operation}"];`)
    }
  })

  it('実composableは契約GETを本人認証・no-storeで送り、filterとcursorを保つ', async () => {
    const requests: string[] = []
    external.fetch.mockImplementation(async (request, options) => {
      const url = new URL(String(request))
      const path = url.pathname
      // mock側のURLをFE実装から複製せず、実controllerのGETだけを受理する。
      const contractPath = path.endsWith(`/${resultId}`)
        ? path.replace(`/${resultId}`, '/{resultId}') : path
      expect(controllerGets).toContain(contractPath)
      expect(options?.method ?? 'GET').toBe('GET')
      expect(options?.cache).toBe('no-store')
      expect(new Headers(options?.headers).get('Authorization')).toBe('Bearer synthetic-access')
      requests.push(path)
      if (path === listPath) {
        expect(url.searchParams.get('method')).toBe('DIAGNOSIS')
        expect(url.searchParams.get('cursor')).toBe('opaque-cursor')
        expect(url.searchParams.get('limit')).toBe('20')
        return new Response(JSON.stringify({
          data: [{ id: resultId }], meta: { hasNext: false, nextCursor: null, limit: 20 },
        }), { headers: { 'Content-Type': 'application/json' } })
      }
      if (path === detailPath.replace('{resultId}', resultId)) {
        return new Response(JSON.stringify({ data: { id: resultId } }), {
          headers: { 'Content-Type': 'application/json' },
        })
      }
      throw new Error('UNEXPECTED_DIAGNOSIS_CONTRACT_GET')
    })
    const scope = effectScope()
    scopes.push(scope)
    const diagnosis = await useNuxtApp().runWithContext(() => scope.run(() => useDiagnosisApi()))
    if (!diagnosis) throw new Error('SYNTHETIC_SCOPE_MISSING')
    expect((await diagnosis.results('DIAGNOSIS', 'opaque-cursor')).data[0]?.id).toBe(resultId)
    expect((await diagnosis.result(resultId)).id).toBe(resultId)
    expect(requests).toEqual([listPath, detailPath.replace('{resultId}', resultId)])
  })
})
