import { afterAll, afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { ofetch } from 'ofetch'
import ToastService from 'primevue/toastservice'
import ConfirmationService from 'primevue/confirmationservice'
import { useNuxtApp, useRoute } from '#app'
import { useTeamStore } from '~/stores/useTeamStore'
import DashboardErrorState from '~/components/DashboardErrorState.vue'
import type { ShiftScheduleResponse } from '~/types/shift'
import Page from './index.vue'

const { api } = vi.hoisted(() => ({ api: vi.fn() }))
// HTTP通信の境界だけを置換し、ページ・store・API composable・エラー部品は実物を通す。
const createApiSpy = vi.spyOn(ofetch, 'create').mockReturnValue(api as unknown as ReturnType<typeof ofetch.create>)
afterAll(() => createApiSpy.mockRestore())

const schedule: ShiftScheduleResponse = {
  id: 4,
  teamId: 12,
  content: { title: '当番予定', periodType: 'WEEKLY', note: null },
  period: { startDate: '2026-10-12', endDate: '2026-10-12', requestDeadline: null },
  status: { status: 'COLLECTING', publishedAt: null, publishedBy: null },
  audit: { createdBy: 1, createdAt: '2026-10-01T03:00:00Z', updatedAt: '2026-10-01T03:00:00Z' },
}
const schedulePath = '/api/v1/shifts/schedules/4'
const slotsPath = `${schedulePath}/slots`
const teamsPath = '/api/v1/me/teams'
let failedPath: string | null
let failure: unknown
let role: string

beforeEach(() => {
  failedPath = null
  failure = undefined
  role = 'ADMIN'
  useTeamStore(useNuxtApp().$pinia).$reset()
  api.mockReset().mockImplementation(async (path: string) => {
    if (path === failedPath) throw failure
    if (path === teamsPath) {
      return { data: [{ id: 12, slug: 'owned-team', name: '自分のチーム', role }] }
    }
    if (path === schedulePath) return { data: schedule }
    if (path === slotsPath) return { data: [] }
    throw new Error(`Unexpected API path: ${path}`)
  })
  // エラー報告の外部POSTも外へ送らない。表示ロジックは実useErrorHandlerを通す。
  vi.stubGlobal('$fetch', vi.fn().mockResolvedValue({}))
})
afterEach(() => vi.unstubAllGlobals())

async function mountPage() {
  const detailRoute = {
    path: '/shift/4',
    params: { id: '4' },
  } satisfies Pick<ReturnType<typeof useRoute>, 'path' | 'params'>
  const wrapper = await mountSuspended(Page, {
    route: detailRoute,
    global: { plugins: [ToastService, ConfirmationService] },
  })
  await flushPromises()
  return wrapper
}
function calls(path: string): number {
  return api.mock.calls.filter(([calledPath]) => calledPath === path).length
}

async function retry(wrapper: Awaited<ReturnType<typeof mountPage>>) {
  await wrapper.get('[data-testid="load-error-state-retry"]').trigger('click')
  await flushPromises()
}

describe('シフト詳細の取得失敗と再試行', () => {
  it.each([
    ['403', { statusCode: 403 }],
    ['404', { statusCode: 404 }],
    ['通信断', new TypeError('Failed to fetch')],
    ['API失敗', { statusCode: 500 }],
    ['値なしの失敗', undefined],
  ])('%sでも持続エラーを表示し、再試行で全取得をやり直す', async (_, error) => {
    failedPath = schedulePath
    failure = error
    const wrapper = await mountPage()
    expect(wrapper.getComponent(DashboardErrorState).props('error')).toEqual(error)
    expect(wrapper.get('[data-testid="load-error-state"]').text()).not.toBe('')
    expect(wrapper.find('[data-testid="shift-schedule-delete"]').exists()).toBe(false)
    expect(calls(teamsPath)).toBe(1)
    expect(calls(schedulePath)).toBe(1)
    expect(calls(slotsPath)).toBe(1)
    failedPath = null
    await retry(wrapper)
    expect(wrapper.find('[data-testid="load-error-state"]').exists()).toBe(false)
    expect(wrapper.text()).toContain(schedule.content.title)
    expect(wrapper.find('[data-testid="shift-schedule-delete"]').exists()).toBe(true)
    expect(calls(teamsPath)).toBe(2)
    expect(calls(schedulePath)).toBe(2)
    expect(calls(slotsPath)).toBe(2)
  })

  it('slotsだけ失敗しても正常な本文を装わず、再試行で詳細とslotsを再取得する', async () => {
    failedPath = slotsPath
    failure = { statusCode: 500 }
    const wrapper = await mountPage()
    expect(wrapper.find('[data-testid="load-error-state"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain(schedule.content.title)
    failedPath = null
    await retry(wrapper)
    expect(wrapper.text()).toContain(schedule.content.title)
    expect(calls(teamsPath)).toBe(2)
    expect(calls(schedulePath)).toBe(2)
    expect(calls(slotsPath)).toBe(2)
  })

  it('myTeamsの失敗も表示し、復旧後の再試行で依存する詳細とslotsを取得する', async () => {
    failedPath = teamsPath
    failure = { statusCode: 500 }
    const wrapper = await mountPage()
    expect(wrapper.find('[data-testid="load-error-state"]').exists()).toBe(true)
    expect(calls(schedulePath)).toBe(0)
    expect(calls(slotsPath)).toBe(0)
    failedPath = null
    await retry(wrapper)
    expect(wrapper.text()).toContain(schedule.content.title)
    expect(calls(teamsPath)).toBe(2)
    expect(calls(schedulePath)).toBe(1)
    expect(calls(slotsPath)).toBe(1)
  })

  it.each(['ADMIN', 'MEMBER'])('正常取得時の%s管理CTA境界を維持する', async (actorRole) => {
    role = actorRole
    const wrapper = await mountPage()
    expect(wrapper.text()).toContain(schedule.content.title)
    expect(wrapper.find('[data-testid="load-error-state"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="shift-schedule-delete"]').exists()).toBe(actorRole === 'ADMIN')
  })
})