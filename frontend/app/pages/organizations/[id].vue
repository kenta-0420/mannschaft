<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
  layout: 'default',
})

const route = useRoute()
const orgApi = useOrganizationApi()
const notification = useNotification()
const { handleApiError } = useErrorHandler()

const orgId = computed(() => Number(route.params.id))
const { roleName, loading: roleLoading, loadPermissions, isAdmin, isAdminOrDeputy } = useRoleAccess('organization', orgId)

interface OrgDetail {
  id: number
  name: string
  nameKana: string | null
  nickname1: string | null
  nickname2: string | null
  prefecture: string | null
  city: string | null
  description: string | null
  visibility: string
  supporterEnabled: boolean
  version: number
  memberCount: number
  archivedAt: string | null
  createdAt: string
}

interface OrgTeam {
  id: number
  name: string
  nickname1: string | null
  iconUrl: string | null
  template: string
  memberCount: number
}

interface PermissionGroup {
  id: number
  name: string
  description: string | null
  permissions: string[]
  createdAt: string
}

const org = ref<OrgDetail | null>(null)
const orgTeams = ref<OrgTeam[]>([])
const permissionGroups = ref<PermissionGroup[]>([])
const loading = ref(false)
const activeTab = ref(0)
const showLeaveConfirm = ref(false)

const visibilityLabel: Record<string, string> = {
  PUBLIC: '公開',
  PRIVATE: '非公開',
}

const templateLabel: Record<string, string> = {
  SPORTS: 'スポーツ',
  CLINIC: 'クリニック',
  SCHOOL: '学校',
  COMMUNITY: 'コミュニティ',
  COMPANY: '企業',
  OTHER: 'その他',
}

async function fetchOrg() {
  loading.value = true
  try {
    const result = await orgApi.getOrganization(orgId.value)
    org.value = result.data
  }
  catch (error) {
    handleApiError(error)
  }
  finally {
    loading.value = false
  }
}

async function fetchOrgTeams() {
  try {
    const result = await orgApi.getTeamsInOrg(orgId.value)
    orgTeams.value = result.data
  }
  catch {
    orgTeams.value = []
  }
}

async function fetchPermissionGroups() {
  try {
    const result = await orgApi.getPermissionGroups(orgId.value)
    permissionGroups.value = result.data
  }
  catch {
    permissionGroups.value = []
  }
}

async function leaveOrganization() {
  try {
    await orgApi.leaveOrganization(orgId.value)
    notification.success('組織から退出しました')
    navigateTo('/dashboard')
  }
  catch (error) {
    handleApiError(error)
  }
  finally {
    showLeaveConfirm.value = false
  }
}

onMounted(async () => {
  await Promise.all([fetchOrg(), loadPermissions()])
  // 権限情報取得後に追加データを読み込む
  await Promise.all([
    fetchOrgTeams(),
    isAdmin.value ? fetchPermissionGroups() : Promise.resolve(),
  ])
})
</script>

