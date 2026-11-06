import { defineStore } from 'pinia'

interface MyTeam {
  id: number
  slug: string
  name: string
  nickname1: string | null
  iconUrl: string | null
  role: string
  template: string
  memberCount: number
}

export interface FetchMyTeamsOptions {
  signal?: AbortSignal
}

export type FetchMyTeamsResult = { ok: true } | { ok: false; error: unknown }

// SSRの別リクエストや別Piniaへ進行中の取得を共有しない。
const inFlightByStore = new WeakMap<object, { requestId: number, promise: Promise<void> }>()

export const useTeamStore = defineStore('team', {
  state: () => ({
    myTeams: [] as MyTeam[],
    loading: false,
    fetchRequestId: 0,
  }),

  getters: {
    teamCount: (state): number => state.myTeams.length,
    adminTeams: (state): MyTeam[] =>
      state.myTeams.filter(t => t.role === 'ADMIN' || t.role === 'SYSTEM_ADMIN'),
  },

  actions: {
    async fetchMyTeams(options: FetchMyTeamsOptions = {}): Promise<void> {
      if (!options.signal) {
        const inFlight = inFlightByStore.get(this)
        if (inFlight?.requestId === this.fetchRequestId) {
          await inFlight.promise
          return
        }
      }
      const request = this.fetchMyTeamsWithResult(options).then(() => {})
      if (!options.signal) {
        inFlightByStore.set(this, { requestId: this.fetchRequestId, promise: request })
        void request.finally(() => {
          if (inFlightByStore.get(this)?.promise === request) inFlightByStore.delete(this)
        })
      }
      await request
    },

    async fetchMyTeamsWithResult(
      options: FetchMyTeamsOptions = {},
    ): Promise<FetchMyTeamsResult> {
      const requestId = ++this.fetchRequestId
      this.loading = true
      try {
        const api = useApi()
        const response = await api<{ data: MyTeam[] }>('/api/v1/me/teams', {
          signal: options.signal,
        })
        if (requestId === this.fetchRequestId) this.myTeams = response.data
        return { ok: true }
      } catch (error) {
        if (requestId === this.fetchRequestId) this.myTeams = []
        return { ok: false, error }
      } finally {
        if (requestId === this.fetchRequestId) this.loading = false
      }
    },

    clear() {
      this.fetchRequestId++
      inFlightByStore.delete(this)
      this.myTeams = []
      this.loading = false
    },
  },
})
