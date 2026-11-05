package session;

import config.ExchangeConfig;
import engine.SessionRules;

/** Reference data from the {@code SESSION_*} variables, for a venue with no database: every date trades. */
public final class ConfigReferenceSource implements ReferenceSource {

    private final SessionReference reference;

    public ConfigReferenceSource(ExchangeConfig config) {
        this(SessionReference.fromConfig(config));
    }

    public ConfigReferenceSource(SessionReference reference) {
        this.reference = reference;
    }

    @Override
    public SessionDay load(int businessDate) {
        return new SessionDay(reference, true);
    }

    @Override
    public void checkAtStartup() {
        int verdict = SessionRules.checkReference(reference.symbolBytes(), reference.tickSize(),
                reference.lotSize(), reference.priceScale());
        if (verdict != SessionRules.OK) {
            throw new ReferenceDataException("The session reference data is not one this engine can honour: "
                    + SessionRules.describe(verdict) + ". " + remedy());
        }
    }

    @Override
    public String remedy() {
        return "Set " + ExchangeConfig.SESSION_SYMBOL + ", " + ExchangeConfig.SESSION_TICK_SIZE + ", "
                + ExchangeConfig.SESSION_LOT_SIZE + " and " + ExchangeConfig.SESSION_PRICE_SCALE
                + " accordingly (or unset them for the defaults).";
    }
}
