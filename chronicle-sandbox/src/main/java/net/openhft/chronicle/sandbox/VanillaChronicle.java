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

package net.openhft.chronicle.sandbox;

import net.openhft.affinity.AffinitySupport;
import net.openhft.chronicle.*;
import net.openhft.lang.Maths;
import net.openhft.lang.io.DirectBytes;
import net.openhft.lang.io.DirectStore;
import net.openhft.lang.io.IOTools;
import net.openhft.lang.io.NativeBytes;
import net.openhft.lang.io.serialization.BytesMarshallerFactory;
import net.openhft.lang.io.serialization.impl.VanillaBytesMarshallerFactory;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.ref.WeakReference;

/**
 * Created by peter
 */
public class VanillaChronicle implements Chronicle {
    private final String name;
    private final String basePath;
    private final VanillaChronicleConfig config;
    private final ThreadLocal<WeakReference<BytesMarshallerFactory>> marshallersCache = new ThreadLocal<WeakReference<BytesMarshallerFactory>>();
    private final ThreadLocal<WeakReference<ExcerptTailer>> tailerCache = new ThreadLocal<WeakReference<ExcerptTailer>>();
    private final ThreadLocal<WeakReference<ExcerptAppender>> appenderCache = new ThreadLocal<WeakReference<ExcerptAppender>>();
    private final DirectBytes NO_BYTES = DirectStore.allocateLazy(4096).createSlice();
    private final VanillaIndexCache indexCache;
    private final VanillaDataCache dataCache;
    private final int indexBlockSizeBits, indexBlockSizeMask;
    private final int indexBlockLongsBits, indexBlockLongsMask;
    private final int dataBlockSizeBits, dataBlockSizeMask;
    private final int entriesForCycleBits;
    private final long entriesForCycleMask;
    //    private volatile int cycle;
    private volatile long lastWrittenIndex;

    public VanillaChronicle(String basePath) {
        this(basePath, VanillaChronicleConfig.DEFAULT);
    }

    public VanillaChronicle(String basePath, VanillaChronicleConfig config) {
        this.basePath = basePath;
        this.config = config;
        name = new File(basePath).getName();
        DateCache dateCache = new DateCache(config.cycleFormat(), config.cycleLength());
        indexBlockSizeBits = Maths.intLog2(config.indexBlockSize());
        indexBlockSizeMask = -1 >>> -indexBlockSizeBits;
        indexCache = new VanillaIndexCache(basePath, indexBlockSizeBits, dateCache);
        indexBlockLongsBits = indexBlockSizeBits - 3;
        indexBlockLongsMask = indexBlockSizeMask >>> 3;
        dataBlockSizeBits = Maths.intLog2(config.dataBlockSize());
        dataBlockSizeMask = -1 >>> -dataBlockSizeBits;
        dataCache = new VanillaDataCache(basePath, dataBlockSizeBits, dateCache);
        entriesForCycleBits = Maths.intLog2(config.entriesPerCycle());
        entriesForCycleMask = -1L >>> -entriesForCycleBits;

//        cycle = (int) (System.currentTimeMillis() / config.cycleLength());
    }

    @Override
    public String name() {
        return name;
    }

    @NotNull
    @Override
    public Excerpt createExcerpt() throws IOException {
        return new VanillaExcerpt();
    }

    protected BytesMarshallerFactory acquireBMF() {
        WeakReference<BytesMarshallerFactory> bmfRef = marshallersCache.get();
        BytesMarshallerFactory bmf = null;
        if (bmfRef != null)
            bmf = bmfRef.get();
        if (bmf == null) {
            bmf = createBMF();
            marshallersCache.set(new WeakReference<BytesMarshallerFactory>(bmf));
        }
        return bmf;
    }

    protected BytesMarshallerFactory createBMF() {
        return new VanillaBytesMarshallerFactory();
    }

