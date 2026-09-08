import { describe, expect, it } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import type { JoinRequestResponse } from '~/composables/useJoinRequestApi'
import JoinRequestList from './JoinRequestList.vue'

mockNuxtImport('useI18n', () => () => ({
  t: (key: string, params?: Record<string, unknown>) =>
    params ? `${key}:${JSON.stringify(params)}` : key,
}))
mockNuxtImport('useDatetime', () => () => ({
  formatDate: (v: string) => v,
  formatDateTime: (v: string) => v,
}))

const stubs = {
  Badge: true,
  LoadingBounce: true,
  Button: {
    props: ['label', 'loading'],
    emits: ['click'],
    template: '<button :disabled="loading" @click="$emit(\'click\')">{{ label }}</button>',
  },
}

function makeRequest(overrides: Partial<JoinRequestResponse> = {}): JoinRequestResponse {
  return {
    id: 'req-1',
    scopeType: 'TEAM',
    scopeId: 12,
    requesterUserId: 999,
    message: 'よろしくお願いします',
    status: 'PENDING',
    reviewerUserId: null,
    reviewedAt: null,
    reviewComment: null,
    createdAt: '2026-09-01T00:00:00Z',
    ...overrides,
  }
}

describe('JoinRequestList', () => {
  it('件数 0 のとき空状態を表示する', async () => {
    const wrapper = await mountSuspended(JoinRequestList, {
      props: { requests: [], processingIds: [], loading: false },
      global: { stubs },
    })
    expect(wrapper.text()).toContain('承認待ちの参加申請はありません')
  })

  it('申請一覧を行として描画し、承認/却下ボタンで各 emit を発火する', async () => {
    const requests = [makeRequest(), makeRequest({ id: 'req-2', requesterUserId: 1000, message: null })]
    const wrapper = await mountSuspended(JoinRequestList, {
      props: { requests, processingIds: [], loading: false },
      global: { stubs },
    })

    const rows = wrapper.findAll('[data-testid="join-request-row"]')
    expect(rows).toHaveLength(2)

    const buttons = wrapper.findAll('button')
    // 1件目: 承認, 却下
    await buttons[0]!.trigger('click')
    expect(wrapper.emitted('approve')).toEqual([['req-1']])

    await buttons[1]!.trigger('click')
    expect(wrapper.emitted('reject')).toEqual([['req-1']])
  })

  it('loading 中はローディング表示を出す', async () => {
    const wrapper = await mountSuspended(JoinRequestList, {
      props: { requests: [], processingIds: [], loading: true },
      global: { stubs },
    })
    expect(wrapper.html().toLowerCase()).toContain('loading-bounce')
  })
})
