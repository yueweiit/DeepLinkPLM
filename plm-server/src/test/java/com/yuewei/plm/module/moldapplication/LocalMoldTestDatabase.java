package com.yuewei.plm.module.moldapplication;

import java.net.URI;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/** Test-only guard, checked before starting a transaction or creating any fixture. */
final class LocalMoldTestDatabase {
    private static final Set<String> DATABASES=Set.of("plm_data_management","plm_sales_policy_qa");
    private static final String PRODUCTION_CLUSTER="7651923859845308451";
    static String database(String jdbcUrl) {
        if(jdbcUrl==null||!jdbcUrl.startsWith("jdbc:postgresql://"))throw new IllegalArgumentException("Explicit local PostgreSQL QA URL required");
        URI uri=URI.create(jdbcUrl.substring(5));
        if(!Set.of("127.0.0.1","localhost","[::1]").contains(uri.getHost())||uri.getUserInfo()!=null||uri.getPort()<1)
            throw new IllegalArgumentException("Only explicit loopback QA endpoints are allowed");
        String name=uri.getPath().substring(1);
        if(!DATABASES.contains(name))throw new IllegalArgumentException("Not an allowed QA database");
        return name;
    }
    static void verify(String jdbcUrl,JdbcTemplate jdbc) {
        String expected=database(jdbcUrl);
        verifyIdentity(expected,jdbc.queryForObject("select current_database()",String.class),jdbc.queryForObject("select system_identifier::text from pg_control_system()",String.class));
    }
    static void verifyIdentity(String expected,String actual,String cluster) {
        if(!DATABASES.contains(expected)||!expected.equals(actual)||cluster==null||!cluster.matches("[0-9]+")||PRODUCTION_CLUSTER.equals(cluster))
            throw new IllegalArgumentException("QA database identity verification failed; no writes allowed");
    }
    private LocalMoldTestDatabase() {}
}
