package io.ebeaninternal.server.query;

import io.ebeaninternal.server.deploy.BeanProperty;
import io.ebeaninternal.server.deploy.DbSqlContext;
import io.ebeaninternal.server.deploy.TableJoinColumn;
import io.ebeaninternal.server.lib.util.StringHelper;
import io.ebeaninternal.server.util.ArrayStack;

import java.util.ArrayList;
import java.util.HashSet;

class DefaultDbSqlContext implements DbSqlContext {

  private static final String COMMA = ", ";

  private static final String PERIOD = ".";
  private static final int STRING_BUILDER_INITIAL_CAPACITY = 140;

  private final String tableAliasPlaceHolder;

  private final String columnAliasPrefix;

  private final ArrayStack<String> tableAliasStack = new ArrayStack<>();

  private final ArrayStack<String> joinStack = new ArrayStack<>();

  private final ArrayStack<String> prefixStack = new ArrayStack<>();

  private boolean useColumnAlias;

  private int columnIndex;

  private StringBuilder sb = new StringBuilder(STRING_BUILDER_INITIAL_CAPACITY);

  /**
   * A Set used to make sure formula joins are only added once to a query.
   */
  private HashSet<String> formulaJoins;

  private HashSet<String> tableJoins;

  private final SqlTreeAlias alias;

  private String currentPrefix;

  private ArrayList<BeanProperty> encryptedProps;

  private final CQueryDraftSupport draftSupport;

  private final CQueryHistorySupport historySupport;

  private final boolean historyQuery;

  /**
   * Construct for SELECT clause (with column alias settings).
   */
  DefaultDbSqlContext(SqlTreeAlias alias, CQueryBuilder builder,
                      boolean alwaysUseColumnAlias, CQueryHistorySupport historySupport, CQueryDraftSupport draftSupport) {

    this.alias = alias;
    this.tableAliasPlaceHolder = builder.tableAliasPlaceHolder;
    this.columnAliasPrefix = builder.columnAliasPrefix;
    this.useColumnAlias = columnAliasPrefix != null && alwaysUseColumnAlias;
    this.draftSupport = draftSupport;
    this.historySupport = historySupport;
    this.historyQuery = (historySupport != null);
  }

  @Override
  public void startGroupBy() {
    this.useColumnAlias = false;
  }

  public void addEncryptedProp(BeanProperty p) {
    if (encryptedProps == null) {
      encryptedProps = new ArrayList<>();
    }
    encryptedProps.add(p);
  }

  public BeanProperty[] getEncryptedProps() {
    if (encryptedProps == null) {
      return null;
    }

    return encryptedProps.toArray(new BeanProperty[encryptedProps.size()]);
  }

  public String peekJoin() {
    return joinStack.peek();
  }

  public void popJoin() {
    joinStack.pop();
  }

  public void pushJoin(String node) {
    joinStack.push(node);
  }

  public void addJoin(String type, String table, TableJoinColumn[] cols, String a1, String a2, String inheritance) {

    if (tableJoins == null) {
      tableJoins = new HashSet<>();
    }

    String joinKey = table + "-" + a1 + "-" + a2;
    if (tableJoins.contains(joinKey)) {
      return;
    }

    tableJoins.add(joinKey);

    sb.append(" ").append(type);
    boolean addAsOfOnClause = false;
    if (draftSupport != null) {
      appendTable(table, draftSupport.getDraftTable(table));

    } else if (!historyQuery) {
      sb.append(" ").append(table).append(" ");

    } else {
      // check if there is an associated history table and if so
      // use the unionAll view - we expect an additional predicate to match
      String asOfView = historySupport.getAsOfView(table);
      appendTable(table, asOfView);
      if (asOfView != null) {
        addAsOfOnClause = !historySupport.isStandardsBased();
      }
    }

    sb.append(a2);
    sb.append(" on ");
    for (int i = 0; i < cols.length; i++) {
      TableJoinColumn pair = cols[i];
      if (i > 0) {
        sb.append(" and ");
      }

      if (pair.getForeignSqlFormula() != null) {
        sb.append(StringHelper.replaceString(pair.getForeignSqlFormula(), tableAliasPlaceHolder, a2));
      } else {
        sb.append(a2).append(".").append(pair.getForeignDbColumn());
      }

      sb.append(" = ");

      if (pair.getLocalSqlFormula() != null) {
        sb.append(StringHelper.replaceString(pair.getLocalSqlFormula(), tableAliasPlaceHolder, a1));
      } else {
        sb.append(a1).append(".").append(pair.getLocalDbColumn());
      }
    }

    // add on any inheritance where clause
    if (inheritance != null && !inheritance.isEmpty()) {
      sb.append(" and ").append(a2).append(".").append(inheritance);
    }

    if (addAsOfOnClause) {
      sb.append(" and ").append(historySupport.getAsOfPredicate(a2));
    }

    sb.append(" ");
  }

