<script setup lang="ts">
/**
 * F03.10 第四陣 Wave2-B 管理者向けイベント代理出席一覧パネル。
 *
 * <p>管理者が代理出席の一覧を確認し、ACCEPTED 状態の委任に対して
 * 代理チェックインを実行できる。</p>
 */
import DelegationListItem from '~/components/proxy/DelegationListItem.vue'
import type { EventDelegationResponse } from '~/types/event'

const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: string
  eventId: number
}>()

const { fetchDelegations, proxyCheckin } = useEventDelegationApi()
const { t } = useI18n()

const page = ref(1)
const size = 20
const delegations = ref<EventDelegationResponse[]>([])
const total = ref(0)
const loading = ref(false)
const checkingIn = ref<string | null>(null)
const errorMessage = ref<string | null>(null)

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = null
  try {
    const res = await fetchDelegations(props.eventId, page.value, size)
    delegations.value = res.data ?? []
    total.value = res.total ?? 0
  } finally {
    loading.value = false
  }
}

async function handleProxyCheckin(delegation: EventDelegationResponse): Promise<void> {
  checkingIn.value = delegation.id
  errorMessage.value = null
  try {
    await proxyCheckin(props.eventId, delegation.id)
    await load()
  } catch {
    errorMessage.value = t('error.unknown')
  } finally {
    checkingIn.value = null
  }
}

onMounted(load)
watch(() => page.value, load)
</script>

<template>
  <div class="space-y-2 p-4">
    <div v-if="loading" class="py-4 text-center text-sm text-surface-500">
      {{ $t('button.loading') }}
    </div>
    <div
      v-else-if="delegations.length === 0"
      class="py-4 text-center text-sm text-surface-500"
    >
      {{ $t('proxy.delegation.admin.empty') }}
    </div>
    <template v-else>
      <div
        v-if="errorMessage"
        class="mb-2 rounded border border-red-200 bg-red-50 p-2 text-sm text-red-700 dark:border-red-800 dark:bg-red-900/20 dark:text-red-300"
      >
        {{ errorMessage }}
      </div>
      <div
        v-for="item in delegations"
        :key="item.id"
        class="space-y-1"
      >
        <DelegationListItem :item="item" />
        <!-- ACCEPTED 状態の場合のみ代理チェックインボタンを表示 -->
        <div v-if="item.status === 'ACCEPTED'" class="flex justify-end px-1">
          <Button
            :label="$t('proxy.delegation.admin.checkin')"
            icon="pi pi-check"
            size="small"
            severity="primary"
            outlined
            :loading="checkingIn === item.id"
            :disabled="checkingIn !== null"
            @click="handleProxyCheckin(item)"
          />
        </div>
      </div>

      <!-- ページネーション -->
      <div v-if="total > size" class="mt-4 flex justify-center">
        <Paginator
          :first="(page - 1) * size"
          :rows="size"
          :total-records="total"
          :rows-per-page-options="[]"
          @page="(e: { page: number }) => { page = e.page + 1 }"
        />
      </div>
    </template>
  </div>
</template>
