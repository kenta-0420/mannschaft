package com.mannschaft.app.seal;

import com.mannschaft.app.seal.dto.ScopeDefaultResponse;
import com.mannschaft.app.seal.dto.SealResponse;
import com.mannschaft.app.seal.dto.StampLogResponse;
import com.mannschaft.app.seal.entity.ElectronicSealEntity;
import com.mannschaft.app.seal.entity.SealScopeDefaultEntity;
import com.mannschaft.app.seal.entity.SealStampLogEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SealMapper} (MapStruct生成実装) の単体テスト。
 * SealMapperImpl を直接インスタンス化してマッピングを検証する。
 */
@DisplayName("SealMapper 単体テスト")
class SealMapperTest {

    private SealMapperImpl mapper;

    @BeforeEach
    void setUp() {
        mapper = new SealMapperImpl();
    }

    // ----------------------------------------
    // Helper: BaseEntity の id を Reflection でセット
    // ----------------------------------------
    private void setBaseEntityId(Object entity, Long id) throws Exception {
        Field field = entity.getClass().getSuperclass().getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }

    private void setBaseEntityCreatedAt(Object entity, LocalDateTime dt) throws Exception {
        Field field = entity.getClass().getSuperclass().getDeclaredField("createdAt");
        field.setAccessible(true);
        field.set(entity, dt);
    }

    private void setBaseEntityUpdatedAt(Object entity, LocalDateTime dt) throws Exception {
        Field field = entity.getClass().getSuperclass().getDeclaredField("updatedAt");
        field.setAccessible(true);
        field.set(entity, dt);
    }

    // SealStampLogEntity は BaseEntity を継承しないので直接アクセス
    private void setSealStampLogId(SealStampLogEntity entity, Long id) throws Exception {
        Field field = entity.getClass().getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }

    // ----------------------------------------
    // ElectronicSealEntity → SealResponse
    // ----------------------------------------
    @Nested
    @DisplayName("toSealResponse")
    class ToSealResponse {

        @Test
        @DisplayName("正常系: LAST_NAME バリアントが変換される")
        void LAST_NAMEバリアント変換() throws Exception {
            ElectronicSealEntity entity = ElectronicSealEntity.builder()
                    .userId(1L)
                    .variant(SealVariant.LAST_NAME)
                    .displayText("田中")
                    .svgData("<svg>...</svg>")
                    .sealHash("abc123")
                    .generationVersion(1)
                    .build();
            setBaseEntityId(entity, 10L);
            LocalDateTime now = LocalDateTime.of(2026, 1, 1, 0, 0);
            setBaseEntityCreatedAt(entity, now);
            setBaseEntityUpdatedAt(entity, now);

            SealResponse response = mapper.toSealResponse(entity);

            assertThat(response.getId()).isEqualTo(10L);
            assertThat(response.getUserId()).isEqualTo(1L);
            assertThat(response.getVariant()).isEqualTo("LAST_NAME");
            assertThat(response.getDisplayText()).isEqualTo("田中");
            assertThat(response.getSvgData()).isEqualTo("<svg>...</svg>");
            assertThat(response.getSealHash()).isEqualTo("abc123");
            assertThat(response.getGenerationVersion()).isEqualTo(1);
            assertThat(response.getCreatedAt()).isEqualTo(now);
        }

        @Test
        @DisplayName("正常系: FULL_NAME バリアントが変換される")
        void FULL_NAMEバリアント変換() throws Exception {
            ElectronicSealEntity entity = ElectronicSealEntity.builder()
                    .userId(2L)
                    .variant(SealVariant.FULL_NAME)
                    .displayText("山田太郎")
                    .svgData("<svg>full</svg>")
                    .sealHash("def456")
                    .generationVersion(3)
                    .build();
            setBaseEntityId(entity, 20L);

            SealResponse response = mapper.toSealResponse(entity);

            assertThat(response.getVariant()).isEqualTo("FULL_NAME");
            assertThat(response.getGenerationVersion()).isEqualTo(3);
        }

        @Test
        @DisplayName("正常系: リスト変換")
        void リスト変換() throws Exception {
            ElectronicSealEntity e1 = ElectronicSealEntity.builder()
                    .userId(1L).variant(SealVariant.LAST_NAME).displayText("A")
                    .svgData("<svg/>").sealHash("h1").generationVersion(1).build();
            ElectronicSealEntity e2 = ElectronicSealEntity.builder()
                    .userId(2L).variant(SealVariant.FULL_NAME).displayText("B")
                    .svgData("<svg/>").sealHash("h2").generationVersion(2).build();
            setBaseEntityId(e1, 1L);
            setBaseEntityId(e2, 2L);

            List<SealResponse> list = mapper.toSealResponseList(List.of(e1, e2));

            assertThat(list).hasSize(2);
            assertThat(list.get(0).getVariant()).isEqualTo("LAST_NAME");
            assertThat(list.get(1).getVariant()).isEqualTo("FULL_NAME");
        }
    }

    // ----------------------------------------
    // SealScopeDefaultEntity → ScopeDefaultResponse
    // ----------------------------------------
    @Nested
    @DisplayName("toScopeDefaultResponse")
    class ToScopeDefaultResponse {

        @Test
        @DisplayName("正常系: DEFAULT スコープタイプが変換される")
        void DEFAULTスコープタイプ変換() throws Exception {
            SealScopeDefaultEntity entity = SealScopeDefaultEntity.builder()
                    .userId(1L)
                    .scopeType(SealScopeType.DEFAULT)
                    .scopeId(null)
                    .sealId(10L)
                    .build();
            setBaseEntityId(entity, 5L);
            LocalDateTime now = LocalDateTime.of(2026, 2, 1, 0, 0);
            setBaseEntityCreatedAt(entity, now);
            setBaseEntityUpdatedAt(entity, now);

            ScopeDefaultResponse response = mapper.toScopeDefaultResponse(entity);

            assertThat(response.getId()).isEqualTo(5L);
            assertThat(response.getUserId()).isEqualTo(1L);
            assertThat(response.getScopeType()).isEqualTo("DEFAULT");
            assertThat(response.getScopeId()).isNull();
            assertThat(response.getSealId()).isEqualTo(10L);
            assertThat(response.getCreatedAt()).isEqualTo(now);
        }

