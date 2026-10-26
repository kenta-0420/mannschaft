package com.mannschaft.app.gallery.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;

/**
 * アルバム作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateAlbumRequest {

    private final Long teamId;

    private final Long organizationId;

    @NotBlank
    @Size(max = 200)
    private final String title;

    @Size(max = 500)
    private final String description;

    private final LocalDate eventDate;

    private final String visibility;

    private final Boolean allowMemberUpload;

    private final Boolean allowDownload;
}
