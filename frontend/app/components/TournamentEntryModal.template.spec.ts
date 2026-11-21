// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { computed, defineComponent, ref, watch } from 'vue'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import Modal from './TournamentEntryModal.vue'

/** CMP-260930-0228 エントリーテンプレートの保存・読込 UI */
const mocks = vi.hoisted(() => ({
  getEntryMembers: vi.fn(),
  getEntryTemplates: vi.fn(),
  applyEntryTemplate: vi.fn(),
  createEntryTemplate: vi.fn(),
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
const InputText = defineComponent({
  props: ['modelValue', 'placeholder'],
  emits: ['update:modelValue'],
  template:
    '<input :placeholder="placeholder" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)">',
})

const listResponse = {
  entryMembers: [
    {
      id: 'e1',
      userId: 11,
      displayName: '選手A',
      memberNumber: null,
      jerseyNumber: 10,
      position: 'FW',
      notes: null,
      sortOrder: 1,
    },
    {
      id: 'e2',
      userId: 12,
      displayName: '選手B',
      memberNumber: null,
      jerseyNumber: null,
      position: null,
      notes: null,
      sortOrder: 2,
    },
  ],
  entryCount: 2,
  minEntryCount: null,
  maxEntryCount: null,
  teamMemberCandidates: [],
}

beforeEach(() => {
  vi.resetAllMocks()
  mocks.getEntryMembers.mockResolvedValue({ data: listResponse })
  mocks.getEntryTemplates.mockResolvedValue({ data: [] })
  mocks.createEntryTemplate.mockResolvedValue({ data: { id: 't9', name: 'N', members: [] } })
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
        InputText,
        DataTable: true,
        Column: true,
        Checkbox: true,
        InputNumber: true,
        Tag: true,
      },
    },
  })
  await flushPromises()
  return wrapper
}

const SAVE_LABEL = 'tournament.entry.template.create'
const NAME_INPUT = 'input[placeholder="tournament.entry.template.name"]'
const saveButton = (w: Awaited<ReturnType<typeof render>>) =>
  w.findAll('button').find((b) => b.text() === SAVE_LABEL)

describe('CMP-260930-0228 エントリーテンプレート保存', () => {
  it('現在のエントリー（背番号・ポジション込み）をテンプレートとして作成APIへ送る', async () => {
    const wrapper = await render()
    await wrapper.get(NAME_INPUT).setValue('  先発  ')
    await saveButton(wrapper)!.trigger('click')
    await flushPromises()

    expect(mocks.createEntryTemplate).toHaveBeenCalledExactlyOnceWith('1', '5', {
      name: '先発',
      description: null,
      members: [
        { userId: 11, jerseyNumber: 10, position: 'FW', sortOrder: 1 },
        { userId: 12, jerseyNumber: null, position: null, sortOrder: 2 },
      ],
    })
    expect(mocks.toastAdd).toHaveBeenCalledOnce()
    // 保存後は一覧を取り直し、読込（適用）できるようにする
    expect(mocks.getEntryTemplates).toHaveBeenCalledTimes(2)
  })

  it('テンプレート名が空なら保存できない', async () => {
    const wrapper = await render()
    expect(saveButton(wrapper)!.attributes('disabled')).toBeDefined()
    await saveButton(wrapper)!.trigger('click')
    expect(mocks.createEntryTemplate).not.toHaveBeenCalled()
  })

  it('エントリーが0件なら保存できない', async () => {
    mocks.getEntryMembers.mockResolvedValue({
      data: { ...listResponse, entryMembers: [], entryCount: 0 },
    })
    const wrapper = await render()
    await wrapper.get(NAME_INPUT).setValue('名前')
    expect(saveButton(wrapper)!.attributes('disabled')).toBeDefined()
  })

  it('テンプレートが5件あれば保存できず上限を表示する', async () => {
    mocks.getEntryTemplates.mockResolvedValue({
      data: Array.from({ length: 5 }, (_, i) => ({ id: `t${i}`, name: `T${i}`, memberCount: 1 })),
    })
    const wrapper = await render()
    await wrapper.get(NAME_INPUT).setValue('名前')
    expect(saveButton(wrapper)!.attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('tournament.entry.template.maxCount')
  })

  it('テンプレート一覧の取得に失敗したら通知し、保存を無効にして案内を出す', async () => {
    const error = { statusCode: 404, data: { error: { code: 'TOUR_026' } } }
    mocks.getEntryTemplates.mockRejectedValue(error)
    const wrapper = await render()
    await wrapper.get(NAME_INPUT).setValue('名前')

    expect(mocks.handleApiError).toHaveBeenCalledExactlyOnceWith(error)
    expect(saveButton(wrapper)!.attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('tournament.entry.template.loadFailed')
  })

  it('保存失敗は握りつぶさず通知し、成功toastを出さない', async () => {
    const error = { statusCode: 400, data: { error: { code: 'TOUR_025' } } }
    mocks.createEntryTemplate.mockRejectedValue(error)
    const wrapper = await render()
    await wrapper.get(NAME_INPUT).setValue('名前')
    await saveButton(wrapper)!.trigger('click')
    await flushPromises()

    expect(mocks.handleApiError).toHaveBeenCalledExactlyOnceWith(error)
    expect(mocks.toastAdd).not.toHaveBeenCalled()
  })

  it('権限なし（非管理者）には保存UIを出さず、APIも呼ばない', async () => {
    const wrapper = await render(false)
    expect(saveButton(wrapper)).toBeUndefined()
    expect(wrapper.find(NAME_INPUT).exists()).toBe(false)
    expect(mocks.createEntryTemplate).not.toHaveBeenCalled()
  })
})
