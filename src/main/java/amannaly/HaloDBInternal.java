package amannaly;

import org.HdrHistogram.Histogram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * @author Arjun Mannaly
 */
class HaloDBInternal {

    private static final Logger logger = LoggerFactory.getLogger(HaloDBInternal.class);

    private static final Histogram writeLatencyHistogram = new Histogram(TimeUnit.SECONDS.toNanos(5), 3);

    private File dbDirectory;

    private volatile HaloDBFile currentWriteFile;

    private TombstoneFile tombstoneFile;

    private Map<Integer, HaloDBFile> readFileMap = new ConcurrentHashMap<>();

    HaloDBOptions options;

    private KeyCache keyCache;

    private final Map<Integer, Integer> staleDataPerFileMap = new ConcurrentHashMap<>();

    private CompactionManager compactionManager;

    private final Set<Integer> filesToMerge = new ConcurrentSkipListSet<>();

    private HaloDBInternal() {}

    static HaloDBInternal open(File directory, HaloDBOptions options) throws IOException {
        HaloDBInternal result = new HaloDBInternal();

        FileUtils.createDirectory(directory);

        result.dbDirectory = directory;

        result.keyCache = new OffHeapCache(options.numberOfRecords);
        result.buildReadFileMap();
        result.buildKeyCache(options);

        result.options = options;
        result.currentWriteFile = result.createHaloDBFile();

        result.compactionManager = new CompactionManager(result, options.mergeJobIntervalInSeconds);
        result.compactionManager.start();

        result.tombstoneFile = TombstoneFile.create(directory, options);

        logger.info("Opened HaloDB {}", directory.getName());
        logger.info("isMergeDisabled - {}", options.isMergeDisabled);
        logger.info("maxFileSize - {}", options.maxFileSize);
        logger.info("mergeJobIntervalInSeconds - {}", options.mergeJobIntervalInSeconds);
        logger.info("mergeThresholdPerFile - {}", options.mergeThresholdPerFile);
        logger.info("mergeThresholdFileNumber - {}", options.mergeThresholdFileNumber);

        return result;
    }

    void close() throws IOException {

        compactionManager.stopThread();

        //TODO: make this optional as it will take time.
        keyCache.close();

        for (HaloDBFile file : readFileMap.values()) {
            file.close();
        }

        readFileMap.clear();

        if (currentWriteFile != null) {
            currentWriteFile.close();
        }
        if (tombstoneFile != null) {
            tombstoneFile.close();
        }
    }

    void put(byte[] key, byte[] value) throws IOException {
        if (key.length > Byte.MAX_VALUE) {
            throw new IllegalArgumentException("key length cannot exceed " + Byte.MAX_VALUE);
        }

        Record record = new Record(key, value);
        record.setSequenceNumber(getNextSequenceNumber());
        RecordMetaDataForCache entry = writeRecordToFile(record);

        updateStaleDataMap(key);

        keyCache.put(key, entry);
    }

    byte[] get(byte[] key) throws IOException {
        RecordMetaDataForCache metaData = keyCache.get(key);
        if (metaData == null) {
            return null;
        }

        HaloDBFile readFile = readFileMap.get(metaData.getFileId());
        if (readFile == null) {
            logger.debug("File {} not present. Merge job would have deleted it. Retrying ...", metaData.getFileId());
            return get(key);
        }
        //TODO: there is a race condition. what if the file is being deleted or was deleted.
        return readFile.readFromFile(metaData.getValueOffset(), metaData.getValueSize());
    }

    void delete(byte[] key) throws IOException {
        if (keyCache.remove(key)) {
            TombstoneEntry entry = new TombstoneEntry(key, getNextSequenceNumber());
            rollOverCurrentTombstoneFile(entry);
            tombstoneFile.write(entry);
            updateStaleDataMap(key);
        }
    }

    long size() {
        return keyCache.size();
    }

    private RecordMetaDataForCache writeRecordToFile(Record record) throws IOException {
        rollOverCurrentWriteFile(record);
        return currentWriteFile.writeRecord(record);
    }

    private void rollOverCurrentWriteFile(Record record) throws IOException {
        int size = record.getKey().length + record.getValue().length + Record.Header.HEADER_SIZE;

        if (currentWriteFile == null ||  currentWriteFile.getWriteOffset() + size > options.maxFileSize) {
            currentWriteFile = createHaloDBFile();
        }
    }

