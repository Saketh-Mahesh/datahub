package com.linkedin.metadata.entity.ebean;

import com.codahale.metrics.MetricRegistry;
import com.datahub.util.exception.ModelConversionException;
import com.datahub.util.exception.RetryLimitReached;
import com.linkedin.common.AuditStamp;
import com.linkedin.common.urn.Urn;
import com.linkedin.metadata.entity.AspectDao;
import com.linkedin.metadata.entity.AspectMigrationsDao;
import com.linkedin.metadata.entity.EntityAspect;
import com.linkedin.metadata.entity.EntityAspectIdentifier;
import com.linkedin.metadata.entity.ListResult;
import com.linkedin.metadata.entity.restoreindices.RestoreIndicesArgs;
import com.linkedin.metadata.query.ExtraInfo;
import com.linkedin.metadata.query.ExtraInfoArray;
import com.linkedin.metadata.query.ListResultMetadata;
import com.linkedin.metadata.search.utils.QueryUtils;
import com.linkedin.metadata.utils.metrics.MetricUtils;
import io.ebean.DuplicateKeyException;
import io.ebean.EbeanServer;
import io.ebean.ExpressionList;
import io.ebean.Junction;
import io.ebean.PagedList;
import io.ebean.Query;
import io.ebean.RawSql;
import io.ebean.RawSqlBuilder;
import io.ebean.Transaction;
import io.ebean.TxScope;
import io.ebean.annotation.TxIsolation;
import io.ebean.annotation.Platform;
import io.ebean.config.dbplatform.DatabasePlatform;
import io.ebean.plugin.SpiServer;
import java.net.URISyntaxException;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.persistence.RollbackException;
import javax.persistence.PersistenceException;
import javax.persistence.Table;
import lombok.extern.slf4j.Slf4j;

import static com.linkedin.metadata.Constants.ASPECT_LATEST_VERSION;

@Slf4j
public class EbeanAspectDao implements AspectDao, AspectMigrationsDao {

  private final EbeanServer _server;
  private boolean _connectionValidated = false;
  private final Clock _clock = Clock.systemUTC();

  // Flag used to make sure the dao isn't writing aspects
  // while its storage is being migrated
  private boolean _canWrite = true;

  // Why 375? From tuning, this seems to be about the largest size we can get without having ebean batch issues.
  // This may be able to be moved up, 375 is a bit conservative. However, we should be careful to tweak this without
  // more testing.
  private int _queryKeysCount = 375; // 0 means no pagination on keys

  public EbeanAspectDao(@Nonnull final EbeanServer server) {
    _server = server;
  }

  @Override
  public void setWritable(boolean canWrite) {
    _canWrite = canWrite;
  }

  /**
   * Return the {@link EbeanServer} server instance used for customized queries.
   * Only used in tests.
   */
  public EbeanServer getServer() {
    return _server;
  }

  public void setConnectionValidated(boolean validated) {
    _connectionValidated = validated;
    _canWrite = validated;
  }

  private boolean validateConnection() {
    if (_connectionValidated) {
      return true;
    }
    if (!AspectStorageValidationUtil.checkV2TableExists(_server)) {
      log.error("GMS is on a newer version than your storage layer. Please refer to "
          + "https://datahubproject.io/docs/advanced/no-code-upgrade to view the upgrade guide.");
      _canWrite = false;
      return false;
    } else {
      _connectionValidated = true;
      return true;
    }
  }


  @Override
  public long saveLatestAspect(
      @Nonnull final String urn,
      @Nonnull final String aspectName,
      @Nullable final String oldAspectMetadata,
      @Nullable final String oldActor,
      @Nullable final String oldImpersonator,
      @Nullable final Timestamp oldTime,
      @Nullable final String oldSystemMetadata,
      @Nonnull final String newAspectMetadata,
      @Nonnull final String newActor,
      @Nullable final String newImpersonator,
      @Nonnull final Timestamp newTime,
      @Nullable final String newSystemMetadata,
      final Long nextVersion
  ) {

    validateConnection();
    if (!_canWrite) {
      return 0;
    }
    // Save oldValue as the largest version + 1
    long largestVersion = ASPECT_LATEST_VERSION;
    if (oldAspectMetadata != null && oldTime != null) {
      largestVersion = nextVersion;
      saveAspect(urn, aspectName, oldAspectMetadata, oldActor, oldImpersonator, oldTime, oldSystemMetadata, largestVersion, true);
    }

    // Save newValue as the latest version (v0)
    saveAspect(urn, aspectName, newAspectMetadata, newActor, newImpersonator, newTime, newSystemMetadata, ASPECT_LATEST_VERSION, oldAspectMetadata == null);

    return largestVersion;
  }

