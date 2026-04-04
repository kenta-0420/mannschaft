<script setup lang="ts">
import type { ContactableMember } from '~/types/contact'

const props = defineProps<{
  visible: boolean
  teamId?: number
  orgId?: number
  title?: string
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  added: []
}>()

const contactApi = useContactApi()
const { captureQuiet } = useErrorReport()
const notification = useNotification()

const members = ref<ContactableMember[]>([])
const loading = ref(false)
const searchQuery = ref('')
const sendingIds = ref<number[]>([])

async function fetchMembers() {
  loading.value = true
  try {
    if (props.teamId) {
      const result = await contactApi.getTeamContactableMembers(props.teamId, {
        q: searchQuery.value || undefined,
      })
      members.value = result.data
    } else if (props.orgId) {
      const result = await contactApi.getOrgContactableMembers(props.orgId, {
        q: searchQuery.value || undefined,
      })
      members.value = result.data
    }
  } catch (e) {
    captureQuiet(e, { context: 'ContactTeamSearchDialog: メンバー取得' })
  } finally {
    loading.value = false
  }
}

async function sendRequest(member: ContactableMember) {
  sendingIds.value.push(member.userId)
  try {
    await contactApi.sendRequest({
      targetUserId: member.userId,
      sourceType: props.teamId ? 'TEAM_SEARCH' : 'ORG_SEARCH',
    })
    member.hasPendingRequest = true
    notification.success('申請を送信しました')
  } catch (e) {
    captureQuiet(e, { context: 'ContactTeamSearchDialog: 申請送信' })
    notification.error('申請に失敗しました')
  } finally {
    sendingIds.value = sendingIds.value.filter((id) => id !== member.userId)
  }
}

let searchTimer: ReturnType<typeof setTimeout> | null = null
watch(searchQuery, () => {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(fetchMembers, 300)
})

watch(
  () => props.visible,
  (val) => {
    if (val) fetchMembers()
  },
)
</script>

<template>
  <Dialog
    :visible="visible"
    :header="title || 'メンバーに連絡先を申請'"
    :style="{ width: '480px' }"
    modal
    @update:visible="emit('update:visible', $event)"
  >
    <div class="flex flex-col gap-4">
      <IconField>
        <InputIcon class="pi pi-search" />
        <InputText v-model="searchQuery" placeholder="名前で検索..." class="w-full" />
      </IconField>

      <PageLoading v-if="loading" />

      <div v-else-if="members.length === 0" class="py-6 text-center text-sm text-gray-400">
        該当するメンバーが見つかりません
      </div>

      <div v-else class="flex max-h-96 flex-col gap-2 overflow-y-auto">
        <div
          v-for="member in members"
          :key="member.userId"
          class="flex items-center gap-3 rounded-lg p-2 hover:bg-surface-50"
        >
          <Avatar
            :image="member.avatarUrl ?? undefined"
            :label="member.avatarUrl ? undefined : member.displayName.charAt(0)"
            shape="circle"
          />
          <div class="min-w-0 flex-1">
            <div class="truncate font-medium">{{ member.displayName }}</div>
            <div v-if="member.contactHandle" class="text-xs text-gray-400">
              @{{ member.contactHandle }}
            </div>
          </div>
          <span v-if="member.isContact" class="text-xs text-gray-400">連絡先済み</span>
          <span v-else-if="member.hasPendingRequest" class="text-xs text-gray-400">申請中</span>
          <Button
            v-else
            label="追加"
            icon="pi pi-user-plus"
            size="small"
            :loading="sendingIds.includes(member.userId)"
            @click="sendRequest(member)"
          />
        </div>
      </div>
    </div>
  </Dialog>
</template>
