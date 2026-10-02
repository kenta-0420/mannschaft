import { defineStore } from 'pinia'
import type { components } from '~/types/generated'

/**
 * WebSocket（STOMP）経由で受信する通知の型。
 *
 * NotificationDispatchService.sendViaWebSocket が convertAndSendToUser で送信する
 * NotificationResponse（BE の Raw DTO・scopeId/actorId は number）と同一形状。
 * REST 一覧 API（GET /api/v1/notifications）のレスポンスも同じ DTO を返すため、
 * OpenAPI 生成型（components['schemas']['NotificationResponse']）をそのまま用いる
 * （手動型 ~/types/notification.ts の NotificationResponse は actor オブジェクト・
 * scopeId 文字列化等 別形状で本 WS ペイロードとは一致しないため使わない）。
 */
export type WebSocketNotificationPayload = components['schemas']['NotificationResponse']

/**
 * 通知ストア（F10.7 WebSocket連動用）。
 *
 * WebSocket（STOMP）経由で受信した最新通知を保持し、
 * ウィジェット等のコンポーネントが watch できるようにする。
 *
 * /user/{userId}/queue/notifications トピックで受信した通知をここに格納する
 * （購読は useUserNotificationSocket composable が行う）。
 */
export const useNotificationStore = defineStore('notification', {
  state: () => ({
    /** WebSocket経由で最後に受信した通知（購読中のみ更新される） */
    latestNotification: null as WebSocketNotificationPayload | null,
  }),

  actions: {
    /**
     * WebSocket受信通知をストアに格納する。
     * NotificationDispatchService が送信する NotificationResponse 形式を想定。
     *
     * @param notification 受信した通知オブジェクト
     */
    setLatestNotification(notification: WebSocketNotificationPayload) {
      this.latestNotification = notification
    },
  },
})
