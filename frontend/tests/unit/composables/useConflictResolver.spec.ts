import { describe, it, expect } from 'vitest'
import type { PagedResponse } from '~/types/api'
import type { SyncConflictListItem, SyncConflictDetail, ResolveConflictPayload } from '~/types/sync'

/**
 * F11.1 Phase 5: useConflictResolver のユニットテスト。
 *
 * useConflictResolver は内部で useApi (→ useI18n) を呼ぶため、
 * Nuxt テスト環境の setup コンテキスト外では直接呼べない。
 * そのため、composable から抽出したロジック（正規化・ボディ構築）を
 * 単体テストする。
 */

// === useConflictResolver.ts から抽出したロジック ===

type RawConflictListItem = {
  id: number
  resource_type: string
  resource_id: number
  client_version: number | null
  server_version: number | null
  resolution: string | null
  resolved_at: string | null
  created_at: string
}

type RawConflictDetail = {
  id: number
  user_id: number
  resource_type: string
  resource_id: number
  client_data: string | Record<string, unknown>
  server_data: string | Record<string, unknown>
  client_version: number | null
  server_version: number | null
  resolution: string | null
  resolved_at: string | null
  created_at: string
  updated_at: string
}

function parseJsonSafe(value: string): Record<string, unknown> {
  try {
    return JSON.parse(value) as Record<string, unknown>
  } catch {
    return {}
  }
}

function normalizeListItem(raw: RawConflictListItem): SyncConflictListItem {
  return {
    id: raw.id,
    resourceType: raw.resource_type,
    resourceId: raw.resource_id,
    clientVersion: raw.client_version,
    serverVersion: raw.server_version,
    resolution: raw.resolution,
    resolvedAt: raw.resolved_at,
    createdAt: raw.created_at,
  }
}

function normalizeDetail(raw: RawConflictDetail): SyncConflictDetail {
  return {
    id: raw.id,
    userId: raw.user_id,
    resourceType: raw.resource_type,
    resourceId: raw.resource_id,
    clientData: typeof raw.client_data === 'string'
      ? parseJsonSafe(raw.client_data)
      : raw.client_data as Record<string, unknown>,
    serverData: typeof raw.server_data === 'string'
      ? parseJsonSafe(raw.server_data)
      : raw.server_data as Record<string, unknown>,
    clientVersion: raw.client_version,
    serverVersion: raw.server_version,
    resolution: raw.resolution,
    resolvedAt: raw.resolved_at,
    createdAt: raw.created_at,
    updatedAt: raw.updated_at,
  }
}

function buildResolveBody(payload: ResolveConflictPayload): Record<string, unknown> {
  const body: Record<string, unknown> = {
    resolution: payload.resolution,
  }
  if (payload.mergedData) {
    body.merged_data = JSON.stringify(payload.mergedData)
  }
  return body
}

function buildConflictsUrl(page: number, size: number): string {
  return `/api/v1/sync/conflicts/me?page=${page}&size=${size}`
}

function normalizePagedResponse(
  data: RawConflictListItem[],
  meta: { page: number; size: number; totalElements: number; totalPages: number },
): PagedResponse<SyncConflictListItem> {
  return {
    data: data.map(normalizeListItem),
    meta: {
      page: meta.page,
      size: meta.size,
      totalElements: meta.totalElements,
      totalPages: meta.totalPages,
    },
  }
}

// === テスト ===

