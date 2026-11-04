import com.sun.management.ThreadMXBean;
import io.github.jichuo1.meguviz.ui.scheduler.FrameBudget;
import io.github.jichuo1.meguviz.ui.scheduler.FrameTicket;
import io.github.jichuo1.meguviz.ui.scheduler.ScheduledOperation;
import io.github.jichuo1.meguviz.ui.scheduler.StateSlot;
import io.github.jichuo1.meguviz.ui.scheduler.UiFrameDriver;
import io.github.jichuo1.meguviz.ui.scheduler.UiScheduler;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;

/** Host-only probe on the unit-test classpath; Android APIs are mockable stubs, not device evidence. */
public final class SchedulerProbe {
    private static long consumed;
    private static final Function0<Unit> NOOP = () -> Unit.INSTANCE;
    private static final Function0<Boolean> VALID = () -> true;
    private static final Function0<Unit> ACTION = () -> { consumed++; return Unit.INSTANCE; };
    private static final FrameBudget BUDGET = new FrameBudget(0L, null, true, "host-probe");

    private static final class Driver implements UiFrameDriver {
        private Function1<? super FrameBudget, Unit> callback;
        public String getOwner() { return "host-probe"; }
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

    private static void verifyArraySnapshot() {
        Driver driver = new Driver();
        UiScheduler scheduler = new UiScheduler(driver, NOOP, 2, 2, 2, Long.MAX_VALUE, () -> 0L);
        ScheduledOperation[] input = {
                new ScheduledOperation("first", VALID, ACTION),
                new ScheduledOperation("second", VALID, ACTION),
        };
        long before = consumed;
        scheduler.submitBatch(input);
        input[0] = new ScheduledOperation("replaced", VALID, () -> { consumed += 100; return Unit.INSTANCE; });
        driver.tick();
        if (consumed - before != 2) throw new AssertionError("batch retained the caller's mutable array");
        before = consumed;
        scheduler.submitBatch(new ScheduledOperation("first", VALID, ACTION), new ScheduledOperation("second", VALID, ACTION));
        driver.tick();
        if (consumed - before != 2) throw new AssertionError("Java vararg batch did not preserve both calls");
        scheduler.close();
    }

    public static void main(String[] args) {
        int warmup = args.length > 0 ? Integer.parseInt(args[0]) : 2000;
        int iterations = args.length > 1 ? Integer.parseInt(args[1]) : 4000;
        if (warmup < 1 || iterations < 1) throw new IllegalArgumentException("positive counts required");
        java.lang.management.ThreadMXBean generic = ManagementFactory.getThreadMXBean();
        ThreadMXBean allocation = generic instanceof ThreadMXBean ? (ThreadMXBean) generic : null;
        if (allocation != null && allocation.isThreadAllocatedMemorySupported()) {
            allocation.setThreadAllocatedMemoryEnabled(true);
        } else {
            allocation = null;
        }
        verifyArraySnapshot();
        System.out.println("scenario,size,iterations,median_nanos,p95_nanos,p99_nanos,bytes_per_round");
        for (String scenario : new String[]{"states", "grouped-states", "commands", "batch"}) {
            for (int size : new int[]{16, 64, 256}) {
                measure(scenario, size, warmup, iterations, allocation);
            }
        }
        if (consumed == 0) throw new AssertionError("work was not executed");
    }

    private static void measure(String scenario, int size, int warmup, int iterations, ThreadMXBean allocation) {
        Driver driver = new Driver();
        UiScheduler scheduler = new UiScheduler(driver, NOOP, size, size, size, Long.MAX_VALUE, () -> 0L);
        Object[] slots = new Object[size];
        List<ScheduledOperation> batch = new ArrayList<>();
        for (int index = 0; index < size; index++) {
            slots[index] = new StateSlot(scenario.equals("grouped-states") ? index / 4 : index, index % 4);
            batch.add(new ScheduledOperation("probe", VALID, ACTION));
        }
        for (int index = 0; index < warmup; index++) round(scenario, scheduler, driver, slots, batch);
        long[] elapsed = new long[iterations];
        long thread = Thread.currentThread().getId();
        long allocated = allocation == null ? 0L : allocation.getThreadAllocatedBytes(thread);
        for (int index = 0; index < iterations; index++) {
            long start = System.nanoTime();
            round(scenario, scheduler, driver, slots, batch);
            elapsed[index] = System.nanoTime() - start;
        }
        long bytes = allocation == null ? -1L : allocation.getThreadAllocatedBytes(thread) - allocated;
        Arrays.sort(elapsed);
        System.out.println(scenario + "," + size + "," + iterations + "," + elapsed[iterations / 2] + "," +
                elapsed[(int) ((iterations - 1) * 0.95)] + "," + elapsed[(int) ((iterations - 1) * 0.99)] + "," +
                (bytes < 0 ? -1L : bytes / iterations));
        scheduler.close();
    }

    private static void round(String scenario, UiScheduler scheduler, Driver driver, Object[] slots,
                              List<ScheduledOperation> batch) {
        switch (scenario) {
            case "states":
            case "grouped-states":
                for (Object slot : slots) scheduler.submitState(slot, VALID, ACTION);
                break;
            case "commands":
                for (Object ignored : slots) scheduler.submitCommand(VALID, ACTION);
                break;
            case "batch":
                scheduler.submitBatch(batch);
                break;
            default:
                throw new AssertionError(scenario);
        }
        driver.tick();
        if (scheduler.getPendingStateCount() != 0 || scheduler.getPendingCommandCount() != 0) {
            throw new AssertionError("round did not drain");
        }
    }
}
