package io.ebeaninternal.server.querydefn;

import io.ebean.OrderBy;
import io.ebean.OrderBy.Property;
import io.ebean.EbeanServer;
import io.ebean.Expression;
import io.ebean.ExpressionFactory;
import io.ebean.ExpressionList;
import io.ebean.FetchConfig;
import io.ebean.FetchPath;
import io.ebean.FutureIds;
import io.ebean.FutureList;
import io.ebean.FutureRowCount;
import io.ebean.PagedList;
import io.ebean.PersistenceContextScope;
import io.ebean.Query;
import io.ebean.QueryIterator;
import io.ebean.RawSql;
import io.ebean.Version;
import io.ebean.bean.CallStack;
import io.ebean.bean.ObjectGraphNode;
import io.ebean.bean.ObjectGraphOrigin;
import io.ebean.bean.PersistenceContext;
import io.ebean.event.BeanQueryRequest;
import io.ebean.event.readaudit.ReadEvent;
import io.ebean.plugin.BeanType;
import io.ebeaninternal.api.BindParams;
import io.ebeaninternal.api.CQueryPlanKey;
import io.ebeaninternal.api.HashQuery;
import io.ebeaninternal.api.ManyWhereJoins;
import io.ebeaninternal.api.SpiExpression;
import io.ebeaninternal.api.SpiExpressionList;
import io.ebeaninternal.api.SpiExpressionValidation;
import io.ebeaninternal.api.SpiNamedParam;
import io.ebeaninternal.api.SpiQuery;
import io.ebeaninternal.api.SpiQuerySecondary;
import io.ebeaninternal.server.autotune.ProfilingListener;
import io.ebeaninternal.server.deploy.BeanDescriptor;
import io.ebeaninternal.server.deploy.BeanPropertyAssocMany;
import io.ebeaninternal.server.deploy.TableJoin;
import io.ebeaninternal.server.expression.DefaultExpressionList;
import io.ebeaninternal.server.expression.SimpleExpression;
import io.ebeaninternal.server.query.CancelableQuery;
import io.ebeaninternal.server.query.NativeSqlQueryPlanKey;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Default implementation of an Object Relational query.
 */
public class DefaultOrmQuery<T> implements SpiQuery<T> {

  private static final String DEFAULT_QUERY_NAME = "default";

  private static final FetchConfig FETCH_QUERY = new FetchConfig().query();

  private static final FetchConfig FETCH_LAZY = new FetchConfig().lazy();

  private final Class<T> beanType;

  private final BeanDescriptor<T> beanDescriptor;

  private final EbeanServer server;

  private final ExpressionFactory expressionFactory;

  /**
   * For lazy loading of ManyToMany we need to add a join to the intersection table. This is that
   * join to the intersection table.
   */
  private TableJoin m2mIncludeJoin;

  private ProfilingListener profilingListener;

  private boolean cancelled;

  private CancelableQuery cancelableQuery;

  private Type type;

  private Mode mode = Mode.NORMAL;

  private Object tenantId;

  /**
   * Holds query in structured form.
   */
  private OrmQueryDetail detail;

  private int maxRows;

  private int firstRow;

  /**
   * Set to true to disable lazy loading on the object graph returned.
   */
  private boolean disableLazyLoading;

  /**
   * Lazy loading batch size (can override server wide default).
   */
  private int lazyLoadBatchSize;

  private OrderBy<T> orderBy;

  private String loadMode;

  private String loadDescription;

  private String generatedSql;

  private String lazyLoadProperty;

  private String lazyLoadManyPath;

  /**
   * Set to true by a user wanting a DISTINCT query (id property must be excluded).
   */
  private boolean distinct;

  /**
   * Set to true internally by Ebean when it needs the DISTINCT keyword added to the query (id
   * property still expected).
   */
  private boolean sqlDistinct;

  /**
   * Set to true if this is a future fetch using background threads.
   */
  private boolean futureFetch;

  /**
   * Only used for read auditing with findFutureList() query.
   */
  private ReadEvent futureFetchAudit;

  private int timeout;

  /**
   * The property used to get the key value for a Map.
   */
  private String mapKey;

  /**
   * Used for find by id type query.
   */
  private Object id;

  private Map<String, ONamedParam> namedParams;

  /**
   * Bind parameters when using the query language.
   */
  private BindParams bindParams;

  private DefaultExpressionList<T> textExpressions;

  private DefaultExpressionList<T> whereExpressions;

  private DefaultExpressionList<T> havingExpressions;

  private boolean asOfBaseTable;

  private int asOfTableCount;

  /**
   * Set for flashback style 'as of' query.
   */
  private Timestamp asOf;

  private TemporalMode temporalMode = TemporalMode.CURRENT;

  private Timestamp versionsStart;
  private Timestamp versionsEnd;

  private List<String> softDeletePredicates;

  private boolean disableReadAudit;

  private int bufferFetchSizeHint;

  private boolean usageProfiling = true;

  private boolean loadBeanCache;

  private boolean excludeBeanCache;

  private Boolean useQueryCache;

  private Boolean readOnly;

