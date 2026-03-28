<script setup lang="ts">
import type { BillingMethod } from '~/types/advertiser'

definePageMeta({ middleware: 'auth' })
const route = useRoute()
const router = useRouter()
const orgId = Number(route.params.id)
const advertiserApi = useAdvertiserApi()
const { success, error: showError } = useNotification()

const form = ref({
  companyName: '',
  contactEmail: '',
  billingMethod: 'STRIPE' as BillingMethod,
})
const submitting = ref(false)

const billingOptions = [
  { label: 'クレジットカード（Stripe）', value: 'STRIPE' },
  { label: '請求書払い', value: 'INVOICE' },
]

async function submit() {
  if (!form.value.companyName || !form.value.contactEmail) return
  submitting.value = true
  try {
    await advertiserApi.register(orgId, form.value)
    success('広告主登録を申請しました。審査後に利用開始できます。')
    router.push(`/organizations/${orgId}/advertiser`)
  }
  catch { showError('登録に失敗しました') }
  finally { submitting.value = false }
}
</script>

<template>
  <div class="mx-auto max-w-lg">
    <h1 class="mb-6 text-2xl font-bold">広告主登録</h1>
    <div class="rounded-xl border border-surface-200 bg-surface-0 p-6 dark:border-surface-700 dark:bg-surface-800">
      <div class="mb-4">
        <label class="mb-1 block text-sm font-medium">会社名 / 広告主名</label>
        <InputText v-model="form.companyName" class="w-full" placeholder="株式会社サンプル" />
      </div>
      <div class="mb-4">
        <label class="mb-1 block text-sm font-medium">連絡先メールアドレス</label>
        <InputText v-model="form.contactEmail" type="email" class="w-full" placeholder="ads@example.com" />
      </div>
      <div class="mb-6">
        <label class="mb-1 block text-sm font-medium">決済方法</label>
        <Select v-model="form.billingMethod" :options="billingOptions" optionLabel="label" optionValue="value" class="w-full" />
      </div>
      <Button label="登録申請" icon="pi pi-check" :loading="submitting" class="w-full" @click="submit" />
    </div>
  </div>
</template>
