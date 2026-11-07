// @vitest-environment happy-dom
import { afterEach, describe, expect, expectTypeOf, it, vi } from 'vitest'
import { defineComponent, h, nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import { useForm } from 'vee-validate'
import { z } from 'zod'
import { toTypedSchema } from './toTypedSchema'

const wrappers: ReturnType<typeof mount>[] = []
afterEach(() => wrappers.splice(0).forEach((wrapper) => wrapper.unmount()))

describe('toTypedSchema のフォーム契約', () => {
  it('実useFormがtrim済みの出力を送信し、不正な入力の送信を拒否する', async () => {
    const schema = z.object({ name: z.string().trim().min(1, '必須です') })
    const form = mountForm(() => useForm({ validationSchema: toTypedSchema(schema) }))
    const submitted = vi.fn()

    form.setFieldValue('name', '   ')
    await form.handleSubmit(submitted)()
    expect(submitted).not.toHaveBeenCalled()
    expect(form.errors.value.name).toBe('必須です')

    form.setFieldValue('name', ' 名前 ')
    await form.handleSubmit((values) => {
      expectTypeOf(values).toEqualTypeOf<z.output<typeof schema>>()
      submitted(values)
    })()
    expect(submitted).toHaveBeenCalledWith({ name: '名前' })
    expect(form.errors.value).toEqual({})
  })

  it('非同期の検証を待ち、フィールドエラーを実useFormへ渡す', async () => {
    const schema = z.object({ name: z.string() }).refine(async (value) => value.name !== '重複', {
      path: ['name'],
      message: '使用済みです',
    })
    const form = mountForm(() => useForm({ validationSchema: toTypedSchema(schema) }))
    const submitted = vi.fn()

    form.setFieldValue('name', '重複')
    await form.handleSubmit(submitted)()
    expect(form.errors.value).toEqual({ name: '使用済みです' })
    expect(submitted).not.toHaveBeenCalled()

    form.setFieldValue('name', '未使用')
    await form.handleSubmit(submitted)()
    expect(submitted).toHaveBeenCalledOnce()
  })

  it('同じパスの全エラーとルートエラーを保持する', async () => {
    const schema = toTypedSchema(
      z.object({ name: z.string().min(2, '短すぎます').regex(/^[A-Z]+$/, '英大文字です') })
        .refine(() => false, { message: 'フォームエラー' }),
    )
    expect(await schema.parse({ name: 'a' })).toEqual({
      errors: [
        { path: 'name', errors: ['短すぎます', '英大文字です'] },
        { path: '', errors: ['フォームエラー'] },
      ],
    })
  })

  it('WebhookのURLはZod 4の正規化後の値を返す', async () => {
    const schema = toTypedSchema(z.object({ url: z.string().url() }))
    expect(await schema.parse({ url: '  https://example.test/webhook  ' })).toEqual({
      value: { url: 'https://example.test/webhook' }, errors: [],
    })
  })

  it('未完の初期値にもfalse/null/空文字のdefaultを補い、明示値を優先してresetする', async () => {
    const schema = toTypedSchema(z.object({
      name: z.string().min(1),
      description: z.string().default(''),
      active: z.boolean().default(false),
      limit: z.number().nullable().default(null),
    }))
    const form = mountForm(() => useForm({
      validationSchema: schema,
      initialValues: { description: '指定値', active: true },
    }))

    expect(form.values).toEqual({ description: '指定値', active: true, limit: null })
    form.setValues({ name: '入力', description: '変更値', active: false, limit: 3 })
    form.resetForm()
    expect(form.values).toEqual({ description: '指定値', active: true, limit: null })
    expect(schema.cast?.({})).toEqual({ description: '', active: false, limit: null })
    expect(schema.cast?.({ limit: null, active: false })).toEqual({
      description: '', active: false, limit: null,
    })
    await nextTick()
    await form.handleSubmit(vi.fn())()
    expect(form.errors.value.name).toBeTruthy()
  })

  it('同期castでtrimを適用し、非同期検証時にもdefaultと入力を保持する', () => {
    expect(toTypedSchema(z.object({ name: z.string().trim() })).cast?.({ name: ' 名前 ' }))
      .toEqual({ name: '名前' })
    const schema = toTypedSchema(z.object({ name: z.string(), active: z.boolean().default(false) })
      .refine(async () => true))
    expect(schema.cast?.({ name: '入力' })).toEqual({ name: '入力', active: false })
  })

  it('必須・任意・default・null許容と存在しないフィールドのdescribeを区別する', () => {
    const schema = toTypedSchema(z.object({
      name: z.string(), optional: z.string().optional(), active: z.boolean().default(false),
      nullable: z.string().nullable(),
    }))
    expect(schema.describe?.()).toEqual({ required: true, exists: true })
    expect(schema.describe?.('name')).toEqual({ required: true, exists: true })
    expect(schema.describe?.('optional')).toEqual({ required: false, exists: true })
    expect(schema.describe?.('active')).toEqual({ required: false, exists: true })
    expect(schema.describe?.('nullable')).toEqual({ required: true, exists: true })
    expect(schema.describe?.('unknown' as 'name')).toEqual({ required: false, exists: false })
  })
})

function mountForm<T>(setup: () => T): T {
  let form: T | undefined
  wrappers.push(mount(defineComponent({
    setup() { form = setup(); return () => h('form') },
  })))
  return form!
}
