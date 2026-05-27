import { defineStore } from 'pinia'
import type { NotificationResponse } from '~/types/notification'

/**
 * 通知ストア（F10.7 WebSocket連動用）。
 *
 * WebSocket（STOMP）経由で受信した最新通知を保持し、
 * ウィジェット等のコンポーネントが watch できるようにする。
 *
 * /user/{userId}/queue/notifications トピックで受信した通知をここに格納する。
 */
export const useNotificationStore = defineStore('notification', {
  state: () => ({
    /** WebSocket経由で最後に受信した通知（購読中のみ更新される） */
    latestNotification: null as NotificationResponse | null,
  }),

  actions: {
    /**
     * WebSocket受信通知をストアに格納する。
     * NotificationDispatchService が送信する NotificationResponse 形式を想定。
     *
     * @param notification 受信した通知オブジェクト
     */
    setLatestNotification(notification: NotificationResponse) {
      this.latestNotification = notification
    },
  },
})
