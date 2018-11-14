/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.job.process.autodetect.output;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.DocWriteResponse;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.support.ThreadedActionListener;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.action.update.UpdateAction;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.action.update.UpdateResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.xpack.core.ml.MachineLearningField;
import org.elasticsearch.xpack.core.ml.job.config.Job;
import org.elasticsearch.xpack.core.ml.job.messages.Messages;
import org.elasticsearch.xpack.core.ml.job.persistence.AnomalyDetectorsIndex;
import org.elasticsearch.xpack.core.ml.job.persistence.ElasticsearchMappings;
import org.elasticsearch.xpack.core.ml.job.process.autodetect.output.FlushAcknowledgement;
import org.elasticsearch.xpack.core.ml.job.process.autodetect.state.ModelSizeStats;
import org.elasticsearch.xpack.core.ml.job.process.autodetect.state.ModelSnapshot;
import org.elasticsearch.xpack.core.ml.job.process.autodetect.state.Quantiles;
import org.elasticsearch.xpack.core.ml.job.results.AnomalyRecord;
import org.elasticsearch.xpack.core.ml.job.results.Bucket;
import org.elasticsearch.xpack.core.ml.job.results.CategoryDefinition;
import org.elasticsearch.xpack.core.ml.job.results.Forecast;
import org.elasticsearch.xpack.core.ml.job.results.ForecastRequestStats;
import org.elasticsearch.xpack.core.ml.job.results.Influencer;
import org.elasticsearch.xpack.core.ml.job.results.ModelPlot;
import org.elasticsearch.xpack.ml.MachineLearning;
import org.elasticsearch.xpack.ml.job.persistence.JobResultsPersister;
import org.elasticsearch.xpack.ml.job.process.autodetect.AutodetectProcess;
import org.elasticsearch.xpack.ml.job.process.normalizer.Renormalizer;
import org.elasticsearch.xpack.ml.job.results.AutodetectResult;
import org.elasticsearch.xpack.ml.notifications.Auditor;

import java.time.Duration;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.elasticsearch.xpack.core.ClientHelper.ML_ORIGIN;
import static org.elasticsearch.xpack.core.ClientHelper.executeAsyncWithOrigin;

/**
 * A runnable class that reads the autodetect process output in the
 * {@link #process(AutodetectProcess)} method and persists parsed
 * results via the {@linkplain JobResultsPersister} passed in the constructor.
 * <p>
 * Has methods to register and remove alert observers.
 * Also has a method to wait for a flush to be complete.
 *
 * Buckets are the written last after records, influencers etc
 * when the end of bucket is reached. Therefore results aren't persisted
 * until the bucket is read, this means that interim results for all
 * result types can be safely deleted when the bucket is read and before
 * the new results are updated. This is specifically for the case where
 * a flush command is issued repeatedly in the same bucket to generate
 * interim results and the old interim results have to be cleared out
 * before the new ones are written.
 */
public class AutoDetectResultProcessor {

    private static final Logger LOGGER = LogManager.getLogger(AutoDetectResultProcessor.class);

    private final Client client;
    private final Auditor auditor;
    private final String jobId;
    private final Renormalizer renormalizer;
    private final JobResultsPersister persister;

    final CountDownLatch completionLatch = new CountDownLatch(1);
    final Semaphore updateModelSnapshotSemaphore = new Semaphore(1);
    volatile CountDownLatch onCloseActionsLatch;
    private final FlushListener flushListener;
    private volatile boolean processKilled;
    private volatile boolean failed;
    private int bucketCount; // only used from the process() thread, so doesn't need to be volatile

    /**
     * New model size stats are read as the process is running
     */
    private volatile ModelSizeStats latestModelSizeStats;

    public AutoDetectResultProcessor(Client client, Auditor auditor, String jobId, Renormalizer renormalizer,
                                     JobResultsPersister persister, ModelSizeStats latestModelSizeStats) {
        this(client, auditor, jobId, renormalizer, persister, latestModelSizeStats, new FlushListener());
    }

    AutoDetectResultProcessor(Client client, Auditor auditor, String jobId, Renormalizer renormalizer,
                              JobResultsPersister persister, ModelSizeStats latestModelSizeStats, FlushListener flushListener) {
        this.client = Objects.requireNonNull(client);
        this.auditor = Objects.requireNonNull(auditor);
        this.jobId = Objects.requireNonNull(jobId);
        this.renormalizer = Objects.requireNonNull(renormalizer);
        this.persister = Objects.requireNonNull(persister);
        this.flushListener = Objects.requireNonNull(flushListener);
        this.latestModelSizeStats = Objects.requireNonNull(latestModelSizeStats);
    }

