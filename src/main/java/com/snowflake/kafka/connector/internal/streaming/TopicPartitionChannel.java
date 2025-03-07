package com.snowflake.kafka.connector.internal.streaming;

import static com.snowflake.kafka.connector.SnowflakeSinkConnectorConfig.ERRORS_DEAD_LETTER_QUEUE_TOPIC_NAME_CONFIG;
import static com.snowflake.kafka.connector.SnowflakeSinkConnectorConfig.ERRORS_TOLERANCE_CONFIG;
import static com.snowflake.kafka.connector.SnowflakeSinkConnectorConfig.SNOWFLAKE_ROLE;
import static com.snowflake.kafka.connector.internal.streaming.StreamingUtils.DURATION_BETWEEN_GET_OFFSET_TOKEN_RETRY;
import static com.snowflake.kafka.connector.internal.streaming.StreamingUtils.MAX_GET_OFFSET_TOKEN_RETRIES;
import static java.time.temporal.ChronoUnit.SECONDS;
import static org.apache.kafka.common.record.TimestampType.NO_TIMESTAMP_TYPE;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.snowflake.kafka.connector.Utils;
import com.snowflake.kafka.connector.dlq.KafkaRecordErrorReporter;
import com.snowflake.kafka.connector.internal.BufferThreshold;
import com.snowflake.kafka.connector.internal.LoggerHandler;
import com.snowflake.kafka.connector.internal.PartitionBuffer;
import com.snowflake.kafka.connector.internal.SnowflakeConnectionService;
import com.snowflake.kafka.connector.records.RecordService;
import com.snowflake.kafka.connector.records.SnowflakeJsonSchema;
import com.snowflake.kafka.connector.records.SnowflakeRecordContent;
import dev.failsafe.Failsafe;
import dev.failsafe.Fallback;
import dev.failsafe.RetryPolicy;
import dev.failsafe.function.CheckedSupplier;
import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import net.snowflake.client.jdbc.internal.fasterxml.jackson.core.JsonProcessingException;
import net.snowflake.ingest.streaming.InsertValidationResponse;
import net.snowflake.ingest.streaming.OpenChannelRequest;
import net.snowflake.ingest.streaming.SnowflakeStreamingIngestChannel;
import net.snowflake.ingest.streaming.SnowflakeStreamingIngestClient;
import net.snowflake.ingest.utils.Pair;
import net.snowflake.ingest.utils.SFException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.sink.SinkRecord;
import org.apache.kafka.connect.sink.SinkTaskContext;

/**
 * This is a wrapper on top of Streaming Ingest Channel which is responsible for ingesting rows to
 * Snowflake.
 *
 * <p>There is a one to one relation between partition and channel.
 *
 * <p>The number of TopicPartitionChannel objects can scale in proportion to the number of
 * partitions of a topic.
 *
 * <p>Whenever a new instance is created, the cache(Map) in SnowflakeSinkService is also replaced.
 *
 * <p>During rebalance, we would lose this state and hence there is a need to invoke
 * getLatestOffsetToken from Snowflake
 */
public class TopicPartitionChannel {
  private static final LoggerHandler LOGGER =
      new LoggerHandler(TopicPartitionChannel.class.getName());

  private static final long NO_OFFSET_TOKEN_REGISTERED_IN_SNOWFLAKE = -1L;

  // last time we invoked insertRows API
  private long previousFlushTimeStampMs;

  /* Buffer to hold JSON converted incoming SinkRecords */
  private StreamingBuffer streamingBuffer;

  private final Lock bufferLock = new ReentrantLock(true);

  /**
   * States whether this channel has received any records before.
   *
   * <p>If this is false, the topicPartitionChannel has recently been initialised and didnt receive
   * any records before or TopicPartitionChannel was recently created. (Or may be there was some
   * error in some API and hence we had to fetch it again)
   *
   * <p>If the channel is closed, or if partition reassignment is triggered (Rebalancing), we wipe
   * off partitionsToChannel cache in {@link SnowflakeSinkServiceV2}
   */
  private boolean hasChannelReceivedAnyRecordsBefore = false;

  // This offset is updated when Snowflake has received offset from insertRows API
  // We will update this value after calling offsetToken API for this channel
  // We will only update it during start of the channel initialization
  private AtomicLong offsetPersistedInSnowflake =
      new AtomicLong(NO_OFFSET_TOKEN_REGISTERED_IN_SNOWFLAKE);

  // used to communicate to the streaming ingest's insertRows API
  // This is non final because we might decide to get the new instance of Channel
  private SnowflakeStreamingIngestChannel channel;

  /**
   * Offsets are reset in kafka when one of following cases arises in which we rely on source of
   * truth (Which is Snowflake's committed offsetToken)
   *
   * <ol>
   *   <li>If channel fails to fetch offsetToken from Snowflake, we reopen the channel and try to
   *       fetch offset from Snowflake again
   *   <li>If channel fails to ingest a buffer(Buffer containing rows/offsets), we reopen the
   *       channel and try to fetch offset from Snowflake again
   * </ol>
   *
   * <p>In both cases above, we ask Kafka to send back offsets, strictly from offset number after
   * the offset present in Snowflake. i.e of Snowflake has offsetToken = x, we are asking Kafka to
   * start sending offsets again from x + 1
   *
   * <p>We reset the boolean to false when we see the desired offset from Kafka
   *
   * <p>This boolean is used to indicate that we reset offset in kafka and we will only buffer once
   * we see the offset which is one more than an offset present in Snowflake.
   */
  private AtomicBoolean isOffsetResetInKafka = new AtomicBoolean(false);

  // -------- private final fields -------- //

  private final SnowflakeStreamingIngestClient streamingIngestClient;

  // Topic partition Object from connect consisting of topic and partition
  private final TopicPartition topicPartition;

  /* Channel Name is computed from topic and partition */
  private final String channelName;

  /* table is required for opening the channel */
  private final String tableName;

  /* Error handling, DB, schema, Snowflake URL and other snowflake specific connector properties are defined here. */
  private final Map<String, String> sfConnectorConfig;

  /* Responsible for converting records to Json */
  private final RecordService recordService;

  /* Responsible for returning errors to DLQ if records have failed to be ingested. */
  private final KafkaRecordErrorReporter kafkaRecordErrorReporter;

  /**
   * Available from {@link org.apache.kafka.connect.sink.SinkTask} which has access to various
   * utility methods.
   */
  private final SinkTaskContext sinkTaskContext;

