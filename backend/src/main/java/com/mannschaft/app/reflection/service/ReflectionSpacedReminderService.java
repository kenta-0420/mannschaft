package com.mannschaft.app.reflection.service;

import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.reflection.entity.ReflectionEntryEntity;
import com.mannschaft.app.reflection.entity.ReflectionThemeEntity;
import com.mannschaft.app.reflection.repository.ReflectionSpacedReminderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * 間隔反復リマインダーの生成・キャンセル・送信のサービス（F06.5・§5）。
 *
 * <p><b>第二陣スケルトン</b>: シグネチャ・依存注入のみ確定。本体ロジック（remind_at のユーザー TZ 織り込み生成＝
 * §5.3・PENDING 上限検証＝§2.5.1(a)・PRE_EXAM 14/7/3/1 日前生成＋過去日ガード＝§5.5・FORGOT 翌日 SPACED 再生成＝
 * AC-22・due 走査＋孤児 fail-safe＋status 遷移送信＝§5.2/AC-10）は次陣（試練 red→出陣 green）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReflectionSpacedReminderService {

    private final ReflectionSpacedReminderRepository reflectionSpacedReminderRepository;
    private final NotificationHelper notificationHelper;

    /** エントリ保存時に SPACED 行（1/3/7/14 日後）を生成する（§5.3・AC-9）。 */
    public void generateSpacedReminders(ReflectionEntryEntity entry, ReflectionThemeEntity theme) {
        throw new UnsupportedOperationException("F06.5 未実装: §5.3 SPACED リマインダ生成");
    }

    /** FORGOT 時に翌日（recall_date+1）の SPACED 行を追加生成する（§3.1・AC-22）。 */
    public void scheduleNextDaySpacedReminder(ReflectionEntryEntity entry, LocalDate recallDate) {
        throw new UnsupportedOperationException("F06.5 未実装: §3.1 FORGOT 翌日 SPACED 再生成");
    }

    /** exam_date 設定時に PRE_EXAM 行（14/7/3/1 日前）を生成する（過去日ガード・§5.5・AC-12）。 */
    public void generatePreExamReminders(ReflectionThemeEntity theme) {
        throw new UnsupportedOperationException("F06.5 未実装: §5.5 PRE_EXAM リマインダ生成");
    }

    /** エントリ削除/復活・テーマ削除時に当該由来の PENDING 行を CANCELLED 化する（§5.5）。 */
    public void cancelPendingForEntry(java.util.UUID entryId) {
        throw new UnsupportedOperationException("F06.5 未実装: §5.5 エントリ由来 PENDING キャンセル");
    }

    /** exam_date 変更・テーマ削除時に PRE_EXAM の PENDING 行を CANCELLED 化する（§5.5）。 */
    public void cancelPendingPreExamForTheme(java.util.UUID themeId) {
        throw new UnsupportedOperationException("F06.5 未実装: §5.5 テーマ由来 PRE_EXAM キャンセル");
    }

    /** ユーザーの PENDING リマインダー総数（§2.5.1(a) 上限判定）。 */
    public long countPendingReminders(Long userId) {
        throw new UnsupportedOperationException("F06.5 未実装: §2.5.1 PENDING 総数カウント");
    }

    /** due（remind_at<=now・PENDING）を走査し、孤児 fail-safe＋status 遷移で送信する（§5.2・AC-10）。 */
    public void processDueReminders() {
        throw new UnsupportedOperationException("F06.5 未実装: §5.2 due リマインダ送信");
    }
}
