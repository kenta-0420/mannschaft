package com.mannschaft.app.auth.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.BirthProfileErrorCode;
import com.mannschaft.app.auth.UserOperationErrorCode;
import com.mannschaft.app.auth.ParentalConsentLinkStatus;
import com.mannschaft.app.auth.dto.BirthProfileConfirmationResponse;
import com.mannschaft.app.auth.dto.BirthProfileResponse;
import com.mannschaft.app.auth.dto.BirthProfileUpdateRequest;
import com.mannschaft.app.auth.dto.BirthProfileUpdateResponse;
import com.mannschaft.app.auth.dto.ConfirmedBirthNumbers;
import com.mannschaft.app.auth.entity.BirthProfileCommandEntity;
import com.mannschaft.app.auth.entity.BirthProfileConfirmationEntity;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.BirthProfileCommandRepository;
import com.mannschaft.app.auth.repository.BirthProfileConfirmationRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.repository.ParentalConsentLinkRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.common.timezone.TimezoneContextHolder;
import com.mannschaft.app.common.util.AgeGroupCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/** 出生原情報の処理をusers先ロックの同一auth TXで完結させる。 */
@Service
@RequiredArgsConstructor
public class BirthProfileRunner {
    private static final String PURPOSE = "BIRTH_STYLE";
    private final UserRowLockService rowLock;
    private final UserRepository users;
    private final ParentalConsentLinkRepository parentalLinks;
    private final BirthProfileCommandRepository commands;
    private final BirthProfileConfirmationRepository confirmations;
    private final EncryptionService encryption;
    private final BirthProfileProofCodec proof;
    private final BirthStyleCalculator calculator;
    private final ObjectMapper mapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BirthProfileResponse read(Long userId) {
        UserEntity user = activeUser(userId);
        return new BirthProfileResponse(user.getLastName(), user.getFirstName(), user.getLastNameKana(),
                user.getFirstNameKana(), user.getBirthDate(), Long.toString(user.getBirthProfileVersion()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BirthProfileUpdateResponse update(Long userId, UUID commandId, BirthProfileUpdateRequest request) {
        UserEntity user = activeUser(userId);
        if (commandId == null || request == null || request.revision() < 0) throw invalid();
        String keyId = proof.keyId();
        String requestHash = encryption.hmac(encoded("birth-profile:put:v1", Long.toString(request.revision()),
                request.lastName(), request.firstName(), request.lastNameKana(), request.firstNameKana(), request.birthDate()));
        var replay = commands.findByUserIdAndCommandId(userId, commandId);
        if (replay.isPresent()) {
            BirthProfileCommandReplay.verify(replay.get(), BirthProfileCommandReplay.RAW_PROFILE_PUT, keyId, requestHash);
            return decode(replay.get().getResponseSnapshot(), BirthProfileUpdateResponse.class);
        }
        if (user.getBirthProfileVersion() != request.revision()) {
            throw new BusinessException(BirthProfileErrorCode.VERSION_CONFLICT);
        }
        LocalDate date = date(request.birthDate(), BirthProfileErrorCode.INVALID_INPUT);
        // live入力の既存日付範囲は成功再送の後。年月経過で旧ACKを無効化しない。
        LocalDate today = LocalDate.now(TimezoneContextHolder.get());
        if (date.isAfter(today) || date.isBefore(today.minusYears(100))) throw invalid();
        validateNames(request.lastName(), request.firstName(), request.lastNameKana(), request.firstNameKana());
        try { calculator.calculate(date, request.lastNameKana(), request.firstNameKana()); }
        catch (IllegalArgumentException error) { throw invalid(); }
        if (AgeGroupCalculator.isMinor(date, today) && parentalLinks.findByChildUserIdForUpdate(userId).stream()
                .noneMatch(link -> link.getStatus() == ParentalConsentLinkStatus.APPROVED)) {
            // 既存有効同意の正本と同じAPPROVED条件。新statusや通知を作らず更新前に拒否する。
            throw new BusinessException(BirthProfileErrorCode.PARENTAL_CONSENT_REQUIRED);
        }
        user.updateBirthProfile(request.lastName(), request.firstName(), request.lastNameKana(), request.firstNameKana(),
                request.birthDate(), encryption.hmac(request.lastName()), encryption.hmac(request.firstName()),
                encryption.hmac(request.birthDate()), date.getYear());
        BirthProfileUpdateResponse response = new BirthProfileUpdateResponse(Long.toString(user.getBirthProfileVersion()));
        saveCommand(userId, commandId, BirthProfileCommandReplay.RAW_PROFILE_PUT, keyId, requestHash, response);
        return response;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BirthProfileConfirmationResponse confirm(Long userId, UUID commandId, long revision, boolean useConfirmed) {
        UserEntity user = activeUser(userId);
        if (commandId == null || revision < 0 || !useConfirmed) throw invalid();
        // 原情報を含まない確認命令は鍵交代後も同じ入力を比較できる。
        String requestHash = stableHash(encoded("birth-profile:confirm:v1", Long.toString(revision), "true"));
        var replay = commands.findByUserIdAndCommandId(userId, commandId);
        if (replay.isPresent()) {
            BirthProfileCommandReplay.verify(replay.get(), BirthProfileCommandReplay.CONFIRM_BIRTH_STYLE, null, requestHash);
            return decode(replay.get().getResponseSnapshot(), BirthProfileConfirmationResponse.class);
        }
        if (user.getBirthProfileVersion() != revision) throw new BusinessException(BirthProfileErrorCode.VERSION_CONFLICT);
        LocalDate birthDate = completeDate(user);
        Instant now = BirthProfileProofCodec.micros(Instant.now());
        Instant expiresAt = now.plusSeconds(600);
        UUID id = UuidV7.generate();
        String fingerprint = fingerprint(user, birthDate);
        BirthProfileConfirmationEntity confirmation = BirthProfileConfirmationEntity.builder().id(id)
                .userId(userId).profileRevision(revision).withdrawalAttemptId(user.getWithdrawalAttemptId())
                .fingerprint(fingerprint).keyId(proof.keyId()).purpose(PURPOSE).expiresAt(expiresAt)
                .signature(proof.signature(id, userId, revision, user.getWithdrawalAttemptId(), fingerprint, PURPOSE, expiresAt))
                .createdAt(now).updatedAt(now).build();
        confirmations.saveAndFlush(confirmation);
        BirthProfileConfirmationResponse response = new BirthProfileConfirmationResponse(id, expiresAt, Long.toString(revision));
        saveCommand(userId, commandId, BirthProfileCommandReplay.CONFIRM_BIRTH_STYLE, null, requestHash, response);
        return response;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public <T> T withConfirmedBirthProfile(Long userId, UUID ref, Supplier<Optional<T>> successfulReplay,
            Function<ConfirmedBirthNumbers, T> initialWrite) {
        UserEntity user = activeUser(userId);
        if (ref == null || successfulReplay == null || initialWrite == null) throw invalid();
        // ACTIVE認可後の独立PRIMARY読取。保存成功は古い確認や鍵交代より先に返す。
        Optional<T> saved = successfulReplay.get();
        if (saved.isPresent()) return saved.get();
        BirthProfileConfirmationEntity confirmation = confirmations.findByIdAndUserId(ref, userId)
                .orElseThrow(() -> new BusinessException(BirthProfileErrorCode.NOT_FOUND));
        Instant now = BirthProfileProofCodec.micros(Instant.now());
        if (!PURPOSE.equals(confirmation.getPurpose()) || !now.isBefore(confirmation.getExpiresAt())
                || confirmation.getProfileRevision() != user.getBirthProfileVersion()
                || !Objects.equals(confirmation.getWithdrawalAttemptId(), user.getWithdrawalAttemptId())
                || !proof.authentic(confirmation)) throw stale();
        LocalDate birthDate = completeDate(user);
        if (!proof.sameFingerprint(fingerprint(user, birthDate), confirmation.getFingerprint())) throw stale();
        var numbers = calculator.calculate(birthDate, user.getLastNameKana(), user.getFirstNameKana());
        // callbackは診断の独立REQUIRES_NEWだけ。原5欄やfingerprintを渡さない。
        return initialWrite.apply(new ConfirmedBirthNumbers(numbers, user.getBirthProfileVersion()));
    }

    private UserEntity activeUser(Long id) {
        if (id == null || rowLock.lock(id) != UserRowLockService.UserState.ACTIVE) {
            throw new BusinessException(UserOperationErrorCode.NOT_ALLOWED);
        }
        return users.findById(id).orElseThrow(() -> new BusinessException(UserOperationErrorCode.NOT_ALLOWED));
    }

    private LocalDate completeDate(UserEntity user) {
        try {
            validateNames(user.getLastName(), user.getFirstName(), user.getLastNameKana(), user.getFirstNameKana());
            LocalDate date = date(user.getBirthDate(), BirthProfileErrorCode.PROFILE_INCOMPLETE);
            calculator.calculate(date, user.getLastNameKana(), user.getFirstNameKana());
            return date;
        } catch (BusinessException | IllegalArgumentException error) {
            throw new BusinessException(BirthProfileErrorCode.PROFILE_INCOMPLETE);
        }
    }

    private String fingerprint(UserEntity user, LocalDate date) {
        return proof.fingerprint(user.getLastName(), user.getFirstName(), user.getLastNameKana(), user.getFirstNameKana(), date);
    }

    private void saveCommand(Long userId, UUID commandId, String type, String keyId, String hash, Object response) {
        Instant now = BirthProfileProofCodec.micros(Instant.now());
        commands.saveAndFlush(BirthProfileCommandEntity.builder().id(UuidV7.generate()).userId(userId).commandId(commandId)
                .commandType(type).requestKeyId(keyId).requestHash(hash).responseSnapshot(encode(response))
                .createdAt(now).updatedAt(now).build());
    }

    private String encode(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new BusinessException(BirthProfileErrorCode.UNAVAILABLE); }
    }
    private <T> T decode(String value, Class<T> type) {
        try { return mapper.readValue(value, type); }
        catch (JsonProcessingException error) { throw new BusinessException(BirthProfileErrorCode.UNAVAILABLE); }
    }
    private static String encoded(String purpose, String... values) {
        StringBuilder result = new StringBuilder(purpose);
        for (String value : values) {
            if (value == null) throw invalid();
            result.append('|').append(value.length()).append(':').append(value);
        }
        return result.toString();
    }
    private static String stableHash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException("命令比較方式が使用できません"); }
    }
    private static void validateNames(String... names) {
        for (String name : names) {
            if (name == null || name.isBlank() || name.length() > 100 || name.codePoints().anyMatch(Character::isISOControl)) {
                throw invalid();
            }
        }
    }
    private static LocalDate date(String value, BirthProfileErrorCode errorCode) {
        if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new BusinessException(errorCode);
        try {
            LocalDate result = LocalDate.parse(value);
            if (result.getYear() < 1 || !result.toString().equals(value)) throw new BusinessException(errorCode);
            return result;
        } catch (DateTimeParseException error) { throw new BusinessException(errorCode); }
    }
    private static BusinessException invalid() { return new BusinessException(BirthProfileErrorCode.INVALID_INPUT); }
    private static BusinessException stale() { return new BusinessException(BirthProfileErrorCode.CONFIRMATION_STALE); }
}
