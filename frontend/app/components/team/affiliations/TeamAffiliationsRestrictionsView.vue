<script setup lang="ts">
import type { TeamOrgAffiliationRestriction } from '~/composables/useOrgTeamMembershipApi'
import { formatAffiliationDate } from '~/utils/memberTeamsView'

/**
 * F01.2.1 8-D — チームが止めている組織からの招待（辞退による冷却・ブロック）の一覧と解除（設計書 §5.4・AC-D04）。
 *
 * チームの加盟操作者にだけ親（affiliations）が描画する。解除すると、その組織は再び招待できる。
 * 既に期限切れ・解除済みで消えた行を解除しようとしたときは、エラーを出して一覧を取り直す。
 */
const props = defineProps<{
  teamSlug: string
}>()

const { t, locale } = useI18n()
const toast = useToast()
const { listRestrictions, liftRestriction } = useTeamAffiliationApi()

const PAGE_SIZE = 20

const { items, loading, loadFailed, page, totalPages, load, reset } = useLatestPagedList<TeamOrgAffiliationRestriction>(
  p => listRestrictions(props.teamSlug, p, PAGE_SIZE),
)

watch(() => props.teamSlug, () => reset())

const busyId = ref<string | null>(null)
const actionFailed = ref(false)

async function lift(restriction: TeamOrgAffiliationRestriction) {
  if (busyId.value !== null || !restriction.id) return
  busyId.value = restriction.id
  actionFailed.value = false
  try {
    await liftRestriction(props.teamSlug, restriction.id)
    toast.add({ severity: 'success', summary: t('teamAffiliations.restrictions.lifted'), life: 3000 })
  }
  catch {
    actionFailed.value = true
  }
  finally {
    busyId.value = null
  }
  await load()
}

function kindLabel(kind: string | undefined): string {
  return kind === 'BLOCK'
    ? t('teamAffiliations.restrictions.kind_blocked')
    : t('teamAffiliations.restrictions.kind_cooldown')
}

onMounted(() => void load())
</script>

<template>
  <div data-testid="team-affiliations-restrictions">
    <p class="mb-3 text-sm text-surface-500">
      {{ t('teamAffiliations.restrictions.hint') }}
    </p>

    <Message v-if="actionFailed" severity="error" class="mb-3" data-testid="restriction-action-error">
      {{ t('teamAffiliations.restrictions.action_error') }}
    </Message>

    <div v-if="loading" class="flex justify-center py-8">
      <ProgressSpinner style="width: 2rem; height: 2rem" />
    </div>

    <Message v-else-if="loadFailed" severity="error" data-testid="restrictions-load-error">
      {{ t('teamAffiliations.restrictions.load_error') }}
    </Message>

    <p
      v-else-if="items.length === 0"
      class="py-6 text-center text-sm text-surface-500"
      data-testid="restrictions-empty"
    >
      {{ t('teamAffiliations.restrictions.empty') }}
    </p>

    <ul v-else class="divide-y divide-surface-200 dark:divide-surface-700">
      <li
        v-for="restriction in items"
        :key="restriction.id"
        class="flex flex-wrap items-start justify-between gap-3 py-3"
        data-testid="restriction-row"
      >
        <div class="min-w-0 flex-1 space-y-1">
          <p class="font-medium">
            {{ restriction.organization?.name }}
          </p>
          <p class="text-xs text-surface-500" data-testid="restriction-kind">
            {{ kindLabel(restriction.kind) }}
          </p>
          <p v-if="restriction.restrictedUntil" class="text-xs text-surface-500">
            {{ t('teamAffiliations.restrictions.until', { date: formatAffiliationDate(restriction.restrictedUntil, locale) }) }}
          </p>
          <p v-if="restriction.createdAt" class="text-xs text-surface-500">
            {{ t('teamAffiliations.restrictions.created_at', { date: formatAffiliationDate(restriction.createdAt, locale) }) }}
          </p>
        </div>
        <Button
          :label="t('teamAffiliations.restrictions.lift')"
          icon="pi pi-unlock"
          size="small"
          outlined
          :loading="busyId === restriction.id"
          :disabled="busyId !== null"
          :data-testid="`restriction-lift-${restriction.id}`"
          @click="lift(restriction)"
        />
      </li>
    </ul>

    <MemberTeamsPager v-model:page="page" :total-pages="totalPages" />
  </div>
</template>
