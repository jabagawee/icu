// © 2026 and later: Unicode, Inc. and others.
// License & terms of use: http://www.unicode.org/copyright.html
package com.ibm.icu.dev.test.util;

import com.ibm.icu.dev.test.TestUtil;
import com.ibm.icu.dev.test.TestUtil.JavaVendor;
import com.ibm.icu.impl.OlsonTimeZone;
import com.ibm.icu.impl.TimeZoneAdapter;
import com.ibm.icu.text.TimeZoneFormat;
import com.ibm.icu.util.SimpleTimeZone;
import com.ibm.icu.util.TimeZone;
import com.ibm.icu.util.ULocale;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Concurrency regression tests for TimeZone, ZoneMeta, and TimeZoneFormat. */
@RunWith(JUnit4.class)
public class TimeZoneConcurrencyTest extends ConcurrencyTest {

    @Test
    public void testTimeZoneDefaultTypeConcurrent() throws Exception {
        runConcurrent(
                "TimeZoneDefaultType",
                tid -> {
                    for (int i = 0; i < ITERATIONS; i++) {
                        int type = TimeZone.getDefaultTimeZoneType();
                        assertTrue(
                                "TimeZone type should be ICU or JDK",
                                type == TimeZone.TIMEZONE_ICU || type == TimeZone.TIMEZONE_JDK);
                        TimeZone tz = TimeZone.getDefault();
                        assertNotNull("Default TimeZone should not be null", tz);
                    }
                });
    }

    /** Write-contention test: concurrent setDefault() calls interleaved with getDefault() reads. */
    @Test
    public void testTimeZoneSetDefaultConcurrent() throws Exception {
        TimeZone original = TimeZone.getDefault();
        String[] zoneIds = {"America/New_York", "Europe/Berlin", "Asia/Tokyo", "Pacific/Auckland"};
        try {
            runConcurrent(
                    "TimeZoneSetDefault",
                    tid -> {
                        for (int i = 0; i < ITERATIONS / 10; i++) {
                            if (tid % 4 == 0) {
                                TimeZone tz =
                                        TimeZone.getTimeZone(zoneIds[(tid + i) % zoneIds.length]);
                                TimeZone.setDefault(tz);
                            } else {
                                TimeZone tz = TimeZone.getDefault();
                                assertNotNull(
                                        "Default TimeZone should never be null during writes", tz);
                                String id = tz.getID();
                                assertNotNull("TimeZone ID should never be null", id);
                            }
                        }
                    });
        } finally {
            TimeZone.setDefault(original);
        }
    }

    /**
     * ICU-23629: On Android, {@code java.util.TimeZone.setDefault()} holds the {@code
     * java.util.TimeZone.class} monitor while it calls {@link TimeZone#setICUDefault}. This test
     * holds that monitor while {@link TimeZone#getDefault()} waits for it on another thread, and
     * checks that setICUDefault() can still complete. If getDefault() held anything setICUDefault()
     * needs while calling into java.util.TimeZone, the two would deadlock on Android.
     *
     * <p>Not gated on exhaustiveness: it is deterministic and finishes in milliseconds.
     */
    @Test
    public void testGetDefaultWhileJdkMonitorHolderClearsIcuDefault() throws Exception {
        checkClearCompletesWhileWaitingForJdkMonitor(
                "TimeZone.getDefault()",
                () -> {
                    // Make the getter refill from java.util.TimeZone, and make
                    // java.util.TimeZone.getDefault() need the monitor to do it.
                    TimeZone.setICUDefault(null);
                    java.util.TimeZone.setDefault(null);
                },
                TimeZone::getDefault);
    }

