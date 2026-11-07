// @vitest-environment node
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * CMP-260916-0054: BE に単数形 `/api/v1/circulation` のコントローラは存在しない。
 * 死んだ単数形関数が再び入り込まないことを、ソースのパス文字列で保証する。
 * 正しいパスは複数形 `/api/v1/circulations/...` / `circulation-documents` のみ。
 */
const source = readFileSync(
  resolve(__dirname, '../../../app/composables/useCirculationApi.ts'),
  'utf-8',
)

describe('useCirculationApi のパス', () => {
  it('単数形 /api/v1/circulation（直後が s / - 以外）を叩かない', () => {
    const singular = source.match(/\/api\/v1\/circulation(?![s-])/g)
    expect(singular).toBeNull()
  })

  it('死んだ単数形関数群を公開しない', () => {
    const dead = [
      'getCirculations', 'getCirculation', 'createCirculation', 'updateCirculation',
      'deleteCirculation', 'startCirculation', 'cancelCirculation', 'forceComplete',
      'correctStamp', 'skipRecipient', 'sendReminder', 'getStampStatus', 'exportPdf',
      'getMyCirculations',
    ]
    for (const name of dead) {
      expect(source).not.toMatch(new RegExp(`function ${name}\\b`))
    }
  })
})
