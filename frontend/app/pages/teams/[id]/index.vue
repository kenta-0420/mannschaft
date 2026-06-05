<script setup lang="ts">
import type { ViewerRole } from '~/types/dashboard'
import type { TeamResponse } from '~/types/team'
import FavoriteToggleButton from '~/components/favorites/FavoriteToggleButton.vue'

definePageMeta({
  middleware: 'auth',
  layout: 'default',
})

const route = useRoute()
const teamApi = useTeamApi()
const notification = useNotification()
const { handleApiError } = useErrorHandler()
const { templateLabel, visibilityLabel } = useScopeLabels()

const teamId = computed(() => String(route.params.id))
const {
  roleName,
  loading: roleLoading,
  loadPermissions,
  isAdmin,
  isAdminOrDeputy,
} = useRoleAccess('team', teamId)

const viewerRole = computed<ViewerRole>(() => (roleName.value as ViewerRole | null) ?? 'PUBLIC')

const {
  settings: widgetVisibilitySettings,
  fetch: fetchWidgetVisibility,
} = useDashboardWidgetVisibility('team', teamId)

const followStatus = ref<'NONE' | 'PENDING' | 'APPROVED'>('NONE')
const followLoading = ref(false)
const showCancelSupporterConfirm = ref(false)

// F02.8 告知ウィザード
const showBroadcastWizard = ref(false)

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

const team = ref<TeamResponse | null>(null)
const loading = ref(false)
const activeTab = ref(0)
const showLeaveConfirm = ref(false)

const displayName = computed(() => team.value?.basicInfo?.nickname1 || team.value?.basicInfo?.name || '')

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
  // ウィジェット可視性設定を取得（非メンバー・サポーターは403になるため catch して空のまま = デフォルト適用）
  fetchWidgetVisibility().catch(() => {})
})
</script>

