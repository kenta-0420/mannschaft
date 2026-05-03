<script setup lang="ts">
import type { TicketProductResponse, TicketBookResponse } from '~/types/ticket'

definePageMeta({ middleware: 'auth' })
const route = useRoute()
const teamId = Number(route.params.id)

const notification = useNotification()
const { getProducts, getBooks, createProduct } = useTicketApi()

const products = ref<TicketProductResponse[]>([])
const books = ref<TicketBookResponse[]>([])
const loading = ref(false)
const tab = ref<'products' | 'books'>('products')

const showCreateDialog = ref(false)
const saving = ref(false)
const form = ref({
  name: '',
  description: '',
  totalTickets: 10,
  price: 0,
  taxRate: 0.10,
  validityDays: 90,
  isOnlinePurchaseEnabled: false,
})

async function load() {
  loading.value = true
  try {
    const [pRes, bRes] = await Promise.all([getProducts(teamId), getBooks(teamId)])
    products.value = pRes.data
    books.value = bRes.data
  } catch {
    notification.error('回数券情報の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

function getBookStatusClass(s: string): string {
  switch (s) {
    case 'ACTIVE':
      return 'bg-green-100 text-green-700'
    case 'EXHAUSTED':
      return 'bg-surface-100 text-surface-500'
    case 'EXPIRED':
      return 'bg-red-100 text-red-600'
    default:
      return 'bg-surface-100'
  }
}

function openCreateDialog() {
  form.value = { name: '', description: '', totalTickets: 10, price: 0, taxRate: 0.10, validityDays: 90, isOnlinePurchaseEnabled: false }
  showCreateDialog.value = true
}

async function handleCreate() {
  if (!form.value.name.trim() || form.value.price < 0) return
  saving.value = true
  try {
    await createProduct(teamId, {
      name: form.value.name,
      description: form.value.description || undefined,
      totalTickets: form.value.totalTickets,
      price: form.value.price,
      taxRate: form.value.taxRate,
      validityDays: form.value.validityDays,
      isOnlinePurchaseEnabled: form.value.isOnlinePurchaseEnabled,
    })
    notification.success('商品を追加しました')
    showCreateDialog.value = false
    await load()
  } catch {
    notification.error('商品の追加に失敗しました')
  } finally {
    saving.value = false
  }
}

onMounted(() => load())
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <PageHeader title="回数券" />
      <Button label="回数券を追加" icon="pi pi-plus" size="small" @click="openCreateDialog" />
    </div>
    <SelectButton
      v-model="tab"
      :options="[
        { label: '商品', value: 'products' },
        { label: '発行済み', value: 'books' },
      ]"
      option-label="label"
      option-value="value"
      class="mb-4"
    />
    <PageLoading v-if="loading" size="40px" />
    <div v-else-if="tab === 'products'" class="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
      <SectionCard
        v-for="p in products"
        :key="p.id"
        :title="p.name"
      >
        <p class="mt-1 text-xs text-surface-400">{{ p.description }}</p>
        <div class="mt-3 flex items-end justify-between">
          <div>
            <span class="text-lg font-bold">¥{{ p.price.toLocaleString() }}</span
            ><span class="text-xs text-surface-400"> / {{ p.totalTickets }}回</span>
          </div>
          <span class="text-xs text-surface-400">有効{{ p.validityDays }}日</span>
        </div>
      </SectionCard>
    </div>
    <div v-else class="flex flex-col gap-2">
      <SectionCard
        v-for="b in books"
        :key="b.id"
        class="flex items-center gap-4"
      >
        <Avatar :label="b.displayName.charAt(0)" shape="circle" />
        <div class="flex-1">
          <p class="text-sm font-medium">{{ b.displayName }}</p>
          <p class="text-xs text-surface-400">{{ b.productName }}</p>
        </div>
        <div class="text-right">
          <span
            :class="getBookStatusClass(b.status)"
            class="rounded px-2 py-0.5 text-xs font-medium"
            >{{ b.status }}</span
          >
          <p class="mt-1 text-sm font-bold">残 {{ b.remainingTickets }}/{{ b.totalTickets }}</p>
        </div>
      </SectionCard>
    </div>

    <Dialog v-model:visible="showCreateDialog" modal header="商品を追加" :style="{ width: '28rem' }">
      <div class="flex flex-col gap-4 py-2">
        <div class="flex flex-col gap-1">
          <label class="text-sm font-medium">商品名 <span class="text-red-500">*</span></label>
          <InputText v-model="form.name" placeholder="例: 10回コース" class="w-full" />
        </div>
        <div class="flex flex-col gap-1">
          <label class="text-sm font-medium">説明</label>
          <Textarea v-model="form.description" rows="2" class="w-full" />
        </div>
        <div class="grid grid-cols-2 gap-3">
          <div class="flex flex-col gap-1">
            <label class="text-sm font-medium">枚数 <span class="text-red-500">*</span></label>
            <InputNumber v-model="form.totalTickets" :min="1" class="w-full" />
          </div>
          <div class="flex flex-col gap-1">
            <label class="text-sm font-medium">有効日数 <span class="text-red-500">*</span></label>
            <InputNumber v-model="form.validityDays" :min="1" class="w-full" />
          </div>
        </div>
        <div class="grid grid-cols-2 gap-3">
          <div class="flex flex-col gap-1">
            <label class="text-sm font-medium">価格（税抜）<span class="text-red-500">*</span></label>
            <InputNumber v-model="form.price" :min="0" mode="currency" currency="JPY" locale="ja-JP" class="w-full" />
          </div>
          <div class="flex flex-col gap-1">
            <label class="text-sm font-medium">税率</label>
            <Select
              v-model="form.taxRate"
              :options="[{ label: '10%', value: 0.10 }, { label: '8%', value: 0.08 }, { label: '0%', value: 0 }]"
              option-label="label"
              option-value="value"
              class="w-full"
            />
          </div>
        </div>
        <div class="flex items-center gap-2">
          <Checkbox v-model="form.isOnlinePurchaseEnabled" :binary="true" input-id="online" />
          <label for="online" class="text-sm">オンライン購入を有効にする</label>
        </div>
      </div>
      <template #footer>
        <Button label="キャンセル" text :disabled="saving" @click="showCreateDialog = false" />
        <Button label="追加" icon="pi pi-check" :loading="saving" @click="handleCreate" />
      </template>
    </Dialog>
  </div>
</template>
