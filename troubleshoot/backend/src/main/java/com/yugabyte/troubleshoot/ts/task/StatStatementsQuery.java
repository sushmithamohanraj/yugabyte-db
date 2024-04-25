package com.yugabyte.troubleshoot.ts.task;

import static com.yugabyte.troubleshoot.ts.CommonUtils.PG_TIMESTAMP_FORMAT;
import static com.yugabyte.troubleshoot.ts.CommonUtils.SYSTEM_PLATFORM;
import static com.yugabyte.troubleshoot.ts.MetricsUtil.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.yugabyte.troubleshoot.ts.CommonUtils;
import com.yugabyte.troubleshoot.ts.logs.LogsUtil;
import com.yugabyte.troubleshoot.ts.models.*;
import com.yugabyte.troubleshoot.ts.service.*;
import com.yugabyte.troubleshoot.ts.yba.client.YBAClient;
import com.yugabyte.troubleshoot.ts.yba.client.YBAClientError;
import com.yugabyte.troubleshoot.ts.yba.models.RunQueryResult;
import io.prometheus.client.Counter;
import io.prometheus.client.Summary;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Value;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

@Component
@Configuration
@Slf4j
@Profile("!test")
public class StatStatementsQuery {

  private static final Summary UNIVERSE_PROCESS_TIME =
      buildSummary(
          "ts_pss_query_universe_process_time_millis",
          "PG Stat Statements universe processing time",
          LABEL_RESULT);

  private static final Summary NODE_PROCESS_TIME =
      buildSummary(
          "ts_pss_query_node_process_time_millis",
          "PG Stat Statements node processing time",
          LABEL_RESULT);

  private static final Counter STAT_STATEMENTS_STORED =
      buildCounter(
          "ts_pss_query_stat_statements_stored", "PG Stat Statements entries stored in TS storage");

  private static final Counter STAT_STATEMENTS_INACTIVE =
      buildCounter(
          "ts_pss_query_stat_statements_inactive",
          "PG Stat Statements entries skipped because of inactivity");

  public static final String MINIMUM_VERSION_THRESHOLD_LATENCY_HISTOGRAM_SUPPORT_2_18 =
      "2.18.1.0-b67";

  /** YBDB versions above this threshold support latency histogram. */
  public static final String MINIMUM_VERSION_THRESHOLD_LATENCY_HISTOGRAM_SUPPORT = "2.19.1.0-b80";

  static final String PG_STAT_STATEMENTS_QUERY_PART1 =
      "select now() as timestamp, dbid, datname, queryid, query, calls, total_time, rows";
  static final String PG_STAT_STATEMENTS_QUERY_LH = ", yb_latency_histogram";
  static final String PG_STAT_STATEMENTS_QUERY_PART2 =
      " from pg_catalog.pg_stat_statements left join pg_stat_database on dbid = datid"
          + " where datname not in ('template0', 'template1', 'system_platform')";

  private static final String TIMESTAMP = "timestamp";
  private static final String DB_ID = "dbid";
  private static final String DB_NAME = "datname";
  private static final String QUERY_ID = "queryid";
  private static final String QUERY = "query";
  private static final String CALLS = "calls";
  private static final String TOTAL_TIME = "total_time";
  private static final String ROWS = "rows";
  private static final String YB_LATENCY_HISTOGRAM = "yb_latency_histogram";

  final Map<UUID, UniverseProgress> universesProcessStartTime = new ConcurrentHashMap<>();

  private final Map<QueryKey, JsonNode> queryLastStats = new ConcurrentHashMap<>();
  private final UniverseMetadataService universeMetadataService;
  private final UniverseDetailsService universeDetailsService;
  private final PgStatStatementsService pgStatStatementsService;
  private final PgStatStatementsQueryService pgStatStatementsQueryService;

  private final RuntimeConfigService runtimeConfigService;

  private final ThreadPoolTaskExecutor pgStatStatementsQueryExecutor;
  private final ThreadPoolTaskExecutor pgStatStatementsNodesQueryExecutor;
  private final ObjectMapper objectMapper;

  private final YBAClient ybaClient;

