package com.mannschaft.app.member;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.member.dto.CreateFieldRequest;
import com.mannschaft.app.member.dto.FieldResponse;
import com.mannschaft.app.member.entity.MemberProfileFieldEntity;
import com.mannschaft.app.member.repository.MemberProfileFieldRepository;
import com.mannschaft.app.member.service.MemberProfileFieldService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("MemberProfileFieldService 単体テスト")
class MemberProfileFieldServiceTest {

    @Mock private MemberProfileFieldRepository fieldRepository;
    @Mock private MemberMapper memberMapper;
    @InjectMocks private MemberProfileFieldService service;

    @Nested
    @DisplayName("createField")
    class CreateField {

        @Test
        @DisplayName("正常系: フィールド定義が作成される")
        void 作成_正常_保存() {
            // Given
            CreateFieldRequest req = new CreateFieldRequest(
                    1L, null, "ポジション", "TEXT", null, false, 0);
            given(fieldRepository.save(any(MemberProfileFieldEntity.class))).willAnswer(inv -> inv.getArgument(0));
            given(memberMapper.toFieldResponse(any(MemberProfileFieldEntity.class)))
                    .willReturn(new FieldResponse(1L, 1L, null, "ポジション", "TEXT",
                            null, false, 0, true, null, null));

            // When
            FieldResponse result = service.createField(req);

            // Then
            assertThat(result.getFieldName()).isEqualTo("ポジション");
            verify(fieldRepository).save(any(MemberProfileFieldEntity.class));
        }
    }

    @Nested
    @DisplayName("deactivateField")
    class DeactivateField {

        @Test
        @DisplayName("異常系: フィールド不在でMEMBER_004例外")
        void 無効化_不在_例外() {
            // Given
            given(fieldRepository.findById(1L)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.deactivateField(1L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("MEMBER_004"));
        }
    }
}