  // ------- Kafka record related properties ------- //
  // Offset number we would want to commit back to kafka
  // This value is + 1 of what we find in snowflake.
  // It tells kafka to start sending offsets from this offset because earlier ones have been
  // ingested. (Hence it +1)
  private final AtomicLong offsetSafeToCommitToKafka;

  // added to buffer before calling insertRows
  private final AtomicLong processedOffset; // processed offset

  /* Error related properties */

  // If set to true, we will send records to DLQ provided DLQ name is valid.
  private final boolean errorTolerance;

  // Whether to log errors to log file.
  private final boolean logErrors;

  // Set to false if DLQ topic is null or empty. True if it is a valid string in config
  private final boolean isDLQTopicSet;

  // Used to identify when to flush (Time, bytes or number of records)
  private final BufferThreshold streamingBufferThreshold;

  // Whether schematization has been enabled.
  private final boolean enableSchematization;

  // Whether schema evolution could be done on this channel
  private final boolean enableSchemaEvolution;

  // Reference to the Snowflake connection service
  private final SnowflakeConnectionService conn;

  /** Testing only, initialize TopicPartitionChannel without the connection service */
  public TopicPartitionChannel(
      SnowflakeStreamingIngestClient streamingIngestClient,
      TopicPartition topicPartition,
      final String channelName,
      final String tableName,
      final BufferThreshold streamingBufferThreshold,
      final Map<String, String> sfConnectorConfig,
      KafkaRecordErrorReporter kafkaRecordErrorReporter,
      SinkTaskContext sinkTaskContext) {
    this(
        streamingIngestClient,
        topicPartition,
        channelName,
        tableName,
        streamingBufferThreshold,
        sfConnectorConfig,
        kafkaRecordErrorReporter,
        sinkTaskContext,
        null);
  }

  /**
   * @param streamingIngestClient client created specifically for this task
   * @param topicPartition topic partition corresponding to this Streaming Channel
   *     (TopicPartitionChannel)
   * @param channelName channel Name which is deterministic for topic and partition
   * @param tableName table to ingest in snowflake
   * @param streamingBufferThreshold bytes, count of records and flush time thresholds.
   * @param sfConnectorConfig configuration set for snowflake connector
   * @param kafkaRecordErrorReporter kafka errpr reporter for sending records to DLQ
   * @param sinkTaskContext context on Kafka Connect's runtime
   * @param conn the snowflake connection service
   */
  public TopicPartitionChannel(
      SnowflakeStreamingIngestClient streamingIngestClient,
      TopicPartition topicPartition,
      final String channelName,
      final String tableName,
      final BufferThreshold streamingBufferThreshold,
      final Map<String, String> sfConnectorConfig,
      KafkaRecordErrorReporter kafkaRecordErrorReporter,
      SinkTaskContext sinkTaskContext,
      SnowflakeConnectionService conn) {
    this.streamingIngestClient = Preconditions.checkNotNull(streamingIngestClient);
    Preconditions.checkState(!streamingIngestClient.isClosed());
    this.topicPartition = Preconditions.checkNotNull(topicPartition);
    this.channelName = Preconditions.checkNotNull(channelName);
    this.tableName = Preconditions.checkNotNull(tableName);
    this.streamingBufferThreshold = Preconditions.checkNotNull(streamingBufferThreshold);
    this.sfConnectorConfig = Preconditions.checkNotNull(sfConnectorConfig);
    this.channel = Preconditions.checkNotNull(openChannelForTable());
    this.kafkaRecordErrorReporter = Preconditions.checkNotNull(kafkaRecordErrorReporter);
    this.sinkTaskContext = Preconditions.checkNotNull(sinkTaskContext);
    this.conn = conn;

    this.recordService = new RecordService();

    this.previousFlushTimeStampMs = System.currentTimeMillis();

    this.streamingBuffer = new StreamingBuffer();
    this.processedOffset = new AtomicLong(-1);
    this.offsetSafeToCommitToKafka = new AtomicLong(0);

    /* Error properties */
    this.errorTolerance = StreamingUtils.tolerateErrors(this.sfConnectorConfig);
    this.logErrors = StreamingUtils.logErrors(this.sfConnectorConfig);
    this.isDLQTopicSet =
        !Strings.isNullOrEmpty(StreamingUtils.getDlqTopicName(this.sfConnectorConfig));

    /* Schematization related properties */
    this.enableSchematization =
        this.recordService.setAndGetEnableSchematizationFromConfig(sfConnectorConfig);
    this.enableSchemaEvolution =
        this.enableSchematization
            && this.conn != null
            && this.conn.hasSchemaEvolutionPermission(
                tableName, sfConnectorConfig.get(SNOWFLAKE_ROLE));
  }

  /**
   * Inserts the record into buffer
   *
   * <p>Step 1: Initializes this channel by fetching the offsetToken from Snowflake for the first
   * time this channel/partition has received offset after start/restart.
   *
   * <p>Step 2: Decides whether given offset from Kafka needs to be processed and whether it
   * qualifies for being added into buffer.
   *
   * @param kafkaSinkRecord input record from Kafka
   */
  public void insertRecordToBuffer(SinkRecord kafkaSinkRecord) {
    precomputeOffsetTokenForChannel(kafkaSinkRecord);

    if (shouldIgnoreAddingRecordToBuffer(kafkaSinkRecord)) {
      return;
    }

    // discard the record if the record offset is smaller or equal to server side offset, or if
    // record is smaller than any other record offset we received before
    final long currentOffsetPersistedInSnowflake = this.offsetPersistedInSnowflake.get();
    final long currentProcessedOffset = this.processedOffset.get();
    if (kafkaSinkRecord.kafkaOffset() > currentOffsetPersistedInSnowflake
        && kafkaSinkRecord.kafkaOffset() > currentProcessedOffset) {
      StreamingBuffer copiedStreamingBuffer = null;
      bufferLock.lock();
      try {
        this.streamingBuffer.insert(kafkaSinkRecord);
        this.processedOffset.set(kafkaSinkRecord.kafkaOffset());
        // # of records or size based flushing
        if (this.streamingBufferThreshold.isFlushBufferedBytesBased(
                streamingBuffer.getBufferSizeBytes())
            || this.streamingBufferThreshold.isFlushBufferedRecordCountBased(
                streamingBuffer.getNumOfRecords())) {
          copiedStreamingBuffer = streamingBuffer;
          this.streamingBuffer = new StreamingBuffer();
          LOGGER.debug(
              "Flush based on buffered bytes or buffered number of records for"
                  + " channel:{},currentBufferSizeInBytes:{}, currentBufferedRecordCount:{},"
                  + " connectorBufferThresholds:{}",
              this.getChannelName(),
              copiedStreamingBuffer.getBufferSizeBytes(),
              copiedStreamingBuffer.getSinkRecords().size(),
              this.streamingBufferThreshold);
        }
      } finally {
        bufferLock.unlock();
      }

      // If we found reaching buffer size threshold or count based threshold, we will immediately
      // flush (Insert them)
      if (copiedStreamingBuffer != null) {
        insertBufferedRecords(copiedStreamingBuffer);
      }
    } else {
      LOGGER.debug(
          "Skip adding offset:{} to buffer for channel:{} because"
              + " offsetPersistedInSnowflake:{}, processedOffset:{}",
          kafkaSinkRecord.kafkaOffset(),
          this.getChannelName(),
          currentOffsetPersistedInSnowflake,
          currentProcessedOffset);
    }
  }

