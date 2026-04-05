<script setup lang="ts">
import type { MemberSkillResponse, SkillCategoryResponse, SkillStatus } from '~/types/skill'

const props = defineProps<{
  teamId: number
  canManage?: boolean
}>()

const emit = defineEmits<{
  edit: [skill: MemberSkillResponse]
  create: []
  verify: [skill: MemberSkillResponse]
}>()

const { searchSkills, deleteSkill, verifySkill, getCertificateUrl } = useSkillApi()
const notification = useNotification()

const items = ref<MemberSkillResponse[]>([])
const categories = ref<SkillCategoryResponse[]>([])
const loading = ref(false)

const keyword = ref('')
const selectedCategoryId = ref<number | null>(null)
const selectedStatus = ref<SkillStatus | null>(null)

const statusOptions = [
  { label: 'すべて', value: null },
  { label: '有効', value: 'ACTIVE' as SkillStatus },
  { label: '期限切れ', value: 'EXPIRED' as SkillStatus },
  { label: '確認待ち', value: 'PENDING_REVIEW' as SkillStatus },
]

async function loadCategories() {
  try {
    const { getSkillCategories } = useSkillApi()
    const res = await getSkillCategories(props.teamId)
    categories.value = res.data
  } catch {
    // categories are optional for filtering
  }
}

const categoryOptions = computed(() => [
  { label: 'すべてのカテゴリ', value: null },
  ...categories.value.map((c) => ({ label: c.name, value: c.id })),
])

