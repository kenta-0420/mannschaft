import { describe, it, expect, vi } from 'vitest'
import { ref } from 'vue'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'

const { getTeam } = vi.hoisted(() => ({ getTeam: vi.fn() }))
mockNuxtImport('useTeamApi', () => () => ({ getTeam }))
const { useSchoolTeamContext } = await import('./useSchoolTeamContext')

describe('学校画面のチーム識別', () => {
  it('英字slugから既存チーム応答の数値IDと種別を得る', async () => {
    getTeam.mockResolvedValueOnce({ data: { numericId: 42, location: { template: 'CLASS' } } })
    const ctx = useSchoolTeamContext(ref('class-blue'))
    expect(await ctx.load()).toBe(true)
    expect(getTeam).toHaveBeenCalledWith('class-blue')
    expect(ctx.teamId.value).toBe('42')
    expect(ctx.template.value).toBe('CLASS')
  })

  it('失敗を非学校nullと区別し同じ取得を再試行できる', async () => {
    getTeam.mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce({ data: { numericId: 42, location: { template: null } } })
    const ctx = useSchoolTeamContext(ref('class-blue'))
    expect(await ctx.load()).toBe(false)
    expect(ctx.error.value).toBeTruthy()
    expect(ctx.teamId.value).toBe('')
    expect(await ctx.load()).toBe(true)
    expect(ctx.error.value).toBeUndefined()
    expect(ctx.template.value).toBeNull()
  })

  it('Aの遅い応答をBへ適用しない', async () => {
    let finishA!: (value: unknown) => void
    getTeam.mockReturnValueOnce(new Promise(resolve => { finishA = resolve }))
      .mockResolvedValueOnce({ data: { numericId: 22, location: { template: 'OTHER' } } })
    const slug = ref('a')
    const ctx = useSchoolTeamContext(slug)
    const pending = ctx.load()
    slug.value = 'b'
    await ctx.load()
    finishA({ data: { numericId: 11, location: { template: 'CLASS' } } })
    expect(await pending).toBe(false)
    expect(ctx.teamId.value).toBe('22')
    expect(ctx.template.value).toBe('OTHER')
  })
})
