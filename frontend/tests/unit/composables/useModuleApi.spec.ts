// @vitest-environment node
import { describe, it, expect, beforeEach, vi } from 'vitest'

/**
 * useModuleApi.toggleTeamModule のユニットテスト（CMP-260911-0110）。
 *
 * BE `TeamModuleController#toggleTeamModule` は `@Valid @RequestBody ToggleModuleRequest`
 * （moduleId / enabled）を要求する。本文なしで PATCH すると必ず 400 になるため、
 * 本文 `{ moduleId, enabled }` を送ることを固定する。
 */
const { mockApi } = vi.hoisted(() => ({ mockApi: vi.fn() }))
vi.mock('~/composables/useApi', () => ({ useApi: () => mockApi }))
vi.stubGlobal('useApi', () => mockApi)

const { useModuleApi } = await import('~/composables/useModuleApi')

describe('useModuleApi.toggleTeamModule', () => {
  beforeEach(() => {
    mockApi.mockReset()
    mockApi.mockResolvedValue(undefined)
  })

  it('有効化: PATCH で { moduleId, enabled: true } を本文に送る', async () => {
    await useModuleApi().toggleTeamModule('my-team', 7, true)
    expect(mockApi).toHaveBeenCalledWith('/api/v1/teams/my-team/modules/7/toggle', {
      method: 'PATCH',
      body: { moduleId: 7, enabled: true },
    })
  })

  it('無効化: PATCH で { moduleId, enabled: false } を本文に送る', async () => {
    await useModuleApi().toggleTeamModule('my-team', 7, false)
    expect(mockApi).toHaveBeenCalledWith('/api/v1/teams/my-team/modules/7/toggle', {
      method: 'PATCH',
      body: { moduleId: 7, enabled: false },
    })
  })
})
