/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.phoenix.end2end.index;

import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.phoenix.end2end.ParallelStatsDisabledIT;
import org.apache.phoenix.hbase.index.IndexRegionObserver;
import org.apache.phoenix.hbase.index.Indexer;
import org.apache.phoenix.hbase.index.covered.NonTxIndexBuilder;
import org.apache.phoenix.index.GlobalIndexChecker;
import org.apache.phoenix.index.PhoenixIndexBuilder;
import org.apache.phoenix.index.PhoenixIndexCodec;
import org.apache.phoenix.jdbc.PhoenixConnection;
import org.apache.phoenix.query.QueryServices;
import org.apache.phoenix.query.QueryServicesOptions;
import org.apache.phoenix.util.SchemaUtil;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

@RunWith(Parameterized.class)
public class IndexCoprocIT extends ParallelStatsDisabledIT {
    private boolean isNamespaceMapped = false;
    private boolean isMultiTenant = false;
    public static final String GLOBAL_INDEX_CHECKER_CONFIG =
        "|org.apache.phoenix.index.GlobalIndexChecker|805306365|";
    public static final String INDEX_REGION_OBSERVER_CONFIG =
        "|org.apache.phoenix.hbase.index.IndexRegionObserver" +
            "|805306366|org.apache.hadoop.hbase.index.codec.class=" +
            "org.apache.phoenix.index.PhoenixIndexCodec," +
            "index.builder=org.apache.phoenix.index.PhoenixIndexBuilder";
    public static final String INDEXER_CONFIG =
        "|org.apache.phoenix.hbase.index.Indexer" +
            "|805306366|org.apache.hadoop.hbase.index.codec.class=" +
            "org.apache.phoenix.index.PhoenixIndexCodec," +
            "index.builder=org.apache.phoenix.index.PhoenixIndexBuilder";

    public IndexCoprocIT(boolean isMultiTenant){
        this.isMultiTenant = isMultiTenant;
    }
    @Parameterized.Parameters(name ="CreateIndexCoprocIT_mulitTenant={0}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{{true}, {false}});
    }

    @Test
    public void testCreateCoprocs() throws Exception {
        String schemaName = "S_" + generateUniqueName();
        String tableName = "T_" + generateUniqueName();
        String indexName = "I_" + generateUniqueName();
        String physicalTableName = SchemaUtil.getPhysicalHBaseTableName(schemaName, tableName,
            isNamespaceMapped).getString();
        String physicalIndexName = SchemaUtil.getPhysicalHBaseTableName(schemaName,
            indexName, isNamespaceMapped).getString();
        Admin admin = ((PhoenixConnection) getConnection()).getQueryServices().getAdmin();

        createBaseTable(schemaName, tableName, isMultiTenant, 0, null);
        createIndexTable(schemaName, tableName, indexName);

        HTableDescriptor baseDescriptor = admin.getTableDescriptor(TableName.valueOf(physicalTableName));
        HTableDescriptor indexDescriptor = admin.getTableDescriptor(TableName.valueOf(physicalIndexName));

        assertUsingNewCoprocs(baseDescriptor, indexDescriptor);

        //Simulate an index upgrade rollback by removing coprocs and enabling old Indexer
        downgradeIndexCoprocs(admin, baseDescriptor, indexDescriptor);

        baseDescriptor = admin.getTableDescriptor(TableName.valueOf(physicalTableName));
        indexDescriptor = admin.getTableDescriptor(TableName.valueOf(physicalIndexName));
        assertUsingOldCoprocs(baseDescriptor, indexDescriptor);

        //Now that we've downgraded, we make sure that a create statement won't re-upgrade us
        createBaseTable(schemaName, tableName, true, 0, null);
        baseDescriptor = admin.getTableDescriptor(TableName.valueOf(physicalTableName));
        indexDescriptor = admin.getTableDescriptor(TableName.valueOf(physicalIndexName));
        assertUsingOldCoprocs(baseDescriptor, indexDescriptor);
    }

    private void downgradeIndexCoprocs(Admin admin, HTableDescriptor baseDescriptor,
                                       HTableDescriptor indexDescriptor) throws Exception {
        removeCoproc(IndexRegionObserver.class, baseDescriptor, admin);
        removeCoproc(IndexRegionObserver.class, indexDescriptor, admin);
        removeCoproc(GlobalIndexChecker.class, indexDescriptor, admin);

        Map<String, String> props = new HashMap<String, String>();
        props.put(NonTxIndexBuilder.CODEC_CLASS_NAME_KEY, PhoenixIndexCodec.class.getName());
        Indexer.enableIndexing(baseDescriptor, PhoenixIndexBuilder.class,
            props, QueryServicesOptions.DEFAULT_COPROCESSOR_PRIORITY);
        admin.modifyTable(baseDescriptor.getTableName(), baseDescriptor);
    }