    public void process(AutodetectProcess process) {
        Context context = new Context(jobId, persister.bulkPersisterBuilder(jobId));

        // If a function call in this throws for some reason we don't want it
        // to kill the results reader thread as autodetect will be blocked
        // trying to write its output.
        try {
            bucketCount = 0;
            Iterator<AutodetectResult> iterator = process.readAutodetectResults();
            while (iterator.hasNext()) {
                try {
                    AutodetectResult result = iterator.next();
                    processResult(context, result);
                    if (result.getBucket() != null) {
                        LOGGER.trace("[{}] Bucket number {} parsed from output", jobId, bucketCount);
                    }
                } catch (Exception e) {
                    if (processKilled) {
                        throw e;
                    }
                    if (process.isProcessAliveAfterWaiting() == false) {
                        throw e;
                    }
                    LOGGER.warn(new ParameterizedMessage("[{}] Error processing autodetect result", jobId), e);
                }
            }

            try {
                if (processKilled == false) {
                    context.bulkResultsPersister.executeRequest();
                }
            } catch (Exception e) {
                LOGGER.warn(new ParameterizedMessage("[{}] Error persisting autodetect results", jobId), e);
            }
            if (processKilled == false) {
                try {
                    onAutodetectClose();
                } catch (Exception e) {
                    if (onCloseActionsLatch != null) {
                        onCloseActionsLatch.countDown();
                    }
                    throw e;
                }
            }

            LOGGER.info("[{}] {} buckets parsed from autodetect output", jobId, bucketCount);
        } catch (Exception e) {
            failed = true;

            if (processKilled) {
                // Don't log the stack trace in this case.  Log just enough to hint
                // that it would have been better to close jobs before shutting down,
                // but we now fully expect jobs to move between nodes without doing
                // all their graceful close activities.
                LOGGER.warn("[{}] some results not processed due to the process being killed", jobId);
            } else if (process.isProcessAliveAfterWaiting() == false) {
                // Don't log the stack trace to not shadow the root cause.
                LOGGER.warn("[{}] some results not processed due to the termination of autodetect", jobId);
            } else {
                // We should only get here if the iterator throws in which
                // case parsing the autodetect output has failed.
                LOGGER.error(new ParameterizedMessage("[{}] error parsing autodetect output", jobId), e);
            }
        } finally {
            flushListener.clear();
            completionLatch.countDown();
        }
    }

    public void setProcessKilled() {
        processKilled = true;
        renormalizer.shutdown();
    }

