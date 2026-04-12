<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
  layout: 'default',
})

const orgApi = useOrganizationApi()
const orgStore = useOrganizationStore()
const { handleApiError } = useErrorHandler()
const notification = useNotification()

const followedOrgIds = ref<number[]>([])
const followingOrgIds = ref<number[]>([])

const myOrgIds = computed(() => new Set(orgStore.myOrganizations.map((o) => o.id)))

async function followOrg(orgId: number, event: Event) {
  event.stopPropagation()
  followingOrgIds.value.push(orgId)
  try {
    await orgApi.followOrganization(orgId)
    followedOrgIds.value.push(orgId)
    notification.success('サポーターとして登録しました')
  } catch {
    notification.error('フォローに失敗しました')
  } finally {
    followingOrgIds.value = followingOrgIds.value.filter((id) => id !== orgId)
  }
}

interface OrgSummary {
  id: number
  name: string
  nickname1: string | null
  iconUrl: string | null
  prefecture: string | null
  city: string | null
  memberCount: number
  supporterEnabled: boolean
}

const organizations = ref<OrgSummary[]>([])
const loading = ref(false)
const totalRecords = ref(0)
const currentPage = ref(0)
const pageSize = 20
const showCreateDialog = ref(false)

const searchParams = ref({
  keyword: '',
  prefecture: '',
})

async function fetchOrganizations() {
  loading.value = true
  try {
    const result = await orgApi.searchOrganizations({
      keyword: searchParams.value.keyword || undefined,
      prefecture: searchParams.value.prefecture || undefined,
      page: currentPage.value,
      size: pageSize,
    })
    organizations.value = result.data
    totalRecords.value = result.meta.totalElements
  } catch (error) {
    handleApiError(error, '組織検索')
  } finally {
    loading.value = false
  }
}

function onSearch(params: { keyword: string; prefecture: string; orgType: string }) {
  searchParams.value = { keyword: params.keyword, prefecture: params.prefecture }
  currentPage.value = 0
  fetchOrganizations()
}

function onPageChange(event: { page: number }) {
  currentPage.value = event.page
  fetchOrganizations()
}

function onOrgCreated(entity: { id: number; name: string }) {
  navigateTo(`/organizations/${entity.id}`)
}

function formatLocation(prefecture: string | null, city: string | null): string {
  return [prefecture, city].filter(Boolean).join(' ') || '-'
}

onMounted(() => {
  fetchOrganizations()
})
</script>

<template>
  <div class="mx-auto max-w-6xl p-6">
    <div class="mb-6 flex items-center justify-between">
      <PageHeader title="組織検索" />
      <Button label="組織を作成" icon="pi pi-plus" @click="showCreateDialog = true" />
    </div>

    <div class="mb-6">
      <SearchBar placeholder="組織名で検索" :show-org-type-filter="true" @search="onSearch" />
    </div>

    <PageLoading v-if="loading" />

    <DashboardEmptyState
      v-else-if="organizations.length === 0"
      icon="pi pi-search"
      message="該当する組織が見つかりませんでした"
    />

    <template v-else>
      <div class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <div
          v-for="org in organizations"
          :key="org.id"
          class="cursor-pointer rounded-lg border p-4 transition-shadow hover:shadow-md"
          @click="navigateTo(`/organizations/${org.id}`)"
        >
          <div class="mb-3 flex items-center gap-3">
            <Avatar
              :image="org.iconUrl ?? undefined"
              :label="org.iconUrl ? undefined : org.name.charAt(0)"
              shape="circle"
              size="large"
            />
            <div class="min-w-0 flex-1">
              <h3 class="truncate font-semibold">
                {{ org.nickname1 || org.name }}
              </h3>
            </div>
          </div>
          <div class="flex items-center justify-between text-sm text-gray-500">
            <span
              ><i class="pi pi-map-marker mr-1" />{{
                formatLocation(org.prefecture, org.city)
              }}</span
            >
            <span><i class="pi pi-users mr-1" />{{ org.memberCount }}人</span>
          </div>
          <div
            v-if="org.supporterEnabled && !myOrgIds.has(org.id)"
            class="mt-3 border-t border-surface-100 pt-3"
          >
            <span
              v-if="followedOrgIds.includes(org.id)"
              class="flex items-center gap-1 text-sm text-primary"
            >
              <i class="pi pi-heart-fill" />サポーター登録済み
            </span>
            <Button
              v-else
              label="サポーターになる"
              icon="pi pi-heart"
              size="small"
              severity="secondary"
              outlined
              class="w-full"
              :loading="followingOrgIds.includes(org.id)"
              @click="followOrg(org.id, $event)"
            />
          </div>
        </div>
      </div>

      <div class="mt-6">
        <Paginator
          :rows="pageSize"
          :total-records="totalRecords"
          :first="currentPage * pageSize"
          @page="onPageChange"
        />
      </div>
    </template>

    <EntityCreateDialog
      entity-type="organization"
      :visible="showCreateDialog"
      @update:visible="showCreateDialog = $event"
      @created="onOrgCreated"
    />
  </div>
</template>
