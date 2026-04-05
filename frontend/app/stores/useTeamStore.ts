import { defineStore } from 'pinia'

interface MyTeam {
  id: number
  name: string
  nickname1: string | null
  iconUrl: string | null
  role: string
  template: string
}

export const useTeamStore = defineStore('team', {
  state: () => ({
    myTeams: [] as MyTeam[],
    loading: false,
  }),

  getters: {
    teamCount: (state): number => state.myTeams.length,
    adminTeams: (state): MyTeam[] =>
      state.myTeams.filter(t => t.role === 'ADMIN' || t.role === 'SYSTEM_ADMIN'),
  },

  actions: {
    async fetchMyTeams() {
      this.loading = true
      try {
        const api = useApi()
        const response = await api<{ data: MyTeam[] }>('/api/v1/me/teams')
        this.myTeams = response.data
      }
      catch {
        this.myTeams = []
      }
      finally {
        this.loading = false
      }
    },

    clear() {
      this.myTeams = []
    },
  },
})