  @Override
  public void saveAspect(
      @Nonnull final String urn,
      @Nonnull final String aspectName,
      @Nonnull final String aspectMetadata,
      @Nonnull final String actor,
      @Nullable final String impersonator,
      @Nonnull final Timestamp timestamp,
      @Nonnull final String systemMetadata,
      final long version,
      final boolean insert) {

    validateConnection();

    final EbeanAspectV2 aspect = new EbeanAspectV2();
    aspect.setKey(new EbeanAspectV2.PrimaryKey(urn, aspectName, version));
    aspect.setMetadata(aspectMetadata);
    aspect.setSystemMetadata(systemMetadata);
    aspect.setCreatedOn(timestamp);
    aspect.setCreatedBy(actor);
    if (impersonator != null) {
      aspect.setCreatedFor(impersonator);
    }

    saveEbeanAspect(aspect, insert);
  }

  @Override
  public void saveAspect(@Nonnull final EntityAspect aspect, final boolean insert) {
    EbeanAspectV2 ebeanAspect = EbeanAspectV2.fromEntityAspect(aspect);
    saveEbeanAspect(ebeanAspect, insert);
  }

  private void saveEbeanAspect(@Nonnull final EbeanAspectV2 ebeanAspect, final boolean insert) {
    validateConnection();
    if (insert) {
      _server.insert(ebeanAspect);
    } else {
      _server.update(ebeanAspect);
    }
  }

  @Override
  @Nullable
  public EntityAspect getLatestAspect(@Nonnull final String urn, @Nonnull final String aspectName) {
    validateConnection();
    final EbeanAspectV2.PrimaryKey key = new EbeanAspectV2.PrimaryKey(urn, aspectName, ASPECT_LATEST_VERSION);
    EbeanAspectV2 ebeanAspect = _server.find(EbeanAspectV2.class, key);
    return ebeanAspect == null ? null : ebeanAspect.toEntityAspect();
  }

  @Override
  public long getMaxVersion(@Nonnull final String urn, @Nonnull final String aspectName) {
    validateConnection();
    List<EbeanAspectV2> result = _server.find(EbeanAspectV2.class)
        .where()
        .eq("urn", urn)
        .eq("aspect", aspectName)
        .orderBy()
        .desc("version")
        .findList();
    if (result.size() == 0) {
      return -1;
    }
    return result.get(0).getKey().getVersion();
  }

  @Override
  public long countEntities() {
    validateConnection();
    return _server.find(EbeanAspectV2.class)
        .setDistinct(true)
        .select(EbeanAspectV2.URN_COLUMN)
        .findCount();
  }

  @Override
  public boolean checkIfAspectExists(@Nonnull String aspectName) {
    validateConnection();
    return _server.find(EbeanAspectV2.class)
        .where()
        .eq(EbeanAspectV2.ASPECT_COLUMN, aspectName)
        .exists();
  }

  @Override
  @Nullable
  public EntityAspect getAspect(@Nonnull final String urn, @Nonnull final String aspectName, final long version) {
    return getAspect(new EntityAspectIdentifier(urn, aspectName, version));
  }

  @Override
  @Nullable
  public EntityAspect getAspect(@Nonnull final EntityAspectIdentifier key) {
    validateConnection();
    EbeanAspectV2.PrimaryKey primaryKey = new EbeanAspectV2.PrimaryKey(key.getUrn(), key.getAspect(), key.getVersion());
    EbeanAspectV2 ebeanAspect = _server.find(EbeanAspectV2.class, primaryKey);
    return ebeanAspect == null ? null : ebeanAspect.toEntityAspect();
  }

  @Override
  public void deleteAspect(@Nonnull final EntityAspect aspect) {
    validateConnection();
    EbeanAspectV2 ebeanAspect = EbeanAspectV2.fromEntityAspect(aspect);
    _server.delete(ebeanAspect);
  }

