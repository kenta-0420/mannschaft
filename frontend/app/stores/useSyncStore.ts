import { defineStore } from 'pinia'

/**
 * F11.1 PWA: オフライン同期状態の Pinia ストア。
 *
 * 同期の進行状況・未送信件数・コンフリクト情報を管理し、
 * UI コンポーネント（SyncProgressIndicator 等）から参照される。
 */

export interface ConflictSummary {
  clientId: string
  path: string
  message: string
}

interface SyncState {
  syncInProgress: boolean
  pendingCount: number
  lastSyncAt: string | null
  conflicts: ConflictSummary[]
}

export const useSyncStore = defineStore('sync', {
  state: (): SyncState => ({
    syncInProgress: false,
    pendingCount: 0,
    lastSyncAt: null,
    conflicts: [],
  }),

  getters: {
    hasConflicts: (state): boolean => state.conflicts.length > 0,
    conflictCount: (state): number => state.conflicts.length,
  },

  actions: {
    startSync() {
      this.syncInProgress = true
    },

    finishSync(syncedAt?: string) {
      this.syncInProgress = false
      this.lastSyncAt = syncedAt ?? new Date().toISOString().replace('Z', '').slice(0, 19)
    },

    updatePendingCount(count: number) {
      this.pendingCount = count
    },

    addConflict(conflict: ConflictSummary) {
      this.conflicts.push(conflict)
    },

    clearConflicts() {
      this.conflicts = []
    },

    removeConflict(clientId: string) {
      this.conflicts = this.conflicts.filter((c) => c.clientId !== clientId)
    },
  },
})
