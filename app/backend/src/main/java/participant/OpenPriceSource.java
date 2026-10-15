package participant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import model.Prices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Resolves the venue's anchor: the official market-open price, in long units of $0.0001, that
 * the maker centres its first quotes on and the terminal draws as its market-open reference.
 *
 * <p>Single source of truth with the frontend (P11): it reads {@code dailyBar.o} from the same
 * Alpaca snapshot ({@code data.alpaca.markets/v2/stocks/<sym>/snapshot?feed=iex}) and converts
 * it the same way (round to the cent, scale to units), so bots and terminal share one number.
 * The frontend goes through the Vite dev proxy because a browser cannot hold the credentials;
 * this harness runs on the user's machine and calls Alpaca directly with the same two headers.
 *
 * <p>Credentials come from the environment ({@code ALPACA_KEY_ID} / {@code ALPACA_SECRET_KEY})
 * when set, else from the frontend's {@code .env.local} (the file the Vite proxy already reads),
 * so there is exactly one place the keys live. The parsing and unit conversion are pulled out as
 * pure static methods so they can be unit-tested with no network.
 */
public final class OpenPriceSource {

    private static final Logger log = LoggerFactory.getLogger(OpenPriceSource.class);

    private static final String HOST = "https://data.alpaca.markets";
    private static final String FEED = "iex";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    /** Alpaca API credentials. */
    public record Credentials(String keyId, String secretKey) { }

    // --- pure helpers (unit-tested) -------------------------------------------------------

    /**
     * USD open dollars -> long units of $0.0001, matching the frontend's
     * {@code parsePrice(o.toFixed(2))}: round to the nearest cent, then scale by 10,000. The
     * result is always on the one-cent tick. Returns empty for a non-finite or non-positive open.
     */
    public static OptionalLong openUnits(double openDollars) {
        if (!Double.isFinite(openDollars) || openDollars <= 0.0) {
            return OptionalLong.empty();
        }
        long cents = Math.round(openDollars * 100.0);      // round at the cent, like toFixed(2)
        if (cents <= 0) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(cents * (Prices.SCALE / 100L));   // cents -> units of $0.0001
    }

    /** Extract {@code dailyBar.o} from an Alpaca snapshot JSON string and convert to units. */
    OptionalLong parseSnapshot(String json) throws IOException {
        JsonNode root = mapper.readTree(json);
        JsonNode o = root.path("dailyBar").path("o");
        if (!o.isNumber()) {
            return OptionalLong.empty();
        }
        return openUnits(o.asDouble());
    }

    /**
     * Read the two Alpaca keys: environment variables win; otherwise parse {@code envLocal}
     * (KEY=VALUE lines, '#' comments, optional surrounding quotes). Empty when neither source
     * supplies both keys.
     */
    public static Optional<Credentials> loadCredentials(Path envLocal) {
        String key = System.getenv("ALPACA_KEY_ID");
        String sec = System.getenv("ALPACA_SECRET_KEY");
        if (isSet(key) && isSet(sec)) {
            return Optional.of(new Credentials(key.trim(), sec.trim()));
        }
        if (envLocal != null && Files.isReadable(envLocal)) {
            try {
                List<String> lines = Files.readAllLines(envLocal, StandardCharsets.UTF_8);
                for (String raw : lines) {
                    String line = raw.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int eq = line.indexOf('=');
                    if (eq <= 0) continue;
                    String k = line.substring(0, eq).trim();
                    String v = unquote(line.substring(eq + 1).trim());
                    if ("ALPACA_KEY_ID".equals(k) && !isSet(key)) key = v;
                    else if ("ALPACA_SECRET_KEY".equals(k) && !isSet(sec)) sec = v;
                }
            } catch (IOException e) {
                log.warn("Could not read {}: {}", envLocal, e.toString());
            }
        }
        return (isSet(key) && isSet(sec)) ? Optional.of(new Credentials(key, sec)) : Optional.empty();
    }

    // --- network --------------------------------------------------------------------------

    /**
     * Fetch the market-open anchor for {@code symbol} in units of $0.0001. Throws on any failure
     * (no credentials, non-200, missing field): the maker's whole premise is anchoring to the
     * open, so a silent fallback to an invented price would defeat the point. The caller can
     * instead pass an explicit override (see {@code ParticipantSimMain}).
     */
    public long fetchOpenUnits(String symbol, Credentials creds) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(HOST + "/v2/stocks/" + symbol + "/snapshot?feed=" + FEED))
                .header("APCA-API-KEY-ID", creds.keyId())
                .header("APCA-API-SECRET-KEY", creds.secretKey())
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new IOException("Alpaca snapshot returned HTTP " + res.statusCode() + " for " + symbol);
        }
        OptionalLong units = parseSnapshot(res.body());
        if (units.isEmpty()) {
            throw new IOException("Alpaca snapshot for " + symbol + " had no usable dailyBar.o");
        }
        long u = units.getAsLong();
        log.info("Market-open anchor for {}: {} units ($0.0001) = ${}", symbol, u, u / (double) Prices.SCALE);
        return u;
    }

    private static boolean isSet(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static String unquote(String v) {
        if (v.length() >= 2
                && ((v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"')
                 || (v.charAt(0) == '\'' && v.charAt(v.length() - 1) == '\''))) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }
}
