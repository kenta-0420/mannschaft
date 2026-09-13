import { describe, expect, it } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import VideoPlayer from '~/components/timeline/VideoPlayer.vue'

describe('Timeline VideoPlayer', () => {
  it('バックエンドが返したACL照合済み署名URLをそのまま再生する', async () => {
    const signedUrl = 'https://r2.example.com/video.mp4?X-Amz-Signature=deadbeef'
    const wrapper = await mountSuspended(VideoPlayer, {
      props: {
        sourceUrl: signedUrl,
        processingStatus: 'COMPLETED',
        mimeType: 'video/mp4',
      },
    })

    expect(wrapper.get('video').attributes('src')).toBe(signedUrl)
    expect(wrapper.html()).not.toContain('/api/r2/')
  })

  it('処理中は動画URLをDOMへ公開しない', async () => {
    const signedUrl = 'https://r2.example.com/video.mp4?X-Amz-Signature=deadbeef'
    const wrapper = await mountSuspended(VideoPlayer, {
      props: {
        sourceUrl: signedUrl,
        processingStatus: 'PROCESSING',
      },
    })

    expect(wrapper.find('video').exists()).toBe(false)
    expect(wrapper.html()).not.toContain(signedUrl)
  })
})
