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
package org.apache.phoenix.cache;

import static org.apache.phoenix.query.QueryServices.MAX_MEMORY_PERC_ATTRIB;
import static org.apache.phoenix.query.QueryServices.MAX_MEMORY_WAIT_MS_ATTRIB;
import static org.apache.phoenix.query.QueryServices.MAX_TENANT_MEMORY_PERC_ATTRIB;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;

import org.apache.hadoop.hbase.index.util.ImmutableBytesPtr;
import org.apache.phoenix.memory.ChildMemoryManager;
import org.apache.phoenix.memory.GlobalMemoryManager;
import org.apache.phoenix.query.QueryServices;
import org.apache.phoenix.query.QueryServicesOptions;
import org.apache.phoenix.schema.PTable;


/**
 * 
 * Global root cache for the server. Each tenant is managed as a child tenant cache of this one. Queries
 * not associated with a particular tenant use this as their tenant cache.
 *
 * 
 * @since 0.1
 */
public class GlobalCache extends TenantCacheImpl {
    private static GlobalCache INSTANCE; 
    
    private final Configuration config;
    // TODO: Use Guava cache with auto removal after lack of access 
    private final ConcurrentMap<ImmutableBytesWritable,TenantCache> perTenantCacheMap = new ConcurrentHashMap<ImmutableBytesWritable,TenantCache>();
    // Cache for lastest PTable for a given Phoenix table
    private final ConcurrentHashMap<ImmutableBytesPtr,PTable> metaDataCacheMap = new ConcurrentHashMap<ImmutableBytesPtr,PTable>();
    
    public static synchronized GlobalCache getInstance(RegionCoprocessorEnvironment env) {
        // See http://www.cs.umd.edu/~pugh/java/memoryModel/DoubleCheckedLocking.html
        // for explanation of why double locking doesn't work. 
        if (INSTANCE == null) {
            INSTANCE = new GlobalCache(env.getConfiguration());
        }
        return INSTANCE;
    }
    
    public ConcurrentHashMap<ImmutableBytesPtr,PTable> getMetaDataCache() {
        return metaDataCacheMap;
    }
    
    /**
     * Get the tenant cache associated with the tenantId. If tenantId is not applicable, null may be
     * used in which case a global tenant cache is returned.
     * @param env the HBase configuration
     * @param tenantId the tenant ID or null if not applicable.
     * @return TenantCache
     */
    public static TenantCache getTenantCache(RegionCoprocessorEnvironment env, ImmutableBytesWritable tenantId) {
        GlobalCache globalCache = GlobalCache.getInstance(env);
        TenantCache tenantCache = tenantId == null ? globalCache : globalCache.getChildTenantCache(tenantId);      
        return tenantCache;
    }
    
    private GlobalCache(Configuration config) {
        super(new GlobalMemoryManager(Runtime.getRuntime().totalMemory() * 
                                          config.getInt(MAX_MEMORY_PERC_ATTRIB, QueryServicesOptions.DEFAULT_MAX_MEMORY_PERC) / 100,
                                      config.getInt(MAX_MEMORY_WAIT_MS_ATTRIB, QueryServicesOptions.DEFAULT_MAX_MEMORY_WAIT_MS)),
              config.getInt(QueryServices.MAX_SERVER_CACHE_TIME_TO_LIVE_MS, QueryServicesOptions.DEFAULT_MAX_SERVER_CACHE_TIME_TO_LIVE_MS));
        this.config = config;
    }
    
    public Configuration getConfig() {
        return config;
    }
    
    /**
     * Retrieve the tenant cache given an tenantId.
     * @param tenantId the ID that identifies the tenant
     * @return the existing or newly created TenantCache
     */
    public TenantCache getChildTenantCache(ImmutableBytesWritable tenantId) {
        TenantCache tenantCache = perTenantCacheMap.get(tenantId);
        if (tenantCache == null) {
            int maxTenantMemoryPerc = config.getInt(MAX_TENANT_MEMORY_PERC_ATTRIB, QueryServicesOptions.DEFAULT_MAX_TENANT_MEMORY_PERC);
            int maxServerCacheTimeToLive = config.getInt(QueryServices.MAX_SERVER_CACHE_TIME_TO_LIVE_MS, QueryServicesOptions.DEFAULT_MAX_SERVER_CACHE_TIME_TO_LIVE_MS);
            TenantCacheImpl newTenantCache = new TenantCacheImpl(new ChildMemoryManager(getMemoryManager(), maxTenantMemoryPerc), maxServerCacheTimeToLive);
            tenantCache = perTenantCacheMap.putIfAbsent(tenantId, newTenantCache);
            if (tenantCache == null) {
                tenantCache = newTenantCache;
            }
        }
        return tenantCache;
    }
}
