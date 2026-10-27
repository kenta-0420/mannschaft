import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { ref } from 'vue'
import Outbox from './system-admin/email-outbox/index.vue'
import Committees from './organizations/[slug]/committees.vue'
import Distributions from './committees/[id]/distributions.vue'
import Users from './admin/users.vue'
import { springPage } from '../../tests/fixtures/spring-page'

const { rawApi, showError } = vi.hoisted(() => ({ rawApi: vi.fn(), showError: vi.fn() }))
mockNuxtImport('useApi', () => () => rawApi)
mockNuxtImport('useRoute', () => () => ({ params: { slug: '7', id: '9' } }))
mockNuxtImport('useNotification', () => () => ({ error: showError, success: vi.fn() }))
mockNuxtImport('useRoleAccess', () => () => ({ isAdminOrDeputy: ref(false), loadPermissions: vi.fn(async () => {}) }))
mockNuxtImport('useDatetime', () => () => ({ formatDateTime: (s: string) => s, userTimezone: ref('Asia/Tokyo') }))

const cases = [
  { name: 'outbox', component: Outbox, endpoint: '/api/v1/system-admin/email-outbox', errorId: 'email-outbox-error-state', label: 'VERIFICATION-41',
    row: (id: number) => ({ id: 'row-' + id, status: 'PENDING', templateKind: 'VERIFICATION-' + id, sourceDomain: 'auth', retryCount: 0, createdAt: '2026-10-20T03:00:00', nextAttemptAt: null, sentAt: null, lastError: null }) },
  { name: 'committee一覧', component: Committees, endpoint: '/api/v1/organizations/7/committees', errorId: 'committees-list-error-state', label: '委員会41',
    row: (id: number) => ({ id, name: '委員会' + id, status: 'ACTIVE', memberCount: 1, description: null }) },
  { name: 'committee配布履歴', component: Distributions, endpoint: '/api/v1/committees/9/distributions', errorId: 'committee-distributions-error-state', label: '配布41',
    row: (id: number) => ({ id, customTitle: '配布' + id, contentType: 'CUSTOM_MESSAGE', targetScope: 'COMMITTEE_ONLY', confirmationMode: 'NONE', createdAt: '2026-10-20T03:00:00' }) },
  { name: 'dashboard users', component: Users, endpoint: '/api/v1/system-admin/dashboard/users', errorId: 'admin-users-error-state', label: '利用者41',
    row: (id: number) => ({ id, displayName: '利用者' + id, createdAt: '2026-10-20T03:00:00' }) },
] as const

beforeEach(() => { rawApi.mockReset(); showError.mockReset() })

function setupRaw(entry: typeof cases[number], total: number, fail = false) {
  let failed = fail
  rawApi.mockImplementation(async (url: string) => {
    if (url.includes('/metrics')) return { data: { queueDepthPending: 0, queueDepthDeadLetter: 0, successRate24h: null, oldestPendingAgeSeconds: null } }
    if (!url.startsWith(entry.endpoint)) throw new Error('想定外HTTP: ' + url)
    if (failed) { failed = false; throw new Error('network') }
    const page = Number(new URL(url, 'http://localhost').searchParams.get('page') ?? 0)
    const all = Array.from({ length: total }, (_, i) => entry.row(i + 1))
    return { data: springPage(all.slice(page * 20, (page + 1) * 20), total, page), message: null }
  })
}

function listCalls(entry: typeof cases[number]) {
  return rawApi.mock.calls.filter(([url]) => String(url).split('?')[0] === entry.endpoint)
}

