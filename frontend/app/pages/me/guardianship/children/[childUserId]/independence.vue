<script setup lang="ts">
import type { IndependenceStatusResponse } from '~/types/guardianship'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const { t } = useI18n()
const guardianshipApi = useGuardianshipApi()
const notification = useNotification()
const { formatDate } = useDatetime()

const childUserId = computed(() => Number(route.params.childUserId))

// データ
const status = ref<IndependenceStatusResponse | null>(null)
const loading = ref(false)

// 引き継ぎダイアログ
const showHandoverDialog = ref(false)
const childEmailInput = ref('')
const submitting = ref(false)

async function loadStatus() {
  loading.value = true
  try {
    const res = await guardianshipApi.getIndependenceStatus(childUserId.value)
    status.value = res.data
  } finally {
    loading.value = false
  }
}

async function handleInitiateHandover() {
  submitting.value = true
  try {
    await guardianshipApi.initiateHandover(
      childUserId.value,
      childEmailInput.value.trim() || undefined,
    )
    notification.success(t('proxy.guardianship.independence.handoverSuccess'))
    showHandoverDialog.value = false
    // ステータス再取得
    await loadStatus()
  } finally {
    submitting.value = false
  }
}

onMounted(loadStatus)
</script>

<template>
  <div class="max-w-2xl mx-auto py-8 px-4">
    <!-- パンくず -->
    <div class="mb-4 flex items-center gap-2 text-sm text-surface-500">
      <NuxtLink to="/me/guardianship/switch" class="hover:underline">
        {{ $t('proxy.guardianship.switch.title') }}
      </NuxtLink>
      <i class="pi pi-chevron-right text-xs" />
      <span>{{ $t('proxy.guardianship.independence.title') }}</span>
    </div>

    <h1 class="text-2xl font-bold mb-6">{{ $t('proxy.guardianship.independence.title') }}</h1>

    <!-- ローディング -->
    <div v-if="loading" class="flex justify-center py-12">
      <ProgressSpinner />
    </div>

    <template v-else-if="status">
      <!-- 既に自立段階 -->
      <Message v-if="!status.switchAllowed" severity="info" class="mb-6" :closable="false">
        {{ $t('proxy.guardianship.independence.alreadyIndependent') }}
      </Message>

      <!-- ステータスカード -->
      <div class="rounded-xl border border-surface-200 bg-surface-0 p-6 shadow-sm space-y-4">
        <!-- 現在の段階 -->
        <div class="flex items-center justify-between">
          <span class="text-sm text-surface-500">{{ $t('proxy.guardianship.independence.stage') }}</span>
          <span class="font-semibold">
            {{
              status.stageKey
                ? $t(`proxy.guardianship.stage.${status.stageKey}`, status.stageKey)
                : '—'
            }}
          </span>
        </div>

        <!-- 封印境界日 -->
        <div class="flex items-center justify-between">
          <span class="text-sm text-surface-500">{{ $t('proxy.guardianship.independence.sealDate') }}</span>
          <span class="font-semibold">{{ formatDate(status.sealDate) }}</span>
        </div>

        <!-- パスワード設定状況 -->
        <div class="flex items-center justify-between">
          <span class="text-sm text-surface-500">パスワード状況</span>
          <Tag
            v-if="status.passwordSet"
            :value="$t('proxy.guardianship.independence.passwordSet')"
            severity="success"
          />
          <Tag
            v-else
            :value="$t('proxy.guardianship.independence.passwordNotSet')"
            severity="warn"
          />
        </div>
      </div>

      <!-- 引き継ぎボタン（パスワード未設定かつ切替可能な場合のみ） -->
      <div v-if="!status.passwordSet && status.switchAllowed" class="mt-6">
        <Button
          :label="$t('proxy.guardianship.independence.handover')"
          icon="pi pi-send"
          severity="primary"
          @click="showHandoverDialog = true"
        />
      </div>
    </template>

    <!-- 引き継ぎ確認ダイアログ -->
    <Dialog
      v-model:visible="showHandoverDialog"
      modal
      :header="$t('proxy.guardianship.independence.handover')"
      :style="{ width: '28rem' }"
    >
      <p class="text-surface-700 text-sm leading-relaxed mb-4">
        {{ $t('proxy.guardianship.independence.handoverConfirm') }}
      </p>

      <!-- 任意: 子のメールアドレス入力 -->
      <div class="flex flex-col gap-1">
        <label class="text-xs text-surface-500">
          {{ $t('proxy.guardianship.independence.childEmail') }}
        </label>
        <InputText
          v-model="childEmailInput"
          type="email"
          placeholder="child@example.com"
          class="w-full"
        />
      </div>

      <template #footer>
        <div class="flex justify-end gap-2">
          <Button
            :label="$t('proxy.guardianship.switch.cancelButton')"
            severity="secondary"
            text
            @click="showHandoverDialog = false"
          />
          <Button
            :label="$t('proxy.guardianship.independence.handover')"
            severity="primary"
            :loading="submitting"
            @click="handleInitiateHandover"
          />
        </div>
      </template>
    </Dialog>
  </div>
</template>
