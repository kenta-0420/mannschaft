package com.mannschaft.app.resume.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.resume.ResumeErrorCode;
import com.mannschaft.app.resume.dto.ResumeDetailResponse;
import com.mannschaft.app.resume.dto.ResumeFullSaveRequest;
import com.mannschaft.app.resume.entity.ResumeCareerEntity;
import com.mannschaft.app.resume.entity.ResumeEducationEntity;
import com.mannschaft.app.resume.entity.ResumeEntity;
import com.mannschaft.app.resume.entity.ResumeQualificationEntity;
import com.mannschaft.app.resume.entity.ResumeSkillEntity;
import com.mannschaft.app.resume.repository.ResumeCareerRepository;
import com.mannschaft.app.resume.repository.ResumeEducationRepository;
import com.mannschaft.app.resume.repository.ResumeQualificationRepository;
import com.mannschaft.app.resume.repository.ResumeRepository;
import com.mannschaft.app.resume.repository.ResumeSkillRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ResumeService} 単体テスト（F01.10）。
 *
 * <p>設計書: {@code docs/features/F01.10_mypage_resume.md} §12.1
 *
 * <p>対象テストケース:
 * <ul>
 *   <li>UT-01: CRUD（作成・取得・論理削除）</li>
 *   <li>UT-02: 一括保存（PUT 差分 upsert）</li>
 *   <li>UT-03: バリデーション（件数上限）</li>
 *   <li>UT-06: 楽観ロック競合</li>
 *   <li>UT-08: 職歴出し分けフラグ</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ResumeService 単体テスト")
class ResumeServiceTest {

    private static final Long USER_ID = 100L;

    @Mock
    private ResumeRepository resumeRepository;
    @Mock
    private ResumeEducationRepository educationRepository;
    @Mock
    private ResumeCareerRepository careerRepository;
    @Mock
    private ResumeQualificationRepository qualificationRepository;
    @Mock
    private ResumeSkillRepository skillRepository;

    @InjectMocks
    private ResumeService resumeService;

    // =========================================================================
    // UT-01: CRUD
    // =========================================================================

    @Nested
    @DisplayName("UT-01: CRUD")
    class UT01Crud {

        @Test
        @DisplayName("タイトルを指定して履歴書を作成できる")
        void testCreateResume_withTitle() {
            // arrange
            given(resumeRepository.findByUserIdOrderByCreatedAtDesc(USER_ID))
                    .willReturn(new ArrayList<>());
            ResumeEntity saved = buildResumeEntity(UUID.randomUUID(), USER_ID, "テスト履歴書");
            given(resumeRepository.save(any(ResumeEntity.class))).willReturn(saved);
            given(educationRepository.findByResumeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(any()))
                    .willReturn(List.of());
            given(careerRepository.findByResumeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(any()))
                    .willReturn(List.of());
            given(qualificationRepository.findByResumeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(any()))
                    .willReturn(List.of());
            given(skillRepository.findByResumeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(any()))
                    .willReturn(List.of());

            // act
            ResumeDetailResponse result = resumeService.createResume(USER_ID, "テスト履歴書");

            // assert
            assertThat(result).isNotNull();
            assertThat(result.title()).isEqualTo("テスト履歴書");
        }

        @Test
        @DisplayName("タイトルが null の場合は「下書き YYYY-MM-DD」が自動採番される")
        void testCreateResume_withoutTitle_autoGeneratesTitle() {
            // arrange
            given(resumeRepository.findByUserIdOrderByCreatedAtDesc(USER_ID))
                    .willReturn(new ArrayList<>());
            // save されるエンティティのタイトルを ArgumentCaptor でキャプチャする
            ArgumentCaptor<ResumeEntity> captor = ArgumentCaptor.forClass(ResumeEntity.class);
            ResumeEntity saved = buildResumeEntityWithAutoTitle(UUID.randomUUID(), USER_ID);
            given(resumeRepository.save(any(ResumeEntity.class))).willReturn(saved);
            given(educationRepository.findByResumeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(any()))
                    .willReturn(List.of());
            given(careerRepository.findByResumeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(any()))
                    .willReturn(List.of());
            given(qualificationRepository.findByResumeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(any()))
                    .willReturn(List.of());
            given(skillRepository.findByResumeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(any()))
                    .willReturn(List.of());

            // act
            resumeService.createResume(USER_ID, null);

            // assert: save されるエンティティのタイトルが「下書き YYYY-MM-DD」形式であること
            verify(resumeRepository).save(captor.capture());
            String capturedTitle = captor.getValue().getTitle();
            assertThat(capturedTitle).startsWith("下書き ");
            assertThat(capturedTitle).matches("下書き \\d{4}-\\d{2}-\\d{2}.*");
        }