for (const entry of cases) {
  describe(entry.name + ' 実ページとrawHTTPの契約', () => {
    it.each([0, 1, 20, 21, 40, 41])('%i件をPageのプロパティではなく実行として表示する', async (total) => {
      setupRaw(entry, total)
      const wrapper = await mountSuspended(entry.component)
      await flushPromises()

      expect(wrapper.find('[data-testid="' + entry.errorId + '"]').exists()).toBe(false)
      if (total > 0) expect(wrapper.text()).toContain(entry.row(1).id === 'row-1' ? 'VERIFICATION-1' : entry.name === 'committee一覧' ? '委員会1' : entry.name === 'committee配布履歴' ? '配布1' : '利用者1')
      const pager = wrapper.findComponent({ name: entry.name === 'dashboard users' ? 'DataTable' : 'Paginator' })
      if (entry.name !== 'outbox' && total > 20) expect(pager.props('totalRecords')).toBe(total)
      if (entry.name === 'outbox' && total > 0) expect(wrapper.text()).toContain(String(total) + ' 件中')
      if (entry.name === 'outbox') {
        expect(wrapper.findAll('tbody tr')).toHaveLength(Math.min(total, 20))
        if (total === 0) expect(wrapper.text()).toContain('データがありません')
      } else if (entry.name === 'dashboard users') {
        expect(wrapper.findComponent({ name: 'DataTable' }).props('value')).toHaveLength(Math.min(total, 20))
        if (total === 0) expect(wrapper.text()).toContain('ユーザーがありません')
      } else {
        expect(wrapper.findAll('.divide-y > div')).toHaveLength(Math.min(total, 20))
        expect(wrapper.findComponent({ name: 'DashboardEmptyState' }).exists()).toBe(total === 0)
      }
      expect(listCalls(entry)).toHaveLength(1)
      expect(wrapper.text()).not.toContain('1〜0')
    })

    it('41件のpage0→1→2→1で総件数を維持する', async () => {
      setupRaw(entry, 41)
      const wrapper = await mountSuspended(entry.component)
      await flushPromises()
      const previous = entry.name === 'outbox'
        ? wrapper.findAll('button').find(b => b.find('.pi-chevron-left').exists())!
        : wrapper.findComponent({ name: 'PrevPageLink' })
      expect(previous.attributes('disabled')).toBeDefined()
      for (const page of [1, 2, 1]) {
        if (entry.name === 'outbox') {
          const buttons = wrapper.findAll('button')
          const button = buttons.find(b => b.find(page === 1 && listCalls(entry).length === 3 ? '.pi-chevron-left' : '.pi-chevron-right').exists())
          expect(button).toBeDefined()
          await button!.trigger('click')
        } else {
          wrapper.findComponent({ name: entry.name === 'dashboard users' ? 'DataTable' : 'Paginator' }).vm.$emit('page', { page, rows: 20, first: page * 20 })
        }
        await flushPromises()
        const calls = listCalls(entry)
        expect(String(calls.at(-1)?.[0])).toContain('page=' + page)
        if (page === 2) {
          expect(wrapper.text()).toContain(entry.label)
          const next = entry.name === 'outbox'
            ? wrapper.findAll('button').find(b => b.find('.pi-chevron-right').exists())!
            : wrapper.findComponent({ name: 'NextPageLink' })
          expect(next.attributes('disabled')).toBeDefined()
        }
        if (entry.name === 'outbox') expect(wrapper.text()).toContain('41 件中')
        else expect(wrapper.findComponent({ name: entry.name === 'dashboard users' ? 'DataTable' : 'Paginator' }).props('totalRecords')).toBe(41)
      }
      expect(listCalls(entry)).toHaveLength(4)
    })

    it('表示済み一覧の次要求失敗では旧行と総件数を残さず再試行で戻る', async () => {
      setupRaw(entry, 41)
      const wrapper = await mountSuspended(entry.component)
      await flushPromises()
      const label = (id: number) => entry.name === 'outbox' ? 'VERIFICATION-' + id : entry.name === 'committee一覧' ? '委員会' + id : entry.name === 'committee配布履歴' ? '配布' + id : '利用者' + id
      expect(wrapper.text()).toContain(label(1))
      rawApi.mockRejectedValueOnce(new Error('network'))
      if (entry.name === 'outbox') {
        await wrapper.findAll('button').find(b => b.find('.pi-chevron-right').exists())!.trigger('click')
      } else {
        wrapper.findComponent({ name: entry.name === 'dashboard users' ? 'DataTable' : 'Paginator' }).vm.$emit('page', { page: 1, rows: 20, first: 20 })
      }
      await flushPromises()
      expect(wrapper.find('[data-testid="' + entry.errorId + '"]').exists()).toBe(true)
      expect(wrapper.text()).not.toContain(label(1))
      expect(wrapper.text()).not.toContain('41 件中')
      expect(wrapper.findComponent({ name: entry.name === 'dashboard users' ? 'DataTable' : 'Paginator' }).exists()).toBe(false)
      await wrapper.find('[data-testid="' + entry.errorId + '-retry"]').trigger('click')
      await flushPromises()
      expect(wrapper.find('[data-testid="' + entry.errorId + '"]').exists()).toBe(false)
      expect(wrapper.text()).toContain(label(21))
      expect(listCalls(entry)).toHaveLength(3)
    })

    it('初回失敗は空表示と区別し再試行で行へ戻る', async () => {
      setupRaw(entry, 1, true)
      const wrapper = await mountSuspended(entry.component)
      await flushPromises()
      expect(wrapper.find('[data-testid="' + entry.errorId + '"]').exists()).toBe(true)
      await wrapper.find('[data-testid="' + entry.errorId + '-retry"]').trigger('click')
      await flushPromises()
      expect(wrapper.find('[data-testid="' + entry.errorId + '"]').exists()).toBe(false)
      expect(listCalls(entry)).toHaveLength(2)
    })
  })
}


