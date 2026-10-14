package ru.practicum.crm.analytics.api.dto;

import java.time.LocalDate;

public record InboundPoint(LocalDate date, long count) {
}