    void processResult(Context context, AutodetectResult result) {
        if (processKilled) {
            return;
        }

        Bucket bucket = result.getBucket();
        if (bucket != null) {
            if (context.deleteInterimRequired) {
                // Delete any existing interim results generated by a Flush command
                // which have not been replaced or superseded by new results.
                LOGGER.trace("[{}] Deleting interim results", context.jobId);
                persister.deleteInterimResults(context.jobId);
                context.deleteInterimRequired = false;
            }

            // persist after deleting interim results in case the new
            // results are also interim
            context.bulkResultsPersister.persistBucket(bucket).executeRequest();
            ++bucketCount;
        }
        List<AnomalyRecord> records = result.getRecords();
        if (records != null && !records.isEmpty()) {
            context.bulkResultsPersister.persistRecords(records);
        }
        List<Influencer> influencers = result.getInfluencers();
        if (influencers != null && !influencers.isEmpty()) {
            context.bulkResultsPersister.persistInfluencers(influencers);
        }
        CategoryDefinition categoryDefinition = result.getCategoryDefinition();
        if (categoryDefinition != null) {
            persister.persistCategoryDefinition(categoryDefinition);
        }
        ModelPlot modelPlot = result.getModelPlot();
        if (modelPlot != null) {
            context.bulkResultsPersister.persistModelPlot(modelPlot);
        }
        Forecast forecast = result.getForecast();
        if (forecast != null) {
            context.bulkResultsPersister.persistForecast(forecast);
        }
        ForecastRequestStats forecastRequestStats = result.getForecastRequestStats();
        if (forecastRequestStats != null) {
            LOGGER.trace("Received Forecast Stats [{}]", forecastRequestStats.getId());
            context.bulkResultsPersister.persistForecastRequestStats(forecastRequestStats);

            // execute the bulk request only in some cases or in doubt
            // otherwise rely on the count-based trigger
            switch (forecastRequestStats.getStatus()) {
                case OK:
                case STARTED:
                    break;
                case FAILED:
                case SCHEDULED:
                case FINISHED:
                default:
                    context.bulkResultsPersister.executeRequest();

            }
        }
        ModelSizeStats modelSizeStats = result.getModelSizeStats();
        if (modelSizeStats != null) {
            processModelSizeStats(context, modelSizeStats);
        }
        ModelSnapshot modelSnapshot = result.getModelSnapshot();
        if (modelSnapshot != null) {
            // We need to refresh in order for the snapshot to be available when we try to update the job with it
            IndexResponse indexResponse = persister.persistModelSnapshot(modelSnapshot, WriteRequest.RefreshPolicy.IMMEDIATE);
            if (indexResponse.getResult() == DocWriteResponse.Result.CREATED) {
                updateModelSnapshotOnJob(modelSnapshot);
            }
        }
        Quantiles quantiles = result.getQuantiles();
        if (quantiles != null) {
            LOGGER.debug("[{}] Parsed Quantiles with timestamp {}", context.jobId, quantiles.getTimestamp());
            persister.persistQuantiles(quantiles);
            context.bulkResultsPersister.executeRequest();

            if (processKilled == false && renormalizer.isEnabled()) {
                // We need to make all results written up to these quantiles available for renormalization
                persister.commitResultWrites(context.jobId);
                LOGGER.debug("[{}] Quantiles queued for renormalization", context.jobId);
                renormalizer.renormalize(quantiles);
            }
        }
        FlushAcknowledgement flushAcknowledgement = result.getFlushAcknowledgement();
        if (flushAcknowledgement != null) {
            LOGGER.debug("[{}] Flush acknowledgement parsed from output for ID {}", context.jobId, flushAcknowledgement.getId());
            // Commit previous writes here, effectively continuing
            // the flush from the C++ autodetect process right
            // through to the data store
            context.bulkResultsPersister.executeRequest();
            persister.commitResultWrites(context.jobId);
            flushListener.acknowledgeFlush(flushAcknowledgement);
            // Interim results may have been produced by the flush,
            // which need to be
            // deleted when the next finalized results come through
            context.deleteInterimRequired = true;
        }
    }

    private void processModelSizeStats(Context context, ModelSizeStats modelSizeStats) {
        LOGGER.trace("[{}] Parsed ModelSizeStats: {} / {} / {} / {} / {} / {}",
                context.jobId, modelSizeStats.getModelBytes(), modelSizeStats.getTotalByFieldCount(),
                modelSizeStats.getTotalOverFieldCount(), modelSizeStats.getTotalPartitionFieldCount(),
                modelSizeStats.getBucketAllocationFailuresCount(), modelSizeStats.getMemoryStatus());

        persister.persistModelSizeStats(modelSizeStats);
        notifyModelMemoryStatusChange(context, modelSizeStats);
        latestModelSizeStats = modelSizeStats;
    }

    private void notifyModelMemoryStatusChange(Context context, ModelSizeStats modelSizeStats) {
        ModelSizeStats.MemoryStatus memoryStatus = modelSizeStats.getMemoryStatus();
        if (memoryStatus != latestModelSizeStats.getMemoryStatus()) {
            if (memoryStatus == ModelSizeStats.MemoryStatus.SOFT_LIMIT) {
                auditor.warning(context.jobId, Messages.getMessage(Messages.JOB_AUDIT_MEMORY_STATUS_SOFT_LIMIT));
            } else if (memoryStatus == ModelSizeStats.MemoryStatus.HARD_LIMIT) {
                auditor.error(context.jobId, Messages.getMessage(Messages.JOB_AUDIT_MEMORY_STATUS_HARD_LIMIT,
                        new ByteSizeValue(modelSizeStats.getModelBytes(), ByteSizeUnit.BYTES).toString()));
            }
        }
    }

    protected void updateModelSnapshotOnJob(ModelSnapshot modelSnapshot) {
        try {
            // This blocks the main processing thread in the unlikely event
            // there are 2 model snapshots queued up. But it also has the
            // advantage of ensuring order
            updateModelSnapshotSemaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.info("[{}] Interrupted acquiring update model snapshot semaphore", jobId);
            return;
        }

        Map<String, Object> update = new HashMap<>();
        update.put(Job.MODEL_SNAPSHOT_ID.getPreferredName(), modelSnapshot.getSnapshotId());
        update.put(Job.MODEL_SNAPSHOT_MIN_VERSION.getPreferredName(), modelSnapshot.getMinVersion().toString());

        updateJob(jobId, update, new ActionListener<UpdateResponse>() {
                    @Override
                    public void onResponse(UpdateResponse updateResponse) {
                        updateModelSnapshotSemaphore.release();
                        LOGGER.debug("[{}] Updated job with model snapshot id [{}]", jobId, modelSnapshot.getSnapshotId());
                    }

                    @Override
                    public void onFailure(Exception e) {
                        updateModelSnapshotSemaphore.release();
                        LOGGER.error("[" + jobId + "] Failed to update job with new model snapshot id [" +
                                modelSnapshot.getSnapshotId() + "]", e);
                    }
                });
    }

