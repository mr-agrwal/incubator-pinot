/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.core.query.aggregation.function;

import java.util.Map;
import org.apache.pinot.common.function.AggregationFunctionType;
import org.apache.pinot.common.utils.DataSchema.ColumnDataType;
import org.apache.pinot.core.common.BlockValSet;
import org.apache.pinot.core.common.ObjectSerDeUtils;
import org.apache.pinot.core.query.aggregation.AggregationResultHolder;
import org.apache.pinot.core.query.aggregation.ObjectAggregationResultHolder;
import org.apache.pinot.core.query.aggregation.groupby.GroupByResultHolder;
import org.apache.pinot.core.query.aggregation.groupby.ObjectGroupByResultHolder;
import org.apache.pinot.core.query.request.context.ExpressionContext;
import org.apache.pinot.core.segment.index.readers.Dictionary;
import org.apache.pinot.spi.data.FieldSpec.DataType;
import org.roaringbitmap.PeekableIntIterator;
import org.roaringbitmap.RoaringBitmap;


public class DistinctCountBitmapAggregationFunction extends BaseSingleInputAggregationFunction<RoaringBitmap, Integer> {
  protected Dictionary _dictionary;

  public DistinctCountBitmapAggregationFunction(ExpressionContext expression) {
    super(expression);
  }

  @Override
  public AggregationFunctionType getType() {
    return AggregationFunctionType.DISTINCTCOUNTBITMAP;
  }

  @Override
  public void accept(AggregationFunctionVisitorBase visitor) {
    visitor.visit(this);
  }

  @Override
  public AggregationResultHolder createAggregationResultHolder() {
    return new ObjectAggregationResultHolder();
  }

  @Override
  public GroupByResultHolder createGroupByResultHolder(int initialCapacity, int maxCapacity) {
    return new ObjectGroupByResultHolder(initialCapacity, maxCapacity);
  }

  @Override
  public void aggregate(int length, AggregationResultHolder aggregationResultHolder,
      Map<ExpressionContext, BlockValSet> blockValSetMap) {
    BlockValSet blockValSet = blockValSetMap.get(_expression);

    // Treat BYTES value as serialized RoaringBitmap
    DataType valueType = blockValSet.getValueType();
    if (valueType == DataType.BYTES) {
      byte[][] bytesValues = blockValSet.getBytesValuesSV();
      RoaringBitmap bitmap = aggregationResultHolder.getResult();
      if (bitmap != null) {
        for (int i = 0; i < length; i++) {
          bitmap.or(ObjectSerDeUtils.ROARING_BITMAP_SER_DE.deserialize(bytesValues[i]));
        }
      } else {
        bitmap = ObjectSerDeUtils.ROARING_BITMAP_SER_DE.deserialize(bytesValues[0]);
        aggregationResultHolder.setValue(bitmap);
        for (int i = 1; i < length; i++) {
          bitmap.or(ObjectSerDeUtils.ROARING_BITMAP_SER_DE.deserialize(bytesValues[i]));
        }
      }
      return;
    }

    // For dictionary-encoded expression, store dictionary ids into the bitmap
    RoaringBitmap bitmap = getBitmap(aggregationResultHolder);
    Dictionary dictionary = blockValSet.getDictionary();
    if (dictionary != null) {
      _dictionary = dictionary;
      int[] dictIds = blockValSet.getDictionaryIdsSV();
      bitmap.addN(dictIds, 0, length);
      return;
    }

    // For non-dictionary-encoded expression, store hash code of the values into the bitmap
    switch (valueType) {
      case INT:
        int[] intValues = blockValSet.getIntValuesSV();
        bitmap.addN(intValues, 0, length);
        break;
      case LONG:
        long[] longValues = blockValSet.getLongValuesSV();
        for (int i = 0; i < length; i++) {
          bitmap.add(Long.hashCode(longValues[i]));
        }
        break;
      case FLOAT:
        float[] floatValues = blockValSet.getFloatValuesSV();
        for (int i = 0; i < length; i++) {
          bitmap.add(Float.hashCode(floatValues[i]));
        }
        break;
      case DOUBLE:
        double[] doubleValues = blockValSet.getDoubleValuesSV();
        for (int i = 0; i < length; i++) {
          bitmap.add(Double.hashCode(doubleValues[i]));
        }
        break;
      case STRING:
        String[] stringValues = blockValSet.getStringValuesSV();
        for (int i = 0; i < length; i++) {
          bitmap.add(stringValues[i].hashCode());
        }
        break;
      default:
        throw new IllegalStateException(
            "Illegal data type for DISTINCT_COUNT_BITMAP aggregation function: " + valueType);
    }
  }

