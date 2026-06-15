<script setup lang="ts">
import type { ViewerRole } from '~/types/dashboard'
import { resolveSlugRedirectPath } from '~/utils/slugRedirect'

definePageMeta({
  middleware: 'auth',
  layout: 'default',
})

const route = useRoute()
const orgSlug = computed(() => String(route.params.slug))
const {
  roleName,
  loading: roleLoading,
  loadPermissions,
  isAdmin,
  isAdminOrDeputy,
} = useRoleAccess('organization', orgSlug)

const viewerRole = computed<ViewerRole>(() => (roleName.value as ViewerRole | null) ?? 'PUBLIC')

const {
  settings: widgetVisibilitySettings,
  fetch: fetchWidgetVisibility,
} = useDashboardWidgetVisibility('organization', orgSlug)

const {
  org,
  orgTeams,
  permissionGroups,
  loading,
  followStatus,
  followLoading,
  showCancelSupporterConfirm,
  showLeaveConfirm,
  fetchOrg,
  fetchOrgTeams,
  fetchPermissionGroups,
  fetchFollowStatus,
  applySupporter,
  cancelSupporter,
  leaveOrganization,
} = useOrgDetail(orgSlug)

const {
  ancestors,
  children,
  loading: hierarchyLoading,
  childrenHasNext,
  fetchAncestors,
  fetchChildren,
} = useOrgHierarchy(orgSlug)

const activeTab = ref(0)

/** 下位組織タブを表示するか（子0件かつ非ADMINなら隠す） */
const showChildrenTab = computed(() => isAdmin.value || children.value.length > 0)

/**
 * F15.4 Phase 3: 組織内チーム（店舗）検索ページへの導線を表示するか
 * - 組織が PUBLIC のときは未ログイン者を含め誰でも表示
 * - それ以外は組織メンバー（roleName が付与されている）にのみ表示
 */
const showTeamSearchLink = computed(() => {
  if (!org.value) return false
  if (org.value.visibility?.visibility === 'PUBLIC') return true
  return Boolean(roleName.value)
})

/**
 * 組織取得が 404（org 未取得）のとき、旧 slug → 新 slug の MOVED かを解決し 301 遷移する
 * （村方式・BE #1542）。取得が成功する現行 slug では解決 EP を一切叩かないため happy path に干渉しない。
 * 遷移した場合は true を返す。
 *
 * SSR 初回アクセスは slug-redirect.global ミドルウェアが本物の HTTP 301 を返すため、
 * ここはクライアント側 SPA 遷移で旧 slug に到達した場合のフォールバック。
 * 解決ロジックは middleware と共通の {@link resolveSlugRedirectPath} に一本化している。
 */
async function tryRedirectMovedSlug(): Promise<boolean> {
  const { resolveSlug } = useSlugRedirect()
  const target = await resolveSlugRedirectPath(route.path, resolveSlug)
  if (!target) return false
  await navigateTo(
    { path: target, query: route.query, hash: route.hash },
    { redirectCode: 301, replace: true },
  )
  return true
}

onMounted(async () => {
  await Promise.all([fetchOrg(), loadPermissions()])
  // 組織が取得できなかった（404 等）場合は MOVED slug の可能性を解決し 301 遷移を試みる。
  if (!org.value && await tryRedirectMovedSlug()) return
  await Promise.all([
    fetchOrgTeams(),
    isAdmin.value ? fetchPermissionGroups() : Promise.resolve(),
    fetchFollowStatus(roleName),
    fetchAncestors(),
    fetchChildren(true),
  ])
  // ウィジェット可視性設定を取得（非メンバー・サポーターは403になるため catch して空のまま = デフォルト適用）
  fetchWidgetVisibility().catch(() => {})
})
</script>