<template>
  <div class="mx-auto max-w-6xl p-6">
    <div v-if="loading || roleLoading" class="flex justify-center py-12">
      <ProgressSpinner style="width: 48px; height: 48px" />
    </div>

    <template v-else-if="org">
      <!-- ヘッダー -->
      <div class="mb-6 flex items-center justify-between">
        <div class="flex items-center gap-4">
          <Button
            icon="pi pi-arrow-left"
            text
            rounded
            @click="navigateTo('/dashboard')"
          />
          <div>
            <h1 class="text-2xl font-bold">
              {{ org.nickname1 || org.name }}
            </h1>
            <div class="mt-1 flex items-center gap-2">
              <RoleBadge v-if="roleName" :role="roleName" />
            </div>
          </div>
        </div>
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

      <!-- タブ -->
      <Tabs v-model:value="activeTab">
        <TabList>
          <Tab :value="0">
            基本情報
          </Tab>
          <Tab :value="1">
            メンバー
          </Tab>
          <Tab :value="2">
            所属チーム
          </Tab>
          <Tab v-if="isAdminOrDeputy" :value="3">
            招待
          </Tab>
          <Tab v-if="isAdmin" :value="4">
            権限グループ
          </Tab>
        </TabList>

        <TabPanels>
          <!-- 基本情報タブ -->
          <TabPanel :value="0">
            <div class="mt-4 grid grid-cols-1 gap-6 md:grid-cols-2">
              <div class="space-y-4">
                <div>
                  <label class="text-sm font-medium text-gray-500">組織名</label>
                  <p class="mt-1">
                    {{ org.name }}
                  </p>
                </div>
                <div v-if="org.nameKana">
                  <label class="text-sm font-medium text-gray-500">組織名（カナ）</label>
                  <p class="mt-1">
                    {{ org.nameKana }}
                  </p>
                </div>
                <div v-if="org.nickname1">
                  <label class="text-sm font-medium text-gray-500">ニックネーム1</label>
                  <p class="mt-1">
                    {{ org.nickname1 }}
                  </p>
                </div>
                <div v-if="org.nickname2">
                  <label class="text-sm font-medium text-gray-500">ニックネーム2</label>
                  <p class="mt-1">
                    {{ org.nickname2 }}
                  </p>
                </div>
              </div>
              <div class="space-y-4">
                <div>
                  <label class="text-sm font-medium text-gray-500">所在地</label>
                  <p class="mt-1">
                    {{ [org.prefecture, org.city].filter(Boolean).join(' ') || '未設定' }}
                  </p>
                </div>
                <div>
                  <label class="text-sm font-medium text-gray-500">公開設定</label>
                  <p class="mt-1">
                    {{ visibilityLabel[org.visibility] ?? org.visibility }}
                  </p>
                </div>
                <div>
                  <label class="text-sm font-medium text-gray-500">メンバー数</label>
                  <p class="mt-1">
                    {{ org.memberCount }}人
                  </p>
                </div>
                <div>
                  <label class="text-sm font-medium text-gray-500">サポーター機能</label>
                  <p class="mt-1">
                    {{ org.supporterEnabled ? '有効' : '無効' }}
                  </p>
                </div>
                <div v-if="org.description">
                  <label class="text-sm font-medium text-gray-500">説明</label>
                  <p class="mt-1 whitespace-pre-wrap">
                    {{ org.description }}
                  </p>
                </div>
              </div>
            </div>
            <div v-if="isAdmin" class="mt-6">
              <Button
                label="設定を編集"
                icon="pi pi-pencil"
                outlined
              />
            </div>
          </TabPanel>

          <!-- メンバータブ -->
          <TabPanel :value="1">
            <div class="mt-4">
              <MemberTable
                scope-type="organization"
                :scope-id="orgId"
                :can-change-role="isAdminOrDeputy"
                :can-remove="isAdminOrDeputy"
              />
            </div>
          </TabPanel>

          <!-- 所属チームタブ -->
          <TabPanel :value="2">
            <div class="mt-4">
              <div v-if="orgTeams.length === 0" class="rounded-lg border border-dashed border-gray-300 p-8 text-center text-gray-500">
                <i class="pi pi-inbox mb-2 text-3xl" />
                <p>所属チームはありません</p>
              </div>
              <div v-else class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
                <div
                  v-for="team in orgTeams"
                  :key="team.id"
                  class="cursor-pointer rounded-lg border p-4 transition-shadow hover:shadow-md"
                  @click="navigateTo(`/teams/${team.id}`)"
                >
                  <div class="mb-2 flex items-center gap-3">
                    <Avatar
                      :image="team.iconUrl ?? undefined"
                      :label="team.iconUrl ? undefined : team.name.charAt(0)"
                      shape="circle"
                    />
                    <div class="min-w-0 flex-1">
                      <h3 class="truncate font-semibold">
                        {{ team.nickname1 || team.name }}
                      </h3>
                      <Tag :value="templateLabel[team.template] ?? team.template" severity="info" class="text-xs" />
                    </div>
                  </div>
                  <div class="text-sm text-gray-500">
                    <i class="pi pi-users mr-1" />{{ team.memberCount }}人
                  </div>
                </div>
              </div>
            </div>
          </TabPanel>

          <!-- 招待タブ -->
          <TabPanel v-if="isAdminOrDeputy" :value="3">
            <div class="mt-4">
              <InviteTokenList
                scope-type="organization"
                :scope-id="orgId"
              />
            </div>
          </TabPanel>

          <!-- 権限グループタブ -->
          <TabPanel v-if="isAdmin" :value="4">
            <div class="mt-4">
              <div v-if="permissionGroups.length === 0" class="rounded-lg border border-dashed border-gray-300 p-8 text-center text-gray-500">
                <i class="pi pi-shield mb-2 text-3xl" />
                <p>権限グループはまだ作成されていません</p>
              </div>
              <div v-else class="space-y-3">
                <div
                  v-for="group in permissionGroups"
                  :key="group.id"
                  class="rounded-lg border p-4"
                >
                  <div class="mb-2 flex items-center justify-between">
                    <h3 class="font-semibold">
                      {{ group.name }}
                    </h3>
                    <span class="text-sm text-gray-500">{{ group.permissions.length }}件の権限</span>
                  </div>
                  <p v-if="group.description" class="mb-2 text-sm text-gray-600">
                    {{ group.description }}
                  </p>
                  <div class="flex flex-wrap gap-1">
                    <Tag
                      v-for="perm in group.permissions"
                      :key="perm"
                      :value="perm"
                      severity="secondary"
                      class="text-xs"
                    />
                  </div>
                </div>
              </div>
            </div>
          </TabPanel>
        </TabPanels>
      </Tabs>

      <!-- 退出確認ダイアログ -->
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