  @Override
  public void aggregateGroupBySV(int length, int[] groupKeyArray, GroupByResultHolder groupByResultHolder,
      Map<ExpressionContext, BlockValSet> blockValSetMap) {
    BlockValSet blockValSet = blockValSetMap.get(_expression);

    // Treat BYTES value as serialized RoaringBitmap
    DataType valueType = blockValSet.getValueType();
    if (valueType == DataType.BYTES) {
      byte[][] bytesValues = blockValSet.getBytesValuesSV();
      for (int i = 0; i < length; i++) {
        RoaringBitmap value = ObjectSerDeUtils.ROARING_BITMAP_SER_DE.deserialize(bytesValues[i]);
        int groupKey = groupKeyArray[i];
        RoaringBitmap bitmap = groupByResultHolder.getResult(groupKey);
        if (bitmap != null) {
          bitmap.or(value);
        } else {
          groupByResultHolder.setValueForKey(groupKey, value);
        }
      }
      return;
    }

    // For dictionary-encoded expression, store dictionary ids into the bitmap
    Dictionary dictionary = blockValSet.getDictionary();
    if (dictionary != null) {
      _dictionary = dictionary;
      int[] dictIds = blockValSet.getDictionaryIdsSV();
      for (int i = 0; i < length; i++) {
        getBitmap(groupByResultHolder, groupKeyArray[i]).add(dictIds[i]);
      }
      return;
    }

    // For non-dictionary-encoded expression, store hash code of the values into the bitmap
    switch (valueType) {
      case INT:
        int[] intValues = blockValSet.getIntValuesSV();
        for (int i = 0; i < length; i++) {
          getBitmap(groupByResultHolder, groupKeyArray[i]).add(intValues[i]);
        }
        break;
      case LONG:
        long[] longValues = blockValSet.getLongValuesSV();
        for (int i = 0; i < length; i++) {
          getBitmap(groupByResultHolder, groupKeyArray[i]).add(Long.hashCode(longValues[i]));
        }
        break;
      case FLOAT:
        float[] floatValues = blockValSet.getFloatValuesSV();
        for (int i = 0; i < length; i++) {
          getBitmap(groupByResultHolder, groupKeyArray[i]).add(Float.hashCode(floatValues[i]));
        }
        break;
      case DOUBLE:
        double[] doubleValues = blockValSet.getDoubleValuesSV();
        for (int i = 0; i < length; i++) {
          getBitmap(groupByResultHolder, groupKeyArray[i]).add(Double.hashCode(doubleValues[i]));
        }
        break;
      case STRING:
        String[] stringValues = blockValSet.getStringValuesSV();
        for (int i = 0; i < length; i++) {
          getBitmap(groupByResultHolder, groupKeyArray[i]).add(stringValues[i].hashCode());
        }
        break;
      default:
        throw new IllegalStateException(
            "Illegal data type for DISTINCT_COUNT_BITMAP aggregation function: " + valueType);
    }
  }

  @Override
  public void aggregateGroupByMV(int length, int[][] groupKeysArray, GroupByResultHolder groupByResultHolder,
      Map<ExpressionContext, BlockValSet> blockValSetMap) {
    BlockValSet blockValSet = blockValSetMap.get(_expression);

    // Treat BYTES value as serialized RoaringBitmap
    DataType valueType = blockValSet.getValueType();
    if (valueType == DataType.BYTES) {
      byte[][] bytesValues = blockValSet.getBytesValuesSV();
      for (int i = 0; i < length; i++) {
        RoaringBitmap value = ObjectSerDeUtils.ROARING_BITMAP_SER_DE.deserialize(bytesValues[i]);
        for (int groupKey : groupKeysArray[i]) {
          RoaringBitmap bitmap = groupByResultHolder.getResult(groupKey);
          if (bitmap != null) {
            bitmap.or(value);
          } else {
            // Clone a bitmap for the group
            groupByResultHolder.setValueForKey(groupKey, value.clone());
          }
        }
      }
      return;
    }

    // For dictionary-encoded expression, store dictionary ids into the bitmap
    Dictionary dictionary = blockValSet.getDictionary();
    if (dictionary != null) {
      _dictionary = dictionary;
      int[] dictIds = blockValSet.getDictionaryIdsSV();
      for (int i = 0; i < length; i++) {
        setValueForGroupKeys(groupByResultHolder, groupKeysArray[i], dictIds[i]);
      }
      return;
    }

    // For non-dictionary-encoded expression, store hash code of the values into the bitmap
    switch (valueType) {
      case INT:
        int[] intValues = blockValSet.getIntValuesSV();
        for (int i = 0; i < length; i++) {
          setValueForGroupKeys(groupByResultHolder, groupKeysArray[i], intValues[i]);
        }
        break;
      case LONG:
        long[] longValues = blockValSet.getLongValuesSV();
        for (int i = 0; i < length; i++) {
          setValueForGroupKeys(groupByResultHolder, groupKeysArray[i], Long.hashCode(longValues[i]));
        }
        break;
      case FLOAT:
        float[] floatValues = blockValSet.getFloatValuesSV();
        for (int i = 0; i < length; i++) {
          setValueForGroupKeys(groupByResultHolder, groupKeysArray[i], Float.hashCode(floatValues[i]));
        }
        break;
      case DOUBLE:
        double[] doubleValues = blockValSet.getDoubleValuesSV();
        for (int i = 0; i < length; i++) {
          setValueForGroupKeys(groupByResultHolder, groupKeysArray[i], Double.hashCode(doubleValues[i]));
        }
        break;
      case STRING:
        String[] stringValues = blockValSet.getStringValuesSV();
        for (int i = 0; i < length; i++) {
          setValueForGroupKeys(groupByResultHolder, groupKeysArray[i], stringValues[i].hashCode());
        }
        break;
      default:
        throw new IllegalStateException(
            "Illegal data type for DISTINCT_COUNT_BITMAP aggregation function: " + valueType);
    }
  }

