import { onScopeDispose, ref, shallowRef, watch } from 'vue'
import type { RouteLocationRaw } from 'vue-router'
import type { components } from '~/types/generated'
import type { ScopeCalendarSummary, ScopeTabType } from '~/types/dashboard-scope'
import { useBulletinApi } from '~/composables/useBulletinApi'
import { useBlogApi } from '~/composables/useBlogApi'
import { useTodoApi } from '~/composables/useTodoApi'
import { useScheduleCrud } from '~/composables/schedule/useScheduleCrud'
import { useChatChannels } from '~/composables/chat/useChatChannels'
import { useErrorHandler } from '~/composables/useErrorHandler'
import { blogPostReadRoute } from '~/utils/blogPostRoute'
import { getLoadErrorHttpStatus } from '~/utils/loadError'

export interface ScopeWidgetPreviewContext {
  scopeType: ScopeTabType
  scopeSlug: string
  numericScopeId?: string
}
export interface ScopeWidgetPreviewTarget {
  kind: 'schedule' | 'bulletin' | 'blog' | 'chat' | 'todo' | 'calendar'
  id?: number
  title: string
  slug?: string
  calendar?: ScopeCalendarSummary
}
export interface ScopeWidgetPreviewField {
  label: string
  value: string | number
  format?: 'date' | 'datetime' | 'translation'
}
export interface ScopeWidgetPreviewDetail {
  title: string
  body: string
  bodyFormat: 'html' | 'markdown' | 'text'
  fields: ScopeWidgetPreviewField[]
  route: RouteLocationRaw
}
export type ScopeWidgetPreviewState = 'CLOSED' | 'LOADING' | 'FULL' | 'ERROR' | 'UNAVAILABLE'

