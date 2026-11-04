import com.sun.management.ThreadMXBean;
import io.github.jichuo1.meguviz.ui.recycler.LatestWork;
import java.lang.management.ManagementFactory;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

/**
 * Host-only deterministic probe for {@link LatestWork}'s bounded latest-wins admission.
 *
 * <p>Measures owner-thread allocation for a continuous-submit storm and asserts the contracts:
 * at most one running plus one pending, only the latest pending value runs, a failed compute does
 * not break the pending, and close/dispatch rejection clear work and block later submissions.
 *
 * <p>This is not an Android performance gate and does not prove ART behaviour.
 */
public final class LatestWorkProbe {
    private static final class Queue implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable command) { tasks.add(command); }
        int size() { return tasks.size(); }
        void run() { tasks.removeFirst().run(); }
        void runBackground() {
            final Throwable[] failure = new Throwable[1];
            Thread thread = new Thread(() -> {
                try { run(); } catch (Throwable error) { failure[0] = error; }
            });
            thread.start();
            try { thread.join(); } catch (InterruptedException interrupted) { throw new IllegalStateException(interrupted); }
            if (failure[0] != null) throw new IllegalStateException(failure[0]);
        }
    }

    private static final AtomicLong APPLIED = new AtomicLong();
    private static final Function1<Object, Object> IDENTITY = value -> value;
    private static final Function1<Throwable, Unit> THROW =
            error -> { throw new IllegalStateException(error); };

    private LatestWorkProbe() { }

    public static void main(String[] args) {
        int storm = args.length > 0 ? Integer.parseInt(args[0]) : 2000;
        int rounds = args.length > 1 ? Integer.parseInt(args[1]) : 5;
        if (storm < 1 || rounds < 1) throw new IllegalArgumentException("positive counts required");
        verifySemantics();
        ThreadMXBean allocation = allocationBean();
        List<Long> bytes = new ArrayList<>();
        for (int i = 0; i < rounds; i++) bytes.add(stormAllocation(storm, allocation));
        Collections.sort(bytes);
        System.out.println("storm_size=" + storm + " rounds=" + rounds
                + " owner_alloc_bytes=" + bytes + " applied=" + APPLIED.get());
        if (APPLIED.get() == 0) throw new AssertionError("work was not executed");
    }

    private static long stormAllocation(int storm, ThreadMXBean allocation) {
        // Pre-allocate the values so the measurement isolates LatestWork's own allocation
        // instead of the Integer boxing a primitive storm would add.
        Object[] values = new Object[storm];
        for (int i = 0; i < storm; i++) values[i] = new Object();
        Queue background = new Queue();
        Queue owner = new Queue();
        LatestWork<Object, Object> work = new LatestWork<>(background, owner, IDENTITY,
                (value, result) -> { APPLIED.incrementAndGet(); return Unit.INSTANCE; }, THROW);
        for (Object value : values) work.submit(value);
        background.runBackground(); owner.run(); background.runBackground(); owner.run();
        long before = allocation == null ? 0L : allocation.getThreadAllocatedBytes(Thread.currentThread().getId());
        for (Object value : values) work.submit(value);
        background.runBackground(); owner.run(); background.runBackground(); owner.run();
        long after = allocation == null ? 0L : allocation.getThreadAllocatedBytes(Thread.currentThread().getId());
        work.close();
        return after - before;
    }

    private static void verifySemantics() {
        // Bounded latest-wins: one running plus one pending, only the latest pending value runs.
        Queue background = new Queue();
        Queue owner = new Queue();
        List<Object> calculated = new ArrayList<>();
        List<Object> committed = new ArrayList<>();
        LatestWork<Object, Object> work = new LatestWork<>(background, owner,
                value -> { calculated.add(value); return value; },
                (value, result) -> { committed.add(result); return Unit.INSTANCE; }, THROW);
        for (int i = 0; i < 1000; i++) work.submit(i);
        if (background.size() != 1) throw new AssertionError("burst must queue one task");
        background.runBackground(); owner.run();
        if (!calculated.equals(Collections.singletonList(0)))
            throw new AssertionError("only the running value may compute: " + calculated);
        background.runBackground(); owner.run();
        if (!calculated.equals(Arrays.asList(0, 999)))
            throw new AssertionError("pending slot must hold the latest value: " + calculated);
        if (!committed.equals(Collections.singletonList(999)))
            throw new AssertionError("only the latest result commits: " + committed);
        work.close();
        try { work.submit(1001); throw new AssertionError("closed submit must fail"); }
        catch (IllegalStateException expected) { }

        // A failed compute must not break the pending submission.
        Queue failedBackground = new Queue();
        Queue failedOwner = new Queue();
        List<Object> applied = new ArrayList<>();
        LatestWork<Object, Object> failing = new LatestWork<>(failedBackground, failedOwner,
                value -> { if (value.equals(1)) throw new IllegalStateException("boom"); return value; },
                (value, result) -> { applied.add(result); return Unit.INSTANCE; }, THROW);
        failing.submit(1); failing.submit(2);
        failedBackground.runBackground(); failedOwner.run();
        failedBackground.runBackground(); failedOwner.run();
        if (!applied.equals(Collections.singletonList(2)))
            throw new AssertionError("pending must survive a failed compute: " + applied);
        failing.close();

        // Owner rejection closes the controller and blocks later submissions.
        Queue rejectingBackground = new Queue();
        LatestWork<Object, Object> rejecting = new LatestWork<>(rejectingBackground,
                command -> { throw new RejectedExecutionException(); }, IDENTITY,
                (value, result) -> Unit.INSTANCE, THROW);
        rejecting.submit(1); rejecting.submit(2);
        rejectingBackground.runBackground();
        if (rejectingBackground.size() != 0) throw new AssertionError("rejection must clear work");
        try { rejecting.submit(3); throw new AssertionError("rejected controller must block submits"); }
        catch (IllegalStateException expected) { }
    }

    private static ThreadMXBean allocationBean() {
        java.lang.management.ThreadMXBean generic = ManagementFactory.getThreadMXBean();
        if (generic instanceof ThreadMXBean && ((ThreadMXBean) generic).isThreadAllocatedMemorySupported()) {
            ((ThreadMXBean) generic).setThreadAllocatedMemoryEnabled(true);
            return (ThreadMXBean) generic;
        }
        return null;
    }
}