  /**
   * If kafka offset was recently reset, we will skip adding any more records to buffer until we see
   * a desired offset from kafka.
   *
   * <p>Desired Offset from Kafka = (offset persisted in snowflake + 1)
   *
   * <p>Check {link {@link TopicPartitionChannel#resetChannelMetadataAfterRecovery}} for reset logic
   *
   * @param kafkaSinkRecord Record to check for above condition only in case of failures
   *     (isOffsetResetInKafka = true)
   * @return true if this record can be skipped to add into buffer, false otherwise.
   */
  private boolean shouldIgnoreAddingRecordToBuffer(SinkRecord kafkaSinkRecord) {
    if (this.isOffsetResetInKafka.get()) {
      if ((kafkaSinkRecord.kafkaOffset() - offsetPersistedInSnowflake.get()) != 1L) {
        // ignore
        LOGGER.debug(
            "Ignore adding offset:{} to buffer for channel:{} because we recently encountered"
                + " error and reset offset in Kafka. offsetPersistedInSnowflake:{}",
            kafkaSinkRecord.kafkaOffset(),
            this.getChannelName(),
            this.offsetPersistedInSnowflake.get());
        return true;
      } else if ((kafkaSinkRecord.kafkaOffset() - offsetPersistedInSnowflake.get()) == 1L) {
        LOGGER.debug(
            "Got the desired offset:{} from Kafka, we can add this offset to buffer for channel:{}",
            kafkaSinkRecord.kafkaOffset(),
            this.getChannelName());
        this.isOffsetResetInKafka.set(false);
      }
    }
    return false;
  }

  /**
   * Pre-computes the offset token which might have been persisted into Snowflake for this channel.
   *
   * <p>It is possible the offset token is null. In this case, -1 is set
   *
   * <p>Note: This code is execute only for the first time when:
   *
   * <ul>
   *   <li>Connector starts
   *   <li>Connector restarts because task was killed
   *   <li>Rebalance/Partition reassignment
   * </ul>
   *
   * @param record record this partition received.
   */
  private void precomputeOffsetTokenForChannel(final SinkRecord record) {
    if (!hasChannelReceivedAnyRecordsBefore) {
      LOGGER.info(
          "Received offset:{} for topic:{} as the first offset for this partition:{} after"
              + " start/restart/rebalance",
          record.kafkaOffset(),
          record.topic(),
          record.kafkaPartition());
      // This will only be called once at the beginning when an offset arrives for first time
      // after connector starts
      final long lastCommittedOffsetToken = fetchOffsetTokenWithRetry();
      this.offsetPersistedInSnowflake.set(
          (lastCommittedOffsetToken == -1L)
              ? NO_OFFSET_TOKEN_REGISTERED_IN_SNOWFLAKE
              : lastCommittedOffsetToken);
      this.hasChannelReceivedAnyRecordsBefore = true;
    }
  }

  private boolean shouldConvertContent(final Object content) {
    return content != null && !(content instanceof SnowflakeRecordContent);
  }

  /**
   * This would always return false for streaming ingest use case since isBroken field is never set.
   * isBroken is set only when using Custom snowflake converters and the content was not json
   * serializable.
   *
   * <p>For Community converters, the kafka record will not be sent to Kafka connector if the record
   * is not serializable.
   */
  private boolean isRecordBroken(final SinkRecord record) {
    return isContentBroken(record.value()) || isContentBroken(record.key());
  }

  private boolean isContentBroken(final Object content) {
    return content != null && ((SnowflakeRecordContent) content).isBroken();
  }

  private SinkRecord handleNativeRecord(SinkRecord record, boolean isKey) {
    SnowflakeRecordContent newSFContent;
    Schema schema = isKey ? record.keySchema() : record.valueSchema();
    Object content = isKey ? record.key() : record.value();
    try {
      newSFContent = new SnowflakeRecordContent(schema, content);
    } catch (Exception e) {
      LOGGER.error("Native content parser error:\n{}", e.getMessage());
      try {
        // try to serialize this object and send that as broken record
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ObjectOutputStream os = new ObjectOutputStream(out);
        os.writeObject(content);
        newSFContent = new SnowflakeRecordContent(out.toByteArray());
      } catch (Exception serializeError) {
        LOGGER.error(
            "Failed to convert broken native record to byte data:\n{}",
            serializeError.getMessage());
        throw e;
      }
    }
    // create new sinkRecord
    Schema keySchema = isKey ? new SnowflakeJsonSchema() : record.keySchema();
    Object keyContent = isKey ? newSFContent : record.key();
    Schema valueSchema = isKey ? record.valueSchema() : new SnowflakeJsonSchema();
    Object valueContent = isKey ? record.value() : newSFContent;
    return new SinkRecord(
        record.topic(),
        record.kafkaPartition(),
        keySchema,
        keyContent,
        valueSchema,
        valueContent,
        record.kafkaOffset(),
        record.timestamp(),
        record.timestampType(),
        record.headers());
  }

  // --------------- BUFFER FLUSHING LOGIC --------------- //

