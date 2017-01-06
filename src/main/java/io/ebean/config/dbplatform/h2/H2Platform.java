package io.ebean.config.dbplatform.h2;

import io.ebean.BackgroundExecutor;
import io.ebean.Platform;
import io.ebean.config.dbplatform.DatabasePlatform;
import io.ebean.config.dbplatform.DbPlatformType;
import io.ebean.config.dbplatform.DbType;
import io.ebean.config.dbplatform.IdType;
import io.ebean.config.dbplatform.PlatformIdGenerator;
import io.ebean.dbmigration.ddlgeneration.platform.H2Ddl;

import javax.sql.DataSource;

/**
 * H2 specific platform.
 */
public class H2Platform extends DatabasePlatform {

  public H2Platform() {
    super();
    this.platform = Platform.H2;
    this.dbEncrypt = new H2DbEncrypt();
    this.platformDdl = new H2Ddl(this);
    this.historySupport = new H2HistorySupport();
    this.nativeUuidType = true;
    this.dbDefaultValue.setNow("now()");
    this.columnAliasPrefix = null;

    this.dbIdentity.setIdType(IdType.IDENTITY);
    this.dbIdentity.setSupportsGetGeneratedKeys(true);
    this.dbIdentity.setSupportsSequence(true);
    this.dbIdentity.setSupportsIdentity(true);

    // like ? escape'' not working in the latest version H2 so just using no
    // escape clause for now noting that backslash is an escape char for like in H2
    this.likeClause = "like ?";

    dbTypeMap.put(DbType.UUID, new DbPlatformType("uuid", false));
  }

  /**
   * Return a H2 specific sequence IdGenerator that supports batch fetching
   * sequence values.
   */
  @Override
  public PlatformIdGenerator createSequenceIdGenerator(BackgroundExecutor be, DataSource ds,
                                                       String seqName, int batchSize) {

    return new H2SequenceIdGenerator(be, ds, seqName, batchSize);
  }

  @Override
  protected String withForUpdate(String sql) {
    return sql + " for update";
  }
}
