// @vitest-environment happy-dom
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import BroadcastWizard from '~/components/announcement/BroadcastWizard.vue'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import PrimeVue from 'primevue/config'
import RadioButton from 'primevue/radiobutton'
import Textarea from 'primevue/textarea'
import DatePicker from 'primevue/datepicker'
import BroadcastGuideContent from '~/components/announcement/BroadcastGuideContent.vue'
import BroadcastGuideModal from '~/components/announcement/BroadcastGuideModal.vue'
import BroadcastStep1Audience from '~/components/announcement/BroadcastStep1Audience.vue'
import BroadcastStep2Channel from '~/components/announcement/BroadcastStep2Channel.vue'
import BroadcastStep3Content from '~/components/announcement/BroadcastStep3Content.vue'
import BroadcastTemplateSelector from '~/components/announcement/BroadcastTemplateSelector.vue'
import type { AnnouncementTemplate, BroadcastChannel } from '~/types/announcement_broadcast'

// 全アプリのbootstrapを避け、外部境界だけ置き換えて実際のウィザードとDialogを検証する。
vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (key: string) => key, tm: () => ({ step1: '', step2: '' }) }),
}))
vi.mock('~/composables/useApi', () => ({ useApi: () => vi.fn() }))
vi.mock('~/composables/useOrganizationApi', () => ({
  useOrganizationApi: () => ({ getTeamsInOrg: vi.fn() }),
}))
vi.mock('~/composables/useBroadcastAudience', () => ({
  useBroadcastAudience: () => ({ fetchTeamGroups: vi.fn(), previewAudience: vi.fn() }),
}))
vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({ success: vi.fn(), error: vi.fn() }),
}))
vi.mock('~/composables/useDatetime', () => ({
  useDatetime: () => ({ buildOffsetDateTimeStr: () => null }),
}))

let wrapper: WizardWrapper

beforeEach(async () => {
  setActivePinia(createPinia())
  wrapper = await mountWizard()
})

async function mountWizard() {
  const wrapper = mount(BroadcastWizard, {
    props: { visible: true, scopeType: 'TEAM', scopeId: '1' },
    attachTo: document.body,
    global: {
      plugins: [[PrimeVue, { unstyled: true }]],
      components: {
        Dialog,
        Button,
        RadioButton,
        Textarea,
        DatePicker,
        BroadcastStep1Audience,
        BroadcastStep2Channel,
        BroadcastStep3Content,
        BroadcastGuideContent,
        BroadcastGuideModal,
      },
      stubs: { BroadcastTemplateSelector: true, transition: false },
      mocks: { $t: (key: string) => key },
    },
  })
  return wrapper
}

type WizardWrapper = Awaited<ReturnType<typeof mountWizard>>

async function applyTemplate(wrapper: WizardWrapper, preferredChannel: BroadcastChannel | null) {
  const template: AnnouncementTemplate = {
    id: 7,
    name: '公開告知',
    targetRole: 'PUBLIC',
    targetTeamIds: null,
    preferredChannel,
    isDefault: false,
    createdBy: null,
    createdAt: '2026-10-18T12:00:00',
  }
  wrapper.findComponent(BroadcastTemplateSelector).vm.$emit('apply', template)
  await flushPromises()
}

async function nextFromAudience(wrapper: WizardWrapper) {
  await wrapper.findComponent(BroadcastStep1Audience).findComponent(Button).trigger('click')
}

async function openGuide(wrapper: WizardWrapper) {
  const toggle = wrapper
    .findAllComponents(Button)
    .find((button) => button.attributes('data-testid') === 'broadcast-guide-toggle')
  await toggle!.trigger('click')
  await flushPromises()
  const guide = wrapper.findComponent(BroadcastGuideModal).findComponent(Dialog)
  expect(guide.props('visible')).toBe(true)
  expect(guide.props('modal')).toBe(true)
  return guide
}

