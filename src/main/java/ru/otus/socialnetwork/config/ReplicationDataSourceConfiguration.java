package ru.otus.socialnetwork.config;

import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

@Configuration(proxyBeanMethods = false)
@Profile("replication")
@EnableConfigurationProperties(DataSourceProperties.class)
public class ReplicationDataSourceConfiguration {

    @Bean
    @LiquibaseDataSource
    @ConfigurationProperties("spring.datasource.hikari")
    HikariDataSource primaryDataSource(DataSourceProperties properties) {
        var pool = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        pool.setPoolName("primary");
        return pool;
    }

    @Bean
    @ConfigurationProperties("app.replication.hikari")
    HikariDataSource replica1DataSource(DataSourceProperties properties,
                                        @Value("${app.replication.replica1-url}") String url) {
        return replicaPool(properties, url, "replica1");
    }

    @Bean
    @ConfigurationProperties("app.replication.hikari")
    HikariDataSource replica2DataSource(DataSourceProperties properties,
                                        @Value("${app.replication.replica2-url}") String url) {
        return replicaPool(properties, url, "replica2");
    }

    @Bean
    @Primary
    DataSource dataSource(@Qualifier("primaryDataSource") DataSource primary,
                          @Qualifier("replica1DataSource") DataSource replica1,
                          @Qualifier("replica2DataSource") DataSource replica2) {
        var replicas = new AbstractRoutingDataSource() {
            private final AtomicInteger nextReplica = new AtomicInteger();

            @Override
            protected Object determineCurrentLookupKey() {
                return nextReplica.getAndUpdate(current -> 1 - current);
            }
        };
        replicas.setTargetDataSources(Map.of(0, replica1, 1, replica2));
        replicas.afterPropertiesSet();

        var dataSource = new LazyConnectionDataSourceProxy(primary);
        dataSource.setReadOnlyDataSource(replicas);
        return dataSource;
    }

    private static HikariDataSource replicaPool(DataSourceProperties properties, String url, String name) {
        var pool = properties.initializeDataSourceBuilder().type(HikariDataSource.class).url(url).build();
        pool.setPoolName(name);
        pool.setReadOnly(true);
        return pool;
    }
}
