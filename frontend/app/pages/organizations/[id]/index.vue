<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
  layout: 'default',
})

const route = useRoute()
const orgId = computed(() => Number(route.params.id))
const {
  roleName,
  loading: roleLoading,
  loadPermissions,
  isAdmin,
  isAdminOrDeputy,
} = useRoleAccess('organization', orgId)

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

const activeTab = ref(0)

onMounted(async () => {
  await Promise.all([fetchOrg(), loadPermissions()])
  await Promise.all([
    fetchOrgTeams(),
    isAdmin.value ? fetchPermissionGroups() : Promise.resolve(),
    fetchFollowStatus(roleName),
  ])
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
              {{ org.nickname1 || org.name }}
            </h1>
            <div class="mt-1 flex items-center gap-2">
              <RoleBadge v-if="roleName" :role="roleName" />
            </div>
            <div class="mt-2 flex items-center gap-4 text-sm text-surface-500">
              <span class="flex items-center gap-1">
                <i class="pi pi-users text-xs" />
                メンバー <strong class="text-surface-700">{{ org.memberCount }}</strong
                >人
              </span>
              <span v-if="org.supporterEnabled" class="flex items-center gap-1">
                <i class="pi pi-heart text-xs" />
                サポーター <strong class="text-surface-700">{{ org.supporterCount ?? '—' }}</strong
                >人
              </span>
            </div>
          </div>
        </div>
        <template v-if="org.supporterEnabled && !roleName">
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
          v-if="!isAdmin && roleName"
          label="組織から退出"
          icon="pi pi-sign-out"
          severity="danger"
          outlined
          size="small"
          @click="showLeaveConfirm = true"
        />
      </div>

      <Tabs v-model:value="activeTab">
        <TabList>
          <Tab :value="0"> ダッシュボード </Tab>
          <Tab :value="1"> 基本情報 </Tab>
          <Tab :value="2"> メンバー </Tab>
          <Tab :value="3"> 所属チーム </Tab>
          <Tab v-if="isAdminOrDeputy" :value="4"> 招待 </Tab>
          <Tab v-if="isAdmin" :value="5"> 権限グループ </Tab>
          <Tab v-if="isAdmin && org.supporterEnabled" :value="6"> サポーター管理 </Tab>
          <Tab v-if="isAdmin" :value="7"> 機能設定 </Tab>
        </TabList>

        <TabPanels>
          <TabPanel :value="0">
            <div class="mt-4">
              <ScopeDashboard
                scope-type="organization"
                :scope-id="orgId"
                :scope-name="org.nickname1 || org.name"
                :scope-template="org.template"
              />
            </div>
            <div class="mt-8">
              <SnsFeedDisplay scope-type="organization" :scope-id="orgId" />
            </div>
          </TabPanel>

          <TabPanel :value="1">
            <OrgInfoTab :org="org" :is-admin="isAdmin" />
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
            <OrgTeamGrid :teams="orgTeams" />
          </TabPanel>

          <TabPanel v-if="isAdminOrDeputy" :value="4">
            <div class="mt-4">
              <InviteTokenList scope-type="organization" :scope-id="orgId" />
            </div>
          </TabPanel>

          <TabPanel v-if="isAdmin" :value="5">
            <OrgPermissionGroupList :groups="permissionGroups" />
          </TabPanel>

          <TabPanel v-if="isAdmin && org.supporterEnabled" :value="6">
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

      <Dialog
        v-model:visible="showCancelSupporterConfirm"
        header="サポーターをやめますか？"
        :style="{ width: '400px' }"
        modal
      >
        <p>{{ org.nickname1 || org.name }}のサポーターをやめます。よろしいですか？</p>
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
