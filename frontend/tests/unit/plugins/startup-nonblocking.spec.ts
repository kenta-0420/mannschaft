// @vitest-environment node
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const appRoot = resolve(process.cwd(), 'app')

describe('起動時のバックグラウンド同期', () => {
  for (const plugin of [
    'scope-dashboard.client.ts',
    'feature-flags.client.ts',
    'nav-settings.client.ts',
  ]) {
    it(`${plugin} は未応答APIをawaitしない`, () => {
      const source = readFileSync(resolve(appRoot, 'plugins', plugin), 'utf8')
      expect(source).not.toMatch(/defineNuxtPlugin\(async/)
    })
  }

  it('個人パネルは未使用の全体取得を描画条件にしない', () => {
    const source = readFileSync(
      resolve(appRoot, 'components/dashboard/DashboardPersonalPanel.vue'),
      'utf8',
    )
    expect(source).not.toContain('fetchPersonalDashboard')
    expect(source).not.toContain('<PageLoading v-if="loading"')
  })
})
