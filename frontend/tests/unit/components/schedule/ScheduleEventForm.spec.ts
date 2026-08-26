import { defineComponent, h, ref } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import ScheduleEventForm from '~/components/schedule/ScheduleEventForm.vue'

/**
 * [P2回帰・検分四巡目] ScheduleEventForm の `saved` イベントが渡すスコープの回帰テスト。
 *
 * 背景: 保存 API の応答を待っている間、スコープ選択欄（submitting 中も操作可能）を
 * ユーザーが変更できる。以前は `emit('saved', effectiveScope.value)` がリアクティブな
 * 「現在値」を読んでいたため、API が実際に保存した先とは異なるスコープが呼び出し側へ
 * 渡ってしまっていた（await を跨いでリアクティブな値を読む一般的な誤り）。
 * API 呼び出しの直前にスコープをスナップショットし、`emit` にはそれだけを渡すよう修正した。
 */

const scheduleApiMock = {
  createSchedule: vi.fn(),
  createPersonalSchedule: vi.fn(),
  updateSchedule: vi.fn(),
  updatePersonalSchedule: vi.fn(),
  getSchedule: vi.fn(),
  getMyScheduleDetail: vi.fn(),
}
const notificationMock = { success: vi.fn(), error: vi.fn() }
const errorHandlerMock = { handleApiError: vi.fn(), getFieldErrors: vi.fn(() => ({})) }
const googleCalendarMock = { googleSyncEnabled: ref(false), fetchPersonalSyncStatus: vi.fn() }

vi.mock('~/composables/useScheduleApi', () => ({ useScheduleApi: () => scheduleApiMock }))
vi.mock('~/composables/useNotification', () => ({ useNotification: () => notificationMock }))
vi.mock('~/composables/useErrorHandler', () => ({ useErrorHandler: () => errorHandlerMock }))
vi.mock('~/composables/useGoogleCalendarApi', () => ({ useGoogleCalendarApi: () => googleCalendarMock }))

// Dialog は visible=false でも子を描画してしまうと逆に自然なので、visible に関わらず
// 常時スロットを描画する軽量スタブに置き換える（フッターの保存ボタンへ確実に到達するため）。
const DialogStub = defineComponent({
  name: 'Dialog',
  props: { visible: Boolean, header: String },
  emits: ['update:visible', 'hide'],
  setup(_props, { slots }) {
    return () => h('div', { 'data-testid': 'dialog-stub' }, [slots.default?.(), slots.footer?.()])
  },
})

// スコープ選択欄。実運用と同じく v-model:selected-scope-key で親の selectedScopeKey を変更する。
const ScopeSelectorStub = defineComponent({
  name: 'ScheduleEventScopeSelector',
  props: {
    selectedScopeKey: String,
    scopeOptions: { type: Array, default: () => [] },
  },
  emits: ['update:selectedScopeKey'],
  setup(props, { emit }) {
    return () =>
      h(
        'select',
        {
          'data-testid': 'scope-selector-stub',
          value: props.selectedScopeKey,
          onChange: (e: Event) => emit('update:selectedScopeKey', (e.target as HTMLSelectElement).value),
        },
        (props.scopeOptions as Array<{ value: string; label: string }>).map(o =>
          h('option', { value: o.value }, o.label),
        ),
      )
  },
})

// タイトル入力欄のみ再現する軽量スタブ（v-model:form）。
const BasicFieldsStub = defineComponent({
  name: 'ScheduleEventBasicFields',
  props: { form: { type: Object, required: true } },
  emits: ['update:form'],
  setup(props, { emit }) {
    return () =>
      h('input', {
        'data-testid': 'title-input',
        value: (props.form as { title: string }).title,
        onInput: (e: Event) =>
          emit('update:form', { ...(props.form as object), title: (e.target as HTMLInputElement).value }),
      })
  },
})

// PrimeVue Button の軽量スタブ。auto-stub（true）は label prop をテキストとして描画しないため、
// フッターの「作成」ボタンをテキストで特定できるよう、label をそのまま描画する形にする。
const ButtonStub = defineComponent({
  name: 'Button',
  props: { label: String, icon: String, loading: Boolean, text: Boolean },
  emits: ['click'],
  setup(props, { emit }) {
    return () => h('button', { type: 'button', onClick: (e: Event) => emit('click', e) }, props.label)
  },
})

const globalStubs = {
  Dialog: DialogStub,
  ScheduleEventScopeSelector: ScopeSelectorStub,
  ScheduleEventBasicFields: BasicFieldsStub,
  ScheduleTargetPicker: true,
  ScheduleEventRecurrenceInput: true,
  ScheduleEventReminderInput: true,
  ScheduleEventScheduledAttachmentInput: true,
  ScheduleEventColorPicker: true,
  Checkbox: true,
  Textarea: true,
  Button: ButtonStub,
}

const teamScope = { label: 'チームA', value: 'team_t1', isPersonal: false, scopeType: 'team' as const, scopeId: 't1' }
const personalScope = { label: '個人', value: 'personal', isPersonal: true, scopeType: 'team' as const, scopeId: '' }

describe('ScheduleEventForm: saved イベントのスコープ', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    scheduleApiMock.createSchedule.mockReset()
    scheduleApiMock.createPersonalSchedule.mockReset()
    scheduleApiMock.updateSchedule.mockReset()
    scheduleApiMock.updatePersonalSchedule.mockReset()
    notificationMock.success.mockReset()
    errorHandlerMock.handleApiError.mockReset()
  })

  it('[P2回帰] 保存API応答待ち中にスコープを変更しても、saved には変更前（実際に保存した）スコープが渡る', async () => {
    // createSchedule の解決を自分で制御できるようにする（応答待ちを再現するため）
    let resolveCreate: (value: unknown) => void = () => {}
    const pending = new Promise((resolve) => { resolveCreate = resolve })
    scheduleApiMock.createSchedule.mockReturnValue(pending)

    const wrapper = await mountSuspended(ScheduleEventForm, {
      props: {
        visible: true,
        scopeType: 'team',
        scopeId: 't1',
        isPersonal: false,
        scopeOptions: [teamScope, personalScope],
      },
      global: { stubs: globalStubs },
    })

    // タイトルを入力する（送信バリデーションを通すため）
    await wrapper.get('[data-testid="title-input"]').setValue('検分四巡目テスト予定')

    // 保存ボタンを押す（作成先はチームA＝team_t1 のまま）
    const buttons = wrapper.findAll('button')
    const submitButton = buttons.find(b => b.text() === '作成')
    expect(submitButton).toBeTruthy()
    await submitButton!.trigger('click')
    await flushPromises()

    // 実際に呼ばれた API はチームA向け（スナップショットがここで固定される）
    expect(scheduleApiMock.createSchedule).toHaveBeenCalledWith('team', 't1', expect.anything())

    // API 応答待ちの間に、ユーザーがスコープ選択欄を「個人」へ変更する
    await wrapper.get('[data-testid="scope-selector-stub"]').setValue('personal')
    await wrapper.vm.$nextTick()

    // ここで API が解決する
    resolveCreate({ data: { id: 1 } })
    await flushPromises()
    await wrapper.vm.$nextTick()

    // saved に渡るスコープは「変更前」（実際に保存した）チームAのままでなければならない
    const savedEvents = wrapper.emitted('saved')
    expect(savedEvents).toBeTruthy()
    expect(savedEvents![0]).toEqual([{ isPersonal: false, scopeType: 'team', scopeId: 't1' }])
  })
})
