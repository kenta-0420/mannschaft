import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import TransitionAlertBanner from './TransitionAlertBanner.vue'
import TransitionAlertResolveModal from './TransitionAlertResolveModal.vue'
import type { TransitionAlertResponse } from '~/types/school'

const { resolveAlert } = vi.hoisted(() => ({ resolveAlert: vi.fn() }))
mockNuxtImport('useTransitionAlertApi', () => () => ({ resolveAlert }))
mockNuxtImport('useNotification', () => () => ({ error: vi.fn(), success: vi.fn() }))
const alert: TransitionAlertResponse = { id: 7, teamId: 42, studentUserId: 8,
  attendanceDate: '2026-11-10', previousPeriodNumber: 1, currentPeriodNumber: 2,
  previousPeriodStatus: 'ATTENDING', currentPeriodStatus: 'ABSENT', alertLevel: 'NORMAL',
  resolved: false, createdAt: '2026-11-10T09:00:00' }
const modalStub = { name: 'TransitionAlertResolveModal', props: ['canResolve'], template: '<div />' }
beforeEach(() => resolveAlert.mockReset())

describe('移動検知アラートの解決資格', () => {
  it.each([undefined, false])('資格%sではアラートを読めても解決ボタン・モーダルを出さない', async canResolve => {
    const wrapper = await mountSuspended(TransitionAlertBanner, {
      props: { alerts: [alert], teamId: '42', canResolve },
      global: { stubs: { TransitionAlertResolveModal: modalStub } },
    })
    expect(wrapper.find('[data-testid="transition-alert-item-7"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="transition-alert-resolve-7"]').exists()).toBe(false)
    await (wrapper.vm as unknown as { openResolveModal: (value: TransitionAlertResponse) => void }).openResolveModal(alert)
    expect(wrapper.findComponent(modalStub).exists()).toBe(false)
  })

  it('資格trueで開き、権限取消またはteam変更で旧モーダルを破棄する', async () => {
    const wrapper = await mountSuspended(TransitionAlertBanner, {
      props: { alerts: [alert], teamId: '42', canResolve: true },
      global: { stubs: { TransitionAlertResolveModal: modalStub } },
    })
    await wrapper.get('[data-testid="transition-alert-resolve-7"]').trigger('click')
    expect(wrapper.findComponent(modalStub).props('canResolve')).toBe(true)
    await wrapper.setProps({ canResolve: false })
    expect(wrapper.findComponent(modalStub).exists()).toBe(false)
    await wrapper.setProps({ canResolve: true })
    await wrapper.get('[data-testid="transition-alert-resolve-7"]').trigger('click')
    await wrapper.setProps({ teamId: '99' })
    expect(wrapper.findComponent(modalStub).exists()).toBe(false)
    ;(wrapper.vm as unknown as { onResolved: (id: number) => void }).onResolved(7)
    expect(wrapper.emitted('resolved')).toBeUndefined()
  })

  it.each([undefined, false])('submit handlerも資格%sではAPIを呼ばない', async canResolve => {
    const wrapper = await mountSuspended(TransitionAlertResolveModal, {
      props: { alert, teamId: '42', visible: true, canResolve }, global: { stubs: { Dialog: true } },
    })
    const vm = wrapper.vm as unknown as { note: string; onConfirm: () => Promise<void> }
    vm.note = 'confirmed'
    await vm.onConfirm()
    expect(resolveAlert).not.toHaveBeenCalled()
  })

  it('有資格のsubmitは同teamへ一度送り、処理中の二重submitを抑止する', async () => {
    let finish!: () => void
    resolveAlert.mockReturnValueOnce(new Promise<void>(resolve => { finish = resolve }))
    const wrapper = await mountSuspended(TransitionAlertResolveModal, {
      props: { alert, teamId: '42', visible: true, canResolve: true }, global: { stubs: { Dialog: true } },
    })
    const vm = wrapper.vm as unknown as { note: string; submitting: boolean; onConfirm: () => Promise<void> }
    vm.note = 'confirmed'
    const pending = vm.onConfirm()
    expect(vm.submitting).toBe(true)
    await vm.onConfirm()
    expect(resolveAlert).toHaveBeenCalledExactlyOnceWith('42', 7, 'confirmed')
    finish()
    await pending
    expect(vm.submitting).toBe(false)
    expect(wrapper.emitted('resolved')).toEqual([[7]])
    await wrapper.setProps({ canResolve: false })
    vm.note = 'not permitted'
    await vm.onConfirm()
    expect(resolveAlert).toHaveBeenCalledTimes(1)
  })

  it('送信後に資格が取り消された場合は古い成功をemitしない', async () => {
    let finish!: () => void
    resolveAlert.mockReturnValueOnce(new Promise<void>(resolve => { finish = resolve }))
    const wrapper = await mountSuspended(TransitionAlertResolveModal, {
      props: { alert, teamId: '42', visible: true, canResolve: true }, global: { stubs: { Dialog: true } },
    })
    const vm = wrapper.vm as unknown as { note: string; onConfirm: () => Promise<void> }
    vm.note = 'confirmed'
    const pending = vm.onConfirm()
    await wrapper.setProps({ canResolve: false })
    finish()
    await pending
    expect(wrapper.emitted('resolved')).toBeUndefined()
    expect(wrapper.emitted('update:visible')).toContainEqual([false])
  })


  it('旧モーダル破棄後の成功を新しい画面へemitしない', async () => {
    let finish!: () => void
    resolveAlert.mockReturnValueOnce(new Promise<void>(resolve => { finish = resolve }))
    const wrapper = await mountSuspended(TransitionAlertResolveModal, {
      props: { alert, teamId: '42', visible: true, canResolve: true }, global: { stubs: { Dialog: true } },
    })
    const vm = wrapper.vm as unknown as { note: string; onConfirm: () => Promise<void> }
    vm.note = 'confirmed'
    const pending = vm.onConfirm()
    wrapper.unmount()
    finish()
    await pending
    expect(wrapper.emitted('resolved')).toBeUndefined()
  })

})
