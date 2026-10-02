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
