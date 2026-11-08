// @vitest-environment nuxt
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterEach, beforeEach, describe, expect, expectTypeOf, it, vi } from 'vitest'
import { defineComponent } from 'vue'
import { setActivePinia } from 'pinia'
import { useNuxtApp } from '#app'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useAuthStore } from '~/stores/useAuthStore'
import type { paths } from '~/types/generated'
import type { AssignmentResult, RanchState } from '~/types/ranch'
import type { useRanchApi } from './useRanchApi'
import { useRanchState } from './useRanchState'

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

const assignmentPath = '/api/v1/me/ranch/assignment' satisfies keyof paths
const dinosaurId = '33333333-3333-4333-8333-333333333333'
const wrappers: { unmount: () => void }[] = []
const source = (path: string) => readFileSync(resolve(process.cwd(), path), 'utf8')
const controller = source('../backend/src/main/java/com/mannschaft/app/ranch/controller/RanchAssignmentController.java')
const controllerBase = controller.match(/@RequestMapping\("([^"]+)"\)/)?.[1]
const controllerPut = controller.match(/@PutMapping\("([^"]+)"\)/)?.[1]
const selected: AssignmentResult = {
  dinosaurId, method: 'HABITAT_RANDOM', habitat: 'LAND', speciesKey: 'DEV_TRICERATOPS',
  variantKey: 'DEV_ORANGE_96_WALK_V1', speciesCatalogVersion: '2',
  confirmedAt: '2032-01-06T12:00:00Z', version: '2',
}
const state: RanchState = {
  featureStatus: 'AVAILABLE', deliveryPaused: false, rewardsStatus: 'DISABLED', shopAvailable: false,
  owner: { id: '11111111-1111-4111-8111-111111111111', status: 'ACTIVE', balance: '0', version: '1' },
  dinosaur: { id: dinosaurId, speciesKey: selected.speciesKey!, variantKey: selected.variantKey!,
    habitat: 'LAND', speciesCatalogVersion: '2', stage: 'EGG', name: null, xp: '0', nextStageXp: null,
    version: '2', namedAt: null, egg: null },
  settings: null, roomSlots: [], serverTime: '2032-01-06T12:00:00Z', policyVersion: null,
  careBudget: null, weekBudget: null,
  assignment: { availableMethods: ['HABITAT_RANDOM'], selectionConfirmed: true, confirmedMethod: 'HABITAT_RANDOM' },
}

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
  for (const wrapper of wrappers.splice(0)) wrapper.unmount()
  useAuthStore().$reset()
  vi.restoreAllMocks()
})

describe('牧場選定PUTの実AssignmentResult契約', () => {
  it('controller・DTO・OpenAPIはRanchStateではなく平坦なAssignmentResultを返す', () => {
    expect(`${controllerBase}${controllerPut}`).toBe(assignmentPath)
    expect(controller).toContain('ResponseEntity<ApiResponse<AssignmentResult>>')
    const dto = source('../backend/src/main/java/com/mannschaft/app/ranch/dto/AssignmentResult.java')
    expect(dto).toContain('record AssignmentResult(UUID dinosaurId')
    const document = JSON.parse(source('../docs/openapi.json')) as {
      paths: Record<string, { put?: { operationId?: string; responses: Record<string, {
        content: Record<string, { schema: { $ref?: string } }>
      }> } }>
      components: { schemas: Record<string, { properties: Record<string, unknown> }> }
    }
    const operation = document.paths[assignmentPath]?.put
    expect(operation?.operationId).toBe('assign')
    expect(operation?.responses['200']?.content['*/*']?.schema.$ref)
      .toBe('#/components/schemas/ApiResponseAssignmentResult')
    expect(Object.keys(document.components.schemas.AssignmentResult!.properties).sort())
      .toEqual(Object.keys(selected).sort())
    expectTypeOf<Awaited<ReturnType<ReturnType<typeof useRanchApi>['assignment']>>>()
      .toEqualTypeOf<AssignmentResult>()
  })

  it('実commandはPUT結果を保持し、actは別GETで本人stateを再取得する', async () => {
    const calls: string[] = []
    external.fetch.mockImplementation(async (request, options) => {
      const path = new URL(String(request)).pathname
      calls.push(`${options?.method ?? 'GET'} ${path}`)
      expect(new Headers(options?.headers).get('Authorization')).toBe('Bearer synthetic-access')
      if (path === `${controllerBase}${controllerPut}` && options?.method === 'PUT') {
        expect(JSON.parse(String(options.body))).toEqual({ method: 'HABITAT_RANDOM', habitat: 'LAND', version: '1' })
        expect(new Headers(options.headers).get('Idempotency-Key')).toBeTruthy()
        return new Response(JSON.stringify({ data: selected }), { headers: { 'Content-Type': 'application/json' } })
      }
      if (path === controllerBase && (options?.method ?? 'GET') === 'GET') {
        expect(options?.cache).toBe('no-store')
        return new Response(JSON.stringify({ data: state }), { headers: { 'Content-Type': 'application/json' } })
      }
      throw new Error('UNEXPECTED_RANCH_CONTRACT_TRANSPORT')
    })
    let ranch: ReturnType<typeof useRanchState> | undefined
    const harness = defineComponent({
      setup() {
        // useErrorHandlerのuseI18nも含め、製品と同じVue setupで初期化する。
        ranch = useRanchState()
        return () => null
      },
    })
    wrappers.push(await mountSuspended(harness))
    const currentRanch = ranch
    if (!currentRanch) throw new Error('SYNTHETIC_SCOPE_MISSING')
    const result = await currentRanch.act(() => currentRanch.api.assignment({ method: 'HABITAT_RANDOM', habitat: 'LAND', version: '1' }))
    expect(result).toEqual(selected)
    expect(result).not.toHaveProperty('dinosaur')
    expect(currentRanch.state.value?.dinosaur?.id).toBe(result.dinosaurId)
    expect(currentRanch.state.value?.dinosaur?.stage).toBe('EGG')
    expect(currentRanch.state.value?.assignment?.selectionConfirmed).toBe(true)
    expect(currentRanch.state.value?.dinosaur?.speciesKey).toBe(result.speciesKey)
    expect(currentRanch.state.value?.dinosaur?.variantKey).toBe(result.variantKey)
    expect(calls).toEqual([`PUT ${assignmentPath}`, `GET ${controllerBase}`])
  })
})
