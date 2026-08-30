<script setup lang="ts">
import type {
  CreateRecruitmentListingRequest,
  RecruitmentCategoryResponse,
  RecruitmentListingStatus,
  RecruitmentListingSummaryResponse,
  UpdateRecruitmentListingRequest,
} from '~/types/recruitment'
import type { MarketAudienceScope, PersonalMarketMatch } from '~/types/market'

definePageMeta({ middleware: 'auth' })

const PAGE_SIZE = 20
const { t, locale } = useI18n()
const api = useRecruitmentApi()
const teamStore = useTeamStore()
const organizationStore = useOrganizationStore()
const confirm = useConfirm()
const notification = useNotification()
const { handleApiError } = useErrorHandler()
const { page, rows, totalRecords, reset } = usePagination(PAGE_SIZE)
const {
  prefectures,
  cities,
  citiesLoading,
  selectedPrefecture,
  selectedCity,
  loadPrefectures,
  selectPrefecture,
} = useMarketRegions()

const listings = ref<RecruitmentListingSummaryResponse[]>([])
const matches = ref<PersonalMarketMatch[]>([])
const categories = ref<RecruitmentCategoryResponse[]>([])
const loading = ref(true)
const creating = ref(false)
const showCreateForm = ref(false)
const selectedListingId = ref<number | null>(null)
const matchesPage = ref(0)
const matchesTotalRecords = ref(0)
const status = ref<RecruitmentListingStatus | null>(null)
const categoryId = ref<number | null>(null)
const editingListing = ref<RecruitmentListingSummaryResponse | null>(null)
const editForm = reactive<UpdateRecruitmentListingRequest>({})
const savingEdit = ref(false)
const publishingListingId = ref<number | null>(null)
const visibility = ref<'PUBLIC' | 'SELECTED_SCOPES' | 'SCOPE_ONLY'>('SCOPE_ONLY')
const selectedAudienceKeys = ref<string[]>([])
const editDialogVisible = computed({
  get: () => editingListing.value !== null,
  set: (visible: boolean) => {
    if (!visible) editingListing.value = null
  },
})

const totalPages = computed(() => Math.ceil(totalRecords.value / rows.value))
const matchesTotalPages = computed(() => Math.ceil(matchesTotalRecords.value / PAGE_SIZE))
const audienceOptions = computed(() => [
  ...teamStore.myTeams.map((team) => ({
    key: `TEAM:${team.id}`,
    label: team.nickname1 || team.name,
    scopeType: 'TEAM' as const,
    scopeId: team.id,
  })),
  ...organizationStore.myOrganizations.map((organization) => ({
    key: `ORGANIZATION:${organization.id}`,
    label: organization.nickname1 || organization.name,
    scopeType: 'ORGANIZATION' as const,
    scopeId: organization.id,
  })),
])

function audienceScopes(): MarketAudienceScope[] {
  const selected = new Set(selectedAudienceKeys.value)
  return audienceOptions.value
    .filter((option) => selected.has(option.key))
    .map(({ scopeType, scopeId }) => ({ scopeType, scopeId }))
}

async function loadListings() {
  loading.value = true
  try {
    const result = await api.listMyMarketListings({
      status: status.value ?? undefined,
      prefectureCode: selectedPrefecture.value ?? undefined,
      cityCode: selectedCity.value ?? undefined,
      categoryId: categoryId.value ?? undefined,
      page: page.value,
      size: rows.value,
    })
    listings.value = result.data
    totalRecords.value = result.meta.totalElements
  } catch (cause) {
    handleApiError(cause, t('market.personal.loadFailed'))
  } finally {
    loading.value = false
  }
}

async function reloadFromFirstPage() {
  reset()
  await loadListings()
}

async function changePage(nextPage: number) {
  page.value = nextPage
  await loadListings()
}

async function onPrefectureChange(code: string | null) {
  await selectPrefecture(code)
  await reloadFromFirstPage()
}

async function loadMatches(listingId: number, targetPage = 0) {
  selectedListingId.value = listingId
  matchesPage.value = targetPage
  try {
    const result = await api.listMyMarketMatches(listingId, { page: targetPage, size: PAGE_SIZE })
    matches.value = result.data
    matchesTotalRecords.value = result.meta.totalElements
  } catch (cause) {
    handleApiError(cause, t('market.personal.loadFailed'))
  }
}

