<script setup lang="ts">
import type {
  TeamPage,
  MemberProfile,
  CreateMemberProfileRequest,
} from '~/types/member-profile'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const orgSlug = computed(() => String(route.params.slug))
const memberProfileApi = useMemberProfileApi()
const orgApi = useOrganizationApi()
const notification = useNotification()
const { isAdmin, loadPermissions } = useRoleAccess('organization', orgSlug)
const { t } = useI18n()

// 年度別ページの二段構え: ページ一覧 → ページを選ぶとそのページのメンバー一覧、という2画面構成。
// BE の実在エンドポイントは /api/v1/team/pages・/api/v1/team/members（teamId/organizationId は
// 数値IDで指定する）であり、URL の slug をそのまま使えないため組織の numericId を解決してから叩く。
const view = ref<'pages' | 'members'>('pages')
const organizationId = ref<number | null>(null)

const loading = ref(true)
const loadError = ref(false)

const pages = ref<TeamPage[]>([])
const selectedPage = ref<TeamPage | null>(null)
const members = ref<MemberProfile[]>([])
const membersLoading = ref(false)

// --- ページ管理 ---
const showPageDialog = ref(false)
const pageForm = ref({
  title: '',
  slug: '',
  pageType: 'YEARLY' as 'MAIN' | 'YEARLY',
  year: new Date().getFullYear(),
  visibility: 'MEMBERS_ONLY' as 'PUBLIC' | 'MEMBERS_ONLY',
})
const showDeletePageDialog = ref(false)
const deleteTargetPage = ref<TeamPage | null>(null)

const showCopyDialog = ref(false)
const copySourcePageId = ref<number | null>(null)

async function resolveOrganizationId() {
  const res = await orgApi.getOrganization(orgSlug.value)
  organizationId.value = res.data.numericId ?? null
}

async function loadPages() {
  if (organizationId.value == null) return
  const res = await memberProfileApi.listPages({ organizationId: organizationId.value, size: 100 })
  pages.value = res.data
}

async function loadData() {
  loading.value = true
  loadError.value = false
  try {
    await loadPermissions()
    await resolveOrganizationId()
    await loadPages()
  } catch {
    loadError.value = true
    notification.error(t('common.memberProfile.loadFailed'))
  } finally {
    loading.value = false
  }
}

function openCreatePage() {
  pageForm.value = {
    title: '',
    slug: '',
    pageType: 'YEARLY',
    year: new Date().getFullYear(),
    visibility: 'MEMBERS_ONLY',
  }
  showPageDialog.value = true
}

async function savePage() {
  if (organizationId.value == null) return
  try {
    await memberProfileApi.createPage({
      organizationId: organizationId.value,
      title: pageForm.value.title,
      slug: pageForm.value.slug,
      pageType: pageForm.value.pageType,
      year: pageForm.value.pageType === 'YEARLY' ? pageForm.value.year : undefined,
      visibility: pageForm.value.visibility,
    })
    notification.success(t('common.memberProfile.pages.createSuccess'))
    showPageDialog.value = false
    await loadPages()
  } catch {
    notification.error(t('common.memberProfile.pages.createFailed'))
  }
}

async function togglePublish(page: TeamPage) {
  try {
    const nextStatus = page.status === 'PUBLISHED' ? 'DRAFT' : 'PUBLISHED'
    await memberProfileApi.changePageStatus(page.id, nextStatus)
    notification.success(t('common.memberProfile.pages.updateSuccess'))
    await loadPages()
  } catch {
    notification.error(t('common.memberProfile.pages.statusChangeFailed'))
  }
}

function confirmDeletePage(page: TeamPage) {
  deleteTargetPage.value = page
  showDeletePageDialog.value = true
}

async function executeDeletePage() {
  if (!deleteTargetPage.value) return
  try {
    await memberProfileApi.deletePage(deleteTargetPage.value.id)
    notification.success(t('common.memberProfile.pages.deleteSuccess'))
    showDeletePageDialog.value = false
    deleteTargetPage.value = null
    await loadPages()
  } catch {
    notification.error(t('common.memberProfile.pages.deleteFailed'))
  }
}

// --- メンバー一覧（ページ選択後） ---

async function openPage(page: TeamPage) {
  selectedPage.value = page
  view.value = 'members'
  await loadMembers()
}