  /**
   * If difference between current time and previous flush time is more than threshold, insert the
   * buffered Rows.
   *
   * <p>Note: We acquire buffer lock since we copy the buffer.
   *
   * <p>Threshold is config parameter: {@link
   * com.snowflake.kafka.connector.SnowflakeSinkConnectorConfig#BUFFER_FLUSH_TIME_SEC}
   *
   * <p>Previous flush time here means last time we called insertRows API with rows present in
   */
  protected void insertBufferedRecordsIfFlushTimeThresholdReached() {
    if (this.streamingBufferThreshold.isFlushTimeBased(this.previousFlushTimeStampMs)) {
      LOGGER.debug(
          "Time based flush for channel:{}, CurrentTimeMs:{}, previousFlushTimeMs:{},"
              + " bufferThresholdSeconds:{}",
          this.getChannelName(),
          System.currentTimeMillis(),
          this.previousFlushTimeStampMs,
          this.streamingBufferThreshold.getFlushTimeThresholdSeconds());
      StreamingBuffer copiedStreamingBuffer;
      bufferLock.lock();
      try {
        copiedStreamingBuffer = this.streamingBuffer;
        this.streamingBuffer = new StreamingBuffer();
      } finally {
        bufferLock.unlock();
      }
      if (copiedStreamingBuffer != null) {
        insertBufferedRecords(copiedStreamingBuffer);
      }
    }
  }

  /**
   * Invokes insertRows API using the provided offsets which were initially buffered for this
   * partition. This buffer is decided based on the flush time threshold, buffered bytes or number
   * of records
   */
  InsertRowsResponse insertBufferedRecords(StreamingBuffer streamingBufferToInsert) {
    // intermediate buffer can be empty here if time interval reached but kafka produced no records.
    if (streamingBufferToInsert.isEmpty()) {
      LOGGER.debug("No Rows Buffered for channel:{}, returning", this.getChannelName());
      this.previousFlushTimeStampMs = System.currentTimeMillis();
      return null;
    }
    InsertRowsResponse response = null;
    try {
      response = insertRowsWithFallback(streamingBufferToInsert);
      // Updates the flush time (last time we called insertRows API)
      this.previousFlushTimeStampMs = System.currentTimeMillis();

      LOGGER.info(
          "Successfully called insertRows for channel:{}, buffer:{}, insertResponseHasErrors:{}",
          this.getChannelName(),
          streamingBufferToInsert,
          response.hasErrors());
      if (response.hasErrors()) {
        handleInsertRowsFailures(
            response.getInsertErrors(), streamingBufferToInsert.getSinkRecords());
      }

      // Check whether we need to reset the offset due to schema evolution
      if (response.needToResetOffset()) {
        streamingApiFallbackSupplier(
            StreamingApiFallbackInvoker.INSERT_ROWS_SCHEMA_EVOLUTION_FALLBACK);
      }
      return response;
    } catch (TopicPartitionChannelInsertionException ex) {
      // Suppressing the exception because other channels might still continue to ingest
      LOGGER.warn(
          String.format(
              "[INSERT_BUFFERED_RECORDS] Failure inserting buffer:%s for channel:%s",
              streamingBufferToInsert, this.getChannelName()),
          ex);
    }
    return response;
  }

  /**
   * Uses {@link Fallback} API to reopen the channel if insertRows throws {@link SFException}.
   *
   * <p>We have deliberately not performed retries on insertRows because it might slow down overall
   * ingestion and introduce lags in committing offsets to Kafka.
   *
   * <p>Note that insertRows API does perform channel validation which might throw SFException if
   * channel is invalidated.
   *
   * <p>It can also send errors {@link
   * net.snowflake.ingest.streaming.InsertValidationResponse.InsertError} in form of response inside
   * {@link InsertValidationResponse}
   *
   * @param buffer buffer to insert into snowflake
   * @return InsertRowsResponse a response that wraps around InsertValidationResponse
   */
  private InsertRowsResponse insertRowsWithFallback(StreamingBuffer buffer) {
    Fallback<Object> reopenChannelFallbackExecutorForInsertRows =
        Fallback.builder(
                executionAttemptedEvent -> {
                  insertRowsFallbackSupplier(executionAttemptedEvent.getLastException());
                })
            .handle(SFException.class)
            .onFailedAttempt(
                event ->
                    LOGGER.warn(
                        String.format(
                            "Failed Attempt to invoke the insertRows API for buffer:%s", buffer),
                        event.getLastException()))
            .onFailure(
                event ->
                    LOGGER.error(
                        String.format(
                            "%s Failed to open Channel or fetching offsetToken for channel:%s",
                            StreamingApiFallbackInvoker.INSERT_ROWS_FALLBACK,
                            this.getChannelName()),
                        event.getException()))
            .build();

    return Failsafe.with(reopenChannelFallbackExecutorForInsertRows)
        .get(
            new InsertRowsApiResponseSupplier(
                this.channel, buffer, this.enableSchemaEvolution, this.conn));
  }

  /** Invokes the API given the channel and streaming Buffer. */
  private static class InsertRowsApiResponseSupplier
      implements CheckedSupplier<InsertRowsResponse> {

    // Reference to the Snowpipe Streaming channel
    private final SnowflakeStreamingIngestChannel channel;

    // Buffer that holds the original sink records from kafka
    private final StreamingBuffer insertRowsStreamingBuffer;

    // Whether the schema evolution is enabled
    private final boolean enableSchemaEvolution;

    // Connection service which will be used to do the ALTER TABLE command for schema evolution
    private final SnowflakeConnectionService conn;

    private InsertRowsApiResponseSupplier(
        SnowflakeStreamingIngestChannel channelForInsertRows,
        StreamingBuffer insertRowsStreamingBuffer,
        boolean enableSchemaEvolution,
        SnowflakeConnectionService conn) {
      this.channel = channelForInsertRows;
      this.insertRowsStreamingBuffer = insertRowsStreamingBuffer;
      this.enableSchemaEvolution = enableSchemaEvolution;
      this.conn = conn;
    }

    @Override
    public InsertRowsResponse get() throws Throwable {
      LOGGER.debug(
          "Invoking insertRows API for channel:{}, streamingBuffer:{}",
          this.channel.getFullyQualifiedName(),
          this.insertRowsStreamingBuffer);
      Pair<List<Map<String, Object>>, List<Long>> recordsAndOffsets =
          this.insertRowsStreamingBuffer.getData();
      List<Map<String, Object>> records = recordsAndOffsets.getKey();
      List<Long> offsets = recordsAndOffsets.getValue();
      InsertValidationResponse finalResponse = new InsertValidationResponse();
      boolean needToResetOffset = false;
      if (!enableSchemaEvolution) {
        finalResponse =
            this.channel.insertRows(
                records, Long.toString(this.insertRowsStreamingBuffer.getLastOffset()));
      } else {
        for (int idx = 0; idx < records.size(); idx++) {
          // For schema evolution, we need to call the insertRows API row by row in order to
          // preserve the original order, for anything after the first schema mismatch error we will
          // retry after the evolution
          InsertValidationResponse response =
              this.channel.insertRow(records.get(idx), Long.toString(offsets.get(idx)));
          if (response.hasErrors()) {
            InsertValidationResponse.InsertError insertError = response.getInsertErrors().get(0);
            List<String> extraColNames = insertError.getExtraColNames();
            List<String> nonNullableColumns = insertError.getMissingNotNullColNames();
            long originalSinkRecordIdx =
                offsets.get(idx) - this.insertRowsStreamingBuffer.getFirstOffset();
            if (extraColNames == null && nonNullableColumns == null) {
              InsertValidationResponse.InsertError newInsertError =
                  new InsertValidationResponse.InsertError(
                      insertError.getRowContent(), originalSinkRecordIdx);
              newInsertError.setException(insertError.getException());
              newInsertError.setExtraColNames(insertError.getExtraColNames());
              newInsertError.setMissingNotNullColNames(insertError.getMissingNotNullColNames());
              // Simply added to the final response if it's not schema related errors
              finalResponse.addError(insertError);
            } else {
              SchematizationUtils.evolveSchemaIfNeeded(
                  this.conn,
                  this.channel.getTableName(),
                  nonNullableColumns,
                  extraColNames,
                  this.insertRowsStreamingBuffer.getSinkRecord(originalSinkRecordIdx));
              // Offset reset needed since it's possible that we successfully ingested partial batch
              needToResetOffset = true;
              break;
            }
          }
        }
      }
      return new InsertRowsResponse(finalResponse, needToResetOffset);
    }
  }