  @Override
  public int deleteUrn(@Nonnull final String urn) {
    validateConnection();
    return _server.createQuery(EbeanAspectV2.class).where().eq(EbeanAspectV2.URN_COLUMN, urn).delete();
  }

  @Override
  @Nonnull
  public Map<EntityAspectIdentifier, EntityAspect> batchGet(@Nonnull final Set<EntityAspectIdentifier> keys) {
    validateConnection();
    if (keys.isEmpty()) {
      return Collections.emptyMap();
    }

    final Set<EbeanAspectV2.PrimaryKey> ebeanKeys = keys.stream().map(EbeanAspectV2.PrimaryKey::fromAspectIdentifier).collect(Collectors.toSet());
    final List<EbeanAspectV2> records;
    if (_queryKeysCount == 0) {
      records = batchGet(ebeanKeys, ebeanKeys.size());
    } else {
      records = batchGet(ebeanKeys, _queryKeysCount);
    }
    return records.stream().collect(Collectors.toMap(record -> record.getKey().toAspectIdentifier(), EbeanAspectV2::toEntityAspect));
  }

  /**
   * BatchGet that allows pagination on keys to avoid large queries.
   * TODO: can further improve by running the sub queries in parallel
   *
   * @param keys a set of keys with urn, aspect and version
   * @param keysCount the max number of keys for each sub query
   */
  @Nonnull
  private List<EbeanAspectV2> batchGet(@Nonnull final Set<EbeanAspectV2.PrimaryKey> keys, final int keysCount) {
    validateConnection();

    int position = 0;

    final int totalPageCount = QueryUtils.getTotalPageCount(keys.size(), keysCount);
    final List<EbeanAspectV2> finalResult = batchGetUnion(new ArrayList<>(keys), keysCount, position);

    while (QueryUtils.hasMore(position, keysCount, totalPageCount)) {
      position += keysCount;
      final List<EbeanAspectV2> oneStatementResult = batchGetUnion(new ArrayList<>(keys), keysCount, position);
      finalResult.addAll(oneStatementResult);
    }

    return finalResult;
  }

  /**
   * Builds a single SELECT statement for batch get, which selects one entity, and then can be UNION'd with other SELECT
   * statements.
   */
  private String batchGetSelect(
      final int selectId,
      @Nonnull final String urn,
      @Nonnull final String aspect,
      final long version,
      @Nonnull final Map<String, Object> outputParamsToValues) {
    validateConnection();

    final String urnArg = "urn" + selectId;
    final String aspectArg = "aspect" + selectId;
    final String versionArg = "version" + selectId;

    outputParamsToValues.put(urnArg, urn);
    outputParamsToValues.put(aspectArg, aspect);
    outputParamsToValues.put(versionArg, version);

    return String.format("SELECT urn, aspect, version, metadata, systemMetadata, createdOn, createdBy, createdFor "
            + "FROM %s WHERE urn = :%s AND aspect = :%s AND version = :%s",
        EbeanAspectV2.class.getAnnotation(Table.class).name(), urnArg, aspectArg, versionArg);
  }

  @Nonnull
  private List<EbeanAspectV2> batchGetUnion(
      @Nonnull final List<EbeanAspectV2.PrimaryKey> keys,
      final int keysCount,
      final int position) {
    validateConnection();

    // Build one SELECT per key and then UNION ALL the results. This can be much more performant than OR'ing the
    // conditions together. Our query will look like:
    //   SELECT * FROM metadata_aspect WHERE urn = 'urn0' AND aspect = 'aspect0' AND version = 0
    //   UNION ALL
    //   SELECT * FROM metadata_aspect WHERE urn = 'urn0' AND aspect = 'aspect1' AND version = 0
    //   ...
    // Note: UNION ALL should be safe and more performant than UNION. We're selecting the entire entity key (as well
    // as data), so each result should be unique. No need to deduplicate.
    // Another note: ebean doesn't support UNION ALL, so we need to manually build the SQL statement ourselves.
    final StringBuilder sb = new StringBuilder();
    final int end = Math.min(keys.size(), position + keysCount);
    final Map<String, Object> params = new HashMap<>();
    for (int index = position; index < end; index++) {
      sb.append(batchGetSelect(
          index - position,
          keys.get(index).getUrn(),
          keys.get(index).getAspect(),
          keys.get(index).getVersion(),
          params));

      if (index != end - 1) {
        sb.append(" UNION ALL ");
      }
    }

    final RawSql rawSql = RawSqlBuilder.parse(sb.toString())
        .columnMapping(EbeanAspectV2.URN_COLUMN, "key.urn")
        .columnMapping(EbeanAspectV2.ASPECT_COLUMN, "key.aspect")
        .columnMapping(EbeanAspectV2.VERSION_COLUMN, "key.version")
        .create();

    final Query<EbeanAspectV2> query = _server.find(EbeanAspectV2.class).setRawSql(rawSql);

    for (Map.Entry<String, Object> param : params.entrySet()) {
      query.setParameter(param.getKey(), param.getValue());
    }

    return query.findList();
  }

