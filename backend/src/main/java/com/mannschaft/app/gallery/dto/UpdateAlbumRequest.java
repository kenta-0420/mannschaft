package com.mannschaft.app.gallery.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;

/**
 * アルバム更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateAlbumRequest {

    @NotBlank
    @Size(max = 200)
    private final String title;

    @Size(max = 500)
    private final String description;

    private final LocalDate eventDate;

    private final String visibility;

    private final Boolean allowMemberUpload;

    private final Boolean allowDownload;

    private final Long coverPhotoId;
}