  // A class that wraps around the InsertValidationResponse from the Ingest SDK plus some additional
  // information
  static class InsertRowsResponse {
    private final InsertValidationResponse response;
    private final boolean needToResetOffset;

    InsertRowsResponse(InsertValidationResponse response, boolean needToResetOffset) {
      this.response = response;
      this.needToResetOffset = needToResetOffset;
    }

    boolean hasErrors() {
      return response.hasErrors();
    }

    List<InsertValidationResponse.InsertError> getInsertErrors() {
      return response.getInsertErrors();
    }

    boolean needToResetOffset() {
      return this.needToResetOffset;
    }
  }

  /**
   * We will reopen the channel on {@link SFException} and reset offset in kafka. But, we will throw
   * a custom exception to show that the streamingBuffer was not added into Snowflake.
   *
   * @throws TopicPartitionChannelInsertionException exception is thrown after channel reopen has
   *     been successful and offsetToken was fetched from Snowflake
   */
  private void insertRowsFallbackSupplier(Throwable ex)
      throws TopicPartitionChannelInsertionException {
    final long offsetRecoveredFromSnowflake =
        streamingApiFallbackSupplier(StreamingApiFallbackInvoker.INSERT_ROWS_FALLBACK);
    throw new TopicPartitionChannelInsertionException(
        String.format(
            "%s Failed to insert rows for channel:%s. Recovered offset from Snowflake is:%s",
            StreamingApiFallbackInvoker.INSERT_ROWS_FALLBACK,
            this.getChannelName(),
            offsetRecoveredFromSnowflake),
        ex);
  }

  /**
   * Invoked only when {@link InsertValidationResponse} has errors.
   *
   * <p>This function checks if we need to log errors, send it to DLQ or just ignore and throw
   * exception.
   *
   * @param insertErrors errors from validation response. (Only if it has errors)
   * @param insertedRecordsToBuffer to map {@link SinkRecord} with insertErrors
   */
  private void handleInsertRowsFailures(
      List<InsertValidationResponse.InsertError> insertErrors,
      List<SinkRecord> insertedRecordsToBuffer) {
    if (logErrors) {
      for (InsertValidationResponse.InsertError insertError : insertErrors) {
        LOGGER.error("Insert Row Error message", insertError.getException());
      }
    }
    if (errorTolerance) {
      if (!isDLQTopicSet) {
        LOGGER.warn(
            "Although config:{} is set, Dead Letter Queue topic:{} is not set.",
            ERRORS_TOLERANCE_CONFIG,
            ERRORS_DEAD_LETTER_QUEUE_TOPIC_NAME_CONFIG);
      } else {
        for (InsertValidationResponse.InsertError insertError : insertErrors) {
          // Map error row number to index in sinkRecords list.
          int rowIndexToOriginalSinkRecord = (int) insertError.getRowIndex();
          this.kafkaRecordErrorReporter.reportError(
              insertedRecordsToBuffer.get(rowIndexToOriginalSinkRecord),
              insertError.getException());
        }
      }
    } else {
      throw new DataException(
          "Error inserting Records using Streaming API", insertErrors.get(0).getException());
    }
  }

  // TODO: SNOW-529755 POLL committed offsets in backgraound thread

  /**
   * Get committed offset from Snowflake. It does an HTTP call internally to find out what was the
   * last offset inserted.
   *
   * <p>If committedOffset fetched from Snowflake is null, we would return -1(default value of
   * committedOffset) back to original call. (-1) would return an empty Map of partition and offset
   * back to kafka.
   *
   * <p>Else, we will convert this offset and return the offset which is safe to commit inside Kafka
   * (+1 of this returned value).
   *
   * <p>Check {@link com.snowflake.kafka.connector.SnowflakeSinkTask#preCommit(Map)}
   *
   * <p>Note:
   *
   * <p>If we cannot fetch offsetToken from snowflake even after retries and reopening the channel,
   * we will throw app
   *
   * @return (offsetToken present in Snowflake + 1), else -1
   */
  public long getOffsetSafeToCommitToKafka() {
    final long committedOffsetInSnowflake = fetchOffsetTokenWithRetry();
    if (committedOffsetInSnowflake == NO_OFFSET_TOKEN_REGISTERED_IN_SNOWFLAKE) {
      return offsetSafeToCommitToKafka.get();
    } else {
      // Return an offset which is + 1 of what was present in snowflake.
      // Idea of sending + 1 back to Kafka is that it should start sending offsets after task
      // restart from this offset
      return offsetSafeToCommitToKafka.updateAndGet(operand -> committedOffsetInSnowflake + 1);
    }
  }