  private PersistenceContextScope persistenceContextScope;

  /**
   * Allow for explicit on off or null for default.
   */
  private Boolean autoTune;

  /**
   * Allow to fetch a record "for update" which should lock it on read
   */
  private boolean forUpdate;

  private boolean singleAttribute;

  /**
   * Set to true if this query has been tuned by autoTune.
   */
  private boolean autoTuned;

  private boolean logSecondaryQuery;

  /**
   * Root table alias. For {@link Query#alias(String)} command.
   */
  private String rootTableAlias;

  /**
   * The node of the bean or collection that fired lazy loading. Not null if profiling is on and
   * this query is for lazy loading. Used to hook back a lazy loading query to the "original" query
   * point.
   */
  private ObjectGraphNode parentNode;

  private BeanPropertyAssocMany<?> lazyLoadForParentsProperty;

  /**
   * Hash of final query after AutoTune tuning.
   */
  private CQueryPlanKey queryPlanKey;

  private PersistenceContext persistenceContext;

  private ManyWhereJoins manyWhereJoins;

  private RawSql rawSql;

  private boolean useDocStore;

  private String docIndexName;

  private OrmUpdateProperties updateProperties;

  private String nativeSql;

  public DefaultOrmQuery(BeanDescriptor<T> desc, EbeanServer server, ExpressionFactory expressionFactory) {
    this.beanDescriptor = desc;
    this.beanType = desc.getBeanType();
    this.server = server;
    this.expressionFactory = expressionFactory;
    this.detail = new OrmQueryDetail();
  }

  public void setNativeSql(String nativeSql) {
    this.nativeSql = nativeSql;
  }

  @Override
  public BeanDescriptor<T> getBeanDescriptor() {
    return beanDescriptor;
  }

  @Override
  public void checkIdEqualTo() {
    if (id == null && whereExpressions != null) {
      id = whereExpressions.idEqualTo(beanDescriptor.getIdName());
      if (id != null) {
        whereExpressions = null;
      }
    }
  }

  @Override
  public boolean isAutoTunable() {
    return nativeSql == null && beanDescriptor.isAutoTunable();
  }

  @Override
  public Query<T> setUseDocStore(boolean useDocStore) {
    this.useDocStore = useDocStore;
    return this;
  }

  @Override
  public boolean isUseDocStore() {
    return useDocStore;
  }

  @Override
  public Query<T> apply(FetchPath fetchPath) {
    fetchPath.apply(this);
    return this;
  }

  @Override
  public void addSoftDeletePredicate(String softDeletePredicate) {
    if (softDeletePredicates == null) {
      softDeletePredicates = new ArrayList<>();
    }
    softDeletePredicates.add(softDeletePredicate);
  }

  @Override
  public List<String> getSoftDeletePredicates() {
    return softDeletePredicates;
  }

  public boolean isAsOfBaseTable() {
    return asOfBaseTable;
  }

  public void setAsOfBaseTable() {
    this.asOfBaseTable = true;
  }

  @Override
  public void incrementAsOfTableCount() {
    asOfTableCount++;
  }

  @Override
  public int getAsOfTableCount() {
    return asOfTableCount;
  }

  @Override
  public Timestamp getAsOf() {
    return asOf;
  }

  @Override
  public DefaultOrmQuery<T> asOf(Timestamp asOfDateTime) {
    this.temporalMode = (asOfDateTime != null) ? TemporalMode.AS_OF : TemporalMode.CURRENT;
    this.asOf = asOfDateTime;
    return this;
  }

  @Override
  public DefaultOrmQuery<T> asDraft() {
    this.temporalMode = TemporalMode.DRAFT;
    return this;
  }

  @Override
  public Query<T> setIncludeSoftDeletes() {
    this.temporalMode = TemporalMode.SOFT_DELETED;
    return this;
  }

  @Override
  public Query<T> setDocIndexName(String indexName) {
    this.docIndexName = indexName;
    this.useDocStore = true;
    return this;
  }

  @Override
  public String getDocIndexName() {
    return docIndexName;
  }

  @Override
  public RawSql getRawSql() {
    return rawSql;
  }

  @Override
  public DefaultOrmQuery<T> setRawSql(RawSql rawSql) {
    this.rawSql = rawSql;
    return this;
  }

  @Override
  public String getOriginKey() {
    if (parentNode == null || parentNode.getOriginQueryPoint() == null) {
      return null;
    } else {
      return parentNode.getOriginQueryPoint().getKey();
    }
  }

  @Override
  public int getLazyLoadBatchSize() {
    return lazyLoadBatchSize;
  }

  @Override
  public Query<T> setLazyLoadBatchSize(int lazyLoadBatchSize) {
    this.lazyLoadBatchSize = lazyLoadBatchSize;
    return this;
  }

  @Override
  public String getLazyLoadProperty() {
    return lazyLoadProperty;
  }

  @Override
  public void setLazyLoadProperty(String lazyLoadProperty) {
    this.lazyLoadProperty = lazyLoadProperty;
  }

  @Override
  public ExpressionFactory getExpressionFactory() {
    return expressionFactory;
  }

