import { defineStore } from 'pinia'

type ScopeType = 'personal' | 'team' | 'organization'

interface CurrentScope {
  type: ScopeType
  id: number | null
  name: string
}

export const useScopeStore = defineStore('scope', {
  state: () => ({
    current: {
      type: 'personal',
      id: null,
      name: '個人',
    } as CurrentScope,
  }),

  getters: {
    isPersonal: (state): boolean => state.current.type === 'personal',
    isTeam: (state): boolean => state.current.type === 'team',
    isOrganization: (state): boolean => state.current.type === 'organization',
    currentScopeId: (state): number | null => state.current.id,
    currentScopeLabel: (state): string => state.current.name,
  },

  actions: {
    setTeamScope(teamId: number, teamName: string) {
      this.current = { type: 'team', id: teamId, name: teamName }
      if (import.meta.client) {
        localStorage.setItem('currentScope', JSON.stringify(this.current))
      }
    },

    setOrganizationScope(orgId: number, orgName: string) {
      this.current = { type: 'organization', id: orgId, name: orgName }
      if (import.meta.client) {
        localStorage.setItem('currentScope', JSON.stringify(this.current))
      }
    },

    setPersonalScope() {
      this.current = { type: 'personal', id: null, name: '個人' }
      if (import.meta.client) {
        localStorage.setItem('currentScope', JSON.stringify(this.current))
      }
    },

    loadFromStorage() {
      if (import.meta.client) {
        const saved = localStorage.getItem('currentScope')
        if (saved) {
          try {
            this.current = JSON.parse(saved)
          }
          catch {
            // ignore parse errors
          }
        }
      }
    },

    clear() {
      this.current = { type: 'personal', id: null, name: '個人' }
      if (import.meta.client) {
        localStorage.removeItem('currentScope')
      }
    },
  },
})
