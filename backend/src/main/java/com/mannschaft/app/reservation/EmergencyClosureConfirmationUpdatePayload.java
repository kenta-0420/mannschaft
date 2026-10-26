package com.mannschaft.app.reservation;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 緊急休業（臨時休業）の患者確認をアドミンへリアルタイム配信する STOMP ペイロード。
 *
 * <p>配信先トピック: {@code /topic/teams/{teamId}/emergency-closures/{closureId}/confirmations}
 * （{@link EmergencyClosureBroadcastListener#DESTINATION_FORMAT}）。</p>
 *
 * <p><b>後続足軽との共有契約</b>: 本ペイロードの形（フィールド名・型）と上記トピック宛先は、
 * WS 購読認可 Interceptor / FE リアルタイムクライアントと共有する契約として固定する。
 * 変更時は後続実装と必ず整合を取ること。</p>
 */
@Getter
@Builder
public class EmergencyClosureConfirmationUpdatePayload {

    /** 確認済み件数（{@code confirmedAt} がセットされたレコード数）。 */
    private final long confirmedCount;

    /** 通知対象の総件数（この臨時休業の確認追跡レコード数）。 */
    private final long totalCount;

    /** 今回確認したユーザー ID。 */
    private final Long userId;

    /** 今回確認したユーザーの氏名（姓 + " " + 名）。 */
    private final String userFullName;

    /** 今回の確認時刻。 */
    private final LocalDateTime confirmedAt;
}
