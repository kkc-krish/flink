/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flink.table.runtime.aggregate

import java.lang.Iterable
import java.util.{ArrayList => JArrayList}

import org.apache.flink.api.common.functions.RichGroupReduceFunction
import org.apache.flink.configuration.Configuration
import org.apache.flink.table.functions.{Accumulator, AggregateFunction}
import org.apache.flink.types.Row
import org.apache.flink.util.{Collector, Preconditions}

/**
  * It wraps the aggregate logic inside of
  * [[org.apache.flink.api.java.operators.GroupReduceOperator]].
  *
  * @param aggregates          The aggregate functions.
  * @param groupKeysMapping    The index mapping of group keys between intermediate aggregate Row
  *                            and output Row.
  * @param aggregateMapping    The index mapping between aggregate function list and aggregated
  *                            value
  *                            index in output Row.
  * @param groupingSetsMapping The index mapping of keys in grouping sets between intermediate
  *                            Row and output Row.
  * @param finalRowArity       The arity of the final resulting row
  */
class AggregateReduceGroupFunction(
    private val aggregates: Array[AggregateFunction[_ <: Any]],
    private val groupKeysMapping: Array[(Int, Int)],
    private val aggregateMapping: Array[(Int, Int)],
    private val groupingSetsMapping: Array[(Int, Int)],
    private val finalRowArity: Int)
  extends RichGroupReduceFunction[Row, Row] {

  Preconditions.checkNotNull(aggregates)
  Preconditions.checkNotNull(groupKeysMapping)

  private var output: Row = _
  private val intermediateGroupKeys: Option[Array[Int]] =
    if (groupingSetsMapping.nonEmpty) { Some(groupKeysMapping.map(_._1)) } else { None }

  val accumulatorList: Array[JArrayList[Accumulator]] = Array.fill(aggregates.length) {
    new JArrayList[Accumulator](2)
  }

  override def open(config: Configuration) {
    output = new Row(finalRowArity)

    // init lists with two empty accumulators
    var i = 0
    while (i < aggregates.length) {
      val accumulator = aggregates(i).createAccumulator()
      accumulatorList(i).add(accumulator)
      accumulatorList(i).add(accumulator)
      i += 1
    }
  }

  /**
    * For grouped intermediate aggregate Rows, merge all of them into aggregate buffer,
    * calculate aggregated values output by aggregate buffer, and set them into output
    * Row based on the mapping relation between intermediate aggregate data and output data.
    *
    * @param records Grouped intermediate aggregate Rows iterator.
    * @param out     The collector to hand results to.
    *
    */
  override def reduce(records: Iterable[Row], out: Collector[Row]): Unit = {

    var last: Row = null
    val iterator = records.iterator()

    // reset first accumulator in merge list
    var i = 0
    while (i < aggregates.length) {
      val accumulator = aggregates(i).createAccumulator()
      accumulatorList(i).set(0, accumulator)
      i += 1
    }

    while (iterator.hasNext) {
      val record = iterator.next()

      i = 0
      while (i < aggregates.length) {
        // insert received accumulator into acc list
        val newAcc = record.getField(groupKeysMapping.length + i).asInstanceOf[Accumulator]
        accumulatorList(i).set(1, newAcc)
        // merge acc list
        val retAcc = aggregates(i).merge(accumulatorList(i))
        // insert result into acc list
        accumulatorList(i).set(0, retAcc)
        i += 1
      }

      last = record
    }

    // Set group keys value to final output.
    i = 0
    while (i < groupKeysMapping.length) {
      val (after, previous) = groupKeysMapping(i)
      output.setField(after, last.getField(previous))
      i += 1
    }

    // get final aggregate value and set to output.
    i = 0
    while (i < aggregateMapping.length) {
      val (after, previous) = aggregateMapping(i)
      val agg = aggregates(previous)
      val result = agg.getValue(accumulatorList(previous).get(0))
      output.setField(after, result)
      i += 1
    }

    // Evaluate additional values of grouping sets
    if (intermediateGroupKeys.isDefined) {
      i = 0
      while (i < groupingSetsMapping.length) {
        val (inputIndex, outputIndex) = groupingSetsMapping(i)
        output.setField(outputIndex, !intermediateGroupKeys.get.contains(inputIndex))
        i += 1
      }
    }

    out.collect(output)
  }
}
