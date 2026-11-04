import io.github.jichuo1.meguviz.android.runtime.core.FrameDriver;
import io.github.jichuo1.meguviz.android.runtime.core.FrameScheduler;
import io.github.jichuo1.meguviz.android.runtime.core.OwnerDispatcher;
import io.github.jichuo1.meguviz.drawable.cache.FlightCache;
import io.github.jichuo1.meguviz.ui.android.AndroidFrameDriver;
import io.github.jichuo1.meguviz.ui.scheduler.UiScheduler;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import kotlin.Unit;

/** Actual local library classes, no Android rendering or device-performance claims. */
public final class JointPipelineProbe {
    private static int checks;

    private static final class Clock implements OwnerDispatcher, FrameDriver {
        private final Thread owner = Thread.currentThread();
        private final ArrayDeque<Runnable> posts = new ArrayDeque<>();
        private FrameDriver.Callback frame;
        int frameRequests;
        final List<Throwable> failures = new ArrayList<>();
        public boolean isOwnerThread() { return Thread.currentThread() == owner; }
        public synchronized boolean post(Runnable callback) { posts.add(callback); return true; }
        public synchronized void remove(Runnable callback) { posts.remove(callback); }
        public boolean request(FrameDriver.Callback callback) {
            checkOwner();
            check(frame == null, "only one physical frame is outstanding");
            frame = callback;
            frameRequests++;
            return true;
        }
        public void remove(FrameDriver.Callback callback) {
            checkOwner();
            if (frame == callback) frame = null;
        }
        void pump() {
            checkOwner();
            while (true) {
                Runnable next;
                synchronized (this) { next = posts.poll(); }
                if (next == null) return;
                next.run();
            }
        }
        void tick() {
            checkOwner();
            FrameDriver.Callback callback = frame;
            frame = null;
            check(callback != null, "a frame was requested");
            callback.onFrame(16_666_667L);
        }
    }

    private static final class Workers implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        public synchronized void execute(Runnable task) { tasks.add(task); }
        void runInBackground() throws InterruptedException {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try {
                    while (true) {
                        Runnable next;
                        synchronized (this) { next = tasks.poll(); }
                        if (next == null) return;
                        next.run();
                    }
                } catch (Throwable error) { failure.set(error); }
            }, "joint-pipeline-worker");
            worker.setDaemon(true);
            worker.start();
            worker.join(10_000);
            check(!worker.isAlive(), "background preparation completed");
            if (failure.get() != null) throw new AssertionError("worker failed", failure.get());
        }
    }

    private static final class Prepared {
        final int generation;
        Prepared(int generation) { this.generation = generation; }
    }

    public static void main(String[] args) throws Exception {
        verifyCandidate(FrameScheduler.class, "meguviz.runtime.candidate.uri");
        verifyCandidate(FlightCache.class, "meguviz.drawable.candidate.uri");
        Clock clock = new Clock();
        Workers workers = new Workers();
        FrameScheduler runtime = new FrameScheduler(clock, clock, clock.failures::add, 64);
        AndroidFrameDriver driver = new AndroidFrameDriver(clock, runtime, new Object());
        UiScheduler ui = new UiScheduler(driver, () -> { clock.checkOwner(); return Unit.INSTANCE; },
                256, 256, 64, Long.MAX_VALUE, () -> 0L);
        FlightCache<String, Prepared> cache = FlightCache.<String, Prepared>builder(workers, clock::post).build();
        AtomicInteger preparations = new AtomicInteger();
        List<Prepared> ready = new ArrayList<>();
        List<Prepared> installed = new ArrayList<>();
        FlightCache.Producer<String, Prepared> producer = (key, cancellation) -> {
            check(!clock.isOwnerThread(), "preparation stays off the UI owner");
            return new Prepared(preparations.incrementAndGet());
        };
        FlightCache.Subscriber<Prepared> subscriber = new FlightCache.Subscriber<Prepared>() {
            public boolean isCurrent() { return true; }
            public void onReady(Prepared value) {
                clock.checkOwner();
                ready.add(value);
                ui.submitState("background", () -> true, () -> { installed.add(value); return Unit.INSTANCE; });
            }
            public void onFailed(Throwable error) { clock.failures.add(error); }
        };
        try {
            FlightCache.Ticket<Prepared> first = cache.request("recipe", producer, subscriber);
            FlightCache.Ticket<Prepared> second = cache.request("recipe", producer, subscriber);
            check(first.status() == FlightCache.Status.STARTED, "first request starts production");
            check(second.status() == FlightCache.Status.JOINED, "same key joins production");
            workers.runInBackground();
            check(preparations.get() == 1, "shared preparation happens once");
            check(ready.isEmpty() && installed.isEmpty(), "workers do not deliver or install directly");
            clock.pump();
            check(ready.size() == 2 && ready.get(0) == ready.get(1), "subscribers share one immutable result");
            check(ui.getPendingStateCount() == 1, "same semantic slot has one pending state");
            check(clock.frameRequests == 1 && installed.isEmpty(), "one upstream frame, no eager setter");
            clock.tick();
            check(installed.size() == 1 && installed.get(0) == ready.get(0), "one owner-side commit in the upstream frame");
            check(clock.frame == null && ui.getCounters().getDrainedFrames() == 1L, "no extra frame or mailbox hop");

            int callbacks = ready.size();
            FlightCache.Ticket<Prepared> hit = cache.request("recipe", producer, subscriber);
            check(hit.status() == FlightCache.Status.READY, "cached preparation takes the ready path");
            check(ready.size() == callbacks, "ready hits do not promise another callback");
            subscriber.onReady(hit.value());
            clock.tick();
            check(preparations.get() == 1 && installed.size() == 2, "ready hit installs without another worker");

            FlightCache.Ticket<Prepared> cancelled = cache.request("cancelled", producer, subscriber);
            check(cancelled.cancel(), "owner can withdraw a subscription");
            workers.runInBackground();
            clock.pump();
            check(preparations.get() == 1 && ready.size() == callbacks + 1, "cancelled work never produces or delivers");
            check(clock.failures.isEmpty(), "no library or subscriber failures");
        } finally {
            cache.close();
            ui.close();
            driver.close();
            runtime.close();
        }
        check(clock.frame == null && runtime.channelCount() == 0, "all owned registrations are released");
        System.out.println("JointPipelineProbe: " + checks + " checks passed (host only; no rendering or ART claim)");
    }

    private static void verifyCandidate(Class<?> type, String property) {
        String expectedUri = System.getProperty(property);
        check(expectedUri != null, "candidate identity is explicit");
        String actualUri = type.getProtectionDomain().getCodeSource().getLocation().toExternalForm();
        check(actualUri.equals(expectedUri), type.getSimpleName() + " loads from the selected candidate");
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }
}
