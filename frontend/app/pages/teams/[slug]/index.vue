<script setup lang="ts">
import type { FetchError } from 'ofetch'
import type { ViewerRole } from '~/types/dashboard'
import type { TeamResponse } from '~/types/team'
import { computeSlugRedirectPath, parseSlugRoute } from '~/utils/slugRedirect'

definePageMeta({
  middleware: 'auth',
  layout: 'default',
})

const route = useRoute()
const teamApi = useTeamApi()
const notification = useNotification()
const { handleApiError } = useErrorHandler()
const { templateLabel, visibilityLabel } = useScopeLabels()

const teamSlug = computed(() => String(route.params.slug))
const {
  roleName,
  loading: roleLoading,
  loadPermissions,
  isAdmin,
  isAdminOrDeputy,
} = useRoleAccess('team', teamSlug)

const viewerRole = computed<ViewerRole>(() => (roleName.value as ViewerRole | null) ?? 'PUBLIC')

const {
  settings: widgetVisibilitySettings,
  fetch: fetchWidgetVisibility,
} = useDashboardWidgetVisibility('team', teamSlug)

const followStatus = ref<'NONE' | 'PENDING' | 'APPROVED'>('NONE')
const followLoading = ref(false)
const showCancelSupporterConfirm = ref(false)

async function fetchFollowStatus() {
  if (roleName.value) return
  try {
    const res = await teamApi.getFollowStatus(teamSlug.value)
    followStatus.value = res.data.status
  } catch {
    followStatus.value = 'NONE'
  }
}

async function applySupporter() {
  followLoading.value = true
  try {
    await teamApi.followTeam(teamSlug.value)
    const res = await teamApi.getFollowStatus(teamSlug.value)
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
    await teamApi.unfollowTeam(teamSlug.value)
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
    const result = await teamApi.getTeam(teamSlug.value)
    team.value = result.data
  } catch (error) {
    // 404 のときは「旧 slug → 新 slug の 301 移動」かもしれないので解決を試みる（村方式・BE #1542）。
    // 取得が成功する現行 slug では解決 EP を一切叩かないため、happy path には干渉しない。
    if ((error as FetchError)?.response?.status === 404 && await tryRedirectMovedSlug()) {
      return
    }
    handleApiError(error, 'チーム詳細取得')
  } finally {
    loading.value = false
  }
}

/**
 * 現 slug が旧 slug（MOVED）なら新 slug の同一パスへ 301 遷移する。
 * 遷移した場合は true を返す（呼び出し元はそれ以上のエラー表示を行わない）。
 */
async function tryRedirectMovedSlug(): Promise<boolean> {
  const parts = parseSlugRoute(useRoute().path)
  if (!parts) return false
  try {
    const result = await teamApi.resolveTeamSlug(parts.slug)
    const target = computeSlugRedirectPath(parts, result)
    if (!target) return false
    await navigateTo(
      { path: target, query: useRoute().query, hash: useRoute().hash },
      { redirectCode: 301, replace: true },
    )
    return true
  } catch {
    return false
  }
}

async function leaveTeam() {
  try {
    await teamApi.leaveTeam(teamSlug.value)
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
      <TeamPageHeader
        :team="team"
        :display-name="displayName"
        :role-name="roleName"
        :is-admin="isAdmin"
        :is-admin-or-deputy="isAdminOrDeputy"
        :follow-status="followStatus"
        :follow-loading="followLoading"
        :template-label="templateLabel"
        @back="navigateTo('/dashboard')"
        @apply-supporter="applySupporter"
        @cancel-supporter="cancelSupporter"
        @show-cancel-confirm="showCancelSupporterConfirm = true"
        @show-leave-confirm="showLeaveConfirm = true"
        @icon-updated="(url) => { if (team && team.metadata) team.metadata.iconUrl = url }"
        @banner-updated="(url) => { if (team && team.metadata) team.metadata.bannerUrl = url }"
      />

      <Tabs v-model:value="activeTab">
        <!-- TabList を村スタイルで全幅表示 -->
        <div class="border-b border-surface-200 dark:border-surface-700 bg-surface-0 dark:bg-surface-900">
          <TabList>
            <Tab :value="0"> ダッシュボード </Tab>
            <Tab :value="1"> 基本情報 </Tab>
            <Tab :value="2"> メンバー </Tab>
            <Tab v-if="isAdminOrDeputy" :value="3"> 招待 </Tab>
            <Tab v-if="isAdmin && team.visibility?.supporterEnabled" :value="4"> サポーター管理 </Tab>
            <Tab v-if="isAdmin" :value="5"> 機能設定 </Tab>
            <Tab v-if="roleName" :value="7"> {{ $t('nav.tab') }} </Tab>
          </TabList>
        </div>

        <!-- TabPanels はパディングあり -->
        <div class="px-6 pb-6">
          <TabPanels class="!bg-transparent">
            <TabPanel :value="0">
              <div class="mt-4">
                <ScopeDashboard
                  scope-type="team"
                  :scope-id="teamSlug"
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
                :team-id="teamSlug"
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
                  :scope-id="teamSlug"
                  :can-change-role="isAdminOrDeputy"
                  :can-remove="isAdminOrDeputy"
                />
              </div>
            </TabPanel>

            <TabPanel v-if="isAdminOrDeputy" :value="3">
              <div class="mt-4">
                <InviteTokenList scope-type="team" :scope-id="teamSlug" />
              </div>
            </TabPanel>

            <TabPanel v-if="isAdmin && team.visibility?.supporterEnabled" :value="4">
              <div class="mt-4">
                <SupporterManagementPanel scope-type="team" :scope-id="teamSlug" />
              </div>
            </TabPanel>

            <TabPanel v-if="isAdmin" :value="5">
              <div class="mt-4">
                <ModuleSettingsPanel scope-type="team" :scope-id="teamSlug" />
              </div>
            </TabPanel>

            <TabPanel v-if="roleName" :value="7">
              <div class="mt-4">
                <!-- 管理者向けショートカット -->
                <div v-if="isAdmin" class="mb-4 grid grid-cols-2 gap-3">
                  <NuxtLink :to="`/teams/${teamSlug}/friend-folders`">
                    <Button :label="$t('folders.title')" icon="pi pi-folder-open" class="w-full" outlined />
                  </NuxtLink>
                  <NuxtLink :to="`/teams/${teamSlug}/friend-feed`">
                    <Button :label="$t('friend_feed.title')" icon="pi pi-inbox" class="w-full" outlined />
                  </NuxtLink>
                  <NuxtLink :to="`/teams/${teamSlug}/friend-forward-exports`">
                    <Button :label="$t('forward_exports.title')" icon="pi pi-history" class="w-full" outlined />
                  </NuxtLink>
                </div>
                <TeamFriendList
                  :team-id="teamSlug"
                  :can-edit="isAdminOrDeputy"
                  :can-toggle-visibility="isAdmin"
                />
              </div>
            </TabPanel>
          </TabPanels>

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
      </Tabs>
    </template>
  </div>
</template>
