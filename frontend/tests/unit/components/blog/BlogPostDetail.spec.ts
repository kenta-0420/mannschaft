import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { setActivePinia, createPinia } from 'pinia'
import BlogPostDetail from '~/components/blog/BlogPostDetail.vue'
import type { BlogPostResponse } from '~/types/cms'

// happy-dom 環境では DOMPurify が動作しないため、既存 useMarkdownRenderer.spec.ts と同じ簡易モックを使う
vi.mock('dompurify', () => ({
  default: {
    sanitize: (dirty: string) =>
      dirty
        .replace(/<script[^>]*>[\s\S]*?<\/script>/gi, '')
        .replace(/\son\w+\s*=\s*"[^"]*"/gi, ''),
  },
}))

/**
 * BlogPostDetail.vue: 本文 markdown 内の h1 を降格し、見出しに階層スタイル用クラスが掛かること。
 */
function post(body: string): BlogPostResponse {
  return {
    id: 1,
    content: { title: '記事タイトル', body } as BlogPostResponse['content'],
    tags: [],
    seriesId: null,
    seriesName: null,
    seriesOrder: null,
    scheduledAt: null,
  }
}

beforeEach(() => {
  setActivePinia(createPinia())
})

describe('BlogPostDetail.vue', () => {
  it('ページ内の h1 はタイトルの 1 つだけで、本文の # は h2 に降格される', async () => {
    const wrapper = await mountSuspended(BlogPostDetail, {
      props: { post: post('# 本文の大見出し\n\n段落\n\n## 中見出し\n\n### 小見出し') },
    })
    expect(wrapper.findAll('h1')).toHaveLength(1)
    expect(wrapper.find('h1').text()).toBe('記事タイトル')
    const body = wrapper.find('.article-body')
    expect(body.exists()).toBe(true)
    expect(body.find('h1').exists()).toBe(false)
    const h2 = body.findAll('h2').map((h) => h.text())
    expect(h2).toEqual(['本文の大見出し', '中見出し'])
    expect(body.find('h3').text()).toBe('小見出し')
  })

  it('サニタイズ処理は維持され、危険な属性は残らず h1 降格だけが効く', async () => {
    const wrapper = await mountSuspended(BlogPostDetail, {
      props: { post: post('# 見出し\n\n<img src=x onerror="window.__xss=1">') },
    })
    const html = wrapper.find('.article-body').html()
    expect(html).not.toContain('onerror')
    expect(wrapper.find('.article-body h2').text()).toBe('見出し')
  })

  it('本文コンテナは効かない prose ではなく article-body を使う', async () => {
    const wrapper = await mountSuspended(BlogPostDetail, { props: { post: post('本文') } })
    expect(wrapper.find('.article-body').classes()).not.toContain('prose')
  })
})
