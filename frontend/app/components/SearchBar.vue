<script setup lang="ts">
defineProps<{
  placeholder?: string
  showTemplateFilter?: boolean
  showOrgTypeFilter?: boolean
}>()

const emit = defineEmits<{
  search: [params: { keyword: string; prefecture: string; template: string; orgType: string }]
}>()

const keyword = ref('')
const prefecture = ref('')
const template = ref('')
const orgType = ref('')

const templateOptions = [
  { label: '全て', value: '' },
  { label: 'スポーツ', value: 'SPORTS' },
  { label: 'クリニック', value: 'CLINIC' },
  { label: 'クラス', value: 'CLASS' },
  { label: 'コミュニティ', value: 'COMMUNITY' },
  { label: '企業', value: 'COMPANY' },
  { label: '家族', value: 'FAMILY' },
  { label: 'その他', value: 'OTHER' },
]

const orgTypeOptions = [
  { label: '全て', value: '' },
  { label: '非営利', value: 'NONPROFIT' },
  { label: '営利', value: 'FORPROFIT' },
]

function onSearch() {
  emit('search', {
    keyword: keyword.value,
    prefecture: prefecture.value,
    template: template.value,
    orgType: orgType.value,
  })
}
</script>

<template>
  <div class="flex flex-wrap items-end gap-3">
    <div class="min-w-48 flex-1">
      <label class="mb-1 block text-sm font-medium">キーワード</label>
      <IconField>
        <InputIcon class="pi pi-search" />
        <InputText
          v-model="keyword"
          :placeholder="placeholder ?? '名前で検索'"
          class="w-full"
          @keyup.enter="onSearch"
        />
      </IconField>
    </div>
    <div class="w-36">
      <label class="mb-1 block text-sm font-medium">都道府県</label>
      <InputText v-model="prefecture" placeholder="例: 東京都" class="w-full" />
    </div>
    <div v-if="showTemplateFilter" class="w-40">
      <label class="mb-1 block text-sm font-medium">テンプレート</label>
      <Select
        v-model="template"
        :options="templateOptions"
        option-label="label"
        option-value="value"
        class="w-full"
      />
    </div>
    <div v-if="showOrgTypeFilter" class="w-36">
      <label class="mb-1 block text-sm font-medium">組織タイプ</label>
      <Select
        v-model="orgType"
        :options="orgTypeOptions"
        option-label="label"
        option-value="value"
        class="w-full"
      />
    </div>
    <Button label="検索" icon="pi pi-search" @click="onSearch" />
  </div>
</template>
