
package com.facebook.presto.elasticsearch;

import com.facebook.presto.spi.type.Type;
import com.google.common.base.Function;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Resources;
import io.airlift.json.JsonCodec;
import org.elasticsearch.action.admin.indices.mapping.get.GetMappingsRequest;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.cluster.metadata.MappingMetaData;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.hppc.cursors.ObjectCursor;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import javax.inject.Inject;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import static com.facebook.presto.spi.type.BigintType.BIGINT;
import static com.facebook.presto.spi.type.DoubleType.DOUBLE;
import static com.facebook.presto.spi.type.VarcharType.VARCHAR;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Iterables.transform;
import static com.google.common.collect.Maps.transformValues;
import static com.google.common.collect.Maps.uniqueIndex;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Locale.ENGLISH;

public class ElasticsearchClient
{
    /**
     * SchemaName -> (TableName -> TableMetadata)
     */
    private Supplier<Map<String, Map<String, ElasticsearchTable>>> schemas;
    private ElasticsearchConfig config;
    private JsonCodec<Map<String, List<ElasticsearchTable>>> catalogCodec;

    @Inject
    public ElasticsearchClient(ElasticsearchConfig config, JsonCodec<Map<String, List<ElasticsearchTable>>> catalogCodec)
            throws IOException
    {
        checkNotNull(config, "config is null");
        checkNotNull(catalogCodec, "catalogCodec is null");

        this.config = config;
        this.catalogCodec = catalogCodec;

        schemas = Suppliers.memoize(schemasSupplier(catalogCodec, config.getMetadata()));
    }

    public Set<String> getSchemaNames()
    {
        return schemas.get().keySet();
    }

    public Set<String> getTableNames(String schema)
    {
        checkNotNull(schema, "schema is null");
        Map<String, ElasticsearchTable> tables = schemas.get().get(schema);
        if (tables == null) {
            return ImmutableSet.of();
        }
        return tables.keySet();
    }

    public ElasticsearchTable getTable(String schema, String tableName)
    {
        try {
            updateSchemas();
        }
        catch (IOException e) {
            e.printStackTrace();
        }

        checkNotNull(schema, "schema is null");
        checkNotNull(tableName, "tableName is null");
        Map<String, ElasticsearchTable> tables = schemas.get().get(schema.toLowerCase(ENGLISH));
        if (tables == null) {
            return null;
        }
        return tables.get(tableName.toLowerCase(ENGLISH));
    }

    Map<String, Map<String, ElasticsearchTable>> updateSchemas()
            throws IOException
    {
        schemas = Suppliers.memoize(schemasSupplier(catalogCodec, config.getMetadata()));

        Map<String, Map<String, ElasticsearchTable>> schemasMap = schemas.get();
        for (Map.Entry<String, Map<String, ElasticsearchTable>> schemaEntry : schemasMap.entrySet()) {

            Map<String, ElasticsearchTable> tablesMap = schemaEntry.getValue();
            for (Map.Entry<String, ElasticsearchTable> tableEntry : tablesMap.entrySet()) {

                updateTableColumns(tableEntry.getValue());
            }
        }
        schemas = Suppliers.memoize(Suppliers.ofInstance(schemasMap));

        return schemasMap;
    }

    ImmutableOpenMap<String, ImmutableOpenMap<String, MappingMetaData>> getMappings(ElasticsearchTableSource src)
            throws ExecutionException, InterruptedException
    {
        int port = src.getPort();
        String hostaddress = src.getHostaddress();
        String clusterName = src.getClusterName();
        String index = src.getIndex();
        String type = src.getType();

        System.out.println("connecting ....");
        System.out.println("hostaddress :" + hostaddress);
        System.out.println("port :" + port);
        System.out.println("clusterName :" + clusterName);
        System.out.println("index :" + index);
        System.out.println("type :" + type);

        Settings settings = ImmutableSettings.settingsBuilder()
                .put("cluster.name", clusterName)
                .build();

        try (Client client = new TransportClient(settings).addTransportAddress(new InetSocketTransportAddress(hostaddress, port))) {

            GetMappingsRequest mappingsRequest = new GetMappingsRequest().types(type);

            // an index is optional - if no index is configured for the table, it will retrieve all indices for the doc type
            if (index != null && !index.isEmpty()) {
                mappingsRequest.indices(index);
            }

            return client
                    .admin()
                    .indices()
                    .getMappings(mappingsRequest)
                    .get()
                    .getMappings();
        }
    }

