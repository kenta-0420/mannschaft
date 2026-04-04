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
const {
  roleName,
  loading: roleLoading,
  loadPermissions,
  isAdmin,
  isAdminOrDeputy,
} = useRoleAccess('team', teamId)

// サポーター申請状態
const followStatus = ref<'NONE' | 'PENDING' | 'APPROVED'>('NONE')
const followLoading = ref(false)
const showCancelSupporterConfirm = ref(false)

async function fetchFollowStatus() {
  if (roleName.value) return // メンバーは不要
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

const templateLabel: Record<string, string> = {
  CLUB: 'クラブ・サークル',
  CLINIC: 'クリニック',
  CLASS: 'クラス',
  COMMUNITY: 'コミュニティ',
  COMPANY: '企業',
  FAMILY: '家族',
  RESTAURANT: '飲食店',
  BEAUTY: '美容院・サロン',
  STORE: '店舗・小売',
  VOLUNTEER: 'ボランティア・NPO',
  NEIGHBORHOOD: '自治会',
  CONDO: 'マンション管理組合',
  OTHER: 'その他',
}

const visibilityLabel: Record<string, string> = {
  PUBLIC: '公開',
  ORGANIZATION_ONLY: 'チーム内のみ',
  PRIVATE: '非公開',
}

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
      <!-- ヘッダー -->
      <div class="mb-6 flex items-center justify-between">
        <div class="flex items-center gap-4">
          <Button icon="pi pi-arrow-left" text rounded @click="navigateTo('/dashboard')" />
          <div>
            <h1 class="text-2xl font-bold">
              {{ team.nickname1 || team.name }}
            </h1>
            <div class="mt-1 flex items-center gap-2">
              <Tag :value="templateLabel[team.template] ?? team.template" severity="info" />
              <RoleBadge v-if="roleName" :role="roleName" />
            </div>
            <div class="mt-2 flex items-center gap-4 text-sm text-surface-500">
              <span class="flex items-center gap-1">
                <i class="pi pi-users text-xs" />
                メンバー <strong class="text-surface-700">{{ team.memberCount }}</strong
                >人
              </span>
              <span v-if="team.supporterEnabled" class="flex items-center gap-1">
                <i class="pi pi-heart text-xs" />
                サポーター <strong class="text-surface-700">{{ team.supporterCount ?? '—' }}</strong
                >人
              </span>
            </div>
          </div>
        </div>
        <!-- サポーター申請ボタン（非メンバー向け） -->
        <template v-if="team.supporterEnabled && !roleName">
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
          <Tab :value="0"> ダッシュボード </Tab>
          <Tab :value="1"> 基本情報 </Tab>
          <Tab :value="2"> メンバー </Tab>
          <Tab v-if="isAdminOrDeputy" :value="3"> 招待 </Tab>
          <Tab v-if="isAdmin && team.supporterEnabled" :value="4"> サポーター管理 </Tab>
          <Tab v-if="isAdmin" :value="5"> 機能設定 </Tab>
        </TabList>

        <TabPanels>
          <!-- ダッシュボードタブ -->
          <TabPanel :value="0">
            <div class="mt-4">
              <ScopeDashboard
                scope-type="team"
                :scope-id="teamId"
                :scope-name="team.nickname1 || team.name"
                :scope-template="team.template"
              />
            </div>
          </TabPanel>

          <!-- 基本情報タブ -->
          <TabPanel :value="1">
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
                  <p class="mt-1">{{ team.memberCount }}人</p>
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
              <Button label="設定を編集" icon="pi pi-pencil" outlined />
            </div>
          </TabPanel>

          <!-- メンバータブ -->
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

          <!-- 招待タブ -->
          <TabPanel v-if="isAdminOrDeputy" :value="3">
            <div class="mt-4">
              <InviteTokenList scope-type="team" :scope-id="teamId" />
            </div>
          </TabPanel>

          <!-- サポーター管理タブ -->
          <TabPanel v-if="isAdmin && team.supporterEnabled" :value="4">
            <div class="mt-4">
              <SupporterManagementPanel scope-type="team" :scope-id="teamId" />
            </div>
          </TabPanel>

          <!-- 機能設定タブ -->
          <TabPanel v-if="isAdmin" :value="5">
            <div class="mt-4">
              <ModuleSettingsPanel scope-type="team" :scope-id="teamId" />
            </div>
          </TabPanel>
        </TabPanels>
      </Tabs>

      <!-- 退出確認ダイアログ -->
      <!-- サポーターをやめる確認ダイアログ -->
      <Dialog
        v-model:visible="showCancelSupporterConfirm"
        header="サポーターをやめますか？"
        :style="{ width: '400px' }"
        modal
      >
        <p>{{ team.nickname1 || team.name }}のサポーターをやめます。よろしいですか？</p>
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
