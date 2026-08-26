package com.mannschaft.app.village.event;

import com.mannschaft.app.auth.event.UserAnonymizedEvent;
import com.mannschaft.app.village.entity.UserVillageNicknameEntity;
import com.mannschaft.app.village.entity.UserVillagePinEntity;
import com.mannschaft.app.village.entity.VillageCharterDrafterEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.repository.UserVillageNicknameRepository;
import com.mannschaft.app.village.repository.UserVillagePinRepository;
import com.mannschaft.app.village.repository.VillageCharterDrafterRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * F17.1 Phase 1 B11 — ユーザー退会（即時匿名化）に応答して village ドメインの個人特定情報を
 * 物理削除 / 匿名化マーカー化する EventListener（設計書 §7.1）。
 *
 * <p>処理内容:</p>
 * <ol>
 *   <li>{@code user_village_nicknames}: Phase 1 は 1 ユーザー 1 行（{@code village_id IS NULL}）。
 *       該当行があれば <strong>物理削除</strong>。
 *       <span style="color:gray">(TODO Phase 2: 村ごとのニックネーム上書き行が増えたら
 *       {@code findAllByUserId} 系メソッドが必要)</span></li>
 *   <li>{@code user_village_pins}: 当該ユーザーの全行を物理削除。</li>
 *   <li>{@code village_memberships}: {@code subject_type=USER} の現役行を
 *       {@code leftAt=now / bannedReason="ANONYMIZED"} に更新（匿名化マーカー）。投稿は保持する。</li>
 * </ol>
 *
 * <p>本リスナーは既存 {@code AuthAnonymizationEventListener} と同じ
 * {@link UserAnonymizedEvent} を購読する。社内コンベンションどおり
 * {@code AFTER_COMMIT} + {@code REQUIRES_NEW} で失敗時はメイン処理を止めない。</p>
 *
 * <p>未対応の関連データ (別軍議で対応予定):</p>
 * <ul>
 *   <li>{@code village_creation_requests}: requester_user_id を 0 化（設計書 §7.1 ④）</li>
 *   <li>{@code bulletin_posts / timeline_posts / chat_messages}: 各機能ドメインの匿名化リスナーが担当</li>
 *   <li>{@code village_reports.reporter_user_id}: 通報者匿名化（B7 で実装済の場合は不要）</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VillageUserCleanerEventListener {

    /** 匿名化マーカー文字列（{@code village_memberships.banned_reason} に書き込む）。 */
    static final String ANONYMIZED_MARKER = "ANONYMIZED";

    private final UserVillageNicknameRepository nicknameRepository;
    private final UserVillagePinRepository pinRepository;
    private final VillageMembershipRepository membershipRepository;
    private final VillageCharterDrafterRepository charterDrafterRepository;

    @Async("event-pool")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleUserAnonymized(UserAnonymizedEvent event) {
        Long userId = event.getUserId();
        try {
            cleanupNicknames(userId);
            cleanupPins(userId);
            anonymizeMemberships(userId);
            anonymizeCharterDrafters(userId);
            log.info("ユーザー退会: village ドメイン匿名化完了: userId={}", userId);
        } catch (Exception e) {
            log.warn("ユーザー退会: village ドメイン匿名化失敗: userId={}, error={}",
                    userId, e.getMessage(), e);
        }
    }

    /**
     * ニックネームを物理削除する。Phase 1 では {@code village_id IS NULL} の全村共通 1 行のみが対象。
     */
    void cleanupNicknames(Long userId) {
        Optional<UserVillageNicknameEntity> nickname =
                nicknameRepository.findByUserIdAndVillageIdIsNull(userId);
        nickname.ifPresent(entity -> {
            nicknameRepository.delete(entity);
            log.debug("ユーザー退会: village ニックネーム物理削除: userId={} nicknameId={}",
                    userId, entity.getId());
        });
    }

    /**
     * お気に入り村ピン留めを全行物理削除する。
     */
    void cleanupPins(Long userId) {
        List<UserVillagePinEntity> pins = pinRepository.findByUserIdOrderBySortOrderAsc(userId);
        if (!pins.isEmpty()) {
            pinRepository.deleteAll(pins);
            log.debug("ユーザー退会: village ピン物理削除: userId={} count={}", userId, pins.size());
        }
    }

    /**
     * 当該ユーザーが {@code subject_type=USER} で参加している現役メンバーシップ全てに
     * 匿名化マーカーをセットし退場扱いにする。投稿は保持する。
     */
    void anonymizeMemberships(Long userId) {
        List<VillageMembershipEntity> memberships = membershipRepository
                .findBySubjectTypeAndSubjectIdAndLeftAtIsNull(VillageSubjectType.USER, userId);
        if (memberships.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (VillageMembershipEntity m : memberships) {
            m.setLeftAt(now);
            m.setBannedReason(ANONYMIZED_MARKER);
        }
        membershipRepository.saveAll(memberships);
        log.debug("ユーザー退会: village メンバーシップ匿名化: userId={} count={}",
                userId, memberships.size());
    }

    /**
     * 村憲章の策定者から個人リンク（{@code user_id}）を切断する（F17.3・設計書 §11.1）。
     *
     * <p>当該ユーザーが策定者として刻まれている全行の {@code user_id} を <strong>NULL 化</strong>し、
     * {@code nickname_snapshot}（制定当時の村ニックネーム＝仮名文字列）は<strong>残置</strong>する。
     * 実名は元々保存していない（§10 G4）ため、これは「個人リンク切断＋仮名残置」という原則4 準拠の
     * 匿名化であり、憲章という村の史料から「誰が興したか」を消さない。</p>
     */
    void anonymizeCharterDrafters(Long userId) {
        List<VillageCharterDrafterEntity> drafters = charterDrafterRepository.findByUserId(userId);
        if (drafters.isEmpty()) {
            return;
        }
        for (VillageCharterDrafterEntity d : drafters) {
            d.setUserId(null); // nickname_snapshot は残置（仮名史料）
        }
        charterDrafterRepository.saveAll(drafters);
        log.debug("ユーザー退会: village 憲章策定者の user_id NULL 化: userId={} count={}",
                userId, drafters.size());
    }
}
