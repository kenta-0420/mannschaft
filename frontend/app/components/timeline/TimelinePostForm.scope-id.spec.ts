import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import Form from './TimelinePostForm.vue'

const mocks = vi.hoisted(() => ({ api: vi.fn() }))
mockNuxtImport('useApi', () => () => mocks.api)
mockNuxtImport('useAuthStore', () => () => ({ user: { id: 1 }, loadFromStorage: vi.fn() }))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key, te: () => true }))
mockNuxtImport('useNotification', () => () => ({ showSuccess: vi.fn(), showError: vi.fn() }))
const stubs = { VideoUploadInput: true, TimelinePollForm: true, AnnouncementToggle: true }
beforeEach(() => {
  mocks.api.mockReset().mockImplementation(async (url: string) => url.endsWith('/permissions')
    ? { data: { roleName: 'ADMIN', permissions: [] } }
    : { data: { id: 17 } })
})

describe('投稿フォームのscopeIDと権限slug', () => {
  it('FEED-SCOPE-7: 組織権限は公開slug、投稿と添付先は内部IDを使う', async () => {
    const view = await mountSuspended(Form, {
      props: { scopeType: 'ORGANIZATION', scopeId: 7, scopeSlug: '12345' },
      global: { stubs },
    })
    await flushPromises()
    expect(mocks.api).toHaveBeenCalledWith('/api/v1/organizations/12345/me/permissions')
    expect(view.find('[data-testid="timeline-delivery-scope"]').exists()).toBe(true)
    expect(view.findComponent({ name: 'VideoUploadInput' }).props('scopeId')).toBe(7)
    await view.get('textarea').setValue('所属先への投稿')
    await view.get('[data-testid="team-timeline-submit"]').trigger('click')
    expect(mocks.api).toHaveBeenCalledWith('/api/v1/timeline/posts', {
      method: 'POST', body: { scopeType: 'ORGANIZATION', scopeId: 7, content: '所属先への投稿' },
    })
  })

})
