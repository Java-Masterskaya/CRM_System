package ru.practicum.crm.analytics.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record InboundSeries(String key, List<InboundPoint> points) {
}
