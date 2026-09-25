package com.urlshortener.shortener.validation;

import com.urlshortener.common.config.AppProperties;
import com.urlshortener.common.error.InvalidUrlException;
import com.urlshortener.common.error.ServiceUnavailableException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

/**
 * Creation-time SSRF protection (design doc section 10.3, E4). The hostname is resolved and rejected if <em>any</em>
 * of its addresses is private/loopback/link-local, so a multi-record answer cannot hide an internal address among
 * public ones.
 *
 * <p>Fail-closed: a host that cannot be resolved is rejected, never waved through (section 8.2.1); a resolver that
 * is merely slow surfaces as 503, not as a bypassed check. This is a best-effort creation-time check - DNS can
 * change after creation - and that limitation is documented in section 19, not oversold.
 */
@Component
public class SsrfGuard {

    private final HostResolver resolver;
    private final long timeoutMillis;
    private final ExecutorService lookups = Executors.newVirtualThreadPerTaskExecutor();

    public SsrfGuard(HostResolver resolver, AppProperties props) {
        this.resolver = resolver;
        this.timeoutMillis = props.url().dnsTimeout().toMillis();
    }

    /** @param host as returned by {@link java.net.URI#getHost()}; IPv6 literals may still carry brackets */
    public void assertPublic(String host) {
        String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (bare.endsWith(".")) {
            bare = bare.substring(0, bare.length() - 1);   // "localhost." is the same host as "localhost"
        }
        InetAddress[] addresses = resolve(bare);
        for (InetAddress address : addresses) {
            if (IpClassifier.isBlocked(address)) {
                throw new InvalidUrlException("The longUrl host resolves to a private or internal address.");
            }
        }
    }

    private InetAddress[] resolve(String host) {
        Future<InetAddress[]> future = lookups.submit(() -> resolver.resolve(host));
        try {
            InetAddress[] addresses = future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            if (addresses == null || addresses.length == 0) {
                throw new InvalidUrlException("The longUrl host could not be resolved.");
            }
            return addresses;
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new ServiceUnavailableException("Could not verify the longUrl host in time; try again.", e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new ServiceUnavailableException("Interrupted while verifying the longUrl host.", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof UnknownHostException) {
                throw new InvalidUrlException("The longUrl host could not be resolved.");
            }
            throw new ServiceUnavailableException("Could not verify the longUrl host.", e.getCause());
        }
    }
}
