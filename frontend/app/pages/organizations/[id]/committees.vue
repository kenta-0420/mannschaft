<script setup lang="ts">
import type { CommitteeSummary } from '~/types/committee'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const orgId = Number(route.params.id)
const committeeApi = useCommitteeApi()
const notification = useNotification()
const { handleApiError } = useErrorHandler()
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('organization', orgId)

const committees = ref<CommitteeSummary[]>([])
const loading = ref(true)
const showCreateDialog = ref(false)
const creating = ref(false)

// 作成フォームの値
const createForm = ref({
  name: '',
  description: '',
  purposeTag: '',
  visibilityToOrg: 'NAME_ONLY' as 'HIDDEN' | 'NAME_ONLY' | 'NAME_AND_PURPOSE',
})

async function loadCommittees() {
  loading.value = true
  try {
    const res = await committeeApi.listCommittees(orgId)
    committees.value = res.data
  } catch {
    committees.value = []
  } finally {
    loading.value = false
  }
}

async function onCreateCommittee() {
  if (!createForm.value.name.trim()) return
  creating.value = true
  try {
    await committeeApi.createCommittee(orgId, {
      name: createForm.value.name,
      description: createForm.value.description || null,
      purposeTag: createForm.value.purposeTag || null,
      visibilityToOrg: createForm.value.visibilityToOrg,
    })
    notification.success(t('committee.label'), t('committee.list.create'))
    showCreateDialog.value = false
    createForm.value = { name: '', description: '', purposeTag: '', visibilityToOrg: 'NAME_ONLY' }
    await loadCommittees()
  } catch (err) {
    handleApiError(err, 'createCommittee')
  } finally {
    creating.value = false
  }
}

function statusSeverity(status: CommitteeSummary['status']): string {
  switch (status) {
    case 'ACTIVE': return 'success'
    case 'CLOSED': return 'warning'
    case 'ARCHIVED': return 'danger'
    case 'CANCELLED_DRAFT': return 'danger'
    default: return 'secondary'
  }
}

function goToCommittee(id: number) {
  navigateTo(`/committees/${id}`)
}

onMounted(async () => {
  await loadPermissions()
  await loadCommittees()
})
</script>

<template>
  <PageLoading v-if="loading" />
  <div v-else>
    <div class="mb-4 flex items-center justify-between">
      <div class="flex items-center gap-3">
        <BackButton />
        <PageHeader :title="$t('committee.list.title')" />
      </div>
      <Button
        v-if="isAdminOrDeputy"
        :label="$t('committee.list.create')"
        icon="pi pi-plus"
        @click="showCreateDialog = true"
      />
    </div>

    <SectionCard v-if="committees.length === 0">
      <DashboardEmptyState icon="pi pi-users" :message="$t('committee.list.empty')" />
    </SectionCard>

    <SectionCard v-else>
      <div class="divide-y">
        <div
          v-for="committee in committees"
          :key="committee.id"
          class="flex cursor-pointer items-center justify-between py-3 px-1 hover:bg-gray-50 transition-colors rounded"
          @click="goToCommittee(committee.id)"
        >
          <div class="flex-1 min-w-0">
            <div class="flex items-center gap-2">
              <span class="font-medium text-gray-900 truncate">{{ committee.name }}</span>
              <Tag
                :value="$t(`committee.status.${committee.status}`)"
                :severity="statusSeverity(committee.status)"
                class="text-xs"
              />
            </div>
            <p v-if="committee.description" class="mt-1 text-sm text-gray-500 truncate">
              {{ committee.description }}
            </p>
          </div>
          <div class="ml-4 flex items-center gap-3 text-sm text-gray-500 shrink-0">
            <span>
              <i class="pi pi-users mr-1" />{{ committee.memberCount }}
            </span>
            <i class="pi pi-chevron-right text-gray-400" />
          </div>
        </div>
      </div>
    </SectionCard>

    <!-- 委員会作成ダイアログ -->
    <Dialog
      v-model:visible="showCreateDialog"
      :header="$t('committee.create_dialog.title')"
      modal
      :style="{ width: '480px' }"
    >
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium text-gray-700">
            {{ $t('committee.field.name') }}<span class="ml-1 text-red-500">*</span>
          </label>
          <InputText v-model="createForm.name" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium text-gray-700">
            {{ $t('committee.field.description') }}
          </label>
          <Textarea v-model="createForm.description" class="w-full" rows="3" auto-resize />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium text-gray-700">
            {{ $t('committee.field.purpose_tag') }}
          </label>
          <InputText v-model="createForm.purposeTag" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium text-gray-700">
            {{ $t('committee.field.visibility_to_org') }}
          </label>
          <Select
            v-model="createForm.visibilityToOrg"
            :options="[
              { label: $t('committee.visibility.HIDDEN'), value: 'HIDDEN' },
              { label: $t('committee.visibility.NAME_ONLY'), value: 'NAME_ONLY' },
              { label: $t('committee.visibility.NAME_AND_PURPOSE'), value: 'NAME_AND_PURPOSE' },
            ]"
            option-label="label"
            option-value="value"
            class="w-full"
          />
        </div>
      </div>
      <template #footer>
        <Button
          :label="$t('button.cancel')"
          severity="secondary"
          @click="showCreateDialog = false"
        />
        <Button
          :label="$t('committee.list.create')"
          icon="pi pi-check"
          :loading="creating"
          :disabled="!createForm.name.trim()"
          @click="onCreateCommittee"
        />
      </template>
    </Dialog>
  </div>
</template>
