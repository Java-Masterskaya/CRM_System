package ru.practicum.crm.request.domain;

/**
 * Приоритет заявки (T-035, #36): закрытый набор значений от наименее к наиболее срочному.
 *
 * <p>В базе хранится строковое имя значения, а не порядковый номер: вставка нового значения
 * не должна менять смысл уже записанных строк. Для сортировки по срочности у каждого значения
 * есть ранг — строковый порядок ({@code HIGH}, {@code LOW}, {@code NORMAL}, {@code URGENT})
 * со срочностью не совпадает.
 */
public enum RequestPriority {

    LOW(1),
    NORMAL(2),
    HIGH(3),
    URGENT(4);

    /** Приоритет, если его не указал ни клиент, ни тип заявки. */
    public static final RequestPriority FALLBACK = NORMAL;

    private final short rank;

    RequestPriority(int rank) {
        this.rank = (short) rank;
    }

    /** Чем больше, тем срочнее. */
    public short getRank() {
        return rank;
    }

    /**
     * Приоритет новой заявки: переданный клиентом, иначе приоритет по умолчанию её типа,
     * иначе {@link #FALLBACK}.
     *
     * <p>Результат копируется в заявку, а не вычисляется по ссылке на тип, поэтому смена
     * приоритета по умолчанию у типа не меняет уже созданные заявки.
     */
    public static RequestPriority resolve(RequestPriority requested,
            RequestPriority typeDefault) {
        if (requested != null) {
            return requested;
        }
        return typeDefault != null ? typeDefault : FALLBACK;
    }
}