function backToPages() {
  view.value = 'pages'
  selectedPage.value = null
  members.value = []
}

async function loadMembers() {
  if (!selectedPage.value) return
  membersLoading.value = true
  try {
    const res = await memberProfileApi.listMembers(selectedPage.value.id, 0, 100)
    members.value = res.data
  } catch {
    notification.error(t('common.memberProfile.loadFailed'))
  } finally {
    membersLoading.value = false
  }
}

// --- メンバーCRUD ---
const showMemberDialog = ref(false)
const editingMember = ref<MemberProfile | null>(null)
const memberForm = ref({
  displayName: '',
  memberNumber: '',
  bio: '',
  position: '',
})

function openCreateMember() {
  editingMember.value = null
  memberForm.value = { displayName: '', memberNumber: '', bio: '', position: '' }
  showMemberDialog.value = true
}

function openEditMember(profile: MemberProfile) {
  editingMember.value = profile
  memberForm.value = {
    displayName: profile.displayName,
    memberNumber: profile.memberNumber ?? '',
    bio: profile.bio ?? '',
    position: profile.position ?? '',
  }
  showMemberDialog.value = true
}

async function saveMember() {
  if (!selectedPage.value) return
  try {
    if (editingMember.value) {
      await memberProfileApi.updateMember(editingMember.value.id, {
        displayName: memberForm.value.displayName,
        memberNumber: memberForm.value.memberNumber || undefined,
        bio: memberForm.value.bio || undefined,
        position: memberForm.value.position || undefined,
      })
      notification.success('メンバーを更新しました')
    } else {
      const body: CreateMemberProfileRequest = {
        teamPageId: selectedPage.value.id,
        displayName: memberForm.value.displayName,
        memberNumber: memberForm.value.memberNumber || undefined,
        bio: memberForm.value.bio || undefined,
        position: memberForm.value.position || undefined,
      }
      await memberProfileApi.createMember(body)
      notification.success('メンバーを追加しました')
    }
    showMemberDialog.value = false
    await loadMembers()
  } catch {
    notification.error(t('common.memberProfile.saveFailed'))
  }
}

async function handleDeleteMember(id: number) {
  try {
    await memberProfileApi.deleteMember(id)
    notification.success('メンバーを削除しました')
    await loadMembers()
  } catch {
    notification.error(t('common.memberProfile.deleteFailed'))
  }
}

// --- 一括登録 ---
const showBulkDialog = ref(false)
const bulkText = ref('')

async function executeBulkRegister() {
  if (!selectedPage.value) return
  const lines = bulkText.value.split('\n').map((l) => l.trim()).filter(Boolean)
  const items = lines.map((line) => {
    const [displayName, memberNumber] = line.split(',').map((s) => s?.trim())
    return { displayName: displayName ?? '', memberNumber: memberNumber || undefined }
  }).filter((item) => item.displayName)

  if (items.length === 0) return

  try {
    const res = await memberProfileApi.bulkCreateMembers({
      teamPageId: selectedPage.value.id,
      members: items,
    })
    notification.success(t('common.memberProfile.members.bulkRegisterSuccess', { count: res.createdCount }))
    showBulkDialog.value = false
    bulkText.value = ''
    await loadMembers()
  } catch {
    notification.error(t('common.memberProfile.members.bulkRegisterFailed'))
  }
}

// --- 前年度からのコピー ---
const copyCandidatePages = computed(() =>
  pages.value.filter((p) => p.id !== selectedPage.value?.id),
)

function openCopyDialog() {
  copySourcePageId.value = copyCandidatePages.value[0]?.id ?? null
  showCopyDialog.value = true
}

async function executeCopy() {
  if (!selectedPage.value || copySourcePageId.value == null) return
  try {
    const res = await memberProfileApi.copyMembers(selectedPage.value.id, copySourcePageId.value)
    notification.success(t('common.memberProfile.pages.copySuccess', { count: res.copiedCount }))
    showCopyDialog.value = false
    await loadMembers()
  } catch {
    notification.error(t('common.memberProfile.pages.copyFailed'))
  }
}

onMounted(loadData)
</script>

