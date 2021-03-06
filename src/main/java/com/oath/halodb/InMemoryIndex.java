/*
 * Copyright 2018, Oath Inc
 * Licensed under the terms of the Apache License 2.0. Please refer to accompanying LICENSE file for terms.
 */

package com.oath.halodb;

import com.google.common.primitives.Ints;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Hash table stored in native memory, outside Java heap.
 *
 * @author Arjun Mannaly
 */
class InMemoryIndex {
    private static final Logger logger = LoggerFactory.getLogger(InMemoryIndex.class);

    private final OffHeapHashTable<RecordMetaDataForCache> offHeapHashTable;

    private final int noOfSegments;
    private final int maxSizeOfEachSegment;

    InMemoryIndex(int numberOfKeys, boolean useMemoryPool, int fixedKeySize, int memoryPoolChunkSize) {
        noOfSegments = Ints.checkedCast(Utils.roundUpToPowerOf2(Runtime.getRuntime().availableProcessors() * 2));
        maxSizeOfEachSegment = Ints.checkedCast(Utils.roundUpToPowerOf2(numberOfKeys / noOfSegments));

        long start = System.currentTimeMillis();
        OffHeapHashTableBuilder<RecordMetaDataForCache> builder =
            OffHeapHashTableBuilder.<RecordMetaDataForCache>newBuilder()
                .valueSerializer(new RecordMetaDataSerializer())
                .capacity(Long.MAX_VALUE)
                .segmentCount(noOfSegments)
                .hashTableSize(maxSizeOfEachSegment)
                .fixedValueSize(RecordMetaDataForCache.SERIALIZED_SIZE)
                .loadFactor(1)
                .throwOOME(true);

        if (useMemoryPool) {
            builder.useMemoryPool(true).fixedKeySize(fixedKeySize).memoryPoolChunkSize(memoryPoolChunkSize);
        }

        this.offHeapHashTable = builder.build();

        logger.info("Initialized the cache in {}", (System.currentTimeMillis() - start));
    }

    boolean put(byte[] key, RecordMetaDataForCache metaData) {
        offHeapHashTable.put(key, metaData);
        return true;
    }

    boolean remove(byte[] key) {
        return offHeapHashTable.remove(key);
    }

    boolean replace(byte[] key, RecordMetaDataForCache oldValue, RecordMetaDataForCache newValue) {
        return offHeapHashTable.addOrReplace(key, oldValue, newValue);
    }

    RecordMetaDataForCache get(byte[] key) {
        return offHeapHashTable.get(key);
    }

    boolean containsKey(byte[] key) {
        return offHeapHashTable.containsKey(key);
    }

    void close() {
        try {
            offHeapHashTable.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    long size() {
        return offHeapHashTable.size();
    }

    public OffHeapHashTableStats stats() {
        return offHeapHashTable.stats();
    }

    void resetStats() {
        offHeapHashTable.resetStatistics();
    }

    int getNoOfSegments() {
        return noOfSegments;
    }

    int getMaxSizeOfEachSegment() {
        return maxSizeOfEachSegment;
    }
}