  private void createExtraJoinsToSupportManyWhereClause() {
    manyWhereJoins = new ManyWhereJoins();
    if (whereExpressions != null) {
      whereExpressions.containsMany(beanDescriptor, manyWhereJoins);
    }
    if (havingExpressions != null) {
      havingExpressions.containsMany(beanDescriptor, manyWhereJoins);
    }
  }

  /**
   * Return the extra joins required to support the where clause for 'Many' properties.
   */
  @Override
  public ManyWhereJoins getManyWhereJoins() {
    return manyWhereJoins;
  }

  /**
   * Return true if select all properties was used to ensure the property invoking a lazy load was
   * included in the query.
   */
  @Override
  public boolean selectAllForLazyLoadProperty() {
    if (lazyLoadProperty != null) {
      if (!detail.containsProperty(lazyLoadProperty)) {
        detail.select("*");
        return true;
      }
    }
    return false;
  }

  private List<OrmQueryProperties> removeQueryJoins() {
    List<OrmQueryProperties> queryJoins = detail.removeSecondaryQueries();
    if (queryJoins != null) {
      if (orderBy != null) {
        // remove any orderBy properties that relate to
        // paths of the secondary queries
        for (OrmQueryProperties joinPath : queryJoins) {
          // loop through the orderBy properties and
          // move any ones related to the query join
          List<Property> properties = orderBy.getProperties();
          Iterator<Property> it = properties.iterator();
          while (it.hasNext()) {
            Property property = it.next();
            if (property.getProperty().startsWith(joinPath.getPath())) {
              // remove this orderBy segment and
              // add it to the secondary join
              it.remove();
              joinPath.addSecJoinOrderProperty(property);
            }
          }
        }
      }
    }
    return queryJoins;
  }

  private List<OrmQueryProperties> removeLazyJoins() {
    return detail.removeSecondaryLazyQueries();
  }

  @Override
  public void setLazyLoadManyPath(String lazyLoadManyPath) {
    this.lazyLoadManyPath = lazyLoadManyPath;
  }

  @Override
  public SpiQuerySecondary convertJoins() {
    if (!useDocStore) {
      createExtraJoinsToSupportManyWhereClause();
    }
    markQueryJoins();
    return new OrmQuerySecondary(removeQueryJoins(), removeLazyJoins());
  }

  /**
   * Limit the number of fetch joins to Many properties, mark as query joins as needed.
   */
  private void markQueryJoins() {
    detail.markQueryJoins(beanDescriptor, lazyLoadManyPath, isAllowOneManyFetch());
  }

  private boolean isAllowOneManyFetch() {

    if (Mode.LAZYLOAD_MANY.equals(getMode())) {
      return false;
    } else if (hasMaxRowsOrFirstRow() && !isRawSql()) {
      return false;
    }
    return true;
  }

  @Override
  public void setDefaultSelectClause() {
    detail.setDefaultSelectClause(beanDescriptor);
  }

  @Override
  public void setTenantId(Object tenantId) {
    this.tenantId = tenantId;
  }

  @Override
  public Object getTenantId() {
    return tenantId;
  }

  @Override
  public void setDetail(OrmQueryDetail detail) {
    this.detail = detail;
  }

  @Override
  public boolean tuneFetchProperties(OrmQueryDetail tunedDetail) {
    return detail.tuneFetchProperties(tunedDetail);
  }

  @Override
  public OrmQueryDetail getDetail() {
    return detail;
  }

  @Override
  public ExpressionList<T> filterMany(String prop) {

    OrmQueryProperties chunk = detail.getChunk(prop, true);
    return chunk.filterMany(this);
  }

  @Override
  public void setFilterMany(String prop, ExpressionList<?> filterMany) {
    if (filterMany != null) {
      OrmQueryProperties chunk = detail.getChunk(prop, true);
      chunk.setFilterMany((SpiExpressionList<?>) filterMany);
    }
  }

  @Override
  public void prepareDocNested() {
    if (textExpressions != null) {
      textExpressions.prepareDocNested(beanDescriptor);
    }
    if (whereExpressions != null) {
      whereExpressions.prepareDocNested(beanDescriptor);
    }
  }

  /**
   * Setup to be a delete query.
   */
  @Override
  public void setDelete() {
    // unset any paging and select on the id in the case where the query
    // includes joins and we use - delete ... where id in (...)
    maxRows = 0;
    firstRow = 0;
    forUpdate = false;
    rootTableAlias = "${RTA}"; // alias we remove later
    setSelectId();
  }

  /**
   * Set the select clause to select the Id property.
   */
  @Override
  public void setSelectId() {
    // clear select and fetch joins..
    detail.clear();
    select(beanDescriptor.getIdBinder().getIdProperty());
  }

  @Override
  public void setSingleAttribute() {
    this.singleAttribute = true;
  }

  /**
   * Return true if this is a single attribute query.
   */
  public boolean isSingleAttribute() {
    return singleAttribute;
  }

  /**
   * Return true if the Id should be included in the query.
   */
  @Override
  public boolean isWithId() {
    return !distinct && !singleAttribute;
  }

