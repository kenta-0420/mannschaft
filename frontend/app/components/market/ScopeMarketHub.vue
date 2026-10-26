<script setup lang="ts">
import type { RecruitmentListingSummaryResponse } from '~/types/recruitment'
import type { MarketScopeType } from '~/utils/scopeMarket'
import { getScopeMarketRoutes } from '~/utils/scopeMarket'

interface Props {
  scopeType: MarketScopeType
  scopeId: string
}

const props = defineProps<Props>()

const { t } = useI18n()
const router = useRouter()
const recruitmentApi = useRecruitmentApi()
const { resolveOrganizationNumericId } = useOrganizationNumericId()
const { error, success } = useNotification()
const { isAdminOrDeputy, loadPermissions } = useRoleAccess(props.scopeType, toRef(props, 'scopeId'))

const listings = ref<RecruitmentListingSummaryResponse[]>([])
const loading = ref(false)
const selectedListingId = ref<number | null>(null)
const cancellingListingId = ref<number | null>(null)
const routes = computed(() => getScopeMarketRoutes(props.scopeType, props.scopeId))

async function loadListings() {
  loading.value = true
  listings.value = []
  selectedListingId.value = null
  try {
    const result = props.scopeType === 'team'
      ? await recruitmentApi.listTeamListings(props.scopeId)
      : await recruitmentApi.listOrganizationListings(
          await resolveOrganizationNumericId(props.scopeId),
        )
    listings.value = result.data
  } catch (e) {
    error(String(e))
  } finally {
    loading.value = false
  }
}

function goToCreate() {
  void router.push(routes.value.create)
}

function goToHistory() {
  void router.push(routes.value.history)
}

function selectForMatching(listingId: number) {
  selectedListingId.value = listingId
}

async function cancelListing(listing: RecruitmentListingSummaryResponse) {
  if (!window.confirm(t('recruitment.confirmModal.cancel.message'))) return
  cancellingListingId.value = listing.id
  try {
    await recruitmentApi.cancelListing(listing.id, {
      reason: t('recruitment.action.cancelListing'),
    })
    success(t('recruitment.action.cancelledListing'))
    await loadListings()
  } catch (cause) {
    error(String(cause))
  } finally {
    cancellingListingId.value = null
  }
}

onMounted(async () => {
  await Promise.all([loadPermissions(), loadListings()])
})

watch(
  () => props.scopeId,
  async (scopeId, previousScopeId) => {
    if (scopeId === previousScopeId) return
    await Promise.all([loadPermissions(), loadListings()])
  },
)
</script>

<template>
  <div class="container mx-auto max-w-5xl p-4">
    <PageHeader :title="t('market.management.title')" />
    <p class="mt-1 text-surface-600 dark:text-surface-300">
      {{ t('market.management.description') }}
    </p>

    <div class="mt-6 grid gap-4 md:grid-cols-3">
      <section class="rounded-lg border border-surface-200 p-5 dark:border-surface-700">
        <i class="pi pi-plus-circle text-2xl text-primary" />
        <h2 class="mt-3 text-lg font-semibold">{{ t('market.action.post') }}</h2>
        <p class="mt-2 text-sm text-surface-600 dark:text-surface-300">
          {{ t('market.management.postDescription') }}
        </p>
        <Button
          class="mt-4"
          :label="t('market.action.post')"
          icon="pi pi-plus"
          :disabled="!isAdminOrDeputy"
          :data-testid="`market-${scopeType}-post-link`"
          @click="goToCreate"
        />
        <p v-if="!isAdminOrDeputy" class="mt-2 text-sm text-surface-500">
          {{ t('market.management.permissionRequired') }}
        </p>
      </section>

      <section class="rounded-lg border border-surface-200 p-5 dark:border-surface-700">
        <i class="pi pi-list text-2xl text-primary" />
        <h2 class="mt-3 text-lg font-semibold">{{ t('market.management.history') }}</h2>
        <p class="mt-2 text-sm text-surface-600 dark:text-surface-300">
          {{ t('market.management.historyDescription') }}
        </p>
        <Button
          class="mt-4"
          :label="t('market.management.history')"
          icon="pi pi-arrow-right"
          @click="goToHistory"
        />
      </section>

      <section class="rounded-lg border border-surface-200 p-5 dark:border-surface-700">
        <i class="pi pi-users text-2xl text-primary" />
        <h2 class="mt-3 text-lg font-semibold">{{ t('market.management.matching') }}</h2>
        <p class="mt-2 text-sm text-surface-600 dark:text-surface-300">
          {{ t('market.management.matchingDescription') }}
        </p>
        <Button
          class="mt-4"
          :label="t('market.management.openMatching')"
          icon="pi pi-arrow-down"
          :disabled="!isAdminOrDeputy || listings.length === 0"
          @click="selectedListingId = listings[0]?.id ?? null"
        />
      </section>
    </div>

    <div class="mt-8">
      <h2 class="text-xl font-semibold">{{ t('market.management.matching') }}</h2>
      <div v-if="loading" class="flex justify-center p-8"><LoadingBounce /></div>
      <DashboardEmptyState
        v-else-if="listings.length === 0"
        icon="pi pi-list"
        :message="t('market.management.noListings')"
      />
      <div v-else class="mt-4 flex flex-col gap-3">
        <article
          v-for="listing in listings"
          :key="listing.id"
          class="rounded-lg border border-surface-200 p-3 dark:border-surface-700"
        >
          <RecruitmentListingCard :listing="listing" />
          <div class="mt-3 flex flex-wrap gap-2">
            <Button
              :label="t('market.management.manageParticipants')"
              icon="pi pi-users"
              size="small"
              :disabled="!isAdminOrDeputy"
              @click="selectForMatching(listing.id)"
            />
            <Button
              :label="t('recruitment.action.viewDetails')"
              icon="pi pi-external-link"
              size="small"
              severity="secondary"
              outlined
              @click="router.push(`/recruitment-listings/${listing.id}`)"
            />
            <Button
              v-if="!['CANCELLED', 'AUTO_CANCELLED', 'COMPLETED'].includes(listing.status)"
              :label="t('recruitment.action.cancelListing')"
              icon="pi pi-times"
              size="small"
              severity="danger"
              outlined
              :disabled="!isAdminOrDeputy"
              :loading="cancellingListingId === listing.id"
              :data-testid="`market-listing-cancel-${listing.id}`"
              @click="cancelListing(listing)"
            />
          </div>
        </article>
      </div>
    </div>

    <section
      v-if="selectedListingId != null && isAdminOrDeputy"
      class="mt-8 border-t border-surface-200 pt-6 dark:border-surface-700"
    >
      <RecruitmentParticipantManager :listing-id="selectedListingId" />
    </section>
  </div>
</template>
