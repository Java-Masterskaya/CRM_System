package ru.practicum.crm.request.domain;

/**
 * Статусы жизненного цикла заявки по ТЗ §5.1.
 *
 * <p>Набор закрытый. Допустимые переходы между статусами задаёт матрица переходов (T-043),
 * здесь фиксируется только перечень значений и то, какие из них терминальные.
 */
public enum RequestStatus {

    NEW(false),
    CONTACTED(false),
    IN_PROGRESS(false),
    ON_HOLD(false),
    DONE(true),
    REJECTED(true),
    CANCELLED(true);

    private final boolean terminal;

    RequestStatus(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }
}