    /**
     * Like testGetDefaultWhileJdkMonitorHolderClearsIcuDefault(), for {@link
     * TimeZone#setDefault(TimeZone)}, which holds its own lock while it calls into
     * java.util.TimeZone (on Android and on JDKs before 25, java.util.TimeZone.getTimeZone()
     * synchronizes on the class). setICUDefault() must not need that lock. The zone's clone() waits
     * for the monitor, so setDefault() blocks while holding its lock on any JDK.
     */
    @Test
    public void testSetDefaultWhileJdkMonitorHolderClearsIcuDefault() throws Exception {
        TimeZone zone = new JdkMonitorCloneZone(0, "ICU-23629-Monitor");
        checkClearCompletesWhileWaitingForJdkMonitor(
                "TimeZone.setDefault()", () -> {}, () -> TimeZone.setDefault(zone));
    }

    /** A zone whose clone() synchronizes on java.util.TimeZone.class, as JDK code might. */
    private static final class JdkMonitorCloneZone extends SimpleTimeZone {
        private static final long serialVersionUID = 1L;

        JdkMonitorCloneZone(int rawOffset, String id) {
            super(rawOffset, id);
        }

        @Override
        @SuppressWarnings("LockOnNonEnclosingClassLiteral") // Waiting for it is the point.
        public JdkMonitorCloneZone clone() {
            synchronized (java.util.TimeZone.class) {
                return (JdkMonitorCloneZone) super.clone();
            }
        }
    }

    /**
     * Holds {@code java.util.TimeZone.class} on one thread, runs {@code setup}, then starts {@code
     * waiter} on a second thread and waits until it blocks on the monitor. Then it calls
     * setICUDefault(null) on a third thread, as Android's java.util.TimeZone.setDefault() does, and
     * fails if that doesn't complete within 10 seconds. setICUDefault() runs on its own thread so
     * that if it blocks, the test can fail and then release the monitor, which lets every thread
     * finish instead of leaving locks held for the rest of the run. Skips if {@code waiter} never
     * waits for the monitor on this JDK.
     */
    @SuppressWarnings("LockOnNonEnclosingClassLiteral") // Locking it is the point of the test.
    private void checkClearCompletesWhileWaitingForJdkMonitor(
            String waiterName, Runnable setup, Runnable waiter) throws Exception {
        TimeZone originalIcu = TimeZone.getDefault();
        java.util.TimeZone originalJdk = java.util.TimeZone.getDefault();
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicBoolean waiterNeverWaited = new AtomicBoolean();

        Thread waiterThread =
                new Thread(
                        () -> {
                            try {
                                waiter.run();
                            } catch (Throwable t) {
                                error.compareAndSet(null, t);
                            }
                        },
                        "ICU-23629-waiter");
        Thread clearer =
                new Thread(
                        () -> {
                            try {
                                TimeZone.setICUDefault(null);
                            } catch (Throwable t) {
                                error.compareAndSet(null, t);
                            }
                        },
                        "ICU-23629-clearer");
        Thread androidSetter =
                new Thread(
                        () -> {
                            try {
                                synchronized (java.util.TimeZone.class) {
                                    setup.run();
                                    waiterThread.start();
                                    long deadline =
                                            System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                                    Thread.State state;
                                    while ((state = waiterThread.getState())
                                            != Thread.State.BLOCKED) {
                                        if (state == Thread.State.TERMINATED
                                                || System.nanoTime() > deadline) {
                                            waiterNeverWaited.set(true);
                                            return;
                                        }
                                        Thread.sleep(1);
                                    }
                                    // What Android's java.util.TimeZone.setDefault() does.
                                    clearer.start();
                                    clearer.join(TimeUnit.SECONDS.toMillis(10));
                                    if (clearer.isAlive()) {
                                        error.compareAndSet(
                                                null,
                                                new AssertionError(
                                                        "setICUDefault() blocked while "
                                                                + waiterName
                                                                + " was waiting for"
                                                                + " java.util.TimeZone.class"));
                                    }
                                }
                            } catch (Throwable t) {
                                error.compareAndSet(null, t);
                            }
                        },
                        "ICU-23629-android-setter");
        waiterThread.setDaemon(true);
        clearer.setDaemon(true);
        androidSetter.setDaemon(true);

        boolean stuck = false;
        try {
            androidSetter.start();
            androidSetter.join(TimeUnit.SECONDS.toMillis(30));
            waiterThread.join(TimeUnit.SECONDS.toMillis(30));
            clearer.join(TimeUnit.SECONDS.toMillis(30));
            stuck = androidSetter.isAlive() || waiterThread.isAlive() || clearer.isAlive();
            assertFalse("TimeZone default threads still running after 30 seconds", stuck);
        } finally {
            if (!stuck) {
                java.util.TimeZone.setDefault(originalJdk);
                TimeZone.setICUDefault(originalIcu);
            }
        }
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
        Assume.assumeFalse(
                waiterName
                        + " did not wait for java.util.TimeZone.class, so this JDK can't set up"
                        + " the scenario",
                waiterNeverWaited.get());
    }

