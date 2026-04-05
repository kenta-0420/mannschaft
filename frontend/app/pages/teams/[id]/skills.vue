<script setup lang="ts">
import type { MemberSkillResponse, SkillCategoryResponse } from '~/types/skill'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const teamId = computed(() => Number(route.params.id))
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamId)
const { getSkillCategories } = useSkillApi()
const notification = useNotification()

const loading = ref(true)
const activeTab = ref('0')
const showFormDialog = ref(false)
const editingSkill = ref<MemberSkillResponse | null>(null)
const categories = ref<SkillCategoryResponse[]>([])

const listRef = ref<{ refresh: () => void } | null>(null)

async function loadData() {
  loading.value = true
  try {
    await loadPermissions()
    const res = await getSkillCategories(teamId.value)
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

async function onSaved() {
  listRef.value?.refresh()
  try {
    const res = await getSkillCategories(teamId.value)
    categories.value = res.data
  } catch {
    // silent
  }
}

onMounted(loadData)
</script>

<template>
  <div class="mx-auto max-w-6xl">
    <h1 class="mb-6 text-2xl font-bold">スキル・資格</h1>

    <PageLoading v-if="loading" />

    <template v-else>
      <Tabs v-model:value="activeTab">
        <TabList>
          <Tab value="0">スキル一覧</Tab>
          <Tab value="1">スキルマトリクス</Tab>
          <Tab v-if="isAdminOrDeputy" value="2">カテゴリ管理</Tab>
        </TabList>
        <TabPanels>
          <TabPanel value="0">
            <SkillList
              ref="listRef"
              :team-id="teamId"
              :can-manage="isAdminOrDeputy"
              @create="openCreate"
              @edit="openEdit"
            />
          </TabPanel>
          <TabPanel value="1">
            <SkillMatrix :team-id="teamId" />
          </TabPanel>
          <TabPanel v-if="isAdminOrDeputy" value="2">
            <SkillCategoryManager :team-id="teamId" />
          </TabPanel>
        </TabPanels>
      </Tabs>
    </template>

    <SkillForm
      v-model:visible="showFormDialog"
      :team-id="teamId"
      :skill="editingSkill"
      :categories="categories"
      @saved="onSaved"
    />
  </div>
</template>
