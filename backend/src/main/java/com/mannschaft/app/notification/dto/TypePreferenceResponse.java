package com.mannschaft.app.notification.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 通知種別設定レスポンスDTO（F04.3 ハイブリッド方式・カタログ）。
 *
 * <p>{@code NotificationType} enum の全種別を列挙して返す。DB にレコードがある種別は
 * 保存値を、ない種別は既定値（{@code isEnabled=true} / {@code channelOverride=false} /
 * {@code inAppEnabled=true} / {@code pushEnabled=true}）をマージして返す。</p>
 */
@Builder(toBuilder = true)
@Getter
public class TypePreferenceResponse {

    Long    id;
    Long    userId;
    String  notificationType;
    /** 表示ラベル（MessageSource でユーザーロケール解決済み）。 */
    String  label;
    /** 優先度（LOW/NORMAL/HIGH/URGENT）。 */
    String  priority;
    Boolean isEnabled;
    /** Dual（チャネル個別）モードに展開しているか。 */
    Boolean channelOverride;
    /** Dual モード時のアプリ内配信可否。 */
    Boolean inAppEnabled;
    /** Dual モード時のプッシュ配信可否。 */
    Boolean pushEnabled;
    /** URGENT 種別か（フロントでトグルを無効化）。 */
    Boolean isLocked;

    TypePrefAuditDto audit;

    public record TypePrefAuditDto(LocalDateTime createdAt, LocalDateTime updatedAt) {}
}