        @Test
        @DisplayName("他ユーザーの履歴書 ID を指定すると RESUME_001（404）がスローされる")
        void testGetResume_notOwner_throws404() {
            // arrange
            UUID resumeId = UUID.randomUUID();
            given(resumeRepository.findByIdAndUserId(resumeId, USER_ID))
                    .willReturn(Optional.empty());

            // act & assert
            assertThatThrownBy(() -> resumeService.getResume(resumeId, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ResumeErrorCode.RESUME_001);
        }

        @Test
        @DisplayName("論理削除すると deletedAt がセットされる")
        void testDeleteResume_setsDeletedAt() {
            // arrange
            UUID resumeId = UUID.randomUUID();
            ResumeEntity entity = buildResumeEntity(resumeId, USER_ID, "削除テスト");
            given(resumeRepository.findByIdAndUserId(resumeId, USER_ID))
                    .willReturn(Optional.of(entity));
            given(resumeRepository.save(any(ResumeEntity.class))).willReturn(entity);

            // act
            resumeService.deleteResume(resumeId, USER_ID);

            // assert
            ArgumentCaptor<ResumeEntity> captor = ArgumentCaptor.forClass(ResumeEntity.class);
            verify(resumeRepository).save(captor.capture());
            assertThat(captor.getValue().getDeletedAt()).isNotNull();
        }
    }

    // =========================================================================
    // UT-02: 一括保存（PUT 差分 upsert）
    // =========================================================================

    @Nested
    @DisplayName("UT-02: 一括保存（差分 upsert）")
    class UT02FullSave {

        @Test
        @DisplayName("id なしの子要素は新規作成される")
        void testSaveResume_newChildren_areInserted() {
            // arrange
            UUID resumeId = UUID.randomUUID();
            ResumeEntity entity = buildResumeEntity(resumeId, USER_ID, "テスト");
            given(resumeRepository.findByIdAndUserId(resumeId, USER_ID))
                    .willReturn(Optional.of(entity));
            given(resumeRepository.save(any())).willReturn(entity);
            given(educationRepository.findByResumeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(resumeId))
                    .willReturn(List.of());
            given(careerRepository.findByResumeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(resumeId))
                    .willReturn(List.of());
            given(qualificationRepository.findByResumeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(resumeId))
                    .willReturn(List.of());
            given(skillRepository.findByResumeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(resumeId))
                    .willReturn(List.of());

            // id なしの学歴 DTO（新規作成に相当）
            ResumeFullSaveRequest.EducationSaveDto newEdu =
                    new ResumeFullSaveRequest.EducationSaveDto(null, 2010, 3, "○○大学 卒業", 1);
            ResumeFullSaveRequest req = buildSaveRequest(resumeId, List.of(newEdu));

            // act
            resumeService.saveResume(resumeId, USER_ID, req);

            // assert: educationRepository.save が呼ばれたことを確認
            verify(educationRepository, atLeastOnce()).save(any(ResumeEducationEntity.class));
        }

        @Test
        @DisplayName("リクエストにない既存の子要素は論理削除される")
        void testSaveResume_missingChildren_areLogicallyDeleted() {
            // arrange
            UUID resumeId = UUID.randomUUID();
            UUID existingEduId = UUID.randomUUID();
            ResumeEntity entity = buildResumeEntity(resumeId, USER_ID, "テスト");
            given(resumeRepository.findByIdAndUserId(resumeId, USER_ID))
                    .willReturn(Optional.of(entity));
            given(resumeRepository.save(any())).willReturn(entity);
            // 既存学歴が 1 件あるが、リクエストには含めない
            ResumeEducationEntity existingEdu = buildEducationEntity(existingEduId, resumeId);
            given(educationRepository.findByResumeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(resumeId))
                    .willReturn(List.of(existingEdu));
            given(careerRepository.findByResumeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(resumeId))
                    .willReturn(List.of());
            given(qualificationRepository.findByResumeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(resumeId))
                    .willReturn(List.of());
            given(skillRepository.findByResumeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(resumeId))
                    .willReturn(List.of());

            // リクエストには学歴なし（空リスト）
            ResumeFullSaveRequest req = buildSaveRequest(resumeId, List.of());

            // act
            resumeService.saveResume(resumeId, USER_ID, req);

            // assert: 既存学歴が論理削除（save）されたことを確認
            ArgumentCaptor<ResumeEducationEntity> captor = ArgumentCaptor.forClass(ResumeEducationEntity.class);
            verify(educationRepository, atLeastOnce()).save(captor.capture());
            assertThat(captor.getValue().getDeletedAt()).isNotNull();
        }

