/*
 * Copyright 2014 The Apache Software Foundation
 *
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
package org.apache.phoenix.index;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.util.Pair;

import com.google.common.collect.Lists;
import org.apache.hadoop.hbase.index.ValueGetter;
import org.apache.hadoop.hbase.index.covered.IndexCodec;
import org.apache.hadoop.hbase.index.covered.IndexUpdate;
import org.apache.hadoop.hbase.index.covered.TableState;
import org.apache.hadoop.hbase.index.scanner.Scanner;
import org.apache.hadoop.hbase.index.util.ImmutableBytesPtr;
import org.apache.hadoop.hbase.index.util.IndexManagementUtil;
import org.apache.hadoop.hbase.index.write.IndexWriter;
import org.apache.phoenix.cache.GlobalCache;
import org.apache.phoenix.cache.IndexMetaDataCache;
import org.apache.phoenix.cache.ServerCacheClient;
import org.apache.phoenix.cache.TenantCache;
import org.apache.phoenix.client.KeyValueBuilder;
import org.apache.phoenix.exception.SQLExceptionCode;
import org.apache.phoenix.exception.SQLExceptionInfo;
import org.apache.phoenix.util.PhoenixRuntime;
import org.apache.phoenix.util.ServerUtil;

/**
 * Phoenix-based {@link IndexCodec}. Manages all the logic of how to cleanup an index (
 * {@link #getIndexDeletes(TableState)}) as well as what the new index state should be (
 * {@link #getIndexUpserts(TableState)}).
 */
public class PhoenixIndexCodec extends BaseIndexCodec {
    public static final String INDEX_MD = "IdxMD";
    public static final String INDEX_UUID = "IdxUUID";

    private RegionCoprocessorEnvironment env;
    private KeyValueBuilder builder;

    @Override
    public void initialize(RegionCoprocessorEnvironment env) {
        this.env = env;
        Configuration conf = env.getConfiguration();
        // Install handler that will attempt to disable the index first before killing the region
        // server
        conf.setIfUnset(IndexWriter.INDEX_FAILURE_POLICY_CONF_KEY,
            PhoenixIndexFailurePolicy.class.getName());
        this.builder = KeyValueBuilder.get(env.getHBaseVersion());
    }

    List<IndexMaintainer> getIndexMaintainers(Map<String, byte[]> attributes) throws IOException{
        if (attributes == null) {
            return Collections.emptyList();
        }
        byte[] uuid = attributes.get(INDEX_UUID);
        if (uuid == null) {
            return Collections.emptyList();
        }
        byte[] md = attributes.get(INDEX_MD);
        List<IndexMaintainer> indexMaintainers;
        if (md != null) {
            indexMaintainers = IndexMaintainer.deserialize(md, builder);
        } else {
            byte[] tenantIdBytes = attributes.get(PhoenixRuntime.TENANT_ID_ATTRIB);
            ImmutableBytesWritable tenantId =
                tenantIdBytes == null ? null : new ImmutableBytesWritable(tenantIdBytes);
            TenantCache cache = GlobalCache.getTenantCache(env, tenantId);
            IndexMetaDataCache indexCache =
                (IndexMetaDataCache) cache.getServerCache(new ImmutableBytesPtr(uuid));
            if (indexCache == null) {
                String msg = "key="+ServerCacheClient.idToString(uuid) + " region=" + env.getRegion();
                SQLException e = new SQLExceptionInfo.Builder(SQLExceptionCode.INDEX_METADATA_NOT_FOUND)
                    .setMessage(msg).build().buildException();
                ServerUtil.throwIOException("Index update failed", e); // will not return
            }
            indexMaintainers = indexCache.getIndexMaintainers();
        }
    
        return indexMaintainers;
    }
    
    @Override
    public Iterable<IndexUpdate> getIndexUpserts(TableState state) throws IOException {
        List<IndexMaintainer> indexMaintainers = getIndexMaintainers(state.getUpdateAttributes());
        if (indexMaintainers.isEmpty()) {
            return Collections.emptyList();
        }
        ImmutableBytesWritable ptr = new ImmutableBytesWritable();
        List<IndexUpdate> indexUpdates = Lists.newArrayList();
        // TODO: state.getCurrentRowKey() should take an ImmutableBytesWritable arg to prevent byte copy
        byte[] dataRowKey = state.getCurrentRowKey();
        for (IndexMaintainer maintainer : indexMaintainers) {
            // Short-circuit building state when we know it's a row deletion
            if (maintainer.isRowDeleted(state.getPendingUpdate())) {
                continue;
            }

            // Get a scanner over the columns this maintainer would like to look at
            // Any updates that we would make for those columns are then added to the index update
            Pair<Scanner,IndexUpdate> statePair = state.getIndexedColumnsTableState(maintainer.getAllColumns());
            IndexUpdate indexUpdate = statePair.getSecond();
            Scanner scanner = statePair.getFirst();

            // get the values from the scanner so we can actually use them
            ValueGetter valueGetter = IndexManagementUtil.createGetterFromScanner(scanner, dataRowKey);
            ptr.set(dataRowKey);
            Put put = maintainer.buildUpdateMutation(valueGetter, ptr, state.getCurrentTimestamp());
            indexUpdate.setTable(maintainer.getIndexTableName());
            indexUpdate.setUpdate(put);
            //make sure we close the scanner when we are done
            scanner.close();
            indexUpdates.add(indexUpdate);
        }
        return indexUpdates;
    }

    @Override
    public Iterable<IndexUpdate> getIndexDeletes(TableState state) throws IOException {
        List<IndexMaintainer> indexMaintainers = getIndexMaintainers(state.getUpdateAttributes());
        if (indexMaintainers.isEmpty()) {
            return Collections.emptyList();
        }
        List<IndexUpdate> indexUpdates = Lists.newArrayList();
        ImmutableBytesWritable ptr = new ImmutableBytesWritable();
        // TODO: state.getCurrentRowKey() should take an ImmutableBytesWritable arg to prevent byte copy
        byte[] dataRowKey = state.getCurrentRowKey();
        for (IndexMaintainer maintainer : indexMaintainers) {
            // TODO: if more efficient, I could do this just once with all columns in all indexes
            Pair<Scanner,IndexUpdate> statePair = state.getIndexedColumnsTableState(maintainer.getAllColumns());
            Scanner scanner = statePair.getFirst();
            IndexUpdate indexUpdate = statePair.getSecond();
            indexUpdate.setTable(maintainer.getIndexTableName());
            ValueGetter valueGetter = IndexManagementUtil.createGetterFromScanner(scanner, dataRowKey);
            ptr.set(dataRowKey);
            Delete delete =
                maintainer.buildDeleteMutation(valueGetter, ptr, state.getPendingUpdate(),
                  state.getCurrentTimestamp());
            scanner.close();
            indexUpdate.setUpdate(delete);
            indexUpdates.add(indexUpdate);
        }
        return indexUpdates;
    }
    
  @Override
  public boolean isEnabled(Mutation m) throws IOException {
      return !getIndexMaintainers(m.getAttributesMap()).isEmpty();
  }
  
  @Override
  public byte[] getBatchId(Mutation m) {
    Map<String, byte[]> attributes = m.getAttributesMap();
    return attributes.get(INDEX_UUID);
  }
}