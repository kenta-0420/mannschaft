<script setup lang="ts">

definePageMeta({
  layout: 'auth',
})

const { t } = useI18n()
const route = useRoute()
const invitationApi = useCommitteeInvitationApi()
const notification = useNotification()

const token = computed(() => String(route.params.token))

const accepting = ref(false)
const declining = ref(false)
const done = ref(false)
const doneMessage = ref('')
const error = ref(false)
const errorMessage = ref('')

async function onAccept() {
  accepting.value = true
  try {
    await invitationApi.acceptByToken(token.value)
    notification.success(t('committee.invitation.accepted'))
    doneMessage.value = t('committee.invitation.accepted')
    done.value = true
    // committeeId は CommitteeMember に含まれないため、一般的なダッシュボードに遷移
    // バックエンドの CommitteeMember には committeeId がないので dashboard へ
    await navigateTo('/dashboard')
  } catch (err) {
    const apiError = err as { data?: { error?: { code?: string } } }
    if (apiError?.data?.error?.code === 'INVITATION_EXPIRED') {
      errorMessage.value = t('committee.invitation.expired')
    } else {
      errorMessage.value = t('committee.invitation.invalid')
    }
    error.value = true
  } finally {
    accepting.value = false
  }
}

async function onDecline() {
  declining.value = true
  try {
    await invitationApi.declineByToken(token.value)
    doneMessage.value = t('committee.invitation.declined')
    done.value = true
  } catch (err) {
    const apiError = err as { data?: { error?: { code?: string } } }
    if (apiError?.data?.error?.code === 'INVITATION_EXPIRED') {
      errorMessage.value = t('committee.invitation.expired')
    } else {
      errorMessage.value = t('committee.invitation.invalid')
    }
    error.value = true
  } finally {
    declining.value = false
  }
}
</script>

<template>
  <div class="flex min-h-screen items-center justify-center p-4">
    <!-- エラー状態 -->
    <div v-if="error" class="w-full max-w-md rounded-lg border p-8 text-center">
      <i class="pi pi-exclamation-triangle mb-4 text-5xl text-yellow-500" />
      <h2 class="mb-2 text-xl font-bold">{{ errorMessage }}</h2>
      <Button :label="$t('button.back')" icon="pi pi-home" @click="navigateTo('/')" />
    </div>

    <!-- 完了状態 -->
    <div v-else-if="done" class="w-full max-w-md rounded-lg border p-8 text-center">
      <i class="pi pi-check-circle mb-4 text-5xl text-green-500" />
      <h2 class="mb-2 text-xl font-bold">{{ doneMessage }}</h2>
      <Button
        class="mt-4"
        :label="$t('button.back')"
        icon="pi pi-home"
        @click="navigateTo('/dashboard')"
      />
    </div>

    <!-- 受諾/辞退フォーム -->
    <div v-else class="w-full max-w-md rounded-lg border p-8">
      <div class="text-center">
        <i class="pi pi-users mb-4 text-5xl text-blue-500" />
        <h2 class="mb-2 text-xl font-bold">{{ $t('committee.label') }}</h2>
        <p class="mb-6 text-gray-600">
          {{ $t('committee.invitation.accept') + ' / ' + $t('committee.invitation.decline') }}
        </p>

        <div class="flex flex-col gap-3">
          <Button
            :label="$t('committee.invitation.accept')"
            icon="pi pi-check"
            class="w-full"
            :loading="accepting"
            :disabled="declining"
            @click="onAccept"
          />
          <Button
            :label="$t('committee.invitation.decline')"
            icon="pi pi-times"
            severity="secondary"
            class="w-full"
            :loading="declining"
            :disabled="accepting"
            @click="onDecline"
          />
        </div>
      </div>
    </div>
  </div>
</template>
