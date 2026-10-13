import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, type VueWrapper } from '@vue/test-utils'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import BroadcastStep1Audience from './BroadcastStep1Audience.vue'
import BroadcastTemplateSelector from './BroadcastTemplateSelector.vue'
import type { AnnouncementTemplate, WizardFormState } from '~/types/announcement_broadcast'

/**
 * F01.2.1 8-E 試練: 告知ウィザード ステップ1「チームグループで選ぶ」。
 * AC-H06(UI) 0件なら次へ不可 / AC-H15(UI) プレビュー・pushEnabled=false の注記 /
 * AC-J02(UI) テンプレート警告 / AC-J03(UI) 範囲の端が削除されたら範囲を空にして再選択 / AC-L05 到達
 */

const mockApi = vi.fn()
vi.mock('~/composables/useApi', () => ({ useApi: () => mockApi }))

mockNuxtImport('useI18n', () => () => ({
  t: (key: string, params?: Record<string, unknown>) => (params ? `${key}:${JSON.stringify(params)}` : key),
  tm: () => ({}),
}))
mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), error: vi.fn(), warning: vi.fn(), warn: vi.fn(), info: vi.fn() }))

const GROUPS = [
  { id: 'g1', name: '平成18年度', sortOrder: 0, teamCount: 3 },
  { id: 'g2', name: '平成19年度', sortOrder: 1, teamCount: 4 },
  { id: 'g3', name: '平成20年度', sortOrder: 2, teamCount: 5 },
]

function preview(over: Record<string, unknown> = {}) {
  return {
    data: {
      resolvedTeamCount: 7, directMemberCount: 3, sampleTeams: [], groups: [], pushEnabled: true, warnings: [], ...over,
    },
  }
}

function apiError(status: number, code: string, message = 'msg') {
  return Object.assign(new Error(message), { status, statusCode: status, data: { error: { code, message } } })
}

let previewResponse: () => unknown
let groupsResponse: () => unknown

function route(url: string, opts?: { method?: string, body?: unknown }) {
  if (url.endsWith('/team-groups')) return Promise.resolve(groupsResponse())
  if (url.endsWith('/broadcast/audience-preview')) {
    const r = previewResponse()
    return r instanceof Error ? Promise.reject(r) : Promise.resolve(r)
  }
  if (url.endsWith('/announcement-templates')) return Promise.resolve({ data: [] })
  if (url.endsWith('/teams')) return Promise.resolve({ data: [{ id: 11, name: 'チームA' }] })
  return Promise.reject(new Error(`unexpected ${opts?.method ?? 'GET'} ${url}`))
}

function previewCalls() {
  return mockApi.mock.calls.filter(([url]) => String(url).endsWith('/broadcast/audience-preview'))
}

function baseState(over: Partial<WizardFormState> = {}): WizardFormState {
  return {
    step: 1,
    targetRole: 'MEMBERS_AND_ABOVE',
    targetMode: 'ALL',
    targetTeamIds: null,
    targetGroupIds: [],
    targetGroupRange: null,
    includeUnassigned: false,
    selectedChannel: null,
    templateId: null,
    priority: 'NORMAL',
    expiresAt: null,
    content: {},
    ...over,
  }
}

let wrapper: VueWrapper | undefined
let latest: WizardFormState

async function render(over: Partial<WizardFormState> = {}) {
  latest = baseState(over)
  wrapper = await mountSuspended(BroadcastStep1Audience, {
    props: {
      modelValue: latest,
      scopeType: 'ORGANIZATION',
      scopeId: 'acme',
      isAdmin: true,
      'onUpdate:modelValue': (v: WizardFormState) => {
        latest = v
        void wrapper?.setProps({ modelValue: v })
      },
    },
  })
  await flushPromises()
  return wrapper
}

/** data-testid の PrimeVue 部品に v-model の更新を流し込む（Select / RadioButton の操作の代わり） */
async function emitFrom(w: VueWrapper, testid: string, value: unknown) {
  const c = w.getComponent(`[data-testid="${testid}"]`) as unknown as VueWrapper
  c.vm.$emit('update:modelValue', value)
  await flushPromises()
}