<template>
  <div class="mx-auto max-w-6xl">
    <div class="mb-6 flex items-center justify-between">
      <PageHeader :title="t('common.memberProfile.pages.title')" />
    </div>

    <PageLoading v-if="loading" />

    <div v-else-if="loadError" class="py-12 text-center text-surface-500">
      <i class="pi pi-exclamation-triangle mb-2 text-4xl text-orange-500" />
      <p>{{ $t('common.memberProfile.unavailable') }}</p>
    </div>

    <!-- ページ一覧 -->
    <template v-else-if="view === 'pages'">
      <div v-if="isAdmin" class="mb-4 flex justify-end">
        <Button
          :label="t('common.memberProfile.pages.newPage')"
          icon="pi pi-plus"
          @click="openCreatePage"
        />
      </div>

      <div v-if="pages.length === 0" class="py-12 text-center text-surface-500">
        <i class="pi pi-book mb-2 text-4xl" />
        <p>{{ t('common.memberProfile.pages.empty') }}</p>
      </div>

      <div v-else class="grid gap-4 md:grid-cols-2">
        <Card v-for="page in pages" :key="page.id" class="w-full">
          <template #content>
            <div class="flex items-start justify-between gap-2">
              <div>
                <div class="flex items-center gap-2">
                  <p class="font-semibold">{{ page.title }}</p>
                  <Tag
                    :value="page.status === 'PUBLISHED'
                      ? t('common.memberProfile.pages.statusPublished')
                      : t('common.memberProfile.pages.statusDraft')"
                    :severity="page.status === 'PUBLISHED' ? 'success' : 'secondary'"
                  />
                </div>
                <p v-if="page.year" class="text-sm text-surface-500">{{ page.year }}</p>
              </div>
            </div>
            <div class="mt-4 flex flex-wrap items-center gap-2">
              <Button
                :label="t('common.memberProfile.pages.open')"
                icon="pi pi-users"
                size="small"
                @click="openPage(page)"
              />
              <template v-if="isAdmin">
                <Button
                  :label="page.status === 'PUBLISHED'
                    ? t('common.memberProfile.pages.unpublish')
                    : t('common.memberProfile.pages.publish')"
                  icon="pi pi-eye"
                  size="small"
                  severity="secondary"
                  outlined
                  @click="togglePublish(page)"
                />
                <Button
                  icon="pi pi-trash"
                  size="small"
                  severity="danger"
                  text
                  :aria-label="t('common.memberProfile.pages.delete')"
                  @click="confirmDeletePage(page)"
                />
              </template>
            </div>
          </template>
        </Card>
      </div>
    </template>

    <!-- メンバー一覧（ページ選択後） -->
    <template v-else-if="view === 'members' && selectedPage">
      <div class="mb-4 flex flex-wrap items-center justify-between gap-2">
        <div class="flex items-center gap-3">
          <Button
            icon="pi pi-arrow-left"
            :label="t('common.memberProfile.members.back')"
            text
            severity="secondary"
            @click="backToPages"
          />
          <h2 class="text-lg font-semibold">{{ selectedPage.title }}</h2>
        </div>
        <!-- 管理者専用操作: BE 側も ADMIN 以上でしか受け付けないため、ここでの表示制御は
             UI 上の親切さに過ぎず、正の認可は常にサーバー側で行われる -->
        <div v-if="isAdmin" class="flex flex-wrap gap-2">
          <Button
            :label="t('common.memberProfile.pages.copyFromPrevious')"
            icon="pi pi-copy"
            size="small"
            severity="secondary"
            outlined
            :disabled="copyCandidatePages.length === 0"
            @click="openCopyDialog"
          />
          <Button
            :label="t('common.memberProfile.members.bulkRegister')"
            icon="pi pi-file-import"
            size="small"
            severity="secondary"
            outlined
            @click="showBulkDialog = true"
          />
        </div>
      </div>

      <PageLoading v-if="membersLoading" />
      <MemberProfileList
        v-else
        :profiles="members"
        :editable="isAdmin"
        @create="openCreateMember"
        @edit="openEditMember"
        @delete="handleDeleteMember"
      />
    </template>

    <!-- ページ作成ダイアログ -->
    <Dialog
      v-model:visible="showPageDialog"
      :header="t('common.memberProfile.pages.newPage')"
      :modal="true"
      class="w-full max-w-md"
    >
      <div class="space-y-4">
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('common.memberProfile.pages.titleLabel') }} *</label>
          <InputText v-model="pageForm.title" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('common.memberProfile.pages.slugLabel') }} *</label>
          <InputText v-model="pageForm.slug" class="w-full" placeholder="members-2026" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('common.memberProfile.pages.yearLabel') }}</label>
          <InputNumber v-model="pageForm.year" class="w-full" :use-grouping="false" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('common.memberProfile.pages.visibilityLabel') }}</label>
          <Select
            v-model="pageForm.visibility"
            :options="[
              { label: t('common.memberProfile.pages.visibilityMembersOnly'), value: 'MEMBERS_ONLY' },
              { label: t('common.memberProfile.pages.visibilityPublic'), value: 'PUBLIC' },
            ]"
            option-label="label"
            option-value="value"
            class="w-full"
          />
        </div>
      </div>
      <template #footer>
        <Button :label="t('button.cancel')" severity="secondary" @click="showPageDialog = false" />
        <Button
          :label="t('button.save')"
          icon="pi pi-check"
          :disabled="!pageForm.title || !pageForm.slug"
          @click="savePage"
        />
      </template>
    </Dialog>

    <!-- ページ削除確認ダイアログ -->
    <Dialog
      v-model:visible="showDeletePageDialog"
      :header="t('common.memberProfile.pages.delete')"
      :modal="true"
      class="w-full max-w-sm"
    >
      <p>{{ t('common.memberProfile.pages.deleteConfirm') }}</p>
      <template #footer>
        <Button :label="t('button.cancel')" severity="secondary" @click="showDeletePageDialog = false" />
        <Button :label="t('button.delete')" severity="danger" icon="pi pi-trash" @click="executeDeletePage" />
      </template>
    </Dialog>

    <!-- メンバー作成・編集ダイアログ -->
    <Dialog
      v-model:visible="showMemberDialog"
      :header="editingMember ? t('common.memberProfile.members.edit') : t('common.memberProfile.members.add')"
      :modal="true"
      class="w-full max-w-md"
    >
      <div class="space-y-4">
        <div>
          <label class="mb-1 block text-sm font-medium">名前 *</label>
          <InputText v-model="memberForm.displayName" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">背番号・番号</label>
          <InputText v-model="memberForm.memberNumber" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">役職・ポジション</label>
          <InputText v-model="memberForm.position" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">自己紹介</label>
          <Textarea v-model="memberForm.bio" class="w-full" rows="3" />
        </div>
      </div>
      <template #footer>
        <Button :label="t('button.cancel')" severity="secondary" @click="showMemberDialog = false" />
        <Button
          :label="editingMember ? t('button.save') : t('common.memberProfile.members.add')"
          icon="pi pi-check"
          :disabled="!memberForm.displayName"
          @click="saveMember"
        />
      </template>
    </Dialog>

    <!-- 一括登録ダイアログ -->
    <Dialog
      v-model:visible="showBulkDialog"
      :header="t('common.memberProfile.members.bulkRegister')"
      :modal="true"
      class="w-full max-w-lg"
    >
      <p class="mb-2 text-sm text-surface-500">{{ t('common.memberProfile.members.bulkRegisterHelp') }}</p>
      <Textarea v-model="bulkText" class="w-full" rows="8" placeholder="田中太郎,10&#10;鈴木花子,11" />
      <template #footer>
        <Button :label="t('button.cancel')" severity="secondary" @click="showBulkDialog = false" />
        <Button
          :label="t('common.memberProfile.members.bulkRegister')"
          icon="pi pi-check"
          :disabled="!bulkText.trim()"
          @click="executeBulkRegister"
        />
      </template>
    </Dialog>

    <!-- 前年度からコピーダイアログ -->
    <Dialog
      v-model:visible="showCopyDialog"
      :header="t('common.memberProfile.pages.copyFromPrevious')"
      :modal="true"
      class="w-full max-w-sm"
    >
      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('common.memberProfile.pages.copySourceLabel') }}</label>
        <Select
          v-model="copySourcePageId"
          :options="copyCandidatePages"
          option-label="title"
          option-value="id"
          class="w-full"
        />
      </div>
      <template #footer>
        <Button :label="t('button.cancel')" severity="secondary" @click="showCopyDialog = false" />
        <Button
          :label="t('common.memberProfile.pages.copyConfirm')"
          icon="pi pi-copy"
          :disabled="copySourcePageId == null"
          @click="executeCopy"
        />
      </template>
    </Dialog>
  </div>
</template>
