import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { setActivePinia, createPinia } from 'pinia'
import { useAuthStore } from '~/stores/useAuthStore'
import FileShareLinkDialog from '~/components/filesharing/FileShareLinkDialog.vue'
import type { SharedFile } from '~/types/filesharing'

/**
 * F05.5 (D) FileShareLinkDialog.vue ユニットテスト（Issue #2508 FEオフセット明示）。
 *
 * 背景: BE の CreateLinkRequest.expiresAt は LocalDateTime 宣言で、受信時オフセットを
 * 無視する非対称バグがある。以前は toLocalDateTime() で TZ オフセット無しの壁時計文字列を
 * 送っていたため、useDatetime().buildOffsetDateTimeStr へ差し替えた。
 *
 * 検証観点:
 *   FSL-001: onCreate は expiresAt にユーザーTZ（既定 Asia/Tokyo）のオフセット付き文字列を送る
 *   FSL-002: 非JST（America/Los_Angeles）ユーザーでもそのTZのオフセットが付く
 */

const mockGetFileLinks = vi.fn()
const mockCreateFileLink = vi.fn()
const mockDeleteFileLink = vi.fn()
vi.mock('~/composables/useFileSharingApi', () => ({
  useFileSharingApi: () => ({
    getFileLinks: mockGetFileLinks,
    createFileLink: mockCreateFileLink,
    deleteFileLink: mockDeleteFileLink,
  }),
}))

const mockShowSuccess = vi.fn()
const mockShowError = vi.fn()
vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({
    showSuccess: mockShowSuccess,
    showError: mockShowError,
  }),
}))

function buildFile(overrides: Partial<SharedFile> = {}): SharedFile {
  return {
    id: 42,
    folderId: 1,
    fileName: 'test.pdf',
    originalFileName: 'test.pdf',
    fileSize: 1024,
    mimeType: 'application/pdf',
    description: null,
    uploadedBy: { id: 1, displayName: 'テストユーザー' },
    versionCount: 1,
    currentVersionId: 1,
    tags: [],
    downloadCount: 0,
    createdAt: '2026-05-01T00:00:00+09:00',
    updatedAt: '2026-05-01T00:00:00+09:00',
    ...overrides,
  }
}

/** 指定タイムゾーンで非同期処理を実行する（Node は process.env.TZ の実行時変更を反映する）。 */
async function withSystemTz<T>(tz: string, fn: () => Promise<T>): Promise<T> {
  const original = process.env.TZ
  process.env.TZ = tz
  try {
    return await fn()
  } finally {
    process.env.TZ = original
  }
}

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  mockGetFileLinks.mockResolvedValue({ data: [] })
})

describe('FileShareLinkDialog.vue', () => {
  it('FSL-001: onCreate は expiresAt にユーザーTZ（既定 Asia/Tokyo）のオフセット付き文字列を送る', async () => {
    await withSystemTz('Asia/Tokyo', async () => {
      mockCreateFileLink.mockResolvedValueOnce({ data: {} })
      const wrapper = await mountSuspended(FileShareLinkDialog, {
        props: { file: buildFile(), visible: true },
      })
      const vm = wrapper.vm as unknown as {
        expiresAt: Date | null
        onCreate: () => Promise<void>
      }
      vm.expiresAt = new Date(2026, 5, 10, 15, 0, 0) // 2026-06-10 15:00 JST
      await wrapper.vm.$nextTick()
      await vm.onCreate()

      expect(mockCreateFileLink).toHaveBeenCalledTimes(1)
      const [fileIdArg, bodyArg] = mockCreateFileLink.mock.calls[0] as [number, Record<string, unknown>]
      expect(fileIdArg).toBe(42)
      expect(bodyArg.expiresAt).toBe('2026-06-10T15:00:00+09:00')
    })
  })

  it('FSL-002: 非JST（America/Los_Angeles）ユーザーでも expiresAt にそのTZのオフセットが付く', async () => {
    await withSystemTz('America/Los_Angeles', async () => {
      useAuthStore().user = {
        id: 1,
        email: 'la-user@example.com',
        fullName: 'LA User',
        profileImageUrl: null,
        timezone: 'America/Los_Angeles',
      }
      mockCreateFileLink.mockResolvedValueOnce({ data: {} })
      const wrapper = await mountSuspended(FileShareLinkDialog, {
        props: { file: buildFile(), visible: true },
      })
      const vm = wrapper.vm as unknown as {
        expiresAt: Date | null
        onCreate: () => Promise<void>
      }
      vm.expiresAt = new Date(2026, 5, 10, 15, 0, 0) // 2026-06-10 15:00 PDT
      await wrapper.vm.$nextTick()
      await vm.onCreate()

      const bodyArg = mockCreateFileLink.mock.calls[0]?.[1] as Record<string, unknown>
      expect(bodyArg.expiresAt).toBe('2026-06-10T15:00:00-07:00')
    })
  })
})