  public StatStatementsQuery(
      UniverseMetadataService universeMetadataService,
      UniverseDetailsService universeDetailsService,
      PgStatStatementsService pgStatStatementsService,
      PgStatStatementsQueryService pgStatStatementsQueryService,
      RuntimeConfigService runtimeConfigService,
      ThreadPoolTaskExecutor pgStatStatementsQueryExecutor,
      ThreadPoolTaskExecutor pgStatStatementsNodesQueryExecutor,
      ObjectMapper objectMapper,
      YBAClient ybaClient) {
    this.universeMetadataService = universeMetadataService;
    this.universeDetailsService = universeDetailsService;
    this.pgStatStatementsService = pgStatStatementsService;
    this.pgStatStatementsQueryService = pgStatStatementsQueryService;
    this.runtimeConfigService = runtimeConfigService;
    this.pgStatStatementsQueryExecutor = pgStatStatementsQueryExecutor;
    this.pgStatStatementsNodesQueryExecutor = pgStatStatementsNodesQueryExecutor;
    this.objectMapper = objectMapper;
    this.ybaClient = ybaClient;
  }

  @Scheduled(
      fixedRateString = "${task.pg_stat_statements_query.period}",
      initialDelayString = "PT5S")
  public Map<UUID, UniverseProgress> processAllUniverses() {
    return LogsUtil.callWithContext(this::processAllUniversesInternal);
  }

  private Map<UUID, UniverseProgress> processAllUniversesInternal() {
    Map<UUID, UniverseProgress> result = new HashMap<>();
    for (UniverseMetadata universeMetadata : universeMetadataService.listAll()) {
      UniverseDetails details = universeDetailsService.get(universeMetadata.getId());
      if (details == null) {
        log.warn("Universe details are missing for universe {}", universeMetadata.getId());
        continue;
      }
      if (details.getUniverseDetails().isUniversePaused()) {
        log.debug("Universe {} is paused", universeMetadata.getId());
        continue;
      }
      UniverseProgress progress = universesProcessStartTime.get(universeMetadata.getId());
      if (progress != null) {
        result.put(universeMetadata.getId(), progress);
        long scheduledMillisAgo = System.currentTimeMillis() - progress.scheduleTimestamp;
        log.warn(
            "Universe {} is scheduled {} millis ago. Current status: {}",
            universeMetadata.getId(),
            scheduledMillisAgo,
            progress);
        continue;
      }
      UniverseProgress newProgress =
          new UniverseProgress().setScheduleTimestamp(System.currentTimeMillis());
      universesProcessStartTime.put(universeMetadata.getId(), newProgress);
      result.put(universeMetadata.getId(), newProgress);
      try {
        pgStatStatementsQueryExecutor.execute(
            LogsUtil.withUniverseId(
                () -> processUniverse(universeMetadata, details, newProgress),
                universeMetadata.getId()));
      } catch (Exception e) {
        log.error("Failed to schedule universe " + universeMetadata.getId(), e);
        universesProcessStartTime.remove(universeMetadata.getId());
      }
    }
    return result;
  }

