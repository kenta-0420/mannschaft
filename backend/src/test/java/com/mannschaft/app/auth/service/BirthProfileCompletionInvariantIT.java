package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.ParentalConsentLinkStatus;
import com.mannschaft.app.auth.dto.BirthProfileUpdateRequest;
import com.mannschaft.app.auth.entity.ParentalConsentLinkEntity;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.BirthProfileCommandRepository;
import com.mannschaft.app.auth.repository.ParentalConsentLinkRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.common.timezone.TimezoneContextHolder;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 実Facade/Runner/暗号Service/MySQLでDOB補完と既存同意境界を検証する。 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class BirthProfileCompletionInvariantIT extends AbstractMySqlIntegrationTest {
    @Autowired private BirthProfileFacade profiles;
    @Autowired private UserRepository users;
    @Autowired private ParentalConsentLinkRepository links;
    @Autowired private BirthProfileCommandRepository commands;
    @Autowired private EncryptionService encryption;
    @Autowired private PlatformTransactionManager transactions;

    private Long user(String date) {
        return users.saveAndFlush(UserEntity.builder().email(UUID.randomUUID() + "@completion.invalid")
                .lastName("元姓").firstName("元名").lastNameKana("モト").firstNameKana("ナマエ")
                .birthDate(date).birthYear(date == null ? null : LocalDate.parse(date).getYear())
                .displayName("保持する表示名").locale("ja").timezone("Asia/Tokyo").isSearchable(false)
                .status(UserEntity.UserStatus.ACTIVE).build()).getId();
    }
    private UserEntity actual(Long id) {
        return new TransactionTemplate(transactions).execute(status -> users.findById(id).orElseThrow());
    }
    private BirthProfileUpdateRequest input(LocalDate date) {
        return new BirthProfileUpdateRequest("更新姓", "更新名", "コウシン", "ナマエ", date.toString(), 0);
    }
    private Long approval(Long child) {
        Long parent = user(LocalDate.now(TimezoneContextHolder.get()).minusYears(30).toString());
        String token = UUID.randomUUID().toString().replace("-", "");
        links.saveAndFlush(ParentalConsentLinkEntity.builder().childUserId(child).parentUserId(parent)
                .parentEmail(UUID.randomUUID() + "@guardian.invalid").tokenHash(token + token)
                .status(ParentalConsentLinkStatus.APPROVED).expiresAt(LocalDateTime.now().minusDays(1)).build());
        return parent;
    }
    private void rejected(Long id, UUID key, BirthProfileUpdateRequest input, String code) {
        assertThatThrownBy(() -> profiles.update(id, key, input)).isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode().getCode()).isEqualTo(code));
        assertThat(commands.findByUserIdAndCommandId(id, key)).isEmpty();
    }
    private void unchanged(UserEntity before, UserEntity after) {
        assertThat(after.getLastName()).isEqualTo(before.getLastName());
        assertThat(after.getFirstName()).isEqualTo(before.getFirstName());
        assertThat(after.getLastNameKana()).isEqualTo(before.getLastNameKana());
        assertThat(after.getFirstNameKana()).isEqualTo(before.getFirstNameKana());
        assertThat(after.getBirthDate()).isEqualTo(before.getBirthDate());
        assertThat(after.getBirthYear()).isEqualTo(before.getBirthYear());
        assertThat(after.getBirthProfileVersion()).isEqualTo(before.getBirthProfileVersion());
        assertThat(after.getStatus()).isEqualTo(before.getStatus());
    }

    @Test void futureAndOverHundredYearInputsLeaveProfileAndCommandUnchanged() {
        LocalDate today = LocalDate.now(TimezoneContextHolder.get());
        Long id = user(today.minusYears(30).toString()); UserEntity before = actual(id);
        for (LocalDate date : new LocalDate[]{today.plusDays(1), today.minusYears(100).minusDays(1)}) {
            rejected(id, UUID.randomUUID(), input(date), "BIRTHPROFILE_001"); unchanged(before, actual(id));
        }
    }
    @Test void nullAndAdultToMinorWithoutApprovalLeaveAllFiveFieldsYearAndRevisionUnchanged() {
        LocalDate today = LocalDate.now(TimezoneContextHolder.get());
        for (String oldDate : new String[]{null, today.minusYears(30).toString()}) {
            Long id = user(oldDate); UserEntity before = actual(id);
            rejected(id, UUID.randomUUID(), input(today.minusYears(10)), "BIRTHPROFILE_009");
            unchanged(before, actual(id));
        }
    }
    @Test void approvedMinorCorrectionPreservesOtherProfileAndSynchronizesHashesYearAndOneRevision() {
        LocalDate today = LocalDate.now(TimezoneContextHolder.get());
        Long id = user(today.minusYears(10).toString()); approval(id);
        LocalDate corrected = today.minusYears(11); UUID key = UUID.randomUUID();
        assertThat(profiles.update(id, key, input(corrected)).revision()).isEqualTo("1");
        UserEntity after = actual(id);
        assertThat(after.getBirthDate()).isEqualTo(corrected.toString()); assertThat(after.getBirthYear()).isEqualTo(corrected.getYear());
        assertThat(after.getLastName()).isEqualTo("更新姓"); assertThat(after.getFirstName()).isEqualTo("更新名");
        assertThat(after.getLastNameKana()).isEqualTo("コウシン"); assertThat(after.getFirstNameKana()).isEqualTo("ナマエ");
        assertThat(after.getLastNameHash()).isEqualTo(encryption.hmac("更新姓"));
        assertThat(after.getFirstNameHash()).isEqualTo(encryption.hmac("更新名"));
        assertThat(after.getBirthDateHash()).isEqualTo(encryption.hmac(corrected.toString()));
        assertThat(after.getBirthProfileVersion()).isEqualTo(1); assertThat(after.getStatus()).isEqualTo(UserEntity.UserStatus.ACTIVE);
        assertThat(after.getDisplayName()).isEqualTo("保持する表示名"); assertThat(after.getLocale()).isEqualTo("ja");
        assertThat(after.getTimezone()).isEqualTo("Asia/Tokyo"); assertThat(after.getIsSearchable()).isFalse();
        assertThat(commands.findByUserIdAndCommandId(id, key).orElseThrow().getResponseSnapshot())
                .doesNotContain("更新姓", "更新名", "コウシン", corrected.toString());
    }
    @Test void successfulAckReplaysBeforeCurrentConsentAndNewCommandRequiresConsent() {
        LocalDate today = LocalDate.now(TimezoneContextHolder.get());
        Long id = user(today.minusYears(10).toString()); Long parent = approval(id); UUID key = UUID.randomUUID();
        var input = input(today.minusYears(11)); var saved = profiles.update(id, key, input);
        // 本番解除APIの最終保護者ルールは変えない。fixture状態を変えて再送優先順位だけを検証する。
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            users.findByIdForUpdate(id).orElseThrow(); links.findByChildUserIdForUpdate(id).getFirst().revoke(parent);
        });
        assertThat(profiles.update(id, key, input)).isEqualTo(saved);
        UserEntity before = actual(id);
        var next = new BirthProfileUpdateRequest("次姓", "次名", "ツギ", "ナマエ", today.minusYears(12).toString(), 1);
        rejected(id, UUID.randomUUID(), next, "BIRTHPROFILE_009"); unchanged(before, actual(id));
    }
}
