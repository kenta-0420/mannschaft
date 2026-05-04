interface PlatformAnnouncement {
  id: number
  title: string
  content: string
  severity: 'INFO' | 'WARNING' | 'URGENT'
  isPinned: boolean
  publishedAt: string
}

export function useDashboardApi() {
  const api = useApi()

  async function getNotices(params?: { cursor?: string; limit?: number; isRead?: boolean }) {
    const query = new URLSearchParams()
    if (params?.cursor) query.set('cursor', params.cursor)
    if (params?.limit) query.set('limit', String(params.limit))
    if (params?.isRead !== undefined) query.set('is_read', String(params.isRead))
    return api<{
      data: {
        items: Array<{
          id: number
          type: string
          title: string
          body: string | null
          is_read: boolean
          action_url: string | null
          created_at: string
        }>
        meta: { next_cursor: number; limit: number; total_count: number; has_next: boolean }
      }
    }>(`/api/v1/dashboard/notices?${query}`)
  }

  async function getUpcomingEvents(days: number = 7) {
    return api<{
      data: Array<{
        id: number
        title: string
        start_at: string
        end_at: string
        location: string | null
        all_day: boolean
        scope_type: string | null
        scope_name: string | null
        scope_icon_url: string | null
      }>
    }>(`/api/v1/dashboard/upcoming-events?days=${days}`)
  }

  async function getPersonalTodos() {
    return api<{
      data: Array<{
        id: number
        title: string
        status: string
        priority: string
        dueDate: string | null
        scopeType: string
        scopeId: number | null
      }>
    }>('/api/v1/todos/my')
  }

  async function getActivity(params?: { cursor?: string; limit?: number }) {
    const query = new URLSearchParams()
    if (params?.cursor) query.set('cursor', params.cursor)
    query.set('limit', String(params?.limit ?? 10))
    return api<{
      data: Array<{
        id: number
        type: string
        actor: { id: number; displayName: string; avatarUrl: string | null }
        scopeType: string
        scopeId: number
        scopeName: string
        targetType: string
        targetId: number
        summary: string
        createdAt: string
      }>
    }>(`/api/v1/dashboard/activity?${query}`)
  }

  async function getUnreadThreads(limit: number = 10) {
    return api<{
      data: {
        bulletin_threads: unknown[]
        chat_channels: unknown[]
        total_unread_bulletin: number
        total_unread_chat: number
      }
    }>(`/api/v1/dashboard/unread-threads?limit=${limit}`)
  }

  async function getPlatformAnnouncements() {
    return api<{ data: PlatformAnnouncement[] }>('/api/v1/dashboard/announcements')
  }

  async function getCalendarSummary(month: string) {
    return api<{ data: { month: string; eventDays: number[]; totalEvents: number } }>(
      `/api/v1/dashboard/calendar?month=${month}`,
    )
  }

  async function toggleTodoComplete(todoId: number, completed: boolean) {
    return api(`/api/v1/todos/${todoId}/toggle`, { method: 'PATCH', body: { completed } })
  }

  async function markNoticeRead(noticeId: number) {
    return api(`/api/v1/notifications/${noticeId}/read`, { method: 'POST' })
  }

  async function markAllNoticesRead() {
    return api('/api/v1/notifications/read-all', { method: 'POST' })
  }

  // === Main Dashboard ===
  async function getDashboard(priority?: string) {
    const query = priority ? `?priority=${priority}` : ''
    return api<{ data: unknown }>(`/api/v1/dashboard${query}`)
  }

  // === Chat Hub ===
  async function getChatHub(allTeams?: boolean) {
    const query = allTeams !== undefined ? `?allTeams=${allTeams}` : ''
    return api<{ data: unknown }>(`/api/v1/dashboard/chat-hub${query}`)
  }

  // === My Posts ===
  async function getMyPosts(params?: { cursor?: number; limit?: number }) {
    const query = new URLSearchParams()
    if (params?.cursor) query.set('cursor', String(params.cursor))
    if (params?.limit) query.set('limit', String(params.limit))
    return api<{ data: unknown[] }>(`/api/v1/dashboard/my-posts?${query}`)
  }

  // === Performance ===
  async function getPerformance() {
    return api<{ data: unknown }>('/api/v1/dashboard/performance')
  }

  // === Scoped Dashboard ===
  async function getOrganizationDashboard(orgId: number, statsPeriod?: string) {
    const query = statsPeriod ? `?statsPeriod=${statsPeriod}` : ''
    return api<{ data: unknown }>(`/api/v1/dashboard/organization/${orgId}${query}`)
  }

  async function getTeamDashboard(teamId: number, statsPeriod?: string) {
    const query = statsPeriod ? `?statsPeriod=${statsPeriod}` : ''
    return api<{ data: unknown }>(`/api/v1/dashboard/team/${teamId}${query}`)
  }

  // Widget settings
  async function getWidgetSettings(scopeType: string, scopeId: number | null) {
    const query = new URLSearchParams()
    query.set('scopeType', scopeType)
    if (scopeId) query.set('scopeId', String(scopeId))
    return api<{ data: Array<{ key: string; visible: boolean; order: number }> }>(
      `/api/v1/dashboard/widgets?${query}`,
    )
  }

  async function updateWidgetSettings(
    settings: Array<{ key: string; visible: boolean; order: number }>,
    scopeType: string,
    scopeId: number | null,
  ) {
    return api('/api/v1/dashboard/widgets', {
      method: 'PUT',
      body: { scopeType, scopeId, widgets: settings },
    })
  }

  async function resetWidgetSettings(scopeType: string, scopeId: number | null) {
    const query = new URLSearchParams()
    query.set('scopeType', scopeType)
    if (scopeId) query.set('scopeId', String(scopeId))
    return api(`/api/v1/dashboard/widgets?${query}`, { method: 'DELETE' })
  }

  return {
    getDashboard,
    getNotices,
    getUpcomingEvents,
    getPersonalTodos,
    getActivity,
    getUnreadThreads,
    getPlatformAnnouncements,
    getCalendarSummary,
    getChatHub,
    getMyPosts,
    getPerformance,
    getOrganizationDashboard,
    getTeamDashboard,
    toggleTodoComplete,
    markNoticeRead,
    markAllNoticesRead,
    getWidgetSettings,
    updateWidgetSettings,
    resetWidgetSettings,
  }
}
