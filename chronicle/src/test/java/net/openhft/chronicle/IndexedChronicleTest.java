/*
 * Copyright 2013 Peter Lawrey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.openhft.chronicle;

//import vanilla.java.processingengine.affinity.PosixJNAAffinity;

import net.openhft.chronicle.tools.ChronicleIndexReader;
import net.openhft.chronicle.tools.ChronicleTools;
import org.jetbrains.annotations.NotNull;
import org.junit.Ignore;
import org.junit.Test;

import java.io.IOException;
import java.util.Random;

import static org.junit.Assert.*;

/**
 * @author peter.lawrey
 */
public class IndexedChronicleTest {

    public static final String TMP = System.getProperty("java.io.tmpdir");
    private static final long WARMUP = 20000;

    private static void validateExcerpt(@NotNull ExcerptCommon r, int i, int expected) {
        if (expected > r.remaining() || 8 * expected < r.remaining())
            assertEquals("index: " + r.index(), expected, r.remaining());
        if (expected > r.capacity() || 8 * expected < r.capacity())
            assertEquals("index: " + r.index(), expected, r.capacity());
        assertEquals(0, r.position());
        long l = r.readLong();
        assertEquals(i, l);
        assertEquals(8, r.position());
        if (expected - 8 != r.remaining())
            assertEquals("index: " + r.index(), expected - 8, r.remaining());
        double d = r.readDouble();
        assertEquals(i, d, 0.0);


        if (0 != r.remaining())
            assertEquals("index: " + r.index(), 0, r.remaining());

        r.position(0);
        long l2 = r.readLong();
        assertEquals(i, l2);

        r.position(expected);

        r.finish();
    }

    @Test
    public void singleThreaded() throws IOException {
        final String basePath = TMP + "/singleThreaded";
        ChronicleTools.deleteOnExit(basePath);

        ChronicleConfig config = ChronicleConfig.TEST.clone();
        int dataBlockSize = 4096;
        config.dataBlockSize(dataBlockSize);
        config.indexBlockSize(128);
        IndexedChronicle chronicle = new IndexedChronicle(basePath, config);
        int i = 0;
        try {
            ExcerptAppender w = chronicle.createAppender();
            ExcerptTailer r = chronicle.createTailer();
            Excerpt e = chronicle.createExcerpt();
            Random rand = new Random(1);
            // finish just at the end of the first page.
            int idx = 0;
            for (i = 0; i < 50000; i++) {
//                System.out.println(i + " " + idx);
//                if (i == 28)
//                    ChronicleIndexReader.main(basePath + ".index");

                assertFalse(r.nextIndex());
                assertFalse(e.index(idx));
                int capacity = 16 * (1 + rand.nextInt(7));

                w.startExcerpt(capacity);
                assertEquals(0, w.position());
                w.writeLong(i);
                assertEquals(8, w.position());
                w.writeDouble(i);

                int expected = 16;
                assertEquals(expected, w.position());
                assertEquals(capacity - expected, w.remaining());

                w.finish();

//                ChronicleIndexReader.main(basePath + ".index");

                if (!r.nextIndex()) {
                    assertTrue(r.nextIndex());
                }
                validateExcerpt(r, i, expected);

//                if (i >= 111)
//                    ChronicleIndexReader.main(basePath + ".index");
                if (!e.index(idx++)) {
                    assertTrue(e.wasPadding());
                    assertTrue(e.index(idx++));
                }
                validateExcerpt(e, i, expected);

            }
            w.close();
            r.close();
        } finally {
            chronicle.close();
            System.out.println("i: " + i);
//            ChronicleIndexReader.main(basePath + ".index");
            ChronicleTools.deleteOnExit(basePath);
        }
    }

