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

package org.apache.shardingsphere.core.parse.antlr.filler.sharding.statement.impl.dml;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import org.apache.shardingsphere.core.metadata.table.ShardingTableMetaData;
import org.apache.shardingsphere.core.parse.antlr.constant.QuoteCharacter;
import org.apache.shardingsphere.core.parse.antlr.filler.sharding.SQLSegmentShardingFiller;
import org.apache.shardingsphere.core.parse.antlr.sql.segment.dml.assignment.SetAssignmentsSegment;
import org.apache.shardingsphere.core.parse.antlr.sql.segment.dml.column.ColumnSegment;
import org.apache.shardingsphere.core.parse.antlr.sql.segment.dml.expr.CommonExpressionSegment;
import org.apache.shardingsphere.core.parse.antlr.sql.statement.SQLStatement;
import org.apache.shardingsphere.core.parse.antlr.sql.statement.dml.InsertStatement;
import org.apache.shardingsphere.core.parse.lexer.token.DefaultKeyword;
import org.apache.shardingsphere.core.parse.parser.context.condition.AndCondition;
import org.apache.shardingsphere.core.parse.parser.context.condition.Column;
import org.apache.shardingsphere.core.parse.parser.context.condition.Condition;
import org.apache.shardingsphere.core.parse.parser.context.condition.GeneratedKeyCondition;
import org.apache.shardingsphere.core.parse.parser.context.insertvalue.InsertValue;
import org.apache.shardingsphere.core.parse.parser.exception.SQLParsingException;
import org.apache.shardingsphere.core.parse.parser.expression.SQLExpression;
import org.apache.shardingsphere.core.parse.parser.token.InsertValuesToken;
import org.apache.shardingsphere.core.parse.parser.token.TableToken;
import org.apache.shardingsphere.core.rule.ShardingRule;

import java.util.Iterator;

/**
 * Set assignments filler.
 *
 * @author zhangliang
 */
public final class SetAssignmentsFiller implements SQLSegmentShardingFiller<SetAssignmentsSegment> {
    
    @Override
    public void fill(final SetAssignmentsSegment sqlSegment, final SQLStatement sqlStatement, final String sql, final ShardingRule shardingRule, final ShardingTableMetaData shardingTableMetaData) {
        InsertStatement insertStatement = (InsertStatement) sqlStatement;
        String tableName = insertStatement.getTables().getSingleTableName();
        for (ColumnSegment each : sqlSegment.getColumns()) {
            fillColumn(each, insertStatement, tableName);
        }
        int columnCount = getColumnCountExcludeAssistedQueryColumns(insertStatement, shardingRule, shardingTableMetaData);
        if (sqlSegment.getValues().size() != columnCount) {
            throw new SQLParsingException("INSERT INTO column size mismatch value size.");
        }
        InsertValue insertValue = new InsertValue(DefaultKeyword.SET, sqlSegment.getParametersCount());
        AndCondition andCondition = new AndCondition();
        Iterator<Column> columns = insertStatement.getColumns().iterator();
        for (CommonExpressionSegment each : sqlSegment.getValues()) {
            fillValue(insertStatement, sql, shardingRule, insertValue, andCondition, columns.next(), each);
        }
        insertStatement.getInsertValues().getValues().add(insertValue);
        insertStatement.getRouteConditions().getOrCondition().getAndConditions().add(andCondition);
        insertStatement.setParametersIndex(sqlSegment.getParametersCount());
        insertStatement.getSQLTokens().add(new InsertValuesToken(sqlSegment.getSetClauseStartIndex(), DefaultKeyword.SET));
    }
    
    private void fillColumn(final ColumnSegment sqlSegment, final InsertStatement insertStatement, final String tableName) {
        insertStatement.getColumns().add(new Column(sqlSegment.getName(), tableName));
        if (sqlSegment.getOwner().isPresent() && tableName.equals(sqlSegment.getOwner().get())) {
            insertStatement.getSQLTokens().add(new TableToken(sqlSegment.getStartIndex(), tableName, QuoteCharacter.getQuoteCharacter(tableName), 0));
        }
    }
    
    private void fillValue(final InsertStatement insertStatement, final String sql, final ShardingRule shardingRule,
                           final InsertValue insertValue, final AndCondition andCondition, final Column column, final CommonExpressionSegment expressionSegment) {
        boolean isShardingColumn = shardingRule.isShardingColumn(column.getName(), column.getTableName());
        Optional<SQLExpression> sqlExpression = expressionSegment.convertToSQLExpression(sql);
        Preconditions.checkState(sqlExpression.isPresent());
        insertValue.getColumnValues().add(sqlExpression.get());
        if (isShardingColumn) {
            if (!(-1 < expressionSegment.getPlaceholderIndex() || null != expressionSegment.getValue() || expressionSegment.isText())) {
                throw new SQLParsingException("INSERT INTO can not support complex expression value on sharding column '%s'.", column.getName());
            }
            andCondition.getConditions().add(new Condition(column, sqlExpression.get()));
        }
        Optional<String> generateKeyColumnName = shardingRule.findGenerateKeyColumnName(insertStatement.getTables().getSingleTableName());
        if (generateKeyColumnName.isPresent() && generateKeyColumnName.get().equalsIgnoreCase(column.getName())) {
            insertStatement.getGeneratedKeyConditions().add(createGeneratedKeyCondition(column, expressionSegment, sql));
        }
    }
    
    private int getColumnCountExcludeAssistedQueryColumns(final InsertStatement insertStatement, final ShardingRule shardingRule, final ShardingTableMetaData shardingTableMetaData) {
        String tableName = insertStatement.getTables().getSingleTableName();
        if (shardingTableMetaData.containsTable(tableName) && shardingTableMetaData.get(tableName).getColumns().size() == insertStatement.getColumns().size()) {
            return insertStatement.getColumns().size();
        }
        Optional<Integer> assistedQueryColumnCount = shardingRule.getShardingEncryptorEngine().getAssistedQueryColumnCount(insertStatement.getTables().getSingleTableName());
        if (assistedQueryColumnCount.isPresent()) {
            return insertStatement.getColumns().size() - assistedQueryColumnCount.get();
        }
        return insertStatement.getColumns().size();
    }
    
    private GeneratedKeyCondition createGeneratedKeyCondition(final Column column, final CommonExpressionSegment sqlExpression, final String sql) {
        if (-1 < sqlExpression.getPlaceholderIndex()) {
            return new GeneratedKeyCondition(column, sqlExpression.getPlaceholderIndex(), null);
        }
        if (null != sqlExpression.getValue()) {
            return new GeneratedKeyCondition(column, -1, (Comparable<?>) sqlExpression.getValue());
        }
        return new GeneratedKeyCondition(column, -1, sql.substring(sqlExpression.getStartIndex(), sqlExpression.getStopIndex() + 1));
    }
}