    private void rollOverCurrentTombstoneFile(TombstoneEntry entry) throws IOException {
        int size = entry.getKey().length + TombstoneEntry.TOMBSTONE_ENTRY_HEADER_SIZE;

        if (tombstoneFile == null ||  tombstoneFile.getWriteOffset() + size > options.maxFileSize) {
            if (tombstoneFile != null) {
                tombstoneFile.close();
            }

            tombstoneFile = TombstoneFile.create(dbDirectory, options);
        }
    }

    private void updateStaleDataMap(byte[] key) {
        RecordMetaDataForCache recordMetaData = keyCache.get(key);
        if (recordMetaData != null) {
            int stale = recordMetaData.getValueSize() + key.length + Record.Header.HEADER_SIZE;
            long currentStaleSize = staleDataPerFileMap.merge(recordMetaData.getFileId(), stale, (oldValue, newValue) -> oldValue + newValue);

            HaloDBFile file = readFileMap.get(recordMetaData.getFileId());

            if (currentStaleSize >= file.getSize() * options.mergeThresholdPerFile) {
                filesToMerge.add(recordMetaData.getFileId());
                staleDataPerFileMap.remove(recordMetaData.getFileId());
            }
        }
    }

    boolean areThereEnoughFilesToMerge() {
        //TODO: size() is not a constant time operation.
        //TODO: probably okay since number of files are usually
        //TODO: not too many.
        return filesToMerge.size() >= options.mergeThresholdFileNumber;
    }

    Set<Integer> getFilesToMerge() {
        Set<Integer> fileIds = new HashSet<>();
        Iterator<Integer> it = filesToMerge.iterator();

        //TODO: there was a bug where currentWriteFile was being compacted.
        //TODO: need to write a unit test for this.
        while (fileIds.size() < options.mergeThresholdFileNumber && it.hasNext()) {
            Integer next = it.next();
            if (currentWriteFile.fileId != next)
                fileIds.add(next);
        }

        return fileIds;
    }

    void submitMergedFiles(Set<Integer> mergedFiles) {
        filesToMerge.removeAll(mergedFiles);
    }


    KeyCache getKeyCache() {
        return keyCache;
    }

    HaloDBFile createHaloDBFile() throws IOException {
        HaloDBFile file = HaloDBFile.create(dbDirectory, Utils.generateFileId(), options);
        readFileMap.put(file.fileId, file);
        return file;
    }

    private List<HaloDBFile> getHaloDBDataFilesForReading() throws IOException {
        File[] files = dbDirectory.listFiles(new FileFilter() {
            @Override
            public boolean accept(File file) {
                return Constants.DATA_FILE_PATTERN.matcher(file.getName()).matches();
            }
        });

        List<HaloDBFile> result = new ArrayList<>();
        for (File f : files) {
            result.add(HaloDBFile.openForReading(dbDirectory, f, options));
        }

        return result;
    }

    private void buildReadFileMap() throws IOException {
        getHaloDBDataFilesForReading().forEach(f -> readFileMap.put(f.fileId, f));
    }

    //TODO: probably don't expose this?
    //TODO: current we need this for unit testing.
    Set<Integer> listDataFileIds() {
        return new HashSet<>(readFileMap.keySet());
    }

    private List<Integer> listIndexFiles() {

        File[] files = dbDirectory.listFiles(new FileFilter() {
            @Override
            public boolean accept(File file) {
                return Constants.INDEX_FILE_PATTERN.matcher(file.getName()).matches();
            }
        });

        // sort in ascending order. we want the earliest index files to be processed first.

        return
        Arrays.stream(files)
            .sorted((f1, f2) -> f1.getName().compareTo(f2.getName()))
            .map(file -> Constants.INDEX_FILE_PATTERN.matcher(file.getName()))
            .map(matcher -> {
                matcher.find();
                return matcher.group(1);
            })
            .map(Integer::valueOf)
            .collect(Collectors.toList());
    }

    private File[] listTombstoneFiles() {
        File[] files = dbDirectory.listFiles(new FileFilter() {
            @Override
            public boolean accept(File file) {
                return Constants.TOMBSTONE_FILE_PATTERN.matcher(file.getName()).matches();
            }
        });

        return files;
    }


