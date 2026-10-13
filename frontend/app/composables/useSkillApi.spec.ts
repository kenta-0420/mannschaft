import { beforeEach, describe, expect, it, vi } from 'vitest'

const mockApi = vi.fn()
vi.mock('~/composables/useApi', () => ({ useApi: () => mockApi }))
const { useSkillApi } = await import('./useSkillApi')

// BE: SkillController / SkillSearchController / SkillCategoryController / SkillMatrixController のマッピングと一致させる
describe('useSkillApi: BE コントローラのメソッド・パスと一致する', () => {
  beforeEach(() => {
    mockApi.mockReset()
    mockApi.mockResolvedValue({ data: {} })
  })

  it('承認は PATCH /skills/{id}/verify', async () => {
    await useSkillApi().verifySkill('t1', 5)
    expect(mockApi).toHaveBeenCalledWith('/api/v1/teams/t1/skills/5/verify', { method: 'PATCH' })
  })

  it('資格の取得・登録・更新・削除・自分の一覧', async () => {
    const a = useSkillApi()
    await a.getSkill('t1', 5)
    expect(mockApi).toHaveBeenLastCalledWith('/api/v1/teams/t1/skills/5')
    await a.registerSkill('t1', { skillCategoryId: 1 })
    expect(mockApi).toHaveBeenLastCalledWith('/api/v1/teams/t1/skills', { method: 'POST', body: { skillCategoryId: 1 } })
    await a.updateSkill('t1', 5, { name: 'x' })
    expect(mockApi).toHaveBeenLastCalledWith('/api/v1/teams/t1/skills/5', { method: 'PUT', body: { name: 'x' } })
    await a.deleteSkill('t1', 5)
    expect(mockApi).toHaveBeenLastCalledWith('/api/v1/teams/t1/skills/5', { method: 'DELETE' })
    await a.getMySkills('t1')
    expect(mockApi).toHaveBeenLastCalledWith('/api/v1/teams/t1/skills/me')
  })

  it('検索・証明書URL・アップロードURL', async () => {
    const a = useSkillApi()
    await a.searchSkills('t1', { categoryId: 2, keyword: 'a', status: undefined })
    expect(mockApi).toHaveBeenLastCalledWith('/api/v1/teams/t1/skills/search?categoryId=2&keyword=a')
    await a.getCertificateUrl('t1', 5)
    expect(mockApi).toHaveBeenLastCalledWith('/api/v1/teams/t1/skills/5/certificate-url')
    await a.getSkillUploadUrl('t1')
    expect(mockApi).toHaveBeenLastCalledWith('/api/v1/teams/t1/skills/upload-url', { method: 'POST' })
  })

  it('証明書URLは ApiResponse<String> 形（data が文字列）として読める', async () => {
    mockApi.mockResolvedValue({ data: 'https://example.invalid/c.pdf' })
    const res = await useSkillApi().getCertificateUrl('t1', 5)
    expect(res.data).toBe('https://example.invalid/c.pdf')
  })

  it('カテゴリ一覧は既定で includeInactive を付けず、true のとき付ける', async () => {
    const a = useSkillApi()
    await a.getSkillCategories('t1')
    expect(mockApi).toHaveBeenLastCalledWith('/api/v1/teams/t1/skill-categories')
    await a.getSkillCategories('t1', true)
    expect(mockApi).toHaveBeenLastCalledWith('/api/v1/teams/t1/skill-categories?includeInactive=true')
  })

  it('カテゴリの作成・更新・削除とマトリクス', async () => {
    const a = useSkillApi()
    await a.createSkillCategory('t1', { name: 'c' })
    expect(mockApi).toHaveBeenLastCalledWith('/api/v1/teams/t1/skill-categories', { method: 'POST', body: { name: 'c' } })
    await a.updateSkillCategory('t1', 3, { name: 'd' })
    expect(mockApi).toHaveBeenLastCalledWith('/api/v1/teams/t1/skill-categories/3', { method: 'PUT', body: { name: 'd' } })
    await a.deleteSkillCategory('t1', 3)
    expect(mockApi).toHaveBeenLastCalledWith('/api/v1/teams/t1/skill-categories/3', { method: 'DELETE' })
    await a.getSkillMatrix('t1')
    expect(mockApi).toHaveBeenLastCalledWith('/api/v1/teams/t1/skill-matrix')
  })
})
