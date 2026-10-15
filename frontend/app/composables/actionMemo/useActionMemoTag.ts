/**
 * F02.5 行動メモ — Tag CRUD ドメイン。
 *
 * <p>Phase 3 リファクタにて {@code useActionMemoApi.ts} から分離。
 * getTags / createTag / updateTag / deleteTag /
 * addTagsToMemo / removeTagFromMemo の 6 関数を提供する。</p>
 */
import type {
  ActionMemoTag,
  CreateTagPayload,
  UpdateTagPayload,
} from '~/types/actionMemo'
import {
  ACTION_MEMO_BASE,
  ACTION_MEMO_TAGS_BASE,
  rethrow,
} from './shared/normalize'

export function useActionMemoTag() {
  const api = useApi()
  const BASE = ACTION_MEMO_BASE
  const TAGS_BASE = ACTION_MEMO_TAGS_BASE

  type RawTagResponse = {
    id: number
    name: string
    color: string | null
    sort_order: number
    deleted: boolean
  }

  function normalizeTagResponse(raw: RawTagResponse): ActionMemoTag {
    return {
      id: raw.id,
      name: raw.name,
      color: raw.color ?? null,
      deleted: raw.deleted === true,
    }
  }

  async function getTags(): Promise<ActionMemoTag[]> {
    try {
      const res = await api<{ data: RawTagResponse[] }>(TAGS_BASE)
      return (res.data ?? []).map(normalizeTagResponse)
    } catch (error) {
      rethrow(error)
    }
  }

  async function createTag(payload: CreateTagPayload): Promise<ActionMemoTag> {
    try {
      const res = await api<{ data: RawTagResponse }>(TAGS_BASE, {
        method: 'POST',
        body: {
          name: payload.name,
          color: payload.color ?? undefined,
        },
      })
      return normalizeTagResponse(res.data)
    } catch (error) {
      rethrow(error)
    }
  }

  async function updateTag(id: number, payload: UpdateTagPayload): Promise<ActionMemoTag> {
    try {
      const body: Record<string, unknown> = {}
      if (payload.name !== undefined) body.name = payload.name
      if (payload.color !== undefined) body.color = payload.color
      const res = await api<{ data: RawTagResponse }>(`${TAGS_BASE}/${id}`, {
        method: 'PATCH',
        body,
      })
      return normalizeTagResponse(res.data)
    } catch (error) {
      rethrow(error)
    }
  }

  async function deleteTag(id: number): Promise<void> {
    try {
      await api(`${TAGS_BASE}/${id}`, { method: 'DELETE' })
    } catch (error) {
      rethrow(error)
    }
  }

  // === Memo ↔ Tag operations (Phase 4) ===

  async function addTagsToMemo(memoId: number, tagIds: number[]): Promise<void> {
    try {
      await api(`${BASE}/${memoId}/tags`, {
        method: 'POST',
        body: { tag_ids: tagIds },
      })
    } catch (error) {
      rethrow(error)
    }
  }

  async function removeTagFromMemo(memoId: number, tagId: number): Promise<void> {
    try {
      await api(`${BASE}/${memoId}/tags/${tagId}`, { method: 'DELETE' })
    } catch (error) {
      rethrow(error)
    }
  }

  return {
    getTags,
    createTag,
    updateTag,
    deleteTag,
    addTagsToMemo,
    removeTagFromMemo,
  }
}
