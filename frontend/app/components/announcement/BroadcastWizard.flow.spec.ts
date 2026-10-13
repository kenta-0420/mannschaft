import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, type VueWrapper } from '@vue/test-utils'
import { defineComponent } from 'vue'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import BroadcastWizard from './BroadcastWizard.vue'
import BroadcastTemplateSelector from './BroadcastTemplateSelector.vue'

/**
 * F01.2.1 8-E: 通常導線をウィザード全体でたどる（ステップ1 → 2 → 3 と戻り）。
 * ステップ1にチャネルを直接渡す形ではなく、実際に画面操作でチャネルを選んで進む。
 *
 * - push なしの注記: ステップ1ではチャネル未定のため出さず、選んだ実チャネルが決まるステップ3で再判定して出す（§13.2）
 * - 削除で空になったテンプレート: ステップ2から戻って再マウントされても「次へ」を押せる
 */

const mockApi = vi.fn()
vi.mock('~/composables/useApi', () => ({ useApi: () => mockApi }))

mockNuxtImport('useI18n', () => () => ({
  t: (key: string, params?: Record<string, unknown>) => (params ? `${key}:${JSON.stringify(params)}` : key),
  tm: () => ({}),
}))
mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), error: vi.fn(), warn: vi.fn(), info: vi.fn() }))

const Dialog = defineComponent({
  props: ['visible', 'header'],
  template: `<section v-if="visible"><slot /></section>`,
})

const GROUPS = [
  { id: 'g1', name: '平成18年度', sortOrder: 0, teamCount: 3 },
  { id: 'g2', name: '平成19年度', sortOrder: 1, teamCount: 4 },
]

let previewImpl: (body: Record<string, unknown>) => unknown

function route(url: string, opts?: { method?: string, body?: Record<string, unknown> }) {
  if (url.endsWith('/team-groups')) return Promise.resolve({ data: GROUPS, meta: { unassignedTeamCount: 0, limit: 100 } })
  if (url.endsWith('/broadcast/audience-preview')) return Promise.resolve(previewImpl(opts?.body ?? {}))
  if (url.endsWith('/announcement-templates')) return Promise.resolve({ data: [] })
  if (url.endsWith('/teams')) return Promise.resolve({ data: [] })
  return Promise.reject(new Error(`unexpected ${opts?.method ?? 'GET'} ${url}`))
}

function previewBody(over: Record<string, unknown> = {}) {
  return { data: { resolvedTeamCount: 3, directMemberCount: 1, sampleTeams: [], groups: [], pushEnabled: true, warnings: [], ...over } }
}

function previewCalls() {
  return mockApi.mock.calls.filter(([url]) => String(url).endsWith('/broadcast/audience-preview')) as
    Array<[string, { body: Record<string, unknown> }]>
}

let wrapper: VueWrapper | undefined

async function mountWizard() {
  wrapper = await mountSuspended(BroadcastWizard, {
    props: { visible: true, scopeType: 'ORGANIZATION', scopeId: 'acme', isAdmin: true },
    global: { stubs: { Dialog, BroadcastGuideContent: true } },
  })
  await flushPromises()
  return wrapper
}

async function click(w: VueWrapper, testid: string) {
  await w.get(`[data-testid="${testid}"]`).trigger('click')
  await flushPromises()
}

async function settle() {
  await vi.advanceTimersByTimeAsync(350)
  await flushPromises()
}

beforeEach(() => {
  mockApi.mockReset()
  mockApi.mockImplementation(route)
  previewImpl = () => previewBody()
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  vi.useRealTimers()
})

