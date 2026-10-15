import { describe, it, expect, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import ScheduleCommentForm from '~/components/schedule/comments/ScheduleCommentForm.vue'

/**
 * F03.16 予定コメントスレッド — ScheduleCommentForm.vue ユニットテスト。
 *
 * 検証観点:
 *   SCF-001: 本文が空のときは投稿ボタンが disabled
 *   SCF-002: 本文を入力し投稿すると submit イベントが body と mentionedUserIds([]) 付きで発火する
 *   SCF-003: 2000文字超のときエラーメッセージを表示し投稿ボタンが disabled
 */

mockNuxtImport('useI18n', () => () => ({
  t: (key: string, params?: Record<string, unknown>) =>
    params ? `${key}:${JSON.stringify(params)}` : key,
}))
mockNuxtImport('useScheduleComments', () => () => ({
  mentionCandidates: vi.fn().mockResolvedValue({ data: [] }),
}))

describe('ScheduleCommentForm.vue', () => {
  it('SCF-001: 本文が空のときは投稿ボタンが disabled', async () => {
    const wrapper = await mountSuspended(ScheduleCommentForm, {
      props: { scheduleId: 1, submitting: false },
    })
    // PrimeVue Button はルート要素自体が <button> になる
    const btn = wrapper.find('[data-testid="schedule-comment-submit"]')
    expect(btn.attributes('disabled')).toBeDefined()
  })

  it('SCF-002: 本文を入力し投稿すると submit イベントが発火する', async () => {
    const wrapper = await mountSuspended(ScheduleCommentForm, {
      props: { scheduleId: 1, submitting: false },
    })
    const ta = wrapper.get('textarea')
    await ta.setValue('集合場所は駅前でよいですか？')
    await wrapper.get('[data-testid="schedule-comment-submit"]').trigger('click')

    expect(wrapper.emitted('submit')).toBeTruthy()
    expect(wrapper.emitted('submit')![0]).toEqual(['集合場所は駅前でよいですか？', []])
  })

  it('SCF-003: 2000文字超のときエラーを表示し投稿ボタンが disabled', async () => {
    const wrapper = await mountSuspended(ScheduleCommentForm, {
      props: { scheduleId: 1, submitting: false },
    })
    const ta = wrapper.get('textarea')
    await ta.setValue('あ'.repeat(2001))

    expect(wrapper.find('[data-testid="schedule-comment-error-too-long"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="schedule-comment-submit"]').attributes('disabled')).toBeDefined()
  })
})