async function createListing(value: CreateRecruitmentListingRequest) {
  if (visibility.value === 'SELECTED_SCOPES' && audienceScopes().length === 0) {
    notification.warn(t('market.personal.audienceRequired'))
    return
  }
  creating.value = true
  try {
    await api.createMyMarketListing({
      ...value,
      paymentEnabled: false,
      price: null,
      visibility: visibility.value,
      audienceScopes: visibility.value === 'SELECTED_SCOPES' ? audienceScopes() : [],
      payeeKind: null,
      payeeUserId: null,
    })
    notification.success(t('market.personal.created'))
    showCreateForm.value = false
    await reloadFromFirstPage()
  } catch (cause) {
    handleApiError(cause, t('market.personal.saveFailed'))
  } finally {
    creating.value = false
  }
}

function openEditor(listing: RecruitmentListingSummaryResponse) {
  editingListing.value = listing
  Object.assign(editForm, {
    title: listing.title,
    startAt: listing.startAt,
    endAt: listing.endAt,
    applicationDeadline: listing.applicationDeadline,
    capacity: listing.capacity,
    minCapacity: listing.minCapacity,
    location: listing.location,
    visibility: listing.visibility,
    audienceScopes: listing.audienceScopes ?? [],
  })
  visibility.value = listing.visibility === 'PUBLIC' || listing.visibility === 'SELECTED_SCOPES'
    ? listing.visibility
    : 'SCOPE_ONLY'
  selectedAudienceKeys.value = (listing.audienceScopes ?? [])
    .map((scope) => `${scope.scopeType}:${scope.scopeId}`)
}

async function saveEdit() {
  if (!editingListing.value) return
  if (visibility.value === 'SELECTED_SCOPES' && audienceScopes().length === 0) {
    notification.warn(t('market.personal.audienceRequired'))
    return
  }
  savingEdit.value = true
  try {
    await api.updateMyMarketListing(editingListing.value.id, {
      ...editForm,
      visibility: visibility.value,
      audienceScopes: visibility.value === 'SELECTED_SCOPES' ? audienceScopes() : [],
    })
    notification.success(t('market.personal.updated'))
    editingListing.value = null
    await loadListings()
  } catch (cause) {
    handleApiError(cause, t('market.personal.saveFailed'))
  } finally {
    savingEdit.value = false
  }
}

async function publishListing(listingId: number) {
  publishingListingId.value = listingId
  try {
    await api.publishMyMarketListing(listingId)
    notification.success(t('market.personal.published'))
    await loadListings()
  } catch (cause) {
    handleApiError(cause, t('market.personal.saveFailed'))
  } finally {
    publishingListingId.value = null
  }
}

function confirmCancel(listingId: number) {
  confirm.require({
    header: t('market.personal.cancel'),
    message: t('market.personal.cancelConfirm'),
    icon: 'pi pi-exclamation-triangle',
    rejectLabel: t('market.personal.close'),
    acceptLabel: t('market.personal.cancel'),
    acceptClass: 'p-button-danger',
    accept: () => void cancelDraft(listingId),
  })
}

async function cancelDraft(listingId: number) {
  try {
    await api.cancelMyMarketListing(listingId, { reason: t('market.personal.cancelReason') })
    notification.success(t('market.personal.cancelled'))
    await loadListings()
  } catch (cause) {
    handleApiError(cause, t('market.personal.saveFailed'))
  }
}

function formatDate(value: string): string {
  return new Date(value).toLocaleString(locale.value)
}

onMounted(async () => {
  await Promise.all([
    teamStore.myTeams.length ? Promise.resolve() : teamStore.fetchMyTeams(),
    organizationStore.myOrganizations.length ? Promise.resolve() : organizationStore.fetchMyOrganizations(),
  ])
  await loadPrefectures()
  try {
    categories.value = (await api.listCategories()).data
  } catch (cause) {
    handleApiError(cause, t('market.personal.loadFailed'))
  }
  await loadListings()
})
</script>