beforeEach(() => {
  mockApi.mockReset()
  mockApi.mockImplementation(route)
  groupsResponse = () => ({ data: GROUPS, meta: { unassignedTeamCount: 2, limit: 100 } })
  previewResponse = () => preview()
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  vi.useRealTimers()
})

describe('モード選択（グループ機能 on/off）', () => {
  it('グループ機能 on の組織では「チームグループで選ぶ」が選べる', async () => {
    const w = await render()
    expect(w.find('[data-testid="broadcast-target-mode-groups"]').exists()).toBe(true)
    expect(w.find('[data-testid="broadcast-target-mode-teams"]').exists()).toBe(true)
    expect(w.find('[data-testid="broadcast-target-mode-all"]').exists()).toBe(true)
  })

  it('グループ機能 off（409 ORG_067）では従来の2択のまま', async () => {
    groupsResponse = () => { throw apiError(409, 'ORG_067') }
    mockApi.mockImplementation((url: string, opts?: { method?: string }) => {
      if (String(url).endsWith('/team-groups')) return Promise.reject(apiError(409, 'ORG_067'))
      return route(url, opts)
    })
    const w = await render()
    expect(w.find('[data-testid="broadcast-target-mode-groups"]').exists()).toBe(false)
    expect(w.find('[data-testid="broadcast-target-mode-teams"]').exists()).toBe(true)
  })

  it('グループ一覧の取得が 409 以外で失敗したら握りつぶさずエラーを出す', async () => {
    mockApi.mockImplementation((url: string, opts?: { method?: string }) => {
      if (String(url).endsWith('/team-groups')) return Promise.reject(apiError(500, 'COMMON_500', 'boom'))
      return route(url, opts)
    })
    const w = await render({ targetMode: 'GROUPS' })
    expect(w.find('[data-testid="broadcast-groups-error"]').exists()).toBe(true)
  })

  it('グループモードでは並び順どおりのチェックリスト・範囲・未分類・直属メンバー注記・並び順の案内が出る', async () => {
    const w = await render({ targetMode: 'GROUPS' })
    const labels = w.findAll('[data-testid^="broadcast-group-item-"]').map(e => e.text())
    expect(labels).toEqual(['平成18年度', '平成19年度', '平成20年度'])
    expect(w.find('[data-testid="broadcast-range-kind"]').exists()).toBe(true)
    expect(w.find('[data-testid="broadcast-include-unassigned"]').exists()).toBe(true)
    expect(w.text()).toContain('announcement.target_direct_members_note')
    expect(w.text()).toContain('announcement.target_order_hint')
  })
})

describe('グループ選択がモデルに反映される（送信値の元）', () => {
  it('個別チェックで targetGroupIds に積まれ、未分類チェックで includeUnassigned が立つ', async () => {
    const w = await render({ targetMode: 'GROUPS' })
    await w.get('[data-testid="broadcast-group-item-g1"] input').setValue(true)
    expect(latest.targetGroupIds).toEqual(['g1'])
    await w.get('[data-testid="broadcast-include-unassigned"] input').setValue(true)
    expect(latest.includeUnassigned).toBe(true)
  })

  it('範囲「〜以前」で {from: null, to: A}、「〜以降」で {from: A, to: null}、「A〜B」で両端を送る', async () => {
    const w = await render({ targetMode: 'GROUPS' })
    await emitFrom(w, 'broadcast-range-kind', 'UP_TO')
    await emitFrom(w, 'broadcast-range-to', 'g2')
    expect(latest.targetGroupRange).toEqual({ fromGroupId: null, toGroupId: 'g2' })

    await emitFrom(w, 'broadcast-range-kind', 'FROM')
    await emitFrom(w, 'broadcast-range-from', 'g2')
    expect(latest.targetGroupRange).toEqual({ fromGroupId: 'g2', toGroupId: null })

    await emitFrom(w, 'broadcast-range-kind', 'BETWEEN')
    await emitFrom(w, 'broadcast-range-from', 'g1')
    await emitFrom(w, 'broadcast-range-to', 'g3')
    expect(latest.targetGroupRange).toEqual({ fromGroupId: 'g1', toGroupId: 'g3' })
  })

  it('範囲の種類を選んだだけ（端が未選択）の間は範囲を送らない', async () => {
    const w = await render({ targetMode: 'GROUPS' })
    await emitFrom(w, 'broadcast-range-kind', 'UP_TO')
    expect(latest.targetGroupRange).toBeNull()
  })

  it('モードを切り替えると他モードの選択は持ち越さない', async () => {
    const w = await render({ targetMode: 'GROUPS', targetGroupIds: ['g1'], includeUnassigned: true })
    await emitFrom(w, 'broadcast-target-mode-teams', 'TEAMS')
    expect(latest.targetMode).toBe('TEAMS')
    expect(latest.targetGroupIds).toEqual([])
    expect(latest.includeUnassigned).toBe(false)
    expect(latest.targetTeamIds).toEqual([])
  })
})