    @NotNull
    @Override
    public ExcerptTailer createTailer() throws IOException {
        WeakReference<ExcerptTailer> ref = tailerCache.get();
        ExcerptTailer tailer = null;
        if (ref != null)
            tailer = ref.get();
        if (tailer == null) {
            tailer = createTailer0();
            tailerCache.set(new WeakReference<ExcerptTailer>(tailer));
        }
        return tailer;
    }

    private ExcerptTailer createTailer0() {
        return new VanillaTailer();
    }

    @NotNull
    @Override
    public ExcerptAppender createAppender() throws IOException {
        WeakReference<ExcerptAppender> ref = appenderCache.get();
        ExcerptAppender appender = null;
        if (ref != null)
            appender = ref.get();
        if (appender == null) {
            appender = createAppender0();
            appenderCache.set(new WeakReference<ExcerptAppender>(appender));
        }
        return appender;
    }

    private ExcerptAppender createAppender0() {
        return new VanillaAppender();
    }

    @Override
    public long lastWrittenIndex() {
        return lastWrittenIndex;
    }

    @Override
    public long size() {
        return lastWrittenIndex + 1;
    }

    @Override
    public void close() {
        indexCache.close();
        dataCache.close();
    }

    public void clear() {
        indexCache.close();
        dataCache.close();
        IOTools.deleteDir(basePath);
    }

    public void checkCounts(int min, int max) {
        indexCache.checkCounts(min, max);
        dataCache.checkCounts(min, max);
    }

    abstract class AbstractVanillaExcerpt extends NativeBytes implements ExcerptCommon {
        protected long index = -1;
        protected VanillaFile dataFile;

        public AbstractVanillaExcerpt() {
            super(acquireBMF(), NO_BYTES.startAddr(), NO_BYTES.startAddr(), NO_BYTES.startAddr());
        }

        @Override
        public boolean wasPadding() {
            return false;
        }

        @Override
        public long index() {
            return index;
        }

        @Override
        public long lastWrittenIndex() {
            return VanillaChronicle.this.lastWrittenIndex();
        }

        @Override
        public long size() {
            return lastWrittenIndex() + 1;
        }

        @Override
        public ExcerptCommon toEnd() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Chronicle chronicle() {
            return VanillaChronicle.this;
        }

        public int cycle() {
            return (int) (System.currentTimeMillis() / config.cycleLength());
        }

        private int lastCycle = Integer.MIN_VALUE,
                lastDailyCount = Integer.MIN_VALUE,
                lastThreadId = Integer.MIN_VALUE,
                lastDataCount = Integer.MIN_VALUE;
        private VanillaFile lastIndexFile = null, lastDataFile = null;

        public boolean index(long nextIndex) {
            try {
                int cycle = (int) (nextIndex >>> entriesForCycleBits);
                int dailyCount = (int) ((nextIndex & entriesForCycleMask) >>> indexBlockLongsBits);
                int dailyOffset = (int) (nextIndex & indexBlockLongsMask);
                long indexValue;
                try {
                    if (lastCycle != cycle || lastDailyCount != dailyCount) {
                        if (lastIndexFile != null) {
                            lastIndexFile.decrementUsage();
                            lastIndexFile = null;
                        }
                        lastIndexFile = indexCache.indexFor(cycle, dailyCount, false);
                        assert lastIndexFile.usage() > 1;
                        lastCycle = cycle;
                        lastDailyCount = dailyCount;
                        if (lastDataFile != null) {
                            lastDataFile.decrementUsage();
                            lastDataFile = null;
                        }
                    }
                    indexValue = lastIndexFile.bytes().readVolatileLong(dailyOffset << 3);
                } catch (FileNotFoundException e) {
                    return false;
                }
                if (indexValue == 0) {
                    return false;
                }
                int threadId = (int) (indexValue >>> 48);
                long dataOffset0 = indexValue & (-1L >>> -48);
                int dataCount = (int) (dataOffset0 >>> dataBlockSizeBits);
                int dataOffset = (int) (dataOffset0 & dataBlockSizeMask);
                if (lastThreadId != threadId || lastDataCount != dataCount) {
                    if (dataFile != null) {
                        dataFile.decrementUsage();
                        dataFile = null;
                    }
                }
                if (dataFile == null) {
                    dataFile = dataCache.dataFor(cycle, threadId, dataCount, false);
                    lastThreadId = threadId;
                    lastDataCount = dataCount;
                }
                NativeBytes bytes = dataFile.bytes();
                int len = bytes.readVolatileInt(dataOffset - 4);
                if (len == 0)
                    return false;
                int len2 = ~len;
                // invalid if either the top two bits are set,
                if ((len2 >>> 30) != 0)
                    throw new IllegalStateException("Corrupted length " + Integer.toHexString(len));
                startAddr = positionAddr = bytes.startAddr() + dataOffset;
                limitAddr = startAddr + ~len;
                index = nextIndex;
                finished = false;
                return true;
            } catch (IOException ioe) {
                throw new AssertionError(ioe);
            }
        }

