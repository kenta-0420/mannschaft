import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(
  resolve(process.cwd(), 'app/pages/teams/[slug]/payments/advances.vue'),
  'utf8',
)

describe('立替・精算ページ', () => {
  it('slugを数値team IDへ解決し、BE認可と同じ管理権限で表示を制御する', () => {
    expect(source).toContain("resolveScopeId('TEAM', teamSlug.value)")
    expect(source).toContain('isAdminOrDeputy')
  })

  it('PENDINGだけに精算確認操作を表示し、ハンドラでも状態を再検証する', () => {
    expect(source).toContain("v-if=\"advance.settlementStatus === 'PENDING'\"")
    expect(source).toContain("if (advance.settlementStatus !== 'PENDING') return")
    expect(source).toContain("advance.settlementStatus !== 'PENDING'")
  })

  it('確認ダイアログを経て精算APIを呼ぶ', () => {
    expect(source).toContain('confirm.require({')
    expect(source).toContain('confirmPaymentAdvanceSettlement(teamId.value, advance.id)')
  })
})