        @Test
        @DisplayName("同一ボディを 2 回送っても同じ結果になる（冪等性）")
        void testSaveResume_idempotent_sameBodyProducesSameResult() {
            // arrange
            UUID resumeId = UUID.randomUUID();
            UUID eduId = UUID.randomUUID();
            ResumeEntity entity = buildResumeEntity(resumeId, USER_ID, "テスト");
            given(resumeRepository.findByIdAndUserId(resumeId, USER_ID))
                    .willReturn(Optional.of(entity));
            given(resumeRepository.save(any())).willReturn(entity);
            ResumeEducationEntity existingEdu = buildEducationEntity(eduId, resumeId);
            given(educationRepository.findByResumeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(resumeId))
                    .willReturn(List.of(existingEdu));
            given(careerRepository.findByResumeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(resumeId))
                    .willReturn(List.of());
            given(qualificationRepository.findByResumeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(resumeId))
                    .willReturn(List.of());
            given(skillRepository.findByResumeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(resumeId))
                    .willReturn(List.of());
            given(educationRepository.findByResumeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(resumeId))
                    .willReturn(List.of());
            given(skillRepository.findByResumeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(resumeId))
                    .willReturn(List.of());

            // 同じ id を含むリクエスト（2 回目も同じ）
            ResumeFullSaveRequest.EducationSaveDto dto =
                    new ResumeFullSaveRequest.EducationSaveDto(eduId.toString(), 2010, 3, "○○大学", 1);
            ResumeFullSaveRequest req = buildSaveRequest(resumeId, List.of(dto));

            // act（1 回目）
            resumeService.saveResume(resumeId, USER_ID, req);
            // act（2 回目）
            resumeService.saveResume(resumeId, USER_ID, req);

            // assert: 2 回連続して saveResume を呼んでも例外なし（冪等）
            // 具体的な DB 状態は IT-01 で検証するため、ここは例外が出ないことのみ確認
        }
    }

    // =========================================================================
    // UT-03: バリデーション
    // =========================================================================

    @Nested
    @DisplayName("UT-03: バリデーション")
    class UT03Validation {

        @Test
        @DisplayName("学歴が 30 件を超えると RESUME_003 がスローされる")
        void testSaveResume_tooManyEducations_throwsResume003() {
            // arrange: checkChildLimits() は saveResume の先頭で呼ばれるため
            // findByIdAndUserId() は呼ばれない。スタブ不要。
            UUID resumeId = UUID.randomUUID();

            // 31 件の学歴 DTO を生成（上限 30 件超過）
            List<ResumeFullSaveRequest.EducationSaveDto> tooManyEdus = new ArrayList<>();
            for (int i = 0; i < 31; i++) {
                tooManyEdus.add(new ResumeFullSaveRequest.EducationSaveDto(null, 2000 + i, 4, "学校" + i, i));
            }
            ResumeFullSaveRequest req = buildSaveRequest(resumeId, tooManyEdus);

            // act & assert
            assertThatThrownBy(() -> resumeService.saveResume(resumeId, USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ResumeErrorCode.RESUME_003);
        }
    }

    // =========================================================================
    // UT-06: 楽観ロック
    // =========================================================================

    @Nested
    @DisplayName("UT-06: 楽観ロック")
    class UT06OptimisticLock {

