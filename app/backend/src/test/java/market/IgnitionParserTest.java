package market;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Pure tests of {@link IgnitionParser} (P16-3): exact dollar conversion and the bar's New York date. */
class IgnitionParserTest {

    private static IgnitionParser.Bar parse(String json) {
        return IgnitionParser.parse(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String snapshot(String open, String time) {
        return "{\"symbol\":\"ASML\",\"latestTrade\":{\"p\":150.1,\"t\":\"x\"},"
                + "\"dailyBar\":{\"t\":" + time + ",\"o\":" + open + ",\"h\":151.2,\"c\":150.0,\"v\":12345},"
                + "\"prevDailyBar\":{\"o\":1.0,\"t\":\"2026-11-17T05:00:00Z\"}}";
    }

    @Test
    void aWellFormedSnapshotGivesUnitsAndTheNewYorkDate() {
        IgnitionParser.Bar bar = parse(snapshot("149.8", "\"2026-11-18T05:00:00Z\""));
        assertNotNull(bar);
        assertEquals(1_498_000L, bar.openPx());
        assertEquals(20261118, bar.date());
    }

    @Test
    void conversionIsExactAndRoundsHalfUpAtTheCent() {
        assertEquals(1_498_800L, IgnitionParser.dollarsToUnits("149.875"));
        assertEquals(1_498_700L, IgnitionParser.dollarsToUnits("149.874"));
        assertEquals(10_100L, IgnitionParser.dollarsToUnits("1.005"), "a double would give 1.00");
        assertEquals(1_500_000L, IgnitionParser.dollarsToUnits("150"));
        assertEquals(1_500_000L, IgnitionParser.dollarsToUnits("150.00"));
        assertEquals(7_201_500L, IgnitionParser.dollarsToUnits("720.15"));
        assertEquals(100L, IgnitionParser.dollarsToUnits("0.005"));
        assertEquals(0L, IgnitionParser.dollarsToUnits("150.4567") % 100L, "always on the cent tick");
    }

    @Test
    void anythingButAPlainPositiveDecimalIsRefused() {
        assertEquals(-1L, IgnitionParser.dollarsToUnits("1.498E2"));
        assertEquals(-1L, IgnitionParser.dollarsToUnits("-5"));
        assertEquals(-1L, IgnitionParser.dollarsToUnits("0"));
        assertEquals(-1L, IgnitionParser.dollarsToUnits("0.004"));
        assertEquals(-1L, IgnitionParser.dollarsToUnits("1000000000"));
        assertEquals(-1L, IgnitionParser.dollarsToUnits(".5"));
        assertEquals(-1L, IgnitionParser.dollarsToUnits("5."));
        assertEquals(-1L, IgnitionParser.dollarsToUnits(""));
    }

    @Test
    void anIntegerOpenIsAccepted() {
        assertEquals(1_500_000L, parse(snapshot("150", "\"2026-11-18T05:00:00Z\"")).openPx());
    }

    @Test
    void theDateIsTheBarsNewYorkDateNotItsUtcDate() {
        // 03:30 UTC on the 19th is still the 18th in New York.
        assertEquals(20261118, parse(snapshot("149.8", "\"2026-11-19T03:30:00Z\"")).date());
        assertEquals(20260704, parse(snapshot("149.8", "\"2026-07-04T04:00:00Z\"")).date(), "EDT");
        assertEquals(20261118, parse(snapshot("149.8", "\"2026-11-18T00:00:00-05:00\"")).date(), "an offset");
    }

    @Test
    void missingOrUnusableFieldsGiveNull() {
        assertNull(parse("{}"));
        assertNull(parse("{\"dailyBar\":{}}"));
        assertNull(parse("{\"dailyBar\":{\"o\":149.8}}"), "no t");
        assertNull(parse("{\"dailyBar\":{\"t\":\"2026-11-18T05:00:00Z\"}}"), "no o");
        assertNull(parse(snapshot("\"149.80\"", "\"2026-11-18T05:00:00Z\"")), "o as a string");
        assertNull(parse(snapshot("0", "\"2026-11-18T05:00:00Z\"")));
        assertNull(parse(snapshot("-5", "\"2026-11-18T05:00:00Z\"")));
        assertNull(parse(snapshot("1.498E2", "\"2026-11-18T05:00:00Z\"")));
        assertNull(parse(snapshot("149.8", "\"yesterday\"")));
        assertNull(parse(snapshot("149.8", "20261118")), "t as a number");
        assertNull(parse("{\"dailyBar\":null}"));
        assertNull(parse("{\"dailyBar\":[1,2]}"));
    }

    @Test
    void malformedJsonGivesNullWithoutThrowing() {
        assertNull(parse("not json"));
        assertNull(parse("{\"dailyBar\":{\"o\":149.8,"));
        assertNull(parse("[]"));
        assertNull(parse(""));
        assertNull(IgnitionParser.parse(new byte[] {(byte) 0xff, (byte) 0xfe}));
    }
}
