/*
 * Copyright (C) 2017-2018 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.exec.store.hbase;

import java.util.Arrays;
import java.util.List;

import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.filter.BinaryComparator;
import org.apache.hadoop.hbase.filter.ByteArrayComparable;
import org.apache.hadoop.hbase.filter.CompareFilter.CompareOp;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.filter.NullComparator;
import org.apache.hadoop.hbase.filter.RegexStringComparator;
import org.apache.hadoop.hbase.filter.RowFilter;
import org.apache.hadoop.hbase.filter.SingleColumnValueFilter;

import com.dremio.common.expression.BooleanOperator;
import com.dremio.common.expression.FunctionCall;
import com.dremio.common.expression.LogicalExpression;
import com.dremio.common.expression.SchemaPath;
import com.dremio.common.expression.visitors.AbstractExprVisitor;
import com.google.common.base.Charsets;

public class HBaseFilterBuilder extends AbstractExprVisitor<HBaseScanSpec, Void, RuntimeException> implements HBaseConstants {

  private final LogicalExpression le;
  private final HBaseScanSpec oldSpec;

  private boolean allExpressionsConverted = true;

  HBaseFilterBuilder(TableName tableName, byte[] startRow, byte[] stopRow, byte[] serializedFilter, LogicalExpression le) {
    this.le = le;

    this.oldSpec = new HBaseScanSpec(tableName, startRow, stopRow, serializedFilter);
  }

  public HBaseScanSpec parseTree()  {
    HBaseScanSpec parsedSpec = le.accept(this, null);
    if (parsedSpec != null) {

      parsedSpec = mergeScanSpecs("booleanAnd", oldSpec, parsedSpec);
      /*
       * If RowFilter is THE filter attached to the scan specification,
       * remove it since its effect is also achieved through startRow and stopRow.
       */
      Filter parsedFilter = HBaseUtils.deserializeFilter(parsedSpec.getSerializedFilter());
      if (parsedFilter instanceof RowFilter && ((RowFilter)parsedFilter).getComparator() instanceof BinaryComparator) {
        return new HBaseScanSpec(parsedSpec.getTableName(), parsedSpec.getStartRow(), parsedSpec.getStopRow(), (byte[]) null);
      }
    }
    return parsedSpec;
  }

  public boolean isAllExpressionsConverted() {
    return allExpressionsConverted;
  }

  @Override
  public HBaseScanSpec visitUnknown(LogicalExpression e, Void value)  {
    allExpressionsConverted = false;
    return null;
  }

  @Override
  public HBaseScanSpec visitBooleanOperator(BooleanOperator op, Void value)  {
    return visitFunctionCall(op, value);
  }

  @Override
  public HBaseScanSpec visitFunctionCall(FunctionCall call, Void value)  {
    HBaseScanSpec nodeScanSpec = null;
    String functionName = call.getName();
    List<LogicalExpression> args = call.args;

    if (CompareFunctionsProcessor.isCompareFunction(functionName)) {
      CompareFunctionsProcessor processor = CompareFunctionsProcessor.process(call);
      if (processor.isSuccess()) {
        nodeScanSpec = createHBaseScanSpec(call, processor);
      }
    } else {
      switch (functionName) {
      case "booleanAnd":
      case "booleanOr":
        HBaseScanSpec firstScanSpec = args.get(0).accept(this, null);
        for (int i = 1; i < args.size(); ++i) {
          HBaseScanSpec nextScanSpec = args.get(i).accept(this, null);
          if (firstScanSpec != null && nextScanSpec != null) {
            nodeScanSpec = mergeScanSpecs(functionName, firstScanSpec, nextScanSpec);
          } else {
            allExpressionsConverted = false;
            if ("booleanAnd".equals(functionName)) {
              nodeScanSpec = firstScanSpec == null ? nextScanSpec : firstScanSpec;
            }
          }
          firstScanSpec = nodeScanSpec;
        }
        break;
      }
    }

    if (nodeScanSpec == null) {
      allExpressionsConverted = false;
    }

    return nodeScanSpec;
  }

  private HBaseScanSpec mergeScanSpecs(String functionName, HBaseScanSpec leftScanSpec, HBaseScanSpec rightScanSpec)  {
    Filter newFilter = null;
    byte[] startRow = HConstants.EMPTY_START_ROW;
    byte[] stopRow = HConstants.EMPTY_END_ROW;

    switch (functionName) {
    case "booleanAnd":
      newFilter = HBaseUtils.andFilterAtIndex(
          HBaseUtils.deserializeFilter(leftScanSpec.getSerializedFilter()),
          HBaseUtils.LAST_FILTER,
          HBaseUtils.deserializeFilter(rightScanSpec.getSerializedFilter()));
      startRow = HBaseUtils.maxOfStartRows(leftScanSpec.getStartRow(), rightScanSpec.getStartRow());
      stopRow = HBaseUtils.minOfStopRows(leftScanSpec.getStopRow(), rightScanSpec.getStopRow());
      if (!HBaseUtils.isRowKeyEmpty(startRow) && !HBaseUtils.isRowKeyEmpty(stopRow) && HBaseUtils.compareRowKeys(startRow, stopRow) > 0) {
        // The result is an empty range: a start point that follows the stop point. Collapse into a single point (arbitrarily chosen to be the min of the two)
        startRow = stopRow;
      }
      break;
    case "booleanOr":
      newFilter = HBaseUtils.orFilterAtIndex(
          HBaseUtils.deserializeFilter(leftScanSpec.getSerializedFilter()),
          HBaseUtils.LAST_FILTER,
          HBaseUtils.deserializeFilter(rightScanSpec.getSerializedFilter()));
      startRow = HBaseUtils.minOfStartRows(leftScanSpec.getStartRow(), rightScanSpec.getStartRow());
      stopRow = HBaseUtils.maxOfStopRows(leftScanSpec.getStopRow(), rightScanSpec.getStopRow());
    }
    return new HBaseScanSpec(leftScanSpec.getTableName(), startRow, stopRow, newFilter);
  }

  private HBaseScanSpec createHBaseScanSpec(FunctionCall call, CompareFunctionsProcessor processor)  {
    String functionName = processor.getFunctionName();
    SchemaPath field = processor.getPath();
    byte[] fieldValue = processor.getValue();
    boolean sortOrderAscending = processor.isSortOrderAscending();
    boolean isRowKey = field.getAsUnescapedPath().equals(ROW_KEY);
    if (!(isRowKey
        || (!field.getRootSegment().isLastPath()
            && field.getRootSegment().getChild().isLastPath()
            && field.getRootSegment().getChild().isNamed())
           )
        ) {
      /*
       * if the field in this function is neither the row_key nor a qualified HBase column, return.
       */
      return null;
    }

    if (processor.isRowKeyPrefixComparison()) {
      return createRowKeyPrefixScanSpec(call, processor);
    }

    CompareOp compareOp = null;
    boolean isNullTest = false;
    ByteArrayComparable comparator = new BinaryComparator(fieldValue);
    byte[] startRow = HConstants.EMPTY_START_ROW;
    byte[] stopRow = HConstants.EMPTY_END_ROW;
    switch (functionName) {
    case "equal":
      compareOp = CompareOp.EQUAL;
      if (isRowKey) {
        startRow = fieldValue;
        /* stopRow should be just greater than 'value'*/
        stopRow = Arrays.copyOf(fieldValue, fieldValue.length+1);
        compareOp = CompareOp.EQUAL;
      }
      break;
    case "not_equal":
      compareOp = CompareOp.NOT_EQUAL;
      break;
    case "greater_than_or_equal_to":
      if (sortOrderAscending) {
        compareOp = CompareOp.GREATER_OR_EQUAL;
        if (isRowKey) {
          startRow = fieldValue;
        }
      } else {
        compareOp = CompareOp.LESS_OR_EQUAL;
        if (isRowKey) {
          // stopRow should be just greater than 'value'
          stopRow = Arrays.copyOf(fieldValue, fieldValue.length+1);
        }
      }
      break;
    case "greater_than":
      if (sortOrderAscending) {
        compareOp = CompareOp.GREATER;
        if (isRowKey) {
          // startRow should be just greater than 'value'
          startRow = Arrays.copyOf(fieldValue, fieldValue.length+1);
        }
      } else {
        compareOp = CompareOp.LESS;
        if (isRowKey) {
          stopRow = fieldValue;
        }
      }
      break;
    case "less_than_or_equal_to":
      if (sortOrderAscending) {
        compareOp = CompareOp.LESS_OR_EQUAL;
        if (isRowKey) {
          // stopRow should be just greater than 'value'
          stopRow = Arrays.copyOf(fieldValue, fieldValue.length+1);
        }
      } else {
        compareOp = CompareOp.GREATER_OR_EQUAL;
        if (isRowKey) {
          startRow = fieldValue;
        }
      }
      break;
    case "less_than":
      if (sortOrderAscending) {
        compareOp = CompareOp.LESS;
        if (isRowKey) {
          stopRow = fieldValue;
        }
      } else {
        compareOp = CompareOp.GREATER;
        if (isRowKey) {
          // startRow should be just greater than 'value'
          startRow = Arrays.copyOf(fieldValue, fieldValue.length+1);
        }
      }
      break;
    case "isnull":
    case "isNull":
    case "is null":
      if (isRowKey) {
        return null;
      }
      isNullTest = true;
      compareOp = CompareOp.EQUAL;
      comparator = new NullComparator();
      break;
    case "isnotnull":
    case "isNotNull":
    case "is not null":
      if (isRowKey) {
        return null;
      }
      compareOp = CompareOp.NOT_EQUAL;
      comparator = new NullComparator();
      break;
    case "like":
      /*
       * Convert the LIKE operand to Regular Expression pattern so that we can
       * apply RegexStringComparator()
       */
      HBaseRegexParser parser = new HBaseRegexParser(call).parse();
      compareOp = CompareOp.EQUAL;
      comparator = new RegexStringComparator(parser.getRegexString());

      /*
       * We can possibly do better if the LIKE operator is on the row_key
       */
      if (isRowKey) {
        String prefix = parser.getPrefixString();
        if (prefix != null) { // group 3 is literal
          /*
           * If there is a literal prefix, it can help us prune the scan to a sub range
           */
          if (prefix.equals(parser.getLikeString())) {
            /* The operand value is literal. This turns the LIKE operator to EQUAL operator */
            startRow = stopRow = fieldValue;
            compareOp = null;
          } else {
            startRow = prefix.getBytes(Charsets.UTF_8);
            stopRow = startRow.clone();
            boolean isMaxVal = true;
            for (int i = stopRow.length - 1; i >= 0 ; --i) {
              int nextByteValue = (0xff & stopRow[i]) + 1;
              if (nextByteValue < 0xff) {
                stopRow[i] = (byte) nextByteValue;
                isMaxVal = false;
                break;
              } else {
                stopRow[i] = 0;
              }
            }
            if (isMaxVal) {
              stopRow = HConstants.EMPTY_END_ROW;
            }
          }
        }
      }
      break;
    }

    if (compareOp != null || startRow != HConstants.EMPTY_START_ROW || stopRow != HConstants.EMPTY_END_ROW) {
      Filter filter = null;
      if (isRowKey) {
        if (compareOp != null) {
          filter = new RowFilter(compareOp, comparator);
        }
      } else {
        byte[] family = HBaseUtils.getBytes(field.getRootSegment().getPath());
        byte[] qualifier = HBaseUtils.getBytes(field.getRootSegment().getChild().getNameSegment().getPath());
        filter = new SingleColumnValueFilter(family, qualifier, compareOp, comparator);
        ((SingleColumnValueFilter)filter).setLatestVersionOnly(true);
        if (!isNullTest) {
          ((SingleColumnValueFilter)filter).setFilterIfMissing(true);
        }
      }
      return new HBaseScanSpec(oldSpec.getTableName(), startRow, stopRow, filter);
    }
    // else
    return null;
  }

  private HBaseScanSpec createRowKeyPrefixScanSpec(FunctionCall call, CompareFunctionsProcessor processor)  {
    byte[] startRow = processor.getRowKeyPrefixStartRow();
    byte[] stopRow  = processor.getRowKeyPrefixStopRow();
    Filter filter   = processor.getRowKeyPrefixFilter();

    if (startRow != HConstants.EMPTY_START_ROW ||
        stopRow != HConstants.EMPTY_END_ROW ||
        filter != null) {
      return new HBaseScanSpec(oldSpec.getTableName(), startRow, stopRow, filter);
    }

    // else
    return null;
  }

}
