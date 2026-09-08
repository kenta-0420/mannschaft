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
  Badge: { props: ['value'], template: '<span data-testid="badge">{{ value }}</span>' },
  LoadingBounce: true,
  Button: {
    props: ['label', 'loading', 'disabled'],
    emits: ['click'],
    template: '<button :disabled="disabled || loading" @click="$emit(\'click\')">{{ label }}</button>',
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

const baseProps = {
  requests: [] as JoinRequestResponse[],
  processingIds: [] as string[],
  loading: false,
  error: false,
  totalElements: 0,
  hasMore: false,
}

describe('JoinRequestList', () => {
  it('件数 0 のとき空状態を表示する', async () => {
    const wrapper = await mountSuspended(JoinRequestList, {
      props: { ...baseProps },
      global: { stubs },
    })
    expect(wrapper.text()).toContain('承認待ちの参加申請はありません')
  })

  it('申請一覧を行として描画し、承認/却下ボタンで各 emit を発火する', async () => {
    const requests = [makeRequest(), makeRequest({ id: 'req-2', requesterUserId: 1000, message: null })]
    const wrapper = await mountSuspended(JoinRequestList, {
      props: { ...baseProps, requests, totalElements: requests.length },
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
      props: { ...baseProps, loading: true },
      global: { stubs },
    })
    expect(wrapper.html().toLowerCase()).toContain('loading-bounce')
  })

  // Codex 検分 CMP-260901-1538 第1巡 P1-2 是正: バッジは totalElements（全件数）を使う
  it('バッジは requests.length ではなく totalElements を表示する', async () => {
    const requests = [makeRequest()]
    const wrapper = await mountSuspended(JoinRequestList, {
      props: { ...baseProps, requests, totalElements: 45 },
      global: { stubs },
    })
    expect(wrapper.find('[data-testid="badge"]').text()).toBe('45')
  })

  // Codex 検分 CMP-260901-1538 第1巡 P1-2 是正: 追加読み込み
  it('hasMore=true のときは「さらに読み込む」ボタンが出て loadMore を発火する', async () => {
    const requests = [makeRequest()]
    const wrapper = await mountSuspended(JoinRequestList, {
      props: { ...baseProps, requests, totalElements: 21, hasMore: true },
      global: { stubs },
    })
    const loadMoreButton = wrapper.find('[data-testid="join-request-load-more-button"]')
    expect(loadMoreButton.exists()).toBe(true)

    await loadMoreButton.trigger('click')
    expect(wrapper.emitted('loadMore')).toHaveLength(1)
  })

  it('hasMore=false のときは「さらに読み込む」ボタンが出ない', async () => {
    const requests = [makeRequest()]
    const wrapper = await mountSuspended(JoinRequestList, {
      props: { ...baseProps, requests, totalElements: 1, hasMore: false },
      global: { stubs },
    })
    expect(wrapper.find('[data-testid="join-request-load-more-button"]').exists()).toBe(false)
  })

  // Codex 検分 CMP-260901-1538 第1巡 P1-3 是正: 取得失敗時に空状態を表示しない
  it('取得失敗（件数0）のときは空状態ではなくエラー表示＋再試行ボタンを出す', async () => {
    const wrapper = await mountSuspended(JoinRequestList, {
      props: { ...baseProps, error: true },
      global: { stubs },
    })
    expect(wrapper.text()).not.toContain('承認待ちの参加申請はありません')
    const errorBox = wrapper.find('[data-testid="join-request-list-error"]')
    expect(errorBox.exists()).toBe(true)

    const retryButton = wrapper.find('[data-testid="join-request-list-retry-button"]')
    await retryButton.trigger('click')
    expect(wrapper.emitted('retry')).toHaveLength(1)
  })

  it('追加読み込み失敗時（件数>0）は既存行を残したまま再試行を提示する', async () => {
    const requests = [makeRequest()]
    const wrapper = await mountSuspended(JoinRequestList, {
      props: { ...baseProps, requests, totalElements: 21, hasMore: true, error: true },
      global: { stubs },
    })
    // 既存行は消えない
    expect(wrapper.findAll('[data-testid="join-request-row"]')).toHaveLength(1)
    // 追加読み込みボタンではなく再試行導線が出る
    expect(wrapper.find('[data-testid="join-request-load-more-button"]').exists()).toBe(false)
    const retryButton = wrapper.find('[data-testid="join-request-load-more-retry-button"]')
    expect(retryButton.exists()).toBe(true)

    await retryButton.trigger('click')
    expect(wrapper.emitted('retry')).toHaveLength(1)
  })

  // P2 是正: 処理中は承認・却下の両ボタンを無効化する
  it('processingIds に含まれる行は承認・却下の両ボタンが無効化される', async () => {
    const requests = [makeRequest({ id: 'req-1' })]
    const wrapper = await mountSuspended(JoinRequestList, {
      props: { ...baseProps, requests, totalElements: 1, processingIds: ['req-1'] },
      global: { stubs },
    })
    const buttons = wrapper.findAll('[data-testid="join-request-row"] button')
    expect(buttons).toHaveLength(2)
    for (const button of buttons) {
      expect(button.attributes('disabled')).toBeDefined()
    }
  })
})
