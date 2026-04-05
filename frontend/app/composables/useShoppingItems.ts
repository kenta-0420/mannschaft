import type { ShoppingListResponse, ShoppingItemResponse } from '~/types/shopping-list'

export function useShoppingItems(teamId: number, selectedList: Ref<ShoppingListResponse | null>) {
  const shoppingApi = useShoppingListApi()
  const { showError } = useNotification()

  const items = ref<ShoppingItemResponse[]>([])
  const loadingItems = ref(false)
  const newItemName = ref('')

  async function loadItems() {
    if (!selectedList.value) return
    loadingItems.value = true
    try {
      const res = await shoppingApi.listItems(teamId, selectedList.value.id)
      items.value = res.data
    } catch {
      showError('アイテムの取得に失敗しました')
    } finally {
      loadingItems.value = false
    }
  }

  async function addItem() {
    if (!selectedList.value || !newItemName.value.trim()) return
    try {
      await shoppingApi.createItem(teamId, selectedList.value.id, { name: newItemName.value.trim() })
      newItemName.value = ''
      await loadItems()
    } catch {
      showError('アイテムの追加に失敗しました')
    }
  }

  async function toggleItem(item: ShoppingItemResponse) {
    if (!selectedList.value) return
    try {
      await shoppingApi.checkItem(teamId, selectedList.value.id, item.id)
      await loadItems()
    } catch {
      showError('更新に失敗しました')
    }
  }

  async function removeItem(item: ShoppingItemResponse) {
    if (!selectedList.value) return
    try {
      await shoppingApi.deleteItem(teamId, selectedList.value.id, item.id)
      await loadItems()
    } catch {
      showError('削除に失敗しました')
    }
  }

  async function clearChecked() {
    if (!selectedList.value || !confirm('チェック済みのアイテムを削除しますか？')) return
    try {
      await shoppingApi.deleteCheckedItems(teamId, selectedList.value.id)
      await loadItems()
    } catch {
      showError('削除に失敗しました')
    }
  }

  return { items, loadingItems, newItemName, loadItems, addItem, toggleItem, removeItem, clearChecked }
}
