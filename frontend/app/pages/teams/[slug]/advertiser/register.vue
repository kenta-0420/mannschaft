<script setup lang="ts">
// F09.17 チームスコープ 広告主登録ページ。
//
// 組織版 (pages/organizations/[id]/advertiser/register.vue) をベースに
// scope を TEAM に変更したページ。
// POST /api/v1/teams/{teamSlug}/advertiser/register を呼び出す。

import type { BillingMethod } from '~/types/advertiser'

definePageMeta({ layout: 'team', middleware: 'auth' })
const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const teamSlug = String(route.params.slug)
const advertiserApi = useAdvertiserApi()
const toast = useNotification()

const form = ref({
  companyName: '',
  contactEmail: '',
  billingMethod: 'STRIPE' as BillingMethod,
})
const submitting = ref(false)

const billingOptions = [
  { label: t('advertising.teams_page.register.billing_stripe'), value: 'STRIPE' },
  { label: t('advertising.teams_page.register.billing_invoice'), value: 'INVOICE' },
]

async function submit() {
  if (!form.value.companyName || !form.value.contactEmail) return
  submitting.value = true
  try {
    await advertiserApi.registerTeam(teamSlug, form.value)
    toast.success(t('advertising.teams_page.register.success_message'))
    router.push(`/teams/${teamSlug}/advertiser`)
  }
  catch { toast.error(t('advertising.teams_page.register.error_message')) }
  finally { submitting.value = false }
}
</script>

<template>
  <div class="mx-auto max-w-lg">
    <PageHeader :title="t('advertising.teams_page.register.title')" />
    <SectionCard>
      <div class="mb-4">
        <label class="mb-1 block text-sm font-medium">{{ t('advertising.teams_page.register.company_name_label') }}</label>
        <InputText v-model="form.companyName" class="w-full" :placeholder="t('advertising.teams_page.register.company_name_placeholder')" />
      </div>
      <div class="mb-4">
        <label class="mb-1 block text-sm font-medium">{{ t('advertising.teams_page.register.contact_email_label') }}</label>
        <InputText v-model="form.contactEmail" type="email" class="w-full" placeholder="ads@example.com" />
      </div>
      <div class="mb-6">
        <label class="mb-1 block text-sm font-medium">{{ t('advertising.teams_page.register.billing_method_label') }}</label>
        <Select v-model="form.billingMethod" :options="billingOptions" option-label="label" option-value="value" class="w-full" />
      </div>
      <Button :label="t('advertising.teams_page.register.submit')" icon="pi pi-check" :loading="submitting" class="w-full" @click="submit" />
    </SectionCard>
  </div>
</template>
