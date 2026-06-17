package com.mannschaft.app.resident.event;

import com.mannschaft.app.auth.event.UserAnonymizedEvent;
import com.mannschaft.app.resident.repository.PropertyListingInquiryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * resident ドメインの退会データ削除リスナー（クロスドメインFK撤廃キャンペーン 第二陣E）。
 *
 * <p>users を親とする ON DELETE CASCADE のクロスドメインFK {@code fk_pli_user}
 * （property_listing_inquiries.user_id → users CASCADE）を V100.001 で撤廃するにあたり、
 * 退会フローでリスナーが先行削除することで CASCADE を冗長化する
 * （第一陣 notification・第二陣 pointcard / search / actionmemo と同じ論法）。</p>
 *
 * <p><b>二層削除モデル（CLAUDE.md「PII 消去のタイミング §13.12」）での区分:</b>
 * 物件問い合わせ（property_listing_inquiries）の {@code message} は自由記述の問い合わせ内容＝
 * 個人の発話（PII）であり、再設定で復旧する性質のものでもない。漏洩リスクを最小化するため
 * <b>退会時【即時削除】</b>として {@link UserAnonymizedEvent}（退会受付直後・即時消去）を購読して削除する。</p>
 *
 * <p><b>同一ドメイン内 FK は対象外:</b> {@code fk_pli_listing}
 * （listing_id → property_listings ON DELETE CASCADE）は同一 resident ドメイン内 CASCADE のため
 * V100.001 でも残す（CLAUDE.md §2 で許可）。問い合わせ行の削除は user_id 起点で行い、listing は触らない。</p>
 *
 * <p><b>三重防御パターン（過去の ApplicationContext 全滅事故の再発防止）:</b>
 * <ul>
 *   <li>{@code @Async("event-pool")} — 呼び出し元 TX とスレッド分離（即時削除プール）</li>
 *   <li>{@code @TransactionalEventListener(AFTER_COMMIT)} — 呼出元コミット成立後のみ実行</li>
 *   <li>{@code @Transactional(REQUIRES_NEW)} — 独立した新規 TX。
 *       素の {@code REQUIRED} は AFTER_COMMIT では起動時バリデーションで弾かれるため必須。</li>
 * </ul>
 * 例外は WARN ログのみで伝播させない（他ドメインリスナーの処理を妨げない／GDPR タイムリミットを優先）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResidentAnonymizationEventListener {

    private final PropertyListingInquiryRepository propertyListingInquiryRepository;

    /**
     * 退会即時匿名化（{@link UserAnonymizedEvent}）を購読し、物件問い合わせ（問い合わせ内容＝PII）を即時削除する。
     *
     * @param event 退会即時匿名化イベント
     */
    @Async("event-pool")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserAnonymized(UserAnonymizedEvent event) {
        Long userId = event.getUserId();
        try {
            int deleted = propertyListingInquiryRepository.deleteByUserId(userId);
            log.info("ユーザー退会: 物件問い合わせ（問い合わせ内容＝PII）を即時削除完了: userId={}, deleted={}",
                    userId, deleted);
        } catch (Exception e) {
            log.warn("ユーザー退会: 物件問い合わせの即時削除失敗: userId={}, error={}",
                    userId, e.getMessage(), e);
        }
    }
}
