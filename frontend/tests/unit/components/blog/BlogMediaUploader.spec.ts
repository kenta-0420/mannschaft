import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import BlogMediaUploader from '~/components/blog/BlogMediaUploader.vue'

/**
 * AC-B1: BlogMediaUploader が記事本文へ挿入するのは **r2Key**（`blog/...`）であること。
 *
 * 背景: 従来は公開ベース URL `config.public.r2PublicUrl` を連結して絶対 URL を挿入していたが、
 * この設定は nuxt.config に一度も宣言されたことがない。null ガードも無いため空文字へ
 * フォールバックし、`/blog/TEAM/12/xxx.png` という先頭スラッシュ付きの壊れた相対パスが
 * そのまま記事本文へ永続保存されていた。
 *
 * マスター御裁可により配信は署名 URL（presigned URL）へ統一する。署名 URL には有効期限が
 * あるため本文へ URL を焼き込むことはできない。よって **本文には r2Key を保存し、
 * 記事取得時に BE の MediaUrlResolver で署名 URL へ解決する**（案A）。
 *
 * 本テストはその入口側の契約を固定する。
 */

const uploadImage = vi.fn()
const uploadVideo = vi.fn()
const handleApiError = vi.fn()

vi.mock('~/composables/useBlogMediaApi', () => ({
  useBlogMediaApi: () => ({ uploadImage, uploadVideo }),
}))

vi.mock('~/composables/useErrorHandler', () => ({
  useErrorHandler: () => ({ handleApiError }),
}))

const IMAGE_KEY = 'blog/TEAM/12/aaaaaaaa-1111.png'
const VIDEO_KEY = 'blog/TEAM/12/bbbbbbbb-2222.mp4'

async function mountUploader() {
  return await mountSuspended(BlogMediaUploader, {
    props: { scopeType: 'TEAM', scopeId: '12', blogPostId: 500 },
  })
}

/** jsdom では DataTransfer が使えないため files を直接生やして change を発火する。 */
async function selectFile(input: HTMLInputElement, file: File, wrapperInput: {
  trigger: (e: string) => Promise<void>
}) {
  Object.defineProperty(input, 'files', { value: [file], configurable: true })
  await wrapperInput.trigger('change')
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('BlogMediaUploader — 本文へ挿入するのは r2Key であること（AC-B1）', () => {
  it('AC-B1-1: 画像挿入は ![name](blog/...) 形式の r2Key を emit する', async () => {
    uploadImage.mockResolvedValue({ fileKey: IMAGE_KEY })
    const wrapper = await mountUploader()

    const input = wrapper.find('input[accept="image/*"]')
    const file = new File(['dummy'], 'photo.png', { type: 'image/png' })
    await selectFile(input.element as HTMLInputElement, file, input)
    await wrapper.vm.$nextTick()

    const emitted = wrapper.emitted('inserted')
    expect(emitted).toBeTruthy()
    expect(emitted![0]![0]).toBe(`![photo.png](${IMAGE_KEY})`)
  })

  it('AC-B1-2: 動画挿入は <video src="blog/..."> 形式の r2Key を emit する', async () => {
    uploadVideo.mockResolvedValue({ fileKey: VIDEO_KEY })
    const wrapper = await mountUploader()

    const input = wrapper.find('input[accept="video/mp4,video/webm"]')
    const file = new File(['dummy'], 'clip.mp4', { type: 'video/mp4' })
    await selectFile(input.element as HTMLInputElement, file, input)
    await wrapper.vm.$nextTick()

    const emitted = wrapper.emitted('inserted')
    expect(emitted).toBeTruthy()
    expect(emitted![0]![0]).toBe(`<video src="${VIDEO_KEY}" controls></video>`)
  })

  it('AC-B1-3: 挿入文字列に先頭スラッシュ付きの絶対パスが混入しない', async () => {
    uploadImage.mockResolvedValue({ fileKey: IMAGE_KEY })
    const wrapper = await mountUploader()

    const input = wrapper.find('input[accept="image/*"]')
    const file = new File(['dummy'], 'photo.png', { type: 'image/png' })
    await selectFile(input.element as HTMLInputElement, file, input)
    await wrapper.vm.$nextTick()

    const markdown = wrapper.emitted('inserted')![0]![0] as string
    // 壊れた形（r2PublicUrl 未宣言 → 空文字連結）の再発防止
    expect(markdown).not.toContain('](/blog/')
    expect(markdown).not.toContain('(/blog/')
  })

  it('AC-B1-4: 挿入文字列に http(s) 絶対URLが混入しない（署名URLは焼き込まない）', async () => {
    uploadImage.mockResolvedValue({ fileKey: IMAGE_KEY })
    const wrapper = await mountUploader()

    const input = wrapper.find('input[accept="image/*"]')
    const file = new File(['dummy'], 'photo.png', { type: 'image/png' })
    await selectFile(input.element as HTMLInputElement, file, input)
    await wrapper.vm.$nextTick()

    const markdown = wrapper.emitted('inserted')![0]![0] as string
    // 署名URLには有効期限があるため、本文へ焼き込むと必ず期限切れで壊れる
    expect(markdown).not.toMatch(/https?:\/\//)
  })
})
