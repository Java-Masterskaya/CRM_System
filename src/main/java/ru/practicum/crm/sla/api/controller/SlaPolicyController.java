package ru.practicum.crm.sla.api.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.practicum.crm.common.pagination.PageRequests;
import ru.practicum.crm.common.pagination.PageResponse;
import ru.practicum.crm.sla.api.dto.SlaPolicyRequest;
import ru.practicum.crm.sla.api.dto.SlaPolicyResponse;
import ru.practicum.crm.sla.api.dto.SlaTermsRequest;
import ru.practicum.crm.sla.domain.SlaPolicy;
import ru.practicum.crm.sla.domain.SlaTerms;
import ru.practicum.crm.sla.service.SlaPolicyService;
import ru.practicum.crm.tenant.api.TenantContext;

/**
 * Политики сроков арендатора для администратора (T-055): просмотр, создание и изменение сроков.
 * Право {@code SLA_POLICY_MANAGE} проверяет сервис, без него — 403.
 *
 * <p>Список — страница по общему контракту, в порядке создания; {@code sort} не принимается.
 * Удаления нет: по ТЗ политики создают, изменяют и просматривают.
 */
@RestController
@RequestMapping("/admin/sla-policies")
public class SlaPolicyController {

    private final SlaPolicyService service;

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "Контекст арендатора — Spring-бин, внедряется как зависимость. "
                    + "SpotBugs считает его изменяемым из-за метода setTenantId")
    private final TenantContext tenantContext;

    public SlaPolicyController(SlaPolicyService service, TenantContext tenantContext) {
        this.service = service;
        this.tenantContext = tenantContext;
    }

    @GetMapping
    public PageResponse<SlaPolicyResponse> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");
        return PageResponse.of(service.policies(tenantContext.getCurrentTenantId(),
                        PageRequests.toPageable(page, size, sort == null ? null : List.of(sort),
                                Set.of()))
                .map(SlaPolicyController::toResponse));
    }

    /**
     * Политика для пары «тип + приоритет» или, если оба не заданы, политика по умолчанию. Повтор
     * пары или второй политики по умолчанию — 409.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SlaPolicyResponse create(@Valid @RequestBody SlaPolicyRequest body) {
        UUID tenantId = tenantContext.getCurrentTenantId();
        SlaTerms terms = new SlaTerms(body.firstResponseMinutes(), body.resolutionMinutes());
        SlaPolicy created = body.forDefaultPolicy()
                ? service.createDefault(tenantId, terms)
                : service.create(tenantId, body.typeId(), body.priority(), terms);
        return toResponse(created);
    }

    @PutMapping("/{policyId}")
    public SlaPolicyResponse changeTerms(@PathVariable UUID policyId,
            @Valid @RequestBody SlaTermsRequest body) {
        return toResponse(service.changeTerms(tenantContext.getCurrentTenantId(), policyId,
                new SlaTerms(body.firstResponseMinutes(), body.resolutionMinutes())));
    }

    private static SlaPolicyResponse toResponse(SlaPolicy policy) {
        return new SlaPolicyResponse(policy.getId(), policy.getTypeId(), policy.getPriority(),
                policy.isDefault(), policy.getFirstResponseMinutes(),
                policy.getResolutionMinutes(), policy.getCreatedAt(), policy.getUpdatedAt());
    }
}
