<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
  layout: 'default',
})

const teamApi = useTeamApi()
const teamStore = useTeamStore()
const { handleApiError } = useErrorHandler()
const notification = useNotification()

const followedTeamIds = ref<number[]>([])
const followingTeamIds = ref<number[]>([])

const myTeamIds = computed(() => new Set(teamStore.myTeams.map((t) => t.id)))

async function followTeam(teamId: number, event: Event) {
  event.stopPropagation()
  followingTeamIds.value.push(teamId)
  try {
    await teamApi.followTeam(teamId)
    followedTeamIds.value.push(teamId)
    notification.success('サポーターとして登録しました')
  } catch {
    notification.error('フォローに失敗しました')
  } finally {
    followingTeamIds.value = followingTeamIds.value.filter((id) => id !== teamId)
  }
}

interface TeamSummary {
  id: number
  name: string
  nickname1: string | null
  iconUrl: string | null
  prefecture: string | null
  city: string | null
  template: string
  memberCount: number
  supporterEnabled: boolean
}

const teams = ref<TeamSummary[]>([])
const loading = ref(false)
const totalRecords = ref(0)
const currentPage = ref(0)
const pageSize = 20
const showCreateDialog = ref(false)

const searchParams = ref({
  keyword: '',
  prefecture: '',
  template: '',
})

const templateLabel: Record<string, string> = {
  SPORTS: 'スポーツ',
  CLINIC: 'クリニック',
  CLASS: 'クラス',
  COMMUNITY: 'コミュニティ',
  COMPANY: '企業',
  FAMILY: '家族',
  OTHER: 'その他',
}

async function fetchTeams() {
  loading.value = true
  try {
    const result = await teamApi.searchTeams({
      keyword: searchParams.value.keyword || undefined,
      prefecture: searchParams.value.prefecture || undefined,
      template: searchParams.value.template || undefined,
      page: currentPage.value,
      size: pageSize,
    })
    teams.value = result.data
    totalRecords.value = result.meta.totalElements
  } catch (error) {
    handleApiError(error, 'チーム検索')
  } finally {
    loading.value = false
  }
}

function onSearch(params: { keyword: string; prefecture: string; template: string }) {
  searchParams.value = {
    keyword: params.keyword,
    prefecture: params.prefecture,
    template: params.template,
  }
  currentPage.value = 0
  fetchTeams()
}

function onPageChange(event: { page: number }) {
  currentPage.value = event.page
  fetchTeams()
}

function onTeamCreated(entity: { id: number; name: string }) {
  navigateTo(`/teams/${entity.id}`)
}

function formatLocation(prefecture: string | null, city: string | null): string {
  return [prefecture, city].filter(Boolean).join(' ') || '-'
}

onMounted(() => {
  fetchTeams()
})
</script>

<template>
  <div class="mx-auto max-w-6xl p-6">
    <div class="mb-6 flex items-center justify-between">
      <h1 class="text-2xl font-bold">チーム検索</h1>
      <Button label="チームを作成" icon="pi pi-plus" @click="showCreateDialog = true" />
    </div>

    <div class="mb-6">
      <SearchBar placeholder="チーム名で検索" :show-template-filter="true" @search="onSearch" />
    </div>

    <PageLoading v-if="loading" />

    <div
      v-else-if="teams.length === 0"
      class="rounded-lg border border-dashed border-gray-300 p-12 text-center text-gray-500"
    >
      <i class="pi pi-search mb-2 text-4xl" />
      <p>該当するチームが見つかりませんでした</p>
    </div>

    <template v-else>
      <div class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <div
          v-for="team in teams"
          :key="team.id"
          class="cursor-pointer rounded-lg border p-4 transition-shadow hover:shadow-md"
          @click="navigateTo(`/teams/${team.id}`)"
        >
          <div class="mb-3 flex items-center gap-3">
            <Avatar
              :image="team.iconUrl ?? undefined"
              :label="team.iconUrl ? undefined : team.name.charAt(0)"
              shape="circle"
              size="large"
            />
            <div class="min-w-0 flex-1">
              <h3 class="truncate font-semibold">
                {{ team.nickname1 || team.name }}
              </h3>
              <Tag
                :value="templateLabel[team.template] ?? team.template"
                severity="info"
                class="text-xs"
              />
            </div>
          </div>
          <div class="flex items-center justify-between text-sm text-gray-500">
            <span
              ><i class="pi pi-map-marker mr-1" />{{
                formatLocation(team.prefecture, team.city)
              }}</span
            >
            <span><i class="pi pi-users mr-1" />{{ team.memberCount }}人</span>
          </div>
          <div
            v-if="team.supporterEnabled && !myTeamIds.has(team.id)"
            class="mt-3 border-t border-surface-100 pt-3"
          >
            <span
              v-if="followedTeamIds.includes(team.id)"
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
              :loading="followingTeamIds.includes(team.id)"
              @click="followTeam(team.id, $event)"
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
      entity-type="team"
      :visible="showCreateDialog"
      @update:visible="showCreateDialog = $event"
      @created="onTeamCreated"
    />
  </div>
</template>
