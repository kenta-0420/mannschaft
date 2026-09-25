import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useConfirmableNotificationApi } from './useConfirmableNotificationApi'

const api = vi.fn()

vi.stubGlobal('useApi', () => api)

describe('useConfirmableNotificationApi', () => {
  beforeEach(() => api.mockReset())

  it('targets the paged recipients endpoint with pagination parameters', async () => {
    const client = useConfirmableNotificationApi()

    await client.getRecipients('ORGANIZATION', '42', 9, { page: 2, size: 50, unconfirmedOnly: true })

    expect(api).toHaveBeenCalledWith(
      '/api/v1/organizations/42/confirmable-notifications/9/recipients/page?page=2&size=50&unconfirmedOnly=true',
    )
  })

  it('sends target-based requests and preserves the accepted response contract', async () => {
    const client = useConfirmableNotificationApi()
    const body = { title: '確認してください', priority: 'NORMAL' as const, targets: [{ type: 'TEAM' as const, id: 3 }] }

    await client.sendNotification('TEAM', '3', body)

    expect(api).toHaveBeenCalledWith('/api/v1/teams/3/confirmable-notifications', { method: 'POST', body })
  })

  it('uses the recipient-group CRUD endpoint', async () => {
    const client = useConfirmableNotificationApi()

    await client.createRecipientGroup('TEAM', '3', { name: 'スタッフ', targets: [{ type: 'TEAM', id: 3 }] })

    expect(api).toHaveBeenCalledWith('/api/v1/teams/3/confirmable-recipient-groups', expect.objectContaining({ method: 'POST' }))
  })
})
