import { describe, expect, it } from 'vitest'

import { mountSuspended } from '@nuxt/test-utils/runtime'
import ProjectMilestoneDialog from './ProjectMilestoneDialog.vue'
import type { CreateMilestoneRequest, MilestoneResponse } from '~/types/project'

/**
 * CMP-261105-1507 B4: マイルストーンのダイアログ。
 * (a) タイトル空は送信前に弾き、欄にエラーを出す。BE の fieldErrors も欄に出す。
 * (b) 開くたびに保存値で初期化する（Esc で閉じた打ちかけの値を持ち越さない）。新規追加は閉じたら空に戻す。
 */
const stubs = {
  Dialog: {
    props: ['visible'],
    template: '<div v-if="visible"><slot /><slot name="footer" /></div>',
  },
  InputText: {
    props: ['modelValue', 'invalid'],
    emits: ['update:modelValue'],
    template: '<input :value="modelValue" :data-invalid="invalid" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  Button: {
    props: ['label'],
    emits: ['click'],
    template: '<button @click="$emit(\'click\')">{{ label }}</button>',
  },
}

const milestone = { id: 1, title: '保存値' } as unknown as MilestoneResponse

async function mountDialog(form: CreateMilestoneRequest, editing: MilestoneResponse | null, fieldErrors?: Record<string, string>) {
  return mountSuspended(ProjectMilestoneDialog, {
    props: { visible: true, form, editing, fieldErrors },
    global: { stubs },
  })
}

function titleInput(wrapper: Awaited<ReturnType<typeof mountDialog>>) {
  return wrapper.find('[data-testid="milestone-title-input"]')
}

describe('ProjectMilestoneDialog', () => {
  it('タイトルが空のとき保存を送信せず、タイトル欄にエラーを出す', async () => {
    const wrapper = await mountDialog({ title: '', dueDate: '', sortOrder: 0 }, null)
    await wrapper.find('[data-testid="milestone-save-button"]').trigger('click')
    expect(wrapper.emitted('save')).toBeUndefined()
    expect(wrapper.find('[data-testid="milestone-title-error"]').exists()).toBe(true)
  })

  it('空白だけのタイトルも送信しない', async () => {
    const wrapper = await mountDialog({ title: '   ', dueDate: '', sortOrder: 0 }, null)
    await wrapper.find('[data-testid="milestone-save-button"]').trigger('click')
    expect(wrapper.emitted('save')).toBeUndefined()
  })

  it('タイトルがあれば save を emit する', async () => {
    const wrapper = await mountDialog({ title: '設計', dueDate: '', sortOrder: 0 }, null)
    await wrapper.find('[data-testid="milestone-save-button"]').trigger('click')
    expect(wrapper.emitted('save')).toHaveLength(1)
  })

  it('BE が返した fieldErrors（title）を欄に出す', async () => {
    const wrapper = await mountDialog(
      { title: '設計', dueDate: '', sortOrder: 0 },
      null,
      { title: 'タイトルは必須です' },
    )
    expect(wrapper.find('[data-testid="milestone-title-error"]').text()).toBe('タイトルは必須です')
  })

  it('編集: Esc で閉じて開き直すと、打ちかけの値ではなく保存値が出る', async () => {
    const wrapper = await mountDialog({ title: '保存値', dueDate: '', sortOrder: 0 }, milestone)
    await titleInput(wrapper).setValue('打ちかけ')
    expect((titleInput(wrapper).element as HTMLInputElement).value).toBe('打ちかけ')

    await wrapper.setProps({ visible: false })
    await wrapper.setProps({ visible: true })

    expect((titleInput(wrapper).element as HTMLInputElement).value).toBe('保存値')
  })

  it('新規追加: 入力して閉じると、開き直したときに空に戻っている', async () => {
    const wrapper = await mountDialog({ title: '', dueDate: '', sortOrder: 2 }, null)
    await titleInput(wrapper).setValue('途中')

    await wrapper.setProps({ visible: false })
    await wrapper.setProps({ visible: true })

    expect((titleInput(wrapper).element as HTMLInputElement).value).toBe('')
  })

  it('送信前エラーは開き直すと消える', async () => {
    const wrapper = await mountDialog({ title: '', dueDate: '', sortOrder: 0 }, null)
    await wrapper.find('[data-testid="milestone-save-button"]').trigger('click')
    expect(wrapper.find('[data-testid="milestone-title-error"]').exists()).toBe(true)

    await wrapper.setProps({ visible: false })
    await wrapper.setProps({ visible: true })

    expect(wrapper.find('[data-testid="milestone-title-error"]').exists()).toBe(false)
  })
})