describe('BroadcastWizard ガイドモーダルと手順確認', () => {
  it('テンプレートの推奨チャネルを手順2で確認・変更し、往復しても選択を維持する', async () => {
    await applyTemplate(wrapper, 'BULLETIN_THREAD')

    await nextFromAudience(wrapper)

    const channelStep = wrapper.findComponent(BroadcastStep2Channel)
    expect(channelStep.exists()).toBe(true)
    expect(channelStep.props('modelValue')).toMatchObject({
      step: 2,
      selectedChannel: 'BULLETIN_THREAD',
      targetRole: 'PUBLIC',
      templateId: 7,
    })
    expect(channelStep.findAll('button')[0]!.classes()).toContain('border-primary-500')
    await channelStep.findAll('button')[1]!.trigger('click')
    await channelStep.findAll('button').at(-1)!.trigger('click')
    expect(wrapper.findComponent(BroadcastStep3Content).props('modelValue').selectedChannel).toBe(
      'TIMELINE_POST',
    )

    await wrapper.findComponent(BroadcastStep3Content).findAll('button').at(-2)!.trigger('click')
    await wrapper.findComponent(BroadcastStep2Channel).findAll('button').at(-2)!.trigger('click')
    await nextFromAudience(wrapper)
    expect(wrapper.findComponent(BroadcastStep2Channel).props('modelValue')).toMatchObject({
      step: 2,
      selectedChannel: 'TIMELINE_POST',
      templateId: 7,
    })
  })

  it.each([false, true])(
    'テンプレート利用=%sでも未選択・推奨nullなら手順2の次へは選択まで無効',
    async (useTemplate) => {
      if (useTemplate) await applyTemplate(wrapper, null)
      await nextFromAudience(wrapper)
      const channelStep = wrapper.findComponent(BroadcastStep2Channel)
      const next = channelStep.findAll('button').at(-1)!
      expect(next.attributes('disabled')).toBeDefined()

      await channelStep.findAll('button')[1]!.trigger('click')

      expect(next.attributes('disabled')).toBeUndefined()
      await next.trigger('click')
      expect(wrapper.findComponent(BroadcastStep3Content).exists()).toBe(true)
    },
  )

  it('全3手順でガイドを開閉でき、対象範囲・チャネル・入力を維持する', async () => {
    await applyTemplate(wrapper, 'TIMELINE_POST')
    const firstGuide = await openGuide(wrapper)
    await firstGuide
      .findAllComponents(Button)
      .find((button) => button.props('label') === 'button.close')!
      .trigger('click')
    expect(firstGuide.props('visible')).toBe(false)
    await nextFromAudience(wrapper)
    const secondGuide = await openGuide(wrapper)
    await secondGuide
      .findAllComponents(Button)
      .find((button) => button.props('label') === 'button.close')!
      .trigger('click')
    expect(secondGuide.props('visible')).toBe(false)
    const channelStep = wrapper.findComponent(BroadcastStep2Channel)
    expect(channelStep.props('modelValue')).toMatchObject({
      step: 2,
      targetRole: 'PUBLIC',
      selectedChannel: 'TIMELINE_POST',
    })
    await channelStep.findAll('button').at(-1)!.trigger('click')
    await wrapper
      .findComponent(BroadcastStep3Content)
      .get('textarea')
      .setValue('入力を維持する本文')

    const thirdGuide = await openGuide(wrapper)
    await thirdGuide
      .findAllComponents(Button)
      .find((button) => button.props('label') === 'button.close')!
      .trigger('click')
    expect(thirdGuide.props('visible')).toBe(false)

    expect(wrapper.findComponent(BroadcastStep3Content).props('modelValue')).toMatchObject({
      step: 3,
      content: { body: '入力を維持する本文' },
    })
    expect(wrapper.findComponent(BroadcastStep3Content).get('textarea').element.value).toBe(
      '入力を維持する本文',
    )
  })

  it('Escはガイドだけを閉じ、親を閉じて再表示するとガイドは非表示になる', async () => {
    const guide = await openGuide(wrapper)

    document.dispatchEvent(
      new KeyboardEvent('keydown', { code: 'Escape', key: 'Escape', bubbles: true }),
    )
    await flushPromises()

    expect(guide.props('visible')).toBe(false)
    expect(wrapper.emitted('update:visible')).toBeUndefined()
    expect(wrapper.findComponent(BroadcastStep1Audience).exists()).toBe(true)
    await openGuide(wrapper)
    await wrapper.setProps({ visible: false })
    await wrapper.setProps({ visible: true })
    expect(guide.props('visible')).toBe(false)
  })
})
