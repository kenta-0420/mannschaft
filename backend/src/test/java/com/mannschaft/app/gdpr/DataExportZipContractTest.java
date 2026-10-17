package com.mannschaft.app.gdpr;

import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.gdpr.dto.PrefetchedPersonalData;
import com.mannschaft.app.gdpr.dto.UserEmailInfo;
import com.mannschaft.app.gdpr.entity.DataExportEntity;
import com.mannschaft.app.gdpr.repository.DataExportRepository;
import com.mannschaft.app.gdpr.service.DataExportService;
import com.mannschaft.app.gdpr.service.PersonalDataCollector;
import com.mannschaft.app.mail.outbox.EmailOutboxRequest;
import com.mannschaft.app.mail.outbox.EmailOutboxService;
import net.lingala.zip4j.io.inputstream.ZipInputStream;
import net.lingala.zip4j.model.LocalFileHeader;
import net.lingala.zip4j.model.enums.AesKeyStrength;
import net.lingala.zip4j.model.enums.AesVersion;
import net.lingala.zip4j.model.enums.EncryptionMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 新規出力の実ZIPバイトとメールpasswordの対応を小さい入力で検証する。 */
@ExtendWith(MockitoExtension.class)
class DataExportZipContractTest {
    @Mock private DataExportRepository repository;
    @Mock private PersonalDataCollector collector;
    @Mock private StorageService storage;
    @Mock private EmailOutboxService outbox;
    @Mock private org.springframework.transaction.PlatformTransactionManager transactionManager;
    @InjectMocks private DataExportService service;

    private DataExportEntity entity;
    private static final long EXPORT_ID = 123L;
    private static final UserEmailInfo USER = new UserEmailInfo(
            9001315L, "zip-contract@example.com", "試験", "本人", PrefetchedPersonalData.none());

    @BeforeEach
    void 準備() {
        entity = DataExportEntity.builder().userId(USER.userId()).status("PENDING").build();
        given(repository.findById(EXPORT_ID)).willReturn(Optional.of(entity));
    }

    @Test
    void 正常_全entryがAES256AE2で空UnicodeとJSON値を復元する() throws Exception {
        Map<String, String> input = new LinkedHashMap<>();
        input.put("本人.json", "{\"本文\":\"日本語 😀\"}");
        input.put("empty.json", "");
        input.put("null.json", "null");
        input.put("zero.json", "0");
        input.put("list.json", "[]");
        byte[] archive = run(input);
        String password = password();

        assertThat(entity.getStatus()).isEqualTo("COMPLETED");
        assertThat(password).hasSize(12).matches("[A-Za-z0-9!@#$%]{12}");
        assertThat(BCrypt.checkpw(password, entity.getZipPasswordHash())).isTrue();
        assertThat(read(archive, password.toCharArray(), true)).isEqualTo(input);
        byte[] fallback = ReflectionTestUtils.invokeMethod(service, "generateMetadataOnlyZip", input, password);
        assertThat(read(fallback, password.toCharArray(), true)).isEqualTo(input);
    }

    @Test
    void 異常_password無しで内容を読めない() {
        byte[] archive = run(Map.of("profile.json", "{\"text\":\"本人\"}"));
        assertThatThrownBy(() -> read(archive, null, false)).isInstanceOf(IOException.class);
    }

    @Test
    void 異常_誤passwordで内容を読めない() {
        byte[] archive = run(Map.of("profile.json", "{\"text\":\"本人\"}"));
        assertThatThrownBy(() -> read(archive, "wrong-password".toCharArray(), false))
                .isInstanceOf(IOException.class);
    }

    @Test
    void 異常_暗号文の認証コード改ざんを拒否する() throws Exception {
        byte[] archive = run(Map.of("profile.json", "{\"text\":\"owned\"}"));
        String password = password();
        // 最初のdata descriptor直前はAE-2の認証コード末尾。ZIP構造を保った改ざんにする。
        int descriptor = findSignature(archive, 0x50, 0x4b, 0x07, 0x08);
        assertThat(descriptor).isGreaterThan(0);
        archive[descriptor - 1] ^= 1;
        assertThatThrownBy(() -> read(archive, password.toCharArray(), false))
                .isInstanceOf(IOException.class);
    }

    @Test
    void 異常_JavaNullは空文字へ変換せずFAILEDにする() {
        Map<String, String> input = new LinkedHashMap<>();
        input.put("invalid.json", null);
        given(collector.collect(any(), any(), any())).willReturn(input);
        service.processExportAsync(EXPORT_ID, USER, Set.of("profile"));
        assertThat(entity.getStatus()).isEqualTo("FAILED");
        verify(storage, never()).upload(any(), any(), any());
        verify(outbox).enqueue(org.mockito.ArgumentMatchers.argThat(
                request -> request.templateKind().equals("GDPR_EXPORT_FAILED")));
    }

    @Test
    void 異常_upload失敗はREADYを出さずFAILEDにする() {
        given(collector.collect(any(), any(), any())).willReturn(Map.of("a.json", "[]"));
        org.mockito.Mockito.doThrow(new IllegalStateException("owned upload failure"))
                .when(storage).upload(any(), any(), any());
        service.processExportAsync(EXPORT_ID, USER, Set.of("profile"));
        assertThat(entity.getStatus()).isEqualTo("FAILED");
        verify(outbox, never()).enqueue(org.mockito.ArgumentMatchers.argThat(
                request -> request.templateKind().equals("GDPR_EXPORT_READY")));
    }

    private byte[] run(Map<String, String> input) {
        given(collector.collect(any(), any(), any())).willReturn(input);
        service.processExportAsync(EXPORT_ID, USER, Set.of("profile"));
        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(storage).upload(eq("gdpr-exports/" + USER.userId() + "/" + EXPORT_ID + ".zip"),
                bytes.capture(), eq("application/zip"));
        return bytes.getValue();
    }

    private String password() {
        ArgumentCaptor<EmailOutboxRequest> request = ArgumentCaptor.forClass(EmailOutboxRequest.class);
        verify(outbox).enqueue(request.capture());
        assertThat(request.getValue().templateKind()).isEqualTo("GDPR_EXPORT_READY");
        String body = request.getValue().payloadVars().get("body");
        return body.substring(body.indexOf("<strong>") + 8, body.indexOf("</strong>"));
    }

    private Map<String, String> read(byte[] archive, char[] password, boolean checkHeaders) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive), password)) {
            LocalFileHeader entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (checkHeaders) {
                    assertThat(entry.isEncrypted()).isTrue();
                    assertThat(entry.getEncryptionMethod()).isEqualTo(EncryptionMethod.AES);
                    assertThat(entry.getAesExtraDataRecord().getAesKeyStrength()).isEqualTo(AesKeyStrength.KEY_STRENGTH_256);
                    assertThat(entry.getAesExtraDataRecord().getAesVersion()).isEqualTo(AesVersion.TWO);
                    assertThat(entry.getCrc()).isZero();
                }
                result.put(entry.getFileName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return result;
    }

    private int findSignature(byte[] bytes, int a, int b, int c, int d) {
        for (int i = 0; i < bytes.length - 3; i++) {
            if ((bytes[i] & 255) == a && (bytes[i + 1] & 255) == b
                    && (bytes[i + 2] & 255) == c && (bytes[i + 3] & 255) == d) return i;
        }
        return -1;
    }
}
