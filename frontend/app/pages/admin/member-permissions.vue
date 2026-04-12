<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

const api = useApi()
const scopeStore = useScopeStore()
const scopeType = computed(() => scopeStore.current.type as 'team' | 'organization')
const scopeId = computed(() => scopeStore.current.id ?? 0)
const { success, error: showError } = useNotification()

interface PermissionItem {
  key: string
  label: string
  category: string
  enabled: boolean
}

const DEFAULT_PERMISSIONS: Omit<PermissionItem, 'enabled'>[] = [
  { key: 'post.create', label: '投稿作成', category: 'タイムライン' },
  { key: 'post.edit_own', label: '自分の投稿を編集', category: 'タイムライン' },
  { key: 'post.delete_own', label: '自分の投稿を削除', category: 'タイムライン' },
  { key: 'schedule.view', label: 'スケジュール閲覧', category: 'スケジュール' },
  { key: 'schedule.create', label: 'スケジュール作成', category: 'スケジュール' },
  { key: 'bulletin.create', label: '掲示板スレッド作成', category: '掲示板' },
  { key: 'bulletin.reply', label: '掲示板返信', category: '掲示板' },
  { key: 'file.upload', label: 'ファイルアップロード', category: 'ファイル' },
  { key: 'file.download', label: 'ファイルダウンロード', category: 'ファイル' },
  { key: 'member.view', label: 'メンバー一覧閲覧', category: 'メンバー' },
  { key: 'chat.message', label: 'チャット送信', category: 'チャット' },
  { key: 'gallery.upload', label: 'ギャラリーアップロード', category: 'ギャラリー' },
]

const permissions = ref<PermissionItem[]>([])
const loading = ref(true)
const saving = ref(false)

const categories = computed(() => [...new Set(DEFAULT_PERMISSIONS.map((p) => p.category))])

function permsByCategory(cat: string) {
  return permissions.value.filter((p) => p.category === cat)
}

async function load() {
  loading.value = true
  try {
    const res = await api<{ data: Record<string, boolean> }>(
      `/api/v1/${scopeType.value}s/${scopeId.value}/role-permissions/member`,
    )
    permissions.value = DEFAULT_PERMISSIONS.map((p) => ({
      ...p,
      enabled: res.data[p.key] ?? true,
    }))
  } catch {
    permissions.value = DEFAULT_PERMISSIONS.map((p) => ({ ...p, enabled: true }))
  } finally {
    loading.value = false
  }
}

async function save() {
  saving.value = true
  try {
    const body = Object.fromEntries(permissions.value.map((p) => [p.key, p.enabled]))
    await api(`/api/v1/${scopeType.value}s/${scopeId.value}/role-permissions/member`, {
      method: 'PUT',
      body,
    })
    success('MEMBER権限を更新しました')
  } catch {
    showError('更新に失敗しました')
  } finally {
    saving.value = false
  }
}

watch(scopeId, (v) => { if (v) load() })
onMounted(() => { if (scopeId.value) load() })
</script>

<template>
  <div class="mx-auto max-w-3xl">
    <div class="mb-6 flex items-center justify-between">
      <div>
        <PageHeader title="MEMBER権限設定"><p class="text-sm text-surface-500">MEMBERロールのデフォルト操作をON/OFFで調整します</p></PageHeader>
      </div>
      <Button label="保存" icon="pi pi-check" :loading="saving" @click="save" />
    </div>

    <PageLoading v-if="loading" />

    <div v-else class="flex flex-col gap-6">
      <SectionCard
        v-for="cat in categories"
        :key="cat"
        :title="cat"
      >
        <div class="grid gap-3 sm:grid-cols-2">
          <div
            v-for="perm in permsByCategory(cat)"
            :key="perm.key"
            class="flex items-center justify-between rounded-lg bg-surface-50 px-3 py-2 dark:bg-surface-900"
          >
            <span class="text-sm">{{ perm.label }}</span>
            <ToggleSwitch v-model="perm.enabled" />
          </div>
        </div>
      </SectionCard>
    </div>
  </div>
</template>
