import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { nextTick } from 'vue'
import BulletinArchiveView from '~/components/bulletin/BulletinArchiveView.vue'

const confirmRequire = vi.fn()
const showUndoToast = vi.fn()
const showError = vi.fn()
const showSuccess = vi.fn()
const archiveScopedThread = vi.fn()
const getArchiveFolderTree = vi.fn()
const getArchiveThreads = vi.fn()
const restoreTheme = vi.fn()
const archiveTheme = vi.fn()
const getTheme = vi.fn()
const listEntries = vi.fn()
const listArchiveFolders = vi.fn()
const searchArchive = vi.fn()
const routerPush = vi.fn()
const routeState = {
  path: '/reflections/themes/theme-1',
  fullPath: '/reflections/themes/theme-1',
  params: { themeId: 'theme-1' },
  query: {},
  hash: '',
  matched: [],
  meta: {},
  name: undefined,
  redirectedFrom: undefined,
}

mockNuxtImport('useI18n', () => () => ({
  t: (key: string) => key,
}))

mockNuxtImport('useConfirm', () => () => ({ require: confirmRequire }))
mockNuxtImport('useUndoToast', () => () => ({ showUndoToast }))
mockNuxtImport('useNotification', () => () => ({
  showError,
  showSuccess,
  error: showError,
  success: showSuccess,
}))
mockNuxtImport('useRelativeTime', () => () => ({ relativeTime: (value: string) => value }))
mockNuxtImport('useDatetime', () => () => ({ formatDate: (value: string) => value }))
mockNuxtImport('useRouter', () => () => ({
  push: routerPush,
  replace: vi.fn().mockResolvedValue(undefined),
  go: vi.fn(),
  back: vi.fn(),
  forward: vi.fn(),
  beforeEach: vi.fn().mockReturnValue(vi.fn()),
  afterEach: vi.fn().mockReturnValue(vi.fn()),
  beforeResolve: vi.fn().mockReturnValue(vi.fn()),
  onError: vi.fn().mockReturnValue(vi.fn()),
  addRoute: vi.fn(),
  removeRoute: vi.fn(),
  hasRoute: vi.fn().mockReturnValue(false),
  getRoutes: vi.fn().mockReturnValue([]),
  resolve: vi.fn().mockReturnValue({ href: '/', ...routeState }),
  currentRoute: { value: routeState },
  isReady: vi.fn().mockResolvedValue(undefined),
}))
mockNuxtImport('useRoute', () => () => routeState)
mockNuxtImport('useBulletinApi', () => () => ({
  getArchiveFolderTree,
  deleteArchiveFolder: vi.fn(),
  getArchiveThreads,
  moveThreadToFolder: vi.fn(),
  archiveScopedThread,
}))
mockNuxtImport('useReflectionApi', () => () => ({
  getTheme,
  listEntries,
  listArchiveFolders,
  searchArchive,
  restoreTheme,
  archiveTheme,
  bulkArchive: vi.fn(),
}))

const ThemeDetailPage = (await import('~/pages/reflections/themes/[themeId].vue')).default
const ReflectionArchivePage = (await import('~/pages/reflections/archive/index.vue')).default

const thread = {
  id: 101,
  categoryId: null,
  categoryName: null,
  categoryColor: null,
  scopeType: 'TEAM',
  scopeId: '7',
  author: { id: 1, displayName: '利用者', avatarUrl: null },
  title: '保管スレッド',
  body: '本文',
  priority: 'NORMAL',
  readTrackingMode: 'NONE',
  isPinned: false,
  isLocked: false,
  isArchived: true,
  archiveFolderId: 'folder-before-restore',
  replyCount: 0,
  readCount: 0,
  isRead: true,
  reactionSummary: {},
  myReactions: [],
  lastRepliedAt: null,
  createdAt: '2026-09-11T00:00:00Z',
  updatedAt: '2026-09-11T00:00:00Z',
} as const

const archivedTheme = {
  id: 'theme-1',
  title: '振り返りテーマ',
  description: null,
  sourceType: 'FREE',
  linkedSubjectName: null,
  academicYear: null,
  termLabel: null,
  archivedAt: '2026-09-11T00:00:00Z',
}

async function flush(times = 5): Promise<void> {
  for (let i = 0; i < times; i++) await nextTick()
}

beforeEach(() => {
  vi.clearAllMocks()
  archiveScopedThread.mockResolvedValue({ data: {} })
  getArchiveFolderTree.mockResolvedValue({ data: [], meta: { unfiledThreadCount: 0 } })
  getArchiveThreads.mockResolvedValue({ data: [thread], meta: { totalPages: 1, page: 0 } })
  getTheme.mockResolvedValue({ data: { ...archivedTheme } })
  listEntries.mockResolvedValue({ data: [] })
  listArchiveFolders.mockResolvedValue({ data: [] })
  searchArchive.mockResolvedValue({
    data: { content: [{ ...archivedTheme }], totalElements: 1, totalPages: 1 },
  })
  restoreTheme.mockResolvedValue({ data: { ...archivedTheme, archivedAt: null } })
  archiveTheme.mockResolvedValue({ data: { ...archivedTheme } })
})

