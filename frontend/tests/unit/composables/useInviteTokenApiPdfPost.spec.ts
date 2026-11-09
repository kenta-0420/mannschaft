/**
 * 試練（CMP-260826-1457 W1 P1・AC-11）: 招待 QR-PDF は発行直後の平文を POST して取得する。
 *
 * 実装側が満たすべき契約（BE の仮契約と同じ）:
 * - `downloadInviteTokenPdf(scopeType, scopeId, plaintext: string)` は
 *   POST `${apiBase}/api/v1/teams/${scopeId}/invite-tokens/pdf`、body `{ token: plaintext }`、`responseType: 'blob'`。
 * - URL に平文も tokenId も載せない（平文は body にだけ載る）。
 *
 * red の理由: いまの composable は tokenId を URL に載せて GET `/invite-tokens/{tokenId}/pdf` を呼ぶ。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useInviteTokenApi } from '~/composables/useInviteTokenApi'

mockNuxtImport('useAuthStore', () => () => ({ accessToken: 'test-access-token', loadFromStorage: vi.fn() }))

const PLAINTEXT = 'Zr3x9QpL0a7_bN2-kW5yT8uE1oI4sD6fG0hJ9lK2mNc'

/** 実装後のシグネチャ（第3引数が平文の string）で呼ぶための型。 */
interface PlaintextPdfApi {
  downloadInviteTokenPdf: (scopeType: 'team' | 'organization', scopeId: string, plaintext: string) => Promise<Blob>
}

const fetchMock = vi.fn()

beforeEach(() => {
  fetchMock.mockReset()
  fetchMock.mockResolvedValue(new Blob(['%PDF'], { type: 'application/pdf' }))
  vi.stubGlobal('$fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('useInviteTokenApi: QR-PDF は平文を POST する（CMP-260826-1457 W1 P1）', () => {
  it('POST /invite-tokens/pdf に平文を body で渡し、URL には平文を載せない', async () => {
    const api = useInviteTokenApi() as unknown as PlaintextPdfApi
    await api.downloadInviteTokenPdf('team', 'my-team', PLAINTEXT)

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, options] = fetchMock.mock.calls[0] as [string, { method?: string, body?: unknown, responseType?: string }]
    expect(url).toMatch(/\/api\/v1\/teams\/my-team\/invite-tokens\/pdf$/)
    expect(url).not.toContain(PLAINTEXT)
    expect(options?.method).toBe('POST')
    expect(options?.body).toEqual({ token: PLAINTEXT })
    expect(options?.responseType).toBe('blob')
  })
})
