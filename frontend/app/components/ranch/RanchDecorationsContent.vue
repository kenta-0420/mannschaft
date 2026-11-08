<script setup lang="ts">
import type { ApiResponse } from '~/types/api'
import type { CursorPage, RanchInventory, RanchLegacySyncResult, ShopItem } from '~/types/ranch'
const { t } = useI18n()
useHead({ title: t('ranch.decorations.title') })
const ranch = useRanchState()
const privateRead = useRanchPrivateRead()
const { handleApiError } = useErrorHandler()
const shop = ref<ShopItem[]>([])
const items = ref<RanchInventory[]>([])
const chosen = ref<string | null>(null)
const inventoryCursor = ref<string | null>(null)
const failed = ref(false)
const loading = ref(true)
const shopFailed = ref(false)
const syncResult = ref<RanchLegacySyncResult | null>(null)
const syncFailed = ref(false)
const inventoryFailed = ref(false)
let loadRun = 0
let inventoryRun = 0
async function loadInventory(cursor?: string) {
 const run = ++inventoryRun
 const page = await privateRead.read<CursorPage<RanchInventory>>('/api/v1/me/ranch/collectibles', { cursor, limit: 20 })
 if (!privateRead.isCurrent() || run !== inventoryRun) return
 items.value = cursor ? [...items.value, ...page.data] : page.data
 inventoryCursor.value = page.meta.hasNext ? page.meta.nextCursor : null
}
async function load() {
 const run = ++loadRun
 loading.value = true
 failed.value = false
 shopFailed.value = false
 try {
  await ranch.load()
  if (!privateRead.isCurrent() || run !== loadRun) return
  shop.value = []
  items.value = []
  inventoryCursor.value = null
  if (!ranch.state.value?.owner) return
  await loadInventory()
  if (!privateRead.isCurrent() || run !== loadRun || !ranch.state.value?.shopAvailable) return
  try {
   const response = await privateRead.read<ApiResponse<ShopItem[]>>('/api/v1/me/ranch/shop')
   if (privateRead.isCurrent() && run === loadRun) shop.value = response.data
  } catch (error) {
   if (!privateRead.isCurrent() || run !== loadRun) return
   shopFailed.value = true
   handleApiError(error, 'RanchShop')
  }
 } catch (error) {
  if (!privateRead.isCurrent() || run !== loadRun) return
  failed.value = true
  handleApiError(error, 'RanchDecorations')
 } finally {
  if (privateRead.isCurrent() && run === loadRun) loading.value = false
 }
}
async function purchase(item: ShopItem) {
 const version = ranch.state.value?.owner?.version
 if (!version) return
 try { await ranch.act(() => ranch.api.purchase(item.skuKey, item.priceVersion, version)); if (privateRead.isCurrent()) await load() }
 catch { if (privateRead.isCurrent()) failed.value = true }
}
async function place(index: number) {
 const slot = ranch.state.value?.roomSlots[index]
 const inventoryId = chosen.value
 if (!slot || !inventoryId) return
 try { await ranch.act(() => ranch.api.place(slot, inventoryId)); if (privateRead.isCurrent()) await load() }
 catch { if (privateRead.isCurrent()) failed.value = true }
}
async function remove(index: number) {
 const slot = ranch.state.value?.roomSlots[index]
 if (!slot) return
 try { await ranch.act(() => ranch.api.remove(slot)); if (privateRead.isCurrent()) await load() }
 catch { if (privateRead.isCurrent()) failed.value = true }
}
async function loadMore() {
 if (!inventoryCursor.value) return
 try { await loadInventory(inventoryCursor.value) }
 catch (error) { if (privateRead.isCurrent()) { failed.value = true; handleApiError(error, 'RanchInventory') } }
}
function pendingSyncCursor(): string | null {
 const pending = ranch.api.command.pending.value
 if (pending?.path !== '/api/v1/me/ranch/collectibles/sync' || pending.method !== 'POST') return null
 const body = pending.body
 if (!body || typeof body !== 'object' || !('afterAwardId' in body)) return null
 return typeof body.afterAwardId === 'string' && /^(0|[1-9][0-9]*)$/.test(body.afterAwardId) ? body.afterAwardId : null
}
async function refreshInventory() {
 inventoryFailed.value = false
 try { await loadInventory() }
 catch (error) {
  if (!privateRead.isCurrent()) return
  inventoryFailed.value = true
  handleApiError(error, 'RanchInventory')
 }
}
async function syncLegacy(cursor: string) {
 syncFailed.value = false
 try {
  const result = await ranch.act(() => ranch.api.syncLegacy(cursor))
  if (!privateRead.isCurrent()) return
  syncResult.value = result
 } catch {
  if (privateRead.isCurrent()) syncFailed.value = true
  return
 }
 // 保存ACKは保持し、後続GETだけの失敗は命令失敗に戻さない。
 if (privateRead.isCurrent()) await refreshInventory()
}
async function retryCommand() {
 const cursor = pendingSyncCursor()
 if (cursor !== null) { await syncLegacy(cursor); return }
 try { await ranch.act(ranch.api.retryPending); if (privateRead.isCurrent()) await load() }
 catch { if (privateRead.isCurrent()) failed.value = true }
}
onMounted(load)
</script>
<template>
 <div class="space-y-5">
  <PageHeader :title="t('ranch.decorations.title')" back-to="/my/ranch" />
  <p>{{ t('ranch.decorations.permanent') }}</p>
  <SectionCard :title="t('ranch.decorations.importTitle')">
   <p>{{ t('ranch.decorations.importDescription') }}</p>
   <p v-if="syncResult" role="status">{{ t('ranch.decorations.importResult', { processed: syncResult.processedCount, imported: syncResult.importedCount }) }}</p>
   <p v-if="syncFailed" role="alert">{{ t('ranch.command.failed') }}</p>
   <Button class="min-h-11" :label="t(syncResult ? 'ranch.decorations.importAgain' : 'ranch.decorations.importStart')" :disabled="!ranch.state.value?.owner || ranch.api.command.running.value || !!ranch.api.command.pending.value" @click="syncLegacy('0')" />
   <Button v-if="syncResult?.hasNext" class="min-h-11 ml-2" :label="t('ranch.decorations.importContinue')" :disabled="!ranch.state.value?.owner || ranch.api.command.running.value || !!ranch.api.command.pending.value" @click="syncLegacy(syncResult.nextAfterAwardId)" />
  </SectionCard>
  <DashboardErrorState v-if="inventoryFailed" @retry="refreshInventory" />
  <PageLoading v-if="loading" />
  <DashboardErrorState v-else-if="failed || ranch.failed.value" @retry="load" />
  <Button v-if="ranch.api.command.pending.value" class="min-h-11" :label="t('ranch.retry')" :disabled="ranch.api.command.running.value" @click="retryCommand" />
  <template v-else-if="!loading && !failed && !ranch.failed.value">
   <SectionCard :title="t('ranch.decorations.slots')">
    <Select v-model="chosen" :options="items.filter(item => !item.isRevoked)" option-value="id" :option-label="(item: RanchInventory) => t(item.labelKey)" class="w-full mb-3" :aria-label="t('ranch.decorations.choose')" />
    <Button v-if="inventoryCursor" class="min-h-11 mb-3" :label="t('ranch.next')" @click="loadMore" />
    <div class="grid grid-cols-1 md:grid-cols-3 gap-3">
     <div v-for="(slot,index) in ranch.state.value?.roomSlots" :key="slot.slotKey" class="space-y-2">
      <p>{{ t('ranch.decorations.slot', { n: index+1 }) }} · {{ slot.inventoryId ? t('ranch.decorations.placed') : t('ranch.decorations.empty') }}</p>
      <Button class="min-h-11" :label="t('ranch.decorations.place')" :disabled="!chosen || ranch.api.command.running.value" @click="place(index)" />
      <Button class="min-h-11 ml-2" :label="t('ranch.decorations.remove')" :disabled="!slot.inventoryId || ranch.api.command.running.value" outlined @click="remove(index)" />
     </div>
    </div>
   </SectionCard>
   <SectionCard :title="t('ranch.decorations.shop')">
    <p>{{ t('ranch.decorations.balance', { balance: ranch.state.value?.owner?.balance ?? '0' }) }}</p>
    <p v-if="!ranch.state.value?.shopAvailable">{{ t('ranch.unavailable') }}</p>
    <DashboardErrorState v-else-if="shopFailed" @retry="load" />
    <template v-else>
     <DashboardEmptyState v-if="shop.length === 0" :message="t('ranch.decorations.noItems')" />
     <div v-for="item in shop" :key="item.skuKey" class="flex flex-wrap items-center gap-3 py-3">
      <p>{{ t(item.labelKey) }} · {{ item.pricePoints }}</p>
      <Button class="min-h-11" :label="t(item.isOwned ? 'ranch.decorations.owned' : 'ranch.decorations.purchase')" :disabled="item.isOwned || ranch.api.command.running.value" @click="purchase(item)" />
     </div>
    </template>
   </SectionCard>
  </template>
 </div>
</template>