package com.mannschaft.app.reservation;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * 緊急休業（臨時休業）通知の「患者確認」がコミットされたことを配信リスナーへ伝えるアプリ内イベント。
 *
 * <p><b>正本は HTTP・本イベントは配信のトリガーのみ</b>: {@code EmergencyClosureService.confirmClosure()} が
 * {@code confirmed=true}（{@code confirmedAt} セット）を保存した直後に {@code ApplicationEventPublisher} へ
 * publish される。受信は {@link EmergencyClosureBroadcastListener}（AFTER_COMMIT・配信専用・DB 書き込みなし）。
 * お手本は F08.10 ライブ観戦の {@code MatchLiveUpdateEvent} / {@code MatchLiveBroadcastListener}。</p>
 *
 * <p><b>固有名（命名衝突回避）</b>: 別パッケージの同名 Bean が ApplicationContext を全滅させる事故
 * （feedback: 別パッケージ同名 @Component/@Service）を避けるため、{@code reservation} ドメイン固有名とする。
 * 本イベントは Bean ではない POJO だが、関連クラス（リスナー）の命名も含めて固有名を徹底する。</p>
 *
 * <p>搬送するのは確認の事実を特定する最小情報（closureId / teamId / userId / confirmedAt）のみ。
 * 確認サマリ（confirmedCount/totalCount）やユーザー氏名は配信時点でリスナーが算出/取得する
 * （記録スレッドではなく配信時点で一元化）。</p>
 */
@Getter
@Builder
@ToString
public class EmergencyClosureConfirmedEvent {

    /** 確認対象の臨時休業 ID。 */
    private final Long closureId;

    /** 配信トピックのスコープとなるチーム ID。 */
    private final Long teamId;

    /** 確認したユーザー ID。 */
    private final Long userId;

    /** 確認時刻（{@code confirmedAt}）。 */
    private final LocalDateTime confirmedAt;
}
