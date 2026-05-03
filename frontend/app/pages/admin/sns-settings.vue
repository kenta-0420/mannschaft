<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

const scopeStore = useScopeStore()
const scopeType = computed(() => scopeStore.current.type as 'team' | 'organization')
const scopeId = computed(() => scopeStore.current.id ?? 0)
const { success, error: showError } = useNotification()
const { listFeeds, createFeed, updateFeed, deleteFeed, previewFeed } = useSnsFeedApi()

interface SnsFeed {
  id: number
  provider: string
  accountUsername: string
  displayCount: number
  isActive: boolean
}

const feeds = ref<SnsFeed[]>([])
const loading = ref(true)
const showDialog = ref(false)
const editingItem = ref<SnsFeed | null>(null)
const form = ref({ provider: 'INSTAGRAM', accountUsername: '', isActive: true })
const saving = ref(false)
const previewing = ref<number | null>(null)

const PROVIDERS = [
  { label: 'Instagram', value: 'INSTAGRAM' },
  { label: 'X（旧Twitter）', value: 'TWITTER_X' },
]

function providerLabel(p: string) {
  return PROVIDERS.find((pr) => pr.value === p)?.label ?? p
}

function providerIcon(p: string) {
  return p === 'INSTAGRAM' ? 'pi pi-instagram' : 'pi pi-twitter'
}

async function load() {
  loading.value = true
  try {
    const res = await listFeeds(scopeType.value, scopeId.value)
    feeds.value = res.data as unknown as SnsFeed[]
  } catch {
    showError('SNSフィードの取得に失敗しました')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingItem.value = null
  form.value = { provider: 'INSTAGRAM', accountUsername: '', isActive: true }
  showDialog.value = true
}

function openEdit(item: SnsFeed) {
  editingItem.value = item
  form.value = { provider: item.provider, accountUsername: item.accountUsername, isActive: item.isActive }
  showDialog.value = true
}

async function save() {
  if (!form.value.accountUsername) return
  saving.value = true
  try {
    const body = {
      provider: form.value.provider,
      accountUsername: form.value.accountUsername,
    }
    if (editingItem.value) {
      await updateFeed(scopeType.value, scopeId.value, editingItem.value.id, { ...body, isActive: form.value.isActive })
      success('SNSフィードを更新しました')
    } else {
      await createFeed(scopeType.value, scopeId.value, body)
      success('SNSフィードを追加しました')
    }
    showDialog.value = false
    await load()
  } catch {
    showError('保存に失敗しました')
  } finally {
    saving.value = false
  }
}

async function preview(id: number) {
  previewing.value = id
  try {
    await previewFeed(scopeType.value, scopeId.value, id)
    success('プレビューを更新しました')
    await load()
  } catch {
    showError('プレビューの取得に失敗しました')
  } finally {
    previewing.value = null
  }
}

async function remove(item: SnsFeed) {
  if (!confirm(`「@${item.accountUsername}」の連携を削除しますか？`)) return
  try {
    await deleteFeed(scopeType.value, scopeId.value, item.id)
    success('削除しました')
    await load()
  } catch {
    showError('削除に失敗しました')
  }
}

watch(scopeId, (v) => { if (v) load() })
onMounted(() => { if (scopeId.value) load() })
</script>

<template>
  <div class="mx-auto max-w-4xl">
    <div class="mb-6 flex items-center justify-between">
      <div>
        <PageHeader title="SNS設定"><p class="text-sm text-surface-500">Instagram・X のフィード連携を管理します</p></PageHeader>
      </div>
      <Button label="アカウントを追加" icon="pi pi-plus" @click="openCreate" />
    </div>

    <PageLoading v-if="loading" />

    <DataTable v-else :value="feeds" striped-rows data-key="id">
      <template #empty>
        <DashboardEmptyState icon="pi pi-share-alt" message="SNS連携がありません" />
      </template>
      <Column header="プロバイダー" style="width: 140px">
        <template #body="{ data }">
          <div class="flex items-center gap-2">
            <i :class="[providerIcon(data.provider), 'text-lg']" />
            <span class="text-sm">{{ providerLabel(data.provider) }}</span>
          </div>
        </template>
      </Column>
      <Column header="アカウント">
        <template #body="{ data }">
          <span class="font-medium">@{{ data.accountUsername }}</span>
        </template>
      </Column>
      <Column header="状態" style="width: 80px">
        <template #body="{ data }">
          <Tag :value="data.isActive ? '有効' : '無効'" :severity="data.isActive ? 'success' : 'secondary'" />
        </template>
      </Column>
      <Column header="表示件数" style="width: 90px">
        <template #body="{ data }">
          <span class="text-sm">{{ data.displayCount }}件</span>
        </template>
      </Column>
      <Column header="操作" style="width: 140px">
        <template #body="{ data }">
          <div class="flex gap-1">
            <Button
              v-tooltip="'プレビュー更新'"
              icon="pi pi-refresh"
              size="small"
              text
              severity="info"
              :loading="previewing === data.id"
              @click="preview(data.id)"
            />
            <Button icon="pi pi-pencil" size="small" text severity="secondary" @click="openEdit(data)" />
            <Button icon="pi pi-trash" size="small" text severity="danger" @click="remove(data)" />
          </div>
        </template>
      </Column>
    </DataTable>

    <Dialog
      v-model:visible="showDialog"
      :header="editingItem ? 'SNS設定編集' : 'SNSアカウント追加'"
      :style="{ width: '420px' }"
      modal
    >
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium">プラットフォーム</label>
          <Select
            v-model="form.provider"
            :options="PROVIDERS"
            option-label="label"
            option-value="value"
            class="w-full"
          />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">アカウント名 <span class="text-red-500">*</span></label>
          <InputText v-model="form.accountUsername" class="w-full" placeholder="例: example_team（@不要）" />
        </div>
        <div class="flex items-center gap-2">
          <ToggleSwitch v-model="form.isActive" input-id="isActive" />
          <label for="isActive" class="text-sm">有効</label>
        </div>
      </div>
      <template #footer>
        <Button label="キャンセル" severity="secondary" text @click="showDialog = false" />
        <Button label="保存" :loading="saving" :disabled="!form.accountUsername" @click="save" />
      </template>
    </Dialog>
  </div>
</template>