    //TODO: this probably needs to be moved to another location.
    private void buildKeyCache(HaloDBOptions options) throws IOException {
        List<Integer> indexFiles = listIndexFiles();

        logger.info("About to scan {} index files to construct cache ...", indexFiles.size());

        long start = System.currentTimeMillis();

        for (int fileId : indexFiles) {
            IndexFile indexFile = new IndexFile(fileId, dbDirectory, options);
            indexFile.open();
            IndexFile.IndexFileIterator iterator = indexFile.newIterator();

            int count = 0, inserted = 0;
            while (iterator.hasNext()) {
                IndexFileEntry indexFileEntry = iterator.next();
                byte[] key = indexFileEntry.getKey();
                int recordOffset = indexFileEntry.getRecordOffset();
                int recordSize = indexFileEntry.getRecordSize();
                long sequenceNumber = indexFileEntry.getSequenceNumber();
                int valueOffset = Utils.getValueOffset(recordOffset, key);
                int valueSize = recordSize - (Record.Header.HEADER_SIZE + key.length);
                count++;

                RecordMetaDataForCache existing = keyCache.get(key);

                if (existing == null) {
                    keyCache.put(key, new RecordMetaDataForCache(fileId, valueOffset, valueSize, sequenceNumber));
                    inserted++;
                }
                else if (existing.getSequenceNumber() < sequenceNumber) {
                    keyCache.put(key, new RecordMetaDataForCache(fileId, valueOffset, valueSize, sequenceNumber));
                    int staleDataSize = existing.getValueSize() + key.length + Record.Header.HEADER_SIZE;
                    staleDataPerFileMap.merge(existing.getFileId(), staleDataSize, (oldValue, newValue) -> oldValue + newValue);
                    inserted++;
                }
            }
            logger.debug("Completed scanning index file {}. Found {} records, inserted {} records", fileId, count, inserted);
            indexFile.close();
        }

        File[] tombStoneFiles = listTombstoneFiles();
        logger.info("About to scan {} tombstone files ...", tombStoneFiles.length);
        for (File file : tombStoneFiles) {
            TombstoneFile tombstoneFile = new TombstoneFile(file, options);
            tombstoneFile.open();
            TombstoneFile.TombstoneFileIterator iterator = tombstoneFile.newIterator();

            int count = 0, deleted = 0;
            while (iterator.hasNext()) {
                TombstoneEntry entry = iterator.next();
                byte[] key = entry.getKey();
                long sequenceNumber = entry.getSequenceNumber();
                count++;

                RecordMetaDataForCache existing = keyCache.get(key);
                if (existing != null && existing.getSequenceNumber() < sequenceNumber) {
                    keyCache.remove(key);
                    deleted++;
                }
            }
            logger.debug("Completed scanning tombstone file {}. Found {} tombstones, deleted {} records", file.getName(), count, deleted);
            tombstoneFile.close();
        }

        long time = (System.currentTimeMillis() - start)/1000;

        logger.info("Completed scanning all key files in {}.\n", time);
    }

    HaloDBFile getHaloDBFile(int fileId) {
        return readFileMap.get(fileId);
    }

    void deleteHaloDBFile(int fileId) throws IOException {
        HaloDBFile file = readFileMap.get(fileId);

        if (file != null) {
            readFileMap.remove(fileId);
            file.delete();
        }

        staleDataPerFileMap.remove(fileId);
    }

    public void printStaleFileStatus() {
        System.out.println("********************************");
        staleDataPerFileMap.forEach((key, value) -> {
            System.out.printf("%d.data - %f\n", key, (value * 100.0) / options.maxFileSize);
        });
        System.out.println("********************************\n\n");
    }

    public static void recordWriteLatency(long time) {
        writeLatencyHistogram.recordValue(time);
    }

    public static void printWriteLatencies() {
        System.out.printf("Write latency mean %f\n", writeLatencyHistogram.getMean());
        System.out.printf("Write latency max %d\n", writeLatencyHistogram.getMaxValue());
        System.out.printf("Write latency 99 %d\n", writeLatencyHistogram.getValueAtPercentile(99.0));
        System.out.printf("Write latency total count %d\n", writeLatencyHistogram.getTotalCount());

    }

    public void printKeyCachePutLatencies() {
        keyCache.printPutLatency();
    }

    public boolean isRecordFresh(Record record) {
        RecordMetaDataForCache metaData = keyCache.get(record.getKey());

        return
            metaData != null
            &&
            metaData.getFileId() == record.getRecordMetaData().getFileId()
            &&
            metaData.getValueOffset() == record.getRecordMetaData().getValueOffset();

    }



    String stats() {
        return keyCache.stats().toString();
    }

    boolean isMergeComplete() {
        return filesToMerge.isEmpty();
    }

    long getNextSequenceNumber() {
        return System.nanoTime();
    }
}
