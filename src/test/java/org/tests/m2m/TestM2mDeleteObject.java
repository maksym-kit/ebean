package org.tests.m2m;

import io.ebean.BaseTestCase;
import io.ebean.Ebean;
import org.tests.model.m2m.Permission;
import org.tests.model.m2m.Role;
import org.tests.model.m2m.Tenant;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class TestM2mDeleteObject extends BaseTestCase {

  @Test
  public void test() {

    Ebean.createUpdate(Permission.class, "delete from Permission").execute();
    Ebean.createUpdate(Tenant.class, "delete from Tenant").execute();
    Ebean.createUpdate(Role.class, "delete from Role").execute();

    Tenant t = new Tenant("tenant");

    Ebean.save(t);

    Permission p1 = new Permission("p1");
    Permission p2 = new Permission("p2");

    Ebean.save(p1);
    Ebean.save(p2);

    Role role1 = new Role("role");
    role1.setTenant(t);

    Set<Permission> permissions = new HashSet<>();
    List<Permission> permsList = Ebean.find(Permission.class).findList();
    permissions.addAll(permsList);

    role1.setPermissions(permissions);

    Ebean.save(role1);

    List<Tenant> tenantList = Ebean.find(Tenant.class).fetch("roles").findList();
    List<Role> roleList = Ebean.find(Role.class).fetch("permissions").findList();
    List<Permission> permissionList = Ebean.find(Permission.class).fetch("roles").findList();

    Assert.assertEquals(1, tenantList.size());
    Assert.assertEquals(2, permissionList.size());
    Assert.assertEquals(1, roleList.size());

    Ebean.delete(role1);

    List<Tenant> tenantList2 = Ebean.find(Tenant.class).fetch("roles").findList();
    List<Role> roleList2 = Ebean.find(Role.class).fetch("permissions").findList();
    List<Permission> permissionList2 = Ebean.find(Permission.class).fetch("roles").findList();

    Assert.assertEquals(0, roleList2.size());
    Assert.assertEquals(1, tenantList2.size());
    Assert.assertEquals(2, permissionList2.size());
  }


  @Test
  public void test_deleteCascading() {

    Permission p1 = new Permission("p1");
    Permission p2 = new Permission("p2");
    Ebean.save(p1);
    Ebean.save(p2);

    Role role1 = new Role("role");

    Set<Permission> permissions = new HashSet<>();
    permissions.add(p2);
    role1.setPermissions(permissions);
    Ebean.save(role1);

    // only deletes as not associated
    Ebean.delete(p1);
    // delete cascades
    Ebean.delete(role1);
  }
}