describe('通常導線（チャネルは画面操作で選ぶ）', () => {
  it('Medium(2巡目): ステップ1では出ず、チャネルを選んだ後のステップ3で実チャネルの pushEnabled=false が注記になる', async () => {
    // BULLETIN_THREAD だけ push が出ない（アンケートだけが push を出す）
    previewImpl = body => previewBody({ pushEnabled: body.channel === 'SURVEY' })
    const w = await mountWizard()

    await w.get('[data-testid="broadcast-target-mode-groups"] input').setValue(true)
    await flushPromises()
    await w.get('[data-testid="broadcast-group-item-g1"] input').setValue(true)
    await settle()
    expect(w.find('[data-testid="broadcast-push-disabled-note"]').exists()).toBe(false)
    expect(previewCalls().every(([, o]) => o.body.channel === undefined)).toBe(true)

    await click(w, 'broadcast-step1-next')
    await click(w, 'broadcast-channel-BULLETIN_THREAD')
    await click(w, 'broadcast-step2-next')

    // ステップ3: 選んだ実チャネルで引き直した結果
    expect(previewCalls().at(-1)?.[1].body.channel).toBe('BULLETIN_THREAD')
    expect(w.get('[data-testid="broadcast-push-disabled-note"]').text()).toBe('announcement.push_disabled_note')
  })

  it('Medium(2巡目): push が出るチャネルでは注記を出さない', async () => {
    previewImpl = body => previewBody({ pushEnabled: body.channel === 'SURVEY' })
    const w = await mountWizard()
    await w.get('[data-testid="broadcast-target-mode-groups"] input').setValue(true)
    await flushPromises()
    await w.get('[data-testid="broadcast-group-item-g1"] input').setValue(true)
    await settle()
    await click(w, 'broadcast-step1-next')
    await click(w, 'broadcast-channel-SURVEY')
    await click(w, 'broadcast-step2-next')
    expect(previewCalls().at(-1)?.[1].body.channel).toBe('SURVEY')
    expect(w.find('[data-testid="broadcast-push-disabled-note"]').exists()).toBe(false)
  })

  it('Medium(2巡目): 削除で空になったテンプレートは、ステップ2から戻って再マウントされても次へ進める', async () => {
    previewImpl = () => previewBody({ warnings: ['TEMPLATE_GROUPS_REMOVED:1'], resolvedTeamCount: 0, directMemberCount: 2 })
    const w = await mountWizard()
    ;(w.getComponent(BroadcastTemplateSelector) as unknown as VueWrapper).vm.$emit('apply', {
      id: 5, name: 'T', targetRole: 'MEMBERS_AND_ABOVE', targetTeamIds: null, targetGroupIds: ['gX'],
      targetGroupRange: null, includeUnassigned: false, preferredChannel: null, isDefault: false, createdBy: null,
      createdAt: '2026-10-01T00:00:00',
    })
    await flushPromises()
    await settle()
    expect(w.get('[data-testid="broadcast-step1-next"]').attributes('disabled')).toBeUndefined()

    await click(w, 'broadcast-step1-next')
    await click(w, 'broadcast-channel-SURVEY')
    await click(w, 'broadcast-step2-next')
    await click(w, 'broadcast-step3-back')
    await click(w, 'broadcast-step2-back')

    // ステップ1が作り直されても、状態はフォームにあるので次へ進める
    await settle()
    expect(w.find('[data-testid="broadcast-step1-next"]').exists()).toBe(true)
    expect(w.get('[data-testid="broadcast-step1-next"]').attributes('disabled')).toBeUndefined()
    const last = previewCalls().at(-1)?.[1].body
    expect(last?.templateId).toBe(5)
    expect(last?.targetGroupIds).toBeUndefined()
  })
})

describe('実際のテンプレートセレクター経由（update:modelValue → apply の順序を再現）', () => {
  const Step3Stub = defineComponent({
    emits: ['submit', 'back'],
    template: `<button data-testid="s3" @click="$emit('submit')">s3</button>`,
  })
  const TEMPLATE = {
    id: 5, name: 'T', targetRole: 'PUBLIC', targetTeamIds: null, targetGroupIds: ['g1'],
    targetGroupRange: null, includeUnassigned: false, preferredChannel: null, isDefault: false, createdBy: null,
    createdAt: '2026-10-01T00:00:00',
  }

  function sentBody(): Record<string, unknown> | undefined {
    const call = mockApi.mock.calls.find(([url, o]) => String(url).endsWith('/broadcast') && (o as { method?: string })?.method === 'POST') as
      [string, { body: Record<string, unknown> }] | undefined
    return call?.[1].body
  }

  it('Medium(4巡目): 「すべて」のまま一覧取得中にグループ付きテンプレートを選び公開範囲を変えても、未適用の templateId は送られない', async () => {
    let release: (v: unknown) => void = () => {}
    mockApi.mockImplementation((url: string, opts?: { method?: string, body?: Record<string, unknown> }) => {
      if (url.endsWith('/team-groups')) return new Promise(r => { release = r })
      if (url.endsWith('/announcement-templates')) return Promise.resolve({ data: [TEMPLATE] })
      if (url.endsWith('/broadcast') && opts?.method === 'POST') {
        return Promise.resolve({ data: { announcementFeedId: 1, channel: 'BULLETIN_THREAD', contentId: 2, contentUrl: '/x', targetRole: 'SUPPORTERS_AND_ABOVE', targetTeamIds: null, priority: 'NORMAL', createdAt: '2026-10-14T00:00:00', warnings: [] } })
      }
      return route(url, opts)
    })
    wrapper = await mountSuspended(BroadcastWizard, {
      props: { visible: true, scopeType: 'ORGANIZATION', scopeId: 'acme', isAdmin: true },
      global: { stubs: { Dialog, BroadcastGuideContent: true, BroadcastStep3Content: Step3Stub } },
    })
    const w = wrapper
    await flushPromises()

    // 実セレクターの Select が値を選ぶ（update:modelValue で templateId を保存 → apply の順）
    const select = w.getComponent(BroadcastTemplateSelector).findComponent({ name: 'Select' })
    select.vm.$emit('update:modelValue', 5)
    await flushPromises()
    // 一覧の取得待ち中に、利用者が公開範囲を絞る（適用は失効する）
    await w.get('#target_role_SUPPORTERS_AND_ABOVE').setValue(true)
    await flushPromises()
    release({ data: GROUPS, meta: { unassignedTeamCount: 0, limit: 100 } })
    await flushPromises()
    await settle()

    // 適用は捨てられ、画面は「すべて」のまま。未適用の templateId は残らない
    await click(w, 'broadcast-step1-next')
    await click(w, 'broadcast-channel-BULLETIN_THREAD')
    await click(w, 'broadcast-step2-next')
    await w.get('[data-testid="s3"]').trigger('click')
    await flushPromises()
    const body = sentBody()
    expect(body).toBeDefined()
    expect(body?.templateId ?? null).toBeNull()
    expect(body?.targetRole).toBe('SUPPORTERS_AND_ABOVE')
    expect(body?.targetGroupIds ?? []).toEqual([])
    expect(body?.targetTeamIds ?? null).toBeNull()
  })
})