    /**
     * A getDefault() refill that read java.util.TimeZone before a setICUDefault(null) must not
     * publish that stale zone afterwards. The writer changes the JDK default and then clears the
     * ICU default, the way Android's java.util.TimeZone.setDefault() does, while another thread
     * calls getDefault(). Each round starts with the ICU default cleared, so the getter refills it
     * from java.util.TimeZone while the writer changes it. After each round the two defaults must
     * agree.
     */
    @Test
    public void testGetDefaultRacingClearDoesNotCacheStaleZone() throws Exception {
        java.util.TimeZone[] zones = {
            java.util.TimeZone.getTimeZone("America/New_York"),
            java.util.TimeZone.getTimeZone("Europe/Berlin")
        };
        checkDefaultsAgreeAfterRaces(
                () -> TimeZone.setICUDefault(null),
                round -> {
                    java.util.TimeZone.setDefault(zones[round % 2]);
                    TimeZone.setICUDefault(null);
                },
                TimeZone::getDefault);
    }

    /**
     * setDefault() racing a setICUDefault(null) and a getDefault() refill must not leave the ICU
     * default behind the JDK default.
     */
    @Test
    public void testSetDefaultRacingClearKeepsDefaultsInSync() throws Exception {
        TimeZone[] zones = {
            TimeZone.getTimeZone("America/New_York"), TimeZone.getTimeZone("Europe/Berlin")
        };
        checkDefaultsAgreeAfterRaces(
                () -> {},
                round -> TimeZone.setDefault(zones[round % 2]),
                () -> {
                    TimeZone.setICUDefault(null);
                    TimeZone.getDefault();
                });
    }

    /**
     * On Android, setDefault() can race java.util.TimeZone.setDefault(), which stores the JDK
     * default and then clears the ICU default. The other thread does the same and then calls
     * getDefault(). setDefault() must not put its own zone back over that clear. This is
     * probabilistic: it reliably catches setDefault() storing its zone again after the JDK store,
     * but the window between that store and setDefault()'s check is too narrow to hit reliably.
     * (Android clears under the {@code java.util.TimeZone.class} monitor; the test doesn't, since
     * with 79.1's locking that would deadlock instead of failing.)
     */
    @Test
    public void testSetDefaultRacingJdkSetDefaultKeepsDefaultsInSync() throws Exception {
        TimeZone[] icuZones = {
            TimeZone.getTimeZone("America/New_York"), TimeZone.getTimeZone("Europe/Berlin")
        };
        java.util.TimeZone[] jdkZones = {
            java.util.TimeZone.getTimeZone("Asia/Tokyo"),
            java.util.TimeZone.getTimeZone("Pacific/Auckland")
        };
        AtomicInteger otherRound = new AtomicInteger();
        // The window is a few instructions wide, so this needs more rounds than the others.
        checkDefaultsAgreeAfterRaces(
                200_000,
                () -> {},
                round -> TimeZone.setDefault(icuZones[round % 2]),
                () -> {
                    java.util.TimeZone.setDefault(jdkZones[otherRound.getAndIncrement() % 2]);
                    TimeZone.setICUDefault(null);
                    TimeZone.getDefault();
                });
    }

