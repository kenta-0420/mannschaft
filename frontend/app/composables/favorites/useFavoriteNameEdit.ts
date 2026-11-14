import { onScopeDispose, ref, type Ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { components } from '~/types/generated'
import type { UserFavoriteItem } from '~/types/favorite'

/** お気に入り名称編集の取得時点と保存先を同じ対象世代に固定する。 */
export function useFavoriteNameEdit(name: Ref<string>) {
  const { getTeam, updateTeam } = useTeamApi()
  const { getOrganization, updateOrganization } = useOrganizationApi()
  const { handleApiError } = useErrorHandler()
  const { t } = useI18n()
  const ready = ref(false)
  const error = ref<string | null>(null)
  let generation = 0
  onScopeDispose(() => {
    generation++
  })
  let target: { item: UserFavoriteItem; slug: string; version: number; generation: number } | null =
    null

  function fail(cause: unknown) {
    error.value = t('favorites.saveError')
    handleApiError(cause, 'favorites')
  }

  async function load(item: UserFavoriteItem | null) {
    const current = ++generation
    target = null
    ready.value = false
    error.value = null
    if (!item || !['TEAM', 'ORGANIZATION'].includes(item.entityType)) return
    try {
      // PR3644が提供する正準pageUrlを使う。数値entityIdへのfallbackはしない。
      const prefix = item.entityType === 'TEAM' ? 'teams' : 'organizations'
      const match = item.entity.pageUrl.match(
        new RegExp(`^/${prefix}/([a-z0-9][a-z0-9-]{1,28}[a-z0-9])$`),
      )
      const slug = match?.[1]
      if (!slug) throw new Error(t('favorites.saveError'))
      const response =
        item.entityType === 'TEAM' ? await getTeam(slug) : await getOrganization(slug)
      if (current !== generation) return
      const detail = response.data
      const version = detail.metadata?.version
      const loadedName = detail.basicInfo?.name
      if (
        String(detail.numericId) !== item.entityId ||
        typeof version !== 'number' ||
        !Number.isSafeInteger(version) ||
        version < 0 ||
        typeof loadedName !== 'string'
      ) {
        throw new Error(t('favorites.saveError'))
      }
      name.value = loadedName
      target = { item, slug, version, generation: current }
      ready.value = true
    } catch (cause) {
      if (current === generation) fail(cause)
    }
  }

  async function save(item: UserFavoriteItem): Promise<boolean> {
    const selected = target
    if (!ready.value || !selected || selected.item !== item) return false
    error.value = null
    try {
      if (item.entityType === 'TEAM') {
        const body: components['schemas']['UpdateTeamRequest'] = {
          name: name.value.trim(),
          version: selected.version,
        }
        await updateTeam(selected.slug, body)
      } else {
        const body: components['schemas']['UpdateOrganizationRequest'] = {
          name: name.value.trim(),
          version: selected.version,
        }
        await updateOrganization(selected.slug, body)
      }
      return selected.generation === generation
    } catch (cause) {
      if (selected.generation === generation) fail(cause)
      return false
    }
  }

  return { ready, error, load, save }
}