  private void processUniverse(
      UniverseMetadata metadata, UniverseDetails details, UniverseProgress progress) {
    log.debug("Processing universe {}", details.getId());
    long startTime = System.currentTimeMillis();
    try {
      progress.setInProgress(true);
      progress.setStartTimestamp(System.currentTimeMillis());
      progress.setNodes(details.getUniverseDetails().getNodeDetailsSet().size());
      Duration queryActiveDuration =
          runtimeConfigService
              .getUniverseConfig(metadata)
              .getDuration(RuntimeConfigKey.PSS_QUERY_ACTIVE_PERIOD);
      Instant activeQueriesAfter =
          Instant.now().minus(queryActiveDuration.toSeconds(), ChronoUnit.SECONDS);
      Set<PgStatStatementsQueryId> activeQueries =
          pgStatStatementsQueryService.listByUniverseId(metadata.getId()).stream()
              .filter(
                  query ->
                      query.getLastActive() != null
                          && query.getLastActive().isAfter(activeQueriesAfter))
              .map(PgStatStatementsQuery::getId)
              .collect(Collectors.toSet());

      Map<String, Future<NodeProcessResult>> results = new HashMap<>();
      for (UniverseDetails.UniverseDefinition.NodeDetails node :
          details.getUniverseDetails().getNodeDetailsSet()) {
        results.put(
            node.getNodeName(),
            pgStatStatementsNodesQueryExecutor.submit(
                LogsUtil.wrapCallable(
                    () -> processNode(metadata, details, node, progress, activeQueries))));
      }
      Map<QueryKey, QueryData> combinedQueries = new HashMap<>();
      for (Map.Entry<String, Future<NodeProcessResult>> resultEntry : results.entrySet()) {
        try {
          NodeProcessResult result = resultEntry.getValue().get();
          if (result.isSuccess()) {
            progress.nodesSuccessful++;
            result
                .getQueries()
                .entrySet()
                .forEach(
                    entry -> {
                      QueryKey keyWithoutNode =
                          new QueryKey(
                              entry.getKey().getUniverseId(),
                              null,
                              entry.getKey().getDatabaseId(),
                              entry.getKey().getQueryId());
                      QueryData existingData = combinedQueries.get(keyWithoutNode);
                      if (existingData != null && !existingData.equals(entry.getValue())) {
                        log.warn(
                            "Query data if different from different DB nodes: {} vs {} for key {}",
                            existingData,
                            entry.getValue(),
                            entry.getKey());
                        return;
                      }
                      combinedQueries.put(keyWithoutNode, entry.getValue());
                    });
          } else {
            progress.nodesFailed++;
          }
        } catch (Exception e) {
          progress.nodesFailed++;
          log.error("Failed to retrieve pg_stat_statements for node {}", resultEntry.getKey(), e);
        }
      }
      List<PgStatStatementsQuery> pgStatStatementsQueries =
          combinedQueries.entrySet().stream()
              .map(
                  e ->
                      new PgStatStatementsQuery()
                          .setScheduledTimestamp(Instant.ofEpochMilli(progress.scheduleTimestamp))
                          .setId(
                              new PgStatStatementsQueryId()
                                  .setUniverseId(metadata.getId())
                                  .setDbId(e.getKey().getDatabaseId())
                                  .setQueryId(e.getKey().getQueryId()))
                          .setDbName(e.getValue().getDbName())
                          .setQuery(e.getValue().getQuery())
                          .setLastActive(e.getValue().getActiveTimestamp()))
              .toList();
      pgStatStatementsQueryService.save(pgStatStatementsQueries);
      universesProcessStartTime.remove(details.getId());
      UNIVERSE_PROCESS_TIME.labels(RESULT_SUCCESS).observe(System.currentTimeMillis() - startTime);
      log.info("Processed universe {}", metadata.getId());
    } catch (Exception e) {
      UNIVERSE_PROCESS_TIME.labels(RESULT_FAILURE).observe(System.currentTimeMillis() - startTime);
      log.info("Failed to process universe universe " + metadata.getId(), e);
    } finally {
      progress.inProgress = false;
    }

    universesProcessStartTime.remove(metadata.getId());
  }

