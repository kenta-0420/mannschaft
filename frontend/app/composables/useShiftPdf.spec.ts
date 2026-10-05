import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useShiftPdf } from './useShiftPdf'

const mockDownloadShiftPdf = vi.fn()
const mockShowError = vi.fn()

mockNuxtImport('useShiftApi', () => () => ({ downloadShiftPdf: mockDownloadShiftPdf }))
mockNuxtImport('useNotification', () => () => ({ showError: mockShowError }))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))

describe('useShiftPdf.download', () => {
  beforeEach(() => {
    mockDownloadShiftPdf.mockReset()
    mockShowError.mockReset()
  })

  it('PDF 取得に失敗したらエラートーストを表示し、error を設定する（握りつぶさない）', async () => {
    mockDownloadShiftPdf.mockRejectedValue(new Error('401'))
    const { download, error, isDownloading } = useShiftPdf()

    await download(399, 'team')

    expect(mockDownloadShiftPdf).toHaveBeenCalledWith(399, 'team')
    expect(mockShowError).toHaveBeenCalledWith('shift.pdf.error')
    expect(error.value).toBe('shift.pdf.error')
    expect(isDownloading.value).toBe(false)
  })
})
