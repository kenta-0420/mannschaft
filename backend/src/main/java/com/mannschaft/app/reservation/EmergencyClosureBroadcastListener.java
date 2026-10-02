package com.mannschaft.app.reservation;

import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.reservation.repository.EmergencyClosureConfirmationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 緊急休業（臨時休業）の患者確認をアドミンへリアルタイム配信する WebSocket 配信リスナー。
 *
 * <p>お手本は F08.10 ライブ観戦の {@code MatchLiveBroadcastListener}。確認の HTTP 正本
 * （{@code EmergencyClosureService.confirmClosure()}）がコミットした後に、アドミンが購読する STOMP トピック
 * {@code /topic/teams/{teamId}/emergency-closures/{closureId}/confirmations} へ確認サマリを push する。</p>
 *
 * <h3>{@code @Transactional} を付けない理由（地雷の逆ケース）</h3>
 * <p><b>本リスナーは配信のみで DB 書き込みを一切行わない</b>（カウントクエリ・ユーザー氏名取得は読み取りのみ）。
 * よって新規 TX は不要であり、{@code @Transactional} を<b>付けない</b>のが正道。
 * 素の {@code @Transactional(REQUIRED)} を AFTER_COMMIT リスナーに付けると、コミット済み TX が無い状況で
 * 起動時バリデーションに弾かれ <b>ApplicationContext が全滅</b>する（feedback: TransactionalEventListener に
 * 素の @Transactional(REQUIRED) は context 全滅）。書き込みリスナーなら {@code REQUIRES_NEW} が必要だが、
 * 配信専用の本リスナーはアノテーションそのものが不要。素の
 * {@code @TransactionalEventListener(AFTER_COMMIT)} のままで ApplicationContext は壊れない
 * （{@code EmergencyClosureBroadcastListenerTest} のアノテーション健全性 UT で担保）。</p>
 *
 * <h3>配信失敗は確認を巻き戻さない（症状は隠さない）</h3>
 * <p>AFTER_COMMIT 後は確認 TX が既にコミット済みのため、配信失敗で確認を巻き戻すことは不可能かつ不要。
 * broker エラー等は {@code try-catch} で捕捉し<b>WARN ログに残す</b>（握り潰さない・症状を隠さない）。
 * アドミンは次の確認 push か画面再取得で追従できるため実害は限定的。</p>
 *
 * <p><b>固有名（命名衝突回避）</b>: 別パッケージの同名 Bean が ApplicationContext を全滅させる事故を避けるため、
 * クラス名（＝既定 Bean 名 {@code emergencyClosureBroadcastListener}）はドメイン固有でユニークにしてある
 * （feedback: 別パッケージ同名 @Component/@Service）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmergencyClosureBroadcastListener {

    /** 配信先トピックの書式（アドミンの SUBSCRIBE 宛先と一致・後続足軽との共有契約として固定）。 */
    static final String DESTINATION_FORMAT = "/topic/teams/%s/emergency-closures/%s/confirmations";

    private final SimpMessagingTemplate messagingTemplate;
    private final EmergencyClosureConfirmationRepository confirmationRepository;
    private final NameResolverService nameResolverService;

    /**
     * 患者確認のコミット後に、確認サマリをアドミンへ配信する。
     *
     * <p>AFTER_COMMIT 発火により、確認 TX がコミット済みの状態のみ配信する
     * （未コミットの確認をアドミンへ見せない・ロールバック時の幻確認を防ぐ）。</p>
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。臨時休業の確定を予約者へ配信する処理。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEmergencyClosureConfirmed(EmergencyClosureConfirmedEvent event) {
        long totalCount = confirmationRepository.countByEmergencyClosureId(event.getClosureId());
        long confirmedCount =
                confirmationRepository.countByEmergencyClosureIdAndConfirmedAtIsNotNull(event.getClosureId());

        // 確認したユーザーの氏名（「姓 + ' ' + 名」）。auth ドメインの UserEntity を直参照せず、
        // common 共有ドメインの NameResolverService 経由で String を受け取る
        // （CLAUDE.md ドメイン境界の原則 / D-1: クロスドメイン Entity 直参照禁止）。
        String userFullName = nameResolverService.resolveUserFullName(event.getUserId());

        EmergencyClosureConfirmationUpdatePayload payload =
                EmergencyClosureConfirmationUpdatePayload.builder()
                        .confirmedCount(confirmedCount)
                        .totalCount(totalCount)
                        .userId(event.getUserId())
                        .userFullName(userFullName)
                        .confirmedAt(event.getConfirmedAt())
                        .build();

        String destination = String.format(DESTINATION_FORMAT, event.getTeamId(), event.getClosureId());
        try {
            messagingTemplate.convertAndSend(destination, payload);
            log.debug("臨時休業確認配信: destination={}, confirmed={}/{}, userId={}",
                    destination, confirmedCount, totalCount, event.getUserId());
        } catch (RuntimeException e) {
            // 配信は best-effort（正本は HTTP）。確認は既にコミット済みゆえ巻き戻さない。
            // 症状は握り潰さず WARN に残す（アドミンは次の push か再取得で追従できる）。
            log.warn("臨時休業確認配信失敗（確認は確定済み・アドミンのリアルタイム性のみ劣化）: "
                            + "destination={}, closureId={}, userId={}",
                    destination, event.getClosureId(), event.getUserId(), e);
        }
    }
}
