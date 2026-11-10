import { toTypedSchema } from '@vee-validate/zod'
import { useForm } from 'vee-validate'
import { z } from 'zod'
import type { components } from '~/types/generated'
import { toLocalDateString } from '~/utils/localDate'

type ScopeType = 'TEAM' | 'ORGANIZATION'
type SessionListItem = components['schemas']['SessionListResponse']

/** チーム/組織の既存一覧・作成画面だけで共有する取得状態。 */
export function useVotingSessions(scopeType: ScopeType, slug: Ref<string>) {
  const { getSessions, createSession } = useVotingApi()
  const { getTeam } = useTeamApi()
  const { getOrganization } = useOrganizationApi()
  const { roleName, roleResolved, roleLoadFailed, isScopeAdmin, loadPermissions } = useRoleAccess(
    scopeType === 'TEAM' ? 'team' : 'organization', slug, { watchScope: false },
  )
  const { handleApiError } = useErrorHandler()
  const notification = useNotification()
  const { t } = useI18n()
  const sessions = ref<SessionListItem[]>([])
  const error = shallowRef<unknown>(null)
  const loading = ref(true)
  const ready = ref(false)
  const numericId = ref<number | null>(null)
  const showCreateDialog = ref(false)
  const saving = ref(false)
  let sequence = 0
  let dialogSlug: string | null = null

  const canCreate = computed(() => ready.value && !loading.value && !error.value
    && roleResolved.value && !roleLoadFailed.value
    && (isScopeAdmin.value || roleName.value === 'DEPUTY_ADMIN'))
  const schema = z.object({
    title: z.string().trim().min(1).max(200),
    description: z.string(),
    resolutionMode: z.enum(['MEETING', 'WRITTEN']),
    meetingDate: z.date().nullable(),
    isAnonymous: z.boolean(),
  }).refine(value => value.resolutionMode !== 'MEETING' || value.meetingDate !== null, { path: ['meetingDate'] })
  const { defineField, handleSubmit, resetForm, errors } = useForm({
    validationSchema: toTypedSchema(schema),
    initialValues: { title: '', description: '', resolutionMode: 'MEETING' as 'MEETING' | 'WRITTEN', meetingDate: null as Date | null, isAnonymous: false },
  })
  const [title] = defineField('title')
  const [description] = defineField('description')
  const [resolutionMode] = defineField('resolutionMode')
  const [meetingDate] = defineField('meetingDate')
  const [isAnonymous] = defineField('isAnonymous')
  const form = reactive({ title, description, resolutionMode, meetingDate, isAnonymous })

  async function load(): Promise<void> {
    const request = ++sequence
    const requestedSlug = slug.value
    const current = () => request === sequence && slug.value === requestedSlug
    loading.value = true
    ready.value = false
    error.value = null
    sessions.value = []
    numericId.value = null
    try {
      const scope = scopeType === 'TEAM' ? await getTeam(requestedSlug) : await getOrganization(requestedSlug)
      if (!current()) return
      const id = scope.data.numericId
      if (typeof id !== 'number' || !Number.isSafeInteger(id) || id <= 0) throw new Error('Invalid voting scope identifier')
      const permissions = await loadPermissions()
      if (!current()) return
      if (!permissions.ok) throw permissions.error
      const result = await getSessions(scopeType === 'TEAM'
        ? { scope_type: 'TEAM', team_id: id } : { scope_type: 'ORGANIZATION', organization_id: id })
      if (!current()) return
      if (!Array.isArray(result.data)) throw new Error('Invalid voting session list response')
      sessions.value = result.data
      numericId.value = id
      ready.value = true
    }
    catch (cause) {
      if (!current()) return
      error.value = cause
      showCreateDialog.value = false
      handleApiError(cause)
    }
    finally {
      if (current()) loading.value = false
    }
  }

  function openCreateDialog(): void {
    if (!canCreate.value || saving.value) return
    resetForm()
    dialogSlug = slug.value
    showCreateDialog.value = true
  }

  const submit = handleSubmit(async values => {
    if (!canCreate.value || saving.value || !showCreateDialog.value || dialogSlug !== slug.value || numericId.value === null) return
    const requestedSlug = slug.value
    const request = sequence
    saving.value = true
    try {
      await createSession({
        scopeType,
        ...(scopeType === 'TEAM' ? { teamId: numericId.value } : { organizationId: numericId.value }),
        title: values.title,
        description: values.description || undefined,
        resolutionMode: values.resolutionMode,
        meetingDate: values.resolutionMode === 'MEETING' && values.meetingDate ? toLocalDateString(values.meetingDate) : undefined,
        isAnonymous: values.isAnonymous,
      })
      if (request !== sequence || slug.value !== requestedSlug) return
      notification.success(t('voting.created'))
      showCreateDialog.value = false
      await load()
    }
    catch (cause) {
      if (request === sequence && slug.value === requestedSlug) handleApiError(cause)
    }
    finally { saving.value = false }
  })
  async function handleCreate(): Promise<void> {
    if (saving.value || !canCreate.value) return
    await submit()
  }

  watch(canCreate, allowed => {
    if (!allowed) {
      showCreateDialog.value = false
      dialogSlug = null
    }
  }, { flush: 'sync' })
  watch(slug, () => {
    showCreateDialog.value = false
    dialogSlug = null
    resetForm()
    void load()
  }, { flush: 'sync' })
  onMounted(() => { void load() })
  onScopeDispose(() => { ++sequence })
  return { sessions, error, loading, canCreate, showCreateDialog, saving, form, errors, load, openCreateDialog, handleCreate }
}
