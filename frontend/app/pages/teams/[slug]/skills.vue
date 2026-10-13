<script setup lang="ts">
import type { MemberSkillResponse, SkillCategoryResponse } from '~/types/skill'

definePageMeta({ layout: 'team', middleware: 'auth' })

const route = useRoute()
const { t } = useI18n()
const teamSlug = computed(() => String(route.params.slug))
const { roleName, isAdmin, isAdminOrDeputy, isMember, can, loadPermissions } = useRoleAccess('team', teamSlug)
const { getSkillCategories } = useSkillApi()
const notification = useNotification()

const loading = ref(true)
/** 権限情報そのものの取得に失敗した（「権限なし」とは区別して表示する） */
const permissionFailed = ref(false)
const activeTab = ref('0')
const showFormDialog = ref(false)
const editingSkill = ref<MemberSkillResponse | null>(null)
const categories = ref<SkillCategoryResponse[]>([])

const listRef = ref<{ refresh: () => void; refreshCategories: () => Promise<void> } | null>(null)
const matrixRef = ref<{ refresh: () => void } | null>(null)

/** チームメンバー（MEMBER 以上）か。非メンバー・SUPPORTER・GUEST は資格系 API を一切呼ばない。 */
const canUse = computed(() => !permissionFailed.value && isMember.value)

/**
 * 全員の資格一覧・マトリクス・カテゴリ管理を使えるか。
 * BE（SkillViewerAccessGuard）は ADMIN・SYSTEM_ADMIN・MANAGE_MEMBER を持つ DEPUTY_ADMIN だけに許す。
 * それ以外へ出すと 403 のトーストが出るだけなので、画面にも出さない。
 */
const canViewAll = computed(
  () => canUse.value && (isAdmin.value || (roleName.value === 'DEPUTY_ADMIN' && can('MANAGE_MEMBER'))),
)

async function loadData() {
  loading.value = true
  permissionFailed.value = false
  try {
    const result = await loadPermissions()
    if (!result.ok) {
      // 取得失敗を「権限なし」に倒さない（BE 障害時に管理者を誤って弾く対処療法を避ける）
      permissionFailed.value = true
      notification.error(t('skill.page.permissionLoadFailed'))
      return
    }
    if (!isMember.value) return
    const res = await getSkillCategories(teamSlug.value)
    categories.value = res.data
  } catch {
    notification.error('データの取得に失敗しました')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingSkill.value = null
  showFormDialog.value = true
}

function openEdit(skill: MemberSkillResponse) {
  editingSkill.value = skill
  showFormDialog.value = true
}

/** 登録フォームのカテゴリ選択肢を取り直す。失敗は握りつぶさず通知する。 */
async function reloadCategories() {
  try {
    const res = await getSkillCategories(teamSlug.value)
    categories.value = res.data
  } catch {
    notification.error(t('skill.page.categoriesReloadFailed'))
  }
}

async function onSaved() {
  listRef.value?.refresh()
  await reloadCategories()
}

/** カテゴリの作成・改名・削除後: 登録フォーム・一覧（名前解決と絞り込み）・マトリクス列を最新にする。 */
async function onCategoriesChanged() {
  await Promise.all([reloadCategories(), listRef.value?.refreshCategories()])
  matrixRef.value?.refresh()
}

onMounted(loadData)
</script>

<template>
  <div class="mx-auto max-w-6xl">
    <PageHeader title="スキル・資格" class="mb-6" />

    <PageLoading v-if="loading" />

    <!-- 権限情報の取得失敗（「権限なし」とは別物） -->
    <DashboardErrorState
      v-else-if="permissionFailed"
      :message="t('skill.page.permissionLoadFailed')"
      @retry="loadData"
    />

    <!-- 非メンバー・SUPPORTER・GUEST -->
    <DashboardEmptyState
      v-else-if="!canUse"
      icon="pi pi-lock"
      :message="t('skill.page.forbidden')"
    />

    <template v-else>
      <Tabs v-model:value="activeTab">
        <TabList>
          <Tab value="0">{{ canViewAll ? 'スキル一覧' : t('skill.page.mineTab') }}</Tab>
          <Tab v-if="canViewAll" value="1">スキルマトリクス</Tab>
          <Tab v-if="canViewAll" value="2">カテゴリ管理</Tab>
        </TabList>
        <TabPanels>
          <TabPanel value="0">
            <SkillList
              ref="listRef"
              :team-id="teamSlug"
              :can-manage="isAdminOrDeputy"
              :scope="canViewAll ? 'all' : 'mine'"
              @create="openCreate"
              @edit="openEdit"
            />
          </TabPanel>
          <TabPanel v-if="canViewAll" value="1">
            <SkillMatrix ref="matrixRef" :team-id="teamSlug" />
          </TabPanel>
          <TabPanel v-if="canViewAll" value="2">
            <SkillCategoryManager :team-id="teamSlug" @changed="onCategoriesChanged" />
          </TabPanel>
        </TabPanels>
      </Tabs>

      <SkillForm
        v-model:visible="showFormDialog"
        :team-id="teamSlug"
        :skill="editingSkill"
        :categories="categories"
        @saved="onSaved"
      />
    </template>
  </div>
</template>
