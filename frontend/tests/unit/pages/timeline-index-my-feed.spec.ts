import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(resolve(process.cwd(), 'app/pages/timeline/index.vue'), 'utf8')

describe('/timeline 個人集約フィード契約', () => {
  it('PUBLIC 投稿フォームを置かず、ダッシュボードと同じ my-feed を使う', () => {
    expect(source).toContain('<TimelineFeed my-feed />')
    expect(source).not.toContain('TimelinePostForm')
    expect(source).not.toContain('scope-type="PUBLIC"')
  })
})
