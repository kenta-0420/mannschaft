<script setup lang="ts">
import type { ContactPrivacySettings } from '~/types/contact'

const contactApi = useContactApi()
const { captureQuiet } = useErrorReport()
const notification = useNotification()

const settings = ref<ContactPrivacySettings>({
  handleSearchable: true,
  contactApprovalRequired: true,
  dmReceiveFrom: 'CONTACTS_ONLY',
  onlineVisibility: 'NOBODY',
})
const loading = ref(false)
const saving = ref(false)

const dmOptions = [
  { label: '誰でも', value: 'ANYONE' },
  { label: 'チームメンバーのみ', value: 'TEAM_MEMBERS_ONLY' },
  { label: '連絡先のみ', value: 'CONTACTS_ONLY' },
]

const visibilityOptions = [
  { label: '誰にも見せない', value: 'NOBODY' },
  { label: '連絡先のみ', value: 'CONTACTS_ONLY' },
  { label: '全員', value: 'EVERYONE' },
]

async function fetchSettings() {
  loading.value = true
  try {
    const result = await contactApi.getPrivacySettings()
    settings.value = result.data
  } catch (e) {
    captureQuiet(e, { context: 'ContactPrivacyForm: 設定取得' })
  } finally {
    loading.value = false
  }
}

async function save() {
  saving.value = true
  try {
    const result = await contactApi.updatePrivacySettings(settings.value)
    settings.value = result.data
    notification.success('プライバシー設定を保存しました')
  } catch (e) {
    captureQuiet(e, { context: 'ContactPrivacyForm: 設定保存' })
    notification.error('保存に失敗しました')
  } finally {
    saving.value = false
  }
}

onMounted(fetchSettings)
</script>

<template>
  <div class="flex flex-col gap-6">
    <PageLoading v-if="loading" />

    <template v-else>
      <div class="flex flex-col gap-4">
        <div class="flex items-center justify-between rounded-lg border border-surface-300 p-4">
          <div>
            <div class="font-medium">@ハンドルで検索を許可</div>
            <div class="mt-0.5 text-sm text-gray-500">
              自分の@ハンドルを知っている人が検索できます
            </div>
          </div>
          <ToggleSwitch v-model="settings.handleSearchable" />
        </div>

        <div class="flex items-center justify-between rounded-lg border border-surface-300 p-4">
          <div>
            <div class="font-medium">連絡先追加に承認が必要</div>
            <div class="mt-0.5 text-sm text-gray-500">
              OFFにすると申請なしで即時追加されます（推奨: ON）
            </div>
          </div>
          <ToggleSwitch v-model="settings.contactApprovalRequired" />
        </div>

        <div class="rounded-lg border border-surface-300 p-4">
          <div class="mb-3 font-medium">DMを受信できる相手</div>
          <div class="flex flex-col gap-2">
            <div
              v-for="opt in dmOptions"
              :key="opt.value"
              class="flex cursor-pointer items-center gap-2"
              @click="settings.dmReceiveFrom = opt.value as ContactPrivacySettings['dmReceiveFrom']"
            >
              <RadioButton
                v-model="settings.dmReceiveFrom"
                :value="opt.value"
                :input-id="`dm-${opt.value}`"
              />
              <label :for="`dm-${opt.value}`" class="cursor-pointer text-sm">{{ opt.label }}</label>
            </div>
          </div>
        </div>

        <div class="rounded-lg border border-surface-300 p-4">
          <div class="mb-1 font-medium">オンライン状態の公開範囲</div>
          <div class="mb-3 text-sm text-gray-500">
            最終オンライン時刻や現在のオンライン状態を誰に見せるかを設定します
          </div>
          <div class="flex flex-col gap-2">
            <div
              v-for="opt in visibilityOptions"
              :key="opt.value"
              class="flex cursor-pointer items-center gap-2"
              @click="settings.onlineVisibility = opt.value as ContactPrivacySettings['onlineVisibility']"
            >
              <RadioButton
                v-model="settings.onlineVisibility"
                :value="opt.value"
                :input-id="`vis-${opt.value}`"
              />
              <label :for="`vis-${opt.value}`" class="cursor-pointer text-sm">{{
                opt.label
              }}</label>
            </div>
          </div>
        </div>
      </div>

      <Button label="保存" icon="pi pi-save" class="self-end" :loading="saving" @click="save" />
    </template>
  </div>
</template>
