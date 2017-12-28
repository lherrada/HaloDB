package amannaly;

import com.google.common.util.concurrent.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Set;

/**
 * @author Arjun Mannaly
 */
class CompactionJob {
    private static final Logger logger = LoggerFactory.getLogger(CompactionJob.class);

    private final Set<Integer> fileIdsToMerge;
    private final HaloDBFile mergedFile;
    private final HaloDBInternal db;

    // TODO: use 35 MB for tests.
    private final RateLimiter compactionRateLimiter;

    private int mergedFileOffset = 0;

    private long unFlushedData = 0;

    CompactionJob(Set<Integer> fileIdsToMerge, HaloDBFile mergedFile, HaloDBInternal db) {
        this.fileIdsToMerge = fileIdsToMerge;
        this.mergedFile = mergedFile;
        this.db = db;
        this.compactionRateLimiter = RateLimiter.create(db.options.compactionJobRate);
    }

    void run() {

        long start = System.currentTimeMillis();
        logger.info("About to start a merge run. Merging {} to {}", fileIdsToMerge, mergedFile.fileId);

        for (int fileId : fileIdsToMerge) {
            try {
                copyFreshRecordsToMergedFileUsingIndexFile(fileId);
            } catch (Exception e) {
                logger.error("Error while compacting " + fileId, e);
            }
        }

        long time = (System.currentTimeMillis()-start)/1000;
        logger.info("Completed merge run in {} seconds for file {}", time, mergedFile.fileId);
    }

    // TODO: group and move adjacent fresh records together for performance.
    private void copyFreshRecordsToMergedFileUsingIndexFile(int idOfFileToMerge) throws IOException {
        FileChannel readFrom =  db.getHaloDBFile(idOfFileToMerge).getChannel();

        IndexFile.IndexFileIterator iterator = db.getHaloDBFile(idOfFileToMerge).getIndexFile().newIterator();

        while (iterator.hasNext()) {
            IndexFileEntry indexFileEntry = iterator.next();
            byte[] key = indexFileEntry.getKey();
            long recordOffset = indexFileEntry.getRecordOffset();
            int recordSize = indexFileEntry.getRecordSize();

            RecordMetaDataForCache currentRecordMetaData = db.getKeyCache().get(key);

            if (isRecordFresh(indexFileEntry, currentRecordMetaData, idOfFileToMerge)) {
                compactionRateLimiter.acquire(recordSize);

                // fresh record copy to merged file.
                long transferred = readFrom.transferTo(recordOffset, recordSize, mergedFile.getChannel());
                assert transferred == recordSize;

                //TODO: for testing. remove.
                if (transferred != recordSize) {
                    logger.error("Had to transfer {} but only did {}", recordSize, transferred);
                }

                unFlushedData += transferred;

                if (db.options.flushDataSizeBytes != -1 && unFlushedData > db.options.flushDataSizeBytes) {
                    mergedFile.getChannel().force(false);
                    unFlushedData = 0;
                }

                IndexFileEntry newEntry = new IndexFileEntry(key, recordSize, mergedFileOffset, indexFileEntry.getSequenceNumber(), indexFileEntry.getFlags());
                mergedFile.getIndexFile().write(newEntry);

                int valueOffset = Utils.getValueOffset(mergedFileOffset, key);
                RecordMetaDataForCache newMetaData = new RecordMetaDataForCache(mergedFile.fileId, valueOffset, currentRecordMetaData.getValueSize(), indexFileEntry.getSequenceNumber());

                if (!indexFileEntry.isTombStone()) {
                    //TODO: if stale record, add the stale file map to remove later.
                    db.getKeyCache().replace(key, currentRecordMetaData, newMetaData);
                }

                mergedFileOffset += recordSize;
            }
        }
        db.deleteHaloDBFile(idOfFileToMerge);
    }

    private boolean isRecordFresh(IndexFileEntry entry, RecordMetaDataForCache metaData, int idOfFileToMerge) {
        return (metaData == null && entry.isTombStone()) ||
                (metaData != null
                        && metaData.getFileId() == idOfFileToMerge
                        && metaData.getValueOffset() == Utils.getValueOffset(entry.getRecordOffset(), entry.getKey())
                );
    }
}