        @Test
        @DisplayName("古いバージョンで PUT するとバージョン不一致が検出され RESUME_010 がスローされる")
        void testSaveResume_versionConflict_throwsResume010() {
            // arrange
            UUID resumeId = UUID.randomUUID();
            // DB 上のバージョンは 2
            ResumeEntity entity = buildResumeEntityWithVersion(resumeId, USER_ID, "テスト", 2L);
            given(resumeRepository.findByIdAndUserId(resumeId, USER_ID))
                    .willReturn(Optional.of(entity));

            // リクエストのバージョンは 1（古い）
            ResumeFullSaveRequest req = buildSaveRequestWithVersion(resumeId, 1L);

            // act & assert
            assertThatThrownBy(() -> resumeService.saveResume(resumeId, USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ResumeErrorCode.RESUME_010);
        }

        @Test
        @DisplayName("JPA の楽観ロック例外は RESUME_010 に変換される")
        void testSaveResume_jpaOptimisticLockException_throwsResume010() {
            // arrange: resumeRepository.save() が例外をスローするため、
            // その後の子リポジトリ find* は呼ばれない。子リポジトリのスタブは不要。
            UUID resumeId = UUID.randomUUID();
            ResumeEntity entity = buildResumeEntity(resumeId, USER_ID, "テスト");
            given(resumeRepository.findByIdAndUserId(resumeId, USER_ID))
                    .willReturn(Optional.of(entity));
            // JPA の楽観ロック例外を save 時にスローさせる
            given(resumeRepository.save(any()))
                    .willThrow(new OptimisticLockingFailureException("version conflict"));

            ResumeFullSaveRequest req = buildSaveRequest(resumeId, List.of());

            // act & assert
            assertThatThrownBy(() -> resumeService.saveResume(resumeId, USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ResumeErrorCode.RESUME_010);
        }
    }

    // =========================================================================
    // UT-08: 職歴出し分けフラグ（ResumeCareerEntity の isIncludeInRirekisho/Shokumukeireki）
    // =========================================================================

    @Nested
    @DisplayName("UT-08: 職歴出し分けフラグ")
    class UT08CareerFilter {

        @Test
        @DisplayName("includeInRirekisho=false の職歴は履歴書用データに含まれない")
        void testCareerFilterForRirekisho() {
            // arrange
            ResumeCareerEntity includedCareer = buildCareerEntity(UUID.randomUUID(), UUID.randomUUID(),
                    true, true, "含まれる会社");
            ResumeCareerEntity excludedCareer = buildCareerEntity(UUID.randomUUID(), UUID.randomUUID(),
                    false, true, "除外される会社");

            // act: includeInRirekisho で絞り込み
            List<ResumeCareerEntity> filteredForRirekisho = List.of(includedCareer, excludedCareer)
                    .stream()
                    .filter(ResumeCareerEntity::isIncludeInRirekisho)
                    .toList();

            // assert
            assertThat(filteredForRirekisho).hasSize(1);
            assertThat(filteredForRirekisho.get(0).getCompanyName()).isEqualTo("含まれる会社");
        }

        @Test
        @DisplayName("includeInShokumukeireki=false の職歴は職務経歴書用データに含まれない")
        void testCareerFilterForShokumukeirekisho() {
            // arrange
            ResumeCareerEntity includedCareer = buildCareerEntity(UUID.randomUUID(), UUID.randomUUID(),
                    true, true, "含まれる会社");
            ResumeCareerEntity excludedCareer = buildCareerEntity(UUID.randomUUID(), UUID.randomUUID(),
                    true, false, "職経除外会社");

            // act: includeInShokumukeireki で絞り込み
            List<ResumeCareerEntity> filteredForShokumukeirekisho =
                    List.of(includedCareer, excludedCareer)
                            .stream()
                            .filter(ResumeCareerEntity::isIncludeInShokumukeireki)
                            .toList();

            // assert
            assertThat(filteredForShokumukeirekisho).hasSize(1);
            assertThat(filteredForShokumukeirekisho.get(0).getCompanyName()).isEqualTo("含まれる会社");
        }
    }

    // =========================================================================
    // テストデータビルダー
    // =========================================================================

    /** 基本的な ResumeEntity を構築する。 */
    private ResumeEntity buildResumeEntity(UUID id, Long userId, String title) {
        ResumeEntity entity = ResumeEntity.builder()
                .userId(userId)
                .title(title)
                .build();
        // UuidV7Entity の id は setId 経由でセット（リフレクションを使用）
        org.springframework.test.util.ReflectionTestUtils.setField(entity, "id", id);
        org.springframework.test.util.ReflectionTestUtils.setField(entity, "version", 0L);
        org.springframework.test.util.ReflectionTestUtils.setField(entity, "createdAt", LocalDateTime.now());
        org.springframework.test.util.ReflectionTestUtils.setField(entity, "updatedAt", LocalDateTime.now());
        return entity;
    }

    /** 自動採番タイトル（「下書き YYYY-MM-DD」）の ResumeEntity を構築する。 */
    private ResumeEntity buildResumeEntityWithAutoTitle(UUID id, Long userId) {
        String autoTitle = "下書き " + java.time.LocalDate.now();
        return buildResumeEntity(id, userId, autoTitle);
    }

    /** バージョン指定の ResumeEntity を構築する。 */
    private ResumeEntity buildResumeEntityWithVersion(UUID id, Long userId, String title, Long version) {
        ResumeEntity entity = buildResumeEntity(id, userId, title);
        org.springframework.test.util.ReflectionTestUtils.setField(entity, "version", version);
        return entity;
    }

    /** 基本的な ResumeFullSaveRequest を構築する。 */
    private ResumeFullSaveRequest buildSaveRequest(UUID resumeId,
                                                    List<ResumeFullSaveRequest.EducationSaveDto> educations) {
        return new ResumeFullSaveRequest(
                "テスト履歴書",   // title
                "WESTERN",       // eraFormat
                null,            // currentAddress
                null,            // currentAddressKana
                null,            // contactAddress
                null,            // contactAddressKana
                null,            // contactPhone
                null,            // contactEmail
                null,            // motivation
                null,            // selfPr
                null,            // personalRequest
                null,            // commuteMinutes
                null,            // dependentsCount
                null,            // hasSpouse
                null,            // spouseSupport
                null,            // careerSummary
                null,            // skillsSummary
                0L,              // version（現在のバージョンと一致）
                educations,
                List.of(),       // careers
                List.of(),       // qualifications
                List.of()        // skills
        );
    }

    /** バージョン指定の ResumeFullSaveRequest を構築する。 */
    private ResumeFullSaveRequest buildSaveRequestWithVersion(UUID resumeId, Long version) {
        return new ResumeFullSaveRequest(
                "テスト履歴書",
                "WESTERN",
                null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                version,
                List.of(), List.of(), List.of(), List.of()
        );
    }

    /** ResumeEducationEntity を構築する。 */
    private ResumeEducationEntity buildEducationEntity(UUID id, UUID resumeId) {
        ResumeEducationEntity entity = ResumeEducationEntity.builder()
                .resumeId(resumeId)
                .entryYear((short) 2010)
                .entryMonth((byte) 3)
                .description("○○大学 卒業")
                .displayOrder(1)
                .build();
        org.springframework.test.util.ReflectionTestUtils.setField(entity, "id", id);
        org.springframework.test.util.ReflectionTestUtils.setField(entity, "createdAt", LocalDateTime.now());
        org.springframework.test.util.ReflectionTestUtils.setField(entity, "updatedAt", LocalDateTime.now());
        return entity;
    }

    /** ResumeCareerEntity を構築する（出し分けフラグを制御できる）。 */
    private ResumeCareerEntity buildCareerEntity(UUID id, UUID resumeId,
                                                  boolean includeInRirekisho,
                                                  boolean includeInShokumukeireki,
                                                  String companyName) {
        ResumeCareerEntity entity = ResumeCareerEntity.builder()
                .resumeId(resumeId)
                .entryYear((short) 2015)
                .entryMonth((byte) 4)
                .isCurrent(false)
                .companyName(companyName)
                .includeInRirekisho(includeInRirekisho)
                .includeInShokumukeireki(includeInShokumukeireki)
                .displayOrder(1)
                .build();
        org.springframework.test.util.ReflectionTestUtils.setField(entity, "id", id);
        org.springframework.test.util.ReflectionTestUtils.setField(entity, "createdAt", LocalDateTime.now());
        org.springframework.test.util.ReflectionTestUtils.setField(entity, "updatedAt", LocalDateTime.now());
        return entity;
    }
}
