import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(
  resolve(process.cwd(), 'app/pages/organizations/[slug]/payment-requests/new.vue'),
  'utf8',
)

describe('協会請求作成ページ', () => {
  it('slugを数値org IDへ解決し、BE認可と同じ管理権限で表示を制御する', () => {
    expect(source).toContain("resolveScopeId('ORGANIZATION', orgSlug.value)")
    expect(source).toContain('isAdminOrDeputy')
  })

  it('ZodとVeeValidateで必須項目・正整数・上限長を検証する', () => {
    expect(source).toContain('toTypedSchema(z.object')
    expect(source).toContain('.int().positive')
    expect(source).toContain('.max(120')
    expect(source).toContain('.max(1000')
  })

  it('所属チームの数値IDとJSTローカル日付を作成APIへ渡す', () => {
    expect(source).toContain('payerTeamId: values.payerTeamId')
    expect(source).toContain("values.dueDate.toLocaleDateString('sv-SE')")
    expect(source).toContain("currency: 'JPY'")
  })
})