  /**
   * Fetches the offset token from Snowflake.
   *
   * <p>It uses <a href="https://github.com/failsafe-lib/failsafe">Failsafe library </a> which
   * implements retries, fallbacks and circuit breaker.
   *
   * <p>Here is how Failsafe is implemented.
   *
   * <p>Fetches offsetToken from Snowflake (Streaming API)
   *
   * <p>If it returns a valid offset number, that number is returned back to caller.
   *
   * <p>If {@link net.snowflake.ingest.utils.SFException} is thrown, we will retry for max 3 times.
   * (Including the original try)
   *
   * <p>Upon reaching the limit of maxRetries, we will {@link Fallback} to opening a channel and
   * fetching offsetToken again.
   *
   * <p>Please note, upon executing fallback, we might throw an exception too. However, in that case
   * we will not retry.
   *
   * @return long offset token present in snowflake for this channel/partition.
   */
  @VisibleForTesting
  protected long fetchOffsetTokenWithRetry() {
    final RetryPolicy<Long> offsetTokenRetryPolicy =
        RetryPolicy.<Long>builder()
            .handle(SFException.class)
            .withDelay(DURATION_BETWEEN_GET_OFFSET_TOKEN_RETRY)
            .withMaxAttempts(MAX_GET_OFFSET_TOKEN_RETRIES)
            .onRetry(
                event ->
                    LOGGER.warn(
                        "[OFFSET_TOKEN_RETRY_POLICY] retry for getLatestCommittedOffsetToken. Retry"
                            + " no:{}, message:{}",
                        event.getAttemptCount(),
                        event.getLastException().getMessage()))
            .build();

    /*
     * The fallback function to execute when all retries from getOffsetToken have exhausted.
     * Fallback is only attempted on SFException
     */
    Fallback<Long> offsetTokenFallbackExecutor =
        Fallback.builder(
                () ->
                    streamingApiFallbackSupplier(
                        StreamingApiFallbackInvoker.GET_OFFSET_TOKEN_FALLBACK))
            .handle(SFException.class)
            .onFailure(
                event ->
                    LOGGER.error(
                        "[OFFSET_TOKEN_FALLBACK] Failed to open Channel/fetch offsetToken for"
                            + " channel:{}",
                        this.getChannelName(),
                        event.getException()))
            .build();

    // Read it from reverse order. Fetch offsetToken, apply retry policy and then fallback.
    return Failsafe.with(offsetTokenFallbackExecutor)
        .onFailure(
            event ->
                LOGGER.error(
                    "[OFFSET_TOKEN_RETRY_FAILSAFE] Failure to fetch offsetToken even after retry"
                        + " and fallback from snowflake for channel:{}, elapsedTimeSeconds:{}",
                    this.getChannelName(),
                    event.getElapsedTime().get(SECONDS),
                    event.getException()))
        .compose(offsetTokenRetryPolicy)
        .get(this::fetchLatestCommittedOffsetFromSnowflake);
  }

  /**
   * Fallback function to be executed when either of insertRows API or getOffsetToken sends
   * SFException.
   *
   * <p>Or, in other words, if streaming channel is invalidated, we will reopen the channel and
   * reset the kafka offset to last committed offset in Snowflake.
   *
   * <p>If a valid offset is found from snowflake, we will reset the topicPartition with
   * (offsetReturnedFromSnowflake + 1).
   *
   * @param streamingApiFallbackInvoker Streaming API which is using this fallback function. Used
   *     for logging mainly.
   * @return offset which was last present in Snowflake
   */
  private long streamingApiFallbackSupplier(
      final StreamingApiFallbackInvoker streamingApiFallbackInvoker) {
    final long offsetRecoveredFromSnowflake =
        getRecoveredOffsetFromSnowflake(streamingApiFallbackInvoker);
    resetChannelMetadataAfterRecovery(streamingApiFallbackInvoker, offsetRecoveredFromSnowflake);
    return offsetRecoveredFromSnowflake;
  }

  /**
   * Resets the offset in kafka, resets metadata related to offsets and clears the buffer.
   *
   * <p>Idea behind resetting offset (1 more than what we found in snowflake) is that Kafka should
   * send offsets from this offset number so as to not miss any data.
   *
   * @param streamingApiFallbackInvoker Streaming API which is using this fallback function. Used
   *     for logging mainly.
   * @param offsetRecoveredFromSnowflake offset number found in snowflake for this
   *     channel(partition)
   */
  private void resetChannelMetadataAfterRecovery(
      final StreamingApiFallbackInvoker streamingApiFallbackInvoker,
      final long offsetRecoveredFromSnowflake) {
    final long offsetToResetInKafka = offsetRecoveredFromSnowflake + 1L;

    // reset the buffer
    this.bufferLock.lock();
    try {
      LOGGER.warn(
          "[RESET_PARTITION] Emptying current buffer:{} for Channel:{} due to reset of offsets in"
              + " kafka",
          this.streamingBuffer,
          this.getChannelName());
      this.streamingBuffer = new StreamingBuffer();
      // Reset Offset in kafka for this topic partition.
      this.sinkTaskContext.offset(this.topicPartition, offsetToResetInKafka);

      // Need to update the in memory processed offset otherwise if same offset is send again, it
      // might get rejected.
      this.processedOffset.set(offsetRecoveredFromSnowflake);
      this.offsetPersistedInSnowflake.set(offsetRecoveredFromSnowflake);

      // state that there was some exception and only clear that state when we have received offset
      // starting from offsetRecoveredFromSnowflake
      this.isOffsetResetInKafka.set(true);
    } finally {
      this.bufferLock.unlock();
    }
    LOGGER.warn(
        "{} Channel:{}, OffsetRecoveredFromSnowflake:{}, Reset kafka offset to:{}",
        streamingApiFallbackInvoker,
        this.getChannelName(),
        offsetRecoveredFromSnowflake,
        offsetToResetInKafka);
  }

  /**
   * {@link Fallback} executes below code if retries have failed on {@link SFException}.
   *
   * <p>It re-opens the channel and fetches the latestOffsetToken one more time after reopen was
   * successful.
   *
   * @param streamingApiFallbackInvoker Streaming API which invoked this function.
   * @return offset which was last present in Snowflake
   */
  private long getRecoveredOffsetFromSnowflake(
      final StreamingApiFallbackInvoker streamingApiFallbackInvoker) {
    LOGGER.warn("{} Re-opening channel:{}", streamingApiFallbackInvoker, this.getChannelName());
    this.channel = Preconditions.checkNotNull(openChannelForTable());
    LOGGER.warn(
        "{} Fetching offsetToken after re-opening the channel:{}",
        streamingApiFallbackInvoker,
        this.getChannelName());
    return fetchLatestCommittedOffsetFromSnowflake();
  }

