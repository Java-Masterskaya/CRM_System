package ru.practicum.crm.analytics.api.dto;

import java.util.List;

public record InboundByDayReportResponse(List<InboundSeries> series) {
    public InboundByDayReportResponse {
        series = series != null ? List.copyOf(series) : List.of();
    }
}
