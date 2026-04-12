<script setup lang="ts">
import type { OnboardingTemplate, OnboardingProgress, OnboardingPreset } from '~/types/onboarding'

definePageMeta({
  middleware: 'auth',
})

const route = useRoute()
const teamId = computed(() => Number(route.params.id))
const onboardingApi = useOnboardingApi()
const notification = useNotification()
const { isAdmin, loadPermissions } = useRoleAccess('team', teamId)

const templates = ref<OnboardingTemplate[]>([])
const progresses = ref<OnboardingProgress[]>([])
const presets = ref<OnboardingPreset[]>([])
const loading = ref(true)
const activeTab = ref('0')
const showTemplateDialog = ref(false)
const editingTemplate = ref<OnboardingTemplate | undefined>()

async function loadData() {
  loading.value = true
  try {
    await loadPermissions()
    const [tmpl, prog] = await Promise.all([
      onboardingApi.listTemplates('team', teamId.value),
      onboardingApi.listProgresses('team', teamId.value),
    ])
    templates.value = tmpl
    progresses.value = prog
    if (isAdmin.value) {
      presets.value = await onboardingApi.listPresets()
    }
  } catch {
    notification.error('データの取得に失敗しました')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingTemplate.value = undefined
  showTemplateDialog.value = true
}

function openEdit(template: OnboardingTemplate) {
  editingTemplate.value = template
  showTemplateDialog.value = true
}

async function handleSave(data: Parameters<typeof onboardingApi.createTemplate>[2]) {
  try {
    if (editingTemplate.value) {
      await onboardingApi.updateTemplate(editingTemplate.value.id, data)
      notification.success('テンプレートを更新しました')
    } else {
      await onboardingApi.createTemplate('team', teamId.value, data)
      notification.success('テンプレートを作成しました')
    }
    showTemplateDialog.value = false
    await loadData()
  } catch {
    notification.error('テンプレートの保存に失敗しました')
  }
}

async function activateTemplate(id: number) {
  try {
    await onboardingApi.activateTemplate(id)
    notification.success('テンプレートを有効化しました')
    await loadData()
  } catch {
    notification.error('有効化に失敗しました')
  }
}

async function archiveTemplate(id: number) {
  try {
    await onboardingApi.archiveTemplate(id)
    notification.success('テンプレートをアーカイブしました')
    await loadData()
  } catch {
    notification.error('アーカイブに失敗しました')
  }
}

async function handleRemind() {
  try {
    await onboardingApi.sendReminder('team', teamId.value)
    notification.success('リマインダーを送信しました')
  } catch {
    notification.error('リマインダーの送信に失敗しました')
  }
}

const statusLabel = (s: string) =>
  ({ DRAFT: '下書き', ACTIVE: '有効', ARCHIVED: 'アーカイブ' })[s] ?? s
const statusSeverity = (s: string) =>
  ({ DRAFT: 'warn', ACTIVE: 'success', ARCHIVED: 'secondary' })[s] ?? 'info'

onMounted(loadData)
</script>

<template>
  <div class="mx-auto max-w-6xl">
    <PageHeader title="オンボーディング管理" class="mb-6" />

    <PageLoading v-if="loading" />

    <template v-else>
      <Tabs v-model:value="activeTab">
        <TabList>
          <Tab value="0">テンプレート</Tab>
          <Tab value="1">メンバー進捗</Tab>
        </TabList>
        <TabPanels>
          <TabPanel value="0">
            <div class="mb-4 flex justify-end">
              <Button
                v-if="isAdmin"
                label="テンプレート作成"
                icon="pi pi-plus"
                @click="openCreate"
              />
            </div>
            <DataTable :value="templates" data-key="id" striped-rows>
              <template #empty>
                <div class="py-8 text-center text-surface-500">テンプレートがありません</div>
              </template>
              <Column field="name" header="テンプレート名" />
              <Column header="ステータス">
                <template #body="{ data }">
                  <Badge
                    :value="statusLabel(data.status)"
                    :severity="statusSeverity(data.status)"
                  />
                </template>
              </Column>
              <Column header="ステップ数">
                <template #body="{ data }">{{ data.steps?.length ?? 0 }}</template>
              </Column>
              <Column v-if="isAdmin" header="操作" style="width: 200px">
                <template #body="{ data }">
                  <div class="flex gap-1">
                    <Button
                      v-if="data.status === 'DRAFT'"
                      icon="pi pi-pencil"
                      size="small"
                      text
                      @click="openEdit(data)"
                    />
                    <Button
                      v-if="data.status === 'DRAFT'"
                      icon="pi pi-check"
                      size="small"
                      text
                      severity="success"
                      @click="activateTemplate(data.id)"
                    />
                    <Button
                      v-if="data.status === 'ACTIVE'"
                      icon="pi pi-inbox"
                      size="small"
                      text
                      severity="warn"
                      @click="archiveTemplate(data.id)"
                    />
                  </div>
                </template>
              </Column>
            </DataTable>
          </TabPanel>
          <TabPanel value="1">
            <OnboardingProgressList :progresses="progresses" @remind="handleRemind" />
          </TabPanel>
        </TabPanels>
      </Tabs>

      <Dialog
        v-model:visible="showTemplateDialog"
        :header="editingTemplate ? 'テンプレート編集' : 'テンプレート作成'"
        :modal="true"
        class="w-full max-w-2xl"
      >
        <OnboardingTemplateBuilder
          :template="editingTemplate"
          :presets="presets"
          @save="handleSave"
          @cancel="showTemplateDialog = false"
        />
      </Dialog>
    </template>
  </div>
</template>