<template>
  <main class="container mx-auto max-w-5xl p-4">
    <ConfirmDialog />
    <PageHeader :title="t('market.personal.title')">
      <template #actions>
        <Button
          class="min-h-11"
          :label="t('market.personal.create')"
          icon="pi pi-plus"
          @click="showCreateForm = !showCreateForm"
        />
      </template>
    </PageHeader>
    <p class="mb-4 text-sm text-surface-600 dark:text-surface-300">
      {{ t('market.personal.description') }}
    </p>

    <SectionCard v-if="showCreateForm" :title="t('market.personal.create')">
      <p class="mb-4 text-sm text-surface-500">{{ t('market.personal.visibilityHelp') }}</p>
      <div class="mb-4 flex flex-col gap-3">
        <label for="personal-market-visibility" class="font-medium">{{ t('market.personal.visibility') }}</label>
        <Select
          id="personal-market-visibility"
          v-model="visibility"
          :options="[
            { value: 'PUBLIC', label: t('market.personal.visibilityPublic') },
            { value: 'SELECTED_SCOPES', label: t('market.personal.visibilitySelectedScopes') },
            { value: 'SCOPE_ONLY', label: t('market.personal.visibilityDraft') },
          ]"
          option-label="label"
          option-value="value"
        />
        <div v-if="visibility === 'SELECTED_SCOPES'" class="flex flex-col gap-2">
          <label for="personal-market-audience">{{ t('market.personal.audience') }}</label>
          <MultiSelect
            id="personal-market-audience"
            v-model="selectedAudienceKeys"
            :options="audienceOptions"
            option-label="label"
            option-value="key"
            :placeholder="t('market.personal.audiencePlaceholder')"
            display="chip"
          />
        </div>
      </div>
      <RecruitmentListingForm
        :categories="categories"
        :initial="{
          paymentEnabled: false,
          visibility,
          participationType: 'INDIVIDUAL',
        }"
        :loading="creating"
        :submit-label="t('market.personal.create')"
        scope-type="PERSONAL"
        hide-payment
        hide-visibility
        @submit="createListing"
      />
    </SectionCard>

    <SectionCard class="mt-6" :title="t('market.personal.list')">
      <div class="mb-4 grid grid-cols-1 gap-3 md:grid-cols-2 lg:grid-cols-4">
        <Select
          v-model="status"
          :options="['DRAFT', 'OPEN', 'FULL', 'CANCELLED']"
          :placeholder="t('market.personal.allStatuses')"
          show-clear
          @change="reloadFromFirstPage"
        />
        <Select
          v-model="selectedPrefecture"
          :options="prefectures"
          option-label="name"
          option-value="code"
          :placeholder="t('market.filter.prefecture')"
          show-clear
          @update:model-value="onPrefectureChange"
        />
        <Select
          v-model="selectedCity"
          :options="cities"
          option-label="name"
          option-value="code"
          :loading="citiesLoading"
          :disabled="!selectedPrefecture"
          :placeholder="t('market.filter.city')"
          show-clear
          @change="reloadFromFirstPage"
        />
        <Select
          v-model="categoryId"
          :options="categories"
          option-label="nameI18nKey"
          option-value="id"
          :placeholder="t('market.filter.category')"
          show-clear
          @change="reloadFromFirstPage"
        >
          <template #option="{ option }">{{ t(option.nameI18nKey) }}</template>
          <template #value="{ value }">{{
            value
              ? t(categories.find((category) => category.id === value)?.nameI18nKey ?? '')
              : t('market.filter.category')
          }}</template>
        </Select>
      </div>

      <PageLoading v-if="loading" />
      <DashboardEmptyState
        v-else-if="listings.length === 0"
        icon="pi pi-file-edit"
        :message="t('market.personal.empty')"
      >
        <template #action
          ><Button
            class="min-h-11"
            :label="t('market.personal.create')"
            @click="showCreateForm = true"
        /></template>
      </DashboardEmptyState>
      <div v-else class="space-y-3">
        <article
          v-for="listing in listings"
          :key="listing.id"
          class="min-w-0 rounded-lg border border-surface-200 p-4 dark:border-surface-700"
        >
          <div class="flex min-w-0 items-start justify-between gap-3">
            <h2 class="min-w-0 break-words font-semibold">{{ listing.title }}</h2>
            <Tag :value="t(`market.status.${listing.status}`)" />
          </div>
          <div class="mt-3 flex flex-wrap gap-2">
            <Button
              class="min-h-11"
              :label="t('market.personal.matches')"
              icon="pi pi-users"
              @click="loadMatches(listing.id)"
            />
            <Button
              v-if="listing.status === 'DRAFT'"
              class="min-h-11"
              :label="t('market.personal.publish')"
              icon="pi pi-send"
              :loading="publishingListingId === listing.id"
              @click="publishListing(listing.id)"
            />
            <Button
              v-if="listing.status === 'DRAFT'"
              class="min-h-11"
              severity="secondary"
              outlined
              :label="t('market.personal.edit')"
              icon="pi pi-pencil"
              @click="openEditor(listing)"
            />
            <Button
              v-if="listing.status === 'DRAFT'"
              class="min-h-11"
              severity="danger"
              outlined
              :label="t('market.personal.cancel')"
              icon="pi pi-times"
              @click="confirmCancel(listing.id)"
            />
          </div>
        </article>
      </div>

      <div v-if="totalPages > 1" class="mt-4 flex justify-between gap-3">
        <Button
          class="min-h-11"
          :disabled="page === 0"
          :label="t('market.personal.previous')"
          @click="changePage(page - 1)"
        />
        <Button
          class="min-h-11"
          :disabled="page + 1 >= totalPages"
          :label="t('market.personal.next')"
          @click="changePage(page + 1)"
        />
      </div>
    </SectionCard>

    <SectionCard
      v-if="selectedListingId !== null"
      class="mt-6"
      :title="t('market.personal.matches')"
    >
      <DashboardEmptyState
        v-if="matches.length === 0"
        icon="pi pi-users"
        :message="t('market.personal.noMatches')"
      />
      <ul v-else class="space-y-2">
        <li
          v-for="match in matches"
          :key="match.participantId"
          class="flex flex-wrap justify-between gap-3"
        >
          <Tag :value="t(`recruitment.participantStatus.${match.status.toLowerCase()}`)" />
          <time :datetime="match.appliedAt">{{ formatDate(match.appliedAt) }}</time>
        </li>
      </ul>
      <div v-if="matchesTotalPages > 1" class="mt-4 flex justify-between gap-3">
        <Button
          class="min-h-11"
          :disabled="matchesPage === 0"
          :label="t('market.personal.previous')"
          @click="loadMatches(selectedListingId, matchesPage - 1)"
        />
        <Button
          class="min-h-11"
          :disabled="matchesPage + 1 >= matchesTotalPages"
          :label="t('market.personal.next')"
          @click="loadMatches(selectedListingId, matchesPage + 1)"
        />
      </div>
    </SectionCard>

    <Dialog
      v-model:visible="editDialogVisible"
      modal
      :header="t('market.personal.edit')"
      class="w-[min(92vw,36rem)]"
    >
      <form class="flex flex-col gap-4" @submit.prevent="saveEdit">
        <InputText v-model="editForm.title" required :placeholder="t('recruitment.field.title')" />
        <div class="grid grid-cols-1 gap-3 md:grid-cols-2">
          <InputText v-model="editForm.startAt" type="datetime-local" required />
          <InputText v-model="editForm.endAt" type="datetime-local" required />
          <InputText v-model="editForm.applicationDeadline" type="datetime-local" required />
          <InputNumber v-model="editForm.capacity" :min="1" required />
          <InputNumber v-model="editForm.minCapacity" :min="1" required />
        </div>
        <InputText v-model="editForm.location" :placeholder="t('recruitment.field.location')" />
        <div class="flex flex-col gap-3">
          <label for="personal-market-edit-visibility">{{ t('market.personal.visibility') }}</label>
          <Select
            id="personal-market-edit-visibility"
            v-model="visibility"
            :options="[
              { value: 'PUBLIC', label: t('market.personal.visibilityPublic') },
              { value: 'SELECTED_SCOPES', label: t('market.personal.visibilitySelectedScopes') },
              { value: 'SCOPE_ONLY', label: t('market.personal.visibilityDraft') },
            ]"
            option-label="label"
            option-value="value"
          />
          <MultiSelect
            v-if="visibility === 'SELECTED_SCOPES'"
            v-model="selectedAudienceKeys"
            :options="audienceOptions"
            option-label="label"
            option-value="key"
            :placeholder="t('market.personal.audiencePlaceholder')"
            display="chip"
          />
        </div>
        <div class="flex justify-end gap-2">
          <Button
            type="button"
            severity="secondary"
            outlined
            :label="t('market.personal.close')"
            @click="editingListing = null"
          />
          <Button type="submit" :loading="savingEdit" :label="t('market.personal.save')" />
        </div>
      </form>
    </Dialog>
  </main>
</template>
