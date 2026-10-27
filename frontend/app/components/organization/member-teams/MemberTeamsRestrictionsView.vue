<script setup lang="ts">
import type { TeamOrgAffiliationRestriction } from '~/composables/useOrgTeamMembershipApi'
import { formatAffiliationDate } from '~/utils/memberTeamsView'

/**
 * F01.2.1 8-B — 組織が止めている申請（冷却期間・ブロック）の一覧と解除。
 *
 * 組織 ADMIN・管理レンズ ON のときだけ親（member-teams）が描画する。
 * 解除は相手チームが再び申請できるようにするだけの操作なので、確認は挟まない。
 */
const props = defineProps<{
  orgSlug: string
}>()

const { t, locale } = useI18n()
const toast = useToast()
const { listRestrictions, liftRestriction } = useOrgTeamMembershipApi()

const PAGE_SIZE = 20

const items = ref<TeamOrgAffiliationRestriction[]>([])
const loading = ref(true)
const loadFailed = ref(false)
const page = ref(0)
const totalPages = ref(1)
const liftingId = ref<string | null>(null)
const liftFailed = ref(false)

async function load() {
  loading.value = true
  loadFailed.value = false
  try {
    const result = await listRestrictions(props.orgSlug, page.value, PAGE_SIZE)
    items.value = result.items
    totalPages.value = result.totalPages
    if (result.items.length === 0 && page.value > 0) {
      page.value -= 1
    }
  }
  catch {
    // 取得失敗は「制限なし」に見せず、エラー表示にする（症状を隠さない）。
    items.value = []
    loadFailed.value = true
  }
  finally {
    loading.value = false
  }
}

watch(page, () => void load())

async function lift(restriction: TeamOrgAffiliationRestriction) {
  if (!restriction.id) return
  liftingId.value = restriction.id
  liftFailed.value = false
  try {
    await liftRestriction(props.orgSlug, restriction.id)
    toast.add({ severity: 'success', summary: t('orgMemberTeams.restrictions.lifted'), life: 3000 })
    await load()
  }
  catch {
    // 失敗はエラー表示にし、解除できたように一覧を書き換えない。
    liftFailed.value = true
  }
  finally {
    liftingId.value = null
  }
}

function kindLabel(restriction: TeamOrgAffiliationRestriction): string {
  return restriction.kind === 'COOLDOWN'
    ? t('teamAffiliation.restriction.kind_COOLDOWN', { until: formatAffiliationDate(restriction.restrictedUntil, locale.value) })
    : t('teamAffiliation.restriction.kind_BLOCK')
}

onMounted(() => void load())
</script>

<template>
  <div data-testid="member-teams-restrictions">
    <Message v-if="liftFailed" severity="error" class="mb-3" data-testid="restriction-lift-error">
      {{ t('orgMemberTeams.restrictions.lift_error') }}
    </Message>

    <div v-if="loading" class="flex justify-center py-8">
      <ProgressSpinner style="width: 2rem; height: 2rem" />
    </div>

    <Message v-else-if="loadFailed" severity="error" data-testid="restrictions-load-error">
      {{ t('orgMemberTeams.restrictions.load_error') }}
    </Message>

    <p
      v-else-if="items.length === 0"
      class="py-6 text-center text-sm text-surface-500"
      data-testid="restrictions-empty"
    >
      {{ t('orgMemberTeams.restrictions.empty') }}
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
            {{ restriction.team?.name }}
          </p>
          <p
            class="text-xs font-medium text-amber-600 dark:text-amber-400"
            :data-kind="restriction.kind"
            :data-testid="`restriction-kind-${restriction.id}`"
          >
            {{ kindLabel(restriction) }}
          </p>
          <p v-if="restriction.reason" class="whitespace-pre-line text-sm text-surface-700 dark:text-surface-200">
            {{ restriction.reason }}
          </p>
        </div>
        <Button
          :label="t('teamAffiliation.restriction.lift')"
          icon="pi pi-unlock"
          size="small"
          outlined
          :loading="liftingId === restriction.id"
          :data-testid="`restriction-lift-${restriction.id}`"
          @click="lift(restriction)"
        />
      </li>
    </ul>

    <MemberTeamsPager v-model:page="page" :total-pages="totalPages" />
  </div>
</template>
