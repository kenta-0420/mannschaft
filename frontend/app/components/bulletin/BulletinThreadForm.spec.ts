// @vitest-environment happy-dom
import { beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import BulletinThreadForm from './BulletinThreadForm.vue'

const createThread = vi.hoisted(() => vi.fn())
mockNuxtImport('useBulletinApi', () => () => ({ createThread, getCategories: vi.fn().mockResolvedValue({ data: [] }) }))
mockNuxtImport('useAnnouncementFeed', () => () => ({ createAnnouncement: vi.fn() }))
mockNuxtImport('useNotification', () => () => ({ showError: vi.fn(), showSuccess: vi.fn() }))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))

beforeEach(() => { localStorage.clear(); createThread.mockReset() })

it('既存契約: 投稿失敗時はダイアログ・入力・下書きを保持しsavedを通知しない', async () => {
  createThread.mockRejectedValue(new Error('offline'))
  const wrapper = mount(BulletinThreadForm, {
    props: { visible: true, scopeType: 'TEAM', scopeId: 1 },
    global: { mocks: { $t: (key: string) => key }, stubs: {
      Dialog: { props: ['visible'], template: '<div v-if="visible" role="dialog"><slot /><slot name="footer" /></div>' },
      InputText: { props: ['modelValue'], emits: ['update:modelValue'], template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
      Textarea: { props: ['modelValue'], emits: ['update:modelValue'], template: '<textarea :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
      Button: { props: ['label', 'disabled', 'loading'], emits: ['click'], template: '<button :disabled="disabled || loading" @click="$emit(\'click\')">{{ label }}</button>' },
      Select: true, AnnouncementToggle: true,
    } },
  })
  await wrapper.get<HTMLInputElement>('input[placeholder="スレッドのタイトル"]').setValue('保持するタイトル')
  await wrapper.get('textarea').setValue('保持する本文')
  await wrapper.findAll('button').find(button => button.text() === '投稿')!.trigger('click')
  await flushPromises()

  expect(createThread).toHaveBeenCalledOnce()
  expect(wrapper.find('[role="dialog"]').exists()).toBe(true)
  expect(wrapper.get<HTMLInputElement>('input[placeholder="スレッドのタイトル"]').element.value).toBe('保持するタイトル')
  expect(wrapper.get('textarea').element.value).toBe('保持する本文')
  expect(JSON.parse(localStorage.getItem('bulletin-draft-TEAM-1')!)).toMatchObject({ title: '保持するタイトル', body: '保持する本文' })
  expect(wrapper.emitted('saved')).toBeUndefined()
})
