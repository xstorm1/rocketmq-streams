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
package org.apache.rocketmq.streams.window.storage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.rocketmq.streams.common.channel.split.ISplit;
import org.apache.rocketmq.streams.common.utils.MapKeyUtil;
import org.apache.rocketmq.streams.window.model.WindowInstance;
import org.apache.rocketmq.streams.window.sqlcache.SQLCache;
import org.apache.rocketmq.streams.window.sqlcache.impl.SQLElement;
import org.apache.rocketmq.streams.window.state.WindowBaseValue;
import org.apache.rocketmq.streams.window.storage.db.DBStorage;
import org.apache.rocketmq.streams.window.storage.rocksdb.RocksdbStorage;

public class WindowStorage<T extends WindowBaseValue> extends AbstractWindowStorage<T> {
    protected transient ShufflePartitionManager shufflePartitionManager = ShufflePartitionManager.getInstance();
    protected IWindowStorage localStorage;
    protected IWindowStorage remoteStorage;

    private ExecutorService executorService;

    //private ExecutorService dbService;
    public WindowStorage(boolean isLoaclStorageOnly) {
        this();
        this.isLocalStorageOnly = isLoaclStorageOnly;
    }

    public WindowStorage() {
        localStorage = new RocksdbStorage();
        remoteStorage = new DBStorage();
        executorService = new ThreadPoolExecutor(10, 10,
            0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<Runnable>());
        //dbService= new ThreadPoolExecutor(1, 1,
        //        0L, TimeUnit.MILLISECONDS,
        //        new LinkedBlockingQueue<Runnable>());

    }

    @Override
    public WindowBaseValueIterator<T> loadWindowInstanceSplitData(String localStorePrefix, String queueId, String windowInstanceId, String keyPrefix,
                                                                  Class<T> clazz) {
        if (isLocalStorageOnly) {
            return localStorage.loadWindowInstanceSplitData(localStorePrefix, queueId, windowInstanceId, keyPrefix, clazz);
        }
        if (shufflePartitionManager.isWindowInstanceFinishInit(queueId, windowInstanceId)) {
            return localStorage.loadWindowInstanceSplitData(localStorePrefix, queueId, windowInstanceId, keyPrefix, clazz);
        }
        return remoteStorage.loadWindowInstanceSplitData(localStorePrefix, queueId, windowInstanceId, keyPrefix
            , clazz);
    }

    protected transient ExecutorService executor = new ThreadPoolExecutor(10, 10,
        0L, TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue<Runnable>(100));

    @Override
    public void multiPut(Map<String, T> values, String windowInstanceId, String queueId) {
        multiPut(values,windowInstanceId,queueId,null);
    }

    public void multiPut(Map<String, T> values, String windowInstanceId, String queueId, SQLCache sqlCache) {
        localStorage.multiPut(values);
        if (isLocalStorageOnly) {
            return;
        }
        if (shufflePartitionManager.isWindowInstanceFinishInit(queueId, windowInstanceId)) {
            //可以考虑异步
            if(sqlCache!=null){
                sqlCache.addCache(new SQLElement(queueId,windowInstanceId,((IRemoteStorage)this.remoteStorage).multiPutSQL(values)));
            }else {
                remoteStorage.multiPut(values);
            }


            return;
        }
        remoteStorage.multiPut(values);
    }

    @Override public Long getMaxSplitNum(WindowInstance windowInstance, Class<T> clazz) {
        if(isLocalStorageOnly){
            return null;
        }
        return remoteStorage.getMaxSplitNum(windowInstance,clazz);
    }

    @Override
    public Map<String, T> multiGet(Class<T> clazz, List<String> keys, String windowInstanceId, String queueId) {
        if (isLocalStorageOnly || shufflePartitionManager.isWindowInstanceFinishInit(queueId, windowInstanceId)) {
            return localStorage.multiGet(clazz, keys);
        }
        return remoteStorage.multiGet(clazz, keys);
    }

    @Override
    public void multiPut(Map<String, T> values) {
        localStorage.multiPut(values);
        if (isLocalStorageOnly) {
            return;
        }
        remoteStorage.multiPut(values);

    }

    @Override
    public Map<String, T> multiGet(Class<T> clazz, List<String> keys) {
        Map<String, T> result = new HashMap<>();
        if (isLocalStorageOnly) {
            result.putAll(localStorage.multiGet(clazz, keys));
            return result;
        }

        List<String> notLocalKeys = new ArrayList<>();
        List<String> localKeys = new ArrayList<>();
        for (String key : keys) {
            String[] values = MapKeyUtil.spliteKey(key);
            String shuffleId = values[0];
            boolean isLocal = shufflePartitionManager.isWindowInstanceFinishInit(shuffleId, createWindowInstanceId(key));
            if (isLocal) {
                localKeys.add(key);
            } else {
                notLocalKeys.add(key);
            }
        }

        result.putAll(localStorage.multiGet(clazz, localKeys));
        result.putAll(remoteStorage.multiGet(clazz, notLocalKeys));
        return result;
    }

    @Override
    public void removeKeys(Collection<String> keys) {
        localStorage.removeKeys(keys);
    }

    /**
     * refer to: WindowMessageProcessor.createStoreKey
     */
    public static String createWindowInstanceId(String msgKey) {
        String[] values = MapKeyUtil.spliteKey(msgKey);
        String[] lastValues = Arrays.copyOfRange(values, 1, values.length - 2);
        //values[4]: endTime or fireTime
        return MapKeyUtil.createKey(lastValues);
    }

    @Override
    public void delete(String windowInstanceId, String queueId, Class<T> clazz) {
       this.delete(windowInstanceId,queueId,clazz,null);
    }
    public void delete(String windowInstanceId, String queueId, Class<T> clazz, SQLCache sqlCache) {
        localStorage.delete(windowInstanceId, queueId, clazz);
        if (!isLocalStorageOnly) {
            if(sqlCache!=null){
                sqlCache.addCache(new SQLElement(queueId,windowInstanceId,((IRemoteStorage)this.remoteStorage).deleteSQL(windowInstanceId,queueId,clazz)));
            }else {
                remoteStorage.delete(windowInstanceId, queueId, clazz);
            }

        }
    }

    public static abstract class WindowBaseValueIterator<T extends WindowBaseValue> implements Iterator<T> {
        protected long partitionNum = -1;

        public void setPartitionNum(long partitionNum) {
            this.partitionNum = partitionNum;
        }
    }

    @Override
    public void clearCache(ISplit split, Class<T> clazz) {
        localStorage.clearCache(split, clazz);
    }

    public IWindowStorage getLocalStorage() {
        return localStorage;
    }

    public IWindowStorage getRemoteStorage() {
        return remoteStorage;
    }
}
