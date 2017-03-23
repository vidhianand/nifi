/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.hbase;

import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.nifi.controller.ConfigurationContext;
import org.apache.nifi.hadoop.KerberosProperties;
import org.apache.nifi.hbase.put.PutColumn;
import org.apache.nifi.hbase.scan.Column;
import org.apache.nifi.hbase.scan.ResultCell;
import org.apache.nifi.hbase.scan.ResultHandler;
import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import org.apache.nifi.distributed.cache.client.DistributedMapCacheClient;
import org.apache.nifi.distributed.cache.client.Serializer;
import org.apache.nifi.distributed.cache.client.exception.SerializationException;
import org.apache.nifi.distributed.cache.client.Deserializer;
import org.apache.nifi.distributed.cache.client.exception.DeserializationException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

import java.io.OutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TestHBase_1_1_2_ClientMapCacheService {

    private KerberosProperties kerberosPropsWithFile;
    private KerberosProperties kerberosPropsWithoutFile;

    private Serializer<String> stringSerializer = new StringSerializer();
    private Deserializer<String> stringDeserializer = new StringDeserializer();

    @Before
    public void setup() {
        // needed for calls to UserGroupInformation.setConfiguration() to work when passing in
        // config with Kerberos authentication enabled
        System.setProperty("java.security.krb5.realm", "nifi.com");
        System.setProperty("java.security.krb5.kdc", "nifi.kdc");

        kerberosPropsWithFile = new KerberosProperties(new File("src/test/resources/krb5.conf"));

        kerberosPropsWithoutFile = new KerberosProperties(null);
    }

    private final String tableName = "nifi";
    private final String columnFamily = "family1";
    private final String columnQualifier = "qualifier1";


    @Test
    public void testPut() throws InitializationException, IOException {
        final String row = "row1";
        final String content = "content1";

        final TestRunner runner = TestRunners.newTestRunner(TestProcessor.class);

        // Mock an HBase Table so we can verify the put operations later
        final Table table = Mockito.mock(Table.class);
        when(table.getName()).thenReturn(TableName.valueOf(tableName));

        // create the controller service and link it to the test processor
        final MockHBaseClientService service = configureHBaseClientService(runner, table);
        runner.assertValid(service);

        final HBaseClientService hBaseClientService = runner.getProcessContext().getProperty(TestProcessor.HBASE_CLIENT_SERVICE)
            .asControllerService(HBaseClientService.class);

        final DistributedMapCacheClient cacheService = configureHBaseCacheService(runner, hBaseClientService);
        runner.assertValid(cacheService);

        // try to put a single cell
        final DistributedMapCacheClient hBaseCacheService = runner.getProcessContext().getProperty(TestProcessor.HBASE_CACHE_SERVICE)
                .asControllerService(DistributedMapCacheClient.class);

        hBaseCacheService.put( row, content, stringSerializer, stringSerializer);

        // verify only one call to put was made
        ArgumentCaptor<Put> capture = ArgumentCaptor.forClass(Put.class);
        verify(table, times(1)).put(capture.capture());

        verifyPut(row, columnFamily, columnQualifier, content, capture.getValue());
    }

    @Test
    public void testGet() throws InitializationException, IOException {
        final String row = "row1";
        final String content = "content1";

        final TestRunner runner = TestRunners.newTestRunner(TestProcessor.class);

        // Mock an HBase Table so we can verify the put operations later
        final Table table = Mockito.mock(Table.class);
        when(table.getName()).thenReturn(TableName.valueOf(tableName));

        // create the controller service and link it to the test processor
        final MockHBaseClientService service = configureHBaseClientService(runner, table);
        runner.assertValid(service);

        final HBaseClientService hBaseClientService = runner.getProcessContext().getProperty(TestProcessor.HBASE_CLIENT_SERVICE)
            .asControllerService(HBaseClientService.class);

        final DistributedMapCacheClient cacheService = configureHBaseCacheService(runner, hBaseClientService);
        runner.assertValid(cacheService);

        final DistributedMapCacheClient hBaseCacheService = runner.getProcessContext().getProperty(TestProcessor.HBASE_CACHE_SERVICE)
                .asControllerService(DistributedMapCacheClient.class);

        hBaseCacheService.put( row, content, stringSerializer, stringSerializer);

        final String result = hBaseCacheService.get(row, stringSerializer, stringDeserializer);

        assertEquals( content, result);

    }

    @Test
    public void testContainsKey() throws InitializationException, IOException {
        final String row = "row1";
        final String content = "content1";

        final TestRunner runner = TestRunners.newTestRunner(TestProcessor.class);

        // Mock an HBase Table so we can verify the put operations later
        final Table table = Mockito.mock(Table.class);
        when(table.getName()).thenReturn(TableName.valueOf(tableName));

        // create the controller service and link it to the test processor
        final MockHBaseClientService service = configureHBaseClientService(runner, table);
        runner.assertValid(service);

        final HBaseClientService hBaseClientService = runner.getProcessContext().getProperty(TestProcessor.HBASE_CLIENT_SERVICE)
            .asControllerService(HBaseClientService.class);

        final DistributedMapCacheClient cacheService = configureHBaseCacheService(runner, hBaseClientService);
        runner.assertValid(cacheService);

        final DistributedMapCacheClient hBaseCacheService = runner.getProcessContext().getProperty(TestProcessor.HBASE_CACHE_SERVICE)
                .asControllerService(DistributedMapCacheClient.class);

        assertFalse( hBaseCacheService.containsKey(row , stringSerializer ) );

        hBaseCacheService.put( row, content, stringSerializer, stringSerializer);

        assertTrue( hBaseCacheService.containsKey(row, stringSerializer) );
    }

    @Test
    public void testPutIfAbsent() throws InitializationException, IOException {
        final String row = "row1";
        final String content = "content1";

        final TestRunner runner = TestRunners.newTestRunner(TestProcessor.class);

        // Mock an HBase Table so we can verify the put operations later
        final Table table = Mockito.mock(Table.class);
        when(table.getName()).thenReturn(TableName.valueOf(tableName));

        // create the controller service and link it to the test processor
        final MockHBaseClientService service = configureHBaseClientService(runner, table);
        runner.assertValid(service);

        final HBaseClientService hBaseClientService = runner.getProcessContext().getProperty(TestProcessor.HBASE_CLIENT_SERVICE)
            .asControllerService(HBaseClientService.class);

        final DistributedMapCacheClient cacheService = configureHBaseCacheService(runner, hBaseClientService);
        runner.assertValid(cacheService);

        final DistributedMapCacheClient hBaseCacheService = runner.getProcessContext().getProperty(TestProcessor.HBASE_CACHE_SERVICE)
                .asControllerService(DistributedMapCacheClient.class);

        assertTrue( hBaseCacheService.putIfAbsent( row, content, stringSerializer, stringSerializer));

        // verify only one call to put was made
        ArgumentCaptor<Put> capture = ArgumentCaptor.forClass(Put.class);
        verify(table, times(1)).put(capture.capture());

        verifyPut(row, columnFamily, columnQualifier, content, capture.getValue());

        assertFalse( hBaseCacheService.putIfAbsent( row, content, stringSerializer, stringSerializer));

        verify(table, times(1)).put(capture.capture());
    }

    @Test
    public void testGetAndPutIfAbsent() throws InitializationException, IOException {
        final String row = "row1";
        final String content = "content1";

        final TestRunner runner = TestRunners.newTestRunner(TestProcessor.class);

        // Mock an HBase Table so we can verify the put operations later
        final Table table = Mockito.mock(Table.class);
        when(table.getName()).thenReturn(TableName.valueOf(tableName));

        // create the controller service and link it to the test processor
        final MockHBaseClientService service = configureHBaseClientService(runner, table);
        runner.assertValid(service);

        final HBaseClientService hBaseClientService = runner.getProcessContext().getProperty(TestProcessor.HBASE_CLIENT_SERVICE)
            .asControllerService(HBaseClientService.class);

        final DistributedMapCacheClient cacheService = configureHBaseCacheService(runner, hBaseClientService);
        runner.assertValid(cacheService);

        final DistributedMapCacheClient hBaseCacheService = runner.getProcessContext().getProperty(TestProcessor.HBASE_CACHE_SERVICE)
                .asControllerService(DistributedMapCacheClient.class);

        assertNull( hBaseCacheService.getAndPutIfAbsent( row, content, stringSerializer, stringSerializer, stringDeserializer));

        // verify only one call to put was made
        ArgumentCaptor<Put> capture = ArgumentCaptor.forClass(Put.class);
        verify(table, times(1)).put(capture.capture());

        verifyPut(row, columnFamily, columnQualifier, content, capture.getValue());

        final String result = hBaseCacheService.getAndPutIfAbsent( row, content, stringSerializer, stringSerializer, stringDeserializer);

        verify(table, times(1)).put(capture.capture());

        assertEquals( result, content);
    }


    private MockHBaseClientService configureHBaseClientService(final TestRunner runner, final Table table) throws InitializationException {
        final MockHBaseClientService service = new MockHBaseClientService(table, kerberosPropsWithFile);
        runner.addControllerService("hbaseClient", service);
        runner.setProperty(service, HBase_1_1_2_ClientService.HADOOP_CONF_FILES, "src/test/resources/hbase-site.xml");
        runner.enableControllerService(service);
        runner.setProperty(TestProcessor.HBASE_CLIENT_SERVICE, "hbaseClient");
        return service;
    }

    private DistributedMapCacheClient configureHBaseCacheService(final TestRunner runner, final HBaseClientService service) throws InitializationException {
        final HBase_1_1_2_ClientMapCacheService cacheService = new HBase_1_1_2_ClientMapCacheService();
        runner.addControllerService("hbaseCache", cacheService);
        runner.setProperty(cacheService, HBase_1_1_2_ClientMapCacheService.HBASE_CLIENT_SERVICE, "hbaseClient");
        runner.setProperty(cacheService, HBase_1_1_2_ClientMapCacheService.HBASE_CACHE_TABLE_NAME, tableName);
        runner.setProperty(cacheService, HBase_1_1_2_ClientMapCacheService.HBASE_COLUMN_FAMILY, columnFamily);
        runner.setProperty(cacheService, HBase_1_1_2_ClientMapCacheService.HBASE_COLUMN_QUALIFIER, columnQualifier);
        runner.enableControllerService(cacheService);
        runner.setProperty(TestProcessor.HBASE_CACHE_SERVICE,"hbaseCache");
        return cacheService;
    }

    private void verifyResultCell(final ResultCell result, final String cf, final String cq, final String val) {
        final String colFamily = new String(result.getFamilyArray(), result.getFamilyOffset(), result.getFamilyLength());
        assertEquals(cf, colFamily);

        final String colQualifier = new String(result.getQualifierArray(), result.getQualifierOffset(), result.getQualifierLength());
        assertEquals(cq, colQualifier);

        final String value = new String(result.getValueArray(), result.getValueOffset(), result.getValueLength());
        assertEquals(val, value);
    }

    private void verifyPut(String row, String columnFamily, String columnQualifier, String content, Put put) {
        assertEquals(row, new String(put.getRow()));

        NavigableMap<byte [], List<Cell>> familyCells = put.getFamilyCellMap();
        assertEquals(1, familyCells.size());

        Map.Entry<byte[], List<Cell>> entry = familyCells.firstEntry();
        assertEquals(columnFamily, new String(entry.getKey()));
        assertEquals(1, entry.getValue().size());

        Cell cell = entry.getValue().get(0);
        assertEquals(columnQualifier, new String(cell.getQualifierArray(), cell.getQualifierOffset(), cell.getQualifierLength()));
        assertEquals(content, new String(cell.getValueArray(), cell.getValueOffset(), cell.getValueLength()));
    }

    // Override methods to create a mock service that can return staged data
    private class MockHBaseClientService extends HBase_1_1_2_ClientService {

        private Table table;
        private List<Result> results = new ArrayList<>();
        private KerberosProperties kerberosProperties;

        public MockHBaseClientService(final Table table, final KerberosProperties kerberosProperties) {
            this.table = table;
            this.kerberosProperties = kerberosProperties;
        }

        @Override
        protected KerberosProperties getKerberosProperties(File kerberosConfigFile) {
            return kerberosProperties;
        }

        protected void setKerberosProperties(KerberosProperties properties) {
            this.kerberosProperties = properties;

        }

        public void addResult(final String rowKey, final Map<String, String> cells, final long timestamp) {
            final byte[] rowArray = rowKey.getBytes(StandardCharsets.UTF_8);
            final Cell[] cellArray = new Cell[cells.size()];
            int i = 0;
            for (final Map.Entry<String, String> cellEntry : cells.entrySet()) {
                final Cell cell = Mockito.mock(Cell.class);
                when(cell.getRowArray()).thenReturn(rowArray);
                when(cell.getRowOffset()).thenReturn(0);
                when(cell.getRowLength()).thenReturn((short) rowArray.length);

                final String cellValue = cellEntry.getValue();
                final byte[] valueArray = cellValue.getBytes(StandardCharsets.UTF_8);
                when(cell.getValueArray()).thenReturn(valueArray);
                when(cell.getValueOffset()).thenReturn(0);
                when(cell.getValueLength()).thenReturn(valueArray.length);

                final byte[] familyArray = "family1".getBytes(StandardCharsets.UTF_8);
                when(cell.getFamilyArray()).thenReturn(familyArray);
                when(cell.getFamilyOffset()).thenReturn(0);
                when(cell.getFamilyLength()).thenReturn((byte) familyArray.length);

                final String qualifier = cellEntry.getKey();
                final byte[] qualifierArray = qualifier.getBytes(StandardCharsets.UTF_8);
                when(cell.getQualifierArray()).thenReturn(qualifierArray);
                when(cell.getQualifierOffset()).thenReturn(0);
                when(cell.getQualifierLength()).thenReturn(qualifierArray.length);

                when(cell.getTimestamp()).thenReturn(timestamp);

                cellArray[i++] = cell;
            }

            final Result result = Mockito.mock(Result.class);
            when(result.getRow()).thenReturn(rowArray);
            when(result.rawCells()).thenReturn(cellArray);
            results.add(result);
        }

        @Override
        public void put(final String tableName, final byte[] rowId, final Collection<PutColumn> columns) throws IOException {
            Put put = new Put(rowId);
            Map<String,String> map = new HashMap<String,String>();
            for (final PutColumn column : columns) {
                put.addColumn(
                        column.getColumnFamily(),
                        column.getColumnQualifier(),
                        column.getBuffer());
                map.put( new String( column.getColumnQualifier() ), new String(column.getBuffer()) );
            }
            table.put(put);

            addResult( new String(rowId) , map,1);
        }

        @Override
        public boolean checkAndPut(final String tableName, final byte[] rowId, final byte[] family, final byte[] qualifier, final byte[] value, final PutColumn column) throws IOException {

            for (Result result: results){
                if ( Arrays.equals(result.getRow(), rowId)){
                    Cell[] cellArray = result.rawCells();
                    for (Cell cell : cellArray){
                        if( Arrays.equals(cell.getFamilyArray(), family) && Arrays.equals(cell.getQualifierArray(), qualifier)){
                            //throw new RuntimeException( new String(cell.getValueArray()) );
                            if( value == null || Arrays.equals(cell.getValueArray(), value)) return false;
                        }
                    }
                }
            }
            final List<PutColumn> putColumns = new ArrayList<PutColumn>();
            putColumns.add(column);
            put(tableName, rowId, putColumns );
            return true;
        }

        @Override
        public void scan(final String tableName, final byte[] startRow, final byte[] endRow, final Collection<Column> columns, final ResultHandler handler) throws IOException {
            if (startRow != endRow) throw new RuntimeException("Start and end must be equal");
            for(Result result: results){
                if (Arrays.equals( result.getRow() , startRow)) {
                    final Cell[] cellArray = result.rawCells();
                    final ResultCell[] resultCells = new ResultCell[cellArray.length ];
                    int i=0;
                    for (Cell cell : cellArray){
                        ResultCell resultCell = new ResultCell();
                        resultCell.setRowArray( result.getRow());
                        resultCell.setFamilyArray(cell.getFamilyArray());
                        resultCell.setQualifierArray(cell.getQualifierArray());
                        resultCell.setValueArray(cell.getValueArray());
                        resultCells[i++]=resultCell;
                    }
                    handler.handle(result.getRow(), resultCells );
                }
            }
        }

        @Override
        protected ResultScanner getResults(Table table, Collection<Column> columns, Filter filter, long minTime) throws IOException {
            final ResultScanner scanner = Mockito.mock(ResultScanner.class);
            Mockito.when(scanner.iterator()).thenReturn(results.iterator());
            return scanner;
        }

        @Override
        protected Connection createConnection(ConfigurationContext context) throws IOException {
            Connection connection = Mockito.mock(Connection.class);
            Mockito.when(connection.getTable(table.getName())).thenReturn(table);
            return connection;
        }
    }

    // handler that saves results for verification
    private static final class CollectingResultHandler implements ResultHandler {

        Map<String,ResultCell[]> results = new LinkedHashMap<>();

        @Override
        public void handle(byte[] row, ResultCell[] resultCells) {
            final String rowStr = new String(row, StandardCharsets.UTF_8);
            results.put(rowStr, resultCells);
        }
    }

    private static class StringSerializer implements Serializer<String> {
        @Override
        public void serialize(final String value, final OutputStream out) throws SerializationException, IOException {
            out.write(value.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static class StringDeserializer implements Deserializer<String> {
        @Override
        public String deserialize(byte[] input) throws DeserializationException, IOException{
            return new String(input);
        }
    }
}