async function loadItems() {
  loading.value = true
  try {
    const params: Record<string, unknown> = {}
    if (selectedCategoryId.value) params.categoryId = selectedCategoryId.value
    if (selectedStatus.value) params.status = selectedStatus.value
    if (keyword.value.trim()) params.keyword = keyword.value.trim()
    if (selectedStatus.value !== 'EXPIRED') params.includeExpired = true
    const res = await searchSkills(props.teamId, params)
    items.value = res.data
  } catch {
    notification.error('スキル一覧の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

function getStatusClass(status: SkillStatus): string {
  switch (status) {
    case 'ACTIVE': return 'bg-green-100 text-green-700 dark:bg-green-900 dark:text-green-300'
    case 'EXPIRED': return 'bg-red-100 text-red-700 dark:bg-red-900 dark:text-red-300'
    case 'PENDING_REVIEW': return 'bg-yellow-100 text-yellow-700 dark:bg-yellow-900 dark:text-yellow-300'
    default: return 'bg-surface-100 text-surface-500'
  }
}

function getStatusLabel(status: SkillStatus): string {
  const labels: Record<string, string> = {
    ACTIVE: '有効',
    EXPIRED: '期限切れ',
    PENDING_REVIEW: '確認待ち',
  }
  return labels[status] || status
}

function formatDate(dateStr: string | null): string {
  if (!dateStr) return '-'
  return new Date(dateStr).toLocaleDateString('ja-JP')
}

async function handleDelete(skill: MemberSkillResponse) {
  try {
    await deleteSkill(props.teamId, skill.id)
    notification.success('スキルを削除しました')
    await loadItems()
  } catch {
    notification.error('スキルの削除に失敗しました')
  }
}

async function handleVerify(skill: MemberSkillResponse) {
  try {
    await verifySkill(props.teamId, skill.id)
    notification.success('スキルを承認しました')
    await loadItems()
  } catch {
    notification.error('スキルの承認に失敗しました')
  }
}

async function handleDownloadCertificate(skill: MemberSkillResponse) {
  try {
    const res = await getCertificateUrl(props.teamId, skill.id)
    window.open(res.data.url, '_blank')
  } catch {
    notification.error('証明書の取得に失敗しました')
  }
}

watch([selectedCategoryId, selectedStatus], () => loadItems())

onMounted(async () => {
  await Promise.all([loadItems(), loadCategories()])
})

defineExpose({ refresh: loadItems })
</script>

<template>
  <div>
    <div class="mb-4 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
      <h2 class="text-lg font-semibold">スキル一覧</h2>
      <Button v-if="canManage" label="スキル登録" icon="pi pi-plus" @click="emit('create')" />
    </div>

    <div class="mb-4 flex flex-col gap-2 sm:flex-row sm:items-center">
      <InputText
        v-model="keyword"
        placeholder="キーワード検索"
        class="w-full sm:w-64"
        @keydown.enter="loadItems"
      />
      <Select
        v-model="selectedCategoryId"
        :options="categoryOptions"
        option-label="label"
        option-value="value"
        placeholder="カテゴリ"
        class="w-full sm:w-48"
      />
      <Select
        v-model="selectedStatus"
        :options="statusOptions"
        option-label="label"
        option-value="value"
        placeholder="ステータス"
        class="w-full sm:w-40"
      />
      <Button icon="pi pi-search" severity="secondary" @click="loadItems" />
    </div>

    <div v-if="loading" class="flex justify-center py-8">
      <ProgressSpinner style="width: 40px; height: 40px" />
    </div>

    <div v-else-if="items.length === 0" class="py-12 text-center">
      <i class="pi pi-id-card mb-3 text-4xl text-surface-300" />
      <p class="text-surface-400">スキル・資格がありません</p>
    </div>

    <div v-else class="space-y-2">
      <div
        v-for="skill in items"
        :key="skill.id"
        class="flex items-center gap-3 rounded-xl border border-surface-300 bg-surface-0 p-4 dark:border-surface-600 dark:bg-surface-800"
      >
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-surface-100 dark:bg-surface-700">
          <i class="pi pi-id-card text-lg text-surface-400" />
        </div>
        <div class="min-w-0 flex-1">
          <div class="flex flex-wrap items-center gap-2">
            <span
              :class="getStatusClass(skill.status)"
              class="rounded px-1.5 py-0.5 text-xs font-medium"
            >
              {{ getStatusLabel(skill.status) }}
            </span>
            <span class="rounded bg-surface-100 px-1.5 py-0.5 text-xs text-surface-500 dark:bg-surface-700">
              {{ skill.categoryName }}
            </span>
          </div>
          <h3 class="text-sm font-semibold">{{ skill.name }}</h3>
          <div class="text-xs text-surface-400">
            <span v-if="skill.issuer">{{ skill.issuer }}</span>
            <span v-if="skill.issuer && skill.credentialNumber"> / </span>
            <span v-if="skill.credentialNumber">{{ skill.credentialNumber }}</span>
            <span v-if="skill.acquiredOn"> ・ 取得: {{ formatDate(skill.acquiredOn) }}</span>
            <span v-if="skill.expiresAt"> ・ 有効期限: {{ formatDate(skill.expiresAt) }}</span>
          </div>
          <div v-if="skill.verifiedAt" class="mt-1 text-xs text-green-600 dark:text-green-400">
            <i class="pi pi-check-circle mr-1" />承認済み ({{ formatDate(skill.verifiedAt) }})
          </div>
        </div>
        <div class="flex shrink-0 gap-1">
          <Button
            v-if="skill.hasCertificate"
            icon="pi pi-download"
            size="small"
            text
            severity="info"
            v-tooltip.top="'証明書を表示'"
            @click="handleDownloadCertificate(skill)"
          />
          <Button
            v-if="canManage && skill.status === 'PENDING_REVIEW'"
            icon="pi pi-check"
            size="small"
            text
            severity="success"
            v-tooltip.top="'承認'"
            @click="handleVerify(skill)"
          />
          <Button
            icon="pi pi-pencil"
            size="small"
            text
            @click="emit('edit', skill)"
          />
          <Button
            v-if="canManage"
            icon="pi pi-trash"
            size="small"
            text
            severity="danger"
            @click="handleDelete(skill)"
          />
        </div>
      </div>
    </div>
  </div>
</template>
