package com.mannschaft.app.succession.batch;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.succession.entity.UnsealRequestEntity;
import com.mannschaft.app.succession.repository.SuccessionPreRegistrationRepository;
import com.mannschaft.app.succession.repository.UnsealRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 封緘解除 72h TTL 自動再封バッチ（F09.15 S2-B）。
 *
 * <p>5 分ごとに {@code auto_reseal_at < NOW()} な未再封の {@link UnsealRequestEntity} を検索し、
 * {@link com.mannschaft.app.succession.entity.SuccessionPreRegistrationEntity#getSealStatus()} を
 * {@code RE_SEALED} に遷移させる。
 * 設計書 §9.3「自動再封バッチ」を参照。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutoResealBatchService {

    private final UnsealRequestRepository unsealRequestRepo;
    private final SuccessionPreRegistrationRepository preRegRepo;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると封緘解除された事業承継の機微情報が 72h の TTL を過ぎても再封緘されず開示されたまま残るため、復旧不能な情報露出が続く")
    @BatchEndpoint(name = "succession-auto-reseal", description = "封緘解除 72h TTL を 5 分毎にチェックし RE_SEALED へ自動遷移する")
    @Scheduled(cron = "0 */5 * * * *")
    // 起動間隔は 5 分。72h TTL 超過の封緘解除を RE_SEALED に戻すだけで対象は少数。間隔の 3 倍を上限とする。
    @SchedulerLock(name = "successionAutoReseal", lockAtLeastFor = "PT30S", lockAtMostFor = "PT15M")
    @Transactional
    public void autoReseal() {
        LocalDateTime now = LocalDateTime.now();
        List<UnsealRequestEntity> expired = unsealRequestRepo
                .findByAutoResealAtBeforeAndReSealedAtIsNullAndDeletedAtIsNull(now);

        if (expired.isEmpty()) {
            return;
        }

        log.info("自動再封バッチ開始: 対象件数={}", expired.size());
        int count = 0;

        for (UnsealRequestEntity req : expired) {
            try {
                req.setReSealedAt(now);
                unsealRequestRepo.save(req);

                UUID preRegId = req.getPreRegistrationId();
                Long orgId = req.getOrganizationId();

                preRegRepo.findByIdAndOrganizationIdAndDeletedAtIsNull(preRegId, orgId)
                        .ifPresent(preReg -> {
                            if ("UNSEALED".equals(preReg.getSealStatus())) {
                                preReg.setSealStatus("RE_SEALED");
                                preReg.setAutoResealAt(null);
                                preRegRepo.save(preReg);
                                log.info("自動再封完了: preRegId={}, organizationId={}", preRegId, orgId);
                            }
                        });
                count++;
            } catch (Exception e) {
                log.error("自動再封エラー: unsealRequestId={}, organizationId={}",
                        req.getId(), req.getOrganizationId(), e);
            }
        }

        log.info("自動再封バッチ完了: 処理件数={}/{}", count, expired.size());
    }
}
