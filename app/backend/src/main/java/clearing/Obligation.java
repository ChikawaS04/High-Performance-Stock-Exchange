package clearing;

/**
 * One participant's netted fills in one symbol for one business date.
 *
 * @param netQuantity    bought minus sold
 * @param cashObligation the value bought minus the value sold, in units of $0.0001: positive means the
 *                       participant pays, negative means it receives
 */
public record Obligation(long participantId, String symbol, long boughtQuantity, long soldQuantity, long netQuantity,
                         long cashObligation) {
}
