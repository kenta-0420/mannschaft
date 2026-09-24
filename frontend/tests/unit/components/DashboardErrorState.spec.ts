import { describe, it, expect, beforeAll } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import DashboardErrorState from '~/components/DashboardErrorState.vue'

/**
 * DashboardErrorState.vue のユニットテスト（CMP-260922-2045）。
 *
 * 取得失敗を「未登録」の空状態（DashboardEmptyState）へフォールバックさせず、
 * 権限エラー・通信断であることが分かるエラー状態として描画するための共通部品。
 */

beforeAll(async () => {
  const warmup = await mountSuspended(DashboardErrorState)
  warmup.unmount()
})

describe('DashboardErrorState.vue', () => {
  it('message 未指定時は汎用の既定文言（i18n）を表示する', async () => {
    const wrapper = await mountSuspended(DashboardErrorState)
    // ロケールに依存せず「既定文言が出ている」ことだけを確認する
    // （テスト環境の既定ロケールは en。文言自体は6言語の common.json#loadErrorState.message）
    expect(wrapper.text()).toContain('Failed to load data')
  })

  it('message 指定時はそのメッセージを表示する', async () => {
    const wrapper = await mountSuspended(DashboardErrorState, {
      props: { message: 'カスタムエラーメッセージ' },
    })
    expect(wrapper.text()).toContain('カスタムエラーメッセージ')
    expect(wrapper.text()).not.toContain('Failed to load data')
  })

  it('既定では再試行ボタンを表示し、クリックで retry を emit する', async () => {
    const wrapper = await mountSuspended(DashboardErrorState, {
      props: { testid: 'my-error-state' },
    })
    const retryButton = wrapper.find('[data-testid="my-error-state-retry"]')
    expect(retryButton.exists()).toBe(true)

    await retryButton.trigger('click')
    expect(wrapper.emitted('retry')).toBeTruthy()
    expect(wrapper.emitted('retry')?.length).toBe(1)
  })

  it('showRetry=false で再試行ボタンを表示しない', async () => {
    const wrapper = await mountSuspended(DashboardErrorState, {
      props: { showRetry: false, testid: 'no-retry-error-state' },
    })
    expect(wrapper.find('[data-testid="no-retry-error-state-retry"]').exists()).toBe(false)
  })

  it('既定の data-testid は load-error-state', async () => {
    const wrapper = await mountSuspended(DashboardErrorState)
    expect(wrapper.find('[data-testid="load-error-state"]').exists()).toBe(true)
  })
})