  private NodeProcessResult processNode(
      UniverseMetadata metadata,
      UniverseDetails details,
      UniverseDetails.UniverseDefinition.NodeDetails node,
      UniverseProgress progress,
      Set<PgStatStatementsQueryId> activeQueries) {
    Long startTime = System.currentTimeMillis();
    try {
      NodeProcessResult nodeResult = new NodeProcessResult(true);
      String ybSoftwareVersion =
          details.getUniverseDetails().getClusters().stream()
              .filter(cl -> cl.getUuid().equals(node.getPlacementUuid()))
              .map(UniverseDetails.UniverseDefinition.Cluster::getUserIntent)
              .map(UniverseDetails.UniverseDefinition.UserIntent::getYbSoftwareVersion)
              .findFirst()
              .orElse(null);
      boolean latencyHistogramSupported = false;
      if (ybSoftwareVersion == null) {
        log.warn("Unknown YB software version for node {}", node.getNodeName());
      } else {
        latencyHistogramSupported =
            CommonUtils.isReleaseEqualOrAfter(
                    MINIMUM_VERSION_THRESHOLD_LATENCY_HISTOGRAM_SUPPORT, ybSoftwareVersion)
                || CommonUtils.isReleaseBetween(
                    MINIMUM_VERSION_THRESHOLD_LATENCY_HISTOGRAM_SUPPORT_2_18,
                    "2.19.0.0_b0",
                    ybSoftwareVersion);
      }

      String query = PG_STAT_STATEMENTS_QUERY_PART1;
      if (latencyHistogramSupported) {
        query += PG_STAT_STATEMENTS_QUERY_LH;
      }
      query += PG_STAT_STATEMENTS_QUERY_PART2;
      RunQueryResult result =
          ybaClient.runSqlQuery(metadata, SYSTEM_PLATFORM, query, node.getNodeName());

      List<PgStatStatements> statStatementsList = new ArrayList<>();
      if (CollectionUtils.isEmpty(result.getResult())) {
        return nodeResult;
      }
      for (JsonNode statsJson : result.getResult()) {
        String dbId = statsJson.get(DB_ID).asText();
        String dbName = statsJson.get(DB_NAME).asText();
        long queryId = statsJson.get(QUERY_ID).asLong();
        String queryText = statsJson.get(QUERY).asText();
        QueryKey key = new QueryKey(metadata.getId(), node.getNodeName(), dbId, queryId);
        JsonNode previousStats = queryLastStats.get(key);
        Instant activeTimestamp = null;
        if (previousStats != null) {
          Instant oldTimestamp =
              OffsetDateTime.parse(previousStats.get(TIMESTAMP).textValue(), PG_TIMESTAMP_FORMAT)
                  .toInstant();
          Instant newTimestamp =
              OffsetDateTime.parse(statsJson.get(TIMESTAMP).textValue(), PG_TIMESTAMP_FORMAT)
                  .toInstant();
          PgStatStatements statStatements =
              new PgStatStatements()
                  .setUniverseId(metadata.getId())
                  .setNodeName(node.getNodeName())
                  .setActualTimestamp(newTimestamp)
                  .setScheduledTimestamp(Instant.ofEpochMilli(progress.scheduleTimestamp))
                  .setDbId(dbId)
                  .setQueryId(statsJson.get(QUERY_ID).asLong());
          fillStats(
              previousStats,
              statsJson,
              Duration.between(oldTimestamp, newTimestamp),
              statStatements);
          if (statStatements.getRps() > 0) {
            activeTimestamp = statStatements.getActualTimestamp();
            statStatementsList.add(statStatements);
          } else if (activeQueries.contains(
              new PgStatStatementsQueryId()
                  .setUniverseId(statStatements.getUniverseId())
                  .setDbId(statStatements.getDbId())
                  .setQueryId(statStatements.getQueryId()))) {
            statStatementsList.add(statStatements);
          } else {
            STAT_STATEMENTS_INACTIVE.inc();
          }
        }
        QueryData queryData = new QueryData(dbName, queryText, activeTimestamp);
        nodeResult.getQueries().put(key, queryData);
        queryLastStats.put(key, statsJson);
      }
      pgStatStatementsService.save(statStatementsList);
      STAT_STATEMENTS_STORED.inc(statStatementsList.size());
      NODE_PROCESS_TIME.labels(RESULT_SUCCESS).observe(System.currentTimeMillis() - startTime);
      return nodeResult;
    } catch (YBAClientError error) {
      NODE_PROCESS_TIME.labels(RESULT_FAILURE).observe(System.currentTimeMillis() - startTime);
      log.warn(
          "Failed to retrieve pg_stat_statements for node {} - {}",
          node.getNodeName(),
          error.getError());
    } catch (Exception e) {
      NODE_PROCESS_TIME.labels(RESULT_FAILURE).observe(System.currentTimeMillis() - startTime);
      log.warn("Failed to retrieve pg_stat_statements for node {}", node.getNodeName(), e);
    }
    return new NodeProcessResult(false);
  }