  /**
   * Returns the offset Token persisted into snowflake.
   *
   * <p>OffsetToken from Snowflake returns a String and we will convert it into long.
   *
   * <p>If it is not long parsable, we will throw {@link ConnectException}
   *
   * @return -1 if no offset is found in snowflake, else the long value of committedOffset in
   *     snowflake.
   */
  private long fetchLatestCommittedOffsetFromSnowflake() {
    LOGGER.debug(
        "Fetching last committed offset for partition channel:{}",
        this.channel.getFullyQualifiedName());
    String offsetToken = null;
    try {
      offsetToken = this.channel.getLatestCommittedOffsetToken();
      if (offsetToken == null) {
        LOGGER.info("OffsetToken not present for channelName:{}", this.getChannelName());
        return NO_OFFSET_TOKEN_REGISTERED_IN_SNOWFLAKE;
      } else {
        long latestCommittedOffsetInSnowflake = Long.parseLong(offsetToken);
        LOGGER.info(
            "Fetched offsetToken:{} for channelName:{}",
            latestCommittedOffsetInSnowflake,
            this.channel.getFullyQualifiedName());
        return latestCommittedOffsetInSnowflake;
      }
    } catch (NumberFormatException ex) {
      LOGGER.error(
          "The offsetToken string does not contain a parsable long:{} for channel:{}",
          offsetToken,
          this.getChannelName());
      throw new ConnectException(ex);
    }
  }

  /**
   * Open a channel for Table with given channel name and tableName.
   *
   * <p>Open channels happens at:
   *
   * <p>Constructor of TopicPartitionChannel -> which means we will wipe of all states and it will
   * call precomputeOffsetTokenForChannel
   *
   * <p>Failure handling which will call reopen, replace instance variable with new channel and call
   * offsetToken/insertRows.
   *
   * @return new channel which was fetched after open/reopen
   */
  private SnowflakeStreamingIngestChannel openChannelForTable() {
    OpenChannelRequest channelRequest =
        OpenChannelRequest.builder(this.channelName)
            .setDBName(this.sfConnectorConfig.get(Utils.SF_DATABASE))
            .setSchemaName(this.sfConnectorConfig.get(Utils.SF_SCHEMA))
            .setTableName(this.tableName)
            .setOnErrorOption(OpenChannelRequest.OnErrorOption.CONTINUE)
            .build();
    LOGGER.info(
        "Opening a channel with name:{} for table name:{}", this.channelName, this.tableName);
    return streamingIngestClient.openChannel(channelRequest);
  }

  /**
   * Close channel associated to this partition Not rethrowing connect exception because the
   * connector will stop. Channel will eventually be reopened.
   */
  public void closeChannel() {
    try {
      this.channel.close().get();
    } catch (InterruptedException | ExecutionException e) {
      LOGGER.error(
          "Failure closing Streaming Channel name:{} msg:{}",
          this.getChannelName(),
          e.getMessage(),
          e);
    }
  }

  /* Return true is channel is closed. Caller should handle the logic for reopening the channel if it is closed. */
  public boolean isChannelClosed() {
    return this.channel.isClosed();
  }

  // ------ GETTERS ------ //

  public StreamingBuffer getStreamingBuffer() {
    return streamingBuffer;
  }

  public long getPreviousFlushTimeStampMs() {
    return previousFlushTimeStampMs;
  }

  public String getChannelName() {
    return this.channel.getFullyQualifiedName();
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("previousFlushTimeStampMs", this.previousFlushTimeStampMs)
        .add("hasChannelReceivedAnyRecordsBefore", this.hasChannelReceivedAnyRecordsBefore)
        .add("offsetPersistedInSnowflake", this.offsetPersistedInSnowflake)
        .add("channelName", this.getChannelName())
        .add("offsetSafeToCommitToKafka", this.offsetSafeToCommitToKafka.get())
        .add("isStreamingIngestClientClosed", this.streamingIngestClient.isClosed())
        .toString();
  }

  @VisibleForTesting
  protected long getOffsetPersistedInSnowflake() {
    return this.offsetPersistedInSnowflake.get();
  }

  @VisibleForTesting
  protected boolean isPartitionBufferEmpty() {
    return streamingBuffer.isEmpty();
  }

  @VisibleForTesting
  protected SnowflakeStreamingIngestChannel getChannel() {
    return this.channel;
  }

  @VisibleForTesting
  protected boolean getIsOffsetResetInKafka() {
    return isOffsetResetInKafka.get();
  }

  /**
   * Converts the original kafka sink record into a Json Record. i.e key and values are converted
   * into Json so that it can be used to insert into variant column of Snowflake Table.
   *
   * <p>TODO: SNOW-630885 - When schematization is enabled, we should create the map directly from
   * the SinkRecord instead of first turning it into json
   */
  private SinkRecord getSnowflakeSinkRecordFromKafkaRecord(final SinkRecord kafkaSinkRecord) {
    SinkRecord snowflakeRecord = kafkaSinkRecord;
    if (shouldConvertContent(kafkaSinkRecord.value())) {
      snowflakeRecord = handleNativeRecord(kafkaSinkRecord, false);
    }
    if (shouldConvertContent(kafkaSinkRecord.key())) {
      snowflakeRecord = handleNativeRecord(snowflakeRecord, true);
    }

    return snowflakeRecord;
  }

