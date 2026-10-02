package com.mannschaft.app.circulation.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CreateDocumentRequest} の HYBRID モード相関バリデーション単体テスト。
 *
 * <p>回覧板 HYBRID モード（先頭N人順番 → 残り一斉）では、あて先を sortOrder で並べたとき
 * 先頭 N 人 = sortOrder 0..N-1（順番）、残り = 同一 sortOrder N（一斉）となる。
 * N（sequentialCount）は「1 ≤ N < あて先数」でなければならず、
 * circulationMode=HYBRID のときは必須。</p>
 */
@DisplayName("CreateDocumentRequest HYBRID相関バリデーションテスト")
class CreateDocumentRequestHybridValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    private CreateDocumentRequest build(String circulationMode, Integer sequentialCount,
                                        int recipientCount) {
        List<RecipientEntry> recipients = new java.util.ArrayList<>();
        for (int i = 0; i < recipientCount; i++) {
            recipients.add(new RecipientEntry((long) (i + 1), i));
        }
        return new CreateDocumentRequest(
                "回覧タイトル", "本文", circulationMode, null, null, null, null, null,
                recipients, sequentialCount);
    }

    @Test
    @DisplayName("AC-2: HYBRID_N妥当(1<=N<あて先数)_違反なし")
    void HYBRID_N妥当_違反なし() {
        // あて先5人・N=2 → 先頭2人順番 + 残り3人一斉
        CreateDocumentRequest req = build("HYBRID", 2, 5);
        Set<ConstraintViolation<CreateDocumentRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("AC-2: HYBRID_sequentialCount未指定_違反あり")
    void HYBRID_未指定_違反あり() {
        CreateDocumentRequest req = build("HYBRID", null, 5);
        Set<ConstraintViolation<CreateDocumentRequest>> violations = validator.validate(req);
        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().equals("hybridSequentialCountValid"));
    }

    @Test
    @DisplayName("AC-2: HYBRID_Nが0以下_違反あり")
    void HYBRID_N範囲外下限_違反あり() {
        CreateDocumentRequest req = build("HYBRID", 0, 5);
        Set<ConstraintViolation<CreateDocumentRequest>> violations = validator.validate(req);
        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().equals("hybridSequentialCountValid"));
    }

    @Test
    @DisplayName("AC-2: HYBRID_Nがあて先数以上_違反あり")
    void HYBRID_N範囲外上限_違反あり() {
        // あて先5人・N=5 → 全員が順番になり「残り一斉」群が消えるため不正
        CreateDocumentRequest req = build("HYBRID", 5, 5);
        Set<ConstraintViolation<CreateDocumentRequest>> violations = validator.validate(req);
        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().equals("hybridSequentialCountValid"));
    }

    @Test
    @DisplayName("AC-2回帰: SEQUENTIALはsequentialCount無視で違反なし")
    void SEQUENTIAL_sequentialCount無視_違反なし() {
        CreateDocumentRequest req = build("SEQUENTIAL", null, 3);
        Set<ConstraintViolation<CreateDocumentRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("AC-2回帰: SIMULTANEOUSはsequentialCount無視で違反なし")
    void SIMULTANEOUS_sequentialCount無視_違反なし() {
        // SIMULTANEOUS では sequentialCount に不正値が入っていても無視する
        CreateDocumentRequest req = build("SIMULTANEOUS", 99, 3);
        Set<ConstraintViolation<CreateDocumentRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }
}
