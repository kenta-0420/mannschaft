<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
  layout: 'default',
})

const route = useRoute()
const teamApi = useTeamApi()
const notification = useNotification()
const { handleApiError } = useErrorHandler()
const { templateLabel, visibilityLabel } = useScopeLabels()

const teamId = computed(() => Number(route.params.id))
const {
  roleName,
  loading: roleLoading,
  loadPermissions,
  isAdmin,
  isAdminOrDeputy,
} = useRoleAccess('team', teamId)

const followStatus = ref<'NONE' | 'PENDING' | 'APPROVED'>('NONE')
const followLoading = ref(false)
const showCancelSupporterConfirm = ref(false)

async function fetchFollowStatus() {
  if (roleName.value) return
  try {
    const res = await teamApi.getFollowStatus(teamId.value)
    followStatus.value = res.data.status
  } catch {
    followStatus.value = 'NONE'
  }
}

async function applySupporter() {
  followLoading.value = true
  try {
    await teamApi.followTeam(teamId.value)
    const res = await teamApi.getFollowStatus(teamId.value)
    followStatus.value = res.data.status
    notification.success(
      followStatus.value === 'APPROVED'
        ? 'サポーターとして登録しました'
        : 'サポーター申請を送信しました',
    )
  } catch (error) {
    handleApiError(error, 'サポーター申請')
  } finally {
    followLoading.value = false
  }
}

async function cancelSupporter() {
  followLoading.value = true
  try {
    await teamApi.unfollowTeam(teamId.value)
    followStatus.value = 'NONE'
    showCancelSupporterConfirm.value = false
    notification.success('サポーターをやめました')
  } catch (error) {
    handleApiError(error, 'サポーター解除')
  } finally {
    followLoading.value = false
  }
}

interface TeamDetail {
  id: number
  name: string
  nameKana: string | null
  nickname1: string | null
  nickname2: string | null
  template: string
  prefecture: string | null
  city: string | null
  description: string | null
  visibility: string
  supporterEnabled: boolean
  version: number
  memberCount: number
  supporterCount?: number
  archivedAt: string | null
  createdAt: string
}

const team = ref<TeamDetail | null>(null)
const loading = ref(false)
const activeTab = ref(0)
const showLeaveConfirm = ref(false)

const displayName = computed(() => team.value?.nickname1 || team.value?.name || '')

async function fetchTeam() {
  loading.value = true
  try {
    const result = await teamApi.getTeam(teamId.value)
    team.value = result.data
  } catch (error) {
    handleApiError(error, 'チーム詳細取得')
  } finally {
    loading.value = false
  }
}

async function leaveTeam() {
  try {
    await teamApi.leaveTeam(teamId.value)
    notification.success('チームから退出しました')
    navigateTo('/dashboard')
  } catch (error) {
    handleApiError(error, 'チーム退出')
  } finally {
    showLeaveConfirm.value = false
  }
}

onMounted(async () => {
  await Promise.all([fetchTeam(), loadPermissions()])
  await fetchFollowStatus()
})
</script>

<template>
  <div class="mx-auto max-w-6xl p-6">
    <div v-if="loading || roleLoading" class="flex justify-center py-12">
      <ProgressSpinner style="width: 48px; height: 48px" />
    </div>

    <template v-else-if="team">
      <TeamHeaderBar
        :team-name="displayName"
        :template="team.template"
        :template-label="templateLabel[team.template] ?? team.template"
        :role-name="roleName"
        :is-admin="isAdmin"
        :member-count="team.memberCount"
        :supporter-enabled="team.supporterEnabled"
        :supporter-count="team.supporterCount"
        :follow-status="followStatus"
        :follow-loading="followLoading"
        @back="navigateTo('/dashboard')"
        @apply-supporter="applySupporter"
        @cancel-supporter="cancelSupporter"
        @show-cancel-confirm="showCancelSupporterConfirm = true"
        @show-leave-confirm="showLeaveConfirm = true"
      />

      <Tabs v-model:value="activeTab">
        <TabList>
          <Tab :value="0"> ダッシュボード </Tab>
          <Tab :value="1"> 基本情報 </Tab>
          <Tab :value="2"> メンバー </Tab>
          <Tab v-if="isAdminOrDeputy" :value="3"> 招待 </Tab>
          <Tab v-if="isAdmin && team.supporterEnabled" :value="4"> サポーター管理 </Tab>
          <Tab v-if="isAdmin" :value="5"> 機能設定 </Tab>
        </TabList>

        <TabPanels>
          <TabPanel :value="0">
            <div class="mt-4">
              <ScopeDashboard
                scope-type="team"
                :scope-id="teamId"
                :scope-name="displayName"
                :scope-template="team.template"
              />
            </div>
          </TabPanel>

          <TabPanel :value="1">
            <TeamDetailInfo
              :team-id="teamId"
              :name="team.name"
              :name-kana="team.nameKana"
              :nickname1="team.nickname1"
              :nickname2="team.nickname2"
              :template="team.template"
              :template-label="templateLabel[team.template] ?? team.template"
              :prefecture="team.prefecture"
              :city="team.city"
              :visibility="team.visibility"
              :visibility-label="visibilityLabel[team.visibility] ?? team.visibility"
              :member-count="team.memberCount"
              :supporter-enabled="team.supporterEnabled"
              :description="team.description"
              :is-admin="isAdmin"
            />
          </TabPanel>

          <TabPanel :value="2">
            <div class="mt-4">
              <MemberTable
                scope-type="team"
                :scope-id="teamId"
                :can-change-role="isAdminOrDeputy"
                :can-remove="isAdminOrDeputy"
              />
            </div>
          </TabPanel>

          <TabPanel v-if="isAdminOrDeputy" :value="3">
            <div class="mt-4">
              <InviteTokenList scope-type="team" :scope-id="teamId" />
            </div>
          </TabPanel>

          <TabPanel v-if="isAdmin && team.supporterEnabled" :value="4">
            <div class="mt-4">
              <SupporterManagementPanel scope-type="team" :scope-id="teamId" />
            </div>
          </TabPanel>

          <TabPanel v-if="isAdmin" :value="5">
            <div class="mt-4">
              <ModuleSettingsPanel scope-type="team" :scope-id="teamId" />
            </div>
          </TabPanel>
        </TabPanels>
      </Tabs>

      <Dialog
        v-model:visible="showCancelSupporterConfirm"
        header="サポーターをやめますか？"
        :style="{ width: '400px' }"
        modal
      >
        <p>{{ displayName }}のサポーターをやめます。よろしいですか？</p>
        <template #footer>
          <Button label="キャンセル" text @click="showCancelSupporterConfirm = false" />
          <Button
            label="やめる"
            severity="danger"
            :loading="followLoading"
            @click="cancelSupporter"
          />
        </template>
      </Dialog>

      <Dialog
        v-model:visible="showLeaveConfirm"
        header="チームから退出"
        :style="{ width: '400px' }"
        modal
      >
        <p>本当にこのチームから退出しますか？この操作は取り消せません。</p>
        <template #footer>
          <Button label="キャンセル" text @click="showLeaveConfirm = false" />
          <Button label="退出する" severity="danger" @click="leaveTeam" />
        </template>
      </Dialog>
    </template>
  </div>
</template>
