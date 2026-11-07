import type { TypedSchema, TypedSchemaError } from 'vee-validate'
import { z } from 'zod'

/**
 * 現行フォームの平坦な ZodObject を VeeValidate 4 に渡す。
 * 公式の stable adapter が Zod 4 に対応したら、この互換層を置き換える。
 */
export function toTypedSchema<T extends z.ZodObject>(
  schema: T,
): TypedSchema<Partial<z.input<T>>, z.output<T>> {
  return {
    __type: 'VVTypedSchema',
    async parse(values) {
      const result = await schema.safeParseAsync(values)
      if (result.success) return { value: result.data, errors: [] }

      const errors = new Map<string, TypedSchemaError>()
      for (const issue of result.error.issues) {
        const path = issue.path.join('.')
        const error = errors.get(path) ?? { path, errors: [] }
        error.errors.push(issue.message)
        errors.set(path, error)
      }
      return { errors: [...errors.values()] }
    },
    cast(values) {
      try {
        // VeeValidateのcastは入力型を返す契約。現行フォームはtrim/defaultのみを適用する。
        return schema.parse(values) as unknown as Partial<z.input<T>>
      } catch {
        // 未入力や非同期検証で同期parseできない初期化・reset時も、defaultを失わない。
        // 入力の可否は必ず上の非同期parseで判定し、ここではエラーを確定しない。
        const defaults = Object.fromEntries(
          Object.entries(schema.shape).map(([key, field]) => [
            key,
            field instanceof z.ZodDefault ? field.parse(undefined) : undefined,
          ]),
        )
        return { ...defaults, ...values }
      }
    },
    describe(path) {
      if (!path) return { required: !schema.isOptional(), exists: true }
      const field = Object.hasOwn(schema.shape, path) ? schema.shape[path] : undefined
      return field
        ? { required: !field.isOptional(), exists: true }
        : { required: false, exists: false }
    },
  }
}
