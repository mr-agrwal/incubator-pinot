/**
 * Copyright (C) 2014-2018 LinkedIn Corp. (pinot-core@linkedin.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.pinot.thirdeye.hadoop.topk;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;

import org.apache.pinot.thirdeye.hadoop.config.MetricType;

/**
 * Wrapper for value generated by mapper in TopKPhase
 */
public class TopKPhaseMapOutputValue {

  Number[] metricValues;
  List<MetricType> metricTypes;

  public TopKPhaseMapOutputValue(Number[] metricValues, List<MetricType> metricTypes) {
    this.metricValues = metricValues;
    this.metricTypes = metricTypes;
  }

  public Number[] getMetricValues() {
    return metricValues;
  }

  /**
   * Converts TopkPhaseMapOutputValue to a buffer of bytes
   * @return
   * @throws IOException
   */
  public byte[] toBytes() throws IOException {

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    DataOutputStream dos = new DataOutputStream(baos);

    // metric values
    dos.writeInt(metricValues.length);
    for (int i = 0; i < metricValues.length; i++) {
      Number number = metricValues[i];
      MetricType metricType = metricTypes.get(i);
      MetricType.writeMetricValueToDataOutputStream(dos, number, metricType);
    }

    baos.close();
    dos.close();
    return baos.toByteArray();
  }

  /**
   * Constructs TopKPhaseMapOutputValue from bytes buffer
   * @param buffer
   * @param metricTypes
   * @return
   * @throws IOException
   */
  public static TopKPhaseMapOutputValue fromBytes(byte[] buffer, List<MetricType> metricTypes) throws IOException {
    DataInputStream dis = new DataInputStream(new ByteArrayInputStream(buffer));
    int length;

    // metric values
    length = dis.readInt();
    Number[] metricValues = new Number[length];

    for (int i = 0 ; i < length; i++) {
      MetricType metricType = metricTypes.get(i);
      Number metricValue = MetricType.readMetricValueFromDataInputStream(dis, metricType);
      metricValues[i] = metricValue;
    }

    TopKPhaseMapOutputValue wrapper;
    wrapper = new TopKPhaseMapOutputValue(metricValues, metricTypes);
    return wrapper;
  }

}