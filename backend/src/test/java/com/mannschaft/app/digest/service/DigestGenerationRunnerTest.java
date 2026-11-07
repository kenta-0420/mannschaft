package com.mannschaft.app.digest.service;

import com.mannschaft.app.digest.DigestScopeType;
import com.mannschaft.app.digest.DigestStatus;
import com.mannschaft.app.digest.DigestStyle;
import com.mannschaft.app.digest.entity.TimelineDigestEntity;
import com.mannschaft.app.digest.event.DigestGenerationNotificationEvent;
import com.mannschaft.app.digest.repository.TimelineDigestRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Issue #2997 G7-a: {@link DigestGenerationRunner} のユニットテスト。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DigestGenerationRunner ユニットテスト")
class DigestGenerationRunnerTest {

    @Mock private TimelineDigestRepository digestRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @InjectMocks private DigestGenerationRunner runner;

    private static final DigestAiProvider.AiDigestResult RESULT =
            new DigestAiProvider.AiDigestResult("題", "本文", "抜粋", "m", 1, 2);

    private static TimelineDigestEntity digest(DigestStatus status, Long triggeredBy) {
        return TimelineDigestEntity.builder()
                .scopeType(DigestScopeType.TEAM).scopeId(50L)
                .periodStart(LocalDateTime.now().minusDays(7)).periodEnd(LocalDateTime.now())
                .digestStyle(DigestStyle.NARRATIVE).status(status).triggeredBy(triggeredBy).build();
    }

    @Test
    @DisplayName("complete: GENERATED に確定し、ソース投稿IDを保存し、完了通知の配送要求を publish する")
    void complete_確定してpublishする() {
        TimelineDigestEntity d = digest(DigestStatus.GENERATING, 100L);
        given(digestRepository.findById(7L)).willReturn(Optional.of(d));

        runner.complete(7L, RESULT, List.of(1L, 2L), 2, "TEAM", 50L);

        assertThat(d.getStatus()).isEqualTo(DigestStatus.GENERATED);
        assertThat(d.getSourcePostIds()).isEqualTo("[1,2]");
        verify(digestRepository).save(d);
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        DigestGenerationNotificationEvent e = (DigestGenerationNotificationEvent) captor.getValue();
        assertThat(e.kind()).isEqualTo(DigestGenerationNotificationEvent.Kind.COMPLETED);
        assertThat(e.triggeredBy()).isEqualTo(100L);
        assertThat(e.scopeType()).isEqualTo("TEAM");
        assertThat(e.scopeId()).isEqualTo(50L);
    }

    @Test
    @DisplayName("complete: triggeredBy が null なら GENERATED には確定するが publish しない")
    void complete_起動者不明ならpublishしない() {
        TimelineDigestEntity d = digest(DigestStatus.GENERATING, null);
        given(digestRepository.findById(7L)).willReturn(Optional.of(d));

        runner.complete(7L, RESULT, List.of(), 0, "TEAM", 50L);

        assertThat(d.getStatus()).isEqualTo(DigestStatus.GENERATED);
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("complete: ダイジェストが無ければ例外（呼び出し側が失敗として扱う）")
    void complete_不存在は例外() {
        given(digestRepository.findById(7L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> runner.complete(7L, RESULT, List.of(), 0, "TEAM", 50L))
                .isInstanceOf(IllegalStateException.class);
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("fail: GENERATING なら FAILED に確定し、失敗通知の配送要求を publish する")
    void fail_確定してpublishする() {
        TimelineDigestEntity d = digest(DigestStatus.GENERATING, 100L);
        given(digestRepository.findById(7L)).willReturn(Optional.of(d));

        boolean transitioned = runner.fail(7L, "AI 障害", "TEAM", 50L);

        assertThat(transitioned).isTrue();
        assertThat(d.getStatus()).isEqualTo(DigestStatus.FAILED);
        assertThat(d.getErrorMessage()).isEqualTo("AI 障害");
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(((DigestGenerationNotificationEvent) captor.getValue()).kind())
                .isEqualTo(DigestGenerationNotificationEvent.Kind.FAILED);
    }

    @Test
    @DisplayName("fail: すでに GENERATED に確定済みなら何もしない（確定済みの結果を FAILED で上書きしない）")
    void fail_確定済みなら何もしない() {
        TimelineDigestEntity d = digest(DigestStatus.GENERATED, 100L);
        given(digestRepository.findById(7L)).willReturn(Optional.of(d));

        boolean transitioned = runner.fail(7L, "後続処理の例外", "TEAM", 50L);

        assertThat(transitioned).isFalse();
        assertThat(d.getStatus()).isEqualTo(DigestStatus.GENERATED);
        verify(digestRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("fail: ダイジェストが無ければ何もしない")
    void fail_不存在なら何もしない() {
        given(digestRepository.findById(7L)).willReturn(Optional.empty());

        assertThat(runner.fail(7L, "x", "TEAM", 50L)).isFalse();
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("fail: triggeredBy が null なら FAILED には確定するが publish しない")
    void fail_起動者不明ならpublishしない() {
        TimelineDigestEntity d = digest(DigestStatus.GENERATING, null);
        given(digestRepository.findById(7L)).willReturn(Optional.of(d));

        assertThat(runner.fail(7L, "x", "TEAM", 50L)).isTrue();
        assertThat(d.getStatus()).isEqualTo(DigestStatus.FAILED);
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }
}
