package com.mannschaft.app.securityincident.service;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.mail.outbox.EmailOutboxRequest;
import com.mannschaft.app.mail.outbox.EmailOutboxService;
import com.mannschaft.app.mail.outbox.EmailTemplateKind;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.securityincident.entity.SecurityIncidentEntity;
import com.mannschaft.app.securityincident.repository.SecurityIncidentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * セキュリティインシデント 70 時間アラートバッチ。
 *
 * <p>GDPR Article 33 では、個人データ漏洩等のインシデントは検知から 72 時間以内に
 * DPA（監督機関）へ通知する義務がある。余裕を持って 70 時間超過を警告する。</p>
 *
 * <p>30 分ごとに実行し、{@code detected_at + 70h} を超えて OPEN/INVESTIGATING のまま
 * DPA 通知未記録のインシデントがあれば、全 SYSTEM_ADMIN にアラートメールを送信する。</p>
 *
 * <p>TODO: securityincident ドメインが role / auth / mail ドメインをまたいでいる。
 * 将来は SecurityIncident70hExceededEvent で各ドメインを分離する候補。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SecurityIncident70hAlertBatchService {

    private final SecurityIncidentRepository securityIncidentRepository;
    private final UserRoleRepository userRoleRepository;
    private final UserRepository userRepository;
    private final EmailOutboxService emailOutboxService;

    /**
     * 70 時間アラートチェックを実行する（30 分ごと）。
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "GDPR 72時間報告義務の2時間前アラートであり、止めると報告期限が無警告で徒過して法令違反が確定する")
    @BatchEndpoint(
            name = "security-incident-70h-alert",
            description = "セキュリティインシデントの70時間アラートを SYSTEM_ADMIN に送信する"
    )
    @Scheduled(cron = "0 */30 * * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "security_incident_70h_alert", lockAtMostFor = "PT60M", lockAtLeastFor = "PT1M")
    @Transactional(readOnly = true)
    public void checkAndAlert() {
        LocalDateTime threshold = LocalDateTime.now().minusHours(70);
        List<SecurityIncidentEntity> targets = securityIncidentRepository.findAlertTargets(threshold);

        if (targets.isEmpty()) {
            return;
        }

        log.warn("[GDPR_ALERT] セキュリティインシデント70時間アラート対象: {}件", targets.size());

        // SYSTEM_ADMIN ユーザーのメールアドレスを取得
        List<Long> adminUserIds = userRoleRepository.findSystemAdminUserIds();
        if (adminUserIds.isEmpty()) {
            log.error("[GDPR_ALERT] SYSTEM_ADMIN ユーザーが存在しないためアラートメールを送信できません");
            return;
        }

        List<UserEntity> adminUsers = userRepository.findByIdIn(adminUserIds);

        targets.forEach(incident -> {
            long hoursElapsed = ChronoUnit.HOURS.between(incident.getDetectedAt(), LocalDateTime.now());
            log.error("[GDPR_ALERT] 【要対応】GDPR 72h 通知期限が近いインシデント: id={}, type={}, severity={}, detectedAt={}, 経過時間={}時間",
                    incident.getId(), incident.getIncidentType(), incident.getSeverity(),
                    incident.getDetectedAt(), hoursElapsed);

            adminUsers.forEach(admin -> sendAlertEmail(admin, incident, hoursElapsed));
        });
    }

    private void sendAlertEmail(UserEntity admin, SecurityIncidentEntity incident, long hoursElapsed) {
        try {
            emailOutboxService.enqueue(new EmailOutboxRequest(
                    EmailTemplateKind.ERROR_REPORT_WEEKLY.name(),
                    "ja",
                    admin.getEmail(),
                    Map.of(
                            "incidentId", incident.getId().toString(),
                            "incidentType", incident.getIncidentType().name(),
                            "severity", incident.getSeverity().name(),
                            "detectedAt", incident.getDetectedAt().toString(),
                            "hoursElapsed", String.valueOf(hoursElapsed),
                            "status", incident.getStatus().name(),
                            "subject", "【緊急】GDPR 72時間通知期限が近いセキュリティインシデントがあります"
                    ),
                    "securityincident",
                    incident.getId().toString(),
                    null,
                    admin.getId(),
                    null
            ));
        } catch (Exception e) {
            // メール送信失敗は握りつぶさずログに記録する（アラート自体は継続する）
            log.error("[GDPR_ALERT] アラートメール enqueue 失敗: adminId={}, incidentId={}",
                    admin.getId(), incident.getId(), e);
        }
    }
}