    @Test
    public void testCreateOnExistingHBaseTable() throws Exception {
        String schemaName = generateUniqueName();
        String tableName = generateUniqueName();
        String indexName = generateUniqueName();
        byte[] cf = Bytes.toBytes("f");
        try (PhoenixConnection conn = getConnection()){
            TableName table = TableName.valueOf(SchemaUtil.getPhysicalHBaseTableName(schemaName,
                tableName, isNamespaceMapped).getString());
            HTableDescriptor originalDesc = new HTableDescriptor(table);
            originalDesc.addFamily(new HColumnDescriptor(cf));
            Admin admin = conn.getQueryServices().getAdmin();
            admin.createTable(originalDesc);
            createBaseTable(schemaName, tableName, isMultiTenant, 0, null);
            HTableDescriptor baseDescriptor = admin.getTableDescriptor(table);
            assertUsingNewCoprocs(baseDescriptor);
            createIndexTable(schemaName, tableName, indexName);
            baseDescriptor = admin.getTableDescriptor(table);
            TableName indexTable = TableName.valueOf(SchemaUtil.getPhysicalHBaseTableName(schemaName,
                indexName, isNamespaceMapped).getString());
            HTableDescriptor indexDescriptor = admin.getTableDescriptor(indexTable);
            assertUsingNewCoprocs(baseDescriptor, indexDescriptor);
        }
    }

    @Test
    public void testAlterDoesntChangeCoprocs() throws Exception {
        String schemaName = "S" + generateUniqueName();
        String tableName = "T_" + generateUniqueName();
        String indexName = "I_" + generateUniqueName();
        String physicalTableName = SchemaUtil.getPhysicalHBaseTableName(schemaName, tableName,
            isNamespaceMapped).getString();
        String physicalIndexName = SchemaUtil.getPhysicalHBaseTableName(schemaName,
            indexName, isNamespaceMapped).getString();
        Admin admin = ((PhoenixConnection) getConnection()).getQueryServices().getAdmin();

        createBaseTable(schemaName, tableName, isMultiTenant, 0, null);
        createIndexTable(schemaName, tableName, indexName);
        HTableDescriptor baseDescriptor = admin.getTableDescriptor(TableName.valueOf(physicalTableName));
        HTableDescriptor indexDescriptor = admin.getTableDescriptor(TableName.valueOf(physicalIndexName));

        assertUsingNewCoprocs(baseDescriptor, indexDescriptor);
        String columnName = "foo";
        addColumnToBaseTable(schemaName, tableName, columnName);
        assertUsingNewCoprocs(baseDescriptor, indexDescriptor);
        dropColumnToBaseTable(schemaName, tableName, columnName);
        assertUsingNewCoprocs(baseDescriptor, indexDescriptor);
    }

    private void assertUsingOldCoprocs(HTableDescriptor baseDescriptor,
                                       HTableDescriptor indexDescriptor) {
        assertCoprocsContains(Indexer.class, baseDescriptor);
        assertCoprocConfig(baseDescriptor, Indexer.class.getName(),
            INDEXER_CONFIG);
        assertCoprocsNotContains(IndexRegionObserver.class, baseDescriptor);
        assertCoprocsNotContains(IndexRegionObserver.class, indexDescriptor);
        assertCoprocsNotContains(GlobalIndexChecker.class, indexDescriptor);
    }

    private void assertUsingNewCoprocs(HTableDescriptor baseDescriptor) {
        assertCoprocsContains(IndexRegionObserver.class, baseDescriptor);
        assertCoprocsNotContains(Indexer.class, baseDescriptor);
    }

    private void assertUsingNewCoprocs(HTableDescriptor baseDescriptor,
                                       HTableDescriptor indexDescriptor) {
        assertCoprocsContains(IndexRegionObserver.class, baseDescriptor);
        assertCoprocConfig(baseDescriptor, IndexRegionObserver.class.getName(),
            INDEX_REGION_OBSERVER_CONFIG);
        assertCoprocsNotContains(Indexer.class, baseDescriptor);
        assertCoprocsNotContains(Indexer.class, indexDescriptor);
        assertCoprocsContains(GlobalIndexChecker.class, indexDescriptor);
        assertCoprocConfig(indexDescriptor, GlobalIndexChecker.class.getName(),
            GLOBAL_INDEX_CHECKER_CONFIG);
    }

