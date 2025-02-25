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
package org.apache.rocketmq.streams.common.cache.compress.impl;

import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.Map;
import org.apache.rocketmq.streams.common.cache.compress.ByteArrayValueKV;
import org.apache.rocketmq.streams.common.cache.compress.ICacheKV;
import org.junit.Assert;

/**
 * 支持key是string，value是int的场景，支持size不大于10000000.只支持int，long，boolean，string类型 只能一次行load，不能进行更新
 */
public class StringValueKV implements ICacheKV<String> {

    protected final static String CODE = "UTF-8";
    protected ByteArrayValueKV values;

    public StringValueKV(int capacity, boolean isFixedLength) {
        values = new ByteArrayValueKV(capacity, isFixedLength);
    }

    @Override
    public String get(String key) {
        byte[] bytes = values.get(key);
        if (bytes == null) {
            return null;
        }
        try {
            return new String(bytes, CODE);
        } catch (Exception e) {
            throw new RuntimeException("can not convert byte 2 string ", e);
        }
    }

    @Override
    public void put(String key, String value) {

        try {
            byte[] bytes = value.getBytes(CODE);
            values.put(key, bytes);
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException("can not convert byte 2 string ", e);
        }

    }

    @Override
    public boolean contains(String key) {
        return values.contains(key);
    }

    @Override
    public int getSize() {
        return values.getSize();
    }

    @Override
    public int calMemory() {
        return values.calMemory();
    }

    public static void main(String[] args) throws InterruptedException {
        int count = 10000000;

        StringValueKV map = new StringValueKV(count, false);
        long start = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            map.put("sdfsdfdds" + i, i + "");
        }
        System.out.println("fixed value size: " + map.getSize());
        //System.out.println("fixed value memory: " + RamUsageEstimator.humanSizeOf(map));
        System.out.println("fixed value write cost: " + (System.currentTimeMillis() - start));

        start = System.currentTimeMillis();
        SplitCache splitCache = new SplitCache(count);
        for (int i = 0; i < count; i++) {
            splitCache.put("sdfsdfdds" + i, i + "");
        }
        System.out.println("free value size: " + splitCache.getSize());
        // System.out.println("free value memory: " + RamUsageEstimator.humanSizeOf(splitCache));
        System.out.println("free value cost: " + (System.currentTimeMillis() - start));

        start = System.currentTimeMillis();
        Map<String, String> originMap = new HashMap<>(count);
        for (int i = 0; i < count; i++) {
            originMap.put("sdfsdfdds" + i, i + "");
        }
        System.out.println("origin map size: " + originMap.size());
        // System.out.println("origin map memory: " + RamUsageEstimator.humanSizeOf(originMap));
        System.out.println("origin map cost: " + (System.currentTimeMillis() - start));

        start = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            String v = map.get("sdfsdfdds" + i);
            Assert.assertEquals(v, i + "");
            v = map.get("asdfasdf" + i);
            Assert.assertNull(v);
        }
        System.out.println("fix value read cost: " + (System.currentTimeMillis() - start));

        start = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            String v = splitCache.get("sdfsdfdds" + i);
            Assert.assertEquals(v, i + "");
            v = splitCache.get("asdfasdf" + i);
            Assert.assertNull(v);
        }
        System.out.println("free value read cost: " + (System.currentTimeMillis() - start));

        start = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            String v = originMap.get("sdfsdfdds" + i);
            Assert.assertEquals(v, i + "");
            v = originMap.get("asdfasdf" + i);
            Assert.assertNull(v);
        }
        System.out.println("origin map read cost: " + (System.currentTimeMillis() - start));
    }

}
