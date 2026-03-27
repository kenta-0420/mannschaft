<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
  layout: 'default',
})

const route = useRoute()
const teamApi = useTeamApi()
const notification = useNotification()
const { handleApiError } = useErrorHandler()

const teamId = computed(() => Number(route.params.id))
const { roleName, loading: roleLoading, loadPermissions, isAdmin, isAdminOrDeputy } = useRoleAccess('team', teamId)

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
  archivedAt: string | null
  createdAt: string
}

const team = ref<TeamDetail | null>(null)
const loading = ref(false)
const activeTab = ref(0)
const showLeaveConfirm = ref(false)

const templateLabel: Record<string, string> = {
  SPORTS: 'スポーツ',
  CLINIC: 'クリニック',
  SCHOOL: '学校',
  COMMUNITY: 'コミュニティ',
  COMPANY: '企業',
  OTHER: 'その他',
}

const visibilityLabel: Record<string, string> = {
  PUBLIC: '公開',
  ORGANIZATION_ONLY: '組織内のみ',
  PRIVATE: '非公開',
}

async function fetchTeam() {
  loading.value = true
  try {
    const result = await teamApi.getTeam(teamId.value)
    team.value = result.data
  }
  catch (error) {
    handleApiError(error)
  }
  finally {
    loading.value = false
  }
}

async function leaveTeam() {
  try {
    await teamApi.leaveTeam(teamId.value)
    notification.success('チームから退出しました')
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
  await Promise.all([fetchTeam(), loadPermissions()])
})
</script>

<template>
  <div class="mx-auto max-w-6xl p-6">
    <div v-if="loading || roleLoading" class="flex justify-center py-12">
      <ProgressSpinner style="width: 48px; height: 48px" />
    </div>

    <template v-else-if="team">
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
              {{ team.nickname1 || team.name }}
            </h1>
            <div class="mt-1 flex items-center gap-2">
              <Tag :value="templateLabel[team.template] ?? team.template" severity="info" />
              <RoleBadge v-if="roleName" :role="roleName" />
            </div>
          </div>
        </div>
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

      <!-- タブ -->
      <Tabs v-model:value="activeTab">
        <TabList>
          <Tab :value="0">
            基本情報
          </Tab>
          <Tab :value="1">
            メンバー
          </Tab>
          <Tab v-if="isAdminOrDeputy" :value="2">
            招待
          </Tab>
        </TabList>

        <TabPanels>
          <!-- 基本情報タブ -->
          <TabPanel :value="0">
            <div class="mt-4 grid grid-cols-1 gap-6 md:grid-cols-2">
              <div class="space-y-4">
                <div>
                  <label class="text-sm font-medium text-gray-500">チーム名</label>
                  <p class="mt-1">
                    {{ team.name }}
                  </p>
                </div>
                <div v-if="team.nameKana">
                  <label class="text-sm font-medium text-gray-500">チーム名（カナ）</label>
                  <p class="mt-1">
                    {{ team.nameKana }}
                  </p>
                </div>
                <div v-if="team.nickname1">
                  <label class="text-sm font-medium text-gray-500">ニックネーム1</label>
                  <p class="mt-1">
                    {{ team.nickname1 }}
                  </p>
                </div>
                <div v-if="team.nickname2">
                  <label class="text-sm font-medium text-gray-500">ニックネーム2</label>
                  <p class="mt-1">
                    {{ team.nickname2 }}
                  </p>
                </div>
                <div>
                  <label class="text-sm font-medium text-gray-500">テンプレート</label>
                  <p class="mt-1">
                    {{ templateLabel[team.template] ?? team.template }}
                  </p>
                </div>
              </div>
              <div class="space-y-4">
                <div>
                  <label class="text-sm font-medium text-gray-500">所在地</label>
                  <p class="mt-1">
                    {{ [team.prefecture, team.city].filter(Boolean).join(' ') || '未設定' }}
                  </p>
                </div>
                <div>
                  <label class="text-sm font-medium text-gray-500">公開設定</label>
                  <p class="mt-1">
                    {{ visibilityLabel[team.visibility] ?? team.visibility }}
                  </p>
                </div>
                <div>
                  <label class="text-sm font-medium text-gray-500">メンバー数</label>
                  <p class="mt-1">
                    {{ team.memberCount }}人
                  </p>
                </div>
                <div>
                  <label class="text-sm font-medium text-gray-500">サポーター機能</label>
                  <p class="mt-1">
                    {{ team.supporterEnabled ? '有効' : '無効' }}
                  </p>
                </div>
                <div v-if="team.description">
                  <label class="text-sm font-medium text-gray-500">説明</label>
                  <p class="mt-1 whitespace-pre-wrap">
                    {{ team.description }}
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
                scope-type="team"
                :scope-id="teamId"
                :can-change-role="isAdminOrDeputy"
                :can-remove="isAdminOrDeputy"
              />
            </div>
          </TabPanel>

          <!-- 招待タブ -->
          <TabPanel v-if="isAdminOrDeputy" :value="2">
            <div class="mt-4">
              <InviteTokenList
                scope-type="team"
                :scope-id="teamId"
              />
            </div>
          </TabPanel>
        </TabPanels>
      </Tabs>

      <!-- 退出確認ダイアログ -->
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
