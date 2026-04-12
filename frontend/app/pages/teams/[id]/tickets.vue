<script setup lang="ts">
import type { TicketProductResponse, TicketBookResponse } from '~/types/ticket'

definePageMeta({ middleware: 'auth' })
const route = useRoute()
const teamId = Number(route.params.id)

const { getProducts, getBooks } = useTicketApi()
const { showError } = useNotification()

const products = ref<TicketProductResponse[]>([])
const books = ref<TicketBookResponse[]>([])
const loading = ref(false)
const tab = ref<'products' | 'books'>('products')

async function load() {
  loading.value = true
  try {
    const [pRes, bRes] = await Promise.all([getProducts(teamId), getBooks(teamId)])
    products.value = pRes.data
    books.value = bRes.data
  } catch {
    showError('回数券情報の取得に失敗しました')
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

onMounted(() => load())
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <PageHeader title="回数券" />
      <Button label="商品を追加" icon="pi pi-plus" />
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
  </div>
</template>
