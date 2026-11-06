import { describe, expect, it } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import MemberCardGuideModal from './MemberCardGuideModal.vue'

describe('MemberCardGuideModal', () => {
  it('閉じる操作でvisibleを更新する', async () => {
    const wrapper = await mountSuspended(MemberCardGuideModal, {
      props: { visible: true, team: true },
      global: { stubs: {
        Dialog: { template: '<div><slot /><slot name="footer" /></div>' },
        Button: { emits: ['click'], template: '<button @click="$emit(\'click\')">close</button>' },
        MemberCardGuideContent: true,
      } },
    })
    expect(wrapper.find('[data-testid="member-card-guide-modal"]').exists()).toBe(true)
    await wrapper.find('button').trigger('click')
    expect(wrapper.emitted('update:visible')).toEqual([[false]])
  })
})