  @Override
  public RoaringBitmap extractAggregationResult(AggregationResultHolder aggregationResultHolder) {
    RoaringBitmap bitmap = aggregationResultHolder.getResult();
    if (bitmap == null) {
      return new RoaringBitmap();
    }

    if (_dictionary != null) {
      // For dictionary-encoded expression, convert dictionary ids to hash code of the values
      return convertToValueBitmap(bitmap, _dictionary);
    } else {
      // For serialized RoaringBitmap and non-dictionary-encoded expression, directly return the bitmap
      return bitmap;
    }
  }

  @Override
  public RoaringBitmap extractGroupByResult(GroupByResultHolder groupByResultHolder, int groupKey) {
    RoaringBitmap bitmap = groupByResultHolder.getResult(groupKey);
    if (bitmap == null) {
      return new RoaringBitmap();
    }

    if (_dictionary != null) {
      // For dictionary-encoded expression, convert dictionary ids to hash code of the values
      return convertToValueBitmap(bitmap, _dictionary);
    } else {
      // For serialized RoaringBitmap and non-dictionary-encoded expression, directly return the bitmap
      return bitmap;
    }
  }

  @Override
  public RoaringBitmap merge(RoaringBitmap intermediateResult1, RoaringBitmap intermediateResult2) {
    intermediateResult1.or(intermediateResult2);
    return intermediateResult1;
  }

  @Override
  public boolean isIntermediateResultComparable() {
    return false;
  }

  @Override
  public ColumnDataType getIntermediateResultColumnType() {
    return ColumnDataType.OBJECT;
  }

  @Override
  public ColumnDataType getFinalResultColumnType() {
    return ColumnDataType.INT;
  }

  @Override
  public Integer extractFinalResult(RoaringBitmap intermediateResult) {
    return intermediateResult.getCardinality();
  }

  /**
   * Returns the bitmap from the result holder or creates a new one if it does not exist.
   */
  protected static RoaringBitmap getBitmap(AggregationResultHolder aggregationResultHolder) {
    RoaringBitmap bitmap = aggregationResultHolder.getResult();
    if (bitmap == null) {
      bitmap = new RoaringBitmap();
      aggregationResultHolder.setValue(bitmap);
    }
    return bitmap;
  }

  /**
   * Returns the bitmap for the given group key or creates a new one if it does not exist.
   */
  protected static RoaringBitmap getBitmap(GroupByResultHolder groupByResultHolder, int groupKey) {
    RoaringBitmap bitmap = groupByResultHolder.getResult(groupKey);
    if (bitmap == null) {
      bitmap = new RoaringBitmap();
      groupByResultHolder.setValueForKey(groupKey, bitmap);
    }
    return bitmap;
  }

  /**
   * Helper method to set value for the given group keys into the result holder.
   */
  private void setValueForGroupKeys(GroupByResultHolder groupByResultHolder, int[] groupKeys, int value) {
    for (int groupKey : groupKeys) {
      getBitmap(groupByResultHolder, groupKey).add(value);
    }
  }

  /**
   * Helper method to read dictionary and convert dictionary ids to hash code of the values for dictionary-encoded
   * expression.
   */
  private static RoaringBitmap convertToValueBitmap(RoaringBitmap dictIdBitmap, Dictionary dictionary) {
    RoaringBitmap valueBitmap = new RoaringBitmap();
    PeekableIntIterator iterator = dictIdBitmap.getIntIterator();
    DataType valueType = dictionary.getValueType();
    switch (valueType) {
      case INT:
        while (iterator.hasNext()) {
          valueBitmap.add(dictionary.getIntValue(iterator.next()));
        }
        break;
      case LONG:
        while (iterator.hasNext()) {
          valueBitmap.add(Long.hashCode(dictionary.getLongValue(iterator.next())));
        }
        break;
      case FLOAT:
        while (iterator.hasNext()) {
          valueBitmap.add(Float.hashCode(dictionary.getFloatValue(iterator.next())));
        }
        break;
      case DOUBLE:
        while (iterator.hasNext()) {
          valueBitmap.add(Double.hashCode(dictionary.getDoubleValue(iterator.next())));
        }
        break;
      case STRING:
        while (iterator.hasNext()) {
          valueBitmap.add(dictionary.getStringValue(iterator.next()).hashCode());
        }
        break;
      default:
        throw new IllegalStateException(
            "Illegal data type for DISTINCT_COUNT_BITMAP aggregation function: " + valueType);
    }
    return valueBitmap;
  }
}
