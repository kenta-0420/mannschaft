import { defineStore } from 'pinia'

interface MyOrganization {
  id: number
  name: string
  nickname1: string | null
  iconUrl: string | null
  role: string
  orgType: string
}

export const useOrganizationStore = defineStore('organization', {
  state: () => ({
    myOrganizations: [] as MyOrganization[],
    loading: false,
  }),

  getters: {
    organizationCount: (state): number => state.myOrganizations.length,
    adminOrganizations: (state): MyOrganization[] =>
      state.myOrganizations.filter(o => o.role === 'ADMIN' || o.role === 'SYSTEM_ADMIN'),
  },

  actions: {
    async fetchMyOrganizations() {
      this.loading = true
      try {
        const api = useApi()
        const response = await api<{ data: MyOrganization[] }>('/api/v1/me/organizations')
        this.myOrganizations = response.data
      }
      catch {
        this.myOrganizations = []
      }
      finally {
        this.loading = false
      }
    },

    clear() {
      this.myOrganizations = []
    },
  },
})
