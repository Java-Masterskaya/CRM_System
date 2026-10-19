package ru.practicum.crm.request.domain;

/**
 * Статусы жизненного цикла заявки по ТЗ §5.1.
 *
 * <p>Набор закрытый. Допустимые переходы между статусами задаёт матрица переходов (T-043),
 * здесь фиксируется только перечень значений, какие из них терминальные и как статус называется
 * для клиента.
 */
public enum RequestStatus {

    NEW(false, "Получена"),
    CONTACTED(false, "Детали уточнены"),
    IN_PROGRESS(false, "В работе"),
    ON_HOLD(false, "Ждём информацию от вас"),
    DONE(true, "Выполнена"),
    REJECTED(true, "Отклонена"),
    CANCELLED(true, "Отменена");

    private final boolean terminal;
    private final String title;

    RequestStatus(boolean terminal, String title) {
        this.terminal = terminal;
        this.title = title;
    }

    public boolean isTerminal() {
        return terminal;
    }

    /**
     * Название для клиента — в письме о смене статуса (T-070). По смыслу статуса из ТЗ §5.1, с
     * точки зрения клиента: {@code ON_HOLD} — «ждём информацию от вас».
     */
    public String title() {
        return title;
    }
}
