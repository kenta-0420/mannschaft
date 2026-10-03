package com.mannschaft.app.village.batch;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * F17.1 Phase 1 B11 — 村長（HEADMAN）自動引き継ぎバッチ（設計書 §5.5）。
 *
 * <p>毎日 UTC 03:00 に全村を巡回し、HEADMAN がユーザー退会済（{@code users.deleted_at NOT NULL}）
 * になっている村について、以下の優先順で自動昇格する:</p>
 * <ol>
 *   <li>最古参 ELDER → HEADMAN</li>
 *   <li>ELDER 不在なら最古参 VILLAGER → HEADMAN</li>
 *   <li>HEADMAN/ELDER/VILLAGER いずれもいなければ村を archive する</li>
 * </ol>
 *
 * <p>アーキテクチャ原則:</p>
 * <ul>
 *   <li>原則5: 本バッチは village ドメインの読み書きに加えて auth ドメイン（{@link UserRepository}）
 *       を読むため越境する。これは「HEADMAN の退会判定」のためやむを得ない参照。
 *       将来 {@code UserAnonymizedEvent} を購読する {@link com.mannschaft.app.village.event.VillageUserCleanerEventListener}
 *       に統合し、退会即時引き継ぎ化する候補としてマークする（TODO）。</li>
 *   <li>HEADMAN 引き継ぎ自体は village ドメイン内の閉じた更新ゆえ {@code @Transactional} は村単位に閉じる。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VillageHeadmanSuccessionBatchService {

    private final VillageRepository villageRepository;
    private final VillageMembershipRepository membershipRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    /**
     * 毎日 UTC 03:00 にバッチ実行する。
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。村長の自動引き継ぎであり、再開後の実行で現在の条件に応じて引き継がれる。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @BatchEndpoint(name = "village-headman-succession-daily", description = "村長 HEADMAN の自動引き継ぎを毎日 UTC 03:00 に処理する")
    @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
    @SchedulerLock(
            name = "villageHeadmanSuccessionBatch",
            lockAtLeastFor = "PT1M",
            lockAtMostFor = "PT30M")
    public void runBatch() {
        log.info("村長引き継ぎバッチ開始");

        int promoted = 0;
        int archived = 0;
        int skipped = 0;
        int failed = 0;
        int totalVillages = 0;

        final int CHUNK_SIZE = 500;
        Pageable pageable = PageRequest.of(0, CHUNK_SIZE);
        Page<VillageEntity> page;
        do {
            page = villageRepository.findByDeletedAtIsNullAndArchivedAtIsNull(pageable);
            for (VillageEntity village : page.getContent()) {
                totalVillages++;
                try {
                    SuccessionResult result = processVillage(village.getId());
                    switch (result) {
                        case PROMOTED -> promoted++;
                        case ARCHIVED -> archived++;
                        case NOT_NEEDED -> skipped++;
                    }
                } catch (Exception e) {
                    failed++;
                    log.error("村長引き継ぎ処理失敗: villageId={}", village.getId(), e);
                }
            }
            pageable = pageable.next();
        } while (page.hasNext());

        log.info("村長引き継ぎバッチ完了: 総村数={} 昇格={} 凍結={} スキップ={} 失敗={}",
                totalVillages, promoted, archived, skipped, failed);
    }

    enum SuccessionResult { PROMOTED, ARCHIVED, NOT_NEEDED }

    /**
     * 1 村について、HEADMAN 引き継ぎが必要なら実行する。
     *
     * <p>パッケージプライベートで公開しユニットテストから呼べるようにしている。</p>
     */
    @Transactional
    public SuccessionResult processVillage(java.util.UUID villageId) {
        Optional<VillageMembershipEntity> headmanOpt = membershipRepository
                .findFirstByVillageIdAndRoleAndLeftAtIsNullOrderByJoinedAtAsc(villageId, VillageRole.HEADMAN);

        // HEADMAN 不在ならまず昇格を試みる
        if (headmanOpt.isEmpty()) {
            return promoteOrArchive(villageId);
        }

        VillageMembershipEntity headman = headmanOpt.get();

        // HEADMAN が USER 名義でない場合（TEAM/ORG の HEADMAN）は退会判定対象外
        if (headman.getSubjectType() != VillageSubjectType.USER) {
            return SuccessionResult.NOT_NEEDED;
        }

        // HEADMAN ユーザーが退会済かどうかを確認。
        // Optional#map は値が null の場合 Optional.empty() を返すため
        // 「deletedAt が null → 現役」「deletedAt 非 null → 退会済」を直接判定する必要がある。
        Optional<UserEntity> userOpt = userRepository.findById(headman.getSubjectId());
        boolean headmanDeleted = userOpt.isEmpty() // user 行が消失していたら退会扱い
                || userOpt.get().getDeletedAt() != null;
        if (!headmanDeleted) {
            return SuccessionResult.NOT_NEEDED;
        }

        // 旧 HEADMAN を leftAt セットして退場させる
        headman.setLeftAt(LocalDateTime.now());
        membershipRepository.save(headman);
        log.info("村長引き継ぎ: 退会済 HEADMAN を退場: villageId={} oldHeadmanMembershipId={}",
                villageId, headman.getId());

        return promoteOrArchive(villageId);
    }

    /**
     * HEADMAN 空席状態から ELDER → VILLAGER の順で 1 名を昇格させる。
     * いずれも居なければ村を archive する。
     */
    private SuccessionResult promoteOrArchive(java.util.UUID villageId) {
        // 1. 最古参 ELDER を昇格
        Optional<VillageMembershipEntity> elder = membershipRepository
                .findFirstByVillageIdAndRoleAndLeftAtIsNullOrderByJoinedAtAsc(villageId, VillageRole.ELDER);
        if (elder.isPresent()) {
            promote(villageId, elder.get(), VillageRole.ELDER);
            return SuccessionResult.PROMOTED;
        }

        // 2. 最古参 VILLAGER を昇格
        Optional<VillageMembershipEntity> villager = membershipRepository
                .findFirstByVillageIdAndRoleAndLeftAtIsNullOrderByJoinedAtAsc(villageId, VillageRole.VILLAGER);
        if (villager.isPresent()) {
            promote(villageId, villager.get(), VillageRole.VILLAGER);
            return SuccessionResult.PROMOTED;
        }

        // 3. 誰もいない → archive
        VillageEntity village = villageRepository.findById(villageId).orElse(null);
        if (village != null) {
            village.setArchivedAt(LocalDateTime.now());
            villageRepository.save(village);
            auditLogService.record(
                    AuditEventType.VILLAGE_ARCHIVED.name(),
                    null, null, null, null,
                    null, null, null,
                    "{\"villageId\":\"" + villageId + "\",\"reason\":\"NO_MEMBERS_LEFT\"}"
            );
            log.info("村長引き継ぎ: 全員不在のため村を凍結: villageId={}", villageId);
        }
        return SuccessionResult.ARCHIVED;
    }

    /**
     * 指定メンバーシップを HEADMAN に昇格する。
     */
    private void promote(java.util.UUID villageId, VillageMembershipEntity target, VillageRole previousRole) {
        target.setRole(VillageRole.HEADMAN);
        membershipRepository.save(target);

        auditLogService.record(
                AuditEventType.VILLAGE_ROLE_GRANTED.name(),
                null,
                target.getSubjectType() == VillageSubjectType.USER ? target.getSubjectId() : null,
                null, null,
                null, null, null,
                "{\"villageId\":\"" + villageId
                        + "\",\"membershipId\":\"" + target.getId()
                        + "\",\"previousRole\":\"" + previousRole
                        + "\",\"newRole\":\"HEADMAN\""
                        + ",\"reason\":\"HEADMAN_SUCCESSION_BATCH\"}"
        );

        log.info("村長引き継ぎ: 昇格: villageId={} membershipId={} previousRole={} → HEADMAN",
                villageId, target.getId(), previousRole);
    }
}
