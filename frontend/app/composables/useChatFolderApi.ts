import type {
  ChatFolderResponse,
  CreateChatFolderRequest,
  UpdateChatFolderRequest,
  AssignFolderItemRequest,
  BulkAssignFolderItemsRequest,
} from '~/types/chat-folder'

export function useChatFolderApi() {
  const api = useApi()
  const base = '/api/v1/chat-folders'

  async function listFolders() {
    return api<{ data: ChatFolderResponse[] }>(base)
  }

  async function createFolder(body: CreateChatFolderRequest) {
    return api<{ data: ChatFolderResponse }>(base, { method: 'POST', body })
  }

  async function updateFolder(folderId: number, body: UpdateChatFolderRequest) {
    return api<{ data: ChatFolderResponse }>(`${base}/${folderId}`, { method: 'PUT', body })
  }

  async function deleteFolder(folderId: number) {
    return api(`${base}/${folderId}`, { method: 'DELETE' })
  }

  async function addItem(folderId: number, body: AssignFolderItemRequest) {
    return api(`${base}/${folderId}/items`, { method: 'PUT', body })
  }

  async function addItemsBulk(folderId: number, body: BulkAssignFolderItemsRequest) {
    return api(`${base}/${folderId}/items/bulk`, { method: 'PUT', body })
  }

  async function removeItem(itemType: string, itemId: number) {
    return api(`${base}/items/${itemType}/${itemId}`, { method: 'DELETE' })
  }

  return {
    listFolders,
    createFolder,
    updateFolder,
    deleteFolder,
    addItem,
    addItemsBulk,
    removeItem,
  }
}