describe('CMP-005 即時復元とUndo', () => {
  it.each([
    ['folder-before-restore'],
    [null],
  ])('掲示板を確認なしで復元し、Undoで元のフォルダ(%s)へ戻す', async (originalFolderId) => {
    getArchiveThreads.mockResolvedValue({
      data: [{ ...thread, archiveFolderId: originalFolderId }],
      meta: { totalPages: 1, page: 0 },
    })
    const wrapper = await mountSuspended(BulletinArchiveView, {
      props: { scopeType: 'TEAM', scopeId: 7, canManage: true },
    })
    await flush()

    await wrapper.get('[data-testid="bulletin-unarchive-101"]').trigger('click')
    await flush()

    expect(confirmRequire).not.toHaveBeenCalled()
    expect(archiveScopedThread).toHaveBeenNthCalledWith(1, 'TEAM', 7, 101, false)
    expect(showUndoToast).toHaveBeenCalledTimes(1)

    const undo = showUndoToast.mock.calls[0]![0].onUndo as () => Promise<void>
    await undo()

    expect(archiveScopedThread).toHaveBeenNthCalledWith(2, 'TEAM', 7, 101, true, originalFolderId)
  })

  it('掲示板の復元失敗時はUndoを表示しない', async () => {
    archiveScopedThread.mockRejectedValueOnce(new Error('restore failed'))
    const wrapper = await mountSuspended(BulletinArchiveView, {
      props: { scopeType: 'TEAM', scopeId: 7, canManage: true },
    })
    await flush()

    await wrapper.get('[data-testid="bulletin-unarchive-101"]').trigger('click')
    await flush()

    expect(showUndoToast).not.toHaveBeenCalled()
    expect(showError).toHaveBeenCalled()
  })

  it('掲示板の復元ボタンを処理中に連打してもリクエストは一度だけ送る', async () => {
    let finishRestore!: (value: { data: Record<string, never> }) => void
    archiveScopedThread.mockImplementationOnce(() => new Promise((resolve) => {
      finishRestore = resolve
    }))
    const wrapper = await mountSuspended(BulletinArchiveView, {
      props: { scopeType: 'TEAM', scopeId: 7, canManage: true },
    })
    await flush()

    const restoreButton = wrapper.get('[data-testid="bulletin-unarchive-101"]')
    await restoreButton.trigger('click')
    await restoreButton.trigger('click')

    expect(archiveScopedThread).toHaveBeenCalledTimes(1)
    finishRestore({ data: {} })
    await flush()
  })

  it('振り返り詳細を確認なしで復元し、Undoで再アーカイブする', async () => {
    const wrapper = await mountSuspended(ThemeDetailPage)
    await flush()

    await wrapper.get('[data-testid="reflection-theme-restore"]').trigger('click')
    await flush()

    expect(confirmRequire).not.toHaveBeenCalled()
    expect(restoreTheme).toHaveBeenCalledWith('theme-1')
    expect(showUndoToast).toHaveBeenCalledTimes(1)

    const undo = showUndoToast.mock.calls[0]![0].onUndo as () => Promise<void>
    await undo()
    expect(archiveTheme).toHaveBeenCalledWith('theme-1')
  })

  it('振り返り詳細のアーカイブ操作は確認を維持する', async () => {
    getTheme.mockResolvedValue({ data: { ...archivedTheme, archivedAt: null } })
    const wrapper = await mountSuspended(ThemeDetailPage)
    await flush()

    await wrapper.get('[data-testid="reflection-theme-archive"]').trigger('click')

    expect(confirmRequire).toHaveBeenCalledTimes(1)
    expect(archiveTheme).not.toHaveBeenCalled()
  })

  it('振り返り保管庫を確認なしで復元し、Undoで再アーカイブする', async () => {
    const wrapper = await mountSuspended(ReflectionArchivePage)
    await flush()

    await wrapper.get('[data-testid="reflection-archive-restore-theme-1"]').trigger('click')
    await flush()

    expect(confirmRequire).not.toHaveBeenCalled()
    expect(restoreTheme).toHaveBeenCalledWith('theme-1')
    expect(showUndoToast).toHaveBeenCalledTimes(1)

    const undo = showUndoToast.mock.calls[0]![0].onUndo as () => Promise<void>
    await undo()
    expect(archiveTheme).toHaveBeenCalledWith('theme-1')
  })

  it('振り返り保管庫の復元失敗時はUndoを表示しない', async () => {
    restoreTheme.mockRejectedValueOnce(new Error('restore failed'))
    const wrapper = await mountSuspended(ReflectionArchivePage)
    await flush()

    await wrapper.get('[data-testid="reflection-archive-restore-theme-1"]').trigger('click')
    await flush()

    expect(showUndoToast).not.toHaveBeenCalled()
    expect(showError).toHaveBeenCalled()
  })
})
