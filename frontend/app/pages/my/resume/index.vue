<script setup lang="ts">
/**
 * F01.10 マイページ履歴書・職務経歴書 — バージョン一覧ページ。
 *
 * 自分の履歴書バージョンをカード形式で一覧表示する。
 * - 新規作成: タイトル未入力でPOST → サーバが「下書き YYYY-MM-DD」を自動採番
 * - 各バージョンに編集・複製・削除ボタン
 * - 削除は確認ダイアログ付き
 */
import type { ResumeSummary } from '~/types/resume'

const { t } = useI18n()
const { listResumes, createResume, deleteResume, duplicateResume } = useResumeApi()
const { success, error } = useNotification()
const { formatDate } = useDatetime()
const confirm = useConfirm()

definePageMeta({
  middleware: 'auth',
})

useHead({ title: () => t('common.resume.title') })

// === データ ===
const resumes = ref<ResumeSummary[]>([])
const loading = ref(false)
const busyId = ref<string | null>(null)
const creating = ref(false)

// 新規作成ダイアログ
const showCreateDialog = ref(false)
const newTitle = ref('')

async function fetchResumes() {
  loading.value = true
  try {
    const res = await listResumes()
    resumes.value = res.data
  }
  catch (e) {
    error(t('common.resume.loadError'), String(e))
  }
  finally {
    loading.value = false
  }
}

// === 新規作成 ===
async function handleCreate() {
  creating.value = true
  try {
    const res = await createResume(newTitle.value.trim() ? { title: newTitle.value.trim() } : undefined)
    // 作成後すぐにエディタへ遷移
    await navigateTo(`/my/resume/${res.data.id}`)
  }
  catch (e) {
    error(t('common.resume.saveError'), String(e))
    creating.value = false
  }
}

function openCreateDialog() {
  newTitle.value = ''
  showCreateDialog.value = true
}

// === 複製 ===
async function handleDuplicate(id: string) {
  busyId.value = id
  try {
    await duplicateResume(id)
    success(t('common.resume.duplicateSuccess'))
    await fetchResumes()
  }
  catch (e) {
    error(t('common.resume.duplicateError'), String(e))
  }
  finally {
    busyId.value = null
  }
}

// === 削除 ===
function handleDelete(resume: ResumeSummary) {
  confirm.require({
    message: t('common.resume.confirmDelete'),
    header: resume.title,
    icon: 'pi pi-exclamation-triangle',
    acceptClass: 'p-button-danger',
    accept: async () => {
      busyId.value = resume.id
      try {
        await deleteResume(resume.id)
        success(t('common.resume.deleteSuccess'))
        await fetchResumes()
      }
      catch (e) {
        error(t('common.resume.deleteError'), String(e))
      }
      finally {
        busyId.value = null
      }
    },
  })
}

onMounted(fetchResumes)
</script>

<template>
  <div class="mx-auto max-w-4xl px-4 py-6">
    <PageHeader :title="t('common.resume.title')" back-to="/my">
      <Button
        :label="t('common.resume.createNew')"
        icon="pi pi-plus"
        class="ml-auto"
        @click="openCreateDialog"
      />
    </PageHeader>

    <!-- ローディング -->
    <PageLoading v-if="loading" />

    <!-- 空状態 -->
    <DashboardEmptyState
      v-else-if="resumes.length === 0"
      icon="pi pi-file"
      :message="t('common.resume.noVersions')"
    />

    <!-- バージョン一覧 -->
    <div v-else class="grid grid-cols-1 gap-4 sm:grid-cols-2">
      <DashboardWidgetCard
        v-for="resume in resumes"
        :key="resume.id"
        :title="resume.title"
        :scrollable="false"
      >
        <!-- バッジ -->
        <div class="mb-3 flex flex-wrap items-center gap-2 text-sm text-surface-500">
          <span>
            <i class="pi pi-clock mr-1" />
            {{ formatDate(resume.updatedAt) }}
          </span>
          <Tag
            v-if="resume.hasPhoto"
            icon="pi pi-image"
            :value="t('common.resume.photoUpload')"
            severity="info"
          />
          <Tag
            :value="resume.eraFormat === 'WESTERN' ? t('common.resume.western') : t('common.resume.japanese')"
            severity="secondary"
          />
        </div>

        <!-- 操作ボタン -->
        <div class="flex flex-wrap gap-2">
          <NuxtLink :to="`/my/resume/${resume.id}`">
            <Button
              :label="t('common.resume.edit')"
              icon="pi pi-pencil"
              size="small"
            />
          </NuxtLink>
          <Button
            :label="t('common.resume.duplicate')"
            icon="pi pi-copy"
            size="small"
            severity="secondary"
            :loading="busyId === resume.id"
            :disabled="busyId !== null && busyId !== resume.id"
            @click="handleDuplicate(resume.id)"
          />
          <Button
            :label="t('common.resume.delete')"
            icon="pi pi-trash"
            size="small"
            severity="danger"
            text
            :loading="busyId === resume.id"
            :disabled="busyId !== null && busyId !== resume.id"
            @click="handleDelete(resume)"
          />
        </div>
      </DashboardWidgetCard>
    </div>

    <!-- 新規作成ダイアログ -->
    <Dialog
      v-model:visible="showCreateDialog"
      :header="t('common.resume.createNew')"
      modal
      :style="{ width: '400px' }"
    >
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium">
            {{ t('common.resume.versionTitle') }}
            <span class="ml-1 text-xs text-surface-400">（{{ t('common.resume.profileNote').slice(0, 10) }}…省略可）</span>
          </label>
          <InputText
            v-model="newTitle"
            :placeholder="t('common.resume.versionTitle')"
            class="w-full"
            @keydown.enter="handleCreate"
          />
          <p class="mt-1 text-xs text-surface-500">
            {{ t('common.resume.profileNote') }}
          </p>
        </div>
      </div>
      <template #footer>
        <Button
          :label="t('common.resume.createNew')"
          icon="pi pi-plus"
          :loading="creating"
          @click="handleCreate"
        />
      </template>
    </Dialog>
  </div>
</template>
