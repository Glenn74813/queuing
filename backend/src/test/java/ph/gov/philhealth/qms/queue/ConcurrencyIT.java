package ph.gov.philhealth.qms.queue;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import ph.gov.philhealth.qms.common.Ids;
import ph.gov.philhealth.qms.security.AuthUser;
import ph.gov.philhealth.qms.security.AuthUserLoader;
import ph.gov.philhealth.qms.security.DevicePrincipal;
import ph.gov.philhealth.qms.support.IntegrationTest;
import ph.gov.philhealth.qms.support.TestData;
import ph.gov.philhealth.qms.user.AppUserRepository;

/** Ticket-number uniqueness and no double-calling under concurrent load. */
@IntegrationTest
class ConcurrencyIT {

    @Autowired
    QueueService queue;
    @Autowired
    AgentSessionService sessions;
    @Autowired
    TestData data;
    @Autowired
    AppUserRepository users;
    @Autowired
    AuthUserLoader loader;
    @Autowired
    org.springframework.transaction.support.TransactionTemplate tx;

    @Test
    void concurrentIssuanceProducesUniqueGaplessNumbers() throws Exception {
        TestData.SiteFixture site = data.site();
        DevicePrincipal kiosk = new DevicePrincipal(site.kioskId(), site.siteId(), "K", "KIOSK");
        int n = 60;
        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return queue.issue(kiosk, site.serviceA(), null, Ticket.Channel.PRINTED, Ids.newId()).getDisplayNumber();
            }));
        }
        start.countDown();
        Set<String> numbers = ConcurrentHashMap.newKeySet();
        for (Future<String> f : results) {
            numbers.add(f.get());
        }
        pool.shutdown();
        assertThat(numbers).hasSize(n);
        for (int i = 1; i <= n; i++) {
            assertThat(numbers).contains(TicketNumberAllocator.format("A", i));
        }
    }

    @Test
    void concurrentCallNextNeverCallsTheSameTicketTwice() throws Exception {
        TestData.SiteFixture site = data.site();
        DevicePrincipal kiosk = new DevicePrincipal(site.kioskId(), site.siteId(), "K", "KIOSK");
        int tickets = 20;
        for (int i = 0; i < tickets; i++) {
            queue.issue(kiosk, site.serviceA(), null, Ticket.Channel.PRINTED, Ids.newId());
        }
        AuthUser a1 = agentAt(site, site.counter1());
        AuthUser a2 = agentAt(site, site.counter2());

        Set<UUID> called = ConcurrentHashMap.newKeySet();
        List<UUID> duplicates = new java.util.concurrent.CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Integer>> futures = new ArrayList<>();
        for (AuthUser agent : List.of(a1, a2)) {
            futures.add(pool.submit((Callable<Integer>) () -> {
                authenticate(agent);
                int count = 0;
                while (true) {
                    Optional<Ticket> t = queue.callNext();
                    if (t.isEmpty()) {
                        return count;
                    }
                    if (!called.add(t.get().getId())) {
                        duplicates.add(t.get().getId());
                    }
                    queue.complete(t.get().getId());
                    count++;
                }
            }));
        }
        int total = futures.get(0).get() + futures.get(1).get();
        pool.shutdown();
        assertThat(duplicates).isEmpty();
        assertThat(total).isEqualTo(tickets);
        assertThat(called).hasSize(tickets);
    }

    private AuthUser agentAt(TestData.SiteFixture site, UUID counter) {
        String username = data.user("AGENT", null, site.siteId());
        AuthUser user = tx.execute(s -> loader.loadActive(users.findByUsernameIgnoreCase(username).orElseThrow().getId())
                .orElseThrow());
        authenticate(user);
        sessions.start(counter);
        SecurityContextHolder.clearContext();
        return user;
    }

    private static void authenticate(AuthUser user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.authorities()));
    }
}
