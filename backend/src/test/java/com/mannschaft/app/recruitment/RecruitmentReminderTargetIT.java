package com.mannschaft.app.recruitment;

import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentReminderEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentReminderRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 募集リマインダーの<b>対象抽出</b>の契約テスト（Gate 基盤工事④-B / Codex 検分 P2 の根治）。
 *
 * <h2>何を守るテストか</h2>
 * <p>{@code RecruitmentReminderRepository} の初版は
 * {@code sentAt IS NULL AND remindAt <= now} という<b>上限だけ</b>で絞っており、下限が無かった。
 * そのためバッチが数日走らなかっただけで、再開時に
 * <b>既に開始・終了した募集にまで「24時間後に開催されます」を最大 100 件/分で送り出す</b>。</p>
 *
 * <p>これは機能フラグとは無関係に存在する欠陥であり、障害で一日止まっただけでも起きる。
 * さらに本 PR は同バッチに {@code SKIP_WHEN_DISABLED} を付けて<b>長期停止という手段を与えた</b>ため、
 * 潜在バグを「この PR が作った一斉送信経路」に変えていた。よって本 PR で根治する。</p>
 *
 * <p>下限は「開始時刻がまだ未来であること」に取った。通知文面が「24時間後に開催」であり、
 * <b>開始済みの募集に送る意味が原理的に無い</b>ためで、恣意的な猶予時間の定数を置かずに済む。</p>
 *
 * <p>注: 注釈構成は同パッケージの既存 IT（{@code RecruitmentScopeContractIT} 等）と揃えてある。
 * {@code @AutoConfigureMockMvc} は Spring TestContext のキャッシュキーに効くため、
 * これを外すと本クラス専用のコンテキストが増えてヒープを圧迫する。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("募集リマインダー: 既に開始した募集は送信対象に入らない")
class RecruitmentReminderTargetIT extends AbstractMySqlIntegrationTest {

    private static final long SCOPE_ID = 980_101L;
    private static final long CATEGORY_ID = 980_102L;
    private static final long CREATED_BY = 980_103L;

    @Autowired
    private RecruitmentReminderRepository reminderRepository;

    @Autowired
    private RecruitmentListingRepository listingRepository;

    private Long insertListing(String title, LocalDateTime startAt) {
        return listingRepository.save(RecruitmentListingEntity.builder()
                .scopeType(com.mannschaft.app.recruitment.RecruitmentScopeType.TEAM)
                .scopeId(SCOPE_ID)
                .categoryId(CATEGORY_ID)
                .title(title)
                .participationType(com.mannschaft.app.recruitment.RecruitmentParticipationType.INDIVIDUAL)
                .startAt(startAt)
                .endAt(startAt.plusHours(2))
                .applicationDeadline(startAt.minusDays(1))
                .autoCancelAt(startAt.minusDays(2))
                .capacity(10)
                .minCapacity(1)
                .status(com.mannschaft.app.recruitment.RecruitmentListingStatus.OPEN)
                .createdBy(CREATED_BY)
                .build()).getId();
    }

    private Long insertDueReminder(Long listingId, LocalDateTime remindAt) {
        return reminderRepository.save(RecruitmentReminderEntity.builder()
                .listingId(listingId)
                .participantId(980_200L)
                .remindAt(remindAt)
                .build()).getId();
    }

    @Test
    @DisplayName("開始済みの募集のリマインダーは取得されず、開始前のものだけが返る")
    void 開始済みは送信対象に入らない() {
        LocalDateTime now = LocalDateTime.now();

        // 既に開始した募集（＝停止明けに拾ってはならない）。remindAt はとうに過ぎている。
        Long startedListing = insertListing("開始済み・3日前に開始", now.minusDays(3));
        Long staleReminder = insertDueReminder(startedListing, now.minusDays(4));

        // まだ開始していない募集（＝正しく送るべき）。remindAt は過ぎている＝送信時刻到来。
        Long upcomingListing = insertListing("開始前・20時間後に開始", now.plusHours(20));
        Long liveReminder = insertDueReminder(upcomingListing, now.minusMinutes(5));

        List<Long> targetIds = reminderRepository
                .findSendableReminders(now, PageRequest.of(0, 100))
                .stream()
                .map(RecruitmentReminderEntity::getId)
                .toList();

        assertThat(targetIds)
                .as("既に開始した募集へ「24時間後に開催されます」を送ってはならない")
                .doesNotContain(staleReminder);
        assertThat(targetIds)
                .as("まだ開始していない募集のリマインダーは従来どおり送信対象に残らねばならない")
                .contains(liveReminder);
    }
}
