package io.ebean.config.dbplatform.mysql;

import io.ebean.BackgroundExecutor;
import io.ebean.Platform;
import io.ebean.config.dbplatform.DatabasePlatform;
import io.ebean.config.dbplatform.DbPlatformType;
import io.ebean.config.dbplatform.DbType;
import io.ebean.config.dbplatform.IdType;
import io.ebean.config.dbplatform.PlatformIdGenerator;
import io.ebean.dbmigration.ddlgeneration.platform.MySqlDdl;

import javax.sql.DataSource;
import java.sql.Types;

/**
 * MySQL specific platform.
 * <p>
 * <ul>
 * <li>supportsGetGeneratedKeys = true</li>
 * <li>Uses LIMIT OFFSET clause</li>
 * <li>Uses ` for quoted identifiers</li>
 * </ul>
 * </p>
 */
public class MySqlPlatform extends DatabasePlatform {

  public MySqlPlatform() {
    super();
    this.platform = Platform.MYSQL;
    this.useExtraTransactionOnIterateSecondaryQueries = true;
    this.likeClause = "like ? escape''";
    this.selectCountWithAlias = true;
    this.dbEncrypt = new MySqlDbEncrypt();
    this.platformDdl = new MySqlDdl(this);
    this.historySupport = new MySqlHistorySupport();
    this.columnAliasPrefix = null;

    this.dbIdentity.setIdType(IdType.IDENTITY);
    this.dbIdentity.setSupportsGetGeneratedKeys(true);
    this.dbIdentity.setSupportsIdentity(true);
    this.dbIdentity.setSupportsSequence(false);

    this.openQuote = "`";
    this.closeQuote = "`";

    this.forwardOnlyHintOnFindIterate = true;
    this.booleanDbType = Types.BIT;

    dbTypeMap.put(DbType.BIT, new DbPlatformType("tinyint(1) default 0"));
    dbTypeMap.put(DbType.BOOLEAN, new DbPlatformType("tinyint(1) default 0"));
    dbTypeMap.put(DbType.TIMESTAMP, new DbPlatformType("datetime(6)"));
    dbTypeMap.put(DbType.CLOB, new MySqlClob());
    dbTypeMap.put(DbType.BLOB, new MySqlBlob());
    dbTypeMap.put(DbType.BINARY, new DbPlatformType("binary", 255));
    dbTypeMap.put(DbType.VARBINARY, new DbPlatformType("varbinary", 255));
  }

  /**
   * Return null in case there is a sequence annotation.
   */
  @Override
  public PlatformIdGenerator createSequenceIdGenerator(BackgroundExecutor be,
                                                       DataSource ds, String seqName, int batchSize) {

    return null;
  }

  @Override
  protected String withForUpdate(String sql) {
    return sql + " for update";
  }
}
