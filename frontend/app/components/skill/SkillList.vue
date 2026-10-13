<script setup lang="ts">
import type { MemberSkillResponse, SkillCategoryResponse, SkillSearchItem, SkillStatus } from '~/types/skill'

const props = withDefaults(defineProps<{
  teamId: string
  canManage?: boolean
  /**
   * 'all'  = 全員の資格（GET /skills/search。ADMIN・SYSTEM_ADMIN・MANAGE_MEMBER を持つ DEPUTY_ADMIN だけ BE が許す）
   * 'mine' = 自分の資格だけ（GET /skills/me。MEMBER 以上）。search は呼ばない
   */
  scope?: 'all' | 'mine'
}>(), { canManage: false, scope: 'all' })

const emit = defineEmits<{
  edit: [skill: MemberSkillResponse]
  create: []
  verify: [skill: MemberSkillResponse]
  /** 承認・削除が成功し一覧が変わった（親がスキルマトリクスなど関連表示を取り直す） */
  changed: []
}>()

const { searchSkills, getMySkills, getSkill, deleteSkill, verifySkill, getCertificateUrl, getSkillCategories } = useSkillApi()
const notification = useNotification()
const { confirmAction } = useConfirmDialog()
const { formatDate } = useDatetime()
const { t } = useI18n()
const authStore = useAuthStore()

const items = ref<SkillSearchItem[]>([])
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

/** @returns 取得に成功したか（失敗時は通知済み） */
async function loadCategories(): Promise<boolean> {
  try {
    // 無効化済みカテゴリに属する資格の名前解決のため、無効も含めて取得する
    const res = await getSkillCategories(props.teamId, true)
    categories.value = res.data
    return true
  } catch {
    notification.error(t('skill.list.loadCategoriesFailed'))
    return false
  }
}

/**
 * カテゴリの作成・改名・削除後に親から呼ばれる。名前解決と絞り込みの選択肢を取り直し、
 * 絞り込み中のカテゴリが無効化・削除されていればフィルタを解除する（解除すると watch が一覧を取り直す）。
 */
async function refreshCategories(): Promise<void> {
  const ok = await loadCategories()
  if (!ok) return
  const selected = selectedCategoryId.value
  if (selected !== null && !categories.value.some((c) => c.id === selected && c.isActive)) {
    selectedCategoryId.value = null
  }
}

const categoryOptions = computed(() => [
  { label: 'すべてのカテゴリ', value: null },
  ...categories.value.filter((c) => c.isActive).map((c) => ({ label: c.name, value: c.id })),
])

/** /skills/me の完全な形を一覧表示用（検索API の形）へ寄せる。 */
function toListItem(skill: MemberSkillResponse): SkillSearchItem {
  return {
    memberSkillId: skill.id,
    userId: skill.userId,
    displayName: null,
    categoryId: skill.skillCategoryId,
    skillName: skill.name,
    issuer: skill.issuer,
    acquiredOn: skill.acquiredOn,
    expiresAt: skill.expiresAt,
    status: skill.status,
    hasCertificate: skill.hasCertificate,
    verifiedAt: skill.verifiedAt,
    expiringSoon: false,
  }
}

