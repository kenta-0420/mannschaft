import { defineStore } from 'pinia'

interface MyOrganization {
  id: number
  slug: string
  name: string
  nickname1: string | null
  iconUrl: string | null
  role: string
  orgType: string
  memberCount: number
}

// SSRの別リクエストや別Piniaへ、進行中の取得を共有しない。
const inFlightByStore = new WeakMap<object, Promise<void>>()

export const useOrganizationStore = defineStore('organization', {
  state: () => ({
    myOrganizations: [] as MyOrganization[],
    loading: false,
    fetchRequestId: 0,
  }),

  getters: {
    organizationCount: (state): number => state.myOrganizations.length,
    adminOrganizations: (state): MyOrganization[] =>
      state.myOrganizations.filter(o => o.role === 'ADMIN' || o.role === 'SYSTEM_ADMIN'),
  },

  actions: {
    async fetchMyOrganizations(): Promise<void> {
      const inFlight = inFlightByStore.get(this)
      if (inFlight) return inFlight
      const requestId = ++this.fetchRequestId
      this.loading = true
      const request = (async () => {
        try {
          const api = useApi()
          const response = await api<{ data: MyOrganization[] }>('/api/v1/me/organizations')
          if (requestId === this.fetchRequestId) this.myOrganizations = response.data
        }
        catch {
          if (requestId === this.fetchRequestId) this.myOrganizations = []
        }
        finally {
          if (requestId === this.fetchRequestId) this.loading = false
        }
      })()
      inFlightByStore.set(this, request)
      void request.finally(() => {
        if (inFlightByStore.get(this) === request) inFlightByStore.delete(this)
      })
      return request
    },

    clear() {
      this.fetchRequestId++
      inFlightByStore.delete(this)
      this.myOrganizations = []
      this.loading = false
    },
  },
})