    private void onAutodetectClose() {
        onCloseActionsLatch = new CountDownLatch(1);

        ActionListener<UpdateResponse> updateListener = ActionListener.wrap(
                updateResponse -> {
                    onCloseActionsLatch.countDown();
                },
                e -> {
                    LOGGER.error("[" + jobId + "] Failed to finalize job on autodetect close", e);
                    onCloseActionsLatch.countDown();
                }
        );

        updateJob(jobId, Collections.singletonMap(Job.FINISHED_TIME.getPreferredName(), new Date()),
                new ThreadedActionListener<>(LOGGER, client.threadPool(),
                        MachineLearning.UTILITY_THREAD_POOL_NAME, updateListener, false)
        );
    }

    private void updateJob(String jobId, Map<String, Object> update, ActionListener<UpdateResponse> listener) {
        UpdateRequest updateRequest = new UpdateRequest(AnomalyDetectorsIndex.configIndexName(),
                ElasticsearchMappings.DOC_TYPE, Job.documentId(jobId));
        updateRequest.retryOnConflict(3);
        updateRequest.doc(update);
        updateRequest.setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE);
        executeAsyncWithOrigin(client, ML_ORIGIN, UpdateAction.INSTANCE, updateRequest, listener);
    }

    public void awaitCompletion() throws TimeoutException {
        try {
            // Although the results won't take 30 minutes to finish, the pipe won't be closed
            // until the state is persisted, and that can take a while
            if (completionLatch.await(MachineLearningField.STATE_PERSIST_RESTORE_TIMEOUT.getMinutes(),
                    TimeUnit.MINUTES) == false) {
                throw new TimeoutException("Timed out waiting for results processor to complete for job " + jobId);
            }

            // Once completionLatch has passed then onCloseActionsLatch must either
            // be set or null, it will not be set later.
            if (onCloseActionsLatch != null && onCloseActionsLatch.await(
                    MachineLearningField.STATE_PERSIST_RESTORE_TIMEOUT.getMinutes(), TimeUnit.MINUTES) == false) {
                throw new TimeoutException("Timed out waiting for results processor run post close actions " + jobId);
            }

            // Input stream has been completely processed at this point.
            // Wait for any updateModelSnapshotOnJob calls to complete.
            updateModelSnapshotSemaphore.acquire();
            updateModelSnapshotSemaphore.release();

            // These lines ensure that the "completion" we're awaiting includes making the results searchable
            waitUntilRenormalizerIsIdle();
            persister.commitResultWrites(jobId);
            persister.commitStateWrites(jobId);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.info("[{}] Interrupted waiting for results processor to complete", jobId);
        }
    }

    /**
     * Blocks until a flush is acknowledged or the timeout expires, whichever happens first.
     *
     * @param flushId the id of the flush request to wait for
     * @param timeout the timeout
     * @return The {@link FlushAcknowledgement} if the flush has completed or the parsing finished; {@code null} if the timeout expired
     */
    @Nullable
    public FlushAcknowledgement waitForFlushAcknowledgement(String flushId, Duration timeout) throws InterruptedException {
        return failed ? null : flushListener.waitForFlush(flushId, timeout);
    }

    public void clearAwaitingFlush(String flushId) {
        flushListener.clear(flushId);
    }

    public void waitUntilRenormalizerIsIdle() {
        renormalizer.waitUntilIdle();
    }

    /**
     * If failed then there was an error parsing the results that cannot be recovered from
     * @return true if failed
     */
    public boolean isFailed() {
        return failed;
    }

    static class Context {

        private final String jobId;
        private JobResultsPersister.Builder bulkResultsPersister;

        boolean deleteInterimRequired;

        Context(String jobId, JobResultsPersister.Builder bulkResultsPersister) {
            this.jobId = jobId;
            this.deleteInterimRequired = true;
            this.bulkResultsPersister = bulkResultsPersister;
        }
    }

    public ModelSizeStats modelSizeStats() {
        return latestModelSizeStats;
    }
}