    /**
     * setDefault() with an Olson zone whose rules were changed must keep that zone as the ICU
     * default, as in 79.1, even though the JDK default gets the stock zone. The check after the JDK
     * store must leave the ICU default alone when nothing else changed it. Skipped on Android,
     * where java.util.TimeZone.setDefault() clears the ICU default, so it comes back as the stock
     * zone there (in 79.1 too).
     */
    @Test
    public void testSetDefaultKeepsChangedOlsonZone() {
        Assume.assumeFalse(
                "java.util.TimeZone.setDefault() clears the ICU default on Android",
                TestUtil.getJavaVendor() == JavaVendor.Android);
        TimeZone originalIcu = TimeZone.getDefault();
        java.util.TimeZone originalJdk = java.util.TimeZone.getDefault();
        try {
            TimeZone zone = TimeZone.getTimeZone("Europe/Berlin");
            zone.setRawOffset(0);
            TimeZone.setDefault(zone);
            TimeZone icu = TimeZone.getDefault();
            assertEquals("ICU default ID", "Europe/Berlin", icu.getID());
            assertEquals("ICU default raw offset", 0, icu.getRawOffset());
        } finally {
            java.util.TimeZone.setDefault(originalJdk);
            TimeZone.setICUDefault(originalIcu);
        }
    }

    /**
     * A setICUDefault() of the same frozen zone that lands while setDefault() of that zone runs
     * must leave it as the ICU default, as in 79.1. The zone is an OlsonTimeZone with changed
     * rules, so a refill from java.util.TimeZone would give the stock zone instead. Skipped on
     * Android for the same reason as testSetDefaultKeepsChangedOlsonZone().
     */
    @Test
    public void testSetIcuDefaultOfSameZoneDuringSetDefaultKeepsIt() {
        Assume.assumeFalse(
                "java.util.TimeZone.setDefault() clears the ICU default on Android",
                TestUtil.getJavaVendor() == JavaVendor.Android);
        TimeZone originalIcu = TimeZone.getDefault();
        java.util.TimeZone originalJdk = java.util.TimeZone.getDefault();
        try {
            SetIcuDefaultOnGetIdZone zone = new SetIcuDefaultOnGetIdZone("Europe/Berlin");
            zone.setRawOffset(0);
            zone.freeze();
            TimeZone.setDefault(zone);
            assertTrue(
                    "SetIcuDefaultOnGetIdZone.getID() hook never ran; this test relies on"
                            + " setDefault() calling getID() on an OlsonTimeZone",
                    zone.hookRan);
            TimeZone icu = TimeZone.getDefault();
            assertEquals("ICU default ID", "Europe/Berlin", icu.getID());
            assertEquals("ICU default raw offset", 0, icu.getRawOffset());
        } finally {
            java.util.TimeZone.setDefault(originalJdk);
            TimeZone.setICUDefault(originalIcu);
        }
    }

    /** An OlsonTimeZone whose getID() calls setICUDefault(this) once, from within setDefault(). */
    private static final class SetIcuDefaultOnGetIdZone extends OlsonTimeZone {
        private static final long serialVersionUID = 1L;
        boolean hookRan;

        SetIcuDefaultOnGetIdZone(String id) {
            super(id);
        }

        @Override
        public String getID() {
            if (!hookRan && calledFromSetDefault()) {
                hookRan = true;
                TimeZone.setICUDefault(this);
            }
            return super.getID();
        }