        @Test
        @DisplayName("正常系: TEAM スコープタイプが変換される")
        void TEAMスコープタイプ変換() throws Exception {
            SealScopeDefaultEntity entity = SealScopeDefaultEntity.builder()
                    .userId(2L)
                    .scopeType(SealScopeType.TEAM)
                    .scopeId(100L)
                    .sealId(20L)
                    .build();
            setBaseEntityId(entity, 6L);

            ScopeDefaultResponse response = mapper.toScopeDefaultResponse(entity);

            assertThat(response.getScopeType()).isEqualTo("TEAM");
            assertThat(response.getScopeId()).isEqualTo(100L);
        }

        @Test
        @DisplayName("正常系: リスト変換")
        void リスト変換() throws Exception {
            SealScopeDefaultEntity e1 = SealScopeDefaultEntity.builder()
                    .userId(1L).scopeType(SealScopeType.DEFAULT).sealId(1L).build();
            SealScopeDefaultEntity e2 = SealScopeDefaultEntity.builder()
                    .userId(1L).scopeType(SealScopeType.TEAM).scopeId(10L).sealId(2L).build();
            setBaseEntityId(e1, 1L);
            setBaseEntityId(e2, 2L);

            List<ScopeDefaultResponse> list = mapper.toScopeDefaultResponseList(List.of(e1, e2));

            assertThat(list).hasSize(2);
            assertThat(list.get(0).getScopeType()).isEqualTo("DEFAULT");
            assertThat(list.get(1).getScopeType()).isEqualTo("TEAM");
        }
    }

    // ----------------------------------------
    // SealStampLogEntity → StampLogResponse
    // ----------------------------------------
    @Nested
    @DisplayName("toStampLogResponse")
    class ToStampLogResponse {

        @Test
        @DisplayName("正常系: CIRCULATION ターゲットタイプが変換される")
        void CIRCULATIONターゲットタイプ変換() throws Exception {
            LocalDateTime stampedAt = LocalDateTime.of(2026, 3, 1, 12, 0);
            SealStampLogEntity entity = SealStampLogEntity.builder()
                    .userId(1L)
                    .sealId(10L)
                    .sealHashAtStamp("hash123")
                    .targetType(StampTargetType.CIRCULATION)
                    .targetId(500L)
                    .stampDocumentHash("docHash")
                    .isRevoked(false)
                    .stampedAt(stampedAt)
                    .build();
            setSealStampLogId(entity, 30L);

            StampLogResponse response = mapper.toStampLogResponse(entity);

            assertThat(response.getId()).isEqualTo(30L);
            assertThat(response.getUserId()).isEqualTo(1L);
            assertThat(response.getSealId()).isEqualTo(10L);
            assertThat(response.getSealHashAtStamp()).isEqualTo("hash123");
            assertThat(response.getTargetType()).isEqualTo("CIRCULATION");
            assertThat(response.getTargetId()).isEqualTo(500L);
            assertThat(response.getStampDocumentHash()).isEqualTo("docHash");
            assertThat(response.getIsRevoked()).isFalse();
            assertThat(response.getStampedAt()).isEqualTo(stampedAt);
        }

        @Test
        @DisplayName("正常系: WORKFLOW ターゲットタイプが変換される")
        void WORKFLOWターゲットタイプ変換() throws Exception {
            SealStampLogEntity entity = SealStampLogEntity.builder()
                    .userId(2L).sealId(11L).sealHashAtStamp("hash456")
                    .targetType(StampTargetType.WORKFLOW).targetId(600L)
                    .isRevoked(true)
                    .revokedAt(LocalDateTime.of(2026, 4, 1, 0, 0))
                    .stampedAt(LocalDateTime.of(2026, 3, 1, 0, 0))
                    .build();
            setSealStampLogId(entity, 31L);

            StampLogResponse response = mapper.toStampLogResponse(entity);

            assertThat(response.getTargetType()).isEqualTo("WORKFLOW");
            assertThat(response.getIsRevoked()).isTrue();
            assertThat(response.getRevokedAt()).isEqualTo(LocalDateTime.of(2026, 4, 1, 0, 0));
        }

        @Test
        @DisplayName("正常系: リスト変換")
        void リスト変換() throws Exception {
            SealStampLogEntity e1 = SealStampLogEntity.builder()
                    .userId(1L).sealId(10L).sealHashAtStamp("h1")
                    .targetType(StampTargetType.CIRCULATION).targetId(1L)
                    .isRevoked(false).stampedAt(LocalDateTime.now()).build();
            SealStampLogEntity e2 = SealStampLogEntity.builder()
                    .userId(2L).sealId(11L).sealHashAtStamp("h2")
                    .targetType(StampTargetType.WORKFLOW).targetId(2L)
                    .isRevoked(false).stampedAt(LocalDateTime.now()).build();
            setSealStampLogId(e1, 1L);
            setSealStampLogId(e2, 2L);

            List<StampLogResponse> list = mapper.toStampLogResponseList(List.of(e1, e2));

            assertThat(list).hasSize(2);
            assertThat(list.get(0).getTargetType()).isEqualTo("CIRCULATION");
            assertThat(list.get(1).getTargetType()).isEqualTo("WORKFLOW");
        }
    }
}
