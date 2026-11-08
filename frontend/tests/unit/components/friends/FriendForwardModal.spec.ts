/**
 * CMP-261014-2034 アリシゼーション指摘: 転送モーダルの送信連打。
 *
 * - 再描画前の連打で POST /forward が複数回送られ、409 のエラートーストが積み上がっていた。
 * - 409（既に転送済み）は汎用エラーではなく専用文言で伝え、親に再取得させる。
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import FriendForwardModal from '~/components/friends/FriendForwardModal.vue'

const forwardMock = vi.fn()
const handleApiError = vi.fn()
const notify = { success: vi.fn(), warn: vi.fn(), error: vi.fn(), info: vi.fn() }

mockNuxtImport('useFriendForwardApi', () => () => ({ forward: forwardMock }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError }))
mockNuxtImport('useNotification', () => () => notify)

interface ModalVm {
  onSubmit: () => Promise<void>
}

async function mountModal() {
  return mountSuspended(FriendForwardModal, {
    props: { modelValue: true, teamId: 't1', postId: 5, sourceTeamName: 'Friend FC' },
  })
}

beforeEach(() => {
  forwardMock.mockReset()
  handleApiError.mockReset()
  Object.values(notify).forEach(fn => fn.mockReset())
})

describe('FriendForwardModal 送信', () => {
  it('再描画前に連打しても forward は1回しか呼ばれない', async () => {
    let resolve: (v: { forwardId: number }) => void = () => {}
    forwardMock.mockReturnValue(new Promise((r) => { resolve = r }))
    const wrapper = await mountModal()
    const vm = wrapper.vm as unknown as ModalVm
    const calls = Array.from({ length: 8 }, () => vm.onSubmit())
    resolve({ forwardId: 1 })
    await Promise.all(calls)
    expect(forwardMock).toHaveBeenCalledTimes(1)
    expect(wrapper.emitted('success')).toHaveLength(1)
  })

  it('409（既に転送済み）は汎用エラーにせず専用文言を1回だけ出し、親へ通知する', async () => {
    forwardMock.mockRejectedValue({ statusCode: 409 })
    const wrapper = await mountModal()
    const vm = wrapper.vm as unknown as ModalVm
    await Promise.all(Array.from({ length: 8 }, () => vm.onSubmit()))
    expect(forwardMock).toHaveBeenCalledTimes(1)
    expect(handleApiError).not.toHaveBeenCalled()
    const shown = [...notify.warn.mock.calls, ...notify.error.mock.calls, ...notify.info.mock.calls]
    expect(shown).toHaveLength(1)
    expect(String(shown[0]![0])).not.toBe('friend_feed.forward.already_forwarded')
    expect(wrapper.emitted('alreadyForwarded')).toHaveLength(1)
  })

  it('409 以外の失敗は従来どおり handleApiError に渡す', async () => {
    forwardMock.mockRejectedValue({ statusCode: 500 })
    const wrapper = await mountModal()
    await (wrapper.vm as unknown as ModalVm).onSubmit()
    expect(handleApiError).toHaveBeenCalledTimes(1)
    expect(wrapper.emitted('alreadyForwarded')).toBeUndefined()
  })
})