    @Test
    public void multiThreaded() throws IOException, InterruptedException {
//        for (int i = 0; i < 20; i++) System.out.println();
        if (Runtime.getRuntime().availableProcessors() < 2) {
            System.err.println("Test requires 2 CPUs, skipping");
            return;
        }

        final String basePath = TMP + "/multiThreaded";
        ChronicleTools.deleteOnExit(basePath);
        final ChronicleConfig config = ChronicleConfig.DEFAULT.clone();
        int dataBlockSize = 1 << 26;
        config.dataBlockSize(dataBlockSize);
        config.indexBlockSize(dataBlockSize / 4);
        IndexedChronicle chronicle = new IndexedChronicle(basePath, config);
        final ExcerptTailer r = chronicle.createTailer();

        final long words = 500L * 1000 * 1000;
        final int size = 4;
        long start = System.nanoTime();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    IndexedChronicle chronicle = new IndexedChronicle(basePath, config);
                    final ExcerptAppender w = chronicle.createAppender();
                    for (int i = 0; i < words; i += size) {
                        w.startExcerpt(4L * size);
                        for (int s = 0; s < size; s++)
                            w.writeInt(1 + i);
//                        w.position(4L * size);
                        w.finish();
                    }
                    w.close();

//                    chronicle.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
        t.start();

        long maxDelay = 0, maxJitter = 0;
        for (long i = 0; i < words; i += size) {
            if (!r.nextIndex()) {
                long start0 = System.nanoTime();
                long last = start0;
                while (!r.nextIndex()) {
                    long now = System.nanoTime();
                    long jitter = now - last;
                    if (i > WARMUP && maxJitter < jitter)
                        maxJitter = jitter;
                    long delay0 = now - start0;
                    if (delay0 > 20e6)
                        throw new AssertionError("index: " + r.index());
                    if (i > WARMUP && maxDelay < delay0)
                        maxDelay = delay0;
                    last = now;
                }
            }
            try {
                for (int s = 0; s < size; s++) {
                    int j = r.readInt();
                    if (j != i + 1) {
                        ChronicleIndexReader.main(basePath + ".index");
                        throw new AssertionError(j + " != " + (i + 1));
                    }
                }
                r.finish();
            } catch (Exception e) {
                System.err.println("i= " + i);
                e.printStackTrace();
                break;
            }
        }

        r.close();
        long rate = words / size * 10 * 1000L / (System.nanoTime() - start);
        System.out.println("Rate = " + rate / 10.0 + " Mmsg/sec for " + size * 4 + " byte messages, " +
                "maxJitter: " + maxJitter / 1000 + " us, " +
                "maxDelay: " + maxDelay / 1000 + " us," +
                "totalWait: " + (PrefetchingMappedFileCache.totalWait.longValue() + SingleMappedFileCache.totalWait.longValue()) / 1000 + " us");
        Thread.sleep(200);
        ChronicleTools.deleteOnExit(basePath);
    }

    @Test
    public void multiThreaded2() throws IOException, InterruptedException {
        if (Runtime.getRuntime().availableProcessors() < 3) {
            System.err.println("Test requires 3 CPUs, skipping");
            return;
        }
        final String basePath = TMP + "/multiThreaded";
        final String basePath2 = TMP + "/multiThreaded2";
        ChronicleTools.deleteOnExit(basePath);
        ChronicleTools.deleteOnExit(basePath2);

        final ChronicleConfig config = ChronicleConfig.DEFAULT.clone();
//        config.dataBlockSize(4*1024);
//        config.indexBlockSize(4 * 1024);

        final int runs = 100 * 1000 * 1000;
        final int size = 4;
        long start = System.nanoTime();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    IndexedChronicle chronicle = new IndexedChronicle(basePath, config);
                    final ExcerptAppender w = chronicle.createAppender();
                    for (int i = 0; i < runs; i += size) {
                        w.startExcerpt(4 * size);
                        for (int s = 0; s < size; s++)
                            w.writeInt(1 + i);
                        w.finish();
                    }
                    w.close();
//                    chronicle.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }, "t1");
        t.start();

        Thread t2 = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    IndexedChronicle chronicle = new IndexedChronicle(basePath, config);
                    final ExcerptTailer r = chronicle.createTailer();
                    IndexedChronicle chronicle2 = new IndexedChronicle(basePath2, config);
                    final ExcerptAppender w = chronicle2.createAppender();
                    for (int i = 0; i < runs; i += size) {
                        do {
                        } while (!r.nextIndex());

                        w.startExcerpt(r.remaining());
                        for (int s = 0; s < size; s++)
                            w.writeInt(r.readInt());
                        r.finish();
                        w.finish();
                    }
                    w.close();
//                    chronicle.close();
//                    chronicle2.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }, "t2");
        t2.start();

        IndexedChronicle chronicle = new IndexedChronicle(basePath2, config);
        final ExcerptTailer r = chronicle.createTailer();

        for (int i = 0; i < runs; i += size) {
            do {
            } while (!r.nextIndex());
            try {
                for (int s = 0; s < size; s++) {
                    long l = r.readInt();
                    if (l != i + 1)
                        throw new AssertionError();
                }
                r.finish();
            } catch (Exception e) {
                System.err.println("i= " + i);
                e.printStackTrace();
                break;
            }
        }
        r.close();
        long rate = 2 * runs / size * 10000L / (System.nanoTime() - start);
        System.out.println("Rate = " + rate / 10.0 + " Mmsg/sec");
        chronicle.close();
        Thread.sleep(200);
        ChronicleTools.deleteOnExit(basePath);
        ChronicleTools.deleteOnExit(basePath2);
    }

    @Test
    @Ignore
    public void testWarmup() throws IOException {
        ChronicleConfig cc = ChronicleConfig.DEFAULT;
        cc.dataBlockSize(64);
        cc.indexBlockSize(64);
        String basePath = TMP + "/warmup";
        ChronicleTools.deleteOnExit(basePath);
        IndexedChronicle ic = new IndexedChronicle(basePath, cc);
        ExcerptAppender appender = ic.createAppender();
        ExcerptTailer tailer = ic.createTailer();
        for (int i = 0; i < 200000; i++) {
            if (i % 1000 == 0)
                System.out.println(i);
            appender.startExcerpt(4);
            appender.writeInt(i);
            appender.finish();
            boolean b = tailer.nextIndex() || tailer.nextIndex();
            tailer.readInt();
            tailer.finish();
        }
        ic.close();

    }
}