  @Override
  @Nonnull
  public ListResult<String> listUrns(
      @Nonnull final String entityName,
      @Nonnull final String aspectName,
      final int start,
      final int pageSize) {

    validateConnection();

    final String urnPrefixMatcher = "urn:li:" + entityName + ":%";
    final PagedList<EbeanAspectV2> pagedList = _server.find(EbeanAspectV2.class)
        .select(EbeanAspectV2.KEY_ID)
        .where()
        .like(EbeanAspectV2.URN_COLUMN, urnPrefixMatcher)
        .eq(EbeanAspectV2.ASPECT_COLUMN, aspectName)
        .eq(EbeanAspectV2.VERSION_COLUMN, ASPECT_LATEST_VERSION)
        .setFirstRow(start)
        .setMaxRows(pageSize)
        .orderBy()
        .asc(EbeanAspectV2.URN_COLUMN)
        .findPagedList();

    final List<String> urns = pagedList
        .getList()
        .stream()
        .map(entry -> entry.getKey().getUrn())
        .collect(Collectors.toList());

    return toListResult(urns, null, pagedList, start);
  }

  @Nonnull
  @Override
  public Integer countAspect(@Nonnull String aspectName, @Nullable String urnLike) {
    ExpressionList<EbeanAspectV2> exp = _server.find(EbeanAspectV2.class)
            .select(EbeanAspectV2.KEY_ID)
            .where()
            .eq(EbeanAspectV2.VERSION_COLUMN, ASPECT_LATEST_VERSION)
            .eq(EbeanAspectV2.ASPECT_COLUMN, aspectName);

    if (urnLike != null) {
      exp = exp.like(EbeanAspectV2.URN_COLUMN, urnLike);
    }
    return exp.findCount();
  }

  @Nonnull
  @Override
  public PagedList<EbeanAspectV2> getPagedAspects(final RestoreIndicesArgs args) {
    ExpressionList<EbeanAspectV2> exp = _server.find(EbeanAspectV2.class)
            .select(EbeanAspectV2.ALL_COLUMNS)
            .where()
            .eq(EbeanAspectV2.VERSION_COLUMN, ASPECT_LATEST_VERSION);
    if (args.aspectName != null) {
      exp = exp.eq(EbeanAspectV2.ASPECT_COLUMN, args.aspectName);
    }
    if (args.urn != null) {
      exp = exp.eq(EbeanAspectV2.URN_COLUMN, args.urn);
    }
    if (args.urnLike != null) {
      exp = exp.like(EbeanAspectV2.URN_COLUMN, args.urnLike);
    }
    return  exp.orderBy()
            .asc(EbeanAspectV2.URN_COLUMN)
            .orderBy()
            .asc(EbeanAspectV2.ASPECT_COLUMN)
            .setFirstRow(args.start)
            .setMaxRows(args.batchSize)
            .findPagedList();
  }

  @Override
  @Nonnull
  public Iterable<String> listAllUrns(int start, int pageSize) {
    validateConnection();
    PagedList<EbeanAspectV2> ebeanAspects = _server.find(EbeanAspectV2.class)
        .setDistinct(true)
        .select(EbeanAspectV2.URN_COLUMN)
        .orderBy()
        .asc(EbeanAspectV2.URN_COLUMN)
        .setFirstRow(start)
        .setMaxRows(pageSize)
        .findPagedList();
    return ebeanAspects.getList().stream().map(EbeanAspectV2::getUrn).collect(Collectors.toList());
  }

