package io.ebeaninternal.server.query;

import io.ebean.Version;
import io.ebean.bean.EntityBean;
import io.ebeaninternal.api.SpiQuery;
import io.ebeaninternal.server.deploy.BeanProperty;
import io.ebeaninternal.server.deploy.BeanPropertyAssoc;
import io.ebeaninternal.server.deploy.BeanPropertyAssocMany;
import io.ebeaninternal.server.deploy.DbReadContext;
import io.ebeaninternal.server.deploy.DbSqlContext;
import io.ebeaninternal.server.deploy.TableJoin;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * The purpose is to add an extra join to the query.
 * <p>
 * This is used to support the where clause or order by clause that refers
 * to properties that are NOT included in the select. To support the where clause
 * etc in this case we must add an extra join.
 * </p>
 */
class SqlTreeNodeExtraJoin implements SqlTreeNode {

  private final BeanPropertyAssoc<?> assocBeanProperty;

  private final String prefix;

  private final boolean manyJoin;

  private final boolean pathContainsMany;

  private List<SqlTreeNodeExtraJoin> children;

  SqlTreeNodeExtraJoin(String prefix, BeanPropertyAssoc<?> assocBeanProperty, boolean pathContainsMany) {
    this.prefix = prefix;
    this.assocBeanProperty = assocBeanProperty;
    this.pathContainsMany = pathContainsMany;
    this.manyJoin = assocBeanProperty instanceof BeanPropertyAssocMany<?>;
  }

  @Override
  public void buildRawSqlSelectChain(List<String> selectChain) {
    // nothing to add
  }

  @Override
  public void addAsOfTableAlias(SpiQuery<?> query) {
    // nothing to do here
  }

  @Override
  public void addSoftDeletePredicate(SpiQuery<?> query) {
    // nothing to do here
  }

  @Override
  public void appendDistinctOn(DbSqlContext ctx, boolean subQuery) {
    // do nothing here ...
  }

  @Override
  public boolean isAggregation() {
    return false;
  }

  @Override
  public void appendGroupBy(DbSqlContext ctx, boolean subQuery) {
    // nothing to do here
  }

  @Override
  public BeanProperty getSingleProperty() {
    throw new IllegalStateException("No expected");
  }

  /**
   * Return true if the extra join is a many join.
   * <p>
   * This means we need to add distinct to the sql query.
   * </p>
   */
  boolean isManyJoin() {
    return manyJoin;
  }

  public String getName() {
    return prefix;
  }

  public void addChild(SqlTreeNodeExtraJoin child) {
    if (children == null) {
      children = new ArrayList<>();
    }
    children.add(child);
  }

  public void appendFrom(DbSqlContext ctx, SqlJoinType joinType) {

    boolean manyToMany = false;

    if (assocBeanProperty instanceof BeanPropertyAssocMany<?>) {
      BeanPropertyAssocMany<?> manyProp = (BeanPropertyAssocMany<?>) assocBeanProperty;
      if (manyProp.isManyToMany()) {

        manyToMany = true;

        String alias = ctx.getTableAlias(prefix);
        String[] split = SplitName.split(prefix);
        String parentAlias = ctx.getTableAlias(split[0]);
        String alias2 = alias + "z_";

        TableJoin manyToManyJoin = manyProp.getIntersectionTableJoin();
        manyToManyJoin.addJoin(joinType, parentAlias, alias2, ctx);

        assocBeanProperty.addJoin(joinType, alias2, alias, ctx);
      }
    }

    if (pathContainsMany) {
      // "promote" to left join as the path contains a many
      joinType = SqlJoinType.OUTER;
    }
    if (!manyToMany) {
      assocBeanProperty.addJoin(joinType, prefix, ctx);
    }

    if (children != null) {

      if (manyJoin || pathContainsMany) {
        // if AUTO then make all descendants use OUTER JOIN
        joinType = joinType.autoToOuter();
      }

      for (SqlTreeNodeExtraJoin child : children) {
        child.appendFrom(ctx, joinType);
      }
    }
  }

  /**
   * Does nothing.
   */
  public void appendSelect(DbSqlContext ctx, boolean subQuery) {
  }

  /**
   * Does nothing.
   */
  public void appendWhere(DbSqlContext ctx) {
  }

  /**
   * Does nothing.
   */
  public EntityBean load(DbReadContext ctx, EntityBean localBean, EntityBean parentBean) throws SQLException {
    return null;
  }

  /**
   * Does nothing.
   */
  @Override
  public <T> Version<T> loadVersion(DbReadContext ctx) throws SQLException {
    return null;
  }

  @Override
  public boolean hasMany() {
    return manyJoin;
  }
}
