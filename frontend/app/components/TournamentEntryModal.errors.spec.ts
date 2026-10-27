// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { computed, defineComponent, ref, watch } from 'vue'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import Modal from './TournamentEntryModal.vue'

const mocks = vi.hoisted(() => ({
  getEntryMembers: vi.fn(),
  getEntryTemplates: vi.fn(),
  applyEntryTemplate: vi.fn(),
  upsertEntryMembers: vi.fn(),
  loadEntryMembersFromTeam: vi.fn(),
  downloadEntryPdf: vi.fn(),
  handleApiError: vi.fn(),
  toastAdd: vi.fn(),
}))
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key }) }))
vi.mock('~/composables/useTournamentApi', () => ({ useTournamentApi: () => mocks }))
vi.mock('~/composables/useErrorHandler', () => ({ useErrorHandler: () => mocks }))
mockNuxtImport('useNuxtApp', () => () => ({ $toast: { add: mocks.toastAdd } }))

const Button = defineComponent({
  props: ['label', 'disabled'],
  emits: ['click'],
  template: '<button :disabled="disabled" @click="$emit(\'click\')">{{ label }}</button>',
})
const Select = defineComponent({
  props: ['modelValue'],
  emits: ['update:modelValue'],
  template:
    '<select @change="$emit(\'update:modelValue\', $event.target.value)"><option value="">-</option><option value="template-1">Template</option></select>',
})
const members = {
  entryMembers: [],
  entryCount: 0,
  minEntryCount: 1,
  maxEntryCount: 10,
  teamMemberCandidates: [
    { userId: 1, displayName: '候補メンバー', memberNumber: null, position: null },
  ],
}
beforeEach(() => {
  vi.resetAllMocks()
  mocks.getEntryMembers.mockResolvedValue({ data: members })
  mocks.getEntryTemplates.mockResolvedValue({
    data: [{ id: 'template-1', name: 'Template', memberCount: 1 }],
  })
  vi.stubGlobal('ref', ref)
  vi.stubGlobal('computed', computed)
  vi.stubGlobal('watch', watch)
  vi.stubGlobal('useI18n', () => ({ t: (key: string) => key }))
  vi.stubGlobal('useNuxtApp', () => ({ $toast: { add: mocks.toastAdd } }))
  vi.stubGlobal('useTournamentApi', () => mocks)
  vi.stubGlobal('useErrorHandler', () => mocks)
})
afterEach(() => vi.unstubAllGlobals())

async function render(isAdmin = true) {
  const wrapper = mount(Modal, {
    props: {
      isOpen: true,
      orgId: '1',
      tournamentId: 2,
      divisionId: 3,
      participantId: 4,
      teamId: '5',
      isAdmin,
    },
    global: {
      mocks: { $t: (key: string) => key },
      stubs: {
        Dialog: { template: '<div><slot /><slot name="footer" /></div>' },
        Button,
        Select,
        DataTable: true,
        Column: true,
        Checkbox: true,
        InputNumber: true,
        InputText: true,
        Tag: true,
      },
    },
  })
  await flushPromises()
  return wrapper
}

describe('CMP-261002-0743 大会操作の失敗通知', () => {
  it.each(['save', 'load', 'template'] as const)(
    'T4 %s失敗は通知し、saved・成功toastへ進まない',
    async (action) => {
      const error = {
        statusCode: 400,
        data: { error: { code: 'TOUR_022', message: '最小人数不足' } },
      }
      const operation = {
        save: mocks.upsertEntryMembers,
        load: mocks.loadEntryMembersFromTeam,
        template: mocks.applyEntryTemplate,
      }[action]
      operation.mockRejectedValue(error)
      const wrapper = await render()
      if (action === 'template') await wrapper.get('select').setValue('template-1')
      const label = {
        save: 'tournament.entry.saveMembers',
        load: 'tournament.entry.loadFromTeam',
        template: 'tournament.entry.template.apply',
      }[action]

      await wrapper
        .findAll('button')
        .find((button) => button.text() === label)!
        .trigger('click')
      await flushPromises()

      expect(operation).toHaveBeenCalledOnce()
      expect(mocks.handleApiError).toHaveBeenCalledExactlyOnceWith(error)
      expect(wrapper.emitted('saved')).toBeUndefined()
      expect(mocks.toastAdd).not.toHaveBeenCalled()
      expect(wrapper.text()).toContain('候補メンバー')
    },
  )

  it.each([429, 500])('T4 %sはuseApiの既存通知と重複しない', async (statusCode) => {
    mocks.upsertEntryMembers.mockRejectedValue({ statusCode })
    const wrapper = await render()

    await wrapper
      .findAll('button')
      .find((button) => button.text() === 'tournament.entry.saveMembers')!
      .trigger('click')
    await flushPromises()

    expect(mocks.handleApiError).not.toHaveBeenCalled()
    expect(wrapper.emitted('saved')).toBeUndefined()
    expect(mocks.toastAdd).not.toHaveBeenCalled()
  })

  it('T5 非管理者には編集・テンプレート適用を表示しない', async () => {
    const wrapper = await render(false)

    expect(mocks.getEntryTemplates).not.toHaveBeenCalled()
    expect(wrapper.find('select').exists()).toBe(false)
    expect(wrapper.findAll('button').map((button) => button.text())).not.toContain(
      'tournament.entry.saveMembers',
    )
  })
})
