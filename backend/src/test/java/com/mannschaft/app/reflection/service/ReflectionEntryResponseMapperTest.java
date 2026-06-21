package com.mannschaft.app.reflection.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.reflection.dto.ReflectionEntryResponse;
import com.mannschaft.app.reflection.entity.ReflectionEntryEntity;
import com.mannschaft.app.reflection.entity.ReflectionThemeEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * {@link ReflectionEntryResponseMapper} 単体テスト（F06.5・§3.2 マスク分離・AC-8）。
 *
 * <p>マスク中は本文をソースから詰めない（structuredContent=null・maskedHint のみ）。開示時は本文を載せる。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReflectionEntryResponseMapper 単体テスト（§3.2 / AC-8）")
class ReflectionEntryResponseMapperTest {

    @Mock private ReflectionMaskEvaluator maskEvaluator;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ReflectionContentSanitizer sanitizer;
    private ReflectionEntryResponseMapper mapper;

    private static final LocalDate TARGET = LocalDate.of(2026, 6, 1);

    private static void setId(Object entity, UUID id) {
        try {
            Field f = entity.getClass().getSuperclass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ReflectionEntryEntity entry() {
        ReflectionEntryEntity e = ReflectionEntryEntity.builder()
                .themeId(UUID.randomUUID()).userId(1L).targetDate(TARGET)
                .structuredContent("{\"main_theme\":\"秘密の本文\"}").version(0L).build();
        setId(e, UUID.randomUUID());
        return e;
    }

    private ReflectionThemeEntity theme() {
        ReflectionThemeEntity t = ReflectionThemeEntity.builder()
                .userId(1L).title("数学II").recallIntervalDays("1,3,7,14").build();
        setId(t, UUID.randomUUID());
        return t;
    }

    private void init() {
        sanitizer = new ReflectionContentSanitizer(objectMapper);
        mapper = new ReflectionEntryResponseMapper(maskEvaluator, sanitizer);
    }

    @Test
    @DisplayName("AC-8: マスク中は structuredContent=null かつ isMasked=true、maskedHint のみ")
    void masked_bodyIsNull() {
        init();
        ReflectionEntryEntity e = entry();
        ReflectionThemeEntity t = theme();
        LocalDate today = TARGET.plusDays(1);
        given(maskEvaluator.isMasked(e, t, today)).willReturn(true);
        given(maskEvaluator.dueRecallDates(any(), any(), any()))
                .willReturn(List.of(TARGET.plusDays(1)));

        ReflectionEntryResponse resp = mapper.toResponse(e, t, today);

        assertThat(resp.isMasked()).isTrue();
        assertThat(resp.structuredContent()).isNull();
        assertThat(resp.maskedHint()).isNotNull();
        assertThat(resp.maskedHint().themeTitle()).isEqualTo("数学II");
        assertThat(resp.maskedHint().dueRecallDates()).containsExactly(TARGET.plusDays(1));
    }

    @Test
    @DisplayName("非マスク時は structuredContent を載せ isMasked=false、maskedHint=null")
    void revealed_bodyPresent() {
        init();
        ReflectionEntryEntity e = entry();
        ReflectionThemeEntity t = theme();
        given(maskEvaluator.isMasked(e, t, TARGET)).willReturn(false);

        ReflectionEntryResponse resp = mapper.toResponse(e, t, TARGET);

        assertThat(resp.isMasked()).isFalse();
        assertThat(resp.structuredContent()).isNotNull();
        assertThat(resp.structuredContent().get("main_theme").asText()).isEqualTo("秘密の本文");
        assertThat(resp.maskedHint()).isNull();
    }

    @Test
    @DisplayName("toRevealedResponse: マスクを無視して本文を開示（recall 開示の遷移点・AC-7）")
    void toRevealedResponse_disclosesBody() {
        init();
        ReflectionEntryEntity e = entry();
        ReflectionThemeEntity t = theme();

        ReflectionEntryResponse resp = mapper.toRevealedResponse(e, t);

        assertThat(resp.isMasked()).isFalse();
        assertThat(resp.structuredContent().get("main_theme").asText()).isEqualTo("秘密の本文");
    }
}
