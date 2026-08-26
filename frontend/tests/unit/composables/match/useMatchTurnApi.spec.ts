import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'

/**
 * F08.10 6-④c useMatchTurnApi ユニットテスト（ターン制 API 配線）。
 *
 * 検証観点:
 *   PAYLOAD-001: buildTurnResultPayload — 勝者あり時は winMethod/totalMoves を載せる
 *   PAYLOAD-002: buildTurnResultPayload — 引分時は winMethod を必ず落とす（🟡 MATCH_028 整合）
 *   IMG-001: isUploadableImage — 画像は OK・SVG/非画像は NG
 *   TURN-API-001: recordResult → PUT .../result に payload
 *   TURN-API-002: createBoard → POST .../boards に payload
 *   TURN-API-003: listBoards → GET .../boards で data を返す
 *   TURN-API-004: presign → confirm → downloadUrl の 3 段（uploadPositionPhoto）
 *   TURN-API-005: uploadPositionPhoto は SVG を事前に弾く（presign を呼ばない）
 *   TURN-API-006: uploadPositionPhoto は 10MB 超を事前に弾く
 *   TURN-API-007: 失敗時は notification.error を呼び再 throw する
 */

const mockFetch = vi.fn()
const mockError = vi.fn()
const mockOfetch = vi.fn()

vi.mock('~/composables/useApi', () => ({
  useApi: () => mockFetch,
}))
vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({ error: mockError, success: vi.fn(), info: vi.fn(), warn: vi.fn() }),
}))
vi.mock('ofetch', () => ({
  ofetch: (...args: unknown[]) => mockOfetch(...args),
}))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))

// eslint-disable-next-line import/first
import {
  useMatchTurnApi,
  buildTurnResultPayload,
  isUploadableImage,
  MATCH_ATTACHMENT_MAX_BYTES,
} from '~/composables/match/useMatchTurnApi'

const ORG = 7
const MATCH = 'm-uuid-1'

/** 指定 type/size の File モック（jsdom の File は size を実体長依存にするため明示する）。 */
function fakeFile(name: string, type: string, size: number): File {
  const f = new File(['x'], name, { type })
  Object.defineProperty(f, 'size', { value: size })
  return f
}

describe('buildTurnResultPayload（🟡 引分時 winMethod 整合）', () => {
  it('PAYLOAD-001: 勝者あり → winnerSide/winMethod/totalMoves をそのまま載せる', () => {
    expect(buildTurnResultPayload('HOME', 'RESIGNATION', 87)).toEqual({
      winnerSide: 'HOME',
      winMethod: 'RESIGNATION',
      totalMoves: 87,
    })
  })

  it('PAYLOAD-001b: 勝者あり + winMethod 未入力 → winMethod=null', () => {
    expect(buildTurnResultPayload('AWAY', null, null)).toEqual({
      winnerSide: 'AWAY',
      winMethod: null,
      totalMoves: null,
    })
  })

  it('PAYLOAD-002: 引分（null）→ winMethod は必ず落とす（REPETITION 指定でも null）', () => {
    expect(buildTurnResultPayload(null, 'REPETITION', 120)).toEqual({
      winnerSide: null,
      winMethod: null,
      totalMoves: 120,
    })
  })
})

describe('isUploadableImage（SVG 除外・事前ガード）', () => {
  it('IMG-001a: image/png は OK', () => {
    expect(isUploadableImage(fakeFile('a.png', 'image/png', 100))).toBe(true)
  })
  it('IMG-001b: image/svg+xml は NG', () => {
    expect(isUploadableImage(fakeFile('a.svg', 'image/svg+xml', 100))).toBe(false)
  })
  it('IMG-001c: 非画像（application/pdf）は NG', () => {
    expect(isUploadableImage(fakeFile('a.pdf', 'application/pdf', 100))).toBe(false)
  })
})

