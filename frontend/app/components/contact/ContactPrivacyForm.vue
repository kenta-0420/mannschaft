<script setup lang="ts">
import type { ContactPrivacySettings } from '~/types/contact'

const { t } = useI18n()
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
  { label: 'contact_privacy.dm_option.anyone', value: 'ANYONE' },
  { label: 'contact_privacy.dm_option.team_members_only', value: 'TEAM_MEMBERS_ONLY' },
  { label: 'contact_privacy.dm_option.contacts_only', value: 'CONTACTS_ONLY' },
]

const visibilityOptions = [
  { label: 'contact_privacy.online_option.nobody', value: 'NOBODY' },
  { label: 'contact_privacy.online_option.contacts_only', value: 'CONTACTS_ONLY' },
  { label: 'contact_privacy.online_option.everyone', value: 'EVERYONE' },
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
    notification.success(t('contact_privacy.saved'))
  } catch (e) {
    captureQuiet(e, { context: 'ContactPrivacyForm: 設定保存' })
    notification.error(t('contact_privacy.save_failed'))
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
        <SectionCard>
          <div class="flex items-center justify-between">
            <div>
              <div class="font-medium">{{ t('contact_privacy.handle_searchable.label') }}</div>
              <div class="mt-0.5 text-sm text-gray-500">
                {{ t('contact_privacy.handle_searchable.help') }}
              </div>
            </div>
            <ToggleSwitch v-model="settings.handleSearchable" />
          </div>
        </SectionCard>

        <SectionCard>
          <div class="flex items-center justify-between">
            <div>
              <div class="font-medium">{{ t('contact_privacy.contact_approval.label') }}</div>
              <div class="mt-0.5 text-sm text-gray-500">
                {{ t('contact_privacy.contact_approval.help') }}
              </div>
            </div>
            <ToggleSwitch v-model="settings.contactApprovalRequired" />
          </div>
        </SectionCard>

        <SectionCard>
          <div class="mb-3 font-medium">{{ t('contact_privacy.dm_section.label') }}</div>
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
              <label :for="`dm-${opt.value}`" class="cursor-pointer text-sm">{{ t(opt.label) }}</label>
            </div>
          </div>
        </SectionCard>

        <SectionCard>
          <div class="mb-1 font-medium">{{ t('contact_privacy.online_section.label') }}</div>
          <div class="mb-3 text-sm text-gray-500">
            {{ t('contact_privacy.online_section.help') }}
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
                t(opt.label)
              }}</label>
            </div>
          </div>
        </SectionCard>
      </div>

      <div class="flex justify-end">
        <Button
          :label="t('contact_privacy.save')"
          icon="pi pi-save"
          :loading="saving"
          @click="save"
        />
      </div>
    </template>
  </div>
</template>
