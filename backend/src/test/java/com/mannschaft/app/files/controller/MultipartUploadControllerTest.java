package com.mannschaft.app.files.controller;

import com.mannschaft.app.files.service.MultipartUploadService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

@DisplayName("MultipartUploadController テスト")
class MultipartUploadControllerTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("POST /api/v1/files/multipart/start は認証済みでも410を返し副作用を起こさない")
    void startUpload_returnsGoneWithoutCallingService() {
        MultipartUploadService service = mock(MultipartUploadService.class);
        MultipartUploadController controller = new MultipartUploadController(service);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("1", null, List.of()));

        var response = controller.startUpload();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
        verifyNoInteractions(service);
    }
}
