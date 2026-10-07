import type { ActivityDetailResponse, ActivityTemplateField } from '~/types/activity'
import { buildActivityFieldValues, type ActivityFieldValue } from '~/utils/activityFields'

/** 実際に変更した入力だけを反映し、定義変更で入力できない旧値は保全する。 */
export function mergeActivityEditedFieldValues(
  raw: Record<string, unknown>,
  fields: ActivityTemplateField[],
  initial: Record<string, ActivityFieldValue>,
  current: Record<string, ActivityFieldValue>,
): Record<string, unknown> {
  const changedKeys = fields
    .map((field) => field.fieldKey)
    .filter((key) => {
      const before = initial[key]
      const after = current[key]
      return before instanceof Date && after instanceof Date
        ? before.getTime() !== after.getTime()
        : before !== after
    })
  const edited = buildActivityFieldValues(fields, current)
  return {
    ...Object.fromEntries(Object.entries(raw).filter(([key]) => !changedKeys.includes(key))),
    ...Object.fromEntries(Object.entries(edited).filter(([key]) => changedKeys.includes(key))),
  }
}

/** APIのJSON文字列を検証してから画面へ渡す。不正なJSONは取得失敗として表示する。 */
export function activityRawFieldValues(
  record: Pick<ActivityDetailResponse, 'fieldValues'>,
): Record<string, unknown> {
  const parsed: unknown = record.fieldValues ? JSON.parse(record.fieldValues) : {}
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed))
    throw new Error('活動記録の項目が不正です')
  return parsed as Record<string, unknown>
}

/** 編集可能な既知の単純値だけを入力モデルへ渡す。未知値は元JSONで保全する。 */
export function activityFieldValues(
  record: Pick<ActivityDetailResponse, 'fieldValues' | 'templateFields'>,
): Record<string, ActivityFieldValue> {
  const parsed = activityRawFieldValues(record)
  const values: Record<string, ActivityFieldValue> = {}
  for (const [key, value] of Object.entries(parsed)) {
    if (
      value === null ||
      typeof value === 'string' ||
      typeof value === 'number' ||
      typeof value === 'boolean'
    )
      values[key] = value
  }
  return values
}

export function activityDisplayFields(
  record: Pick<ActivityDetailResponse, 'fieldValues' | 'templateFields'>,
): Array<{ key: string; label: string; value: string; unit: string | null }> {
  const values = activityRawFieldValues(record)
  const keys = [
    ...new Set([...record.templateFields.map((field) => field.fieldKey), ...Object.keys(values)]),
  ]
  return keys.map((key) => {
    const field = record.templateFields.find((item) => item.fieldKey === key)
    const value = values[key]
    return {
      key,
      label: field?.fieldLabel ?? key,
      unit: field?.unit ?? null,
      value:
        value == null ? '—' : typeof value === 'object' ? JSON.stringify(value) : String(value),
    }
  })
}

export function activityFileIds(attachments: string | null): number[] {
  const parsed: unknown = attachments ? JSON.parse(attachments) : { file_ids: [] }
  if (
    !parsed ||
    typeof parsed !== 'object' ||
    !('file_ids' in parsed) ||
    !Array.isArray(parsed.file_ids)
  )
    throw new Error('活動記録の添付が不正です')
  if (
    !parsed.file_ids.every(
      (id: unknown) => typeof id === 'number' && Number.isSafeInteger(id) && id > 0,
    )
  )
    throw new Error('活動記録の添付IDが不正です')
  return parsed.file_ids
}
