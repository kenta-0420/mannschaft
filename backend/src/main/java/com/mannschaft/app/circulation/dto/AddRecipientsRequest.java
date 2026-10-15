package com.mannschaft.app.circulation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 受信者追加リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class AddRecipientsRequest {

    @NotNull
    @Size(min = 1)
    private final List<RecipientEntry> recipients;
}