    Set<ElasticsearchColumn> getColumns(ElasticsearchTableSource src)
            throws ExecutionException, InterruptedException, IOException, JSONException
    {
        Set<ElasticsearchColumn> result = new HashSet();
        String type = src.getType();
        ImmutableOpenMap<String, ImmutableOpenMap<String, MappingMetaData>> allMappings = getMappings(src);

        // what makes sense is to get the reunion of all the columns from all the mappings for the specified document type
        for (ObjectCursor<String> currentIndex : allMappings.keys()) {

            MappingMetaData mappingMetaData = allMappings.get(currentIndex.value).get(type);
            JSONObject json = new JSONObject(mappingMetaData.source().toString())
                    .getJSONObject(type)
                    .getJSONObject("properties");

            List<String> allColumnMetadata = getColumnsMetadata(null, json);
            for (String columnMetadata : allColumnMetadata) {
                ElasticsearchColumn clm = createColumn(columnMetadata);
                if (!(clm == null)) {
                    result.add(clm);
                }
            }
        }

        return result;
    }

    List<String> getColumnsMetadata(String parent, JSONObject json)
            throws JSONException
    {
        List<String> leaves = new ArrayList();

        Iterator it = json.keys();
        while (it.hasNext()) {
            String key = (String) it.next();
            Object child = json.get(key);
            String childKey = parent == null || parent.isEmpty() ? key : parent.concat(".").concat(key);

            if (child instanceof JSONObject) {
                leaves.addAll(getColumnsMetadata(childKey, (JSONObject) child));
            }
            else if (child instanceof JSONArray) {
                // ignoring arrays for now
                continue;
            }
            else {
                leaves.add(childKey.concat(":").concat(child.toString()));
            }
        }

        return leaves;
    }

    ElasticsearchColumn createColumn(String fieldPath_Type)
            throws JSONException, IOException
    {
        String[] items = fieldPath_Type.split(":");
        String type = items[1];
        String path = items[0];
        Type prestoType;

        if (items.length != 2) {
            System.out.println("Invalid column path format. Ignoring...");
            return null;
        }
        if (!path.endsWith(".type")) {
            System.out.println("Invalid column has no type info. Ignoring...");
            return null;
        }

        if (path.contains(".properties.")) {
            System.out.println("Invalid complex column type. Ignoring...");
            return null;
        }

        switch (type) {

            case "double":
            case "float":
                prestoType = DOUBLE;
                break;
            case "integer":
            case "long":
                prestoType = BIGINT;
                break;
            case "string":
                prestoType = VARCHAR;
                break;
            default:
                System.out.println("Unsupported column type. Ignoring...");
                return null;
        }

        path = path.substring(0, path.lastIndexOf('.'));
        //path = path.replaceAll("\\.properties\\.", ".");
        return new ElasticsearchColumn(path.replaceAll("\\.", "_"), prestoType, path, type);
    }

    void updateTableColumns(ElasticsearchTable table)
    {
        Set<ElasticsearchColumn> columns = new HashSet();

        // the table can have multiple sources
        // the column set should be the reunion of all
        for (ElasticsearchTableSource src : table.getSources()) {
            try {
                columns.addAll(getColumns(src));
            }
            catch (ExecutionException | InterruptedException | IOException | JSONException e) {
                e.printStackTrace();
            }
        }

        table.setColumns(columns
                .stream()
                .collect(Collectors.toList()));
        table.setColumnsMetadata(columns
                .stream()
                .map(ElasticsearchColumnMetadata::new)
                .collect(Collectors.toList()));
    }

    static Map<String, Map<String, ElasticsearchTable>> lookupSchemas(URI metadataUri, JsonCodec<Map<String, List<ElasticsearchTable>>> catalogCodec)
            throws IOException
    {
        URL url = metadataUri.toURL();
        System.out.println("url: " + url);

        String tableMappings = Resources.toString(url, UTF_8);
        System.out.println("tableMappings: " + tableMappings);

        Map<String, List<ElasticsearchTable>> catalog = catalogCodec.fromJson(tableMappings);

        return ImmutableMap.copyOf(
                transformValues(
                        catalog,
                        resolveAndIndexTablesFunction()));
    }

    static Supplier<Map<String, Map<String, ElasticsearchTable>>> schemasSupplier(final JsonCodec<Map<String, List<ElasticsearchTable>>> catalogCodec, final URI metadataUri)
    {
        return () -> {
            try {
                return lookupSchemas(metadataUri, catalogCodec);
            }
            catch (IOException e) {
                throw Throwables.propagate(e);
            }
        };
    }

    static Function<List<ElasticsearchTable>, Map<String, ElasticsearchTable>> resolveAndIndexTablesFunction()
    {
        return tables -> ImmutableMap.copyOf(
                uniqueIndex(
                        transform(
                                tables,
                                table -> new ElasticsearchTable(table.getName(), table.getSources())),
                        ElasticsearchTable::getName));
    }
}