<template>
  <div class="mx-auto max-w-6xl">
    <div v-if="loading || roleLoading" class="flex justify-center px-6 py-12">
      <LoadingBounce />
    </div>

    <template v-else-if="org">
      <OrgPageHeader
        :org="org"
        :org-id="orgSlug"
        :role-name="roleName"
        :is-admin="isAdmin"
        :is-admin-or-deputy="isAdminOrDeputy"
        :follow-status="followStatus"
        :follow-loading="followLoading"
        :ancestors="ancestors"
        @back="navigateTo('/dashboard')"
        @apply-supporter="applySupporter"
        @cancel-supporter="cancelSupporter"
        @show-cancel-confirm="showCancelSupporterConfirm = true"
        @show-leave-confirm="showLeaveConfirm = true"
        @icon-updated="(url) => { if (org && org.metadata) org.metadata.iconUrl = url }"
        @banner-updated="(url) => { if (org && org.metadata) org.metadata.bannerUrl = url }"
      />

      <Tabs v-model:value="activeTab">
        <!-- TabList を村スタイルで全幅表示 -->
        <div class="border-b border-surface-200 dark:border-surface-700 bg-surface-0 dark:bg-surface-900">
          <TabList>
            <Tab :value="0"> ダッシュボード </Tab>
            <Tab :value="1"> 基本情報 </Tab>
            <Tab :value="2"> メンバー </Tab>
            <Tab :value="3"> 所属チーム </Tab>
            <Tab v-if="showChildrenTab" :value="8">
              {{ $t('organization.children_tab') }}
            </Tab>
            <Tab v-if="isAdminOrDeputy" :value="4"> 招待 </Tab>
            <Tab v-if="isAdmin" :value="5"> 権限グループ </Tab>
            <Tab v-if="isAdmin && org.visibility?.supporterEnabled" :value="6"> サポーター管理 </Tab>
            <Tab v-if="isAdmin" :value="7"> 機能設定 </Tab>
          </TabList>
        </div>

        <!-- TabPanels はパディングあり -->
        <div class="px-6 pb-6">
          <TabPanels class="!bg-transparent">
            <TabPanel :value="0">
              <div class="mt-4">
                <ScopeDashboard
                  scope-type="organization"
                  :scope-id="orgSlug"
                  :scope-name="org.basicInfo?.nickname1 || org.basicInfo?.name || ''"
                  :scope-template="org.hierarchy?.orgType"
                  :viewer-role="viewerRole"
                  :is-admin-or-deputy="isAdminOrDeputy"
                  :visibility-map="widgetVisibilitySettings"
                />
              </div>
            </TabPanel>

            <TabPanel :value="1">
              <OrgInfoTab :org="org" :is-admin="isAdmin" :ancestors="ancestors" />
            </TabPanel>

            <TabPanel :value="2">
              <div class="mt-4">
                <MemberTable
                  scope-type="organization"
                  :scope-id="orgSlug"
                  :can-change-role="isAdminOrDeputy"
                  :can-remove="isAdminOrDeputy"
                />
              </div>
            </TabPanel>

            <TabPanel :value="3">
              <div
                v-if="showTeamSearchLink"
                class="mt-4 flex justify-end"
              >
                <NuxtLink
                  :to="`/organizations/${orgSlug}/teams/search`"
                  :aria-label="$t('organizationTeamSearch.title')"
                  class="inline-flex items-center gap-2 rounded-md border border-primary-300 bg-primary-50 px-3 py-2 text-sm font-medium text-primary-700 transition-colors hover:bg-primary-100 focus:outline-none focus:ring-2 focus:ring-primary-400"
                >
                  <i class="pi pi-search" aria-hidden="true" />
                  {{ $t('organizationTeamSearch.title') }}
                </NuxtLink>
              </div>
              <OrgTeamGrid :teams="orgTeams" />
            </TabPanel>

            <TabPanel v-if="showChildrenTab" :value="8">
              <OrgChildrenGrid
                :children="children"
                :loading="hierarchyLoading"
                :has-next="childrenHasNext"
                @load-more="fetchChildren(false)"
              />
            </TabPanel>

            <TabPanel v-if="isAdminOrDeputy" :value="4">
              <div class="mt-4">
                <InviteTokenList scope-type="organization" :scope-id="orgSlug" />
              </div>
            </TabPanel>

            <TabPanel v-if="isAdmin" :value="5">
              <OrgPermissionGroupList :groups="permissionGroups" />
            </TabPanel>

            <TabPanel v-if="isAdmin && org.visibility?.supporterEnabled" :value="6">
              <div class="mt-4">
                <SupporterManagementPanel scope-type="organization" :scope-id="orgSlug" />
              </div>
            </TabPanel>

            <TabPanel v-if="isAdmin" :value="7">
              <div class="mt-4">
                <ModuleSettingsPanel scope-type="organization" :scope-id="orgSlug" />
              </div>
            </TabPanel>
          </TabPanels>

          <Dialog
            v-model:visible="showCancelSupporterConfirm"
            header="サポーターをやめますか？"
            :style="{ width: '400px' }"
            modal
          >
            <p>{{ org.basicInfo?.nickname1 || org.basicInfo?.name }}のサポーターをやめます。よろしいですか？</p>
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
            header="組織から退出"
            :style="{ width: '400px' }"
            modal
          >
            <p>本当にこの組織から退出しますか？この操作は取り消せません。</p>
            <template #footer>
              <Button label="キャンセル" text @click="showLeaveConfirm = false" />
              <Button label="退出する" severity="danger" @click="leaveOrganization" />
            </template>
          </Dialog>
        </div>
      </Tabs>
    </template>
  </div>
</template>
