package io.ebean;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class SqlRowBooleanTest extends BaseTestCase {

  @Test
  public void getBoolean() {

    SqlQuery sqlQuery;
    if (isOracle()) {
      sqlQuery = Ebean.createSqlQuery("SELECT 1 AS ISNT_NULL from dual");
    } else {
      sqlQuery = Ebean.createSqlQuery("SELECT 1 IS NOT NULL AS ISNT_NULL");
    }
    SqlRow row = sqlQuery.findUnique();
    Boolean value = row.getBoolean("ISNT_NULL");
    assertThat(value).isTrue();
  }
}
