import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, type VueWrapper } from '@vue/test-utils'
import { defineComponent } from 'vue'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import BroadcastWizard from './BroadcastWizard.vue'

/**
 * F01.2.1 8-E: 送信の組み立てと完了パネル（AC-H17）。
 * - グループ宛ては targetGroupIds / 範囲 / 未分類を送り、targetTeamIds は null（BROADCAST_011 を避ける）
 * - 宛先を絞った告知は、送信後すぐ閉じずに「宛先: …（送信時 N チーム）」と注記を見せる
 * - 宛先を絞らない告知は従来どおり送信後に閉じる
 */

const mockApi = vi.fn()
vi.mock('~/composables/useApi', () => ({ useApi: () => mockApi }))

mockNuxtImport('useI18n', () => () => ({
  t: (key: string, params?: Record<string, unknown>) => (params ? `${key}:${JSON.stringify(params)}` : key),
  tm: () => ({}),
}))
const notify = { success: vi.fn(), error: vi.fn(), warn: vi.fn(), info: vi.fn() }
mockNuxtImport('useNotification', () => () => notify)

// ステップの中身は別 spec で検証済み。ここでは結線（送信値・完了パネル）だけを見る。
const Step1 = defineComponent({
  props: ['modelValue'],
  emits: ['update:modelValue', 'next'],
  template: `<button data-testid="s1" @click="go">s1</button>`,
  methods: {
    go() {
      const m = (this as unknown as { modelValue: Record<string, unknown> }).modelValue
      const emit = (this as unknown as { $emit: (e: string, v?: unknown) => void }).$emit
      emit('update:modelValue', {
        ...m,
        targetMode: 'GROUPS',
        targetGroupIds: ['g1'],
        targetGroupRange: { fromGroupId: null, toGroupId: 'g2' },
        includeUnassigned: true,
        selectedChannel: 'BULLETIN_THREAD',
        content: { title: 't', body: 'b' },
      })
      emit('next')
    },
  },
})
const Step1Plain = defineComponent({
  props: ['modelValue'],
  emits: ['update:modelValue', 'next'],
  template: `<button data-testid="s1" @click="go">s1</button>`,
  methods: {
    go() {
      const m = (this as unknown as { modelValue: Record<string, unknown> }).modelValue
      const emit = (this as unknown as { $emit: (e: string, v?: unknown) => void }).$emit
      emit('update:modelValue', { ...m, selectedChannel: 'BULLETIN_THREAD', content: { title: 't', body: 'b' } })
      emit('next')
    },
  },
})
const Step3 = defineComponent({
  emits: ['submit', 'back'],
  template: `<button data-testid="s3" @click="$emit('submit')">s3</button>`,
})
const Step2 = defineComponent({
  props: ['modelValue'],
  emits: ['next'],
  template: `<button data-testid="s2" @click="$emit('next')">s2</button>`,
})
const Dialog = defineComponent({
  props: ['visible', 'header'],
  template: `<section v-if="visible"><h2 data-testid="header">{{ header }}</h2><slot /></section>`,
})
const Button = defineComponent({
  props: ['label'],
  template: `<button type="button">{{ label }}</button>`,
})

function response(over: Record<string, unknown> = {}) {
  return {
    data: {
      announcementFeedId: 1, channel: 'BULLETIN_THREAD', contentId: 2, contentUrl: '/x', targetRole: 'MEMBERS_AND_ABOVE',
      targetTeamIds: null, priority: 'NORMAL', createdAt: '2026-10-13T00:00:00', warnings: [], ...over,
    },
  }
}

async function render(step1: unknown) {
  const w = await mountSuspended(BroadcastWizard, {
    props: { visible: true, scopeType: 'ORGANIZATION', scopeId: 'acme', isAdmin: true },
    global: {
      stubs: {
        Dialog, Button,
        BroadcastStep1Audience: step1 as never,
        BroadcastStep2Channel: Step2,
        BroadcastStep3Content: Step3,
        BroadcastGuideContent: true,
      },
    },
  })
  await w.get('[data-testid="s1"]').trigger('click')
  await flushPromises()
  expect(w.findComponent(Step2).props('modelValue')).toMatchObject({
    step: 2,
    selectedChannel: 'BULLETIN_THREAD',
  })
  await w.get('[data-testid="s2"]').trigger('click')
  await flushPromises()
  await w.get('[data-testid="s3"]').trigger('click')
  await flushPromises()
  return w
}

function lastBody(): Record<string, unknown> {
  const call = mockApi.mock.calls.find(([url]) => String(url).endsWith('/broadcast')) as [string, { body: Record<string, unknown> }]
  return call[1].body
}

beforeEach(() => {
  mockApi.mockReset()
  Object.values(notify).forEach(f => f.mockReset())
})

describe('告知ウィザードの送信と完了パネル', () => {
  it('グループ宛ては個別・範囲・未分類を送り、targetTeamIds は null', async () => {
    mockApi.mockResolvedValue(response({ targetAudience: { mode: 'GROUPS', groups: [], includeUnassigned: true, teamCount: 4 } }))
    await render(Step1)
    const body = lastBody()
    expect(body.targetGroupIds).toEqual(['g1'])
    expect(body.targetGroupRange).toEqual({ fromGroupId: null, toGroupId: 'g2' })
    expect(body.includeUnassigned).toBe(true)
    expect(body.targetTeamIds).toBeNull()
  })

  it('宛先を絞った告知は閉じずに、宛先と表示判定の注記を見せ、閉じるボタンで閉じる', async () => {
    mockApi.mockResolvedValue(response({
      targetAudience: {
        mode: 'GROUPS', groups: [{ id: 'g1', name: '平成18年度' }], includeUnassigned: false, teamCount: 12, directMemberCount: 1,
      },
    }))
    const w = await render(Step1)
    expect(w.emitted('update:visible')).toBeUndefined()
    expect(w.find('[data-testid="broadcast-sent-panel"]').exists()).toBe(true)
    expect(w.get('[data-testid="broadcast-audience-summary"]').text()).toContain('平成18年度')
    expect(w.get('[data-testid="broadcast-audience-summary"]').text()).toContain('"count":12')
    expect(w.find('[data-testid="broadcast-audience-display-note"]').exists()).toBe(true)
    expect(w.get('[data-testid="header"]').text()).toBe('announcement.broadcast_done_title')

    await w.get('[data-testid="broadcast-sent-close"]').trigger('click')
    expect((w as VueWrapper).emitted('update:visible')?.at(-1)).toEqual([false])
  })

  it('宛先を絞らない告知（記録なし）は従来どおり送信後に閉じる', async () => {
    mockApi.mockResolvedValue(response({ targetAudience: null }))
    const w = await render(Step1Plain)
    expect(w.find('[data-testid="broadcast-sent-panel"]').exists()).toBe(false)
    expect(w.emitted('update:visible')?.at(-1)).toEqual([false])
  })

  it('テンプレートの除外警告が応答に載っていれば利用者へ警告する', async () => {
    mockApi.mockResolvedValue(response({
      warnings: ['TEMPLATE_GROUPS_REMOVED:2'],
      targetAudience: { mode: 'GROUPS', groups: [], includeUnassigned: true, teamCount: 1 },
    }))
    await render(Step1)
    expect(notify.warn).toHaveBeenCalledWith('announcement.template_deleted_groups_removed')
  })
})