    private void assertCoprocsContains(Class clazz, HTableDescriptor descriptor) {
        String expectedCoprocName = clazz.getName();
        boolean foundCoproc = descriptor.hasCoprocessor(expectedCoprocName);
        Assert.assertTrue("Could not find coproc " + expectedCoprocName +
            " in descriptor " + descriptor,foundCoproc);
    }

    private void assertCoprocsNotContains(Class clazz, HTableDescriptor descriptor) {
        String expectedCoprocName = clazz.getName();
        boolean foundCoproc = descriptor.hasCoprocessor(expectedCoprocName);
        Assert.assertFalse("Could find coproc " + expectedCoprocName +
            " in descriptor " + descriptor,foundCoproc);
    }

    public static void assertCoprocConfig(HTableDescriptor indexDesc,
                                   String className, String expectedConfigValue){
        boolean foundConfig = false;
        for (Map.Entry<ImmutableBytesWritable, ImmutableBytesWritable> entry :
            indexDesc.getValues().entrySet()){
            String propKey = Bytes.toString(entry.getKey().get());
            String propValue = Bytes.toString(entry.getValue().get());
            //Unfortunately, a good API to read coproc properties didn't show up until
            //HBase 2.0. Doing this the painful String-matching way to be compatible with 1.x
            if (propKey.contains("coprocessor")){
                if (propValue.contains(className)){
                    Assert.assertEquals(className + " is configured incorrectly",
                        expectedConfigValue,
                        propValue);
                    foundConfig = true;
                    break;
                }
            }
        }
        Assert.assertTrue("Couldn't find config for " + className, foundConfig);
    }

    private void removeCoproc(Class clazz, HTableDescriptor descriptor, Admin admin) throws Exception {
        descriptor.removeCoprocessor(clazz.getName());
        admin.modifyTable(descriptor.getTableName(), descriptor);
    }

    private void createIndexTable(String schemaName, String tableName, String indexName)
        throws SQLException {
        Connection conn = getConnection();
        String fullTableName = SchemaUtil.getTableName(schemaName, tableName);
        conn.createStatement().execute("CREATE INDEX " + indexName + " ON " + fullTableName + "(v1)");
    }

    private void addColumnToBaseTable(String schemaName, String tableName, String columnName) throws Exception{
        Connection conn = getConnection();
        String ddl = "ALTER TABLE " + SchemaUtil.getTableName(schemaName, tableName) + " " +
            " ADD " + columnName + " varchar(512)";
        conn.createStatement().execute(ddl);
    }

    private void dropColumnToBaseTable(String schemaName, String tableName, String columnName) throws Exception{
        Connection conn = getConnection();
        String ddl = "ALTER TABLE " + SchemaUtil.getTableName(schemaName, tableName) + " " +
            " DROP COLUMN " + columnName;
        conn.createStatement().execute(ddl);
    }

    private void createBaseTable(String schemaName, String tableName, boolean multiTenant, Integer saltBuckets, String splits)
        throws SQLException {
        Connection conn = getConnection();
        if (isNamespaceMapped) {
            conn.createStatement().execute("CREATE SCHEMA IF NOT EXISTS " + schemaName);
        }
        String ddl = "CREATE TABLE IF NOT EXISTS "
            + SchemaUtil.getTableName(schemaName, tableName) + " (t_id VARCHAR NOT NULL,\n" +
            "k1 VARCHAR NOT NULL,\n" +
            "k2 INTEGER NOT NULL,\n" +
            "v1 VARCHAR,\n" +
            "v2 INTEGER,\n" +
            "CONSTRAINT pk PRIMARY KEY (t_id, k1, k2))\n";
        String ddlOptions = multiTenant ? "MULTI_TENANT=true" : "";
        if (saltBuckets != null) {
            ddlOptions = ddlOptions
                + (ddlOptions.isEmpty() ? "" : ",")
                + "salt_buckets=" + saltBuckets;
        }
        if (splits != null) {
            ddlOptions = ddlOptions
                + (ddlOptions.isEmpty() ? "" : ",")
                + "splits=" + splits;
        }
        conn.createStatement().execute(ddl + ddlOptions);
        conn.close();
    }

    private PhoenixConnection getConnection() throws SQLException{
        Properties props = new Properties();
        props.setProperty(QueryServices.IS_NAMESPACE_MAPPING_ENABLED, Boolean.toString(isNamespaceMapped));
        return (PhoenixConnection) DriverManager.getConnection(getUrl(),props);
    }

}