        private static boolean calledFromSetDefault() {
            for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
                if (frame.getClassName().equals(TimeZone.class.getName())
                        && frame.getMethodName().equals("setDefault")) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * A getDefault() while setDefault() runs must already get the new zone, as in 79.1, where
     * setDefault() set the ICU default first and getDefault() waited for the lock it held.
     */
    @Test
    public void testGetDefaultDuringSetDefaultGetsNewZone() {
        TimeZone originalIcu = TimeZone.getDefault();
        java.util.TimeZone originalJdk = java.util.TimeZone.getDefault();
        AtomicBoolean hookRan = new AtomicBoolean();
        AtomicReference<String> seen = new AtomicReference<>();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
            TimeZone.setDefault(
                    new HookedZone(
                            "ICU-23629-Custom",
                            hookRan,
                            () -> seen.set(TimeZone.getDefault().getID())));
            assertTrue(
                    "HookedZone.getID() hook never ran; this test relies on setDefault() wrapping"
                            + " the zone in a TimeZoneAdapter after it stores the ICU default",
                    hookRan.get());
            assertEquals("getDefault() during setDefault()", "ICU-23629-Custom", seen.get());
        } finally {
            java.util.TimeZone.setDefault(originalJdk);
            TimeZone.setICUDefault(originalIcu);
        }
    }

    /**
     * Two setDefault() calls must not interleave: as in 79.1, each runs as a unit. HookedZone
     * starts a second setDefault() after this call's ICU store and waits (up to 10 seconds) until
     * it has either finished or blocked. The test checks that the second zone ends up as both
     * defaults, that is, that the second call ran after this one rather than inside it.
     */
    @Test
    public void testSetDefaultDuringAnotherSetDefaultKeepsDefaultsInSync() throws Exception {
        TimeZone originalIcu = TimeZone.getDefault();
        java.util.TimeZone originalJdk = java.util.TimeZone.getDefault();
        AtomicBoolean hookRan = new AtomicBoolean();
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicReference<Thread.State> otherStateInHook = new AtomicReference<>();
        int otherOffset = 19_234_000;
        Thread other =
                new Thread(
                        () -> {
                            try {
                                TimeZone.setDefault(
                                        new SimpleTimeZone(otherOffset, "ICU-23629-Other"));
                            } catch (Throwable t) {
                                error.compareAndSet(null, t);
                            }
                        },
                        "ICU-23629-other-setDefault");
        other.setDaemon(true);
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"));
            TimeZone.setDefault(
                    new HookedZone(
                            "ICU-23629-Test",
                            hookRan,
                            () -> {
                                other.start();
                                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                                Thread.State state;
                                while ((state = other.getState()) != Thread.State.BLOCKED
                                        && state != Thread.State.TERMINATED
                                        && System.nanoTime() < deadline) {
                                    try {
                                        Thread.sleep(1);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                        break;
                                    }
                                }
                                otherStateInHook.set(state);
                            }));
            other.join(TimeUnit.SECONDS.toMillis(10));
            assertFalse("second setDefault() still running", other.isAlive());
            if (error.get() != null) {
                throw new AssertionError(error.get());
            }
            assertTrue(
                    "HookedZone.getID() hook never ran; this test relies on setDefault() wrapping"
                            + " the zone in a TimeZoneAdapter after it stores the ICU default",
                    hookRan.get());
            Thread.State state = otherStateInHook.get();
            assertTrue(
                    "second setDefault() never blocked or finished while the first was between"
                            + " its stores (state "
                            + state
                            + ")",
                    state == Thread.State.BLOCKED || state == Thread.State.TERMINATED);
            TimeZone icu = TimeZone.getDefault();
            assertEquals("ICU default ID", "ICU-23629-Other", icu.getID());
            assertEquals("ICU default raw offset", otherOffset, icu.getRawOffset());
            assertEquals(
                    "JDK default ID", "ICU-23629-Other", java.util.TimeZone.getDefault().getID());
        } finally {
            if (!other.isAlive()) {
                java.util.TimeZone.setDefault(originalJdk);
                TimeZone.setICUDefault(originalIcu);
            }
        }
    }

    /**
     * A zone that runs {@code hook} once, from getID(), when TimeZone.setDefault() wraps it in a
     * TimeZoneAdapter: that is, after setDefault() has stored the ICU default and before it stores
     * the JDK default. Clones share the hook.
     */
    private static final class HookedZone extends SimpleTimeZone {
        private static final long serialVersionUID = 1L;
        private final transient AtomicBoolean hookRan;
        private final transient Runnable hook;

