package com.mannschaft.app.errorreport.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.errorreport.ErrorReportActivityType;
import com.mannschaft.app.errorreport.entity.ErrorReportActivityEntity;
import com.mannschaft.app.errorreport.repository.ErrorReportActivityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * F12.5 Phase 2 — {@link ErrorReportActivityService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ErrorReportActivityService 単体テスト")
class ErrorReportActivityServiceTest {

    @Mock
    private ErrorReportActivityRepository repository;

    @InjectMocks
    private ErrorReportActivityService service;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        // @InjectMocks では Mock 以外のフィールドは注入されないため、リフレクションで設定
        java.lang.reflect.Field field = ErrorReportActivityService.class.getDeclaredField("objectMapper");
        field.setAccessible(true);
        field.set(service, objectMapper);
    }

    @Test
    @DisplayName("record: actorId / type / content / metadataJson が正しく保存される")
    void record_savesEntityWithSerializedMetadata() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("from", "INVESTIGATING");
        metadata.put("to", "RESOLVED");

        service.record(100L, 5L, ErrorReportActivityType.WORKFLOW_CHANGED, "ノート", metadata);

        ArgumentCaptor<ErrorReportActivityEntity> captor =
                ArgumentCaptor.forClass(ErrorReportActivityEntity.class);
        verify(repository).save(captor.capture());

        ErrorReportActivityEntity saved = captor.getValue();
        assertThat(saved.getErrorReportId()).isEqualTo(100L);
        assertThat(saved.getActorId()).isEqualTo(5L);
        assertThat(saved.getActivityType()).isEqualTo(ErrorReportActivityType.WORKFLOW_CHANGED);
        assertThat(saved.getContent()).isEqualTo("ノート");
        assertThat(saved.getMetadataJson()).contains("\"from\":\"INVESTIGATING\"");
        assertThat(saved.getMetadataJson()).contains("\"to\":\"RESOLVED\"");
    }

    @Test
    @DisplayName("record: metadata が null の場合は metadataJson も null になる")
    void record_nullMetadataYieldsNullJson() {
        service.record(1L, null, ErrorReportActivityType.COMMENT_ADDED, "コメント", null);

        ArgumentCaptor<ErrorReportActivityEntity> captor =
                ArgumentCaptor.forClass(ErrorReportActivityEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getMetadataJson()).isNull();
    }

    @Test
    @DisplayName("record: content が 2000 文字を超える場合は切り詰められる")
    void record_truncatesContent() {
        String longContent = "あ".repeat(2500);
        service.record(1L, 1L, ErrorReportActivityType.COMMENT_ADDED, longContent, null);

        ArgumentCaptor<ErrorReportActivityEntity> captor =
                ArgumentCaptor.forClass(ErrorReportActivityEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getContent()).hasSize(2000);
    }

    @Test
    @DisplayName("recordSystemActivity: metadata.system=true が自動付与され actorId=null になる")
    void recordSystemActivity_setsSystemFlag() {
        service.recordSystemActivity(50L, ErrorReportActivityType.AI_ANALYZED, null);

        ArgumentCaptor<ErrorReportActivityEntity> captor =
                ArgumentCaptor.forClass(ErrorReportActivityEntity.class);
        verify(repository).save(captor.capture());

        ErrorReportActivityEntity saved = captor.getValue();
        assertThat(saved.getActorId()).isNull();
        assertThat(saved.getActivityType()).isEqualTo(ErrorReportActivityType.AI_ANALYZED);
        assertThat(saved.getMetadataJson()).contains("\"system\":true");
    }
}
