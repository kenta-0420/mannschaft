<script setup lang="ts">
import type { OrgTeam } from '~/types/organization'
import { getLoadErrorHttpStatus } from '~/utils/loadError'

/** グループ一覧と従来のカード一覧に共通の除名操作。所属関係だけを解除する。 */
const props = defineProps<{
  orgSlug: string
  team: OrgTeam
  canRemove: boolean
  disabled: boolean
  refreshAfterRemoval: (orgSlug: string) => Promise<void>
}>()
const emit = defineEmits<{ reload: [] }>()
const { t } = useI18n()
const confirm = useConfirm()
const notification = useNotification()
const { handleApiError } = useErrorHandler()
const { removeTeam } = useOrgTeamMembershipApi()
const busy = ref(false)
const errorKind = ref<'forbidden' | 'missing' | 'generic' | null>(null)
let revision = 0
let mounted = true

// 確認中にスコープ・権限・一覧が変わった場合、古い確認の承認では送信しない。
watch(
  [() => props.orgSlug, () => props.team, () => props.canRemove, () => props.disabled],
  () => {
    revision += 1
    errorKind.value = null
  },
  { flush: 'sync' },
)
onUnmounted(() => {
  mounted = false
  revision += 1
})

function askRemove() {
  if (!props.canRemove || props.disabled || busy.value || !props.team.slug) return
  // 別groupの確認を残さず、専用の除名確認だけを開く。
  confirm.close()
  const requestedRevision = revision
  const orgSlug = props.orgSlug
  const teamSlug = props.team.slug
  const refreshAfterRemoval = props.refreshAfterRemoval
  confirm.require({
    group: 'team-affiliation-remove',
    header: t('orgMemberTeams.remove.title'),
    message: t('teamAffiliation.remove.confirm', { team: props.team.nickname1 || props.team.name }),
    icon: 'pi pi-exclamation-triangle',
    acceptLabel: t('teamAffiliation.remove.button'),
    rejectLabel: t('button.cancel'),
    acceptClass: 'min-h-11 min-w-11',
    rejectClass: 'min-h-11 min-w-11',
    acceptProps: { severity: 'danger' },
    rejectProps: { text: true },
    accept: async () => {
      if (
        !mounted ||
        requestedRevision !== revision ||
        busy.value ||
        props.disabled ||
        !props.canRemove
      )
        return
      busy.value = true
      errorKind.value = null
      try {
        await removeTeam(orgSlug, teamSlug)
        // view・絞り込みでボタンが消えても、受理済みの結果は常駐シェルへ同期する。
        const refreshed = refreshAfterRemoval(orgSlug)
        if (mounted && props.orgSlug === orgSlug && props.team.slug === teamSlug) {
          notification.success(t('orgMemberTeams.remove.success'))
        }
        await refreshed
      } catch (error) {
        if (!mounted || requestedRevision !== revision) return
        const status = getLoadErrorHttpStatus(error)
        errorKind.value = status === 403 ? 'forbidden' : status === 404 ? 'missing' : 'generic'
        handleApiError(error, 'org-team-remove')
      } finally {
        busy.value = false
      }
    },
  })
}
</script>

<template>
  <div v-if="canRemove" class="space-y-2" @click.stop>
    <Button
      :label="t('teamAffiliation.remove.button')"
      icon="pi pi-user-minus"
      severity="danger"
      outlined
      size="small"
      class="min-h-11"
      :disabled="disabled || busy"
      :loading="busy"
      :data-testid="`team-remove-${team.slug}`"
      @click="askRemove"
    />
    <Message v-if="errorKind" severity="error" :closable="false" data-testid="team-remove-error">
      {{ t(`orgMemberTeams.remove.error.${errorKind}`) }}
    </Message>
    <Button
      v-if="errorKind === 'missing'"
      :label="t('orgMemberTeams.groups.retry')"
      icon="pi pi-refresh"
      text
      class="min-h-11"
      :disabled="disabled || busy"
      data-testid="team-remove-reload"
      @click="emit('reload')"
    />
  </div>
</template>
