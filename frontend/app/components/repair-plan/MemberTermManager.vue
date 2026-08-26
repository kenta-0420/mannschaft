<script setup lang="ts">
import type { MemberResponse } from '~/types/member'
import type { CreateTermRequest, MemberTerm } from '~/types/repairPlanHandover'

const props = defineProps<{
  teamId: string
  isAdmin: boolean
}>()

const { t } = useI18n()
const notification = useNotification()
const teamApi = useTeamApi()
const { listTerms, createTerm, deleteTerm } = useTermApi(props.teamId)

const terms = ref<MemberTerm[]>([])
const loading = ref(false)
const showAddForm = ref(false)
const saving = ref(false)
const deletingId = ref<number | null>(null)

// 追加フォーム用フィールド
const formUserId = ref<number | null>(null)
const formRoleName = ref('')
const formTermStart = ref('')
const formTermEnd = ref('')

// ユーザー選択肢（チームメンバー）
const memberOptions = ref<{ label: string; value: number }[]>([])

onMounted(async () => {
  await Promise.all([loadTerms(), loadMembers()])
})

async function loadTerms() {
  loading.value = true
  try {
    terms.value = await listTerms()
  } catch {
    notification.error(t('common.fetch_failed'))
  } finally {
    loading.value = false
  }
}

async function loadMembers() {
  try {
    // 全メンバーを取得（最大 200 件）
    const res = await teamApi.getMembers(props.teamId, { size: 200 })
    const members: MemberResponse[] = res.data
    memberOptions.value = members.map((m) => ({
      label: m.displayName,
      value: m.userId,
    }))
  } catch {
    // メンバー取得失敗は致命的ではない（入力で対応可能）
  }
}

function openAddForm() {
  formUserId.value = null
  formRoleName.value = ''
  formTermStart.value = ''
  formTermEnd.value = ''
  showAddForm.value = true
}

function cancelAdd() {
  showAddForm.value = false
}

async function handleCreate() {
  if (formUserId.value === null || !formTermStart.value || !formTermEnd.value) return

  const req: CreateTermRequest = {
    userId: formUserId.value,
    termStart: formTermStart.value,
    termEnd: formTermEnd.value,
    roleName: formRoleName.value || undefined,
  }

  saving.value = true
  try {
    const created = await createTerm(req)
    terms.value = [created, ...terms.value]
    showAddForm.value = false
  } catch {
    notification.error(t('common.save_failed'))
  } finally {
    saving.value = false
  }
}

async function handleDelete(termId: number) {
  deletingId.value = termId
  try {
    await deleteTerm(termId)
    terms.value = terms.value.filter((t) => t.id !== termId)
  } catch {
    notification.error(t('common.save_failed'))
  } finally {
    deletingId.value = null
  }
}
</script>

<template>
  <SectionCard :title="$t('repair_plan.handover.term.manager_title')">
    <!-- ヘッダ: 追加ボタン（ADMIN のみ） -->
    <div class="mb-4 flex items-center justify-between">
      <span class="text-sm text-surface-500 dark:text-surface-400">
        {{ terms.length }}{{ $t('common.count_suffix') }}
      </span>
      <Button
        v-if="isAdmin"
        :label="$t('repair_plan.handover.term.add_term')"
        icon="pi pi-plus"
        size="small"
        @click="openAddForm"
      />
    </div>

    <!-- インライン追加フォーム -->
    <div
      v-if="showAddForm"
      class="mb-4 rounded-lg border border-primary-200 bg-primary-50 p-4 dark:border-primary-800 dark:bg-primary-900/20"
    >
      <div class="grid gap-3 sm:grid-cols-2">
        <!-- ユーザー選択 -->
        <div>
          <label class="mb-1 block text-xs font-medium">
            {{ $t('repair_plan.handover.term.col_name') }}
            <span class="ml-1 text-red-500">*</span>
          </label>
          <Select
            v-model="formUserId"
            :options="memberOptions"
            option-label="label"
            option-value="value"
            class="w-full"
          />
        </div>

        <!-- 役職 -->
        <div>
          <label class="mb-1 block text-xs font-medium">
            {{ $t('repair_plan.handover.term.col_role') }}
          </label>
          <InputText
            v-model="formRoleName"
            class="w-full"
            :placeholder="$t('repair_plan.handover.term.role_placeholder')"
          />
        </div>

        <!-- 開始日 -->
        <div>
          <label class="mb-1 block text-xs font-medium">
            {{ $t('common.start_date') }}
            <span class="ml-1 text-red-500">*</span>
          </label>
          <InputText v-model="formTermStart" type="date" class="w-full" />
        </div>

        <!-- 終了日 -->
        <div>
          <label class="mb-1 block text-xs font-medium">
            {{ $t('common.end_date') }}
            <span class="ml-1 text-red-500">*</span>
          </label>
          <InputText v-model="formTermEnd" type="date" class="w-full" />
        </div>
      </div>

      <div class="mt-3 flex justify-end gap-2">
        <Button
          :label="$t('button.cancel')"
          text
          size="small"
          @click="cancelAdd"
        />
        <Button
          :label="$t('button.save')"
          size="small"
          :loading="saving"
          :disabled="formUserId === null || !formTermStart || !formTermEnd"
          @click="handleCreate"
        />
      </div>
    </div>

    <PageLoading v-if="loading" />

    <DashboardEmptyState
      v-else-if="terms.length === 0"
      icon="pi pi-users"
      :message="$t('common.no_data')"
    />

    <!-- 任期テーブル -->
    <div v-else class="overflow-x-auto">
      <table class="w-full text-sm">
        <thead>
          <tr class="border-b border-surface-200 text-left text-xs text-surface-500 dark:border-surface-700 dark:text-surface-400">
            <th class="pb-2 pr-4 font-medium">
              {{ $t('repair_plan.handover.term.col_name') }}
            </th>
            <th class="pb-2 pr-4 font-medium">
              {{ $t('repair_plan.handover.term.col_role') }}
            </th>
            <th class="pb-2 pr-4 font-medium">
              {{ $t('repair_plan.handover.term.col_period') }}
            </th>
            <th class="pb-2 pr-4 font-medium">
              {{ $t('repair_plan.handover.term.col_active') }}
            </th>
            <th v-if="isAdmin" class="pb-2 font-medium" />
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="term in terms"
            :key="term.id"
            class="border-b border-surface-100 dark:border-surface-800"
          >
            <td class="py-2 pr-4 font-medium text-surface-800 dark:text-surface-100">
              {{ term.userDisplayName }}
            </td>
            <td class="py-2 pr-4 text-surface-600 dark:text-surface-400">
              {{ term.roleName ?? '-' }}
            </td>
            <td class="py-2 pr-4 text-surface-600 dark:text-surface-400">
              {{ term.termStart }} 〜 {{ term.termEnd }}
            </td>
            <td class="py-2 pr-4">
              <Tag
                v-if="term.isActive"
                severity="success"
                :value="$t('repair_plan.handover.term.col_active')"
              />
            </td>
            <td v-if="isAdmin" class="py-2">
              <Button
                icon="pi pi-trash"
                size="small"
                severity="danger"
                text
                :loading="deletingId === term.id"
                :aria-label="$t('button.delete')"
                @click="handleDelete(term.id)"
              />
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </SectionCard>
</template>
