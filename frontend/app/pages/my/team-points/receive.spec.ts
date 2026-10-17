// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { ref, shallowRef, onMounted, onUnmounted } from 'vue'
import ReceivePage from './receive.vue'
import {
  captureLoyaltyReceptionToken,
  takeLoyaltyReceptionToken,
} from '~/utils/loyaltyReceptionToken'
import { useLoyaltyMutation } from '~/composables/useLoyaltyMutation'

const api = { bootstrap: vi.fn(), selfAward: vi.fn() }
const errors = vi.fn()
const success = vi.fn()
const navigate = vi.fn()
for (const [name, value] of Object.entries({
  ref,
  shallowRef,
  onMounted,
  onUnmounted,
  definePageMeta: () => {},
  useHead: () => {},
  useI18n: () => ({ t: (key: string) => key }),
  useTeamLoyaltyApi: () => api,
  useErrorHandler: () => ({ handleApiError: errors }),
  useNotification: () => ({ success }),
  useLoyaltyMutation,
  navigateTo: navigate,
}))
  vi.stubGlobal(name, value)

function capture(token: string) {
  const stripped = vi.fn()
  captureLoyaltyReceptionToken(
    { pathname: '/my/team-points/receive', search: '', hash: `#locationToken=${token}` },
    { state: null, replaceState: stripped },
  )
  return stripped
}
function preview(teamId: number) {
  return {
    account: { teamId, balance: 0 },
    preview: {
      teamId,
      programVersion: 1,
      rule: { id: `rule-${teamId}`, version: 1, name: `Rule ${teamId}`, points: 1 },
      selfAwardMode: 'STAFF_APPROVAL',
      isEligibleForAward: true,
      isAlreadyAwardedToday: false,
    },
  }
}
const wrappers: ReturnType<typeof mount>[] = []
function mountPage() {
  const wrapper = mount(ReceivePage, {
    global: {
      stubs: {
        PageHeader: true,
        PageLoading: { template: '<p data-testid="loading" />' },
        DashboardErrorState: { template: '<p data-testid="error" />' },
        SectionCard: { template: '<section><slot /></section>' },
        LoyaltyAccountPanel: {
          props: ['account'],
          template: '<p data-testid="team">{{ account.teamId }}</p>',
        },
        LoyaltyRequestDetail: {
          props: ['request'],
          template: '<p data-testid="result">{{ request.id }}</p>',
        },
        Button: {
          props: ['label', 'disabled', 'loading'],
          emits: ['click'],
          template:
            '<button :disabled="disabled || loading" @click="$emit(\'click\')">{{ label }}</button>',
        },
        NuxtLink: true,
      },
    },
  })
  wrappers.push(wrapper)
  return wrapper
}
beforeEach(() => {
  vi.resetAllMocks()
  takeLoyaltyReceptionToken()
})
afterEach(() => {
  wrappers.splice(0).forEach((w) => w.unmount())
  takeLoyaltyReceptionToken()
})

