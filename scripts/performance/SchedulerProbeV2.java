import com.sun.management.ThreadMXBean;
import io.github.jichuo1.meguviz.ui.scheduler.FrameBudget;
import io.github.jichuo1.meguviz.ui.scheduler.FrameTicket;
import io.github.jichuo1.meguviz.ui.scheduler.RejectionReason;
import io.github.jichuo1.meguviz.ui.scheduler.ScheduledOperation;
import io.github.jichuo1.meguviz.ui.scheduler.StateSlot;
import io.github.jichuo1.meguviz.ui.scheduler.StateUpdate;
import io.github.jichuo1.meguviz.ui.scheduler.SubmitResult;
import io.github.jichuo1.meguviz.ui.scheduler.UiFrameDriver;
import io.github.jichuo1.meguviz.ui.scheduler.UiScheduler;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;

/**
 * Host-only deterministic probe for the UiScheduler frame fast paths.
 *
 * <p>Measures the paths that must stay allocation-light after the buffer-reuse work:
 * command-only frames (no state index), full state drains (no leftover group set),
 * reentrant submissions (incoming queue reuse) and partial drains (group rotation).
 * It also asserts the semantics those paths must preserve: command FIFO, state
 * coalescing, reentrant next-frame delivery, group round-robin and fault recovery.
 *
 * <p>This is not an Android performance gate and does not prove ART behaviour.
 */
public final class SchedulerProbeV2 {
    private static final Function0<Unit> NOOP = () -> Unit.INSTANCE;
    private static final Function0<Boolean> VALID = () -> true;
    private static final AtomicInteger CONSUMED = new AtomicInteger();
    private static final Function0<Unit> ACTION = () -> {
        CONSUMED.incrementAndGet();
        return Unit.INSTANCE;
    };
    private static final FrameBudget BUDGET = new FrameBudget(0L, null, true, "host-probe-v2");

    private static final class Driver implements UiFrameDriver {
        private Function1<? super FrameBudget, Unit> callback;

        @Override
        public String getOwner() {
            return "host-probe-v2";
        }

        @Override
        public FrameTicket requestFrame(Function1<? super FrameBudget, Unit> next) {
            if (callback != null) throw new AssertionError("duplicate frame request");
            callback = next;
            return () -> callback = null;
        }

        void tick() {
            Function1<? super FrameBudget, Unit> current = callback;
            callback = null;
            if (current != null) current.invoke(BUDGET);
        }
    }

    private static final class Fixture {
        final Driver driver = new Driver();
        final UiScheduler scheduler;
        final Object[] slots;

        Fixture(int size, int maxOperations) {
            scheduler = new UiScheduler(driver, NOOP, size, size, maxOperations, Long.MAX_VALUE, () -> 0L);
            slots = new Object[size];
            for (int index = 0; index < size; index++) {
                slots[index] = new StateSlot(index, index % 4);
            }
        }
    }

    public static void main(String[] args) {
        int warmup = args.length > 0 ? Integer.parseInt(args[0]) : 2000;
        int iterations = args.length > 1 ? Integer.parseInt(args[1]) : 4000;
        if (warmup < 1 || iterations < 1) throw new IllegalArgumentException("positive counts required");

        verifySemantics();

        ThreadMXBean allocation = allocationBean();
        System.out.println("scenario,size,iterations,median_nanos,p95_nanos,p99_nanos,bytes_per_round");
        for (String scenario : new String[]{
                "command-only", "command-drain", "state-full", "state-drain", "state-reentrant",
                "state-partial", "state-coalesce", "state-batch", "state-discard"
        }) {
            for (int size : new int[]{16, 64, 256}) {
                measure(scenario, size, warmup, iterations, allocation);
            }
        }
        if (CONSUMED.get() == 0) throw new AssertionError("work was not executed");
    }

    private static ThreadMXBean allocationBean() {
        java.lang.management.ThreadMXBean generic = ManagementFactory.getThreadMXBean();
        if (generic instanceof ThreadMXBean && ((ThreadMXBean) generic).isThreadAllocatedMemorySupported()) {
            ((ThreadMXBean) generic).setThreadAllocatedMemoryEnabled(true);
            return (ThreadMXBean) generic;
        }
        return null;
    }

