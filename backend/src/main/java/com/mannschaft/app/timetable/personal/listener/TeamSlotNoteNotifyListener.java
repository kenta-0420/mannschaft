package com.mannschaft.app.timetable.personal.listener;

import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.timetable.event.TimetableSlotNoteUpdatedEvent;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableSettingsEntity;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableSettingsRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * F03.15 Phase 4: チーム時間割コマの共通メモが更新されたときに、
 * チームメンバー個別の通知設定を確認のうえ F04.3 経由でプッシュ通知するリスナー。
 *
 * <p>設計書 §5.3 を参照。</p>
 *
 * <ul>
 *   <li><b>5分デバウンス</b>: 同一 slot に対する連続更新は最後の更新から 5 分後に 1 度だけ通知する。
 *       Valkey の単純キー (TTL 5分) で「直近通知済み」を表現し、TTL 内に再発火が来たら通知抑制。</li>
 *   <li>notes が NULL/空文字なら通知しない（削除＝重要度低）。</li>
 *   <li>Valkey が利用不可の場合は安全側でデバウンスをスキップ（毎回通知）するのではなく、デバウンス無し
 *       で送信する＝障害時のフォールバックは「常に通知」。</li>
 * </ul>
 */
@Slf4j
@Component
public class TeamSlotNoteNotifyListener {

    private static final String DEBOUNCE_KEY_PREFIX = "mannschaft:f0315:slot_note_debounce:";
    private static final Duration DEBOUNCE_TTL = Duration.ofMinutes(5);

    private final UserRoleRepository userRoleRepository;
    private final PersonalTimetableSettingsRepository settingsRepository;
    @Autowired(required = false)
    private NotificationHelper notificationHelper;
    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    public TeamSlotNoteNotifyListener(UserRoleRepository userRoleRepository,
                                      PersonalTimetableSettingsRepository settingsRepository) {
        this.userRoleRepository = userRoleRepository;
        this.settingsRepository = settingsRepository;
    }

    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onSlotNoteUpdated(TimetableSlotNoteUpdatedEvent event) {
        try {
            if (event.getNotes() == null || event.getNotes().isBlank()) {
                return;
            }
            if (event.getTeamId() == null) {
                log.debug("teamId が null のため通知抑制: slotId={}", event.getSlotId());
                return;
            }

            // デバウンス判定
            if (isDebounced(event.getSlotId())) {
                log.debug("直近 5 分以内に通知済みのためスキップ: slotId={}", event.getSlotId());
                return;
            }

            List<Long> memberIds = userRoleRepository.findUserIdsByScope("TEAM", event.getTeamId());
            String body = truncate(event.getNotes(), 100);

            for (Long memberId : memberIds) {
                Optional<PersonalTimetableSettingsEntity> settingsOpt =
                        settingsRepository.findById(memberId);
                boolean enabled = settingsOpt
                        .map(PersonalTimetableSettingsEntity::getNotifyTeamSlotNoteUpdates)
                        .orElse(true);
                if (!enabled) continue;
                if (notificationHelper == null) continue;

                try {
                    notificationHelper.notify(
                            memberId,
                            "TEAM_SLOT_NOTE_UPDATED",
                            event.getSubjectName() != null
                                    ? event.getSubjectName() + " の共通メモが更新されました"
                                    : "コマの共通メモが更新されました",
                            body,
                            "TIMETABLE_SLOT", event.getSlotId(),
                            NotificationScopeType.TEAM, event.getTeamId(),
                            "/teams/" + event.getTeamId() + "/timetables/" + event.getTimetableId(),
                            null);
                } catch (Exception ex) {
                    log.warn("コマメモ通知失敗（継続）: userId={}, slotId={}, error={}",
                            memberId, event.getSlotId(), ex.getMessage());
                }
            }

            markDebounced(event.getSlotId());
            log.info("コマ共通メモ更新通知を配信: slotId={}, teamId={}, recipients={}",
                    event.getSlotId(), event.getTeamId(), memberIds.size());
        } catch (Exception ex) {
            log.error("TeamSlotNoteNotifyListener 失敗: slotId={}, error={}",
                    event.getSlotId(), ex.getMessage(), ex);
        }
    }

    private boolean isDebounced(Long slotId) {
        if (redisTemplate == null) return false;
        try {
            Boolean has = redisTemplate.hasKey(DEBOUNCE_KEY_PREFIX + slotId);
            return Boolean.TRUE.equals(has);
        } catch (Exception ex) {
            log.warn("Valkey 接続失敗のためデバウンス無効化: {}", ex.getMessage());
            return false;
        }
    }

    private void markDebounced(Long slotId) {
        if (redisTemplate == null) return;
        try {
            redisTemplate.opsForValue().set(
                    DEBOUNCE_KEY_PREFIX + slotId, "1", DEBOUNCE_TTL);
        } catch (Exception ex) {
            log.warn("Valkey マーキング失敗（通知は配信済み）: {}", ex.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
