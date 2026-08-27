package com.mannschaft.app.survey.listener;

import com.mannschaft.app.bulletin.service.SurveyBulletinThreadService;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.survey.SurveyStatus;
import com.mannschaft.app.survey.event.SurveyCreatedEvent;
import com.mannschaft.app.survey.event.SurveyStatusChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * アンケートイベントを受信して掲示板スレッドを自動管理するリスナー。
 *
 * <p>アンケート作成時: 専用掲示板スレッドを自動生成する。</p>
 * <p>アンケート締め切り時: 対応する掲示板スレッドをロックする。</p>
 *
 * <p>このリスナーは非同期（{@code "event-pool"}）で動作するため、
 * スレッド作成失敗が survey ドメインのトランザクションに影響しない。
 * 失敗時は WARN ログを出力して処理を継続する。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SurveyBulletinThreadListener {

    private final SurveyBulletinThreadService surveyBulletinThreadService;

    /**
     * アンケート作成イベントを受信して掲示板スレッドを自動生成する。
     *
     * @param event アンケート作成イベント
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。アンケートに紐づく掲示スレッドの生成・状態同期。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSurveyCreated(SurveyCreatedEvent event) {
        try {
            surveyBulletinThreadService.createForSurvey(
                    event.getSurveyId(),
                    event.getScopeType(),
                    event.getScopeId(),
                    event.getTitle()
            );
        } catch (Exception e) {
            log.warn("アンケートスレッド自動作成に失敗: surveyId={}", event.getSurveyId(), e);
        }
    }

    /**
     * アンケートステータス変更イベントを受信し、CLOSED 時にスレッドをロックする。
     *
     * @param event アンケートステータス変更イベント
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。アンケートに紐づく掲示スレッドの生成・状態同期。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSurveyStatusChanged(SurveyStatusChangedEvent event) {
        if (event.getNewStatus() == SurveyStatus.CLOSED) {
            try {
                surveyBulletinThreadService.lockForSurvey(event.getSurveyId());
            } catch (Exception e) {
                log.warn("アンケートスレッドロックに失敗: surveyId={}", event.getSurveyId(), e);
            }
        }
    }
}