  @Override
  @Nonnull
  public ListResult<String> listAspectMetadata(
      @Nonnull final String entityName,
      @Nonnull final String aspectName,
      final long version,
      final int start,
      final int pageSize) {

    validateConnection();

    final String urnPrefixMatcher = "urn:li:" + entityName + ":%";
    final PagedList<EbeanAspectV2> pagedList = _server.find(EbeanAspectV2.class)
        .select(EbeanAspectV2.ALL_COLUMNS)
        .where()
        .like(EbeanAspectV2.URN_COLUMN, urnPrefixMatcher)
        .eq(EbeanAspectV2.ASPECT_COLUMN, aspectName)
        .eq(EbeanAspectV2.VERSION_COLUMN, version)
        .setFirstRow(start)
        .setMaxRows(pageSize)
        .orderBy()
        .asc(EbeanAspectV2.URN_COLUMN)
        .findPagedList();

    final List<String> aspects = pagedList.getList().stream().map(EbeanAspectV2::getMetadata).collect(Collectors.toList());
    final ListResultMetadata listResultMetadata = toListResultMetadata(pagedList.getList().stream().map(
        EbeanAspectDao::toExtraInfo).collect(Collectors.toList()));
    return toListResult(aspects, listResultMetadata, pagedList, start);
  }

  @Override
  @Nonnull
  public ListResult<String> listLatestAspectMetadata(
      @Nonnull final String entityName,
      @Nonnull final String aspectName,
      final int start,
      final int pageSize) {

    return listAspectMetadata(entityName, aspectName, ASPECT_LATEST_VERSION, start, pageSize);
  }

  @Override
  @Nonnull
  public <T> T runInTransactionWithRetry(@Nonnull final Supplier<T> block, final int maxTransactionRetry) {
    validateConnection();
    int retryCount = 0;
    Exception lastException;

    T result = null;
    do {
      try (Transaction transaction = _server.beginTransaction(TxScope.requiresNew().setIsolation(TxIsolation.REPEATABLE_READ))) {
        transaction.setBatchMode(true);
        result = block.get();
        transaction.commit();
        lastException = null;
        break;
      } catch (RollbackException | DuplicateKeyException exception) {
        MetricUtils.counter(MetricRegistry.name(this.getClass(), "txFailed")).inc();
        lastException = exception;
      } catch (PersistenceException exception) {
        MetricUtils.counter(MetricRegistry.name(this.getClass(), "txFailed")).inc();
        // TODO: replace this logic by catching SerializableConflictException above once the exception is available
        SpiServer pluginApi = _server.getPluginApi();
        DatabasePlatform databasePlatform = pluginApi.getDatabasePlatform();

        if (databasePlatform.isPlatform(Platform.POSTGRES)) {
          Throwable cause = exception.getCause();
          if (cause instanceof SQLException) {
            SQLException sqlException = (SQLException) cause;
            String sqlState = sqlException.getSQLState();
            while (sqlState == null && sqlException.getCause() instanceof SQLException) {
              sqlException = (SQLException) sqlException.getCause();
              sqlState = sqlException.getSQLState();
            }

            // version 11.33.3 of io.ebean does not have a SerializableConflictException (will be available with version 11.44.1),
            // therefore when using a PostgreSQL database we have to check the SQL state 40001 here to retry the transactions
            // also in case of serialization errors ("could not serialize access due to concurrent update")
            if (sqlState.equals("40001")) {
              lastException = exception;
              continue;
            }
          }
        }

        throw exception;
      }
    } while (++retryCount <= maxTransactionRetry);

    if (lastException != null) {
      MetricUtils.counter(MetricRegistry.name(this.getClass(), "txFailedAfterRetries")).inc();
      throw new RetryLimitReached("Failed to add after " + maxTransactionRetry + " retries", lastException);
    }

    return result;
  }

  @Override
  public long getNextVersion(@Nonnull final String urn, @Nonnull final String aspectName) {
    validateConnection();
    final List<EbeanAspectV2.PrimaryKey> result = _server.find(EbeanAspectV2.class)
        .where()
        .eq(EbeanAspectV2.URN_COLUMN, urn.toString())
        .eq(EbeanAspectV2.ASPECT_COLUMN, aspectName)
        .orderBy()
        .desc(EbeanAspectV2.VERSION_COLUMN)
        .setMaxRows(1)
        .findIds();

    return result.isEmpty() ? 0 : result.get(0).getVersion() + 1L;
  }

