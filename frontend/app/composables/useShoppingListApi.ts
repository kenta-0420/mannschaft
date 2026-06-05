import type {
  ShoppingListResponse,
  ShoppingListRequest,
  ShoppingItemResponse,
  ShoppingItemRequest,
} from '~/types/shopping-list'

export function useShoppingListApi() {
  const api = useApi()

  function buildBase(teamId: string) {
    return `/api/v1/teams/${teamId}/shopping-lists`
  }

  // === Lists ===
  async function listShoppingLists(teamId: string) {
    return api<{ data: ShoppingListResponse[] }>(buildBase(teamId))
  }

  async function createShoppingList(teamId: string, body: ShoppingListRequest) {
    return api<{ data: ShoppingListResponse }>(buildBase(teamId), { method: 'POST', body })
  }

  async function updateShoppingList(teamId: string, listId: number, body: ShoppingListRequest) {
    return api<{ data: ShoppingListResponse }>(`${buildBase(teamId)}/${listId}`, {
      method: 'PUT',
      body,
    })
  }

  async function deleteShoppingList(teamId: string, listId: number) {
    return api(`${buildBase(teamId)}/${listId}`, { method: 'DELETE' })
  }

  async function archiveShoppingList(teamId: string, listId: number) {
    return api(`${buildBase(teamId)}/${listId}/archive`, { method: 'PATCH' })
  }

  async function copyFromTemplate(teamId: string, listId: number) {
    return api<{ data: ShoppingListResponse }>(
      `${buildBase(teamId)}/${listId}/copy-from-template`,
      { method: 'POST' },
    )
  }

  // === Items ===
  async function listItems(teamId: string, listId: number) {
    return api<{ data: ShoppingItemResponse[] }>(`${buildBase(teamId)}/${listId}/items`)
  }

  async function createItem(teamId: string, listId: number, body: ShoppingItemRequest) {
    return api<{ data: ShoppingItemResponse }>(`${buildBase(teamId)}/${listId}/items`, {
      method: 'POST',
      body,
    })
  }

  async function updateItem(
    teamId: string,
    listId: number,
    itemId: number,
    body: ShoppingItemRequest,
  ) {
    return api<{ data: ShoppingItemResponse }>(`${buildBase(teamId)}/${listId}/items/${itemId}`, {
      method: 'PUT',
      body,
    })
  }

  async function deleteItem(teamId: string, listId: number, itemId: number) {
    return api(`${buildBase(teamId)}/${listId}/items/${itemId}`, { method: 'DELETE' })
  }

  async function checkItem(teamId: string, listId: number, itemId: number) {
    return api(`${buildBase(teamId)}/${listId}/items/${itemId}/check`, { method: 'PATCH' })
  }

  async function uncheckAllItems(teamId: string, listId: number) {
    return api(`${buildBase(teamId)}/${listId}/items/uncheck-all`, { method: 'PATCH' })
  }

  async function deleteCheckedItems(teamId: string, listId: number) {
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
