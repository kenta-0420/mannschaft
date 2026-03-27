import { defineStore } from 'pinia'

interface DashboardWidget {
  key: string
  title: string
  visible: boolean
  order: number
  data: unknown
}

interface PersonalDashboardData {
  greeting: string
  todayEventCount: number
  unreadCount: number
  widgets: DashboardWidget[]
}

interface TeamDashboardData {
  teamId: number
  teamName: string
  widgets: DashboardWidget[]
}

interface OrgDashboardData {
  orgId: number
  orgName: string
  widgets: DashboardWidget[]
}

export const useDashboardStore = defineStore('dashboard', {
  state: () => ({
    personalDashboard: null as PersonalDashboardData | null,
    teamDashboards: {} as Record<number, TeamDashboardData>,
    orgDashboards: {} as Record<number, OrgDashboardData>,
    loading: false,
    lastFetchedAt: null as number | null,
  }),

  getters: {
    isStale: (state): boolean => {
      if (!state.lastFetchedAt) return true
      return Date.now() - state.lastFetchedAt > 5 * 60 * 1000 // 5min TTL
    },
  },

  actions: {
    async fetchPersonalDashboard(force = false) {
      if (!force && !this.isStale && this.personalDashboard) return
      this.loading = true
      try {
        const api = useApi()
        const response = await api<{ data: PersonalDashboardData }>('/api/v1/dashboard')
        this.personalDashboard = response.data
        this.lastFetchedAt = Date.now()
      }
      catch {
        // keep existing data on error
      }
      finally {
        this.loading = false
      }
    },

    async fetchTeamDashboard(teamId: number, statsPeriod: string = 'WEEK') {
      this.loading = true
      try {
        const api = useApi()
        const response = await api<{ data: TeamDashboardData }>(
          `/api/v1/dashboard/team/${teamId}?statsPeriod=${statsPeriod}`,
        )
        this.teamDashboards[teamId] = response.data
      }
      catch {
        // keep existing
      }
      finally {
        this.loading = false
      }
    },

    async fetchOrgDashboard(orgId: number, statsPeriod: string = 'WEEK') {
      this.loading = true
      try {
        const api = useApi()
        const response = await api<{ data: OrgDashboardData }>(
          `/api/v1/dashboard/organization/${orgId}?statsPeriod=${statsPeriod}`,
        )
        this.orgDashboards[orgId] = response.data
      }
      catch {
        // keep existing
      }
      finally {
        this.loading = false
      }
    },

    clear() {
      this.personalDashboard = null
      this.teamDashboards = {}
      this.orgDashboards = {}
      this.lastFetchedAt = null
    },
  },
})