describe('outboxの既存操作をraw HTTPで回帰する', () => {
  const entry = cases[0]
  it('末尾からstatus/sourceDomainの適用・解除でpage0へ戻り、余分なmetricsを取らない', async () => {
    setupRaw(entry, 41)
    const wrapper = await mountSuspended(Outbox)
    await flushPromises()
    const next = () => wrapper.findAll('button').find(b => b.find('.pi-chevron-right').exists())!
    await next().trigger('click'); await flushPromises()
    await next().trigger('click'); await flushPromises()
    wrapper.findComponent({ name: 'Select' }).vm.$emit('update:modelValue', 'DEAD_LETTER')
    await wrapper.find('input[placeholder="送信元ドメイン"]').setValue('auth')
    await wrapper.findAll('button').find(b => b.text() === '適用')!.trigger('click')
    await flushPromises()
    let query = new URL(String(listCalls(entry).at(-1)![0]), 'http://localhost').searchParams
    expect(Object.fromEntries(query)).toMatchObject({ page: '0', size: '20', status: 'DEAD_LETTER', sourceDomain: 'auth' })
    await wrapper.findAll('button').find(b => b.text() === 'クリア')!.trigger('click')
    await flushPromises()
    query = new URL(String(listCalls(entry).at(-1)![0]), 'http://localhost').searchParams
    expect(Object.fromEntries(query)).toEqual({ page: '0', size: '20' })
    expect(listCalls(entry)).toHaveLength(5)
    expect(rawApi.mock.calls.filter(([url]) => String(url).includes('/metrics'))).toHaveLength(1)
  })

  for (const action of ['retry', 'cancel'] as const) {
    for (const status of [204, 409]) {
      it(action + ' ' + status + 'で既存の再取得または競合通知を維持する', async () => {
        setupRaw(entry, 1)
        const list = rawApi.getMockImplementation()!
        rawApi.mockImplementation(async (url: string) => {
          if (url.endsWith('/' + action)) {
            if (status === 409) throw { statusCode: 409 }
            return undefined
          }
          const result = await list(url)
          if (url.split('?')[0] === entry.endpoint && action === 'retry') result.data.content[0].status = 'DEAD_LETTER'
          return result
        })
        const wrapper = await mountSuspended(Outbox)
        await flushPromises()
        await wrapper.findAll('button').find(b => b.text() === (action === 'retry' ? '再試行' : 'キャンセル'))!.trigger('click')
        await flushPromises()
        expect(rawApi.mock.calls.filter(([url]) => String(url).endsWith('/' + action))).toHaveLength(1)
        expect(listCalls(entry)).toHaveLength(status === 204 ? 2 : 1)
        if (status === 409) expect(showError).toHaveBeenCalledWith(action === 'retry' ? 'この状態では再試行できません' : 'この状態ではキャンセルできません')
      })
    }
  }
})