        public boolean nextIndex() {
            if (index < 0) {
                toStart();
                if (index < 0)
                    return false;
                index--;
            }
            long nextIndex = index + 1;
            while (true) {
                boolean found = index(nextIndex);
                if (found)
                    return found;
                int cycle = (int) (nextIndex / config.entriesPerCycle());
                if (cycle >= cycle())
                    return false;
                nextIndex = (cycle + 1) * config.entriesPerCycle();
            }
        }

        @NotNull
        public ExcerptCommon toStart() {
            long indexCount = indexCache.firstIndex();
            if (indexCount >= 0) {
                index = indexCount * config.entriesPerCycle();
            }
            return this;
        }
    }

    class VanillaExcerpt extends AbstractVanillaExcerpt implements Excerpt {
        public long findMatch(@NotNull ExcerptComparator comparator) {
            long lo = 0, hi = lastWrittenIndex();
            while (lo <= hi) {
                long mid = (hi + lo) >>> 1;
                if (!index(mid)) {
                    if (mid > lo)
                        index(--mid);
                    else
                        break;
                }
                int cmp = comparator.compare((Excerpt) this);
                finish();
                if (cmp < 0)
                    lo = mid + 1;
                else if (cmp > 0)
                    hi = mid - 1;
                else
                    return mid; // key found
            }
            return ~lo; // -(lo + 1)
        }

        public void findRange(@NotNull long[] startEnd, @NotNull ExcerptComparator comparator) {
            // lower search range
            long lo1 = 0, hi1 = lastWrittenIndex();
            // upper search range
            long lo2 = 0, hi2 = hi1;
            boolean both = true;
            // search for the low values.
            while (lo1 <= hi1) {
                long mid = (hi1 + lo1) >>> 1;
                if (!index(mid)) {
                    if (mid > lo1)
                        index(--mid);
                    else
                        break;
                }
                int cmp = comparator.compare((Excerpt) this);
                finish();

                if (cmp < 0) {
                    lo1 = mid + 1;
                    if (both)
                        lo2 = lo1;
                } else if (cmp > 0) {
                    hi1 = mid - 1;
                    if (both)
                        hi2 = hi1;
                } else {
                    hi1 = mid - 1;
                    if (both)
                        lo2 = mid + 1;
                    both = false;
                }
            }
            // search for the high values.
            while (lo2 <= hi2) {
                long mid = (hi2 + lo2) >>> 1;
                if (!index(mid)) {
                    if (mid > lo2)
                        index(--mid);
                    else
                        break;
                }
                int cmp = comparator.compare((Excerpt) this);
                finish();

                if (cmp <= 0) {
                    lo2 = mid + 1;
                } else {
                    hi2 = mid - 1;
                }
            }
            startEnd[0] = lo1; // inclusive
            startEnd[1] = lo2; // exclusive
        }

