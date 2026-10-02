import { beforeEach, describe, expect, it, vi, type MockedFunction } from 'vitest'

const mockFetch: MockedFunction<(url: string, options?: unknown) => Promise<unknown>> = vi.fn()

vi.mock('~/composables/useApi', () => ({
  useApi: () => mockFetch,
}))

const { useBulletinThreads } = await import('~/composables/bulletin/useBulletinThreads')

describe('useBulletinThreads.archiveScopedThread', () => {
  beforeEach(() => {
    mockFetch.mockReset()
    mockFetch.mockResolvedValue({ data: {} })
  })

  it('復元はBE DTO契約のcamelCaseで送る', async () => {
    const { archiveScopedThread } = useBulletinThreads()

    await archiveScopedThread('TEAM', 'fc-u-18', 101, false)

    expect(mockFetch).toHaveBeenCalledWith(
      '/api/v1/TEAM/fc-u-18/bulletin/threads/101/archive',
      { method: 'POST', body: { isArchived: false } },
    )
  })

  it.each([
    ['7f000101-a08b-18cb-81a0-8db55bf3000f'],
    [null],
  ])('再アーカイブは元フォルダ(%s)をcamelCaseで送る', async (archiveFolderId) => {
    const { archiveScopedThread } = useBulletinThreads()

    await archiveScopedThread('TEAM', 'fc-u-18', 101, true, archiveFolderId)

    expect(mockFetch).toHaveBeenCalledWith(
      '/api/v1/TEAM/fc-u-18/bulletin/threads/101/archive',
      { method: 'POST', body: { isArchived: true, archiveFolderId } },
    )
  })
})
