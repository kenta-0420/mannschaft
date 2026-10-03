<script setup lang="ts">
import type { RanchInventory, ShopItem } from '~/types/ranch'
definePageMeta({ middleware: 'auth' })
const { t } = useI18n(); useHead({ title: t('ranch.decorations.title') })
const ranch = useRanchState(); const { handleApiError } = useErrorHandler()
const shop = ref<ShopItem[]>([]); const items = ref<RanchInventory[]>([]); const chosen = ref<string | null>(null); const inventoryCursor = ref<string | null>(null); const failed = ref(false); const loading = ref(false)
async function load() { loading.value = true; failed.value = false; try { await ranch.load(); shop.value = await ranch.api.shop(); const inventory = await ranch.api.inventory(); items.value = inventory.data; inventoryCursor.value = inventory.meta.hasNext ? inventory.meta.nextCursor : null } catch(error) { failed.value = true; handleApiError(error, 'RanchDecorations') } finally { loading.value = false } }
async function purchase(item: ShopItem) { const version = ranch.state.value?.owner?.version; if (!version) return; try { await ranch.act(() => ranch.api.purchase(item.skuKey,item.priceVersion,version)); await load() } catch { await load(); failed.value = true } }
async function place(index: number) { const slot = ranch.state.value?.roomSlots[index]; if (slot && chosen.value) { try { await ranch.act(() => ranch.api.place(slot,chosen.value!)); await load() } catch { failed.value = true } } }
async function remove(index: number) { const slot = ranch.state.value?.roomSlots[index]; if (slot) { try { await ranch.act(() => ranch.api.remove(slot)); await load() } catch { failed.value = true } } }
async function loadMore() {
 if (!inventoryCursor.value) return
 try { const page = await ranch.api.inventory(inventoryCursor.value); items.value.push(...page.data); inventoryCursor.value = page.meta.hasNext ? page.meta.nextCursor : null } catch (error) { failed.value = true; handleApiError(error, 'RanchInventory') }
}
async function retryCommand() { try { await ranch.act(ranch.api.retryPending); await load() } catch { failed.value = true } }
onMounted(load)
</script>
<template>
 <div class="space-y-5">
  <PageHeader :title="t('ranch.decorations.title')" back-to="/my/ranch" /><p>{{ t('ranch.decorations.permanent') }}</p>
  <PageLoading v-if="loading" /><DashboardErrorState v-else-if="failed || ranch.failed.value" @retry="load" />
  <Button v-if="ranch.api.command.pending.value" class="min-h-11" :label="t('ranch.retry')" @click="retryCommand" />
  <template v-else>
   <SectionCard :title="t('ranch.decorations.slots')">
    <Select v-model="chosen" :options="items.filter(item => !item.isRevoked)" option-value="id" :option-label="item => t(item.labelKey)" class="w-full mb-3" :aria-label="t('ranch.decorations.choose')" />
    <Button v-if="inventoryCursor" class="min-h-11 mb-3" :label="t('ranch.next')" @click="loadMore" />
    <div class="grid grid-cols-1 md:grid-cols-3 gap-3"><div v-for="(slot,index) in ranch.state.value?.roomSlots" :key="slot.slotKey" class="space-y-2"><p>{{ t('ranch.decorations.slot', { n: index+1 }) }} · {{ slot.inventoryId ? t('ranch.decorations.placed') : t('ranch.decorations.empty') }}</p><Button class="min-h-11" :label="t('ranch.decorations.place')" :disabled="!chosen || ranch.api.command.running.value" @click="place(index)" /><Button class="min-h-11 ml-2" :label="t('ranch.decorations.remove')" :disabled="!slot.inventoryId || ranch.api.command.running.value" outlined @click="remove(index)" /></div></div>
   </SectionCard>
   <SectionCard :title="t('ranch.decorations.shop')"><p>{{ t('ranch.decorations.balance', { balance: ranch.state.value?.owner?.balance ?? '0' }) }}</p><DashboardEmptyState v-if="shop.length === 0" :message="t('ranch.decorations.noItems')" /><div v-for="item in shop" :key="item.skuKey" class="flex flex-wrap items-center gap-3 py-3"><p>{{ t(item.labelKey) }} · {{ item.pricePoints }}</p><Button class="min-h-11" :label="t(item.isOwned ? 'ranch.decorations.owned' : 'ranch.decorations.purchase')" :disabled="item.isOwned || !ranch.state.value?.shopAvailable || ranch.api.command.running.value" @click="purchase(item)" /></div></SectionCard>
  </template>
 </div>
</template>
