package clearing;

import java.util.List;

/**
 * A COMPLETE clearing run, as stored.
 *
 * @param nextBusinessDate the date the balances and positions were rolled to
 * @param obligations      one per participant and symbol with fills, ordered by participant then symbol;
 *                         empty for a day with no fills
 */
public record ClearingResult(int businessDate, long closeOutboundSeq, int nextBusinessDate, long fillCount, long volume,
                             List<Obligation> obligations) {
}
