package org.rakam.plugin.user;

import com.facebook.presto.sql.ExpressionFormatter;
import com.facebook.presto.sql.tree.Expression;
import com.google.common.base.Joiner;
import com.google.common.base.Throwables;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.inject.Inject;
import org.rakam.collection.FieldType;
import org.rakam.collection.SchemaField;
import org.rakam.plugin.Column;
import org.rakam.plugin.UserStorage;
import org.rakam.report.QueryError;
import org.rakam.report.QueryExecution;
import org.rakam.report.QueryResult;
import org.rakam.report.postgresql.PostgresqlQueryExecutor;
import org.rakam.util.NotImplementedException;

import java.lang.reflect.ParameterizedType;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static org.rakam.analysis.postgresql.PostgresqlMetastore.fromSql;
import static org.rakam.util.ValidationUtil.checkCollection;
import static org.rakam.util.ValidationUtil.checkProject;
import static org.rakam.util.ValidationUtil.checkTableColumn;

/**
 * Created by buremba <Burak Emre Kabakcı> on 02/05/15 00:00.
 */
public class PostgresqlUserStorageAdapter implements UserStorage {
    public static final String USER_TABLE = "_users";
    public static final String PRIMARY_KEY = "id";
    private final PostgresqlQueryExecutor queryExecutor;
    private final Cache<String, Map<String, FieldType>> propertyCache = CacheBuilder.newBuilder().build();

    @Inject
    public PostgresqlUserStorageAdapter(PostgresqlQueryExecutor queryExecutor) {
        this.queryExecutor = queryExecutor;
    }

    @Override
    public void create(String project, Map<String, Object> properties) {
        throw new NotImplementedException();
    }

    @Override
    public CompletableFuture<QueryResult> filter(String project, Expression filterExpression, List<EventFilter> eventFilter, Sorting sortColumn, long limit, long offset) {
        checkProject(project);
        List<Column> projectColumns = getMetadata(project);
        String columns = Joiner.on(", ").join(projectColumns.stream().map(col -> col.getName()).toArray());

        LinkedList<String> filters = new LinkedList<>();
        if(filterExpression != null) {
            filters.add(new ExpressionFormatter.Formatter().process(filterExpression, null));
        }
        if(eventFilter != null && !eventFilter.isEmpty()) {
            for (EventFilter filter : eventFilter) {
                StringBuilder builder = new StringBuilder();

                checkCollection(filter.collection);
                if(filter.aggregation==null) {
                    builder.append(format("select \"user\" from %s.%s", project, filter.collection));
                    if(filter.filterExpression!=null) {
                        builder.append(" where ").append(new ExpressionFormatter.Formatter().process(filter.filterExpression, null));
                    }
                    filters.add((format("id in (%s)", builder.toString())));
                } else {
                    builder.append(format("select \"user\" from %s.%s", project, filter.collection));
                    if(filter.filterExpression != null) {
                        builder.append(" where ").append(new ExpressionFormatter.Formatter().process(filter.filterExpression, null));
                    }
                    String field;
                    if(filter.aggregation.type == AggregationType.COUNT && filter.aggregation.field == null) {
                        field = "user";
                    }else {
                        field = filter.aggregation.field;
                    }
                    builder.append(" group by \"user\"");
                    if(filter.aggregation.minimum != null) {
                        builder.append(format("%s(\"%s\") > %d", filter.aggregation.type, field, filter.aggregation.minimum));
                        filters.add((format("id in (%s)", builder.toString())));
                    }
                    if(filter.aggregation.maximum != null) {
                        if(filter.aggregation.minimum == null) {
                            builder.append(" having ");
                        }
                        builder.append(format("%s(\"%s\") > %d", filter.aggregation.type, field, filter.aggregation.maximum));
                        filters.add((format("id not in (%s)", builder.toString())));
                    }
                }
            }
        }

        if(sortColumn != null) {
            if (!projectColumns.stream().anyMatch(col -> col.getName().equals(sortColumn.column))) {
                throw new IllegalArgumentException(format("sorting column does not exist: %s", sortColumn.column));
            }
        }

        String orderBy = sortColumn == null ? "" : format(" ORDER BY %s %s", sortColumn.column, sortColumn.order);

        QueryExecution query =  queryExecutor.executeRawQuery(format("SELECT %s FROM %s._users %s %s LIMIT %s OFFSET %s",
                columns, project, filters.isEmpty() ? "" : " WHERE " + Joiner.on(" AND ").join(filters), orderBy, limit, offset));

        CompletableFuture<QueryResult> dataResult = query.getResult();

        if(eventFilter == null || eventFilter.isEmpty()) {
            StringBuilder builder = new StringBuilder();
            builder.append(format("SELECT count(*) FROM %s.%s", project, USER_TABLE));
            if(filterExpression != null) {
                builder.append(" where ").append(filters.get(0));
            }

            QueryExecution totalResult = queryExecutor.executeRawQuery(builder.toString());

            CompletableFuture<QueryResult> result = new CompletableFuture<>();
            CompletableFuture.allOf(dataResult, totalResult.getResult()).whenComplete((__, ex) -> {
                QueryResult data = dataResult.join();
                QueryResult totalResultData = totalResult.getResult().join();
                if(ex == null && !data.isFailed() && !totalResultData.isFailed()) {
                    Object v1 = totalResultData.getResult().get(0).get(0);
                    result.complete(new QueryResult(projectColumns, data.getResult(), ImmutableMap.of("totalResult", v1)));
                } else {
                    result.complete(QueryResult.errorResult(new QueryError(ex.getMessage(), null, 0)));
                }
            });

            return result;
        } else {
            return dataResult;
        }
    }