  private void fillStats(
      JsonNode oldValue, JsonNode newValue, Duration duration, PgStatStatements stats)
      throws IOException {

    long oldCalls = oldValue.get(CALLS).asLong();
    long newCalls = newValue.get(CALLS).asLong();
    double oldTime = oldValue.get(TOTAL_TIME).asDouble();
    double newTime = newValue.get(TOTAL_TIME).asDouble();
    long oldRows = oldValue.get(ROWS).asLong();
    long newRows = newValue.get(ROWS).asLong();
    long calls = newCalls;
    Map<String, Long> oldHistogramMap = new HashMap<>();
    if (newCalls < oldCalls || newTime < oldTime || newRows < oldRows) {
      // This is stats reset scenario
      stats.setRps((double) newCalls / duration.toSeconds());
      stats.setRowsAvg((double) newRows / newCalls);
      stats.setAvgLatency(newTime / newCalls);
    } else {
      calls = newCalls - oldCalls;
      stats.setRps((double) (newCalls - oldCalls) / duration.toSeconds());
      stats.setRowsAvg((double) (newRows - oldRows) / calls);
      stats.setAvgLatency((newTime - oldTime) / calls);
      // Only read old values in case it's not a reset
      if (calls > 0 && oldValue.has(YB_LATENCY_HISTOGRAM)) {
        List<HistogramInterval> oldHistogram = readHistogram(oldValue);
        oldHistogramMap =
            oldHistogram.stream()
                .collect(
                    Collectors.toMap(HistogramInterval::getBounds, HistogramInterval::getCount));
      }
    }
    if (calls <= 0) {
      // Don't need to calculate latencies as they're NaN
      return;
    }
    List<HistogramInterval> newHistogram = readHistogram(newValue);
    long callsCount = 0;
    for (HistogramInterval histogramInterval : newHistogram) {
      String bounds = histogramInterval.getBounds();
      Double upperBound =
          Double.valueOf(bounds.substring(bounds.indexOf(',') + 1, bounds.indexOf(')')));
      callsCount += (histogramInterval.count - oldHistogramMap.getOrDefault(bounds, 0L));
      if (stats.getMeanLatency().isNaN() && callsCount >= calls * 0.5) {
        stats.setMeanLatency(upperBound);
      }
      if (stats.getP90Latency().isNaN() && callsCount >= calls * 0.9) {
        stats.setP90Latency(upperBound);
      }
      if (stats.getP99Latency().isNaN() && callsCount >= calls * 0.99) {
        stats.setP99Latency(upperBound);
      }
      if (stats.getMaxLatency().isNaN() && callsCount == calls) {
        stats.setMaxLatency(upperBound);
      }
    }
    if (callsCount > calls) {
      log.warn(
          "Histogram value is not consistent with calls count. stats: {}, histogtam: {}",
          stats,
          newHistogram);
    }
  }

  private List<HistogramInterval> readHistogram(JsonNode jsonNode) throws IOException {
    ObjectReader histogramReader =
        objectMapper.readerFor(new TypeReference<List<Map<String, Long>>>() {});
    if (jsonNode.has(YB_LATENCY_HISTOGRAM)) {
      List<Map<String, Long>> rawHistogram =
          histogramReader.readValue(jsonNode.get(YB_LATENCY_HISTOGRAM));
      return rawHistogram.stream()
          .map(Map::entrySet)
          .flatMap(Set::stream)
          .map(entry -> new HistogramInterval(entry.getKey(), entry.getValue()))
          .toList();
    }
    return Collections.emptyList();
  }

  @Data
  @Accessors(chain = true)
  public static class UniverseProgress {
    volatile long scheduleTimestamp;
    volatile long startTimestamp;
    volatile boolean inProgress = false;
    volatile int nodes;
    volatile int nodesSuccessful;
    volatile int nodesFailed;
  }

  @Value
  private static class QueryKey {
    UUID universeId;
    String nodeName;
    String databaseId;
    long queryId;
  }

  @Value
  private static class QueryData {
    String dbName;
    String query;
    @EqualsAndHashCode.Exclude Instant activeTimestamp;
  }

  @Value
  private static class NodeProcessResult {
    boolean success;
    Map<QueryKey, QueryData> queries;

    public NodeProcessResult(boolean success) {
      this.success = success;
      this.queries = new HashMap<>();
    }
  }

  @Value
  private static class HistogramInterval {
    String bounds;
    @EqualsAndHashCode.Exclude long count;
  }
}