describe('受付QR再入場の実ページ状態（AC17/20/31）', () => {
  it('初回tokenを一度だけ消費してbootstrapする', async () => {
    capture('FAKE-A')
    api.bootstrap.mockResolvedValue(preview(1))
    mountPage()
    await flushPromises()
    expect(api.bootstrap).toHaveBeenCalledExactlyOnceWith('FAKE-A')
    expect(takeLoyaltyReceptionToken()).toBeNull()
  })
  it('hash除去後に新tokenを一度だけ消費し、前のエラーを消す', async () => {
    capture('FAKE-A')
    api.bootstrap.mockRejectedValueOnce(new Error('ineligible'))
    const w = mountPage()
    await flushPromises()
    expect(w.find('[data-testid="error"]').exists()).toBe(true)
    let stripped = false
    api.bootstrap.mockImplementationOnce(() => {
      expect(stripped).toBe(true)
      return preview(2)
    })
    captureLoyaltyReceptionToken(
      { pathname: '/my/team-points/receive', search: '', hash: '#locationToken=FAKE-B' },
      {
        state: null,
        replaceState: () => {
          stripped = true
        },
      },
    )
    await flushPromises()
    expect(api.bootstrap).toHaveBeenCalledTimes(2)
    expect(api.bootstrap).toHaveBeenLastCalledWith('FAKE-B')
    expect(takeLoyaltyReceptionToken()).toBeNull()
    expect(w.find('[data-testid="error"]').exists()).toBe(false)
    expect(w.get('[data-testid="team"]').text()).toBe('2')
  })
  it('遅い旧TEAM応答が新TEAM previewを上書きせず、新tokenとpreviewで送信する', async () => {
    let resolveOld!: (value: ReturnType<typeof preview>) => void
    api.bootstrap.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveOld = resolve
        }),
    )
    capture('FAKE-A')
    const w = mountPage()
    await flushPromises()
    api.bootstrap.mockResolvedValueOnce(preview(2))
    capture('FAKE-B')
    await flushPromises()
    resolveOld(preview(1))
    await flushPromises()
    expect(w.get('[data-testid="team"]').text()).toBe('2')
    api.selfAward.mockResolvedValueOnce({ outcome: 'SUBMITTED', request: { id: 'request-B' } })
    await w.get('button').trigger('click')
    await flushPromises()
    expect(api.selfAward).toHaveBeenCalledWith(
      2,
      expect.objectContaining({ locationToken: 'FAKE-B', ruleId: 'rule-2' }),
      expect.any(String),
    )
  })
  it('新入口へ旧申請結果・進行中送信の通知や遷移を持ち越さない', async () => {
    capture('FAKE-A')
    api.bootstrap.mockResolvedValueOnce(preview(1))
    const w = mountPage()
    await flushPromises()
    let resolveWrite!: (value: unknown) => void
    api.selfAward.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveWrite = resolve
        }),
    )
    await w.get('button').trigger('click')
    api.bootstrap.mockResolvedValueOnce(preview(2))
    capture('FAKE-B')
    await flushPromises()
    resolveWrite({ outcome: 'AWARDED' })
    await flushPromises()
    expect(w.get('[data-testid="team"]').text()).toBe('2')
    expect(success).not.toHaveBeenCalled()
    expect(navigate).not.toHaveBeenCalled()
    expect(w.get('button').attributes('disabled')).toBeUndefined()
  })
  it('旧bootstrap失敗とunmount後の応答を画面やエラー報告へ反映しない', async () => {
    let rejectOld!: (error: Error) => void
    api.bootstrap.mockImplementationOnce(
      () =>
        new Promise((_, reject) => {
          rejectOld = reject
        }),
    )
    capture('FAKE-A')
    const w = mountPage()
    await flushPromises()
    api.bootstrap.mockResolvedValueOnce(preview(2))
    capture('FAKE-B')
    await flushPromises()
    rejectOld(new Error('old failure'))
    await flushPromises()
    expect(errors).not.toHaveBeenCalled()
    w.unmount()
    capture('FAKE-C')
    await flushPromises()
    expect(api.bootstrap).toHaveBeenCalledTimes(2)
  })
  it('表示済みの申請結果も次の受付入口へ持ち越さない', async () => {
    capture('FAKE-A')
    api.bootstrap.mockResolvedValueOnce(preview(1))
    const w = mountPage()
    await flushPromises()
    api.selfAward.mockResolvedValueOnce({ outcome: 'SUBMITTED', request: { id: 'request-A' } })
    await w.get('button').trigger('click')
    await flushPromises()
    expect(w.get('[data-testid="result"]').text()).toBe('request-A')
    api.bootstrap.mockResolvedValueOnce(preview(2))
    capture('FAKE-B')
    await flushPromises()
    expect(w.find('[data-testid="result"]').exists()).toBe(false)
    expect(w.get('[data-testid="team"]').text()).toBe('2')
  })
})