        HookedZone(String id, AtomicBoolean hookRan, Runnable hook) {
            super(0, id);
            this.hookRan = hookRan;
            this.hook = hook;
        }

        // The hook's own calls (getDefault() clones this zone) call getID() again.
        private static final ThreadLocal<Boolean> inGetID = ThreadLocal.withInitial(() -> false);

        @Override
        public String getID() {
            // hookRan is null while the SimpleTimeZone constructor runs.
            if (hookRan != null && !inGetID.get()) {
                inGetID.set(true);
                try {
                    if (calledFromAdapterInSetDefault() && hookRan.compareAndSet(false, true)) {
                        hook.run();
                    }
                } finally {
                    inGetID.set(false);
                }
            }
            return super.getID();
        }

        private static boolean calledFromAdapterInSetDefault() {
            boolean inAdapter = false;
            for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
                if (frame.getClassName().equals(TimeZoneAdapter.class.getName())
                        && frame.getMethodName().equals("<init>")) {
                    inAdapter = true;
                } else if (inAdapter
                        && frame.getClassName().equals(TimeZone.class.getName())
                        && frame.getMethodName().equals("setDefault")) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Runs {@code beforeRound}, then {@code writer} and {@code other} at the same time on two
     * threads, round after round, and checks after each round that the ICU and JDK defaults have
     * the same ID.
     */
    private void checkDefaultsAgreeAfterRaces(
            Runnable beforeRound, IntConsumer writer, Runnable other) throws Exception {
        checkDefaultsAgreeAfterRaces(50_000, beforeRound, writer, other);
    }

    private void checkDefaultsAgreeAfterRaces(
            int rounds, Runnable beforeRound, IntConsumer writer, Runnable other) throws Exception {
        TimeZone originalIcu = TimeZone.getDefault();
        java.util.TimeZone originalJdk = java.util.TimeZone.getDefault();
        CyclicBarrier start = new CyclicBarrier(2);
        CyclicBarrier end = new CyclicBarrier(2);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread otherThread =
                new Thread(
                        () -> {
                            try {
                                for (int i = 0; i < rounds; i++) {
                                    start.await(10, TimeUnit.SECONDS);
                                    other.run();
                                    end.await(10, TimeUnit.SECONDS);
                                }
                            } catch (Throwable t) {
                                error.compareAndSet(null, t);
                                // Wake the main thread instead of leaving it to time out.
                                start.reset();
                                end.reset();
                            }
                        },
                        "TimeZone-default-race");
        otherThread.setDaemon(true);
        String mismatch = null;
        try {
            otherThread.start();
            for (int i = 0; i < rounds && mismatch == null && error.get() == null; i++) {
                beforeRound.run();
                start.await(10, TimeUnit.SECONDS);
                writer.accept(i);
                end.await(10, TimeUnit.SECONDS);
                String icu = TimeZone.getDefault().getID();
                String jdk = java.util.TimeZone.getDefault().getID();
                if (!icu.equals(jdk)) {
                    mismatch = "round " + i + ": ICU default " + icu + ", JDK default " + jdk;
                }
            }
        } catch (BrokenBarrierException | TimeoutException e) {
            if (error.get() == null) {
                throw e;
            }
        } finally {
            otherThread.interrupt();
            otherThread.join(TimeUnit.SECONDS.toMillis(10));
            java.util.TimeZone.setDefault(originalJdk);
            TimeZone.setICUDefault(originalIcu);
        }
        if (error.get() != null && mismatch == null) {
            throw new AssertionError(error.get());
        }
        assertNull("ICU and JDK defaults disagree after " + mismatch, mismatch);
    }

    /**
     * On Android, java.util.TimeZone.setDefault() clears the ICU default right after storing the
     * JDK default, including when ICU's setDefault() calls it. The next getDefault() must get a
     * custom zone back from the JDK default rather than looking up its ID, which ICU doesn't know.
     */
    @Test
    public void testClearAfterSetDefaultKeepsCustomZone() {
        TimeZone originalIcu = TimeZone.getDefault();
        java.util.TimeZone originalJdk = java.util.TimeZone.getDefault();
        int offset = 19_234_000;
        try {
            TimeZone.setDefault(new SimpleTimeZone(offset, "ICU-23629-Custom"));
            TimeZone.setICUDefault(null);
            TimeZone icu = TimeZone.getDefault();
            assertEquals("ICU default ID", "ICU-23629-Custom", icu.getID());
            assertEquals("ICU default raw offset", offset, icu.getRawOffset());
        } finally {
            java.util.TimeZone.setDefault(originalJdk);
            TimeZone.setICUDefault(originalIcu);
        }
    }

    /**
     * If the JDK default is a TimeZoneAdapter around an ICU zone that can't be frozen (the base
     * class freeze() throws), getDefault() must fall back to looking the zone up by ID rather than
     * throw.
     */
    @Test
    public void testGetDefaultWithUnfreezableAdapterZoneFallsBackToId() {
        TimeZone originalIcu = TimeZone.getDefault();
        java.util.TimeZone originalJdk = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(
                    new TimeZoneAdapter(new UnfreezableZone(0, "America/New_York")));
            TimeZone.setICUDefault(null);
            TimeZone icu = TimeZone.getDefault();
            assertEquals("ICU default ID", "America/New_York", icu.getID());
            assertEquals(
                    "ICU default raw offset (looked up by ID)",
                    java.util.TimeZone.getTimeZone("America/New_York").getRawOffset(),
                    icu.getRawOffset());
        } finally {
            java.util.TimeZone.setDefault(originalJdk);
            TimeZone.setICUDefault(originalIcu);
        }
    }

