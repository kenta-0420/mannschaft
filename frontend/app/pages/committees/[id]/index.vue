<script setup lang="ts">
import type { CommitteeDetail, CommitteeMember, CommitteeInvitation, CommitteeRole } from '~/types/committee'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const committeeId = Number(route.params.id)
const committeeApi = useCommitteeApi()
const invitationApi = useCommitteeInvitationApi()
const notification = useNotification()
const { handleApiError } = useErrorHandler()

const committee = ref<CommitteeDetail | null>(null)
const members = ref<CommitteeMember[]>([])
const invitations = ref<CommitteeInvitation[]>([])
const loading = ref(true)
const transitioning = ref(false)
const showInviteDialog = ref(false)
const inviting = ref(false)

// 招集フォーム
const inviteForm = ref({
  inviteeUserIds: '',
  proposedRole: 'MEMBER' as CommitteeRole,
})

const isChair = computed(() => committee.value?.myRole === 'CHAIR')
const isChairOrVice = computed(() =>
  committee.value?.myRole === 'CHAIR' || committee.value?.myRole === 'VICE_CHAIR',
)

function statusSeverity(status: CommitteeDetail['status']): string {
  switch (status) {
    case 'ACTIVE': return 'success'
    case 'CLOSED': return 'warning'
    case 'ARCHIVED': return 'danger'
    case 'CANCELLED_DRAFT': return 'danger'
    default: return 'secondary'
  }
}

async function loadData() {
  loading.value = true
  try {
    const [committeeRes, membersRes] = await Promise.all([
      committeeApi.getCommittee(committeeId),
      committeeApi.listMembers(committeeId),
    ])
    committee.value = committeeRes.data
    members.value = membersRes.data

    if (isChair.value || isChairOrVice.value) {
      try {
        const invRes = await invitationApi.listInvitations(committeeId)
        invitations.value = invRes.data
      } catch {
        invitations.value = []
      }
    }
  } catch (err) {
    handleApiError(err, 'loadCommittee')
  } finally {
    loading.value = false
  }
}

async function onTransitionStatus(to: string) {
  transitioning.value = true
  try {
    const res = await committeeApi.transitionStatus(committeeId, to)
    committee.value = res.data
    notification.success(t('committee.label'))
  } catch (err) {
    handleApiError(err, 'transitionStatus')
  } finally {
    transitioning.value = false
  }
}

async function onRemoveMember(userId: number, displayName: string) {
  if (!confirm(t('committee.detail.remove_member_confirm', { name: displayName }))) return
  try {
    await committeeApi.removeMember(committeeId, userId)
    notification.success(t('committee.detail.remove_member'))
    await loadData()
  } catch (err) {
    handleApiError(err, 'removeMember')
  }
}

async function onLeave() {
  if (!confirm(t('committee.detail.leave') + 'しますか？')) return
  try {
    await committeeApi.leaveCommittee(committeeId)
    notification.success(t('committee.detail.leave'))
    navigateTo('/dashboard')
  } catch (err) {
    handleApiError(err, 'leaveCommittee')
  }
}

async function onSendInvitations() {
  const userIds = inviteForm.value.inviteeUserIds
    .split(',')
    .map((s) => Number(s.trim()))
    .filter((n) => !isNaN(n) && n > 0)

  if (userIds.length === 0) return
  inviting.value = true
  try {
    await invitationApi.sendInvitations(committeeId, {
      inviteeUserIds: userIds,
      proposedRole: inviteForm.value.proposedRole,
    })
    notification.success(t('committee.invitation.send'))
    showInviteDialog.value = false
    inviteForm.value = { inviteeUserIds: '', proposedRole: 'MEMBER' }
    await loadData()
  } catch (err) {
    handleApiError(err, 'sendInvitations')
  } finally {
    inviting.value = false
  }
}

async function onCancelInvitation(invitationId: number) {
  try {
    await invitationApi.cancelInvitation(invitationId)
    notification.success(t('committee.invitation.cancel'))
    await loadData()
  } catch (err) {
    handleApiError(err, 'cancelInvitation')
  }
}

function formatDate(dateStr: string | null): string {
  if (!dateStr) return '—'
  const d = new Date(dateStr)
  return `${d.getFullYear()}/${String(d.getMonth() + 1).padStart(2, '0')}/${String(d.getDate()).padStart(2, '0')}`
}

onMounted(async () => {
  await loadData()
})
</script>