<template>
  <div class="mx-auto max-w-6xl">
    <div v-if="loading || roleLoading" class="flex justify-center px-6 py-12">
      <LoadingBounce />
    </div>

    <template v-else-if="team">
      <ProfileHeader
        :icon-url="team.metadata?.iconUrl"
        :banner-url="team.metadata?.bannerUrl"
        :name="displayName"
        scope="team"
        :scope-id="teamId"
        :editable="isAdminOrDeputy"
        @icon-updated="(url) => { if (team && team.metadata) team.metadata.iconUrl = url }"
        @banner-updated="(url) => { if (team && team.metadata) team.metadata.bannerUrl = url }"
      >
        <!-- 名前行 + アクション群 -->
        <div class="flex flex-col sm:flex-row sm:items-start sm:justify-between gap-2 pt-1">
          <!-- 左: 戻る + 名前 + メタ情報 -->
          <div class="flex flex-col gap-1 min-w-0">
            <div class="flex items-center gap-2 flex-wrap">
              <Button icon="pi pi-arrow-left" text rounded size="small" @click="navigateTo('/dashboard')" />
              <h1 class="text-xl sm:text-2xl font-bold truncate">
                {{ displayName }}
              </h1>
              <Tag :value="templateLabel[team.location?.template ?? ''] ?? team.location?.template ?? ''" severity="info" />
              <RoleBadge v-if="roleName" :role="roleName" />
            </div>
            <div class="flex items-center gap-3 text-xs sm:text-sm text-surface-500 flex-wrap pl-8">
              <span class="flex items-center gap-1">
                <i class="pi pi-users text-xs" />
                メンバー <strong class="text-surface-700">{{ team.metadata?.memberCount ?? 0 }}</strong>人
              </span>
              <span v-if="team.visibility?.supporterEnabled" class="flex items-center gap-1">
                <i class="pi pi-heart text-xs" />
                サポーター <strong class="text-surface-700">{{ team.social?.supporterCount ?? '—' }}</strong>人
              </span>
            </div>
          </div>

          <!-- 右: アクションボタン群 -->
          <div class="flex items-center gap-2 flex-wrap shrink-0">
            <FavoriteToggleButton
              entity-type="TEAM"
              :entity-id="String(team.id)"
              :entity-name="displayName"
            />
            <template v-if="team.visibility?.supporterEnabled && !roleName">
              <Button
                v-if="followStatus === 'APPROVED'"
                icon="pi pi-heart-fill"
                label="サポーターです"
                size="small"
                :loading="followLoading"
                class="border-red-400 bg-red-50 text-red-500 hover:bg-red-100"
                outlined
                @click="showCancelSupporterConfirm = true"
              />
              <span
                v-else-if="followStatus === 'PENDING'"
                class="flex items-center gap-2 text-sm text-orange-500"
              >
                <i class="pi pi-clock" />申請中（承認待ち）
                <Button
                  label="取消"
                  size="small"
                  severity="secondary"
                  text
                  :loading="followLoading"
                  @click="cancelSupporter"
                />
              </span>
              <Button
                v-else
                label="サポーターになる"
                icon="pi pi-heart"
                severity="secondary"
                outlined
                size="small"
                :loading="followLoading"
                @click="applySupporter"
              />
            </template>
            <Button
              v-if="isAdminOrDeputy"
              :label="$t('market.action.post')"
              icon="pi pi-tag"
              severity="secondary"
              outlined
              size="small"
              @click="navigateTo(`/teams/${teamId}/recruitment-listings/new`)"
            />
            <Button
              v-if="roleName && roleName !== 'SUPPORTER'"
              :label="$t('announcement.broadcast_button_team')"
              icon="pi pi-bullhorn"
              severity="secondary"
              size="small"
              @click="showBroadcastWizard = true"
            />
            <Button
              v-if="!isAdmin && roleName"
              label="チームから退出"
              icon="pi pi-sign-out"
              severity="danger"
              outlined
              size="small"
              @click="showLeaveConfirm = true"
            />
          </div>
        </div>
      </ProfileHeader>

      <BroadcastWizard
        v-model:visible="showBroadcastWizard"
        scope-type="TEAM"
        :scope-id="teamId"
        :is-admin="isAdmin"
      />

      <div class="px-6 pb-6">
        <Tabs v-model:value="activeTab">
          <TabList>
            <Tab :value="0"> ダッシュボード </Tab>
            <Tab :value="1"> 基本情報 </Tab>
            <Tab :value="2"> メンバー </Tab>
            <Tab v-if="isAdminOrDeputy" :value="3"> 招待 </Tab>
            <Tab v-if="isAdmin && team.visibility?.supporterEnabled" :value="4"> サポーター管理 </Tab>
            <Tab v-if="isAdmin" :value="5"> 機能設定 </Tab>
            <Tab v-if="isAdmin" :value="6"> {{ $t('nav.tab') }} </Tab>
          </TabList>

          <TabPanels>
            <TabPanel :value="0">
              <div class="mt-4">
                <ScopeDashboard
                  scope-type="team"
                  :scope-id="teamId"
                  :scope-name="displayName"
                  :scope-template="team.location?.template"
                  :viewer-role="viewerRole"
                  :is-admin-or-deputy="isAdminOrDeputy"
                  :visibility-map="widgetVisibilitySettings"
                />
              </div>
            </TabPanel>

            <TabPanel :value="1">
              <TeamDetailInfo
                :team-id="teamId"
                :name="team.basicInfo?.name ?? ''"
                :name-kana="team.basicInfo?.nameKana ?? null"
                :nickname1="team.basicInfo?.nickname1 ?? null"
                :nickname2="team.basicInfo?.nickname2 ?? null"
                :template="team.location?.template ?? ''"
                :template-label="templateLabel[team.location?.template ?? ''] ?? team.location?.template ?? ''"
                :prefecture="team.location?.prefecture ?? null"
                :city="team.location?.city ?? null"
                :prefecture-code="team.location?.prefectureCode ?? null"
                :city-code="team.location?.cityCode ?? null"
                :visibility="team.visibility?.visibility ?? ''"
                :visibility-label="visibilityLabel[team.visibility?.visibility ?? ''] ?? team.visibility?.visibility ?? ''"
                :member-count="team.metadata?.memberCount ?? 0"
                :team-friend-count="team.social?.teamFriendCount ?? 0"
                :supporter-count="team.social?.supporterCount ?? 0"
                :supporter-enabled="team.visibility?.supporterEnabled ?? false"
                :description="null"
                :is-admin="isAdmin"
                :map-embed-url="team.metadata?.mapEmbedUrl ?? null"
                @updated:map-embed-url="(url) => { if (team && team.metadata) team.metadata.mapEmbedUrl = url }"
                @updated:region-codes="(pc, cc) => {
                  if (team && team.location) {
                    team.location.prefectureCode = pc
                    team.location.cityCode = cc
                  }
                }"
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

            <TabPanel v-if="isAdmin && team.visibility?.supporterEnabled" :value="4">
              <div class="mt-4">
                <SupporterManagementPanel scope-type="team" :scope-id="teamId" />
              </div>
            </TabPanel>

            <TabPanel v-if="isAdmin" :value="5">
              <div class="mt-4">
                <ModuleSettingsPanel scope-type="team" :scope-id="teamId" />
              </div>
            </TabPanel>

            <TabPanel v-if="isAdmin" :value="6">
              <div class="mt-4 grid grid-cols-2 gap-3">
                <NuxtLink :to="`/teams/${teamId}/friends`">
                  <Button :label="$t('friends.title')" icon="pi pi-users" class="w-full" outlined />
                </NuxtLink>
                <NuxtLink :to="`/teams/${teamId}/friend-folders`">
                  <Button :label="$t('folders.title')" icon="pi pi-folder-open" class="w-full" outlined />
                </NuxtLink>
                <NuxtLink :to="`/teams/${teamId}/friend-feed`">
                  <Button :label="$t('friend_feed.title')" icon="pi pi-inbox" class="w-full" outlined />
                </NuxtLink>
                <NuxtLink :to="`/teams/${teamId}/friend-forward-exports`">
                  <Button :label="$t('forward_exports.title')" icon="pi pi-history" class="w-full" outlined />
                </NuxtLink>
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
      </div>
    </template>
  </div>
</template>
