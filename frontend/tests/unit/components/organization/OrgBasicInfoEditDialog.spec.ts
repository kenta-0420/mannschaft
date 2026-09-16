import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import type { OrgDetail } from '~/composables/useOrgDetail'

/**
 * CMP-260907-0852 OrgBasicInfoEditDialog.vue のユニットテスト。
 *
 * <p>「基本情報」タブに表示している組織名・所在地を、その場で編集できるようになったこと、
 * および BE `UpdateOrganizationRequest` が受けるフィールド名でだけ PATCH することを主観点とする。</p>
 *
 * テストケース一覧:
 *  ORG-BIE-001: 保存時に BE DTO と同じフィールド名（version 含む）で updateOrganization を呼ぶ
 *  ORG-BIE-002: 組織名が空のとき保存せずエラーを出す
 *  ORG-BIE-003: version 未取得のとき PATCH を投げない（@NotNull 違反の 400 を作らない）
 */

const updateOrganizationMock = vi.fn()
vi.mock('~/composables/useOrganizationApi', () => ({
  useOrganizationApi: () => ({ updateOrganization: updateOrganizationMock }),
}))

const getPrefecturesMock = vi.fn()
const getCitiesMock = vi.fn()
vi.mock('~/composables/useMatchingApi', () => ({
  useMatchingApi: () => ({
    getPrefectures: getPrefecturesMock,
    getCities: getCitiesMock,
  }),
}))

const notificationMock = { success: vi.fn(), error: vi.fn(), info: vi.fn(), warn: vi.fn() }
vi.mock('~/composables/useNotification', () => ({
  useNotification: () => notificationMock,
}))

const handleApiErrorMock = vi.fn()
vi.mock('~/composables/useErrorHandler', () => ({
  useErrorHandler: () => ({ handleApiError: handleApiErrorMock, getFieldErrors: () => ({}) }),
}))

const OrgBasicInfoEditDialog = (
  await import('~/components/organization/OrgBasicInfoEditDialog.vue')
).default

function createOrg(overrides: Partial<OrgDetail> = {}): OrgDetail {
  return {
    id: 'my-org',
    basicInfo: {
      name: '町内会',
      nameKana: 'チョウナイカイ',
      nickname1: 'ちょう',
      nickname2: '',
    },
    location: { prefecture: '東京都', city: '千代田区' },
    metadata: { version: 3 },
    ...overrides,
  }
}

async function mountDialog(org: OrgDetail) {
  const wrapper = await mountSuspended(OrgBasicInfoEditDialog, {
    props: { orgId: 'my-org', org, visible: true },
  })
  // 初期プリフィル（都道府県マスタ取得を伴う非同期 watch）の完了を待つ。
  await new Promise(resolve => setTimeout(resolve, 0))
  return wrapper
}

describe('OrgBasicInfoEditDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getPrefecturesMock.mockResolvedValue({ data: [{ code: '13', name: '東京都' }] })
    getCitiesMock.mockResolvedValue({ data: [{ code: '13101', name: '千代田区' }] })
    updateOrganizationMock.mockResolvedValue({ data: {} })
  })

  it('ORG-BIE-001: BE DTO と同じフィールド名で updateOrganization を呼ぶ', async () => {
    const wrapper = await mountDialog(createOrg())
    const vm = wrapper.vm as unknown as { handleSave: () => Promise<void> }
    await vm.handleSave()

    expect(updateOrganizationMock).toHaveBeenCalledTimes(1)
    const [slug, body] = updateOrganizationMock.mock.calls[0] as [string, Record<string, unknown>]
    expect(slug).toBe('my-org')
    expect(Object.keys(body).sort()).toEqual(
      ['city', 'name', 'nameKana', 'nickname1', 'nickname2', 'prefecture', 'version'],
    )
    expect(body.name).toBe('町内会')
    expect(body.prefecture).toBe('東京都')
    expect(body.city).toBe('千代田区')
    expect(body.version).toBe(3)
  })

  it('ORG-BIE-002: 組織名が空なら保存しない', async () => {
    const org = createOrg({ basicInfo: { name: '' } })
    const wrapper = await mountDialog(org)
    const vm = wrapper.vm as unknown as { handleSave: () => Promise<void> }
    await vm.handleSave()

    expect(updateOrganizationMock).not.toHaveBeenCalled()
  })

  it('ORG-BIE-003: version 未取得なら PATCH を投げない', async () => {
    const org = createOrg({ metadata: {} })
    const wrapper = await mountDialog(org)
    const vm = wrapper.vm as unknown as { handleSave: () => Promise<void> }
    await vm.handleSave()

    expect(updateOrganizationMock).not.toHaveBeenCalled()
    expect(handleApiErrorMock).toHaveBeenCalled()
  })
})
