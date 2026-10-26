package com.mannschaft.app.timetable.personal.listener;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.timetable.event.TimetableSlotNoteUpdatedEvent;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableSettingsEntity;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableSettingsRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * F03.15 Phase 4: チーム時間割コマの共通メモが更新されたときに、
 * チームメンバー個別の通知設定を確認のうえ F04.3 経由でプッシュ通知するリスナー。
 *
 * <p>設計書 §5.3 を参照。</p>
 *
 * <h2>トランザクション境界（Issue #2997 / CMP-260827-1152 G7-a）</h2>
 * <p>是正前は同期 {@code @EventListener} + {@code REQUIRES_NEW(readOnly = true)} で、業務TX
 * （{@code TimetableSlotService#replaceSlots}）が commit する前に、しかも readOnly のTXの内側で通知を作っていた。
 * 業務TXが rollback してもメンバーには通知が届き、readOnly 配下での INSERT 可否は処理系依存だった。
 * 現在は {@code AFTER_COMMIT} + {@code @Async("event-pool")} とし、受信者ごとに
 * {@code NotificationDeliveryRunner#sendOne}（{@code REQUIRES_NEW}）で配送する。このメソッド自身はTXを持たない。
 * 業務TXが rollback すれば通知は 1 件も作られない。</p>
 *
 * <h2>5分デバウンス（M8）</h2>
 * <ul>
 *   <li>キー: {@code mannschaft:f0315:slot_note_debounce:{timetableId}:{dayOfWeek}:{periodNumber}:{weekPattern}}。
 *       {@code replaceSlots} はスロットを毎回削除再作成して {@code slotId} が変わるため、コマの<b>位置</b>で抑止する
 *       （是正前の slotId キーでは同じコマを連続更新してもデバウンスが一度も効かなかった）。</li>
 *   <li>取得: 値に UUID トークンを入れた {@code SET NX EX 300}。取得できなければ直近 5 分以内に通知済み（または
 *       並行する別の発火が担当中）なので何もしない。並行する 2 発火でも配送は 1 回だけになる。</li>
 *   <li>解放: Lua で「値がトークンと一致するときだけ DEL」。TTL を跨いで遅れて来た解放が、
 *       後から別の発火が取り直した新しい印を消さない。</li>
 *   <li>配送結果と印: 全員が例外で失敗した、または配送対象が 0 人だったときは印を解放する（次の更新で再び通知される）。
 *       1 人以上に配送できた、または visibility deny があった場合は印を残す（成功済みにも失敗した受信者にも再送しない）。</li>
 *   <li>Valkey が使えないときはデバウンスを諦めて毎回通知する（障害時のフォールバックは「常に通知」）。</li>
 * </ul>
 * <p>notes が NULL/空文字なら通知しない（削除＝重要度低）。</p>
 *
 * <p><b>既知の限界（M9）</b>: {@code event-pool} が飽和して投入が拒否された場合（{@code AbortPolicy}）、
 * その回の通知は失われる（業務TXは commit 済みで、デバウンスの印も立っていない）。</p>
 */
@Slf4j
@Component
public class TeamSlotNoteNotifyListener {

    private static final String DEBOUNCE_KEY_PREFIX = "mannschaft:f0315:slot_note_debounce:";
    private static final Duration DEBOUNCE_TTL = Duration.ofMinutes(5);

    /** トークン一致時のみ削除する解放用 Lua。 */
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final UserRoleRepository userRoleRepository;
    private final PersonalTimetableSettingsRepository settingsRepository;
    /** Issue #2715 ロットB: 受信者 locale の解決（D-5: auth の UserRepository を直接呼ばない）。 */
    private final UserLocaleCache userLocaleCache;
    private final MessageSource messageSource;
    private final NotificationDeliveryRunner notificationDeliveryRunner;
    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    public TeamSlotNoteNotifyListener(UserRoleRepository userRoleRepository,
                                      PersonalTimetableSettingsRepository settingsRepository,
                                      UserLocaleCache userLocaleCache,
                                      MessageSource messageSource,
                                      NotificationDeliveryRunner notificationDeliveryRunner) {
        this.userRoleRepository = userRoleRepository;
        this.settingsRepository = settingsRepository;
        this.userLocaleCache = userLocaleCache;
        this.messageSource = messageSource;
        this.notificationDeliveryRunner = notificationDeliveryRunner;
    }

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。チーム時間割コマのメモ更新の通知。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSlotNoteUpdated(TimetableSlotNoteUpdatedEvent event) {
        if (event.getNotes() == null || event.getNotes().isBlank()) {
            return;
        }
        if (event.getTeamId() == null) {
            log.debug("teamId が null のため通知抑制: slotId={}", event.getSlotId());
            return;
        }

        String key = debounceKey(event);
        Debounce debounce = acquireDebounce(key);
        if (debounce.skipped()) {
            log.debug("直近 5 分以内に通知済み（または別の発火が担当中）のためスキップ: key={}", key);
            return;
        }

        try {
            deliver(event, key, debounce);
        } catch (Exception ex) {
            // 配送の準備（メンバー取得等）で失敗した。誰にも配送していないので印を解放して次の更新に委ねる。
            log.error("TeamSlotNoteNotifyListener 失敗: slotId={}, error={}",
                    event.getSlotId(), ex.getMessage(), ex);
            releaseDebounce(key, debounce);
        }
    }

    private void deliver(TimetableSlotNoteUpdatedEvent event, String key, Debounce debounce) {
        List<Long> memberIds = userRoleRepository.findUserIdsByScope("TEAM", event.getTeamId());
        String body = truncate(event.getNotes(), 100);

        // locale を一括解決（N+1 防止。Issue #2715 ロットB）。解決自体の失敗は既定 locale で継続する。
        Map<Long, String> locales;
        try {
            locales = userLocaleCache.getLocales(memberIds);
        } catch (Exception e) {
            log.warn("コマメモ通知の locale 一括解決に失敗（既定 locale で継続）: slotId={}, error={}",
                    event.getSlotId(), e.getMessage());
            locales = Map.of();
        }

        int delivered = 0;
        int denied = 0;
        int failed = 0;
        for (Long memberId : memberIds) {
            try {
                Optional<PersonalTimetableSettingsEntity> settingsOpt = settingsRepository.findById(memberId);
                boolean enabled = settingsOpt
                        .map(PersonalTimetableSettingsEntity::getNotifyTeamSlotNoteUpdates)
                        .orElse(true);
                if (!enabled) continue;

                Locale locale = Locale.forLanguageTag(locales.getOrDefault(memberId, "ja"));
                NotificationDeliveryResult result = notificationDeliveryRunner.sendOne(
                        buildRequest(event, memberId, body, locale));
                if (result == NotificationDeliveryResult.VISIBILITY_DENIED) {
                    denied++;
                    log.warn("コマメモ通知が visibility deny によりスキップされました: userId={}, slotId={}",
                            memberId, event.getSlotId());
                } else {
                    delivered++;
                }
            } catch (Exception ex) {
                failed++;
                log.warn("コマメモ通知失敗（継続）: userId={}, slotId={}, error={}",
                        memberId, event.getSlotId(), ex.getMessage());
            }
        }

        if (delivered == 0 && denied == 0) {
            // 配送対象が 0 人、または全員が例外で失敗した。次の更新で再び通知されるよう印を解放する。
            releaseDebounce(key, debounce);
        }
        log.info("コマ共通メモ更新通知を配信: slotId={}, teamId={}, members={}, delivered={}, denied={}, failed={}",
                event.getSlotId(), event.getTeamId(), memberIds.size(), delivered, denied, failed);
    }

    private NotificationDeliveryRequest buildRequest(TimetableSlotNoteUpdatedEvent event, Long memberId,
                                                     String body, Locale locale) {
        return new NotificationDeliveryRequest(
                memberId,
                "TEAM_SLOT_NOTE_UPDATED",
                NotificationPriority.NORMAL,
                buildTitle(event.getSubjectName(), locale),
                body,
                "TIMETABLE_SLOT", event.getSlotId(),
                NotificationScopeType.TEAM, event.getTeamId(),
                "/teams/" + event.getTeamId() + "/timetables/" + event.getTimetableId(),
                null);
    }

    private static String debounceKey(TimetableSlotNoteUpdatedEvent event) {
        return DEBOUNCE_KEY_PREFIX + event.getTimetableId()
                + ":" + event.getDayOfWeek()
                + ":" + event.getPeriodNumber()
                + ":" + (event.getWeekPattern() != null ? event.getWeekPattern().name() : "EVERY");
    }

    /** デバウンスの印を取得する（{@code SET NX EX}）。Valkey が使えないときは印なしで通知を続行する。 */
    private Debounce acquireDebounce(String key) {
        if (redisTemplate == null) return Debounce.unavailable();
        try {
            String token = UUID.randomUUID().toString();
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, token, DEBOUNCE_TTL);
            if (acquired == null) {
                return Debounce.unavailable();
            }
            return acquired ? Debounce.acquired(token) : Debounce.skip();
        } catch (Exception ex) {
            log.warn("Valkey 接続失敗のためデバウンス無効化（毎回通知）: {}", ex.getMessage());
            return Debounce.unavailable();
        }
    }

    /** 自分が取った印だけを解放する（トークン一致時のみ DEL）。失敗は WARN のみ（TTL で自然に消える）。 */
    private void releaseDebounce(String key, Debounce debounce) {
        if (redisTemplate == null || debounce.token() == null) return;
        try {
            redisTemplate.execute(RELEASE_SCRIPT, List.of(key), debounce.token());
        } catch (Exception ex) {
            log.warn("デバウンスの印の解放に失敗（TTL で失効する）: key={}, error={}", key, ex.getMessage());
        }
    }

    private String buildTitle(String subjectName, Locale locale) {
        if (messageSource == null) {
            return subjectName != null
                    ? subjectName + " の共通メモが更新されました"
                    : "コマの共通メモが更新されました";
        }
        if (subjectName != null) {
            return messageSource.getMessage(
                    "notification.timetable.slotNote.title.withSubject",
                    new Object[]{subjectName}, subjectName + " の共通メモが更新されました", locale);
        }
        return messageSource.getMessage(
                "notification.timetable.slotNote.title.default", null,
                "コマの共通メモが更新されました", locale);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /** デバウンス判定の結果。{@code skipped} なら配送しない。{@code token} が null の場合は印を持たない（Valkey 不可）。 */
    private record Debounce(boolean skipped, String token) {
        static Debounce skip() {
            return new Debounce(true, null);
        }

        static Debounce acquired(String token) {
            return new Debounce(false, token);
        }

        static Debounce unavailable() {
            return new Debounce(false, null);
        }
    }
}
