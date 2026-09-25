package com.mannschaft.app.billing.api.dto;
import lombok.Builder;
import lombok.Getter;
import java.util.List;
@Getter
@Builder
public class PriceRevisionPageResponse {
    private List<PriceRevisionSummaryResponse> items;
    private long totalElements;
    public List<PriceRevisionSummaryResponse> items() { return items; }
}