  @Override
  public NaturalKeyBindParam getNaturalKeyBindParam() {
    NaturalKeyBindParam namedBind = null;
    if (bindParams != null) {
      namedBind = bindParams.getNaturalKeyBindParam();
      if (namedBind == null) {
        return null;
      }
    }

    if (whereExpressions != null) {
      List<SpiExpression> exprList = whereExpressions.internalList();
      if (exprList.size() > 1) {
        return null;
      } else if (exprList.isEmpty()) {
        return namedBind;
      } else {
        if (namedBind != null) {
          return null;
        }
        SpiExpression se = exprList.get(0);
        if (se instanceof SimpleExpression) {
          SimpleExpression e = (SimpleExpression) se;
          if (e.isOpEquals()) {
            return new NaturalKeyBindParam(e.getPropName(), e.getValue());
          }
        }
      }
    }
    return null;
  }

  @Override
  public DefaultOrmQuery<T> copy() {
    return copy(server);
  }

  @Override
  public DefaultOrmQuery<T> copy(EbeanServer server) {

    DefaultOrmQuery<T> copy = new DefaultOrmQuery<>(beanDescriptor, server, expressionFactory);
    copy.m2mIncludeJoin = m2mIncludeJoin;
    copy.profilingListener = profilingListener;

//    copy.query = query;
    copy.rootTableAlias = rootTableAlias;
    copy.distinct = distinct;
    copy.sqlDistinct = sqlDistinct;
    copy.timeout = timeout;
    copy.mapKey = mapKey;
    copy.id = id;
    copy.loadBeanCache = loadBeanCache;
    copy.excludeBeanCache = excludeBeanCache;
    copy.useQueryCache = useQueryCache;
    copy.readOnly = readOnly;
    if (detail != null) {
      copy.detail = detail.copy();
    }
    copy.temporalMode = temporalMode;
    copy.firstRow = firstRow;
    copy.maxRows = maxRows;
    if (orderBy != null) {
      copy.orderBy = orderBy.copy();
    }
    if (bindParams != null) {
      copy.bindParams = bindParams.copy();
    }
    if (whereExpressions != null) {
      copy.whereExpressions = whereExpressions.copy(copy);
    }
    if (havingExpressions != null) {
      copy.havingExpressions = havingExpressions.copy(copy);
    }
    copy.persistenceContextScope = persistenceContextScope;
    copy.usageProfiling = usageProfiling;
    copy.autoTune = autoTune;
    copy.parentNode = parentNode;
    copy.forUpdate = forUpdate;
    copy.rawSql = rawSql;
    return copy;
  }

  @Override
  public Query<T> setPersistenceContextScope(PersistenceContextScope scope) {
    this.persistenceContextScope = scope;
    return this;
  }

  @Override
  public PersistenceContextScope getPersistenceContextScope() {
    return persistenceContextScope;
  }

  @Override
  public Type getType() {
    return type;
  }

  @Override
  public void setType(Type type) {
    this.type = type;
  }

  @Override
  public String getLoadDescription() {
    return loadDescription;
  }

  @Override
  public String getLoadMode() {
    return loadMode;
  }

  @Override
  public void setLoadDescription(String loadMode, String loadDescription) {
    this.loadMode = loadMode;
    this.loadDescription = loadDescription;
  }

  /**
   * Return the TransactionContext.
   * <p>
   * If no TransactionContext is present on the query then the TransactionContext from the
   * Transaction is used (transaction scoped persistence context).
   * </p>
   */
  @Override
  public PersistenceContext getPersistenceContext() {
    return persistenceContext;
  }

  /**
   * Set an explicit TransactionContext (typically for a refresh query).
   * <p>
   * If no TransactionContext is present on the query then the TransactionContext from the
   * Transaction is used (transaction scoped persistence context).
   * </p>
   */
  @Override
  public void setPersistenceContext(PersistenceContext persistenceContext) {
    this.persistenceContext = persistenceContext;
  }

  @Override
  public void setLazyLoadForParents(BeanPropertyAssocMany<?> many) {
    this.lazyLoadForParentsProperty = many;
  }

  @Override
  public BeanPropertyAssocMany<?> getLazyLoadMany() {
    return lazyLoadForParentsProperty;
  }

  /**
   * Return true if the query detail has neither select or joins specified.
   */
  @Override
  public boolean isDetailEmpty() {
    return detail.isEmpty();
  }

  @Override
  public boolean isAutoTuned() {
    return autoTuned;
  }

  @Override
  public void setAutoTuned(boolean autoTuned) {
    this.autoTuned = autoTuned;
  }

  @Override
  public Boolean isAutoTune() {
    return autoTune;
  }

  @Override
  public boolean isForUpdate() {
    return forUpdate;
  }

  @Override
  public void setDefaultRawSqlIfRequired() {
    if (beanDescriptor.isRawSqlBased() && rawSql == null) {
      rawSql = beanDescriptor.getNamedRawSql(DEFAULT_QUERY_NAME);
    }
  }

  @Override
  public DefaultOrmQuery<T> setAutoTune(boolean autoTune) {
    this.autoTune = autoTune;
    return this;
  }

