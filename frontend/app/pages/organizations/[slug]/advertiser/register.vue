<script setup lang="ts">
import type { BillingMethod } from '~/types/advertiser'

definePageMeta({ layout: 'organization', middleware: 'auth' })
const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const orgSlug = String(route.params.slug)
const advertiserApi = useAdvertiserApi()
const { success, error: showError } = useNotification()

// F08.12 §5.0: 後払い（請求書方式）は廃止済み。決済方式はクレジットカード（Stripe）一本のため
// 選択肢は無く、固定表示にする。
const form = ref({
  companyName: '',
  contactEmail: '',
  billingMethod: 'STRIPE' as BillingMethod,
})
const submitting = ref(false)

async function submit() {
  if (!form.value.companyName || !form.value.contactEmail) return
  submitting.value = true
  try {
    await advertiserApi.register(orgSlug, form.value)
    success(t('advertising.register.success_message'))
    router.push(`/organizations/${orgSlug}/advertiser`)
  }
  catch { showError(t('advertising.register.error_message')) }
  finally { submitting.value = false }
}
</script>

<template>
  <div class="mx-auto max-w-lg">
    <PageHeader :title="t('advertising.register.title')" />
    <SectionCard>
      <div class="mb-4">
        <label class="mb-1 block text-sm font-medium">{{ t('advertising.register.company_name_label') }}</label>
        <InputText v-model="form.companyName" class="w-full" :placeholder="t('advertising.register.company_name_placeholder')" />
      </div>
      <div class="mb-4">
        <label class="mb-1 block text-sm font-medium">{{ t('advertising.register.contact_email_label') }}</label>
        <InputText v-model="form.contactEmail" type="email" class="w-full" placeholder="ads@example.com" />
      </div>
      <div class="mb-6">
        <label class="mb-1 block text-sm font-medium">{{ t('advertising.register.billing_method_label') }}</label>
        <p class="text-sm text-gray-500">{{ t('advertising.register.billing_fixed_notice') }}</p>
      </div>
      <Button :label="t('advertising.register.submit')" icon="pi pi-check" :loading="submitting" class="w-full" @click="submit" />
    </SectionCard>
  </div>
</template>