  private void appendTable(String table, String draftTable) {
    if (draftTable != null) {
      // there is an associated history table and view so use that
      sb.append(" ").append(draftTable).append(" ");

    } else {
      sb.append(" ").append(table).append(" ");
    }
  }

  @Override
  public boolean isDraftQuery() {
    return draftSupport != null;
  }

  public String getTableAlias(String prefix) {
    return alias.getTableAlias(prefix);
  }

  public String getTableAliasManyWhere(String prefix) {
    return alias.getTableAliasManyWhere(prefix);
  }

  public void pushSecondaryTableAlias(String alias) {
    tableAliasStack.push(alias);
  }

  public String getRelativePrefix(String propName) {

    return currentPrefix == null ? propName : currentPrefix + "." + propName;
  }

  public void pushTableAlias(String prefix) {
    prefixStack.push(currentPrefix);
    currentPrefix = prefix;
    tableAliasStack.push(getTableAlias(prefix));
  }

  public void popTableAlias() {
    tableAliasStack.pop();
    currentPrefix = prefixStack.pop();
  }

  public DefaultDbSqlContext append(String s) {
    sb.append(s);
    return this;
  }

  public DefaultDbSqlContext append(char s) {
    sb.append(s);
    return this;
  }

  public void appendFormulaJoin(String sqlFormulaJoin, SqlJoinType joinType) {

    // replace ${ta} place holder with the real table alias...
    String tableAlias = tableAliasStack.peek();
    String converted = StringHelper.replaceString(sqlFormulaJoin, tableAliasPlaceHolder, tableAlias);

    if (formulaJoins == null) {
      formulaJoins = new HashSet<>();

    } else if (formulaJoins.contains(converted)) {
      // skip adding a formula join because
      // the same join has already been added.
      return;
    }

    // we only want to add this join once
    formulaJoins.add(converted);

    sb.append(" ");
    if (joinType == SqlJoinType.OUTER) {
      if ("join".equals(sqlFormulaJoin.substring(0, 4).toLowerCase())) {
        // prepend left as we are in the 'many' part
        append(" left ");
      }
    }

    sb.append(converted);
    sb.append(" ");
  }

  public void appendFormulaSelect(String sqlFormulaSelect) {

    String tableAlias = tableAliasStack.peek();
    String converted = StringHelper.replaceString(sqlFormulaSelect, tableAliasPlaceHolder,
      tableAlias);

    sb.append(COMMA);
    sb.append(converted);

    appendColumnAlias();
  }

  @Override
  public void appendHistorySysPeriod() {

    String tableAlias = tableAliasStack.peek();

    sb.append(COMMA);
    sb.append(historySupport.getSysPeriodLower(tableAlias));
    appendColumnAlias();

    sb.append(COMMA);
    sb.append(historySupport.getSysPeriodUpper(tableAlias));
    appendColumnAlias();
  }

  private void appendColumnAlias() {
    if (useColumnAlias) {
      sb.append(" ");
      sb.append(columnAliasPrefix);
      sb.append(columnIndex);
    }
    columnIndex++;
  }

  public void appendColumn(String column) {
    appendColumn(tableAliasStack.peek(), column);
  }

  public void appendColumn(String tableAlias, String column) {
    sb.append(COMMA);

    if (column.contains("${}")) {
      // support DB functions such as lower() etc
      // with the use of secondary columns
      String x = StringHelper.replaceString(column, "${}", tableAlias);
      sb.append(x);
    } else {
      sb.append(tableAlias);
      sb.append(PERIOD);
      sb.append(column);
    }
    appendColumnAlias();
  }

  public String peekTableAlias() {
    return tableAliasStack.peek();
  }

  public void appendRawColumn(String rawcolumnWithTableAlias) {
    sb.append(COMMA);
    sb.append(rawcolumnWithTableAlias);

    appendColumnAlias();
  }

  public int length() {
    return sb.length();
  }

  public String getContent() {
    String s = sb.toString();
    sb = new StringBuilder(STRING_BUILDER_INITIAL_CAPACITY);
    return s;
  }

  public String toString() {
    return "DefaultDbSqlContext: " + sb.toString();
  }

}