  @Override
  public DefaultOrmQuery<T> setForUpdate(boolean forUpdate) {
    this.forUpdate = forUpdate;
    return this;
  }

  @Override
  public ProfilingListener getProfilingListener() {
    return profilingListener;
  }

  @Override
  public void setProfilingListener(ProfilingListener profilingListener) {
    this.profilingListener = profilingListener;
  }

  @Override
  public Mode getMode() {
    return mode;
  }

  @Override
  public TemporalMode getTemporalMode() {
    return temporalMode;
  }

  @Override
  public boolean isAsOfQuery() {
    return asOf != null;
  }

  @Override
  public boolean isAsDraft() {
    return TemporalMode.DRAFT == temporalMode;
  }

  @Override
  public boolean isIncludeSoftDeletes() {
    return TemporalMode.SOFT_DELETED == temporalMode;
  }

  @Override
  public void setMode(Mode mode) {
    this.mode = mode;
  }

  @Override
  public boolean isUsageProfiling() {
    return usageProfiling;
  }

  @Override
  public void setUsageProfiling(boolean usageProfiling) {
    this.usageProfiling = usageProfiling;
  }

  @Override
  public void setLogSecondaryQuery(boolean logSecondaryQuery) {
    this.logSecondaryQuery = logSecondaryQuery;
  }

  @Override
  public boolean isLogSecondaryQuery() {
    return logSecondaryQuery;
  }

  private List<SpiQuery<?>> loggedSecondaryQueries;

  @Override
  public List<SpiQuery<?>> getLoggedSecondaryQueries() {
    return loggedSecondaryQueries;
  }

  @Override
  public void logSecondaryQuery(SpiQuery<?> query) {
    if (loggedSecondaryQueries == null) {
      loggedSecondaryQueries = new ArrayList<>();
    }
    loggedSecondaryQueries.add(query);
  }

  @Override
  public void setParentNode(ObjectGraphNode parentNode) {
    this.parentNode = parentNode;
  }

  @Override
  public ObjectGraphNode getParentNode() {
    return parentNode;
  }

  @Override
  public ObjectGraphNode setOrigin(CallStack callStack) {

    // create a 'origin' which links this query to the profiling information
    ObjectGraphOrigin o = new ObjectGraphOrigin(calculateOriginQueryHash(), callStack, beanType.getName());
    parentNode = new ObjectGraphNode(o, null);
    return parentNode;
  }

  /**
   * Calculate a hash for use in determining the ObjectGraphOrigin.
   * <p>
   * This should be quite a stable hash as most uniqueness is determined by the CallStack, so we
   * only use the bean type and overall query type.
   * </p>
   * <p>
   * This stable hash allows the query to be changed (joins added etc) without losing the already
   * collected usage profiling.
   * </p>
   */
  private int calculateOriginQueryHash() {
    int hc = beanType.getName().hashCode();
    hc = hc * 92821 + (type == null ? 0 : type.ordinal());
    return hc;
  }

  /**
   * Calculate the query hash for either AutoTune query tuning or Query Plan caching.
   */
  CQueryPlanKey createQueryPlanKey() {

    if (isNativeSql()) {
      queryPlanKey = new NativeSqlQueryPlanKey(nativeSql);
    } else {
      queryPlanKey = new OrmQueryPlanKey(m2mIncludeJoin, type, detail, maxRows, firstRow,
        disableLazyLoading, orderBy,
        distinct, sqlDistinct, mapKey, id, bindParams, whereExpressions, havingExpressions,
        temporalMode, forUpdate, rootTableAlias, rawSql, updateProperties);
    }
    return queryPlanKey;
  }

  @Override
  public boolean isNativeSql() {
    return nativeSql != null;
  }

  @Override
  public String getNativeSql() {
    return nativeSql;
  }

  /**
   * Prepare the query which prepares any expressions (sub-query expressions etc) and calculates the query plan key.
   */
  @Override
  public CQueryPlanKey prepare(BeanQueryRequest<?> request) {

    prepareExpressions(request);
    queryPlanKey = createQueryPlanKey();
    return queryPlanKey;
  }

  /**
   * Prepare the expressions (compile sub-queries etc).
   */
  private void prepareExpressions(BeanQueryRequest<?> request) {

    if (whereExpressions != null) {
      whereExpressions.prepareExpression(request);
    }
    if (havingExpressions != null) {
      havingExpressions.prepareExpression(request);
    }
  }

  /**
   * Calculate a hash based on the bind values used in the query.
   * <p>
   * Used with queryPlanHash() to get a unique hash for a query.
   * </p>
   */
  @Override
  public int queryBindHash() {
    int hc = (id == null ? 0 : id.hashCode());
    hc = hc * 92821 + (whereExpressions == null ? 0 : whereExpressions.queryBindHash());
    hc = hc * 92821 + (havingExpressions == null ? 0 : havingExpressions.queryBindHash());
    hc = hc * 92821 + (bindParams == null ? 0 : bindParams.queryBindHash());
    hc = hc * 92821 + (asOf == null ? 0 : asOf.hashCode());
    hc = hc * 92821 + (versionsStart == null ? 0 : versionsStart.hashCode());
    hc = hc * 92821 + (versionsEnd == null ? 0 : versionsEnd.hashCode());
    return hc;
  }