    /** A fixed-offset ICU zone that doesn't implement freeze(). */
    private static final class UnfreezableZone extends TimeZone {
        private static final long serialVersionUID = 1L;
        private int rawOffset;

        UnfreezableZone(int rawOffset, String id) {
            super(id);
            this.rawOffset = rawOffset;
        }

        @Override
        public int getOffset(
                int era, int year, int month, int day, int dayOfWeek, int milliseconds) {
            return rawOffset;
        }

        @Override
        public void setRawOffset(int offsetMillis) {
            rawOffset = offsetMillis;
        }

        @Override
        public int getRawOffset() {
            return rawOffset;
        }

        @Override
        public boolean useDaylightTime() {
            return false;
        }

        @Override
        public boolean inDaylightTime(java.util.Date date) {
            return false;
        }
    }

    /** ZoneMeta SoftReference caches under contention. */
    @Test
    public void testZoneMetaConcurrent() throws Exception {
        runConcurrent(
                "ZoneMeta",
                tid -> {
                    for (int i = 0; i < ITERATIONS; i++) {
                        Set<String> ids =
                                TimeZone.getAvailableIDs(
                                        TimeZone.SystemTimeZoneType.ANY, null, null);
                        assertFalse("Zone IDs should not be empty", ids == null || ids.isEmpty());
                    }
                });
    }

    /** TimeZoneFormat volatile DCL for timezone name cache. */
    @Test
    public void testTimeZoneFormatConcurrent() throws Exception {
        ULocale[] locales = {ULocale.US, ULocale.GERMANY, ULocale.JAPAN, ULocale.FRANCE};
        runConcurrent(
                "TimeZoneFormat",
                tid -> {
                    for (int i = 0; i < ITERATIONS; i++) {
                        ULocale loc = locales[(tid + i) % locales.length];
                        TimeZoneFormat tzf = TimeZoneFormat.getInstance(loc);
                        assertNotNull("TimeZoneFormat should not be null", tzf);
                        String formatted =
                                tzf.format(
                                        TimeZoneFormat.Style.GENERIC_LONG,
                                        TimeZone.getTimeZone("America/New_York"),
                                        System.currentTimeMillis());
                        assertNotNull("TimeZone format result should not be null", formatted);
                    }
                });
    }
}
