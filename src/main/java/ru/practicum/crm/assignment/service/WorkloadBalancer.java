package ru.practicum.crm.assignment.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import ru.practicum.crm.user.api.dto.UserContactDto;

/**
 * Выбор исполнителя по текущей нагрузке (T-051).
 *
 * <p>Кандидаты приходят уже суженными до активных пользователей с правом вести заявки, а их
 * нагрузка — одним агрегирующим запросом. Балансировщик только сравнивает числа и в базу не
 * ходит: выбор одинаково работает в юнит-тестах и в транзакции автоподбора.
 */
public final class WorkloadBalancer {

    private WorkloadBalancer() {
    }

    /**
     * Кандидат с наименьшим числом незавершённых заявок.
     *
     * <p>Кандидат, которого нет в карте нагрузки, считается свободным: незавершённых заявок у
     * него нет. При равной нагрузке выбирается первый в порядке кандидатов — порядок задаёт
     * вызывающий, и одинаковый вход всегда даёт одинаковый выбор. Поэтому разброс нагрузки
     * ограничен: каждая следующая заявка уходит тому, у кого заявок меньше.
     *
     * @param candidates кандидаты; порядок решает ничью по нагрузке
     * @param workload число незавершённых заявок по идентификатору исполнителя
     * @return выбранный кандидат; пусто, если кандидатов нет
     */
    public static Optional<UserContactDto> leastLoaded(List<UserContactDto> candidates,
                                                       Map<UUID, Long> workload) {
        UserContactDto selected = null;
        long selectedLoad = Long.MAX_VALUE;
        for (UserContactDto candidate : candidates) {
            long load = workload.getOrDefault(candidate.id(), 0L);
            if (load < selectedLoad) {
                selected = candidate;
                selectedLoad = load;
            }
        }
        return Optional.ofNullable(selected);
    }
}