/** 詳細は選択時だけ取得し、閉じる・連続選択・所属切替後の旧応答を破棄する。 */
export function useScopeWidgetPreview(context: () => ScopeWidgetPreviewContext) {
  const bulletin = useBulletinApi()
  const blog = useBlogApi()
  const todo = useTodoApi()
  const schedule = useScheduleCrud()
  const chat = useChatChannels()
  const { handleApiError } = useErrorHandler()
  const state = ref<ScopeWidgetPreviewState>('CLOSED')
  const target = shallowRef<ScopeWidgetPreviewTarget | null>(null)
  const detail = shallowRef<ScopeWidgetPreviewDetail | null>(null)
  const error = shallowRef<unknown>(null)
  const trigger = shallowRef<HTMLElement | null>(null)
  let sequence = 0

  function close(): void {
    ++sequence
    state.value = 'CLOSED'
    detail.value = null
    error.value = null
    target.value = null
  }

  async function load(
    item: ScopeWidgetPreviewTarget,
    scope: ScopeWidgetPreviewContext,
  ): Promise<ScopeWidgetPreviewDetail> {
    const type = scope.scopeType === 'TEAM' ? 'team' : 'organization'
    const plural = scope.scopeType === 'TEAM' ? 'teams' : 'organizations'
    const base = `/${plural}/${encodeURIComponent(scope.scopeSlug)}`
    const fields: ScopeWidgetPreviewField[] = []
    const field = (
      label: string,
      value: string | number | null | undefined,
      format?: ScopeWidgetPreviewField['format'],
    ): void => {
      if (value !== null && value !== undefined && value !== '')
        fields.push({ label, value, format })
    }
    const result = (
      title: string,
      body: string | null | undefined,
      route: RouteLocationRaw,
      bodyFormat: ScopeWidgetPreviewDetail['bodyFormat'] = 'text',
    ): ScopeWidgetPreviewDetail => ({
      title,
      body: body ?? '',
      bodyFormat,
      route,
      fields,
    })
    if (!scope.scopeSlug) throw new Error('Missing scope slug')
    if (item.kind === 'calendar' && item.calendar) {
      field('eventsToday', item.calendar.events_today)
      field('eventsThisWeek', item.calendar.events_this_week)
      field(
        'nextEvent',
        item.calendar.next_event ?? 'swipeWidgets.list.noNextEvent',
        item.calendar.next_event ? undefined : 'translation',
      )
      return result(item.title, '', `${base}/schedule`)
    }
    const numericId = Number(scope.numericScopeId)
    if (
      !Number.isSafeInteger(numericId) ||
      numericId <= 0 ||
      !Number.isSafeInteger(item.id) ||
      (item.id ?? 0) <= 0
    ) {
      throw new Error('Missing or invalid scope/item identifier')
    }
    const id = item.id!
    const matchesScope = (
      actualType: string | undefined,
      actualId: string | number | undefined,
    ): boolean => actualType === scope.scopeType && String(actualId) === String(numericId)
    const mismatch = (): never => {
      throw new Error('Widget detail does not match the selected scope/item')
    }

    if (item.kind === 'bulletin') {
      const { data } = await bulletin.getScopedThread(plural, String(numericId), id)
      if (data.id !== id || !matchesScope(data.scopeType, data.scopeId)) mismatch()
      field('author', data.author?.displayName)
      field('updated', data.updatedAt, 'datetime')
      return result(data.title, data.body, `${base}/bulletin?threadId=${id}`, 'html')
    }
    if (item.kind === 'todo') {
      const { data } = await todo.getTodo(type, scope.scopeSlug, id)
      if (
        data.id !== id ||
        !matchesScope(data.scope?.scopeType, data.scope?.scopeId) ||
        !data.content
      )
        mismatch()
      field(
        'due',
        data.schedule?.dueDate ?? 'swipeWidgets.preview.noDue',
        data.schedule?.dueDate ? 'date' : 'translation',
      )
      return result(
        data.content!.title ?? item.title,
        data.content!.description,
        `${base}/todos/${id}`,
      )
    }
    if (item.kind === 'blog') {
      if (!item.slug?.trim()) throw new Error('Missing blog slug')
      const blogScope =
        scope.scopeType === 'TEAM'
          ? { teamId: String(numericId) }
          : { organizationId: String(numericId) }
      const { data } = await blog.getPost(item.slug, blogScope)
      const actual = data.scope
      const scopedId = scope.scopeType === 'TEAM' ? actual?.teamId : actual?.organizationId
      const otherId = scope.scopeType === 'TEAM' ? actual?.organizationId : actual?.teamId
      const route = blogPostReadRoute(data)
      if (
        data.id !== id ||
        !data.content ||
        data.content.slug !== item.slug ||
        scopedId !== numericId ||
        otherId != null ||
        actual?.userId != null ||
        !route
      )
        mismatch()
      field('author', data.audit?.createdBy?.displayName)
      field('published', data.audit?.publishedAt, 'datetime')
      return result(data.content!.title, data.content!.body, route!, 'markdown')
    }
    if (item.kind === 'chat') {
      const { data } = await chat.getChannel(id)
      const actual = data.identity
      const scopedId = scope.scopeType === 'TEAM' ? actual?.teamId : actual?.organizationId
      const otherId = scope.scopeType === 'TEAM' ? actual?.organizationId : actual?.teamId
      if (data.id !== id || scopedId !== numericId || otherId != null || !data.meta) mismatch()
      field('members', data.memberCount)
      field('lastMessage', data.lastMessage?.lastMessagePreview)
      return result(data.meta.name ?? item.title, data.meta.description, `${base}/chat`)
    }
    if (item.kind === 'schedule') {
      // このAPIはscope付きの認可を通す。実DTOのscopeは表示名だけでIDを持たない。
      const response = await schedule.getSchedule(type, scope.scopeSlug, id)
      const data = response.data as components['schemas']['ScheduleResponse']
      if (!data || data.id !== id || !data.content || !data.time?.startAt) mismatch()
      const dateFormat = data.time!.allDay ? 'date' : 'datetime'
      field('start', data.time!.startAt, dateFormat)
      field('end', data.time!.endAt, dateFormat)
      field('location', data.content!.location)
      if (data.content!.attendanceRequired != null) {
        field(
          'attendance',
          `swipeWidgets.preview.${data.content!.attendanceRequired ? 'required' : 'notRequired'}`,
          'translation',
        )
      }
      field('targetCount', data.targetCount)
      return result(data.content!.title ?? item.title, '', `${base}/schedule?eventId=${id}`)
    }
    throw new Error('Invalid widget preview target')
  }

  async function open(item: ScopeWidgetPreviewTarget, element?: HTMLElement | null): Promise<void> {
    const request = ++sequence
    target.value = item
    if (element !== undefined) trigger.value = element
    detail.value = null
    error.value = null
    state.value = 'LOADING'
    try {
      const value = await load(item, { ...context() })
      if (request !== sequence) return
      detail.value = value
      state.value = 'FULL'
    } catch (reason) {
      if (request !== sequence) return
      detail.value = null
      error.value = reason
      state.value = [403, 404].includes(getLoadErrorHttpStatus(reason) ?? 0)
        ? 'UNAVAILABLE'
        : 'ERROR'
      handleApiError(reason, 'scopeWidgetPreview')
    }
  }
  async function retry(): Promise<void> {
    if (target.value && state.value !== 'CLOSED') await open(target.value)
  }

  watch([() => context().scopeType, () => context().scopeSlug], close, { flush: 'sync' })
  watch(
    () => context().numericScopeId,
    () => {
      if (state.value === 'CLOSED') return
      // タグの遅延解決後も再試行できるよう対象を保ち、旧IDで取得した本文だけを破棄する。
      ++sequence
      detail.value = null
      error.value = new Error('Scope identifier changed; retry the selected item')
      state.value = 'ERROR'
    },
    { flush: 'sync' },
  )
  onScopeDispose(close)
  return { state, target, detail, error, trigger, open, close, retry }
}
