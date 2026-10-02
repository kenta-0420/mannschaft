package com.mannschaft.app.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.dashboard.entity.ActivityFeedEntity;
import com.mannschaft.app.dashboard.repository.ActivityFeedRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F03.18: {@link ActivityFeedEntity#getDetail()}（JSON列）の永続化往復を検証する統合テスト（器のみ・第一隊）。
 *
 * <p>発行元（ScheduleService）はまだ結線されていないため、本テストは Entity/Repository 層の
 * 永続化往復（書いて読んで一致する）のみを対象とする。既存7種別との後方互換（detail=nullで
 * INSERTできること）も併せて確認する。</p>
 *
 * <p>Docker 未起動環境では {@code @EnabledIf} により全体 skip（CI で実行される）。</p>
 */
@DisplayName("ActivityFeedEntity detail列 統合テスト（F03.18 永続化往復）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ActivityFeedDetailRepositoryIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private ActivityFeedRepository activityFeedRepository;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ActivityFeedEntity.ActivityFeedEntityBuilder<?, ?> baseEntity() {
        return ActivityFeedEntity.builder()
                .scopeType(ScopeType.TEAM)
                .scopeId(10L)
                .actorId(1L)
                .targetType(TargetType.SCHEDULE)
                .targetId(12345L)
                .summary("予定「定例会議」の日程を変更しました");
    }

    @Test
    @DisplayName("正常系: detailにJSON文字列を書き込み、読み直しても構造として同一の値が返る")
    void detail_writeAndRead_roundTrip() throws Exception {
        // Given
        String detailJson = "{\"scheduleId\":12345,\"title\":\"定例会議\","
                + "\"fields\":[{\"field\":\"startAt\",\"before\":\"2026-08-10T19:00:00\",\"after\":\"2026-08-17T19:00:00\"}],"
                + "\"affectedCount\":1}";
        ActivityFeedEntity entity = baseEntity()
                .activityType(ActivityType.SCHEDULE_RESCHEDULED)
                .detail(detailJson)
                .build();

        // When
        ActivityFeedEntity saved = activityFeedRepository.saveAndFlush(entity);
        activityFeedRepository.flush();
        Optional<ActivityFeedEntity> reloaded = activityFeedRepository.findById(saved.getId());

        // Then
        // MySQL の JSON 型は正規化して保存する（キー順序・空白が変わりうる）ため、
        // 文字列の完全一致ではなく構造として同一であることを検証する。
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getDetail()).isNotNull();
        assertThat(OBJECT_MAPPER.readTree(reloaded.get().getDetail()))
                .isEqualTo(OBJECT_MAPPER.readTree(detailJson));
        assertThat(reloaded.get().getActivityType()).isEqualTo(ActivityType.SCHEDULE_RESCHEDULED);
    }

    @Test
    @DisplayName("正常系: 既存種別はdetail=nullのまま書き込める（後方互換）")
    void detail_nullForExistingActivityType_backwardCompatible() {
        // Given
        ActivityFeedEntity entity = baseEntity()
                .targetType(TargetType.TIMELINE_POST)
                .activityType(ActivityType.POST_CREATED)
                .build();

        // When
        ActivityFeedEntity saved = activityFeedRepository.saveAndFlush(entity);
        Optional<ActivityFeedEntity> reloaded = activityFeedRepository.findById(saved.getId());

        // Then
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getDetail()).isNull();
    }
}
