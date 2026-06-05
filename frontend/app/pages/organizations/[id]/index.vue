<script setup lang="ts">
import type { ViewerRole } from '~/types/dashboard'
import FavoriteToggleButton from '~/components/favorites/FavoriteToggleButton.vue'

definePageMeta({
  middleware: 'auth',
  layout: 'default',
})

const route = useRoute()
const orgId = computed(() => String(route.params.id))
const {
  roleName,
  loading: roleLoading,
  loadPermissions,
  isAdmin,
  isAdminOrDeputy,
} = useRoleAccess('organization', orgId)

const viewerRole = computed<ViewerRole>(() => (roleName.value as ViewerRole | null) ?? 'PUBLIC')

const {
  settings: widgetVisibilitySettings,
  fetch: fetchWidgetVisibility,
} = useDashboardWidgetVisibility('organization', orgId)

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
} = useOrgDetail(orgId)

const {
  ancestors,
  children,
  loading: hierarchyLoading,
  childrenHasNext,
  fetchAncestors,
  fetchChildren,
} = useOrgHierarchy(orgId)

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

// F02.8 告知ウィザード
const showBroadcastWizard = ref(false)

onMounted(async () => {
  await Promise.all([fetchOrg(), loadPermissions()])
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
  <div class="mx-auto max-w-6xl p-6">
    <PageLoading v-if="loading || roleLoading" />

    <template v-else-if="org">
      <div class="mb-6 flex items-center justify-between">
        <div class="flex items-center gap-4">
          <Button icon="pi pi-arrow-left" text rounded @click="navigateTo('/dashboard')" />
          <div>
            <h1 class="text-4xl font-bold">
              {{ org.basicInfo?.nickname1 || org.basicInfo?.name }}
            </h1>
            <div class="mt-1 flex items-center gap-2">
              <RoleBadge v-if="roleName" :role="roleName" />
            </div>
            <div class="mt-2 flex items-center gap-4 text-sm text-surface-500">
              <span class="flex items-center gap-1">
                <i class="pi pi-users text-xs" />
                メンバー <strong class="text-surface-700">{{ org.metadata?.memberCount }}</strong
                >人
              </span>
              <span v-if="org.visibility?.supporterEnabled" class="flex items-center gap-1">
                <i class="pi pi-heart text-xs" />
                サポーター <strong class="text-surface-700">{{ org.supporterCount ?? '—' }}</strong
                >人
              </span>
            </div>
          </div>
        </div>
        <template v-if="org.visibility?.supporterEnabled && !roleName">
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
        <!-- F02.9 お気に入りトグル -->
        <FavoriteToggleButton
          entity-type="ORGANIZATION"
          :entity-id="String(org.id)"
          :entity-name="org.basicInfo?.nickname1 || org.basicInfo?.name || ''"
        />
        <!-- F22.1 市（Market）: ADMIN または DEPUTY_ADMIN のみ「札を立てる」導線 -->
        <Button
          v-if="isAdminOrDeputy"
          :label="$t('market.action.post')"
          icon="pi pi-tag"
          severity="secondary"
          outlined
          size="small"
          @click="navigateTo(`/organizations/${orgId}/recruitment-listings/new`)"
        />
        <!-- F02.8 告知ウィザード：MEMBER以上に表示 -->
        <Button
          v-if="roleName && roleName !== 'SUPPORTER'"
          :label="$t('announcement.broadcast_button_org')"
          icon="pi pi-bullhorn"
          severity="secondary"
          size="small"
          @click="showBroadcastWizard = true"
        />
        <Button
          v-if="!isAdmin && roleName"
          label="組織から退出"
          icon="pi pi-sign-out"
          severity="danger"
          outlined
          size="small"
          @click="showLeaveConfirm = true"
        />
      </div>

      <OrgAncestorsBreadcrumb
        v-if="ancestors.length > 0"
        :ancestors="ancestors"
        :current-org-name="org.basicInfo?.nickname1 || org.basicInfo?.name || ''"
        class="mb-4"
      />

      <ProfileHeader
        :icon-url="org.metadata?.iconUrl ?? null"
        :banner-url="org.metadata?.bannerUrl ?? null"
        :name="org.basicInfo?.nickname1 || org.basicInfo?.name || ''"
        scope="organization"
        :scope-id="orgId"
        :editable="isAdminOrDeputy"
        @icon-updated="(url) => { if (org && org.metadata) org.metadata.iconUrl = url }"
        @banner-updated="(url) => { if (org && org.metadata) org.metadata.bannerUrl = url }"
      />

      <Tabs v-model:value="activeTab">
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

        <TabPanels>
          <TabPanel :value="0">
            <div class="mt-4">
              <ScopeDashboard
                scope-type="organization"
                :scope-id="orgId"
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
                :scope-id="orgId"
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
                :to="`/organizations/${orgId}/teams/search`"
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
              <InviteTokenList scope-type="organization" :scope-id="orgId" />
            </div>
          </TabPanel>

          <TabPanel v-if="isAdmin" :value="5">
            <OrgPermissionGroupList :groups="permissionGroups" />
          </TabPanel>

          <TabPanel v-if="isAdmin && org.visibility?.supporterEnabled" :value="6">
            <div class="mt-4">
              <SupporterManagementPanel scope-type="organization" :scope-id="orgId" />
            </div>
          </TabPanel>

          <TabPanel v-if="isAdmin" :value="7">
            <div class="mt-4">
              <ModuleSettingsPanel scope-type="organization" :scope-id="orgId" />
            </div>
          </TabPanel>
        </TabPanels>
      </Tabs>

      <!-- F02.8 告知ウィザード -->
      <BroadcastWizard
        v-model:visible="showBroadcastWizard"
        scope-type="ORGANIZATION"
        :scope-id="orgId"
        :is-admin="isAdmin"
      />

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
    </template>
  </div>
</template>
