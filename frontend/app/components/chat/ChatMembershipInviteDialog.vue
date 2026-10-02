<script setup lang="ts">
import type { ChatInviteScopeType } from '~/types/chat'
import {
  useChatMembershipInvite,
  type InvitableScope,
  type MembershipInviteResponse,
} from '~/composables/chat/useChatMembershipInvite'

/**
 * チーム/組織への承諾型招待モーダル（F04.12）。
 *
 * DM の相手を、自分が ADMIN/DEPUTY_ADMIN として管理するチーム/組織へ招待する。
 * 招待可能スコープは BE（`GET /me/invitable-scopes`）を真実源として取得する（設計書 B-6）。
 * 0 件のときはエラーにせず「招待できるチーム/組織がありません」を表示する。
 */
const visible = defineModel<boolean>('visible', { default: false })

const props = defineProps<{
  channelId: number
}>()

const emit = defineEmits<{
  invited: [response: MembershipInviteResponse]
}>()

const { getInvitableScopes, issueMembershipInvite } = useChatMembershipInvite()
const notification = useNotification()
const { t } = useI18n()

/** 選択可能なスコープの正規化形（teams/organizations を種別付きで統合）。 */
interface SelectableScope {
  scopeType: ChatInviteScopeType
  scopeId: number
  name: string
}

const loading = ref(false)
const submitting = ref(false)
const scopes = ref<SelectableScope[]>([])
const selected = ref<SelectableScope | null>(null)

async function loadScopes() {
  loading.value = true
  selected.value = null
  try {
    const res = await getInvitableScopes()
    const teams: SelectableScope[] = (res.teams ?? [])
      .filter(
        (s: InvitableScope): s is InvitableScope & { scopeId: number; name: string } =>
          s.scopeId != null && s.name != null,
      )
      .map((s) => ({ scopeType: 'TEAM' as const, scopeId: s.scopeId, name: s.name }))
    const orgs: SelectableScope[] = (res.organizations ?? [])
      .filter(
        (s: InvitableScope): s is InvitableScope & { scopeId: number; name: string } =>
          s.scopeId != null && s.name != null,
      )
      .map((s) => ({ scopeType: 'ORGANIZATION' as const, scopeId: s.scopeId, name: s.name }))
    scopes.value = [...teams, ...orgs]
  } catch {
    scopes.value = []
    notification.error(t('chat.invite.error.loadFailed'))
  } finally {
    loading.value = false
  }
}

function isSelected(s: SelectableScope): boolean {
  return (
    selected.value?.scopeType === s.scopeType && selected.value?.scopeId === s.scopeId
  )
}

function selectScope(s: SelectableScope) {
  selected.value = s
}

async function onSubmit() {
  if (!selected.value || submitting.value) return
  submitting.value = true
  try {
    const response = await issueMembershipInvite(props.channelId, {
      scopeType: selected.value.scopeType,
      scopeId: selected.value.scopeId,
    })
    notification.success(t('chat.invite.sentSuccess'))
    visible.value = false
    emit('invited', response)
  } catch {
    notification.error(t('chat.invite.error.sendFailed'))
  } finally {
    submitting.value = false
  }
}

watch(visible, (v) => {
  if (v) {
    loadScopes()
  }
})
</script>

<template>
  <Dialog
    v-model:visible="visible"
    :header="$t('chat.invite.modal.title')"
    modal
    class="w-full max-w-md"
  >
    <div class="mt-3 flex flex-col gap-4">
      <div v-if="loading" class="flex justify-center py-8">
        <LoadingBounce />
      </div>

      <!-- 0 件: エラーにせず案内表示 -->
      <div
        v-else-if="scopes.length === 0"
        class="py-8 text-center text-sm text-surface-400"
        data-testid="chat-invite-empty"
      >
        {{ $t('chat.invite.modal.empty') }}
      </div>

      <!-- スコープ選択リスト -->
      <div
        v-else
        class="max-h-64 overflow-y-auto rounded-lg border border-surface-300 dark:border-surface-600"
      >
        <button
          v-for="s in scopes"
          :key="`${s.scopeType}-${s.scopeId}`"
          type="button"
          class="flex w-full items-center gap-3 px-3 py-2 text-left transition-colors hover:bg-surface-100 dark:hover:bg-surface-700"
          :class="isSelected(s) ? 'bg-primary/10' : ''"
          data-testid="chat-invite-scope-option"
          @click="selectScope(s)"
        >
          <span
            class="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-surface-100 dark:bg-surface-700"
            aria-hidden="true"
          >
            <i :class="s.scopeType === 'ORGANIZATION' ? 'pi pi-building' : 'pi pi-users'" />
          </span>
          <span class="flex-1 truncate text-sm">{{ s.name }}</span>
          <i v-if="isSelected(s)" class="pi pi-check-circle text-primary" />
        </button>
      </div>
    </div>

    <div class="mt-4 flex justify-end gap-2">
      <Button :label="$t('button.cancel')" text @click="visible = false" />
      <Button
        :label="$t('chat.invite.modal.submit')"
        :loading="submitting"
        :disabled="!selected || scopes.length === 0"
        data-testid="chat-invite-submit"
        @click="onSubmit"
      />
    </div>
  </Dialog>
</template>
