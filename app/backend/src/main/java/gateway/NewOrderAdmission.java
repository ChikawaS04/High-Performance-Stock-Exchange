package gateway;

/**
 * Whether the gateway may sequence a NEW order now (P16-8, the disk guard's hard stop). Asked once per
 * NEW on the gateway's thread, after parsing and before the inbound ring, so a refused NEW is never
 * sequenced or journaled and a journal replay sees exactly the commands the engine applied live.
 * Cancels and session commands are never asked.
 *
 * <p>Implementations answer with one volatile read and never allocate or block.
 */
@FunctionalInterface
public interface NewOrderAdmission {

    /** Admits everything: the gateway's behaviour without a guard. */
    NewOrderAdmission ALWAYS = () -> false;

    /** True while NEW orders must be refused. */
    boolean refuseNew();
}