    private static void measure(String scenario, int size, int warmup, int iterations, ThreadMXBean allocation) {
        boolean drainOnly = scenario.endsWith("-drain");
        boolean reentrant = scenario.equals("state-reentrant");
        boolean partial = scenario.equals("state-partial");
        int maxOperations = partial ? Math.max(1, size / 2) : size;
        Fixture fixture = new Fixture(size, maxOperations);
        UiScheduler scheduler = fixture.scheduler;
        Driver driver = fixture.driver;

        for (int index = 0; index < warmup; index++) {
            round(scenario, fixture, drainOnly);
            if (scheduler.getPendingStateCount() != 0 || scheduler.getPendingCommandCount() != 0) {
                throw new AssertionError("round did not drain: " + scenario);
            }
        }

        long[] elapsed = new long[iterations];
        long thread = Thread.currentThread().getId();
        long allocated = allocation == null ? 0L : allocation.getThreadAllocatedBytes(thread);
        long drainBytes = 0L;
        for (int index = 0; index < iterations; index++) {
            if (drainOnly) {
                submit(scenario, fixture);
                long allocStart = allocation == null ? 0L : allocation.getThreadAllocatedBytes(thread);
                long start = System.nanoTime();
                driver.tick();
                elapsed[index] = System.nanoTime() - start;
                if (allocation != null) drainBytes += allocation.getThreadAllocatedBytes(thread) - allocStart;
            } else {
                long start = System.nanoTime();
                round(scenario, fixture, false);
                elapsed[index] = System.nanoTime() - start;
            }
            if (scheduler.getPendingStateCount() != 0 || scheduler.getPendingCommandCount() != 0) {
                throw new AssertionError("round did not drain: " + scenario);
            }
        }
        long bytes = drainOnly
                ? drainBytes / iterations
                : (allocation == null ? -1L : (allocation.getThreadAllocatedBytes(thread) - allocated) / iterations);
        Arrays.sort(elapsed);
        System.out.println(scenario + "," + size + "," + iterations + "," + elapsed[iterations / 2] + ","
                + elapsed[(int) ((iterations - 1) * 0.95)] + "," + elapsed[(int) ((iterations - 1) * 0.99)] + ","
                + (bytes < 0 ? -1L : bytes));
        scheduler.close();
    }

    private static void round(String scenario, Fixture fixture, boolean drainOnly) {
        if (!drainOnly) submit(scenario, fixture);
        fixture.driver.tick();
        if (scenario.equals("state-reentrant") || scenario.equals("state-partial")) fixture.driver.tick();
    }

    private static void submit(String scenario, Fixture fixture) {
        UiScheduler scheduler = fixture.scheduler;
        switch (scenario) {
            case "command-only":
            case "command-drain":
                for (int index = 0; index < fixture.slots.length; index++) {
                    scheduler.submitCommand(VALID, ACTION);
                }
                return;
            case "state-full":
            case "state-drain":
            case "state-partial":
                for (Object slot : fixture.slots) scheduler.submitState(slot, VALID, ACTION);
                return;
            case "state-reentrant":
                for (Object slot : fixture.slots) {
                    scheduler.submitState(slot, VALID, () -> {
                        CONSUMED.incrementAndGet();
                        scheduler.submitState(new StateSlot("reentrant", slot), VALID, ACTION);
                        return Unit.INSTANCE;
                    });
                }
                return;
            case "state-coalesce":
                // The animated-property case: one hot slot resubmitted every frame.
                for (int index = 0; index < fixture.slots.length; index++) {
                    scheduler.submitState(fixture.slots[0], VALID, ACTION);
                }
                return;
            case "state-batch":
                // The same-semantic batch entry; the caller-side StateUpdate objects are part of the
                // measured cost, so this scenario is not comparable to per-call submitState.
                List<StateUpdate> batch = new ArrayList<>(fixture.slots.length);
                for (Object slot : fixture.slots) batch.add(new StateUpdate(slot, VALID, ACTION));
                scheduler.submitStates(batch);
                return;
            case "state-discard":
                // rebind/release storm: the pending state queue is rescanned on every call.
                for (Object slot : fixture.slots) scheduler.submitState(slot, VALID, ACTION);
                scheduler.discardInvalid();
                return;
            default:
                throw new AssertionError(scenario);
        }
    }

    private static void verifySemantics() {
        verifyCommandFifoAndCoalescing();
        verifyReentrantStateWaitsForNextFrame();
        verifyGroupRoundRobinAfterPartialDrain();
        verifyFaultRecoversWithoutLeak();
        verifyCloseClearsQueues();
        System.out.println("semantic-checks,ok");
    }