describe('宛先プレビュー（AC-H15・300ms デバウンス）と「次へ」', () => {
  it('入力が続く間は呼ばず、300ms 静止後に1回だけ宛先指定付きで呼ぶ', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    const w = await render({ targetMode: 'GROUPS', targetGroupIds: ['g1'] })
    await vi.advanceTimersByTimeAsync(200)
    expect(previewCalls()).toHaveLength(0)
    await w.setProps({ modelValue: baseState({ targetMode: 'GROUPS', targetGroupIds: ['g1', 'g2'] }) })
    await vi.advanceTimersByTimeAsync(200)
    expect(previewCalls()).toHaveLength(0)
    await vi.advanceTimersByTimeAsync(150)
    await flushPromises()
    expect(previewCalls()).toHaveLength(1)
    const [url, opts] = previewCalls()[0] as [string, { method: string, body: Record<string, unknown> }]
    expect(url).toBe('/api/v1/organizations/acme/broadcast/audience-preview')
    expect(opts.method).toBe('POST')
    expect(opts.body.targetGroupIds).toEqual(['g1', 'g2'])
    expect(opts.body.targetTeamIds ?? null).toBeNull()
  })

  it('該当チーム数と直属メンバー数を表示し、1件以上なら次へを押せる', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    const w = await render({ targetMode: 'GROUPS', targetGroupIds: ['g1'] })
    await vi.advanceTimersByTimeAsync(350)
    await flushPromises()
    expect(w.get('[data-testid="broadcast-resolved-count"]').text()).toContain('announcement.target_resolved_count')
    expect(w.get('[data-testid="broadcast-resolved-count"]').text()).toContain('"teams":7')
    expect(w.get('[data-testid="broadcast-resolved-count"]').text()).toContain('"members":3')
    expect(w.get('[data-testid="broadcast-step1-next"]').attributes('disabled')).toBeUndefined()
  })

  it('AC-H06: 0チーム・直属メンバー0人なら「該当する人がいません」と出て次へを押せない', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    previewResponse = () => preview({ resolvedTeamCount: 0, directMemberCount: 0 })
    const w = await render({ targetMode: 'GROUPS', targetGroupIds: ['g1'] })
    await vi.advanceTimersByTimeAsync(350)
    await flushPromises()
    expect(w.get('[data-testid="broadcast-resolved-count"]').text()).toContain('announcement.target_resolved_empty')
    expect(w.get('[data-testid="broadcast-step1-next"]').attributes('disabled')).toBeDefined()
  })

  it('グループ指定で何も選んでいない間は、プレビューを呼ばず次へも押せない', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    const w = await render({ targetMode: 'GROUPS' })
    await vi.advanceTimersByTimeAsync(400)
    await flushPromises()
    expect(previewCalls()).toHaveLength(0)
    expect(w.get('[data-testid="broadcast-step1-next"]').attributes('disabled')).toBeDefined()
  })

  it('プレビューが 400（BROADCAST_009 等）なら BE の文言を出し、次へを押せない', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    previewResponse = () => apiError(400, 'BROADCAST_009', '対象になる人がいません')
    const w = await render({ targetMode: 'GROUPS', targetGroupIds: ['g1'] })
    await vi.advanceTimersByTimeAsync(350)
    await flushPromises()
    expect(w.get('[data-testid="broadcast-preview-error"]').text()).toContain('対象になる人がいません')
    expect(w.get('[data-testid="broadcast-preview-error"]').text()).toContain('BROADCAST_009')
    expect(w.get('[data-testid="broadcast-step1-next"]').attributes('disabled')).toBeDefined()
  })

  it('すべてのチームではプレビューを呼ばず、次へを押せる', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    const w = await render()
    await vi.advanceTimersByTimeAsync(400)
    await flushPromises()
    expect(previewCalls()).toHaveLength(0)
    expect(w.get('[data-testid="broadcast-step1-next"]').attributes('disabled')).toBeUndefined()
  })

  it('pushEnabled=false なら「push 通知は送られません」の注記を出し、true なら出さない', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    previewResponse = () => preview({ pushEnabled: false })
    const w = await render({ targetMode: 'GROUPS', targetGroupIds: ['g1'] })
    await vi.advanceTimersByTimeAsync(350)
    await flushPromises()
    expect(w.get('[data-testid="broadcast-push-disabled-note"]').text()).toBe('announcement.push_disabled_note')

    previewResponse = () => preview({ pushEnabled: true })
    await w.setProps({ modelValue: baseState({ targetMode: 'GROUPS', targetGroupIds: ['g1', 'g2'] }) })
    await vi.advanceTimersByTimeAsync(350)
    await flushPromises()
    expect(w.find('[data-testid="broadcast-push-disabled-note"]').exists()).toBe(false)
  })
})