  /**
   * Return a hash that includes the query plan and bind values.
   * <p>
   * This hash can be used to identify if we have executed the exact same query (including bind
   * values) before.
   * </p>
   */
  @Override
  public HashQuery queryHash() {
    // calculateQueryPlanHash is called just after potential AutoTune tuning
    // so queryPlanHash is calculated well before this method is called
    int hc = queryBindHash();

    return new HashQuery(queryPlanKey, hc);
  }

  @Override
  public boolean isRawSql() {
    return rawSql != null;
  }

  /**
   * Return the timeout.
   */
  @Override
  public int getTimeout() {
    return timeout;
  }

  @Override
  public boolean hasMaxRowsOrFirstRow() {
    return maxRows > 0 || firstRow > 0;
  }

  @Override
  public boolean isVersionsBetween() {
    return versionsStart != null;
  }

  @Override
  public Timestamp getVersionStart() {
    return versionsStart;
  }

  @Override
  public Timestamp getVersionEnd() {
    return versionsEnd;
  }

  @Override
  public Boolean isReadOnly() {
    return readOnly;
  }

  @Override
  public DefaultOrmQuery<T> setReadOnly(boolean readOnly) {
    this.readOnly = readOnly;
    return this;
  }

  public boolean isExcludeBeanCache() {
    // not using L2 cache for asDraft() query
    return excludeBeanCache || isAsDraft();
  }

  @Override
  public boolean isUseBeanCache() {
    return !isExcludeBeanCache() && beanDescriptor.isBeanCaching();
  }

  @Override
  public boolean isUseQueryCache() {
    // not using L2 cache for asDraft() query
    return !isAsDraft() && Boolean.TRUE.equals(useQueryCache);
  }

  @Override
  public DefaultOrmQuery<T> setUseCache(boolean useCache) {
    this.excludeBeanCache = !useCache;
    return this;
  }

  @Override
  public DefaultOrmQuery<T> setUseQueryCache(boolean useQueryCache) {
    this.useQueryCache = useQueryCache;
    return this;
  }

  @Override
  public boolean isLoadBeanCache() {
    // not using L2 cache for asDraft() query
    return !isAsDraft() && loadBeanCache;
  }

  @Override
  public DefaultOrmQuery<T> setLoadBeanCache(boolean loadBeanCache) {
    this.loadBeanCache = loadBeanCache;
    return this;
  }

  @Override
  public DefaultOrmQuery<T> setTimeout(int secs) {
    this.timeout = secs;
    return this;
  }

  @Override
  public DefaultOrmQuery<T> select(String columns) {
    detail.select(columns);
    return this;
  }

  @Override
  public DefaultOrmQuery<T> fetch(String property) {
    return fetch(property, null, null);
  }

  @Override
  public Query<T> fetchQuery(String property) {
    return fetch(property, null, FETCH_QUERY);
  }

  @Override
  public Query<T> fetchLazy(String property) {
    return fetch(property, null, FETCH_LAZY);
  }

  @Override
  public DefaultOrmQuery<T> fetch(String property, FetchConfig joinConfig) {
    return fetch(property, null, joinConfig);
  }

  @Override
  public DefaultOrmQuery<T> fetch(String property, String columns) {
    return fetch(property, columns, null);
  }

  @Override
  public Query<T> fetchQuery(String property, String columns) {
    return fetch(property, columns, FETCH_QUERY);
  }

  @Override
  public Query<T> fetchLazy(String property, String columns) {
    return fetch(property, columns, FETCH_LAZY);
  }

  @Override
  public DefaultOrmQuery<T> fetch(String property, String columns, FetchConfig config) {
    detail.fetch(property, columns, config);
    return this;
  }

  @Override
  public int delete() {
    return server.delete(this, null);
  }

  @Override
  public int update() {
    return server.update(this, null);
  }

  @Override
  public <A> List<A> findIds() {
    // a copy of this query is made in the server
    // as the query needs to modified (so we modify
    // the copy rather than this query instance)
    return server.findIds(this, null);
  }

  @Override
  public int findCount() {
    // a copy of this query is made in the server
    // as the query needs to modified (so we modify
    // the copy rather than this query instance)
    return server.findCount(this, null);
  }

  @Override
  public void findEachWhile(Predicate<T> consumer) {
    server.findEachWhile(this, consumer, null);
  }

  @Override
  public void findEach(Consumer<T> consumer) {
    server.findEach(this, consumer, null);
  }

  @Override
  public QueryIterator<T> findIterate() {
    return server.findIterate(this, null);
  }

  @Override
  public List<Version<T>> findVersions() {
    this.temporalMode = TemporalMode.VERSIONS;
    return server.findVersions(this, null);
  }

  @Override
  public List<Version<T>> findVersionsBetween(Timestamp start, Timestamp end) {
    if (start == null || end == null) {
      throw new IllegalArgumentException("start and end must not be null");
    }
    this.temporalMode = TemporalMode.VERSIONS;
    this.versionsStart = start;
    this.versionsEnd = end;
    return server.findVersions(this, null);
  }

