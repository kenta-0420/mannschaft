import type { components } from '~/types/generated'
import { onScopeDispose, ref, shallowRef } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '~/composables/useApi'
import { useErrorReport } from '~/composables/useErrorReport'
import { useNotification } from '~/composables/useNotification'

/** 本文契約はBEのOpenAPI生成型を正本とする。 */
export type AnnouncementPreviewResponse = components['schemas']['AnnouncementPreviewResponse']

/** 表示用 item のうち本文取得に必要な情報だけ。元IDは要求しない。 */
export interface AnnouncementPreviewTarget {
  id: number
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: string | number
  title: string
  isRead: boolean
}

export type AnnouncementPreviewState = 'CLOSED' | 'LOADING' | 'FULL' | 'LOCKED' | 'ERROR' | 'UNAVAILABLE'

interface PreviewCallbacks {
  onRead?: (item: AnnouncementPreviewTarget) => void
  onUnavailable?: (item: AnnouncementPreviewTarget) => void
}

function errorStatus(error: unknown): number | undefined {
  const value = error as { statusCode?: number; status?: number; response?: { status?: number } }
  return value?.statusCode ?? value?.status ?? value?.response?.status
}

function isGone(error: unknown): boolean {
  const value = error as { data?: { error?: { code?: string } } }
  return value?.data?.error?.code === 'ANNOUNCE_001'
}

/** 元ページは対象種別に対応する内部 path だけ。外部URLや protocol-relative URL は拒否する。 */
export function isAnnouncementSourceUrl(url: string | null, type: AnnouncementPreviewResponse['sourceType'], target?: Pick<AnnouncementPreviewTarget, 'scopeType' | 'scopeId'>): boolean {
  if (!url || !url.startsWith('/') || url.startsWith('//') || url.includes('\\')) return false
  if (type === 'BLOG_POST') {
    if (!/^\/blog\/posts\/[^/?#]+\?(?:teamId|organizationId)=[1-9]\d*$/.test(url)) return false
    return !target || url.endsWith(`?${target.scopeType === 'TEAM' ? 'teamId' : 'organizationId'}=${Number(target.scopeId)}`)
  }
  if (type === 'BULLETIN_THREAD') {
    if (!/^\/(?:teams|organizations)\/[^/?#]+\/bulletin\?threadId=[1-9]\d*$/.test(url)) return false
    return !target || url.startsWith(`/${target.scopeType === 'TEAM' ? 'teams' : 'organizations'}/`)
  }
  return false
}

/** scoped preview の取得と描画後の既読を共通管理する。setup で一度だけ構築する。 */
export function useAnnouncementPreview(callbacks: PreviewCallbacks = {}) {
  const api = useApi()
  const report = useErrorReport()
  const notification = useNotification()
  const { t } = useI18n()
  const state = ref<AnnouncementPreviewState>('CLOSED')
  const item = shallowRef<AnnouncementPreviewTarget | null>(null)
  const preview = shallowRef<AnnouncementPreviewResponse | null>(null)
  const error = shallowRef<unknown>(null)
  const trigger = shallowRef<HTMLElement | null>(null)
  let sequence = 0
  let controller: AbortController | null = null
  const reads = new Set<string>()
  const pendingReads = new Set<string>()

  function key(target: AnnouncementPreviewTarget): string {
    return `${target.scopeType}:${target.scopeId}:${target.id}`
  }

  function path(target: AnnouncementPreviewTarget): string {
    const scopeId = Number(target.scopeId)
    if (!Number.isSafeInteger(scopeId) || scopeId <= 0 || !Number.isSafeInteger(target.id) || target.id <= 0) {
      throw new Error('Invalid announcement scope/feed identifier')
    }
    const scope = target.scopeType === 'TEAM' ? 'teams' : 'organizations'
    return `/api/v1/${scope}/${scopeId}/announcements/${target.id}`
  }

  function current(request: number): boolean {
    return request === sequence && state.value !== 'CLOSED'
  }

  function clearBody(): void {
    preview.value = null
    error.value = null
  }

  function unavailable(target: AnnouncementPreviewTarget): void {
    callbacks.onUnavailable?.(target)
    notification.warn(t('announcement.no_longer_available'))
  }

  async function open(target: AnnouncementPreviewTarget, element?: HTMLElement | null): Promise<void> {
    controller?.abort()
    const request = ++sequence
    controller = new AbortController()
    const signal = controller.signal
    item.value = { ...target }
    if (element !== undefined) trigger.value = element
    clearBody()
    state.value = 'LOADING'
    try {
      const response = await api<{ data: AnnouncementPreviewResponse }>(`${path(target)}/preview`, { signal })
      if (!current(request) || signal.aborted) return
      const data = response.data
      if (!data || data.feedId !== target.id || data.scopeType !== target.scopeType || data.scopeId !== Number(target.scopeId)
        || !Array.isArray(data.attachments)) {
        throw new Error('Announcement preview response does not match its request')
      }
      if (data.accessState === 'LOCKED') {
        if (data.sourceType !== null || data.sourceId !== null || data.sourceUrl !== null || data.blogPost !== null || data.bulletinThread !== null || data.attachments.length !== 0) {
          throw new Error('Locked announcement preview contains protected content')
        }
        state.value = 'LOCKED'
        return
      }
      if (data.accessState !== 'FULL' || !isAnnouncementSourceUrl(data.sourceUrl, data.sourceType, target)
        || !Number.isSafeInteger(data.sourceId) || (data.sourceId ?? 0) <= 0
        || (data.sourceType === 'BLOG_POST' && (data.blogPost?.id !== data.sourceId || data.bulletinThread !== null))
        || (data.sourceType === 'BULLETIN_THREAD' && (data.bulletinThread?.id !== data.sourceId || data.blogPost !== null))) {
        throw new Error('Invalid announcement preview payload')
      }
      preview.value = data
      state.value = 'FULL'
    }
    catch (reason) {
      if (!current(request) || signal.aborted) return
      clearBody()
      error.value = reason
      state.value = isGone(reason) ? 'UNAVAILABLE' : 'ERROR'
      if (isGone(reason)) unavailable(target)
      report.captureQuiet(reason, { context: 'announcementPreview' })
    }
  }

  /** FULL が DOM に描画された後だけ呼ぶ。開始済み POST は閉じても一覧更新を継続する。 */
  async function markDisplayed(): Promise<void> {
    const target = item.value
    if (state.value !== 'FULL' || !preview.value || !target) return
    const request = sequence
    const readKey = key(target)
    if (target.isRead || reads.has(readKey) || pendingReads.has(readKey)) return
    pendingReads.add(readKey)
    try {
      await api(`${path(target)}/read`, { method: 'POST' })
      reads.add(readKey)
      callbacks.onRead?.(target)
    }
    catch (reason) {
      report.captureQuiet(reason, { context: 'announcementPreviewRead' })
      if (isGone(reason)) {
        unavailable(target)
        if (current(request)) {
          clearBody()
          error.value = reason
          state.value = 'UNAVAILABLE'
        }
      }
      else if (current(request)) {
        if (errorStatus(reason) === 401) {
          clearBody()
          error.value = reason
          state.value = 'ERROR'
        }
        notification.error(t('announcement.preview.read_failed'))
      }
    }
    finally {
      pendingReads.delete(readKey)
    }
  }

  function close(): void {
    ++sequence
    controller?.abort()
    controller = null
    clearBody()
    state.value = 'CLOSED'
  }

  async function retry(): Promise<void> {
    if (item.value) await open(item.value)
  }

  onScopeDispose(close)
  return { state, item, preview, error, trigger, open, close, retry, markDisplayed }
}
