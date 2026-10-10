package com.mannschaft.app.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.BirthProfileErrorCode;
import com.mannschaft.app.auth.dto.BirthProfileConfirmationResponse;
import com.mannschaft.app.auth.dto.BirthProfileUpdateRequest;
import com.mannschaft.app.auth.entity.BirthProfileConfirmationEntity;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.BirthProfileCommandRepository;
import com.mannschaft.app.auth.repository.BirthProfileConfirmationRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.diagnosis.repository.DiagnosisCommandRepository;
import com.mannschaft.app.diagnosis.repository.DiagnosisResultRepository;
import com.mannschaft.app.diagnosis.service.DiagnosisOperationFacade;
import com.mannschaft.app.diagnosis.service.DiagnosisPurgeService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

/** AC65のcore確認契約。正式Ranch出生mappingの選定成功はこのfixtureの対象外。 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class BirthProfileConfirmationBoundaryIT extends AbstractMySqlIntegrationTest {
    @Autowired private BirthProfileFacade profiles;
    @Autowired private BirthProfileProofCodec proof;
    @Autowired private UserRepository users;
    @Autowired private BirthProfileConfirmationRepository confirmations;
    @Autowired private BirthProfileCommandRepository profileCommands;
    @Autowired private BirthProfilePurgeService profilePurge;
    @Autowired private DiagnosisOperationFacade diagnosis;
    @Autowired private DiagnosisCommandRepository diagnosisCommands;
    @Autowired private DiagnosisResultRepository results;
    @Autowired private DiagnosisPurgeService diagnosisPurge;
    @Autowired private ObjectMapper mapper;
    private final List<Long> ownedUsers = new ArrayList<>();
    private static final Instant ISSUED = Instant.parse("2030-04-05T06:07:08.123456789Z");

    private Long user() {
        Long id = users.saveAndFlush(UserEntity.builder().email(UUID.randomUUID()+"@proof-boundary.invalid")
                .lastName("山田").firstName("健太").lastNameKana("ヤマダ").firstNameKana("ケンタ")
                .birthDate("1990-01-22").displayName("本人").locale("ja").timezone("UTC")
                .isSearchable(false).status(UserEntity.UserStatus.ACTIVE).build()).getId();
        ownedUsers.add(id);
        return id;
    }

    @AfterEach void cleanupOwnedFixtures() {
        for (Long id : ownedUsers) {
            diagnosisPurge.purgeUser(id);
            profilePurge.purgeUser(id);
            users.deleteById(id);
        }
    }

    // 呼出threadだけの短い固定時刻。Instantの他メソッド・実Bean・DBは置換しない。
    private <T> T at(Instant now, Supplier<T> operation) {
        try (var clock = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
            clock.when(Instant::now).thenReturn(now);
            return operation.get();
        }
    }

    private BirthProfileConfirmationResponse issue(Long user, long revision) {
        return at(ISSUED, () -> profiles.confirm(user, UUID.randomUUID(), revision, true));
    }

    private int resultCount(Long user) {
        return results.findOwnedPage(user, null, null, null, PageRequest.of(0, 100)).size();
    }

    private void rejected(Long user, UUID ref, BirthProfileErrorCode error) {
        var initialWrites = new AtomicInteger();
        assertThatThrownBy(() -> profiles.withConfirmedBirthProfile(user, ref, Optional::empty,
                numbers -> initialWrites.incrementAndGet()))
                .isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.getErrorCode()).isEqualTo(error));
        assertThat(initialWrites).hasValue(0);
        int before = resultCount(user);
        UUID command = UUID.randomUUID();
        assertThatThrownBy(() -> diagnosis.birthResult(user, command, ref))
                .isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.getErrorCode()).isEqualTo(error));
        assertThat(diagnosisCommands.findByUserIdAndCommandId(user, command)).isEmpty();
        assertThat(resultCount(user)).isEqualTo(before);
    }

    @Test void 正規発行のDBと応答保存JSONはMICROS期限と署名を維持する() throws Exception {
        Long user = user();
        UUID command = UUID.randomUUID();
        var response = at(ISSUED, () -> profiles.confirm(user, command, 0, true));
        var stored = confirmations.findByIdAndUserId(response.confirmationRef(), user).orElseThrow();
        assertThat(stored.getCreatedAt()).isEqualTo(Instant.parse("2030-04-05T06:07:08.123456Z"));
        assertThat(stored.getExpiresAt()).isEqualTo(stored.getCreatedAt().plusSeconds(600));
        assertThat(response.expiresAt()).isEqualTo(stored.getExpiresAt());
        assertThat(proof.authentic(stored)).isTrue();
        String json = profileCommands.findByUserIdAndCommandId(user, command).orElseThrow().getResponseSnapshot();
        assertThat(mapper.readValue(json, BirthProfileConfirmationResponse.class)).isEqualTo(response);
        List<String> fields = new ArrayList<>();
        mapper.readTree(json).fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("confirmationRef", "expiresAt", "profileRevision");
        assertThat(at(ISSUED.plusSeconds(900), () -> profiles.confirm(user, command, 0, true))).isEqualTo(response);
    }

    @Test void 期限直前は初回成功し丁度と後は無書込で保存成功再送を優先する() {
        Long user = user();
        var response = issue(user, 0);
        Instant expires = response.expiresAt();
        UUID command = UUID.randomUUID();
        var saved = at(expires.minusNanos(1000), () -> diagnosis.birthResult(user, command, response.confirmationRef()));
        assertThat(resultCount(user)).isEqualTo(1);
        at(expires, () -> { rejected(user, response.confirmationRef(), BirthProfileErrorCode.CONFIRMATION_STALE); return null; });
        at(expires.plusNanos(1000), () -> { rejected(user, response.confirmationRef(), BirthProfileErrorCode.CONFIRMATION_STALE); return null; });
        assertThat(at(expires.plusSeconds(1), () -> diagnosis.birthResult(user, command, response.confirmationRef()))).isEqualTo(saved);
        assertThat(resultCount(user)).isEqualTo(1);
    }

    @Test void 未知参照と他人の正規参照は本人の初回callbackと結果を作らない() {
        Long owner = user(), other = user();
        var response = issue(owner, 0);
        at(ISSUED, () -> {
            rejected(owner, UUID.randomUUID(), BirthProfileErrorCode.NOT_FOUND);
            rejected(other, response.confirmationRef(), BirthProfileErrorCode.NOT_FOUND);
            return null;
        });
        assertThat(resultCount(owner)).isZero();
        assertThat(resultCount(other)).isZero();
    }

    private UUID policyVariant(BirthProfileConfirmationEntity source, String purpose, UUID attempt, BirthProfileProofCodec signer) {
        UUID id = UUID.randomUUID();
        var row = source.toBuilder().id(id).purpose(purpose).withdrawalAttemptId(attempt).keyId(signer.keyId())
                .signature(signer.signature(id, source.getUserId(), source.getProfileRevision(), attempt,
                        source.getFingerprint(), purpose, source.getExpiresAt())).build();
        // 正しく署名された異用途/世代もlive policyで拒否されることを独立に検証する。
        assertThat(signer.authentic(row)).isTrue();
        return confirmations.saveAndFlush(row).getId();
    }

    @Test void 正しい署名でも異用途異世代と旧鍵の参照は初回書込を認めない() {
        Long user = user();
        var response = issue(user, 0);
        var source = confirmations.findByIdAndUserId(response.confirmationRef(), user).orElseThrow();
        byte[] syntheticEncryption = new byte[32], syntheticOldHmac = new byte[32];
        Arrays.fill(syntheticEncryption, (byte) 17);
        Arrays.fill(syntheticOldHmac, (byte) 23);
        var oldSigner = new BirthProfileProofCodec(new EncryptionService(syntheticEncryption, syntheticOldHmac));
        assertThat(oldSigner.keyId()).isNotEqualTo(proof.keyId());
        List<UUID> invalid = List.of(policyVariant(source, "DIAGNOSIS", source.getWithdrawalAttemptId(), proof),
                policyVariant(source, "BIRTH_STYLE", UUID.randomUUID(), proof),
                policyVariant(source, "BIRTH_STYLE", source.getWithdrawalAttemptId(), oldSigner));
        at(ISSUED, () -> {
            for (UUID ref : invalid) {
                rejected(user, ref, BirthProfileErrorCode.CONFIRMATION_STALE);
                var initialWrites = new AtomicInteger();
                // coreの保存成功supplierはlive署名/用途/世代検査より先。正式mapping成功fixtureではない。
                assertThat(profiles.withConfirmedBirthProfile(user, ref, () -> Optional.of("保存済み"), numbers -> {
                    initialWrites.incrementAndGet();
                    return "初回";
                })).isEqualTo("保存済み");
                assertThat(initialWrites).hasValue(0);
            }
            return null;
        });
        assertThat(resultCount(user)).isZero();
    }

    @Test void プロフィール改版は旧確認の新命令だけ失効させ保存済み結果を保持する() throws Exception {
        Long user = user();
        var response = issue(user, 0);
        UUID command = UUID.randomUUID();
        var saved = at(ISSUED, () -> diagnosis.birthResult(user, command, response.confirmationRef()));
        profiles.update(user, UUID.randomUUID(), new BirthProfileUpdateRequest("山田", "花子", "ヤマダ", "ハナコ", "1990-01-22", 0));
        at(ISSUED, () -> { rejected(user, response.confirmationRef(), BirthProfileErrorCode.CONFIRMATION_STALE); return null; });
        assertThat(diagnosis.birthResult(user, command, response.confirmationRef())).isEqualTo(saved);
        assertThat(diagnosis.getResult(user, saved.id())).isEqualTo(saved);
        assertThat(resultCount(user)).isEqualTo(1);
        var publicJson = mapper.valueToTree(saved);
        assertThat(publicJson.has("sourceProfileRevision")).isFalse();
        assertThat(publicJson.has("confirmationRef")).isFalse();
        assertThat(publicJson.has("fingerprint")).isFalse();
        assertThat(publicJson.has("birthDate")).isFalse();
        assertThat(issue(user, 1).profileRevision()).isEqualTo("1");
    }
}