    @Override
    public List<Column> getMetadata(String project) {
        checkProject(project);
        LinkedList<Column> columns = new LinkedList<>();

        try(Connection conn = queryExecutor.getConnection()) {
            DatabaseMetaData metaData = conn.getMetaData();
            ResultSet indexInfo = metaData.getIndexInfo(null, null, USER_TABLE, true, false);
            ResultSet dbColumns = metaData.getColumns(null, project, USER_TABLE, null);

            List<String> uniqueColumns = Lists.newLinkedList();
            while (indexInfo.next()) {
                uniqueColumns.add(indexInfo.getString("COLUMN_NAME"));
            }

            while (dbColumns.next()) {
                String columnName = dbColumns.getString("COLUMN_NAME");
                FieldType fieldType;
                try {
                    fieldType = fromSql(dbColumns.getInt("DATA_TYPE"));
                } catch (IllegalStateException e) {
                    continue;
                }
                columns.add(new Column(columnName, fieldType, uniqueColumns.indexOf(columnName) > -1));
            }
            return columns;

        } catch (SQLException e) {
            throw new IllegalStateException("couldn't get metadata from plugin.user.storage");
        }
    }

    private long getUserId(Object userId) {
        if(userId instanceof String) {
            return Long.parseLong((String) userId);
        }else
        if(userId instanceof Number) {
            return ((Number) userId).longValue();
        } else {
            throw new IllegalArgumentException();
        }
    }

    @Override
    public CompletableFuture<org.rakam.plugin.user.User> getUser(String project, Object userId) {
        checkProject(project);

        return queryExecutor.executeRawQuery(format("select * from %s.%s where %s = %d", project, USER_TABLE, PRIMARY_KEY, getUserId(userId)))
                .getResult().thenApply(result -> {
            HashMap<String, Object> properties = Maps.newHashMap();
            List<Object> objects = result.getResult().get(0);
            List<? extends SchemaField> metadata = result.getMetadata();

            for (int i = 0; i < metadata.size(); i++) {
                String name = metadata.get(i).getName();
                if(!name.equals(PRIMARY_KEY))
                    properties.put(name, objects.get(i));
            }

            return new org.rakam.plugin.user.User(project, userId, properties);
        });
    }

    private Map<String, FieldType> updateCache(String project) {
        Map<String, FieldType> columns = getMetadata(project).stream()
                .collect(Collectors.toMap(col -> col.getName(), col -> col.getType()));
        propertyCache.put(project, columns);
        return columns;
    }

    @Override
    public void setUserProperty(String project, Object user, String property, Object value) {
        checkProject(project);
        checkTableColumn(property, "user property");
        Map<String, FieldType> columns = propertyCache.getIfPresent(project);
        if(columns == null) {
            columns = updateCache(project);
        }

        FieldType fieldType = columns.get(property);
        if(fieldType == null) {
            columns = updateCache(project);
            fieldType = columns.get(property);
        }

        try(Connection conn = queryExecutor.getConnection()) {
            if(fieldType == null) {
                try {
                    conn.createStatement().execute(format("alter table %s.%s add column %s %s",
                            project, property, getPostgresqlType(value.getClass())));
                } catch (SQLException e) {
                    // TODO: check is column exists of this is a different exception
                }
            }
            PreparedStatement statement = conn.prepareStatement("update " + project + "." + USER_TABLE + " set " + property +
                    " = ? where "+PRIMARY_KEY+" = ?");
            statement.setObject(1, value);
            statement.setLong(2, getUserId(user));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw Throwables.propagate(e);
        }
    }

    private String getPostgresqlType(Class clazz) {
        if(clazz.equals(String.class)) {
            return "text";
        }else
        if (clazz.equals(Float.class) || clazz.equals(Double.class)) {
            return "bool";
        }else
        if(Number.class.isAssignableFrom(clazz)) {
            return "bigint";
        } else
        if(clazz.equals(Boolean.class)) {
            return "bool";
        }else
        if(Collection.class.isAssignableFrom(clazz)) {
            return getPostgresqlType((Class) ((ParameterizedType) getClass().getGenericInterfaces()[0]).getActualTypeArguments()[0])+"[]";
        }else {
            throw new IllegalArgumentException();
        }
    }

    @Override
    public void createProject(String project) {
        checkProject(project);
        queryExecutor.executeRawQuery(format("CREATE TABLE IF NOT EXISTS %s.%s (" +
                "  %s bigint NOT NULL,\n" +
                "  PRIMARY KEY (%s)"+
                ")", project, USER_TABLE, PRIMARY_KEY, PRIMARY_KEY));
    }
}