<template>
  <PageLoading v-if="loading" />
  <div v-else-if="committee">
    <div class="mb-4 flex items-center justify-between">
      <div class="flex items-center gap-3">
        <BackButton />
        <PageHeader :title="committee.name" />
        <Tag
          :value="$t(`committee.status.${committee.status}`)"
          :severity="statusSeverity(committee.status)"
        />
      </div>
      <div class="flex items-center gap-2">
        <!-- ステータス遷移ボタン（CHAIR/VICE_CHAIR） -->
        <template v-if="isChairOrVice">
          <Button
            v-if="committee.status === 'DRAFT'"
            :label="$t('committee.detail.activate')"
            icon="pi pi-play"
            severity="success"
            :loading="transitioning"
            @click="onTransitionStatus('ACTIVE')"
          />
          <Button
            v-if="committee.status === 'ACTIVE'"
            :label="$t('committee.detail.close')"
            icon="pi pi-stop"
            severity="warning"
            :loading="transitioning"
            @click="onTransitionStatus('CLOSED')"
          />
          <Button
            v-if="committee.status === 'CLOSED'"
            :label="$t('committee.detail.archive')"
            icon="pi pi-inbox"
            severity="secondary"
            :loading="transitioning"
            @click="onTransitionStatus('ARCHIVED')"
          />
        </template>
      </div>
    </div>

    <div class="grid grid-cols-1 gap-6 lg:grid-cols-3">
      <!-- 基本情報 -->
      <div class="lg:col-span-2 flex flex-col gap-6">
        <SectionCard>
          <div class="space-y-3">
            <div v-if="committee.description" class="text-gray-700">
              {{ committee.description }}
            </div>
            <div class="grid grid-cols-2 gap-3 text-sm">
              <div>
                <span class="text-gray-500">{{ $t('committee.field.start_date') }}:</span>
                <span class="ml-2">{{ formatDate(committee.startDate) }}</span>
              </div>
              <div>
                <span class="text-gray-500">{{ $t('committee.field.end_date') }}:</span>
                <span class="ml-2">{{ formatDate(committee.endDate) }}</span>
              </div>
              <div v-if="committee.purposeTag">
                <span class="text-gray-500">{{ $t('committee.field.purpose_tag') }}:</span>
                <span class="ml-2">{{ committee.purposeTag }}</span>
              </div>
            </div>
            <div class="pt-2">
              <NuxtLink
                :to="`/committees/${committee.id}/distributions`"
                class="text-sm text-blue-600 hover:underline"
              >
                <i class="pi pi-list mr-1" />{{ $t('committee.detail.distributions') }}
              </NuxtLink>
            </div>
          </div>
        </SectionCard>

        <!-- 招集中一覧（CHAIR/VICE_CHAIR のみ） -->
        <SectionCard v-if="isChairOrVice && invitations.length > 0">
          <template #header>
            <h3 class="font-semibold text-gray-800">
              {{ $t('committee.invitation.send') }}
            </h3>
          </template>
          <div class="divide-y">
            <div
              v-for="inv in invitations"
              :key="inv.id"
              class="flex items-center justify-between py-2"
            >
              <div>
                <span class="font-medium">{{ inv.inviteeDisplayName }}</span>
                <Tag
                  :value="$t(`committee.role.${inv.proposedRole}`)"
                  severity="info"
                  class="ml-2 text-xs"
                />
                <Tag
                  :value="inv.status"
                  severity="secondary"
                  class="ml-1 text-xs"
                />
              </div>
              <Button
                v-if="inv.status === 'PENDING'"
                :label="$t('committee.invitation.cancel')"
                size="small"
                severity="secondary"
                @click="onCancelInvitation(inv.id)"
              />
            </div>
          </div>
        </SectionCard>
      </div>

      <!-- メンバー一覧 -->
      <div>
        <SectionCard>
          <template #header>
            <div class="flex items-center justify-between">
              <h3 class="font-semibold text-gray-800">
                {{ $t('committee.detail.members') }}（{{ members.length }}）
              </h3>
              <Button
                v-if="isChair"
                :label="$t('committee.detail.invite')"
                icon="pi pi-user-plus"
                size="small"
                @click="showInviteDialog = true"
              />
            </div>
          </template>
          <div class="divide-y">
            <div
              v-for="member in members"
              :key="member.userId"
              class="flex items-center justify-between py-2"
            >
              <div class="flex items-center gap-2">
                <Avatar
                  :image="member.avatarUrl ?? undefined"
                  :label="member.avatarUrl ? undefined : member.displayName.charAt(0)"
                  shape="circle"
                  size="small"
                />
                <div>
                  <div class="text-sm font-medium">{{ member.displayName }}</div>
                  <div class="text-xs text-gray-500">{{ $t(`committee.role.${member.role}`) }}</div>
                </div>
              </div>
              <Button
                v-if="isChair && member.role !== 'CHAIR'"
                icon="pi pi-times"
                size="small"
                severity="secondary"
                text
                @click="onRemoveMember(member.userId, member.displayName)"
              />
            </div>
          </div>
          <div v-if="committee.myRole && committee.myRole !== 'CHAIR'" class="mt-4 border-t pt-3">
            <Button
              :label="$t('committee.detail.leave')"
              icon="pi pi-sign-out"
              severity="danger"
              text
              size="small"
              class="w-full"
              @click="onLeave"
            />
          </div>
        </SectionCard>
      </div>
    </div>

    <!-- 招集ダイアログ -->
    <Dialog
      v-model:visible="showInviteDialog"
      :header="$t('committee.detail.invite')"
      modal
      :style="{ width: '440px' }"
    >
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium text-gray-700">
            {{ $t('committee.invitation.invitee') }}（ユーザーID、カンマ区切り）
          </label>
          <InputText
            v-model="inviteForm.inviteeUserIds"
            class="w-full"
            placeholder="1, 2, 3"
          />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium text-gray-700">
            {{ $t('committee.invitation.proposed_role') }}
          </label>
          <Select
            v-model="inviteForm.proposedRole"
            :options="[
              { label: $t('committee.role.VICE_CHAIR'), value: 'VICE_CHAIR' },
              { label: $t('committee.role.SECRETARY'), value: 'SECRETARY' },
              { label: $t('committee.role.MEMBER'), value: 'MEMBER' },
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
          @click="showInviteDialog = false"
        />
        <Button
          :label="$t('committee.invitation.send')"
          icon="pi pi-send"
          :loading="inviting"
          @click="onSendInvitations"
        />
      </template>
    </Dialog>
  </div>
</template>