  /**
   * Get Approximate size of Sink Record which we get from Kafka. This is useful to find out how
   * much data(records) we have buffered per channel/partition.
   *
   * <p>This is an approximate size since there is no API available to find out size of record.
   *
   * <p>We first serialize the incoming kafka record into a Json format and find estimate size.
   *
   * <p>Please note, the size we calculate here is not accurate and doesnt match with actual size of
   * Kafka record which we buffer in memory. (Kafka Sink Record has lot of other metadata
   * information which is discarded when we calculate the size of Json Record)
   *
   * <p>We also do the same processing just before calling insertRows API for the buffered rows.
   *
   * <p>Downside of this calculation is we might try to buffer more records but we could be close to
   * JVM memory getting full
   *
   * @param kafkaSinkRecord sink record received as is from Kafka (With connector specific converter
   *     being invoked)
   * @return Approximate long size of record in bytes. 0 if record is broken
   */
  protected long getApproxSizeOfRecordInBytes(SinkRecord kafkaSinkRecord) {
    long sinkRecordBufferSizeInBytes = 0l;

    SinkRecord snowflakeRecord = getSnowflakeSinkRecordFromKafkaRecord(kafkaSinkRecord);

    if (isRecordBroken(snowflakeRecord)) {
      // we won't be able to find accurate size of serialized record since serialization itself
      // failed
      // But this will not happen in streaming ingest since we deprecated custom converters.
      return 0L;
    }

    try {
      // get the row that we want to insert into Snowflake.
      Map<String, Object> tableRow =
          recordService.getProcessedRecordForStreamingIngest(snowflakeRecord);
      // need to loop through the map and get the object node
      for (Map.Entry<String, Object> entry : tableRow.entrySet()) {
        sinkRecordBufferSizeInBytes += entry.getKey().length() * 2L;
        // Can Typecast into string because value is JSON
        Object value = entry.getValue();
        if (value != null) {
          if (value instanceof String) {
            sinkRecordBufferSizeInBytes += ((String) value).length() * 2L; // 1 char = 2 bytes
          } else {
            // for now it could only be a list of string
            for (String s : (List<String>) value) {
              sinkRecordBufferSizeInBytes += s.length() * 2L;
            }
          }
        }
      }
    } catch (JsonProcessingException e) {
      // We ignore any errors here because this is just calculating the record size
    }

    sinkRecordBufferSizeInBytes += StreamingUtils.MAX_RECORD_OVERHEAD_BYTES;
    return sinkRecordBufferSizeInBytes;
  }

  // ------ INNER CLASS ------ //

  /**
   * A buffer which holds the rows before calling insertRows API. It implements the PartitionBuffer
   * class which has all common fields about a buffer.
   *
   * <p>This buffer is a bit different from Snowpipe Buffer. In StreamingBuffer we buffer incoming
   * records from Kafka and once threshold has reached, we would call insertRows API to insert into
   * Snowflake.
   *
   * <p>We would transform kafka records to Snowflake understood records (In JSON format) just
   * before calling insertRows API.
   */
  @VisibleForTesting
  protected class StreamingBuffer
      extends PartitionBuffer<Pair<List<Map<String, Object>>, List<Long>>> {
    // Records coming from Kafka
    private final List<SinkRecord> sinkRecords;

    StreamingBuffer() {
      super();
      sinkRecords = new ArrayList<>();
    }

    @Override
    public void insert(SinkRecord kafkaSinkRecord) {
      if (sinkRecords.isEmpty()) {
        setFirstOffset(kafkaSinkRecord.kafkaOffset());
      }
      sinkRecords.add(kafkaSinkRecord);

      setNumOfRecords(getNumOfRecords() + 1);
      setLastOffset(kafkaSinkRecord.kafkaOffset());

      final long currentKafkaRecordSizeInBytes = getApproxSizeOfRecordInBytes(kafkaSinkRecord);
      // update size of buffer
      setBufferSizeBytes(getBufferSizeBytes() + currentKafkaRecordSizeInBytes);
    }

    /**
     * Get all rows and their offsets. Each map corresponds to one row whose keys are column names
     * and values are corresponding data in that column.
     *
     * <p>This goes over through all buffered kafka records and transforms into JsonSchema and
     * JsonNode Check {@link #handleNativeRecord(SinkRecord, boolean)}
     *
     * @return A pair that contains the records and their corresponding offsets
     */
    @Override
    public Pair<List<Map<String, Object>>, List<Long>> getData() {
      final List<Map<String, Object>> records = new ArrayList<>();
      final List<Long> offsets = new ArrayList<>();
      for (SinkRecord kafkaSinkRecord : sinkRecords) {
        SinkRecord snowflakeRecord = getSnowflakeSinkRecordFromKafkaRecord(kafkaSinkRecord);

        // broken record
        if (isRecordBroken(snowflakeRecord)) {
          // check for error tolerance and log tolerance values
          // errors.log.enable and errors.tolerance
          LOGGER.debug(
              "Broken record offset:{}, topic:{}",
              kafkaSinkRecord.kafkaOffset(),
              kafkaSinkRecord.topic());
          kafkaRecordErrorReporter.reportError(kafkaSinkRecord, new DataException("Broken Record"));
        } else {
          // lag telemetry, note that sink record timestamp might be null
          if (snowflakeRecord.timestamp() != null
              && snowflakeRecord.timestampType() != NO_TIMESTAMP_TYPE) {
            // TODO:SNOW-529751 telemetry
          }

          // Convert this records into Json Schema which has content and metadata, add it to DLQ if
          // there is an exception
          try {
            Map<String, Object> tableRow =
                recordService.getProcessedRecordForStreamingIngest(snowflakeRecord);
            records.add(tableRow);
            offsets.add(snowflakeRecord.kafkaOffset());
          } catch (JsonProcessingException e) {
            LOGGER.warn(
                "Record has JsonProcessingException offset:{}, topic:{}",
                kafkaSinkRecord.kafkaOffset(),
                kafkaSinkRecord.topic());
            kafkaRecordErrorReporter.reportError(kafkaSinkRecord, e);
          }
        }
      }
      LOGGER.debug(
          "Get rows for streaming ingest. {} records, {} bytes, offset {} - {}",
          getNumOfRecords(),
          getBufferSizeBytes(),
          getFirstOffset(),
          getLastOffset());
      return new Pair<>(records, offsets);
    }

    @Override
    public List<SinkRecord> getSinkRecords() {
      return sinkRecords;
    }

    public SinkRecord getSinkRecord(long idx) {
      return sinkRecords.get((int) idx);
    }
  }

  /**
   * Enum representing which Streaming API is invoking the fallback supplier. ({@link
   * #streamingApiFallbackSupplier(StreamingApiFallbackInvoker)})
   *
   * <p>Fallback supplier is essentially reopening the channel and resetting the kafka offset to
   * offset found in Snowflake.
   */
  private enum StreamingApiFallbackInvoker {
    /**
     * Fallback invoked when {@link SnowflakeStreamingIngestChannel#insertRows(Iterable, String)}
     * has failures.
     */
    INSERT_ROWS_FALLBACK,

    /**
     * Fallback invoked when {@link SnowflakeStreamingIngestChannel#getLatestCommittedOffsetToken()}
     * has failures.
     */
    GET_OFFSET_TOKEN_FALLBACK,

    /** Fallback invoked when schema evolution kicks in during insert rows */
    INSERT_ROWS_SCHEMA_EVOLUTION_FALLBACK,
    ;

    /** @return Used to LOG which API tried to invoke fallback function. */
    @Override
    public String toString() {
      return "[" + this.name() + "]";
    }
  }
}
