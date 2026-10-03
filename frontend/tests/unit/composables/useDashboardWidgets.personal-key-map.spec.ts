// @vitest-environment node
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(
  resolve(process.cwd(), 'app/composables/useDashboardWidgets.ts'),
  'utf8',
)

describe('personal dashboard widget key map', () => {
  it.each([
    ['recruitment-feed', 'RECRUITMENT_FEED'],
    ['my-recruitments', 'MY_RECRUITMENTS'],
    ['my-corkboard', 'MY_CORKBOARD'],
    ['village-lobby-digest', 'VILLAGE_LOBBY_DIGEST'],
  ] as const)('%s is persisted as %s', (frontendKey, backendKey) => {
    expect(source).toContain(`'${frontendKey}': { personal: '${backendKey}' }`)
  })
})
