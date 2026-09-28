package ru.practicum.crm.outbox.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.outbox.domain.OutboxEvent;
import ru.practicum.crm.outbox.repository.OutboxEventRepository;

/**
 * Разброс задержки в собранном приложении (#145): настройка {@code app.outbox.retry-jitter}
 * доходит до обработчика, и события, не доставленные одновременно, получают разное время
 * повтора. Отправители — из {@link OutboxProcessorIntegrationTest}.
 *
 * <p>Числа здесь случайные, но исход теста — нет: границы разброса выполняются всегда, а чтобы
 * у десяти событий совпали задержки до долей секунды, нужно невероятное совпадение.
 */
@Import(OutboxProcessorIntegrationTest.Senders.class)
@TestPropertySource(properties = {
    "app.outbox.batch-size=20",
    "app.outbox.retry-delay=1h",
    "app.outbox.max-retry-delay=10h",
    "app.outbox.retry-jitter=0.5",
    "app.outbox.max-attempts=4"
})
class OutboxRetryJitterIntegrationTest extends BaseIntegrationTest {

    private static final String FAILING = "TEST_FAILING";

    @Autowired
    private OutboxProcessor processor;

    @Autowired
    private OutboxEventRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void processBatch_whenEventsFailTogether_spreadsRetriesWithinJitterBounds() {
        UUID tenantId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO tenants (id, name, active, created_at, updated_at)"
                + " VALUES (?, 'Арендатор', true, now(), now())", tenantId);
        for (int i = 0; i < 10; i++) {
            repository.save(new OutboxEvent(tenantId, FAILING, Map.of("n", i)));
        }

        processor.processBatch();

        List<Double> delaySeconds = jdbcTemplate.queryForList("SELECT extract(epoch FROM"
                + " next_attempt_at - updated_at)::float8 FROM outbox_events WHERE status = 'NEW'",
                Double.class);
        assertThat(delaySeconds).hasSize(10)
                .allSatisfy(delay -> assertThat(delay).as("от получаса до часа")
                        .isBetween(1799.0, 3600.0));
        assertThat(new HashSet<>(delaySeconds)).as("повторы не идут одной волной")
                .hasSizeGreaterThan(1);
    }
}
