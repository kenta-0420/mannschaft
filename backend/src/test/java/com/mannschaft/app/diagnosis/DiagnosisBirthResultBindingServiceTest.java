package com.mannschaft.app.diagnosis;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.diagnosis.dto.DiagnosisNumberSummary;
import com.mannschaft.app.diagnosis.dto.DiagnosisResultSummary;
import com.mannschaft.app.diagnosis.dto.OwnedDiagnosisResult;
import com.mannschaft.app.diagnosis.service.DiagnosisBirthResultBindingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 出生採用の版束縛を素の値で検証する。実ref/ACTIVE/replay/採用TXは別MySQL試練で確認する。 */
class DiagnosisBirthResultBindingServiceTest {
    private final DiagnosisBirthResultBindingService service = new DiagnosisBirthResultBindingService();
    private OwnedDiagnosisResult result(DiagnosisMethod method, Long revision) {
        DiagnosisResultSummary summary = new DiagnosisResultSummary(UUID.randomUUID(), method, Instant.EPOCH,
                "result-v1", null, null, "nfkc-hepburn-v1", "digit-reduce-v1", null, null,
                Map.of(), new DiagnosisNumberSummary(6,6,24,33), Map.of("ja","独自ルールの保存説明"), Map.of());
        return new OwnedDiagnosisResult(7L,summary,revision);
    }
    @Test @DisplayName("出生結果の計算元と本人確認版が一致すると採用可能")
    void 保存結果と本人確認版の一致を許可() {
        assertThatCode(()->service.requireSourceProfileRevision(result(DiagnosisMethod.BIRTH_STYLE,3L),3L))
                .doesNotThrowAnyException();
    }
    @Test @DisplayName("新しい本人確認を旧プロフィール結果へ流用しない")
    void 新しい確認で旧出生結果を拒否() {
        assertThatThrownBy(()->service.requireSourceProfileRevision(result(DiagnosisMethod.BIRTH_STYLE,2L),3L))
                .isInstanceOf(BusinessException.class);
    }
    @Test @DisplayName("同じ派生数でも異なる版は拒否")
    void 派生数が同一でも版不一致を拒否() {
        var saved=result(DiagnosisMethod.BIRTH_STYLE,9L);
        assertThatThrownBy(()->service.requireSourceProfileRevision(saved,8L)).isInstanceOf(BusinessException.class);
    }
    @Test @DisplayName("旧結果の欠損bindingと不正な版を推測せず拒否")
    void 版欠損と負数を拒否() {
        for(Long revision : new Long[]{null,-1L}) {
            assertThatThrownBy(()->service.requireSourceProfileRevision(result(DiagnosisMethod.BIRTH_STYLE,revision),0L))
                    .isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(()->service.requireSourceProfileRevision(result(DiagnosisMethod.BIRTH_STYLE,0L),-1L))
                .isInstanceOf(BusinessException.class);
    }
    @Test @DisplayName("通常診断を出生結果として取り扱わない")
    void 通常診断の出生採用を拒否() {
        assertThatThrownBy(()->service.requireSourceProfileRevision(result(DiagnosisMethod.DIAGNOSIS,null),0L))
                .isInstanceOf(BusinessException.class);
    }
}
