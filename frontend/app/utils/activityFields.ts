import type { ActivityTemplateField } from '~/types/activity'

/** 活動カスタムフィールドの入力値（DatePicker は Date を持つ）。 */
export type ActivityFieldValue = string | number | boolean | Date | null | undefined

/**
 * SELECT 型フィールドの選択肢 JSON 文字列を {label,value} 配列にパースする。
 * パース不能・非配列は空配列を返す（症状を隠さず安全側に倒す）。
 */
export function parseSelectOptions(
  optionsJson: string | null | undefined,
): Array<{ label: string; value: string }> {
  if (!optionsJson) return []
  try {
    const parsed: unknown = JSON.parse(optionsJson)
    if (!Array.isArray(parsed)) return []
    return parsed.map((o) => ({ label: String(o), value: String(o) }))
  } catch {
    // eslint-disable-next-line no-restricted-syntax -- 設定 JSON（選択肢）の防御パース。パース不能は空選択肢が正しい（安全側・機能劣化なし）
    return []
  }
}

/**
 * Date をローカル日付の YYYY-MM-DD 文字列に整形する（UTC ズレを避ける）。
 */
export function toYmd(d: Date): string {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

/**
 * フィールドが入力済みか判定する（必須検証用）。
 * - NUMBER: null/undefined/'' は未入力（0 は入力済み）
 * - DATE: Date インスタンスのみ入力済み
 * - CHECKBOX: 真偽値は常に値を持つため常に true（必須でもブロックしない）
 * - TEXT/TEXTAREA/SELECT: 空白除去後に文字があれば入力済み
 */
export function isActivityFieldFilled(
  field: Pick<ActivityTemplateField, 'fieldType'>,
  value: ActivityFieldValue,
): boolean {
  switch (field.fieldType) {
    case 'NUMBER':
      return value !== null && value !== undefined && value !== ''
    case 'DATE':
      return value instanceof Date
    case 'CHECKBOX':
      return true
    default:
      return typeof value === 'string' && value.trim().length > 0
  }
}

/**
 * テンプレフィールド定義と入力値マップから、送信用の fieldValues（キーは fieldKey）を組み立てる。
 * 未入力のフィールドは送らない。DATE は YYYY-MM-DD 文字列、NUMBER は数値、CHECKBOX は真偽値、
 * その他は trim 済み文字列にする。
 */
export function buildActivityFieldValues(
  fields: ActivityTemplateField[],
  inputs: Record<string, ActivityFieldValue>,
): Record<string, string | number | boolean> {
  const out: Record<string, string | number | boolean> = {}
  for (const field of fields) {
    const v = inputs[field.fieldKey]
    if (v === null || v === undefined) continue
    if (field.fieldType === 'DATE') {
      if (v instanceof Date) out[field.fieldKey] = toYmd(v)
      continue
    }
    if (field.fieldType === 'CHECKBOX') {
      out[field.fieldKey] = Boolean(v)
      continue
    }
    if (field.fieldType === 'NUMBER') {
      if (typeof v === 'number') out[field.fieldKey] = v
      continue
    }
    if (typeof v === 'string') {
      const trimmed = v.trim()
      if (trimmed.length > 0) out[field.fieldKey] = trimmed
    }
  }
  return out
}

/**
 * 活動記録の必須項目がすべて満たされているか判定する（登録ボタンの活性制御）。
 */
export function canSubmitActivity(params: {
  templateId: number | null
  title: string
  activityDate: Date | null
  fields: ActivityTemplateField[]
  inputs: Record<string, ActivityFieldValue>
}): boolean {
  if (!params.templateId) return false
  if (!params.title.trim()) return false
  if (!params.activityDate) return false
  for (const field of params.fields) {
    if (field.isRequired && !isActivityFieldFilled(field, params.inputs[field.fieldKey])) {
      return false
    }
  }
  return true
}