        @NotNull
        @Override
        public Excerpt toStart() {
            super.toStart();
            return this;
        }

        @Override
        public Excerpt toEnd() {
            super.toEnd();
            return this;
        }

        @Override
        public void finish() {
            super.finish();
        }
    }

    class VanillaAppender extends AbstractVanillaExcerpt implements ExcerptAppender {
        private int lastCycle = Integer.MIN_VALUE, lastThreadId = Integer.MIN_VALUE;
        private int appenderCycle, appenderThreadId;
        private boolean nextSynchronous;
        private VanillaFile lastIndexFile = null;
        private VanillaFile appenderFile;

        VanillaAppender() {
        }

        @Override
        public void startExcerpt() {
            startExcerpt(config.defaultMessageSize());
        }

        @Override
        public void startExcerpt(long capacity) {
            try {
                appenderCycle = cycle();
                appenderThreadId = AffinitySupport.getThreadId();
                if (appenderCycle != lastCycle || appenderThreadId != lastThreadId) {
                    if (appenderFile != null) {
                        appenderFile.decrementUsage();
                        appenderFile = null;
                    }
                    appenderFile = dataCache.dataForLast(appenderCycle, appenderThreadId);
                    lastCycle = appenderCycle;
                    lastThreadId = appenderThreadId;
                    if (lastIndexFile != null) {
                        lastIndexFile.decrementUsage();
                        lastIndexFile = null;
                    }
                }
                if (appenderFile.bytes().remaining() < capacity + 4) {
                    dataCache.incrementLastCount();
                    appenderFile.decrementUsage();
                    appenderFile = null;
                    appenderFile = dataCache.dataForLast(appenderCycle, appenderThreadId);
                }
                startAddr = positionAddr = appenderFile.bytes().positionAddr() + 4;
                limitAddr = startAddr + capacity;
                nextSynchronous = config.synchronous();
                finished = false;
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }

        @Override
        public void addPaddedEntry() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean nextSynchronous() {
            return nextSynchronous;
        }

        @Override
        public void nextSynchronous(boolean nextSynchronous) {
            this.nextSynchronous = nextSynchronous;
        }

        @Override
        public void finish() {
            super.finish();
            int length = ~(int) (positionAddr - startAddr);
            NativeBytes.UNSAFE.putOrderedInt(null, startAddr - 4, length);
            // position of the start not the end.
            int offset = (int) (startAddr - appenderFile.baseAddr());
            long dataOffset = appenderFile.indexCount() * config.dataBlockSize() + offset;
            long indexValue = ((long) appenderThreadId << 48) + dataOffset;
            try {
                boolean done = false;
                if (lastIndexFile != null) {
                    NativeBytes bytes = lastIndexFile.bytes();
                    while (bytes.remaining() >= 8) {
                        if (bytes.compareAndSwapLong(bytes.position(), 0L, indexValue)) {
                            if (nextSynchronous)
                                lastIndexFile.force();
                            done = true;
                            break;
                        }
                        bytes.position(bytes.position() + 8);
                    }
                }
                if (!done) {
                    if (lastIndexFile != null) {
                        lastIndexFile.decrementUsage();
                        lastIndexFile = null;
                    }

                    lastIndexFile = indexCache.append(appenderCycle, indexValue, nextSynchronous);

                }
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            appenderFile.bytes().positionAddr((positionAddr + 3) & ~3L);
            if (nextSynchronous)
                appenderFile.force();
//            appenderFile.decrementUsage();
        }

        @Override
        public ExcerptAppender toEnd() {
            super.toEnd();
            return this;
        }
    }

    class VanillaTailer extends AbstractVanillaExcerpt implements ExcerptTailer {
        @Override
        public ExcerptTailer toStart() {
            super.toStart();
            return this;
        }

        @Override
        public ExcerptTailer toEnd() {
            super.toEnd();
            return this;
        }

        @Override
        public void finish() {
            super.finish();
        }
    }
}
