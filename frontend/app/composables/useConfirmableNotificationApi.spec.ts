import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useConfirmableNotificationApi } from './useConfirmableNotificationApi'

const api = vi.fn()

mockNuxtImport('useApi', () => () => api)

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

  it('previews the same target selection before sending', async () => {
    const client = useConfirmableNotificationApi()
    const body = { targets: [{ type: 'ORGANIZATION' as const, id: 42 }, { type: 'TEAM' as const, id: 3 }] }

    await client.previewRecipients('ORGANIZATION', '42', body)

    expect(api).toHaveBeenCalledWith('/api/v1/organizations/42/confirmable-notifications/recipient-preview', { method: 'POST', body })
  })

  it('passes a template default recipient group through create and update', async () => {
    const client = useConfirmableNotificationApi()
    const body = { name: '連絡', title: '確認', defaultPriority: 'NORMAL' as const, defaultRecipientGroupId: 'group-1' }

    await client.createTemplate('TEAM', '3', body)
    await client.updateTemplate('TEAM', '3', 7, { ...body, defaultRecipientGroupId: null })

    expect(api).toHaveBeenCalledWith('/api/v1/teams/3/confirmable-notification-templates', { method: 'POST', body })
    expect(api).toHaveBeenCalledWith('/api/v1/teams/3/confirmable-notification-templates/7', { method: 'PUT', body: { ...body, defaultRecipientGroupId: null } })
  })
})
