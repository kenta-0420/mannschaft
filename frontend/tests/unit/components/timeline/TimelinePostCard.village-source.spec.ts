// @vitest-environment node
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(
  resolve(process.cwd(), 'app/components/timeline/TimelinePostCard.vue'),
  'utf8',
)

describe('TimelinePostCard 村投稿元バッジ契約', () => {
  it('VILLAGE の scopeVillageId を村ページへの遷移先に使う', () => {
    expect(source).toContain("scope.scopeType === 'VILLAGE' && scope.scopeVillageId")
    expect(source).toContain('`/villages/${scope.scopeVillageId}`')
  })
})