  @Override
  public List<T> findList() {
    return server.findList(this, null);
  }

  @Override
  public Set<T> findSet() {
    return server.findSet(this, null);
  }

  @Override
  public <K> Map<K, T> findMap() {
    return server.findMap(this, null);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <A> List<A> findSingleAttributeList() {
    return (List<A>) server.findSingleAttributeList(this, null);
  }

  @Override
  public T findUnique() {
    return server.findUnique(this, null);
  }

  @Override
  public FutureIds<T> findFutureIds() {
    return server.findFutureIds(this, null);
  }

  @Override
  public FutureList<T> findFutureList() {
    return server.findFutureList(this, null);
  }

  @Override
  public FutureRowCount<T> findFutureCount() {
    return server.findFutureCount(this, null);
  }

  @Override
  public PagedList<T> findPagedList() {
    return server.findPagedList(this, null);
  }

  /**
   * Set an ordered bind parameter according to its position. Note that the position starts at 1 to
   * be consistent with JDBC PreparedStatement. You need to set a parameter value for each ? you
   * have in the query.
   */
  @Override
  public DefaultOrmQuery<T> setParameter(int position, Object value) {
    if (bindParams == null) {
      bindParams = new BindParams();
    }
    bindParams.setParameter(position, value);
    return this;
  }

  /**
   * Set a named bind parameter. Named parameters have a colon to prefix the name.
   */
  @Override
  public DefaultOrmQuery<T> setParameter(String name, Object value) {

    if (namedParams != null) {
      ONamedParam param = namedParams.get(name);
      if (param != null) {
        param.setValue(value);
        return this;
      }
    }

    if (bindParams == null) {
      bindParams = new BindParams();
    }
    bindParams.setParameter(name, value);
    return this;
  }

  @Override
  public OrderBy<T> getOrderBy() {
    return orderBy;
  }

  @Override
  public OrderBy<T> orderBy() {
    return order();
  }

  @Override
  public OrderBy<T> order() {
    if (orderBy == null) {
      orderBy = new OrderBy<>(this, null);
    }
    return orderBy;
  }

  @Override
  public DefaultOrmQuery<T> orderBy(String orderByClause) {
    return order(orderByClause);
  }

  @Override
  public DefaultOrmQuery<T> order(String orderByClause) {
    if (orderByClause == null || orderByClause.trim().isEmpty()) {
      this.orderBy = null;
    } else {
      this.orderBy = new OrderBy<>(this, orderByClause);
    }
    return this;
  }

  @Override
  public DefaultOrmQuery<T> setOrderBy(OrderBy<T> orderBy) {
    return setOrder(orderBy);
  }

  @Override
  public DefaultOrmQuery<T> setOrder(OrderBy<T> orderBy) {
    this.orderBy = orderBy;
    if (orderBy != null) {
      orderBy.setQuery(this);
    }
    return this;
  }

  /**
   * return true if user specified to use SQL DISTINCT (effectively excludes id property).
   */
  @Override
  public boolean isDistinct() {
    return distinct;
  }

  /**
   * Internally set to use SQL DISTINCT on the query but still have id property included.
   */
  @Override
  public DefaultOrmQuery<T> setDistinct(boolean distinct) {
    this.distinct = distinct;
    return this;
  }

  /**
   * Return true if this query uses SQL DISTINCT either explicitly by the user or internally defined
   * by ebean.
   */
  @Override
  public boolean isDistinctQuery() {
    return distinct || sqlDistinct;
  }

  @Override
  public boolean isSqlDistinct() {
    return sqlDistinct;
  }

  /**
   * Internally set to use SQL DISTINCT on the query but still have id property included.
   */
  @Override
  public void setSqlDistinct(boolean sqlDistinct) {
    this.sqlDistinct = sqlDistinct;
  }

  @Override
  public Class<T> getBeanType() {
    return beanType;
  }

  public String toString() {
    return "Query [" + whereExpressions + "]";
  }

  public TableJoin getM2mIncludeJoin() {
    return m2mIncludeJoin;
  }

  @Override
  public void setM2MIncludeJoin(TableJoin m2mIncludeJoin) {
    this.m2mIncludeJoin = m2mIncludeJoin;
  }

  @Override
  public Query<T> setDisableLazyLoading(boolean disableLazyLoading) {
    this.disableLazyLoading = disableLazyLoading;
    return this;
  }

  @Override
  public boolean isDisableLazyLoading() {
    return disableLazyLoading;
  }

  @Override
  public int getFirstRow() {
    return firstRow;
  }

  @Override
  public DefaultOrmQuery<T> setFirstRow(int firstRow) {
    this.firstRow = firstRow;
    return this;
  }

  @Override
  public int getMaxRows() {
    return maxRows;
  }

  @Override
  public DefaultOrmQuery<T> setMaxRows(int maxRows) {
    this.maxRows = maxRows;
    return this;
  }

  @Override
  public String getMapKey() {
    return mapKey;
  }

  @Override
  public DefaultOrmQuery<T> setMapKey(String mapKey) {
    this.mapKey = mapKey;
    return this;
  }

  @Override
  public Object getId() {
    return id;
  }

  @Override
  public DefaultOrmQuery<T> setId(Object id) {
    if (id == null) {
      throw new NullPointerException("The id is null");
    }
    this.id = id;
    return this;
  }

  @Override
  public BindParams getBindParams() {
    return bindParams;
  }

  @Override
  public DefaultOrmQuery<T> where(Expression expression) {
    where().add(expression);
    return this;
  }

  @Override
  public ExpressionList<T> text() {
    if (textExpressions == null) {
      useDocStore = true;
      textExpressions = new DefaultExpressionList<>(this);
    }
    return textExpressions;
  }

  @Override
  public ExpressionList<T> where() {
    if (whereExpressions == null) {
      whereExpressions = new DefaultExpressionList<>(this, null);
    }
    return whereExpressions;
  }

  @Override
  public void simplifyExpressions() {
    if (whereExpressions != null) {
      whereExpressions.simplify();
    }
  }

  @Override
  public DefaultOrmQuery<T> having(Expression expression) {
    having().add(expression);
    return this;
  }

  @Override
  public ExpressionList<T> having() {
    if (havingExpressions == null) {
      havingExpressions = new DefaultExpressionList<>(this, null);
    }
    return havingExpressions;
  }

  @Override
  public SpiExpressionList<T> getHavingExpressions() {
    return havingExpressions;
  }

  @Override
  public SpiExpressionList<T> getWhereExpressions() {
    return whereExpressions;
  }

  @Override
  public SpiExpressionList<T> getTextExpression() {
    return textExpressions;
  }

  @Override
  public String getGeneratedSql() {
    return generatedSql;
  }

  @Override
  public void setGeneratedSql(String generatedSql) {
    this.generatedSql = generatedSql;
  }

  @Override
  public void checkNamedParameters() {
    if (namedParams != null) {
      Collection<ONamedParam> values = namedParams.values();
      for (ONamedParam value : values) {
        value.checkValueSet();
      }
    }
  }

  @Override
  public SpiNamedParam createNamedParameter(String name) {
    if (namedParams == null) {
      namedParams = new HashMap<>();
    }

    ONamedParam param = namedParams.get(name);
    if (param == null) {
      param = new ONamedParam(name);
      namedParams.put(name, param);
    }
    return param;
  }

  @Override
  public void setDefaultFetchBuffer(int fetchSize) {
    if (bufferFetchSizeHint == 0) {
      bufferFetchSizeHint = fetchSize;
    }
  }

  @Override
  public Query<T> setBufferFetchSizeHint(int bufferFetchSizeHint) {
    this.bufferFetchSizeHint = bufferFetchSizeHint;
    return this;
  }

  @Override
  public int getBufferFetchSizeHint() {
    return bufferFetchSizeHint;
  }

  @Override
  public Query<T> setDisableReadAuditing() {
    this.disableReadAudit = true;
    return this;
  }

  @Override
  public boolean isDisableReadAudit() {
    return disableReadAudit;
  }

  @Override
  public boolean isFutureFetch() {
    return futureFetch;
  }

  @Override
  public void setFutureFetch(boolean backgroundFetch) {
    this.futureFetch = backgroundFetch;
  }

  @Override
  public void setFutureFetchAudit(ReadEvent event) {
    this.futureFetchAudit = event;
  }

  @Override
  public ReadEvent getFutureFetchAudit() {
    return futureFetchAudit;
  }

  @Override
  public void setCancelableQuery(CancelableQuery cancelableQuery) {
    synchronized (this) {
      this.cancelableQuery = cancelableQuery;
    }
  }

  @Override
  public Query<T> alias(String alias) {
    this.rootTableAlias = alias;
    return this;
  }

  @Override
  public String getAlias() {
    return rootTableAlias;
  }

  @Override
  public void cancel() {
    synchronized (this) {
      cancelled = true;
      if (cancelableQuery != null) {
        cancelableQuery.cancel();
      }
    }
  }

  @Override
  public boolean isCancelled() {
    synchronized (this) {
      return cancelled;
    }
  }

  @Override
  public Set<String> validate() {
    return server.validateQuery(this);
  }

  /**
   * Validate all the expression properties/paths given the bean descriptor.
   */
  @Override
  public Set<String> validate(BeanType<T> desc) {

    SpiExpressionValidation validation = new SpiExpressionValidation(desc);
    if (whereExpressions != null) {
      whereExpressions.validate(validation);
    }
    if (havingExpressions != null) {
      havingExpressions.validate(validation);
    }
    if (orderBy != null) {
      for (Property property : orderBy.getProperties()) {
        validation.validate(property.getProperty());
      }
    }
    return validation.getUnknownProperties();
  }

  void setUpdateProperties(OrmUpdateProperties updateProperties) {
    this.updateProperties = updateProperties;
  }

  @Override
  public OrmUpdateProperties getUpdateProperties() {
    return updateProperties;
  }
}
