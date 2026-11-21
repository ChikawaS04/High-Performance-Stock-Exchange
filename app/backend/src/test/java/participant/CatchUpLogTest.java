package participant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The bots' catch-up INFO line (P16-12). */
class CatchUpLogTest {

    @Test
    void namesTheCatchUpTimeTheRequestAndTheOutage() {
        assertEquals("maker caught up through seq 3350 in 412 ms (RESEND from seq 3178, 2 request(s), offline 6150 ms);"
                        + " trading resumes",
                CatchUpLog.caughtUp("maker", 3_350, 412, 3_178, 2, 6_150));
    }

    @Test
    void aZeroOutageIsShown() {
        assertEquals("noise caught up through seq 9 in 0 ms (RESEND from seq 5, 1 request(s), offline 0 ms);"
                + " trading resumes", CatchUpLog.caughtUp("noise", 9, 0, 5, 1, 0));
    }

    @Test
    void anUnknownOutageIsLeftOut() {
        assertEquals("inst caught up through seq 9 in 3 ms (RESEND from seq 5, 1 request(s)); trading resumes",
                CatchUpLog.caughtUp("inst", 9, 3, 5, 1, -1));
    }
}