describe('useMatchTurnApi', () => {
  beforeEach(() => {
    mockFetch.mockReset()
    mockError.mockReset()
    mockOfetch.mockReset()
  })

  it('TURN-API-001: recordResult は PUT .../result に payload を渡す', async () => {
    mockFetch.mockResolvedValueOnce({ data: { id: MATCH, homeScore: 1, awayScore: 0 } })
    const api = useMatchTurnApi()
    const payload = { winnerSide: 'HOME' as const, winMethod: 'RESIGNATION', totalMoves: 99 }
    const res = await api.recordResult(ORG, MATCH, payload)

    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/organizations/${ORG}/matches/${MATCH}/result`,
      { method: 'PUT', body: payload },
    )
    expect(res.homeScore).toBe(1)
  })

  it('TURN-API-002: createBoard は POST .../boards に payload を渡す', async () => {
    mockFetch.mockResolvedValueOnce({ data: { id: 'b1', boardNumber: 1 } })
    const api = useMatchTurnApi()
    const payload = { boardNumber: 1, opponentName: '相手' }
    await api.createBoard(ORG, MATCH, payload)

    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/organizations/${ORG}/matches/${MATCH}/boards`,
      { method: 'POST', body: payload },
    )
  })

  it('TURN-API-003: listBoards は GET .../boards で data を返す', async () => {
    mockFetch.mockResolvedValueOnce({ data: [{ id: 'b1', boardNumber: 1 }] })
    const api = useMatchTurnApi()
    const res = await api.listBoards(ORG, MATCH)

    expect(mockFetch).toHaveBeenCalledWith(`/api/v1/organizations/${ORG}/matches/${MATCH}/boards`)
    expect(res).toHaveLength(1)
  })

  it('TURN-API-004: uploadPositionPhoto は presign → PUT → confirm → downloadUrl の 3 段', async () => {
    // (1) presign
    mockFetch.mockResolvedValueOnce({
      data: { uploadUrl: 'https://storage/put', fileKey: 'key-1', expiresInSeconds: 600 },
    })
    // (3) confirm
    mockFetch.mockResolvedValueOnce({
      data: { id: 'att-1', matchId: MATCH, originalFilename: 'p.png' },
    })
    // (4) downloadUrl
    mockFetch.mockResolvedValueOnce({
      data: { downloadUrl: 'https://storage/get', expiresInSeconds: 300 },
    })
    mockOfetch.mockResolvedValueOnce(undefined)

    const api = useMatchTurnApi()
    const file = fakeFile('p.png', 'image/png', 1000)
    const { attachment, displayUrl } = await api.uploadPositionPhoto(ORG, MATCH, file)

    // presign
    expect(mockFetch).toHaveBeenNthCalledWith(
      1,
      `/api/v1/organizations/${ORG}/matches/${MATCH}/attachments/presign`,
      { method: 'POST', body: { contentType: 'image/png', fileSize: 1000 } },
    )
    // ストレージ直 PUT（Content-Type 一致）
    expect(mockOfetch).toHaveBeenCalledWith('https://storage/put', {
      method: 'PUT',
      body: file,
      headers: { 'Content-Type': 'image/png' },
    })
    // confirm
    expect(mockFetch).toHaveBeenNthCalledWith(
      2,
      `/api/v1/organizations/${ORG}/matches/${MATCH}/attachments`,
      {
        method: 'POST',
        body: { fileKey: 'key-1', originalFilename: 'p.png', contentType: 'image/png', fileSize: 1000 },
      },
    )
    expect(attachment.id).toBe('att-1')
    expect(displayUrl).toBe('https://storage/get')
  })

  it('TURN-API-005: uploadPositionPhoto は SVG を事前に弾き presign を呼ばない', async () => {
    const api = useMatchTurnApi()
    const svg = fakeFile('a.svg', 'image/svg+xml', 100)
    await expect(api.uploadPositionPhoto(ORG, MATCH, svg)).rejects.toThrow()
    expect(mockFetch).not.toHaveBeenCalled()
    expect(mockError).toHaveBeenCalledWith('match.turn.error.attachment_type_invalid')
  })

  it('TURN-API-006: uploadPositionPhoto は 10MB 超を事前に弾く', async () => {
    const api = useMatchTurnApi()
    const big = fakeFile('big.png', 'image/png', MATCH_ATTACHMENT_MAX_BYTES + 1)
    await expect(api.uploadPositionPhoto(ORG, MATCH, big)).rejects.toThrow()
    expect(mockFetch).not.toHaveBeenCalled()
    expect(mockError).toHaveBeenCalledWith('match.turn.error.attachment_too_large')
  })

  it('TURN-API-007: recordResult 失敗時は notification.error を呼び再 throw する', async () => {
    mockFetch.mockRejectedValueOnce(new Error('boom'))
    const api = useMatchTurnApi()

    await expect(
      api.recordResult(ORG, MATCH, { winnerSide: 'HOME', winMethod: null, totalMoves: null }),
    ).rejects.toThrow('boom')
    expect(mockError).toHaveBeenCalledWith('match.turn.error.record_result_failed')
  })
})
