package com.mannschaft.app.pointcard.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.auth.event.UserAnonymizedEvent;
import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import com.mannschaft.app.pointcard.repository.PointCardGroupRepository;
import com.mannschaft.app.pointcard.repository.PointCardUserSettingsRepository;
import com.mannschaft.app.pointcard.repository.UserPointCardRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * pointcard ドメインの退会データ削除リスナー（クロスドメインFK撤廃キャンペーン 第二陣C）。
 *
 * <p>users を親とする ON DELETE CASCADE のクロスドメインFK
 * （{@code fk_upc_user} / {@code fk_pcg_user} / {@code fk_pcus_user}）を
 * V98.001 で撤廃するにあたり、退会フローでリスナーが先行削除することで
 * CASCADE を冗長化する（第一陣 notification と同じ論法）。</p>
 *
 * <p><b>二層削除モデル（CLAUDE.md「PII 消去のタイミング §13.12」）:</b>
 * <ul>
 *   <li><b>保有カード（user_point_cards）= 退会時即時削除</b> —
 *       {@code display_name} / {@code nickname} / {@code barcode_value} / {@code memo} は
 *       AES-256-GCM で暗号化された高機微 PII（{@code @PersonalData category="point_cards"}）。
 *       「どのカードを所有しているか／カード番号本体」を表し、再設定で復旧する性質でもないため、
 *       GDPR Art.17 に照らし {@link UserAnonymizedEvent}（退会受付直後・即時消去）を購読して削除する。</li>
 *   <li><b>カード整理グループ（point_card_groups）= 30日後の物理削除時削除</b> —
 *       グループ名・絵文字はシーン名で PII ではなく、カードを束ねる個人「設定」＝復元価値がある。
 *       GDPR Art.17 の30日撤回ウィンドウを保持するため
 *       {@link AccountPurgedEvent}（30日後の物理削除完了）を購読して削除する。</li>
 *   <li><b>ユーザー設定（point_card_user_settings）= 30日後の物理削除時削除</b> —
 *       オプトイン状態・規約同意・WebAuthn 要求の 1:1 個人設定で、退会撤回時に復元価値がある。
 *       {@link AccountPurgedEvent} を購読して削除する。</li>
 * </ul>
 * </p>
 *
 * <p><b>同一ドメイン内 FK の削除順:</b>
 * user_point_cards を親とする pointcard ドメイン内の子テーブル
 * （point_card_group_items / point_card_balance_events / point_card_stamp_events）は
 * いずれも {@code card_id ON DELETE CASCADE} で user_point_cards を参照している。
 * よって即時削除で {@code userPointCardRepository.deleteByUserId(userId)} を実行すると、
 * これら3子テーブルは DB の同一ドメイン内 CASCADE により自動削除される
 * （子→親の手動順序削除は不要・本リスナーで子 repo を直接叩く必要はない）。
 * group_items は card_id / group_id の両 CASCADE を持つが、カード即時削除時点で
 * card_id 経由 CASCADE により全消去されるため、30日後の groups 削除時には既に空＝整合は崩れない。</p>
 *
 * <p><b>三重防御パターン（過去の ApplicationContext 全滅事故の再発防止）:</b>
 * <ul>
 *   <li>{@code @Async(...)} — 呼び出し元 TX とスレッド分離（即時=event-pool / 30日=purge-pool）</li>
 *   <li>{@code @TransactionalEventListener(AFTER_COMMIT)} — 呼出元コミット成立後のみ実行</li>
 *   <li>{@code @Transactional(REQUIRES_NEW)} — 独立した新規 TX。
 *       素の {@code REQUIRED} は AFTER_COMMIT では起動時バリデーションで弾かれるか
 *       silently 破棄されるため必須。</li>
 * </ul>
 * </p>
 *
 * <p>例外は WARN ログのみで伝播させない（他ドメインリスナーの処理を妨げない／
 * GDPR タイムリミットを優先する）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PointCardAnonymizationEventListener {

    private final UserPointCardRepository userPointCardRepository;
    private final PointCardGroupRepository pointCardGroupRepository;
    private final PointCardUserSettingsRepository pointCardUserSettingsRepository;

    /**
     * 退会即時匿名化（{@link UserAnonymizedEvent}）を購読し、保有カード（暗号化 PII）を即時削除する。
     *
     * <p>user_point_cards の削除により、pointcard ドメイン内の子テーブル
     * （group_items / balance_events / stamp_events）は card_id CASCADE で自動削除される。</p>
     *
     * @param event 退会即時匿名化イベント
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると退会・完全削除済み利用者のポイントカードに個人情報が残存し、退会済みなのに PII が残るという不整合になる")
    @Async("event-pool")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserAnonymized(UserAnonymizedEvent event) {
        Long userId = event.getUserId();
        try {
            userPointCardRepository.deleteByUserId(userId);
            log.info("ユーザー退会: 保有ポイントカード（暗号化PII）を即時削除完了: userId={}", userId);
        } catch (Exception e) {
            log.warn("ユーザー退会: 保有ポイントカードの即時削除失敗: userId={}, error={}",
                    userId, e.getMessage(), e);
        }
    }

    /**
     * 退会30日後の物理削除（{@link AccountPurgedEvent}）を購読し、
     * カード整理グループ・ユーザー設定（個人設定・復元価値）を削除する。
     *
     * @param event アカウント物理削除完了イベント
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると退会・完全削除済み利用者のポイントカードに個人情報が残存し、退会済みなのに PII が残るという不整合になる")
    @Async("purge-pool")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAccountPurged(AccountPurgedEvent event) {
        Long userId = event.getUserId();
        try {
            pointCardGroupRepository.deleteByUserId(userId);
            pointCardUserSettingsRepository.deleteByUserId(userId);
            log.info("ユーザー退会 pointcard purge 完了: カードグループ・ユーザー設定削除: userId={}", userId);
        } catch (Exception e) {
            log.warn("ユーザー退会 pointcard purge: カードグループ・ユーザー設定削除失敗: userId={}, error={}",
                    userId, e.getMessage(), e);
        }
    }
}