describe('テンプレート（AC-J02/J03）', () => {
  function template(over: Partial<AnnouncementTemplate> = {}): AnnouncementTemplate {
    return {
      id: 5,
      name: 'T',
      targetRole: 'MEMBERS_AND_ABOVE',
      targetTeamIds: null,
      targetGroupIds: ['g1', 'gX'],
      targetGroupRange: null,
      includeUnassigned: true,
      preferredChannel: null,
      isDefault: false,
      createdBy: null,
      createdAt: '2026-10-01T00:00:00',
      ...over,
    } as AnnouncementTemplate
  }

  it('AC-J02: 削除済みグループが除外された警告を出し、選択からも外す', async () => {
    previewResponse = () => preview({ warnings: ['TEMPLATE_GROUPS_REMOVED:1'] })
    const w = await render()
    ;(w.getComponent(BroadcastTemplateSelector) as unknown as VueWrapper).vm.$emit('apply', template())
    await flushPromises()
    expect(latest.targetMode).toBe('GROUPS')
    expect(latest.targetGroupIds).toEqual(['g1'])
    expect(latest.includeUnassigned).toBe(true)
    expect(w.get('[data-testid="broadcast-template-warning"]').text()).toContain('announcement.template_deleted_groups_removed')
    const tplCall = previewCalls().find(([, o]) => (o as { body: { templateId?: number } }).body.templateId === 5)
    expect(tplCall).toBeTruthy()
  })

  it('AC-J03: 範囲の端が削除済み（BROADCAST_013）なら範囲を空にして選び直させる', async () => {
    previewResponse = () => apiError(400, 'BROADCAST_013', '範囲の端が削除されています')
    const w = await render()
    ;(w.getComponent(BroadcastTemplateSelector) as unknown as VueWrapper).vm.$emit('apply', template({
      targetGroupIds: [], targetGroupRange: { fromGroupId: 'gX', toGroupId: 'g3' },
    }))
    await flushPromises()
    expect(latest.targetMode).toBe('GROUPS')
    expect(latest.targetGroupRange).toBeNull()
    expect(latest.templateId).toBeNull()
    expect(w.get('[data-testid="broadcast-template-range-invalid"]').text()).toContain('announcement.template_range_invalid')
    // 範囲の入力欄は残り、選び直せる
    expect(w.find('[data-testid="broadcast-range-kind"]').exists()).toBe(true)
  })

  it('チームを選ぶテンプレートはチームモードで適用される（従来互換）', async () => {
    const w = await render()
    ;(w.getComponent(BroadcastTemplateSelector) as unknown as VueWrapper).vm.$emit('apply', template({
      targetTeamIds: ['11'], targetGroupIds: null, includeUnassigned: false,
    }))
    await flushPromises()
    expect(latest.targetMode).toBe('TEAMS')
    expect(latest.targetTeamIds).toEqual(['11'])
  })
})
