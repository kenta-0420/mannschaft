<script setup lang="ts">
import type { CareCategory, CareRelationship, InviteRecipientRequest } from '~/types/careLink'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const api = useCareLinkApi()
const notification = useNotification()
const router = useRouter()

const form = ref<InviteRecipientRequest>({
  careRecipientUserId: 0,
  careCategory: 'GENERAL_FAMILY',
  relationship: 'OTHER',
})

const submitting = ref(false)

const careCategoryOptions = computed(() => [
  { label: t('care.category.MINOR'), value: 'MINOR' as CareCategory },
  { label: t('care.category.ELDERLY'), value: 'ELDERLY' as CareCategory },
  { label: t('care.category.DISABILITY_SUPPORT'), value: 'DISABILITY_SUPPORT' as CareCategory },
  { label: t('care.category.GENERAL_FAMILY'), value: 'GENERAL_FAMILY' as CareCategory },
])

const relationshipOptions = computed(() => [
  { label: t('care.relationship.PARENT'), value: 'PARENT' as CareRelationship },
  { label: t('care.relationship.CHILD'), value: 'CHILD' as CareRelationship },
  { label: t('care.relationship.SPOUSE'), value: 'SPOUSE' as CareRelationship },
  { label: t('care.relationship.GRANDPARENT'), value: 'GRANDPARENT' as CareRelationship },
  { label: t('care.relationship.GRANDCHILD'), value: 'GRANDCHILD' as CareRelationship },
  { label: t('care.relationship.SIBLING'), value: 'SIBLING' as CareRelationship },
  { label: t('care.relationship.LEGAL_GUARDIAN'), value: 'LEGAL_GUARDIAN' as CareRelationship },
  { label: t('care.relationship.CARETAKER'), value: 'CARETAKER' as CareRelationship },
  { label: t('care.relationship.OTHER'), value: 'OTHER' as CareRelationship },
])

async function submit() {
  if (!form.value.careRecipientUserId || form.value.careRecipientUserId <= 0) {
    notification.error(t('care.validation.userIdRequired'))
    return
  }
  submitting.value = true
  try {
    await api.inviteRecipient(form.value)
    notification.success(t('care.message.inviteRecipientSuccess'))
    router.push('/me/care-links')
  } catch {
    notification.error(t('care.message.inviteError'))
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="container mx-auto max-w-lg p-4">
    <PageHeader :title="$t('care.page.inviteRecipient')" class="mb-4" />

    <div class="rounded-lg border border-surface-200 p-6 dark:border-surface-700">
      <div class="flex flex-col gap-4">
        <!-- ユーザーID -->
        <div class="flex flex-col gap-1">
          <label class="text-sm font-medium">
            {{ $t('care.label.userId') }}
            <span class="ml-1 text-red-500">*</span>
          </label>
          <InputNumber
            v-model="form.careRecipientUserId"
            :min="1"
            :use-grouping="false"
            class="w-full"
          />
        </div>

        <!-- ケアカテゴリ -->
        <div class="flex flex-col gap-1">
          <label class="text-sm font-medium">
            {{ $t('care.label.careCategory') }}
            <span class="ml-1 text-red-500">*</span>
          </label>
          <Select
            v-model="form.careCategory"
            :options="careCategoryOptions"
            option-label="label"
            option-value="value"
            class="w-full"
          />
        </div>

        <!-- 続柄 -->
        <div class="flex flex-col gap-1">
          <label class="text-sm font-medium">
            {{ $t('care.label.relationship') }}
            <span class="ml-1 text-red-500">*</span>
          </label>
          <Select
            v-model="form.relationship"
            :options="relationshipOptions"
            option-label="label"
            option-value="value"
            class="w-full"
          />
        </div>
      </div>

      <div class="mt-6 flex gap-2">
        <Button
          :label="$t('common.button.back')"
          severity="secondary"
          @click="router.push('/me/care-links')"
        />
        <Button
          :label="$t('care.button.inviteRecipient')"
          icon="pi pi-send"
          :loading="submitting"
          @click="submit"
        />
      </div>
    </div>
  </div>
</template>