describe('useConflictResolver ロジック', () => {
  describe('normalizeListItem', () => {
    it('スネークケースをキャメルケースに変換する', () => {
      const raw: RawConflictListItem = {
        id: 1,
        resource_type: 'ATTENDANCE_RESPONSE',
        resource_id: 10,
        client_version: 3,
        server_version: 5,
        resolution: null,
        resolved_at: null,
        created_at: '2026-04-10T10:00:00',
      }

      const result = normalizeListItem(raw)

      expect(result.id).toBe(1)
      expect(result.resourceType).toBe('ATTENDANCE_RESPONSE')
      expect(result.resourceId).toBe(10)
      expect(result.clientVersion).toBe(3)
      expect(result.serverVersion).toBe(5)
      expect(result.resolution).toBeNull()
      expect(result.resolvedAt).toBeNull()
      expect(result.createdAt).toBe('2026-04-10T10:00:00')
    })
  })

  describe('normalizeDetail', () => {
    it('JSON 文字列の clientData / serverData をパースする', () => {
      const raw: RawConflictDetail = {
        id: 42,
        user_id: 1,
        resource_type: 'ATTENDANCE_RESPONSE',
        resource_id: 20,
        client_data: '{"status":"PRESENT"}',
        server_data: '{"status":"ABSENT"}',
        client_version: 3,
        server_version: 5,
        resolution: null,
        resolved_at: null,
        created_at: '2026-04-10T10:00:00',
        updated_at: '2026-04-10T10:00:00',
      }

      const result = normalizeDetail(raw)

      expect(result.clientData).toEqual({ status: 'PRESENT' })
      expect(result.serverData).toEqual({ status: 'ABSENT' })
      expect(result.userId).toBe(1)
      expect(result.resourceType).toBe('ATTENDANCE_RESPONSE')
    })

    it('オブジェクト型の clientData / serverData はそのまま返す', () => {
      const raw: RawConflictDetail = {
        id: 1,
        user_id: 1,
        resource_type: 'TEST',
        resource_id: 1,
        client_data: { key: 'value' },
        server_data: { key: 'other' },
        client_version: 1,
        server_version: 2,
        resolution: null,
        resolved_at: null,
        created_at: '2026-04-10T10:00:00',
        updated_at: '2026-04-10T10:00:00',
      }

      const result = normalizeDetail(raw)

      expect(result.clientData).toEqual({ key: 'value' })
      expect(result.serverData).toEqual({ key: 'other' })
    })

    it('不正な JSON 文字列の場合は空オブジェクトを返す', () => {
      const raw: RawConflictDetail = {
        id: 1,
        user_id: 1,
        resource_type: 'TEST',
        resource_id: 1,
        client_data: 'invalid json',
        server_data: '{broken',
        client_version: 1,
        server_version: 2,
        resolution: null,
        resolved_at: null,
        created_at: '2026-04-10T10:00:00',
        updated_at: '2026-04-10T10:00:00',
      }

      const result = normalizeDetail(raw)

      expect(result.clientData).toEqual({})
      expect(result.serverData).toEqual({})
    })

    it('解決済みコンフリクトの resolution / resolvedAt を正しく変換する', () => {
      const raw: RawConflictDetail = {
        id: 42,
        user_id: 1,
        resource_type: 'TEST',
        resource_id: 1,
        client_data: '{}',
        server_data: '{}',
        client_version: 1,
        server_version: 2,
        resolution: 'CLIENT_WIN',
        resolved_at: '2026-04-10T11:00:00',
        created_at: '2026-04-10T10:00:00',
        updated_at: '2026-04-10T11:00:00',
      }

      const result = normalizeDetail(raw)

      expect(result.resolution).toBe('CLIENT_WIN')
      expect(result.resolvedAt).toBe('2026-04-10T11:00:00')
    })
  })

  describe('buildResolveBody', () => {
    it('CLIENT_WIN のボディに merged_data が含まれない', () => {
      const body = buildResolveBody({ resolution: 'CLIENT_WIN' })
      expect(body).toEqual({ resolution: 'CLIENT_WIN' })
      expect(body.merged_data).toBeUndefined()
    })

    it('SERVER_WIN のボディに merged_data が含まれない', () => {
      const body = buildResolveBody({ resolution: 'SERVER_WIN' })
      expect(body).toEqual({ resolution: 'SERVER_WIN' })
    })

    it('MANUAL_MERGE のボディに merged_data がJSON文字列で含まれる', () => {
      const body = buildResolveBody({
        resolution: 'MANUAL_MERGE',
        mergedData: { status: 'MERGED' },
      })
      expect(body.resolution).toBe('MANUAL_MERGE')
      expect(body.merged_data).toBe('{"status":"MERGED"}')
    })
  })

  describe('buildConflictsUrl', () => {
    it('デフォルトパラメータで URL を構築する', () => {
      expect(buildConflictsUrl(0, 20)).toBe('/api/v1/sync/conflicts/me?page=0&size=20')
    })

    it('カスタムページ番号で URL を構築する', () => {
      expect(buildConflictsUrl(3, 10)).toBe('/api/v1/sync/conflicts/me?page=3&size=10')
    })
  })

  describe('normalizePagedResponse', () => {
    it('一覧レスポンスを正規化する', () => {
      const rawData: RawConflictListItem[] = [
        {
          id: 1,
          resource_type: 'TEST',
          resource_id: 10,
          client_version: 1,
          server_version: 2,
          resolution: null,
          resolved_at: null,
          created_at: '2026-04-10T10:00:00',
        },
      ]
      const meta = { page: 0, size: 20, totalElements: 1, totalPages: 1 }

      const result = normalizePagedResponse(rawData, meta)

      expect(result.data).toHaveLength(1)
      expect(result.data[0]!.resourceType).toBe('TEST')
      expect(result.meta.totalElements).toBe(1)
    })

    it('空の一覧を正規化する', () => {
      const result = normalizePagedResponse([], {
        page: 0,
        size: 20,
        totalElements: 0,
        totalPages: 0,
      })

      expect(result.data).toHaveLength(0)
      expect(result.meta.totalElements).toBe(0)
    })
  })
})
