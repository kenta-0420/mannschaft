<script setup lang="ts">
import type { ChatChannelResponse } from '~/types/chat'
import type { MemberResponse } from '~/types/member'

const visible = defineModel<boolean>('visible', { default: false })

const props = defineProps<{
  channelId: number
  dmPartnerUserId?: number
  teamId?: number
  organizationId?: number
}>()

const emit = defineEmits<{
  created: [channel: ChatChannelResponse]
}>()

const { inviteToZimmer } = useChatApi()
const teamApi = useTeamApi()
const orgApi = useOrganizationApi()
const authStore = useAuthStore()
const notification = useNotification()

const members = ref<MemberResponse[]>([])
const membersLoading = ref(false)
const selectedMembers = ref<MemberResponse[]>([])
const memberSearch = ref('')
const shareHistory = ref(true)
const submitting = ref(false)

const filteredMembers = computed(() => {
  const me = authStore.currentUser?.id
  const q = memberSearch.value.toLowerCase()
  return members.value
    .filter((m) => m.userId !== me && m.userId !== props.dmPartnerUserId)
    .filter((m) => !q || m.displayName.toLowerCase().includes(q))
})

async function loadMembers() {
  membersLoading.value = true
  try {
    if (props.teamId) {
      const res = await teamApi.getMembers(props.teamId, { size: 200 })
      members.value = res.data
    } else if (props.organizationId) {
      const res = await orgApi.getMembers(props.organizationId, { size: 200 })
      members.value = res.data
    }
  } catch {
    members.value = []
  } finally {
    membersLoading.value = false
  }
}

function toggleMember(m: MemberResponse) {
  const idx = selectedMembers.value.findIndex((s) => s.userId === m.userId)
  if (idx >= 0) {
    selectedMembers.value.splice(idx, 1)
  } else {
    selectedMembers.value.push(m)
  }
}

function isMemberSelected(m: MemberResponse): boolean {
  return selectedMembers.value.some((s) => s.userId === m.userId)
}

async function onSubmit() {
  if (selectedMembers.value.length === 0 || submitting.value) return
  submitting.value = true
  try {
    const res = await inviteToZimmer(props.channelId, {
      userIds: selectedMembers.value.map((m) => m.userId),
      shareHistory: shareHistory.value,
    })
    notification.success('Zimmer(部屋)を作成しました')
    visible.value = false
    emit('created', res.data)
  } catch {
    notification.error('招待に失敗しました')
  } finally {
    submitting.value = false
  }
}

watch(visible, (v) => {
  if (v) {
    selectedMembers.value = []
    memberSearch.value = ''
    shareHistory.value = true
    if (members.value.length === 0) loadMembers()
  }
})
</script>

<template>
  <Dialog v-model:visible="visible" header="Zimmerに招待する" modal class="w-full max-w-md">
    <div class="flex flex-col gap-4 mt-3">
      <!-- メンバー検索 -->
      <InputText v-model="memberSearch" placeholder="名前で検索..." class="w-full" />

      <!-- 選択中 -->
      <div v-if="selectedMembers.length > 0" class="flex flex-wrap gap-1">
        <Tag
          v-for="m in selectedMembers"
          :key="m.userId"
          severity="info"
          class="cursor-pointer"
          @click="toggleMember(m)"
        >
          <template #default>
            {{ m.displayName }}
            <i class="pi pi-times ml-1 text-xs" />
          </template>
        </Tag>
      </div>

      <!-- メンバー一覧 -->
      <div
        class="max-h-52 overflow-y-auto rounded-lg border border-surface-300 dark:border-surface-600"
      >
        <div v-if="membersLoading" class="flex justify-center py-6">
          <ProgressSpinner style="width: 30px; height: 30px" />
        </div>
        <div
          v-else-if="filteredMembers.length === 0"
          class="py-6 text-center text-sm text-surface-400"
        >
          招待できるメンバーがいません
        </div>
        <button
          v-for="m in filteredMembers"
          :key="m.userId"
          class="flex w-full items-center gap-3 px-3 py-2 text-left transition-colors hover:bg-surface-100 dark:hover:bg-surface-700"
          :class="isMemberSelected(m) ? 'bg-primary/10' : ''"
          @click="toggleMember(m)"
        >
          <Avatar
            :image="m.avatarUrl ?? undefined"
            :label="m.avatarUrl ? undefined : m.displayName.charAt(0)"
            shape="circle"
            size="small"
          />
          <span class="flex-1 truncate text-sm">{{ m.displayName }}</span>
          <i v-if="isMemberSelected(m)" class="pi pi-check-circle text-primary" />
        </button>
      </div>

      <!-- 履歴共有の選択 -->
      <div class="rounded-lg border border-surface-300 p-4 dark:border-surface-600">
        <p class="mb-3 text-sm font-medium">既存のチャット内容を共有しますか？</p>
        <div class="flex flex-col gap-2">
          <div class="flex items-start gap-3 cursor-pointer" @click="shareHistory = true">
            <RadioButton v-model="shareHistory" :value="true" input-id="share-yes" />
            <label for="share-yes" class="cursor-pointer">
              <span class="text-sm font-medium">共有する</span>
              <p class="text-xs text-surface-400">
                これまでのメッセージが新しいZimmerに引き継がれます
              </p>
            </label>
          </div>
          <div class="flex items-start gap-3 cursor-pointer" @click="shareHistory = false">
            <RadioButton v-model="shareHistory" :value="false" input-id="share-no" />
            <label for="share-no" class="cursor-pointer">
              <span class="text-sm font-medium">共有しない</span>
              <p class="text-xs text-surface-400">新しいZimmerは空の状態で始まります</p>
            </label>
          </div>
        </div>
      </div>
    </div>

    <div class="mt-4 flex justify-end gap-2">
      <Button label="キャンセル" text @click="visible = false" />
      <Button
        label="Zimmerを作成"
        :loading="submitting"
        :disabled="selectedMembers.length === 0"
        @click="onSubmit"
      />
    </div>
  </Dialog>
</template>