async function loadItems() {
  loading.value = true
  try {
    if (props.scope === 'mine') {
      const mine = await getMySkills(props.teamId)
      items.value = mine.data.map(toListItem)
      return
    }
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

function getCategoryName(categoryId: number): string {
  return categories.value.find((c) => c.id === categoryId)?.name ?? ''
}

/** BE は編集・削除・証明書を本人または ADMIN/DEPUTY_ADMIN のみに許す。 */
function canOperate(skill: SkillSearchItem): boolean {
  const currentUserId = authStore.currentUser?.id
  const isOwner = typeof currentUserId === 'number' && skill.userId === currentUserId
  return isOwner || !!props.canManage
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

function formatSkillDate(dateStr: string | null): string {
  if (!dateStr) return '-'
  return formatDate(dateStr)
}

async function handleEdit(skill: SkillSearchItem) {
  try {
    const res = await getSkill(props.teamId, skill.memberSkillId)
    emit('edit', res.data)
  } catch {
    notification.error(t('skill.list.loadDetailFailed'))
  }
}

function handleDelete(skill: SkillSearchItem) {
  confirmAction({
    message: t('skill.list.deleteConfirm', { name: skill.skillName }),
    header: t('skill.list.deleteConfirmHeader'),
    onAccept: async () => {
      try {
        await deleteSkill(props.teamId, skill.memberSkillId)
      } catch {
        notification.error('スキルの削除に失敗しました')
        return
      }
      notification.success('スキルを削除しました')
      await loadItems()
      emit('changed')
    },
  })
}

async function handleVerify(skill: SkillSearchItem) {
  try {
    await verifySkill(props.teamId, skill.memberSkillId)
    notification.success('スキルを承認しました')
    await loadItems()
    emit('changed')
  } catch {
    notification.error('スキルの承認に失敗しました')
  }
}

async function handleDownloadCertificate(skill: SkillSearchItem) {
  try {
    const res = await getCertificateUrl(props.teamId, skill.memberSkillId)
    window.open(res.data, '_blank')
  } catch {
    notification.error('証明書の取得に失敗しました')
  }
}

watch([selectedCategoryId, selectedStatus], () => loadItems())

onMounted(async () => {
  await Promise.all([loadItems(), loadCategories()])
})

defineExpose({ refresh: loadItems, refreshCategories })
</script>

<template>
  <div>
    <div class="mb-4 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
      <h2 class="text-lg font-semibold">{{ scope === 'mine' ? t('skill.list.mineTitle') : 'スキル一覧' }}</h2>
      <Button label="スキル登録" icon="pi pi-plus" @click="emit('create')" />
    </div>

    <div v-if="scope === 'all'" class="mb-4 flex flex-col gap-2 sm:flex-row sm:items-center">
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
      <LoadingBounce />
    </div>

    <div v-else-if="items.length === 0" class="py-12 text-center">
      <i class="pi pi-id-card mb-3 text-4xl text-surface-300" />
      <p class="text-surface-400">スキル・資格がありません</p>
    </div>

    <div v-else class="space-y-2">
      <div
        v-for="skill in items"
        :key="skill.memberSkillId"
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
              {{ getCategoryName(skill.categoryId) }}
            </span>
          </div>
          <h3 class="text-sm font-semibold">{{ skill.skillName }}</h3>
          <div class="text-xs text-surface-400">
            <span v-if="skill.issuer">{{ skill.issuer }}</span>
            <span v-if="skill.acquiredOn"> ・ 取得: {{ formatSkillDate(skill.acquiredOn) }}</span>
            <span v-if="skill.expiresAt"> ・ 有効期限: {{ formatSkillDate(skill.expiresAt) }}</span>
          </div>
          <div v-if="skill.verifiedAt" class="mt-1 text-xs text-green-600 dark:text-green-400">
            <i class="pi pi-check-circle mr-1" />承認済み ({{ formatSkillDate(skill.verifiedAt) }})
          </div>
        </div>
        <div class="flex shrink-0 gap-1">
          <Button
            v-if="canOperate(skill) && skill.hasCertificate"
            v-tooltip.top="t('skill.list.actions.certificate')"
            :aria-label="t('skill.list.actions.certificate')"
            icon="pi pi-download"
            size="small"
            text
            severity="info"
            @click="handleDownloadCertificate(skill)"
          />
          <Button
            v-if="canManage && skill.status === 'PENDING_REVIEW'"
            v-tooltip.top="t('skill.list.actions.verify')"
            :aria-label="t('skill.list.actions.verify')"
            icon="pi pi-check"
            size="small"
            text
            severity="success"
            @click="handleVerify(skill)"
          />
          <Button
            v-if="canOperate(skill)"
            :aria-label="t('skill.list.actions.edit')"
            icon="pi pi-pencil"
            size="small"
            text
            @click="handleEdit(skill)"
          />
          <Button
            v-if="canOperate(skill)"
            :aria-label="t('skill.list.actions.delete')"
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
