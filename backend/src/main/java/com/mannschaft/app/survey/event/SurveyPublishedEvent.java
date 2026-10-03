package com.mannschaft.app.survey.event;

import com.mannschaft.app.common.event.BaseEvent;
import com.mannschaft.app.survey.DistributionMode;
import lombok.Getter;

/**
 * アンケート公開イベント（(B) 組織→参加チーム配信 案C フェーズA）。
 *
 * <p>アンケート公開後にトランザクションコミットが完了した時点で発行される。
 * 設計書 F05.4 §1528 の {@code SURVEY_CREATED}＝「公開時にスコープ内全メンバーへ通知」を
 * 実現するため、{@link com.mannschaft.app.survey.listener.SurveyPublishNotificationListener}
 * が本イベントを {@code AFTER_COMMIT} かつ非同期（{@code event-pool}）で受信し、
 * 配信母集団を解決して一括通知を送信する。</p>
 *
 * <p><b>規模対応 Tier2</b>: 公開 API レスポンスは本イベント発行までで即返しし、
 * 受信者ループ（通知行作成）はコミット済みの本イベント受信スレッドで非同期実行する。
 * これにより数万人規模でも publish 応答をブロックしない。</p>
 */
@Getter
public class SurveyPublishedEvent extends BaseEvent {

    /** アンケートID。 */
    private final long surveyId;

    /**
     * スコープ種別（ORGANIZATION / TEAM / COMMITTEE 等）。
     * SurveyEntity の scopeType カラムに相当する文字列。
     */
    private final String scopeType;

    /** スコープID。 */
    private final long scopeId;

    /** アンケートのタイトル。 */
    private final String title;

    /** 配信モード（ALL / TARGETED）。母集団解決の分岐に用いる。 */
    private final DistributionMode distributionMode;

    /** 応援者（SUPPORTER）を配信母集団に含めるか（組織スコープ×ALL でのみ意味を持つ）。 */
    private final boolean includeSupporters;

    /** 公開操作の実行者ユーザーID（通知の actorId に用いる）。 */
    private final Long actorId;

    public SurveyPublishedEvent(long surveyId, String scopeType, long scopeId, String title,
                                DistributionMode distributionMode, boolean includeSupporters,
                                Long actorId) {
        super();
        this.surveyId = surveyId;
        this.scopeType = scopeType;
        this.scopeId = scopeId;
        this.title = title;
        this.distributionMode = distributionMode;
        this.includeSupporters = includeSupporters;
        this.actorId = actorId;
    }
}