    private static void verifyCommandFifoAndCoalescing() {
        Driver driver = new Driver();
        UiScheduler scheduler = new UiScheduler(driver, NOOP, 8, 8, 32, Long.MAX_VALUE, () -> 0L);
        List<Integer> log = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            final int value = index;
            scheduler.submitCommand(VALID, () -> {
                log.add(value);
                return Unit.INSTANCE;
            });
        }
        for (int index = 0; index < 4; index++) {
            final int value = index;
            scheduler.submitState("title", VALID, () -> {
                log.add(100 + value);
                return Unit.INSTANCE;
            });
        }
        driver.tick();
        if (!log.equals(Arrays.asList(103, 0, 1, 2, 3, 4, 5, 6, 7))) {
            throw new AssertionError("FIFO/coalescing order broken: " + log);
        }
        if (scheduler.getCounters().getCoalescedStates() != 3) {
            throw new AssertionError("coalescing count broken");
        }
        if (scheduler.getPendingStateCount() != 0 || scheduler.getPendingCommandCount() != 0) {
            throw new AssertionError("full drain left work behind");
        }
        scheduler.close();
    }

    private static void verifyReentrantStateWaitsForNextFrame() {
        Driver driver = new Driver();
        UiScheduler scheduler = new UiScheduler(driver, NOOP, 8, 8, 8, Long.MAX_VALUE, () -> 0L);
        List<String> log = new ArrayList<>();
        scheduler.submitState("outer", VALID, () -> {
            log.add("outer");
            scheduler.submitState("inner", VALID, () -> {
                log.add("inner");
                return Unit.INSTANCE;
            });
            scheduler.submitCommand(VALID, () -> {
                log.add("command");
                return Unit.INSTANCE;
            });
            return Unit.INSTANCE;
        });
        driver.tick();
        if (!log.equals(List.of("outer"))) throw new AssertionError("reentrant work entered the snapshot");
        if (scheduler.getPendingStateCount() != 1 || scheduler.getPendingCommandCount() != 1) {
            throw new AssertionError("reentrant counts broken");
        }
        driver.tick();
        if (!log.equals(Arrays.asList("outer", "command", "inner"))) {
            throw new AssertionError("reentrant delivery order broken: " + log);
        }
        if (scheduler.getPendingStateCount() != 0 || scheduler.getPendingCommandCount() != 0) {
            throw new AssertionError("reentrant drain left work behind");
        }
        scheduler.close();
    }

    private static void verifyGroupRoundRobinAfterPartialDrain() {
        Driver driver = new Driver();
        UiScheduler scheduler = new UiScheduler(driver, NOOP, 16, 16, 4, Long.MAX_VALUE, () -> 0L);
        List<String> log = new ArrayList<>();
        for (int group = 0; group < 4; group++) {
            for (int property = 0; property < 4; property++) {
                final int binding = group;
                final int slot = property;
                scheduler.submitState(new StateSlot(binding, slot), VALID, () -> {
                    log.add(binding + ":" + slot);
                    return Unit.INSTANCE;
                });
            }
        }
        for (int tick = 0; tick < 4; tick++) driver.tick();
        if (!log.equals(Arrays.asList(
                "0:0", "1:0", "2:0", "3:0",
                "0:1", "1:1", "2:1", "3:1",
                "0:2", "1:2", "2:2", "3:2",
                "0:3", "1:3", "2:3", "3:3"))) {
            throw new AssertionError("partial drain round-robin broken: " + log);
        }
        if (scheduler.getPendingStateCount() != 0) throw new AssertionError("partial drain left states");
        scheduler.close();
    }

    private static void verifyFaultRecoversWithoutLeak() {
        Driver driver = new Driver();
        UiScheduler scheduler = new UiScheduler(driver, NOOP, 8, 8, 8, Long.MAX_VALUE, () -> 0L);
        List<String> log = new ArrayList<>();
        scheduler.submitCommand(VALID, () -> {
            log.add("first");
            return Unit.INSTANCE;
        });
        scheduler.submitCommand(() -> {
            throw new IllegalStateException("boom");
        }, () -> Unit.INSTANCE);
        scheduler.submitCommand(VALID, () -> {
            log.add("never");
            return Unit.INSTANCE;
        });
        driver.tick();
        if (scheduler.getState() != io.github.jichuo1.meguviz.ui.scheduler.SchedulerState.FAULTED) {
            throw new AssertionError("expected fault");
        }
        if (scheduler.getPendingStateCount() != 0 || scheduler.getPendingCommandCount() != 0) {
            throw new AssertionError("fault left pending work");
        }
        scheduler.recover();
        scheduler.submitCommand(VALID, () -> {
            log.add("recovered");
            return Unit.INSTANCE;
        });
        driver.tick();
        if (!log.equals(Arrays.asList("first", "recovered"))) {
            throw new AssertionError("recovery order broken: " + log);
        }
        scheduler.close();
    }

    private static void verifyCloseClearsQueues() {
        Driver driver = new Driver();
        UiScheduler scheduler = new UiScheduler(driver, NOOP, 8, 8, 8, Long.MAX_VALUE, () -> 0L);
        scheduler.submitCommand(VALID, () -> Unit.INSTANCE);
        scheduler.submitState("pending", VALID, () -> Unit.INSTANCE);
        scheduler.close();
        if (scheduler.getPendingStateCount() != 0 || scheduler.getPendingCommandCount() != 0) {
            throw new AssertionError("close left pending work");
        }
        SubmitResult result = scheduler.submitCommand(VALID, () -> Unit.INSTANCE);
        if (!(result instanceof SubmitResult.Rejected)
                || ((SubmitResult.Rejected) result).getReason() != RejectionReason.CLOSED) {
            throw new AssertionError("close did not reject submission");
        }
    }
}
