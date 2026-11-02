package net;

import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;

import java.io.IOException;

/**
 * The one {@code exceptionCaught} policy for this server's handlers (P15-7, carried from P15-4).
 *
 * An {@link IOException} is the peer going away (connection reset, an aborted connection on
 * Windows, a write to a closed channel). It is routine, so it is logged at DEBUG; matching on the
 * type rather than the message keeps it independent of the operating system's wording. Anything
 * else is unexpected and logged at WARN with its stack trace. Either way the channel is closed
 * and the exception is not fired on, so Netty's pipeline tail does not log it a second time.
 */
final class ChannelErrors {

    private ChannelErrors() {
    }

    static void closeOnException(ChannelHandlerContext ctx, Throwable cause, Logger log) {
        if (cause instanceof IOException) {
            log.debug("Connection to {} lost: {}", ctx.channel().remoteAddress(), cause.toString());
        } else {
            log.warn("Closing connection to {} after an unexpected error", ctx.channel().remoteAddress(), cause);
        }
        ctx.close();
    }
}
