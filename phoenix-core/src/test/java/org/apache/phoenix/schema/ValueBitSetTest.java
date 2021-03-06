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
package org.apache.phoenix.schema;

import static org.junit.Assert.assertEquals;

import org.apache.hadoop.hbase.util.Bytes;
import org.junit.Test;

import org.apache.phoenix.schema.KeyValueSchema.KeyValueSchemaBuilder;


public class ValueBitSetTest {
    private static final int FIXED_WIDTH_CHAR_SIZE = 10;
    private KeyValueSchema generateSchema(int nFields, int nRepeating, final int nNotNull) {
        KeyValueSchemaBuilder builder = new KeyValueSchemaBuilder(nNotNull);
        for (int i = 0; i < nFields; i++) {
            final int fieldIndex = i;
            for (int j = 0; j < nRepeating; j++) {
                PDatum datum = new PDatum() {
                    @Override
                    public boolean isNullable() {
                        return fieldIndex <= nNotNull;
                    }
                    @Override
                    public PDataType getDataType() {
                        return PDataType.values()[fieldIndex % PDataType.values().length];
                    }
                    @Override
                    public Integer getByteSize() {
                        return !getDataType().isFixedWidth() ? null : getDataType().getByteSize() == null ? FIXED_WIDTH_CHAR_SIZE : getDataType().getByteSize();
                    }
                    @Override
                    public Integer getMaxLength() {
                        return null;
                    }
                    @Override
                    public Integer getScale() {
                        return null;
                    }
					@Override
					public SortOrder getSortOrder() {
						return SortOrder.getDefault();
					}
                };
                builder.addField(datum);
            }
        }
        KeyValueSchema schema = builder.build();
        return schema;
    }
    
    private static void setValueBitSet(KeyValueSchema schema, ValueBitSet valueSet) {
        for (int i = 0; i < schema.getFieldCount() - schema.getMinNullable(); i++) {
            if ((i & 1) == 1) {
                valueSet.set(i);
            }
        }
    }
    
    @Test
    public void testNullCount() {
        int nFields = 32;
        int nRepeating = 5;
        int nNotNull = 8;
        KeyValueSchema schema = generateSchema(nFields, nRepeating, nNotNull);
        ValueBitSet valueSet = ValueBitSet.newInstance(schema);
        setValueBitSet(schema, valueSet);
        
        // From beginning, not spanning longs
        assertEquals(5, valueSet.getNullCount(0, 10));
        // From middle, not spanning longs
        assertEquals(5, valueSet.getNullCount(10, 10));
        // From middle, spanning to middle of next long
        assertEquals(10, valueSet.getNullCount(64 - 5, 20));
        // from end, not spanning longs
        assertEquals(5, valueSet.getNullCount(nFields*nRepeating-nNotNull-10, 10));
        // from beginning, spanning long entirely into middle of next long
        assertEquals(64, valueSet.getNullCount(2, 128));
    }
    
    @Test
    public void testSizing() {
        int nFields = 32;
        int nRepeating = 5;
        int nNotNull = 8;
        KeyValueSchema schema = generateSchema(nFields, nRepeating, nNotNull);
        ValueBitSet valueSet = ValueBitSet.newInstance(schema);
        // Since no bits are set, it stores the long array length only
        assertEquals(Bytes.SIZEOF_SHORT, valueSet.getEstimatedLength());
        setValueBitSet(schema, valueSet);
        assertEquals(Bytes.SIZEOF_SHORT + Bytes.SIZEOF_LONG * 3, valueSet.getEstimatedLength());
        
        nFields = 18;
        nRepeating = 1;
        nNotNull = 2;
        schema = generateSchema(nFields, nRepeating, nNotNull);
        valueSet = ValueBitSet.newInstance(schema);
        assertEquals(Bytes.SIZEOF_SHORT, valueSet.getEstimatedLength());
        setValueBitSet(schema, valueSet);
        assertEquals(Bytes.SIZEOF_SHORT, valueSet.getEstimatedLength());
        
        nFields = 19;
        nRepeating = 1;
        nNotNull = 2;
        schema = generateSchema(nFields, nRepeating, nNotNull);
        valueSet = ValueBitSet.newInstance(schema);
        assertEquals(Bytes.SIZEOF_SHORT, valueSet.getEstimatedLength());
        setValueBitSet(schema, valueSet);
        assertEquals(Bytes.SIZEOF_SHORT + Bytes.SIZEOF_LONG, valueSet.getEstimatedLength());
        
        nFields = 19;
        nRepeating = 1;
        nNotNull = 19;
        schema = generateSchema(nFields, nRepeating, nNotNull);
        valueSet = ValueBitSet.newInstance(schema);
        assertEquals(0, valueSet.getEstimatedLength());
    }

}
