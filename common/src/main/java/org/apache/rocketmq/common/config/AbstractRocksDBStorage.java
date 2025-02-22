/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.common.config;

import com.google.common.collect.Maps;
import io.netty.buffer.PooledByteBufAllocator;
import java.nio.charset.StandardCharsets;
import org.apache.rocketmq.common.ThreadFactoryImpl;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.common.utils.ThreadUtils;
import org.apache.rocketmq.logging.org.slf4j.Logger;
import org.apache.rocketmq.logging.org.slf4j.LoggerFactory;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.CompactRangeOptions;
import org.rocksdb.CompactionOptions;
import org.rocksdb.CompressionType;
import org.rocksdb.DBOptions;
import org.rocksdb.Env;
import org.rocksdb.FlushOptions;
import org.rocksdb.LiveFileMetaData;
import org.rocksdb.Priority;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.Statistics;
import org.rocksdb.Status;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public abstract class AbstractRocksDBStorage {
    protected static final Logger LOGGER = LoggerFactory.getLogger(LoggerName.ROCKSDB_LOGGER_NAME);

    /**
     * Direct Jemalloc allocator
     */
    public static final PooledByteBufAllocator POOLED_ALLOCATOR = new PooledByteBufAllocator(true);

    public static final byte CTRL_0 = '\u0000';
    public static final byte CTRL_1 = '\u0001';
    public static final byte CTRL_2 = '\u0002';

    private static final String SPACE = " | ";

    protected final String dbPath;
    protected boolean readOnly;
    protected RocksDB db;
    protected DBOptions options;

    protected WriteOptions writeOptions;
    protected WriteOptions ableWalWriteOptions;

    protected ReadOptions readOptions;
    protected ReadOptions totalOrderReadOptions;

    protected CompactionOptions compactionOptions;
    protected CompactRangeOptions compactRangeOptions;

    protected ColumnFamilyHandle defaultCFHandle;
    protected final List<ColumnFamilyOptions> cfOptions = new ArrayList<>();
    protected final List<ColumnFamilyHandle> cfHandles = new ArrayList<>();

    protected volatile boolean loaded;
    protected CompressionType compressionType = CompressionType.LZ4_COMPRESSION;
    private volatile boolean closed;

    private final Semaphore reloadPermit = new Semaphore(1);
    private final ScheduledExecutorService reloadScheduler = ThreadUtils.newScheduledThreadPool(1, new ThreadFactoryImpl("RocksDBStorageReloadService_"));
    private final ThreadPoolExecutor manualCompactionThread = (ThreadPoolExecutor) ThreadUtils.newThreadPoolExecutor(
        1, 1, 1000 * 60, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(1),
        new ThreadFactoryImpl("RocksDBManualCompactionService_"),
        new ThreadPoolExecutor.DiscardOldestPolicy());

    static {
        RocksDB.loadLibrary();
    }

    public AbstractRocksDBStorage(String dbPath) {
        this.dbPath = dbPath;
    }

    protected void initOptions() {
        initWriteOptions();
        initAbleWalWriteOptions();
        initReadOptions();
        initTotalOrderReadOptions();
        initCompactRangeOptions();
        initCompactionOptions();
    }

    /**
     * Write options for <a href="https://github.com/facebook/rocksdb/wiki/Atomic-flush">Atomic Flush</a>
     */
    protected void initWriteOptions() {
        this.writeOptions = new WriteOptions();
        this.writeOptions.setSync(false);
        this.writeOptions.setDisableWAL(true);
        this.writeOptions.setNoSlowdown(true);
    }

    protected void initAbleWalWriteOptions() {
        this.ableWalWriteOptions = new WriteOptions();
        this.ableWalWriteOptions.setSync(false);
        this.ableWalWriteOptions.setDisableWAL(false);
        this.ableWalWriteOptions.setNoSlowdown(true);
    }

    protected void initReadOptions() {
        this.readOptions = new ReadOptions();
        this.readOptions.setPrefixSameAsStart(true);
        this.readOptions.setTotalOrderSeek(false);
        this.readOptions.setTailing(false);
    }

    protected void initTotalOrderReadOptions() {
        this.totalOrderReadOptions = new ReadOptions();
        this.totalOrderReadOptions.setPrefixSameAsStart(false);
        this.totalOrderReadOptions.setTotalOrderSeek(true);
        this.totalOrderReadOptions.setTailing(false);
    }

    protected void initCompactRangeOptions() {
        this.compactRangeOptions = new CompactRangeOptions();
        this.compactRangeOptions.setBottommostLevelCompaction(CompactRangeOptions.BottommostLevelCompaction.kForce);
        this.compactRangeOptions.setAllowWriteStall(true);
        this.compactRangeOptions.setExclusiveManualCompaction(false);
        this.compactRangeOptions.setChangeLevel(true);
        this.compactRangeOptions.setTargetLevel(-1);
        this.compactRangeOptions.setMaxSubcompactions(4);
    }

    protected void initCompactionOptions() {
        this.compactionOptions = new CompactionOptions();
        this.compactionOptions.setCompression(compressionType);
        this.compactionOptions.setMaxSubcompactions(4);
        this.compactionOptions.setOutputFileSizeLimit(4 * 1024 * 1024 * 1024L);
    }

    public boolean hold() {
        if (!this.loaded || this.db == null || this.closed) {
            LOGGER.error("hold rocksdb Failed. {}", this.dbPath);
            return false;
        } else {
            return true;
        }
    }

    public void release() {
    }

    protected void put(ColumnFamilyHandle cfHandle, WriteOptions writeOptions,
        final byte[] keyBytes, final int keyLen,
        final byte[] valueBytes, final int valueLen) throws RocksDBException {
        if (!hold()) {
            throw new IllegalStateException("rocksDB:" + this + " is not ready");
        }
        try {
            this.db.put(cfHandle, writeOptions, keyBytes, 0, keyLen, valueBytes, 0, valueLen);
        } catch (RocksDBException e) {
            scheduleReloadRocksdb(e);
            LOGGER.error("put Failed. {}, {}", this.dbPath, getStatusError(e));
            throw e;
        } finally {
            release();
        }
    }

    protected void put(ColumnFamilyHandle cfHandle, WriteOptions writeOptions,
        final ByteBuffer keyBB, final ByteBuffer valueBB) throws RocksDBException {
        if (!hold()) {
            throw new IllegalStateException("rocksDB:" + this + " is not ready");
        }
        try {
            this.db.put(cfHandle, writeOptions, keyBB, valueBB);
        } catch (RocksDBException e) {
            scheduleReloadRocksdb(e);
            LOGGER.error("put Failed. {}, {}", this.dbPath, getStatusError(e));
            throw e;
        } finally {
            release();
        }
    }

    protected void batchPut(WriteOptions writeOptions, final WriteBatch batch) throws RocksDBException {
        try {
            this.db.write(writeOptions, batch);
        } catch (RocksDBException e) {
            scheduleReloadRocksdb(e);
            LOGGER.error("batchPut Failed. {}, {}", this.dbPath, getStatusError(e));
            throw e;
        } finally {
            batch.clear();
        }
    }

    protected byte[] get(ColumnFamilyHandle cfHandle, ReadOptions readOptions, byte[] keyBytes) throws RocksDBException {
        if (!hold()) {
            throw new IllegalStateException("rocksDB:" + this + " is not ready");
        }
        try {
            return this.db.get(cfHandle, readOptions, keyBytes);
        } catch (RocksDBException e) {
            LOGGER.error("get Failed. {}, {}", this.dbPath, getStatusError(e));
            throw e;
        } finally {
            release();
        }
    }

    protected int get(ColumnFamilyHandle cfHandle, ReadOptions readOptions, final ByteBuffer keyBB,
        final ByteBuffer valueBB) throws RocksDBException {
        if (!hold()) {
            throw new IllegalStateException("rocksDB:" + this + " is not ready");
        }
        try {
            return this.db.get(cfHandle, readOptions, keyBB, valueBB);
        } catch (RocksDBException e) {
            LOGGER.error("get Failed. {}, {}", this.dbPath, getStatusError(e));
            throw e;
        } finally {
            release();
        }
    }

    protected List<byte[]> multiGet(final ReadOptions readOptions,
        final List<ColumnFamilyHandle> columnFamilyHandleList,
        final List<byte[]> keys) throws RocksDBException {
        if (!hold()) {
            throw new IllegalStateException("rocksDB:" + this + " is not ready");
        }
        try {
            return this.db.multiGetAsList(readOptions, columnFamilyHandleList, keys);
        } catch (RocksDBException e) {
            LOGGER.error("multiGet Failed. {}, {}", this.dbPath, getStatusError(e));
            throw e;
        } finally {
            release();
        }
    }

    protected void delete(ColumnFamilyHandle cfHandle, WriteOptions writeOptions,
        byte[] keyBytes) throws RocksDBException {
        if (!hold()) {
            throw new IllegalStateException("rocksDB:" + this + " is not ready");
        }
        try {
            this.db.delete(cfHandle, writeOptions, keyBytes);
        } catch (RocksDBException e) {
            LOGGER.error("delete Failed. {}, {}", this.dbPath, getStatusError(e));
            throw e;
        } finally {
            release();
        }
    }

    protected void delete(ColumnFamilyHandle cfHandle, WriteOptions writeOptions, ByteBuffer keyBB)
        throws RocksDBException {
        if (!hold()) {
            throw new IllegalStateException("rocksDB:" + this + " is not ready");
        }
        try {
            this.db.delete(cfHandle, writeOptions, keyBB);
        } catch (RocksDBException e) {
            LOGGER.error("delete Failed. {}, {}", this.dbPath, getStatusError(e));
            throw e;
        } finally {
            release();
        }
    }

    protected void rangeDelete(ColumnFamilyHandle cfHandle, WriteOptions writeOptions, final byte[] startKey,
        final byte[] endKey) throws RocksDBException {
        if (!hold()) {
            throw new IllegalStateException("rocksDB:" + this + " is not ready");
        }
        try {
            this.db.deleteRange(cfHandle, writeOptions, startKey, endKey);
        } catch (RocksDBException e) {
            scheduleReloadRocksdb(e);
            LOGGER.error("rangeDelete Failed. {}, {}", this.dbPath, getStatusError(e));
            throw e;
        } finally {
            release();
        }
    }

    protected void manualCompactionDefaultCfRange(CompactRangeOptions compactRangeOptions) {
        if (!hold()) {
            return;
        }
        long s1 = System.currentTimeMillis();
        boolean result = true;
        try {
            LOGGER.info("manualCompaction Start. {}", this.dbPath);
            this.db.compactRange(this.defaultCFHandle, null, null, compactRangeOptions);
        } catch (RocksDBException e) {
            result = false;
            scheduleReloadRocksdb(e);
            LOGGER.error("manualCompaction Failed. {}, {}", this.dbPath, getStatusError(e));
        } finally {
            release();
            LOGGER.info("manualCompaction End. {}, rt: {}(ms), result: {}", this.dbPath, System.currentTimeMillis() - s1, result);
        }
    }

    protected void manualCompaction(long minPhyOffset, final CompactRangeOptions compactRangeOptions) {
        this.manualCompactionThread.submit(new Runnable() {
            @Override
            public void run() {
                manualCompactionDefaultCfRange(compactRangeOptions);
            }
        });
    }

    protected void open(final List<ColumnFamilyDescriptor> cfDescriptors) throws RocksDBException {
        this.cfHandles.clear();
        if (this.readOnly) {
            this.db = RocksDB.openReadOnly(this.options, this.dbPath, cfDescriptors, cfHandles);
        } else {
            this.db = RocksDB.open(this.options, this.dbPath, cfDescriptors, cfHandles);
        }
        assert cfDescriptors.size() == cfHandles.size();
        try (Env env = this.db.getEnv()) {
            env.setBackgroundThreads(8, Priority.LOW);
        }
        if (this.db == null) {
            throw new RocksDBException("open rocksdb null");
        }
    }

    protected abstract boolean postLoad();

    public synchronized boolean start() {
        if (this.loaded) {
            return true;
        }
        if (postLoad()) {
            this.loaded = true;
            LOGGER.info("start OK. {}", this.dbPath);
            this.closed = false;
            return true;
        } else {
            return false;
        }
    }

    /**
     * Close column family handles except the default column family
     */
    protected abstract void preShutdown();

    public synchronized boolean shutdown() {
        try {
            if (!this.loaded) {
                return true;
            }

            final FlushOptions flushOptions = new FlushOptions();
            flushOptions.setWaitForFlush(true);
            try {
                flush(flushOptions);
            } finally {
                flushOptions.close();
            }
            this.db.cancelAllBackgroundWork(true);
            this.db.pauseBackgroundWork();
            //The close order matters.
            //1. close column family handles
            preShutdown();

            this.defaultCFHandle.close();

            //2. close column family options.
            for (final ColumnFamilyOptions opt : this.cfOptions) {
                opt.close();
            }
            //3. close options
            if (this.writeOptions != null) {
                this.writeOptions.close();
            }
            if (this.ableWalWriteOptions != null) {
                this.ableWalWriteOptions.close();
            }
            if (this.readOptions != null) {
                this.readOptions.close();
            }
            if (this.totalOrderReadOptions != null) {
                this.totalOrderReadOptions.close();
            }
            //4. close db.
            if (db != null && !this.readOnly) {
                this.db.syncWal();
            }
            if (db != null) {
                this.db.closeE();
            }
            // Close DBOptions after RocksDB instance is closed.
            if (this.options != null) {
                this.options.close();
            }
            //5. help gc.
            this.cfOptions.clear();
            this.db = null;
            this.readOptions = null;
            this.totalOrderReadOptions = null;
            this.writeOptions = null;
            this.ableWalWriteOptions = null;
            this.options = null;

            this.loaded = false;
            LOGGER.info("shutdown OK. {}", this.dbPath);
        } catch (Exception e) {
            LOGGER.error("shutdown Failed. {}", this.dbPath, e);
            return false;
        }
        return true;
    }

    public void flush(final FlushOptions flushOptions) throws RocksDBException {
        flush(flushOptions, this.cfHandles);
    }

    public void flush(final FlushOptions flushOptions, List<ColumnFamilyHandle> columnFamilyHandles) throws RocksDBException {
        if (!this.loaded || this.readOnly || closed) {
            return;
        }

        try {
            if (db != null) {
                // For atomic-flush, we have to explicitly specify column family handles
                // See https://github.com/rust-rocksdb/rust-rocksdb/pull/793
                // and https://github.com/facebook/rocksdb/blob/8ad4c7efc48d301f5e85467105d7019a49984dc8/include/rocksdb/db.h#L1667
                this.db.flush(flushOptions, columnFamilyHandles);
            }
        } catch (RocksDBException e) {
            scheduleReloadRocksdb(e);
            LOGGER.error("flush Failed. {}, {}", this.dbPath, getStatusError(e));
            throw e;
        }
    }

    public void flushWAL() throws RocksDBException {
        this.db.flushWal(true);
    }

    public Statistics getStatistics() {
        return this.options.statistics();
    }

    public ColumnFamilyHandle getDefaultCFHandle() {
        return defaultCFHandle;
    }

    public List<LiveFileMetaData> getCompactionStatus() {
        if (!hold()) {
            return null;
        }
        try {
            return this.db.getLiveFilesMetaData();
        } finally {
            release();
        }
    }

    private void scheduleReloadRocksdb(RocksDBException rocksDBException) {
        if (rocksDBException == null || rocksDBException.getStatus() == null) {
            return;
        }
        Status status = rocksDBException.getStatus();
        Status.Code code = status.getCode();
        // Status.Code.Incomplete == code
        if (Status.Code.Aborted == code || Status.Code.Corruption == code || Status.Code.Undefined == code) {
            LOGGER.error("scheduleReloadRocksdb. {}, {}", this.dbPath, getStatusError(rocksDBException));
            scheduleReloadRocksdb0();
        }
    }

    private void scheduleReloadRocksdb0() {
        if (!this.reloadPermit.tryAcquire()) {
            return;
        }
        this.closed = true;
        this.reloadScheduler.schedule(new Runnable() {
            @Override
            public void run() {
                boolean result = true;
                try {
                    reloadRocksdb();
                } catch (Exception e) {
                    result = false;
                } finally {
                    reloadPermit.release();
                }
                // try to reload rocksdb next time
                if (!result) {
                    LOGGER.info("reload rocksdb Retry. {}", dbPath);
                    scheduleReloadRocksdb0();
                }
            }
        }, 10, TimeUnit.SECONDS);
    }

    private void reloadRocksdb() throws Exception {
        LOGGER.info("reload rocksdb Start. {}", this.dbPath);
        if (!shutdown() || !start()) {
            LOGGER.error("reload rocksdb Failed. {}", dbPath);
            throw new Exception("reload rocksdb Error");
        }
        LOGGER.info("reload rocksdb OK. {}", this.dbPath);
    }

    private String getStatusError(RocksDBException e) {
        if (e == null || e.getStatus() == null) {
            return "null";
        }
        Status status = e.getStatus();
        StringBuilder sb = new StringBuilder(64);
        sb.append("code: ");
        if (status.getCode() != null) {
            sb.append(status.getCode().name());
        } else {
            sb.append("null");
        }
        sb.append(", ").append("subCode: ");
        if (status.getSubCode() != null) {
            sb.append(status.getSubCode().name());
        } else {
            sb.append("null");
        }
        sb.append(", ").append("state: ").append(status.getState());
        return sb.toString();
    }

    public void statRocksdb(Logger logger) {
        try {

            List<LiveFileMetaData> liveFileMetaDataList = this.getCompactionStatus();
            if (liveFileMetaDataList == null || liveFileMetaDataList.isEmpty()) {
                return;
            }
            Map<Integer, StringBuilder> map = Maps.newHashMap();
            for (LiveFileMetaData metaData : liveFileMetaDataList) {
                StringBuilder sb = map.computeIfAbsent(metaData.level(), k -> new StringBuilder(256));
                sb.append(new String(metaData.columnFamilyName(), StandardCharsets.UTF_8)).append(SPACE).
                    append(metaData.fileName()).append(SPACE).
                    append("s: ").append(metaData.size()).append(SPACE).
                    append("a: ").append(metaData.numEntries()).append(SPACE).
                    append("r: ").append(metaData.numReadsSampled()).append(SPACE).
                    append("d: ").append(metaData.numDeletions()).append(SPACE).
                    append(metaData.beingCompacted()).append("\n");
            }

            map.forEach((key, value) -> logger.info("level: {}\n{}", key, value.toString()));

            String blockCacheMemUsage = this.db.getProperty("rocksdb.block-cache-usage");
            String indexesAndFilterBlockMemUsage = this.db.getProperty("rocksdb.estimate-table-readers-mem");
            String memTableMemUsage = this.db.getProperty("rocksdb.cur-size-all-mem-tables");
            String blocksPinnedByIteratorMemUsage = this.db.getProperty("rocksdb.block-cache-pinned-usage");
            logger.info("MemUsage. blockCache: {}, indexesAndFilterBlock: {}, MemTable: {}, blocksPinnedByIterator: {}",
                blockCacheMemUsage, indexesAndFilterBlockMemUsage, memTableMemUsage, blocksPinnedByIteratorMemUsage);
        } catch (Exception ignored) {
        }
    }
}
