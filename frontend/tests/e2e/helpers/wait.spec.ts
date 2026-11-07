// @vitest-environment node
import type { JSHandle, Page } from '@playwright/test'
import { describe, expect, it, vi } from 'vitest'
import { waitForHydration } from './wait'

function createPage() {
  const waitForFunction = vi.fn<Page['waitForFunction']>()
  const reload = vi.fn<Page['reload']>().mockResolvedValue(null)
  const page = { waitForFunction, reload } as unknown as Page

  return { page, waitForFunction, reload }
}

const hydrated = {} as JSHandle<boolean>

describe('waitForHydration', () => {
  it('再読込を無効にすると75秒の単発待機で遅いマウントと一時状態を保持する', async () => {
    const { page, waitForFunction, reload } = createPage()
    let hasVolatileState = true
    reload.mockImplementation(async () => {
      hasVolatileState = false
      return null
    })
    waitForFunction.mockImplementation(async (_predicate, _arg, options) => {
      if ((options?.timeout ?? 0) < 25_000 || !hasVolatileState) {
        throw new Error('マウント前または一時状態の消失')
      }
      return hydrated
    })

    await waitForHydration(page, { reloadOnTimeout: false })

    expect(hasVolatileState).toBe(true)
    expect(reload).not.toHaveBeenCalled()
    expect(waitForFunction).toHaveBeenCalledExactlyOnceWith(expect.any(Function), undefined, {
      timeout: 75_000,
    })
  })

  it('既定では15秒以内に成功すると再読込しない', async () => {
    const { page, waitForFunction, reload } = createPage()
    waitForFunction.mockResolvedValue(hydrated)

    await waitForHydration(page)

    expect(reload).not.toHaveBeenCalled()
    expect(waitForFunction).toHaveBeenCalledExactlyOnceWith(expect.any(Function), undefined, {
      timeout: 15_000,
    })
  })

  it('既定では初回失敗時に再読込して60秒待機で復旧する', async () => {
    const { page, waitForFunction, reload } = createPage()
    waitForFunction.mockRejectedValueOnce(new Error('初回のタイムアウト'))
    waitForFunction.mockResolvedValueOnce(hydrated)

    await waitForHydration(page)

    expect(reload).toHaveBeenCalledExactlyOnceWith({ waitUntil: 'domcontentloaded' })
    expect(waitForFunction).toHaveBeenCalledTimes(2)
    expect(waitForFunction).toHaveBeenNthCalledWith(1, expect.any(Function), undefined, {
      timeout: 15_000,
    })
    expect(waitForFunction).toHaveBeenNthCalledWith(2, expect.any(Function), undefined, {
      timeout: 60_000,
    })
  })

  it('再読込を無効にした待機のエラーをそのまま伝播する', async () => {
    const { page, waitForFunction, reload } = createPage()
    const error = new Error('75秒待機の失敗')
    waitForFunction.mockRejectedValue(error)

    await expect(waitForHydration(page, { reloadOnTimeout: false })).rejects.toBe(error)

    expect(reload).not.toHaveBeenCalled()
    expect(waitForFunction).toHaveBeenCalledTimes(1)
  })

  it('既定の再読込後の最終エラーをそのまま伝播する', async () => {
    const { page, waitForFunction, reload } = createPage()
    const error = new Error('再試行の失敗')
    waitForFunction.mockRejectedValueOnce(new Error('初回のタイムアウト'))
    waitForFunction.mockRejectedValueOnce(error)

    await expect(waitForHydration(page)).rejects.toBe(error)

    expect(reload).toHaveBeenCalledTimes(1)
    expect(waitForFunction).toHaveBeenCalledTimes(2)
  })
})
