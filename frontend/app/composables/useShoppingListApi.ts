import type {
  ShoppingListResponse,
  ShoppingListRequest,
  ShoppingItemResponse,
  ShoppingItemRequest,
} from '~/types/shopping-list'

export function useShoppingListApi() {
  const api = useApi()

  function buildBase(teamId: number) {
    return `/api/v1/teams/${teamId}/shopping-lists`
  }

  // === Lists ===
  async function listShoppingLists(teamId: number) {
    return api<{ data: ShoppingListResponse[] }>(buildBase(teamId))
  }

  async function createShoppingList(teamId: number, body: ShoppingListRequest) {
    return api<{ data: ShoppingListResponse }>(buildBase(teamId), { method: 'POST', body })
  }

  async function updateShoppingList(teamId: number, listId: number, body: ShoppingListRequest) {
    return api<{ data: ShoppingListResponse }>(`${buildBase(teamId)}/${listId}`, {
      method: 'PUT',
      body,
    })
  }

  async function deleteShoppingList(teamId: number, listId: number) {
    return api(`${buildBase(teamId)}/${listId}`, { method: 'DELETE' })
  }

  async function archiveShoppingList(teamId: number, listId: number) {
    return api(`${buildBase(teamId)}/${listId}/archive`, { method: 'PATCH' })
  }

  async function copyFromTemplate(teamId: number, listId: number) {
    return api<{ data: ShoppingListResponse }>(
      `${buildBase(teamId)}/${listId}/copy-from-template`,
      { method: 'POST' },
    )
  }

  // === Items ===
  async function listItems(teamId: number, listId: number) {
    return api<{ data: ShoppingItemResponse[] }>(`${buildBase(teamId)}/${listId}/items`)
  }

  async function createItem(teamId: number, listId: number, body: ShoppingItemRequest) {
    return api<{ data: ShoppingItemResponse }>(`${buildBase(teamId)}/${listId}/items`, {
      method: 'POST',
      body,
    })
  }

  async function updateItem(
    teamId: number,
    listId: number,
    itemId: number,
    body: ShoppingItemRequest,
  ) {
    return api<{ data: ShoppingItemResponse }>(`${buildBase(teamId)}/${listId}/items/${itemId}`, {
      method: 'PUT',
      body,
    })
  }

  async function deleteItem(teamId: number, listId: number, itemId: number) {
    return api(`${buildBase(teamId)}/${listId}/items/${itemId}`, { method: 'DELETE' })
  }

  async function checkItem(teamId: number, listId: number, itemId: number) {
    return api(`${buildBase(teamId)}/${listId}/items/${itemId}/check`, { method: 'PATCH' })
  }

  async function uncheckAllItems(teamId: number, listId: number) {
    return api(`${buildBase(teamId)}/${listId}/items/uncheck-all`, { method: 'PATCH' })
  }

  async function deleteCheckedItems(teamId: number, listId: number) {
    return api(`${buildBase(teamId)}/${listId}/items/checked`, { method: 'DELETE' })
  }

  return {
    listShoppingLists,
    createShoppingList,
    updateShoppingList,
    deleteShoppingList,
    archiveShoppingList,
    copyFromTemplate,
    listItems,
    createItem,
    updateItem,
    deleteItem,
    checkItem,
    uncheckAllItems,
    deleteCheckedItems,
  }
}
