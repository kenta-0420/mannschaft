import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import JoinRequestManagementPanel from './JoinRequestManagementPanel.vue'

/**
 * `JoinRequestManagementPanel` ＋ `useJoinRequestManagement` ＋ `JoinRequestList` の統合テスト
 * （Codex 検分 CMP-260901-1538 第1巡 P1-5 是正: コア機構が一件も検証されていなかった）。
 */

const mockApi = vi.fn()
vi.mock('~/composables/useApi', () => ({
  useApi: () => mockApi,
}))

mockNuxtImport('useI18n', () => () => ({
  t: (key: string, params?: Record<string, unknown>) =>
    params ? `${key}:${JSON.stringify(params)}` : key,
}))
mockNuxtImport('useDatetime', () => () => ({
  formatDate: (v: string) => v,
  formatDateTime: (v: string) => v,
}))
mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), error: vi.fn() }))

const stubs = {
  Badge: { props: ['value'], template: '<span data-testid="badge">{{ value }}</span>' },
  LoadingBounce: true,
  Button: {
    props: ['label', 'loading', 'disabled'],
    emits: ['click'],
    template: '<button :disabled="disabled || loading" @click="$emit(\'click\')">{{ label }}</button>',
  },
}

function makeRequest(id: string) {
  return { id, scopeType: 'TEAM' as const, scopeId: 12, requesterUserId: 1, message: null, status: 'PENDING' as const, reviewerUserId: null, reviewedAt: null, reviewComment: null, createdAt: '2026-09-01T00:00:00Z' }
}

describe('JoinRequestManagementPanel（子ルート実描画）', () => {
  beforeEach(() => {
    mockApi.mockReset()
  })

  it('マウント時に一覧を取得し、バッジに totalElements を表示する', async () => {
    mockApi.mockResolvedValueOnce({
      data: { content: Array.from({ length: 20 }, (_, i) => makeRequest(`req-${i}`)), totalElements: 21, totalPages: 2, number: 0, size: 20 },
    })

    const wrapper = await mountSuspended(JoinRequestManagementPanel, {
      props: { scopeType: 'team', scopeId: 12 },
      global: { stubs },
    })
    await new Promise(resolve => setTimeout(resolve, 0))

    expect(mockApi).toHaveBeenCalledWith('/api/v1/teams/12/join-requests?status=PENDING&page=0&size=20')
    expect(wrapper.find('[data-testid="badge"]').text()).toBe('21')
    expect(wrapper.findAll('[data-testid="join-request-row"]')).toHaveLength(20)
    expect(wrapper.find('[data-testid="join-request-load-more-button"]').exists()).toBe(true)
  })

  it('取得失敗時は空状態を表示せずエラー表示になる', async () => {
    mockApi.mockRejectedValueOnce(new Error('boom'))

    const wrapper = await mountSuspended(JoinRequestManagementPanel, {
      props: { scopeType: 'organization', scopeId: 7 },
      global: { stubs },
    })
    await new Promise(resolve => setTimeout(resolve, 0))

    expect(wrapper.text()).not.toContain('承認待ちの参加申請はありません')
    expect(wrapper.find('[data-testid="join-request-list-error"]').exists()).toBe(true)
  })
})