  @Override
  public Map<String, Long> getNextVersions(@Nonnull final String urn, @Nonnull final Set<String> aspectNames) {
    validateConnection();
    Map<String, Long> result = new HashMap<>();
    Junction<EbeanAspectV2> queryJunction = _server.find(EbeanAspectV2.class)
        .select("aspect, max(version)")
        .where()
        .eq("urn", urn)
        .or();

    ExpressionList<EbeanAspectV2> exp = null;
    for (String aspectName: aspectNames) {
      if (exp == null) {
        exp = queryJunction.eq("aspect", aspectName);
      } else {
        exp = exp.eq("aspect", aspectName);
      }
    }
    if (exp == null) {
      return result;
    }
    // Order by ascending version so that the results are correctly populated.
    // TODO: Improve the below logic to be more explicit.
    exp.orderBy().asc(EbeanAspectV2.VERSION_COLUMN);
    List<EbeanAspectV2.PrimaryKey> dbResults = exp.endOr().findIds();

    for (EbeanAspectV2.PrimaryKey key: dbResults) {
      result.put(key.getAspect(), key.getVersion());
    }

    for (String aspectName: aspectNames) {
      long nextVal = ASPECT_LATEST_VERSION;
      if (result.containsKey(aspectName)) {
        nextVal = result.get(aspectName) + 1L;
      }
      result.put(aspectName, nextVal);
    }
    return result;
  }

  @Nonnull
  private <T> ListResult<T> toListResult(
      @Nonnull final List<T> values,
      @Nullable final ListResultMetadata listResultMetadata,
      @Nonnull final PagedList<?> pagedList,
      @Nullable final Integer start) {
    final int nextStart =
        (start != null && pagedList.hasNext()) ? start + pagedList.getList().size() : ListResult.INVALID_NEXT_START;
    return ListResult.<T>builder()
        // Format
        .values(values)
        .metadata(listResultMetadata)
        .nextStart(nextStart)
        .hasNext(pagedList.hasNext())
        .totalCount(pagedList.getTotalCount())
        .totalPageCount(pagedList.getTotalPageCount())
        .pageSize(pagedList.getPageSize())
        .build();
  }

  @Nonnull
  private static ExtraInfo toExtraInfo(@Nonnull final EbeanAspectV2 aspect) {
    final ExtraInfo extraInfo = new ExtraInfo();
    extraInfo.setVersion(aspect.getKey().getVersion());
    extraInfo.setAudit(toAuditStamp(aspect));
    try {
      extraInfo.setUrn(Urn.createFromString(aspect.getKey().getUrn()));
    } catch (URISyntaxException e) {
      throw new ModelConversionException(e.getMessage());
    }

    return extraInfo;
  }

  @Nonnull
  private static AuditStamp toAuditStamp(@Nonnull final EbeanAspectV2 aspect) {
    final AuditStamp auditStamp = new AuditStamp();
    auditStamp.setTime(aspect.getCreatedOn().getTime());

    try {
      auditStamp.setActor(new Urn(aspect.getCreatedBy()));
      if (aspect.getCreatedFor() != null) {
        auditStamp.setImpersonator(new Urn(aspect.getCreatedFor()));
      }
    } catch (URISyntaxException e) {
      throw new RuntimeException(e);
    }
    return auditStamp;
  }

  @Nonnull
  private ListResultMetadata toListResultMetadata(@Nonnull final List<ExtraInfo> extraInfos) {
    final ListResultMetadata listResultMetadata = new ListResultMetadata();
    listResultMetadata.setExtraInfos(new ExtraInfoArray(extraInfos));
    return listResultMetadata;
  }

  @Override
  @Nonnull
  public List<EntityAspect> getAspectsInRange(@Nonnull Urn urn, Set<String> aspectNames, long startTimeMillis, long endTimeMillis) {
    validateConnection();
    List<EbeanAspectV2> ebeanAspects = _server.find(EbeanAspectV2.class)
        .select(EbeanAspectV2.ALL_COLUMNS)
        .where()
        .eq(EbeanAspectV2.URN_COLUMN, urn.toString())
        .in(EbeanAspectV2.ASPECT_COLUMN, aspectNames)
        .inRange(EbeanAspectV2.CREATED_ON_COLUMN, new Timestamp(startTimeMillis), new Timestamp(endTimeMillis))
        .findList();
    return ebeanAspects.stream().map(EbeanAspectV2::toEntityAspect).collect(Collectors.toList());
  }
}
