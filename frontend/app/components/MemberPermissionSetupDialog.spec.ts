import { flushPromises } from '@vue/test-utils'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import MemberPermissionSetupDialog from './MemberPermissionSetupDialog.vue'

const apiMock = vi.fn()
const notificationMock = { success: vi.fn(), error: vi.fn() }

mockNuxtImport('useApi', () => () => apiMock)
mockNuxtImport('useNotification', () => () => notificationMock)

const initialResponse = {
  data: {
    permissions: [
      { name: 'MANAGE_SCHEDULES', enabled: false, inherited: true },
      { name: 'MANAGE_FILES', enabled: false, inherited: true },
      { name: 'MANAGE_POSTS', enabled: false, inherited: true },
    ],
  },
}

async function mountDialog(roleName = 'ADMIN') {
  const wrapper = await mountSuspended(MemberPermissionSetupDialog, {
    props: { scopeType: 'TEAM', scopeId: 12, slug: 'team-12', roleName },
    global: {
      stubs: {
        Dialog: true,
        Button: true,
        ToggleSwitch: true,
        NuxtLink: { template: '<a><slot /></a>' },
      },
    },
  })
  await flushPromises()
  return wrapper
}

function isVisible(wrapper: Awaited<ReturnType<typeof mountDialog>>): boolean {
  return (wrapper.vm as unknown as { visible: boolean }).visible
}

function dialogState(wrapper: Awaited<ReturnType<typeof mountDialog>>) {
  return wrapper.vm as unknown as {
    permissions: { name: string, enabled: boolean }[]
    save: () => Promise<void>
  }
}

describe('MemberPermissionSetupDialog', () => {
  beforeEach(() => {
    sessionStorage.clear()
    apiMock.mockReset()
    notificationMock.success.mockReset()
    notificationMock.error.mockReset()
  })

  it('未設定のチーム ADMIN に3権限を初期OFFで表示し、完全指定で保存する', async () => {
    apiMock.mockResolvedValueOnce(initialResponse).mockResolvedValueOnce({})
    const wrapper = await mountDialog()

    expect(apiMock).toHaveBeenCalledTimes(1)
    expect(isVisible(wrapper)).toBe(true)
    expect(dialogState(wrapper).permissions).toHaveLength(3)
    expect(dialogState(wrapper).permissions.every(permission => !permission.enabled)).toBe(true)

    dialogState(wrapper).permissions[0]!.enabled = true
    await dialogState(wrapper).save()
    await flushPromises()

    expect(apiMock).toHaveBeenNthCalledWith(2, '/api/v1/admin/member-permissions', {
      method: 'PUT',
      query: { scopeType: 'TEAM', scopeId: 12 },
      body: { permissions: [
        { name: 'MANAGE_SCHEDULES', enabled: true },
        { name: 'MANAGE_FILES', enabled: false },
        { name: 'MANAGE_POSTS', enabled: false },
      ] },
    })
    expect(isVisible(wrapper)).toBe(false)
  })

  it('MEMBERには設定APIもダイアログも開かない', async () => {
    const wrapper = await mountDialog('MEMBER')

    expect(apiMock).not.toHaveBeenCalled()
    expect(isVisible(wrapper)).toBe(false)
  })

  it('設定済みのスコープには再表示しない', async () => {
    apiMock.mockResolvedValueOnce({
      data: { permissions: initialResponse.data.permissions.map(permission => ({ ...permission, inherited: false })) },
    })
    const wrapper = await mountDialog()

    expect(isVisible(wrapper)).toBe(false)
  })
})

// CMP-261111-1429 ALIC-3: de の「Später entscheiden」が単語途中で折れない（フッターは折返し可・ボタンは改行しない）
describe('MemberPermissionSetupDialog フッターのボタン折返し', () => {
  it('フッターは flex-wrap、ボタンは shrink-0 whitespace-nowrap を持つ', async () => {
    apiMock.mockReset().mockResolvedValueOnce(initialResponse)
    const wrapper = await mountSuspended(MemberPermissionSetupDialog, {
      props: { scopeType: 'TEAM', scopeId: 12, slug: 'team-12', roleName: 'ADMIN' },
      global: {
        stubs: {
          Dialog: { template: '<div><slot /><slot name="footer" /></div>' },
          Button: { props: ['label'], template: '<button>{{ label }}</button>' },
          ToggleSwitch: true,
          NuxtLink: { template: '<a><slot /></a>' },
        },
      },
    })
    await flushPromises()
    const footer = wrapper.get('[data-testid="member-permission-setup-footer"]')
    expect(footer.classes()).toContain('flex-wrap')
    const buttons = footer.findAll('button')
    expect(buttons).toHaveLength(2)
    for (const button of buttons) {
      expect(button.classes()).toContain('shrink-0')
      expect(button.classes()).toContain('whitespace-nowrap')
    }
  })
})
