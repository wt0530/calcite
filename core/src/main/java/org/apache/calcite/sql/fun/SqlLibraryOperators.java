/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.sql.fun;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.sql.SqlAggFunction;
import org.apache.calcite.sql.SqlBasicFunction;
import org.apache.calcite.sql.SqlBinaryOperator;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlOperatorBinding;
import org.apache.calcite.sql.SqlOperatorTable;
import org.apache.calcite.sql.SqlSpecialOperator;
import org.apache.calcite.sql.SqlSyntax;
import org.apache.calcite.sql.SqlWriter;
import org.apache.calcite.sql.type.InferTypes;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.type.ReturnTypes;
import org.apache.calcite.sql.type.SqlOperandCountRanges;
import org.apache.calcite.sql.type.SqlReturnTypeInference;
import org.apache.calcite.sql.type.SqlTypeFamily;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.type.SqlTypeTransforms;
import org.apache.calcite.util.Litmus;
import org.apache.calcite.util.Optionality;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;

import static org.apache.calcite.sql.fun.SqlLibrary.BIG_QUERY;
import static org.apache.calcite.sql.fun.SqlLibrary.CALCITE;
import static org.apache.calcite.sql.fun.SqlLibrary.HIVE;
import static org.apache.calcite.sql.fun.SqlLibrary.MSSQL;
import static org.apache.calcite.sql.fun.SqlLibrary.MYSQL;
import static org.apache.calcite.sql.fun.SqlLibrary.ORACLE;
import static org.apache.calcite.sql.fun.SqlLibrary.POSTGRESQL;
import static org.apache.calcite.sql.fun.SqlLibrary.SPARK;

/**
 * Defines functions and operators that are not part of standard SQL but
 * belong to one or more other dialects of SQL.
 *
 * <p>They are read by {@link SqlLibraryOperatorTableFactory} into instances
 * of {@link SqlOperatorTable} that contain functions and operators for
 * particular libraries.
 */
public abstract class SqlLibraryOperators {
  private SqlLibraryOperators() {
  }

  /** The "AGGREGATE(m)" aggregate function;
   * aggregates a measure column according to the measure's rollup strategy.
   * This is a Calcite-specific extension.
   *
   * <p>This operator is for SQL (and AST); for internal use (RexNode and
   * Aggregate) use {@code AGG_M2M}. */
  @LibraryOperator(libraries = {CALCITE})
  public static final SqlFunction AGGREGATE =
      SqlBasicAggFunction.create("AGGREGATE", SqlKind.AGGREGATE_FN,
          ReturnTypes.ARG0, OperandTypes.MEASURE);

  /** The "CONVERT_TIMEZONE(tz1, tz2, datetime)" function;
   * converts the timezone of {@code datetime} from {@code tz1} to {@code tz2}.
   * This function is only on Redshift, but we list it in PostgreSQL
   * because Redshift does not have its own library. */
  @LibraryOperator(libraries = {POSTGRESQL})
  public static final SqlFunction CONVERT_TIMEZONE =
      SqlBasicFunction.create("CONVERT_TIMEZONE",
          ReturnTypes.DATE_NULLABLE, OperandTypes.CHARACTER_CHARACTER_DATETIME,
          SqlFunctionCategory.TIMEDATE);

  /** The "DATEADD(timeUnit, numeric, datetime)" function
   * (Microsoft SQL Server, Redshift, Snowflake). */
  @LibraryOperator(libraries = {MSSQL, POSTGRESQL})
  public static final SqlFunction DATEADD =
      new SqlTimestampAddFunction("DATEADD");

  /** The "DATEDIFF(timeUnit, datetime, datetime2)" function
   * (Microsoft SQL Server, Redshift, Snowflake).
   *
   * <p>MySQL has "DATEDIFF(date, date2)" and "TIMEDIFF(time, time2)" functions
   * but Calcite does not implement these because they have no "timeUnit"
   * argument. */
  @LibraryOperator(libraries = {MSSQL, POSTGRESQL})
  public static final SqlFunction DATEDIFF =
      new SqlTimestampDiffFunction("DATEDIFF",
          OperandTypes.family(SqlTypeFamily.ANY, SqlTypeFamily.DATE,
              SqlTypeFamily.DATE));

  /** The "DATE_PART(timeUnit, datetime)" function
   * (Databricks, Postgres, Redshift, Snowflake). */
  @LibraryOperator(libraries = {POSTGRESQL})
  public static final SqlFunction DATE_PART =
      new SqlExtractFunction("DATE_PART") {
        @Override public void unparse(SqlWriter writer, SqlCall call,
            int leftPrec, int rightPrec) {
          getSyntax().unparse(writer, this, call, leftPrec, rightPrec);
        }
      };

  /** The "DATE_SUB(date, interval)" function (BigQuery);
   * subtracts interval from the date, independent of any time zone. */
  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction DATE_SUB =
      SqlBasicFunction.create(SqlKind.DATE_SUB, ReturnTypes.ARG0_NULLABLE,
           OperandTypes.DATE_INTERVAL)
          .withFunctionType(SqlFunctionCategory.TIMEDATE);

  /** The "DATEPART(timeUnit, datetime)" function
   * (Microsoft SQL Server). */
  @LibraryOperator(libraries = {MSSQL})
  public static final SqlFunction DATEPART =
      new SqlExtractFunction("DATEPART") {
        @Override public void unparse(SqlWriter writer, SqlCall call,
            int leftPrec, int rightPrec) {
          getSyntax().unparse(writer, this, call, leftPrec, rightPrec);
        }
      };

  /** Return type inference for {@code DECODE}. */
  private static final SqlReturnTypeInference DECODE_RETURN_TYPE =
      opBinding -> {
        final List<RelDataType> list = new ArrayList<>();
        for (int i = 1, n = opBinding.getOperandCount(); i < n; i++) {
          if (i < n - 1) {
            ++i;
          }
          list.add(opBinding.getOperandType(i));
        }
        final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
        RelDataType type = typeFactory.leastRestrictive(list);
        if (type != null && opBinding.getOperandCount() % 2 == 1) {
          type = typeFactory.createTypeWithNullability(type, true);
        }
        return type;
      };

  /** The "DECODE(v, v1, result1, [v2, result2, ...], resultN)" function. */
  @LibraryOperator(libraries = {ORACLE})
  public static final SqlFunction DECODE =
      SqlBasicFunction.create(SqlKind.DECODE, DECODE_RETURN_TYPE,
          OperandTypes.VARIADIC);

  /** The "IF(condition, thenValue, elseValue)" function. */
  @LibraryOperator(libraries = {BIG_QUERY, HIVE, SPARK})
  public static final SqlFunction IF =
      new SqlFunction("IF", SqlKind.IF, SqlLibraryOperators::inferIfReturnType,
          null,
          OperandTypes.family(SqlTypeFamily.BOOLEAN, SqlTypeFamily.ANY,
              SqlTypeFamily.ANY)
              .and(
                  // Arguments 1 and 2 must have same type
                  OperandTypes.same(3, 1, 2)),
          SqlFunctionCategory.SYSTEM) {
        @Override public boolean validRexOperands(int count, Litmus litmus) {
          // IF is translated to RexNode by expanding to CASE.
          return litmus.fail("not a rex operator");
        }
      };

  /** Infers the return type of {@code IF(b, x, y)},
   * namely the least restrictive of the types of x and y.
   * Similar to {@link ReturnTypes#LEAST_RESTRICTIVE}. */
  private static @Nullable RelDataType inferIfReturnType(SqlOperatorBinding opBinding) {
    return opBinding.getTypeFactory()
        .leastRestrictive(opBinding.collectOperandTypes().subList(1, 3));
  }

  /** The "NVL(value, value)" function. */
  @LibraryOperator(libraries = {ORACLE})
  public static final SqlBasicFunction NVL =
      SqlBasicFunction.create(SqlKind.NVL,
          ReturnTypes.LEAST_RESTRICTIVE
              .andThen(SqlTypeTransforms.TO_NULLABLE_ALL),
          OperandTypes.SAME_SAME);

  /** The "IFNULL(value, value)" function. */
  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction IFNULL = NVL.withName("IFNULL");

  /** The "LENGTH(string)" function. */
  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction LENGTH =
      SqlStdOperatorTable.CHAR_LENGTH.withName("LENGTH");

  /** The "LPAD(original_value, return_length[, pattern])" function. */
  @LibraryOperator(libraries = {BIG_QUERY, ORACLE})
  public static final SqlFunction LPAD =
      SqlBasicFunction.create(
          "LPAD",
          ReturnTypes.ARG0_NULLABLE_VARYING,
          OperandTypes.STRING_NUMERIC_OPTIONAL_STRING,
          SqlFunctionCategory.STRING);

  /** The "RPAD(original_value, return_length[, pattern])" function. */
  @LibraryOperator(libraries = {BIG_QUERY, ORACLE})
  public static final SqlFunction RPAD =
      SqlBasicFunction.create(
          "RPAD",
          ReturnTypes.ARG0_NULLABLE_VARYING,
          OperandTypes.STRING_NUMERIC_OPTIONAL_STRING,
          SqlFunctionCategory.STRING);

  /** The "LTRIM(string)" function. */
  @LibraryOperator(libraries = {BIG_QUERY, ORACLE})
  public static final SqlFunction LTRIM =
      SqlBasicFunction.create(SqlKind.LTRIM,
          ReturnTypes.ARG0.andThen(SqlTypeTransforms.TO_NULLABLE)
              .andThen(SqlTypeTransforms.TO_VARYING),
          OperandTypes.STRING)
          .withFunctionType(SqlFunctionCategory.STRING);

  /** The "RTRIM(string)" function. */
  @LibraryOperator(libraries = {BIG_QUERY, ORACLE})
  public static final SqlFunction RTRIM =
      SqlBasicFunction.create(SqlKind.RTRIM,
          ReturnTypes.ARG0.andThen(SqlTypeTransforms.TO_NULLABLE)
              .andThen(SqlTypeTransforms.TO_VARYING),
          OperandTypes.STRING)
          .withFunctionType(SqlFunctionCategory.STRING);

  /** Generic "SUBSTR(string, position [, substringLength ])" function. */
  private static final SqlBasicFunction SUBSTR =
      SqlBasicFunction.create("SUBSTR", ReturnTypes.ARG0_NULLABLE_VARYING,
          OperandTypes.STRING_INTEGER_OPTIONAL_INTEGER,
          SqlFunctionCategory.STRING);

  /** The "ENDS_WITH(value1, value2)" function (BigQuery). */
  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction ENDS_WITH =
      SqlBasicFunction.create("ENDS_WITH", ReturnTypes.BOOLEAN_NULLABLE,
          OperandTypes.STRING_SAME_SAME, SqlFunctionCategory.STRING);

  /** The "STARTS_WITH(value1, value2)" function (BigQuery). */
  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction STARTS_WITH =
      SqlBasicFunction.create("STARTS_WITH", ReturnTypes.BOOLEAN_NULLABLE,
          OperandTypes.STRING_SAME_SAME, SqlFunctionCategory.STRING);

  /** BigQuery's "SUBSTR(string, position [, substringLength ])" function. */
  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction SUBSTR_BIG_QUERY =
      SUBSTR.withKind(SqlKind.SUBSTR_BIG_QUERY);

  /** MySQL's "SUBSTR(string, position [, substringLength ])" function. */
  @LibraryOperator(libraries = {MYSQL})
  public static final SqlFunction SUBSTR_MYSQL =
      SUBSTR.withKind(SqlKind.SUBSTR_MYSQL);

  /** Oracle's "SUBSTR(string, position [, substringLength ])" function.
   *
   * <p>It has different semantics to standard SQL's
   * {@link SqlStdOperatorTable#SUBSTRING} function:
   *
   * <ul>
   *   <li>If {@code substringLength} &le; 0, result is the empty string
   *   (Oracle would return null, because it treats the empty string as null,
   *   but Calcite does not have these semantics);
   *   <li>If {@code position} = 0, treat {@code position} as 1;
   *   <li>If {@code position} &lt; 0, treat {@code position} as
   *       "length(string) + position + 1".
   * </ul>
   */
  @LibraryOperator(libraries = {ORACLE})
  public static final SqlFunction SUBSTR_ORACLE =
      SUBSTR.withKind(SqlKind.SUBSTR_ORACLE);

  /** PostgreSQL's "SUBSTR(string, position [, substringLength ])" function. */
  @LibraryOperator(libraries = {POSTGRESQL})
  public static final SqlFunction SUBSTR_POSTGRESQL =
      SUBSTR.withKind(SqlKind.SUBSTR_POSTGRESQL);

  /** The "GREATEST(value, value)" function. */
  @LibraryOperator(libraries = {BIG_QUERY, ORACLE})
  public static final SqlFunction GREATEST =
      SqlBasicFunction.create(SqlKind.GREATEST,
          ReturnTypes.LEAST_RESTRICTIVE.andThen(SqlTypeTransforms.TO_NULLABLE),
          OperandTypes.SAME_VARIADIC);

  /** The "LEAST(value, value)" function. */
  @LibraryOperator(libraries = {BIG_QUERY, ORACLE})
  public static final SqlFunction LEAST =
      SqlBasicFunction.create(SqlKind.LEAST,
          ReturnTypes.LEAST_RESTRICTIVE.andThen(SqlTypeTransforms.TO_NULLABLE),
          OperandTypes.SAME_VARIADIC);

  /**
   * The <code>TRANSLATE(<i>string_expr</i>, <i>search_chars</i>,
   * <i>replacement_chars</i>)</code> function returns <i>string_expr</i> with
   * all occurrences of each character in <i>search_chars</i> replaced by its
   * corresponding character in <i>replacement_chars</i>.
   *
   * <p>It is not defined in the SQL standard, but occurs in Oracle and
   * PostgreSQL.
   */
  @LibraryOperator(libraries = {BIG_QUERY, ORACLE, POSTGRESQL})
  public static final SqlFunction TRANSLATE3 = new SqlTranslate3Function();

  @LibraryOperator(libraries = {MYSQL})
  public static final SqlFunction JSON_TYPE = new SqlJsonTypeFunction();

  @LibraryOperator(libraries = {MYSQL})
  public static final SqlFunction JSON_DEPTH = new SqlJsonDepthFunction();

  @LibraryOperator(libraries = {MYSQL})
  public static final SqlFunction JSON_LENGTH = new SqlJsonLengthFunction();

  @LibraryOperator(libraries = {MYSQL})
  public static final SqlFunction JSON_KEYS = new SqlJsonKeysFunction();

  @LibraryOperator(libraries = {MYSQL})
  public static final SqlFunction JSON_PRETTY = new SqlJsonPrettyFunction();

  @LibraryOperator(libraries = {MYSQL})
  public static final SqlFunction JSON_REMOVE = new SqlJsonRemoveFunction();

  @LibraryOperator(libraries = {MYSQL})
  public static final SqlFunction JSON_STORAGE_SIZE = new SqlJsonStorageSizeFunction();

  @LibraryOperator(libraries = {MYSQL})
  public static final SqlFunction JSON_INSERT = new SqlJsonModifyFunction("JSON_INSERT");

  @LibraryOperator(libraries = {MYSQL})
  public static final SqlFunction JSON_REPLACE = new SqlJsonModifyFunction("JSON_REPLACE");

  @LibraryOperator(libraries = {MYSQL})
  public static final SqlFunction JSON_SET = new SqlJsonModifyFunction("JSON_SET");

  @LibraryOperator(libraries = {MYSQL, ORACLE})
  public static final SqlFunction REGEXP_REPLACE = new SqlRegexpReplaceFunction();

  @LibraryOperator(libraries = {MYSQL})
  public static final SqlFunction COMPRESS =
      SqlBasicFunction.create("COMPRESS",
          ReturnTypes.explicit(SqlTypeName.VARBINARY)
              .andThen(SqlTypeTransforms.TO_NULLABLE),
          OperandTypes.STRING, SqlFunctionCategory.STRING);

  @LibraryOperator(libraries = {MYSQL})
  public static final SqlFunction EXTRACT_VALUE =
      SqlBasicFunction.create("EXTRACTVALUE",
          ReturnTypes.VARCHAR_2000.andThen(SqlTypeTransforms.FORCE_NULLABLE),
          OperandTypes.STRING_STRING);

  @LibraryOperator(libraries = {ORACLE})
  public static final SqlFunction XML_TRANSFORM =
      SqlBasicFunction.create("XMLTRANSFORM",
          ReturnTypes.VARCHAR_2000.andThen(SqlTypeTransforms.FORCE_NULLABLE),
          OperandTypes.STRING_STRING);

  @LibraryOperator(libraries = {ORACLE})
  public static final SqlFunction EXTRACT_XML =
      SqlBasicFunction.create("EXTRACT",
          ReturnTypes.VARCHAR_2000.andThen(SqlTypeTransforms.FORCE_NULLABLE),
          OperandTypes.STRING_STRING_OPTIONAL_STRING);

  @LibraryOperator(libraries = {ORACLE})
  public static final SqlFunction EXISTS_NODE =
      SqlBasicFunction.create("EXISTSNODE",
          ReturnTypes.INTEGER_NULLABLE
              .andThen(SqlTypeTransforms.FORCE_NULLABLE),
          OperandTypes.STRING_STRING_OPTIONAL_STRING);

  /** The "BOOL_AND(condition)" aggregate function, PostgreSQL and Redshift's
   * equivalent to {@link SqlStdOperatorTable#EVERY}. */
  @LibraryOperator(libraries = {POSTGRESQL})
  public static final SqlAggFunction BOOL_AND =
      new SqlMinMaxAggFunction("BOOL_AND", SqlKind.MIN, OperandTypes.BOOLEAN);

  /** The "BOOL_OR(condition)" aggregate function, PostgreSQL and Redshift's
   * equivalent to {@link SqlStdOperatorTable#SOME}. */
  @LibraryOperator(libraries = {POSTGRESQL})
  public static final SqlAggFunction BOOL_OR =
      new SqlMinMaxAggFunction("BOOL_OR", SqlKind.MAX, OperandTypes.BOOLEAN);

  /** The "LOGICAL_AND(condition)" aggregate function, BigQuery's
   * equivalent to {@link SqlStdOperatorTable#EVERY}. */
  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlAggFunction LOGICAL_AND =
      new SqlMinMaxAggFunction("LOGICAL_AND", SqlKind.MIN, OperandTypes.BOOLEAN);

  /** The "LOGICAL_OR(condition)" aggregate function, BigQuery's
   * equivalent to {@link SqlStdOperatorTable#SOME}. */
  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlAggFunction LOGICAL_OR =
      new SqlMinMaxAggFunction("LOGICAL_OR", SqlKind.MAX, OperandTypes.BOOLEAN);

  /** The "COUNTIF(condition) [OVER (...)]" function, in BigQuery,
   * returns the count of TRUE values for expression.
   *
   * <p>{@code COUNTIF(b)} is equivalent to
   * {@code COUNT(*) FILTER (WHERE b)}. */
  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlAggFunction COUNTIF =
      SqlBasicAggFunction
          .create(SqlKind.COUNTIF, ReturnTypes.BIGINT, OperandTypes.BOOLEAN)
          .withDistinct(Optionality.FORBIDDEN);

  /** The "ARRAY_AGG(value [ ORDER BY ...])" aggregate function,
   * in BigQuery and PostgreSQL, gathers values into arrays. */
  @LibraryOperator(libraries = {POSTGRESQL, BIG_QUERY})
  public static final SqlAggFunction ARRAY_AGG =
      SqlBasicAggFunction
          .create(SqlKind.ARRAY_AGG,
              ReturnTypes.andThen(ReturnTypes::stripOrderBy,
                  ReturnTypes.TO_ARRAY), OperandTypes.ANY)
          .withFunctionType(SqlFunctionCategory.SYSTEM)
          .withSyntax(SqlSyntax.ORDERED_FUNCTION)
          .withAllowsNullTreatment(true);

  /** The "ARRAY_CONCAT_AGG(value [ ORDER BY ...])" aggregate function,
   * in BigQuery and PostgreSQL, concatenates array values into arrays. */
  @LibraryOperator(libraries = {POSTGRESQL, BIG_QUERY})
  public static final SqlAggFunction ARRAY_CONCAT_AGG =
      SqlBasicAggFunction
          .create(SqlKind.ARRAY_CONCAT_AGG, ReturnTypes.ARG0,
              OperandTypes.ARRAY)
          .withFunctionType(SqlFunctionCategory.SYSTEM)
          .withSyntax(SqlSyntax.ORDERED_FUNCTION);

  /** The "STRING_AGG(value [, separator ] [ ORDER BY ...])" aggregate function,
   * BigQuery and PostgreSQL's equivalent of
   * {@link SqlStdOperatorTable#LISTAGG}.
   *
   * <p>{@code STRING_AGG(v, sep ORDER BY x, y)} is implemented by
   * rewriting to {@code LISTAGG(v, sep) WITHIN GROUP (ORDER BY x, y)}. */
  @LibraryOperator(libraries = {POSTGRESQL, BIG_QUERY})
  public static final SqlAggFunction STRING_AGG =
      SqlBasicAggFunction
          .create(SqlKind.STRING_AGG, ReturnTypes.ARG0_NULLABLE,
              OperandTypes.STRING.or(OperandTypes.STRING_STRING))
          .withFunctionType(SqlFunctionCategory.SYSTEM)
          .withSyntax(SqlSyntax.ORDERED_FUNCTION);

  /** The "GROUP_CONCAT([DISTINCT] expr [, ...] [ORDER BY ...] [SEPARATOR sep])"
   * aggregate function, MySQL's equivalent of
   * {@link SqlStdOperatorTable#LISTAGG}.
   *
   * <p>{@code GROUP_CONCAT(v ORDER BY x, y SEPARATOR s)} is implemented by
   * rewriting to {@code LISTAGG(v, s) WITHIN GROUP (ORDER BY x, y)}. */
  @LibraryOperator(libraries = {MYSQL})
  public static final SqlAggFunction GROUP_CONCAT =
      SqlBasicAggFunction
          .create(SqlKind.GROUP_CONCAT,
              ReturnTypes.andThen(ReturnTypes::stripOrderBy,
                  ReturnTypes.ARG0_NULLABLE),
              OperandTypes.STRING.or(OperandTypes.STRING_STRING))
          .withFunctionType(SqlFunctionCategory.SYSTEM)
          .withAllowsNullTreatment(false)
          .withAllowsSeparator(true)
          .withSyntax(SqlSyntax.ORDERED_FUNCTION);

  /** The "MAX_BY(value, comp)" aggregate function, Spark's
   * equivalent to {@link SqlStdOperatorTable#ARG_MAX}. */
  @LibraryOperator(libraries = {SPARK})
  public static final SqlAggFunction MAX_BY =
      SqlStdOperatorTable.ARG_MAX.withName("MAX_BY");

  /** The "MIN_BY(condition)" aggregate function, Spark's
   * equivalent to {@link SqlStdOperatorTable#ARG_MIN}. */
  @LibraryOperator(libraries = {SPARK})
  public static final SqlAggFunction MIN_BY =
      SqlStdOperatorTable.ARG_MIN.withName("MIN_BY");

  /** The "DATE(string)" function, equivalent to "CAST(string AS DATE). */
  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction DATE =
      SqlBasicFunction.create("DATE", ReturnTypes.DATE_NULLABLE,
          OperandTypes.STRING, SqlFunctionCategory.TIMEDATE);

  /** The "CURRENT_DATETIME([timezone])" function. */
  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction CURRENT_DATETIME =
      SqlBasicFunction.create("CURRENT_DATETIME",
          ReturnTypes.TIMESTAMP.andThen(SqlTypeTransforms.TO_NULLABLE),
          OperandTypes.NILADIC.or(OperandTypes.STRING),
          SqlFunctionCategory.TIMEDATE);

  /** The "DATE_FROM_UNIX_DATE(integer)" function; returns a DATE value
   * a given number of seconds after 1970-01-01. */
  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction DATE_FROM_UNIX_DATE =
      SqlBasicFunction.create("DATE_FROM_UNIX_DATE",
          ReturnTypes.DATE_NULLABLE, OperandTypes.INTEGER,
          SqlFunctionCategory.TIMEDATE);

  /** The "UNIX_DATE(date)" function; returns the number of days since
   * 1970-01-01. */
  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction UNIX_DATE =
      SqlBasicFunction.create("UNIX_DATE",
          ReturnTypes.INTEGER_NULLABLE, OperandTypes.DATE,
          SqlFunctionCategory.TIMEDATE);

  /** The "MONTHNAME(datetime)" function; returns the name of the month,
   * in the current locale, of a TIMESTAMP or DATE argument. */
  @LibraryOperator(libraries = {MYSQL})
  public static final SqlFunction MONTHNAME =
      SqlBasicFunction.create("MONTHNAME",
          ReturnTypes.VARCHAR_2000, OperandTypes.DATETIME,
          SqlFunctionCategory.TIMEDATE);

  /** The "DAYNAME(datetime)" function; returns the name of the day of the week,
   * in the current locale, of a TIMESTAMP or DATE argument. */
  @LibraryOperator(libraries = {MYSQL})
  public static final SqlFunction DAYNAME =
      SqlBasicFunction.create("DAYNAME",
          ReturnTypes.VARCHAR_2000, OperandTypes.DATETIME,
          SqlFunctionCategory.TIMEDATE);

  @LibraryOperator(libraries = {BIG_QUERY, MYSQL, POSTGRESQL})
  public static final SqlFunction LEFT =
      SqlBasicFunction.create("LEFT",
          ReturnTypes.ARG0_NULLABLE_VARYING,
          OperandTypes.CBSTRING_INTEGER, SqlFunctionCategory.STRING);

  @LibraryOperator(libraries = {BIG_QUERY, MYSQL, POSTGRESQL})
  public static final SqlFunction REPEAT =
      SqlBasicFunction.create("REPEAT",
          ReturnTypes.ARG0_NULLABLE_VARYING,
          OperandTypes.STRING_INTEGER,
          SqlFunctionCategory.STRING);

  @LibraryOperator(libraries = {BIG_QUERY, MYSQL, POSTGRESQL})
  public static final SqlFunction RIGHT =
      SqlBasicFunction.create("RIGHT", ReturnTypes.ARG0_NULLABLE_VARYING,
          OperandTypes.CBSTRING_INTEGER, SqlFunctionCategory.STRING);

  @LibraryOperator(libraries = {MYSQL})
  public static final SqlFunction SPACE =
      SqlBasicFunction.create("SPACE",
          ReturnTypes.VARCHAR_2000_NULLABLE,
          OperandTypes.INTEGER,
          SqlFunctionCategory.STRING);

  @LibraryOperator(libraries = {MYSQL})
  public static final SqlFunction STRCMP =
      SqlBasicFunction.create("STRCMP",
          ReturnTypes.INTEGER_NULLABLE,
          OperandTypes.STRING_STRING,
          SqlFunctionCategory.STRING);

  @LibraryOperator(libraries = {BIG_QUERY, MYSQL, POSTGRESQL, ORACLE})
  public static final SqlFunction SOUNDEX =
      SqlBasicFunction.create("SOUNDEX",
          ReturnTypes.VARCHAR_4_NULLABLE,
          OperandTypes.CHARACTER,
          SqlFunctionCategory.STRING);

  @LibraryOperator(libraries = {POSTGRESQL})
  public static final SqlFunction DIFFERENCE =
      SqlBasicFunction.create("DIFFERENCE",
          ReturnTypes.INTEGER_NULLABLE,
          OperandTypes.STRING_STRING,
          SqlFunctionCategory.STRING);

  /** The case-insensitive variant of the LIKE operator. */
  @LibraryOperator(libraries = {POSTGRESQL})
  public static final SqlSpecialOperator ILIKE =
      new SqlLikeOperator("ILIKE", SqlKind.LIKE, false, false);

  /** The case-insensitive variant of the NOT LIKE operator. */
  @LibraryOperator(libraries = {POSTGRESQL})
  public static final SqlSpecialOperator NOT_ILIKE =
      new SqlLikeOperator("NOT ILIKE", SqlKind.LIKE, true, false);

  /** The regex variant of the LIKE operator. */
  @LibraryOperator(libraries = {SPARK, HIVE})
  public static final SqlSpecialOperator RLIKE =
      new SqlLikeOperator("RLIKE", SqlKind.RLIKE, false, true);

  /** The regex variant of the NOT LIKE operator. */
  @LibraryOperator(libraries = {SPARK, HIVE})
  public static final SqlSpecialOperator NOT_RLIKE =
      new SqlLikeOperator("NOT RLIKE", SqlKind.RLIKE, true, true);

  /** The "CONCAT(arg, ...)" function that concatenates strings.
   * For example, "CONCAT('a', 'bc', 'd')" returns "abcd". */
  @LibraryOperator(libraries = {MYSQL, POSTGRESQL, BIG_QUERY})
  public static final SqlFunction CONCAT_FUNCTION =
      SqlBasicFunction.create("CONCAT",
          ReturnTypes.MULTIVALENT_STRING_SUM_PRECISION_NULLABLE,
          OperandTypes.repeat(SqlOperandCountRanges.from(2),
              OperandTypes.STRING),
          SqlFunctionCategory.STRING)
          .withOperandTypeInference(InferTypes.RETURN_TYPE);

  /** The "CONCAT(arg0, arg1)" function that concatenates strings.
   * For example, "CONCAT('a', 'bc')" returns "abc".
   *
   * <p>It is assigned {@link SqlKind#CONCAT2} to make it not equal to
   * {@link #CONCAT_FUNCTION}. */
  @LibraryOperator(libraries = {ORACLE})
  public static final SqlFunction CONCAT2 =
      SqlBasicFunction.create("CONCAT",
          ReturnTypes.MULTIVALENT_STRING_SUM_PRECISION_NULLABLE,
          OperandTypes.STRING_SAME_SAME,
          SqlFunctionCategory.STRING)
          .withOperandTypeInference(InferTypes.RETURN_TYPE)
          .withKind(SqlKind.CONCAT2);

  /** The "ARRAY_LENGTH(array)" function. */
  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction ARRAY_LENGTH =
      SqlBasicFunction.create("ARRAY_LENGTH",
          ReturnTypes.INTEGER_NULLABLE,
          OperandTypes.ARRAY);

  /** The "ARRAY_REVERSE(array)" function. */
  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction ARRAY_REVERSE =
      SqlBasicFunction.create(SqlKind.ARRAY_REVERSE,
          ReturnTypes.ARG0_NULLABLE,
          OperandTypes.ARRAY);

  /** The "ARRAY_CONCAT(array [, array]*)" function. */
  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction ARRAY_CONCAT =
      SqlBasicFunction.create(SqlKind.ARRAY_CONCAT,
          ReturnTypes.LEAST_RESTRICTIVE,
          OperandTypes.AT_LEAST_ONE_SAME_VARIADIC);

  @LibraryOperator(libraries = {SPARK})
  public static final SqlFunction SUBSTRING_INDEX =
      SqlBasicFunction.create(SqlKind.SUBSTRING_INDEX,
          ReturnTypes.ARG0_NULLABLE_VARYING,
          OperandTypes.STRING_STRING_INTEGER)
          .withFunctionType(SqlFunctionCategory.STRING);

  @LibraryOperator(libraries = {BIG_QUERY, MYSQL})
  public static final SqlFunction REVERSE =
      SqlBasicFunction.create(SqlKind.REVERSE,
          ReturnTypes.ARG0_NULLABLE_VARYING,
          OperandTypes.CHARACTER)
          .withFunctionType(SqlFunctionCategory.STRING);

  @LibraryOperator(libraries = {BIG_QUERY, MYSQL})
  public static final SqlFunction FROM_BASE64 =
      SqlBasicFunction.create("FROM_BASE64",
          ReturnTypes.explicit(SqlTypeName.VARBINARY)
              .andThen(SqlTypeTransforms.TO_NULLABLE),
          OperandTypes.STRING, SqlFunctionCategory.STRING);

  @LibraryOperator(libraries = {MYSQL})
  public static final SqlFunction TO_BASE64 =
      SqlBasicFunction.create("TO_BASE64",
          ReturnTypes.explicit(SqlTypeName.VARCHAR)
              .andThen(SqlTypeTransforms.TO_NULLABLE),
          OperandTypes.STRING.or(OperandTypes.BINARY),
          SqlFunctionCategory.STRING);

  /** The "TO_DATE(string1, string2)" function; casts string1
   * to a DATE using the format specified in string2. */
  @LibraryOperator(libraries = {POSTGRESQL, ORACLE})
  public static final SqlFunction TO_DATE =
      SqlBasicFunction.create("TO_DATE",
          ReturnTypes.DATE_NULLABLE,
          OperandTypes.STRING_STRING,
          SqlFunctionCategory.TIMEDATE);

  /** The "TO_TIMESTAMP(string1, string2)" function; casts string1
   * to a TIMESTAMP using the format specified in string2. */
  @LibraryOperator(libraries = {POSTGRESQL, ORACLE})
  public static final SqlFunction TO_TIMESTAMP =
      SqlBasicFunction.create("TO_TIMESTAMP",
          ReturnTypes.DATE_NULLABLE,
          OperandTypes.STRING_STRING,
          SqlFunctionCategory.TIMEDATE);

  /** The "TIMESTAMP_ADD(timestamp, interval)" function (BigQuery), the
   * two-argument variant of the built-in
   * {@link SqlStdOperatorTable#TIMESTAMP_ADD TIMESTAMPADD} function, which has
   * three arguments.
   *
   * <p>In BigQuery, the syntax is "TIMESTAMP_ADD(timestamp, INTERVAL
   * int64_expression date_part)" but in Calcite the second argument can be any
   * interval expression, not just an interval literal. */
  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction TIMESTAMP_ADD2 =
      SqlBasicFunction.create(SqlKind.TIMESTAMP_ADD, ReturnTypes.ARG0_NULLABLE,
          OperandTypes.TIMESTAMP_INTERVAL)
          .withFunctionType(SqlFunctionCategory.TIMEDATE);

  /** The "TIMESTAMP_DIFF(timestamp, timestamp, timeUnit)" function (BigQuery);
   * returns the number of timeUnit between the two timestamp expressions.
   *
   * <p>{@code TIMESTAMP_DIFF(t1, t2, unit)} is equivalent to
   * {@code TIMESTAMPDIFF(unit, t2, t1)} and {@code (t1 - t2) unit}. */
  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction TIMESTAMP_DIFF3 =
      new SqlTimestampDiffFunction("TIMESTAMP_DIFF",
          OperandTypes.family(SqlTypeFamily.TIMESTAMP, SqlTypeFamily.TIMESTAMP,
              SqlTypeFamily.ANY));

  /** The "TIME_ADD(time, interval)" function (BigQuery);
   * adds interval expression to the specified time expression. */
  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction TIME_ADD =
      SqlBasicFunction.create(SqlKind.TIME_ADD, ReturnTypes.ARG0_NULLABLE,
              OperandTypes.TIME_INTERVAL)
          .withFunctionType(SqlFunctionCategory.TIMEDATE);

  /** The "TIME_DIFF(time, time, timeUnit)" function (BigQuery);
   * returns the number of timeUnit between the two time expressions. */
  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction TIME_DIFF =
      new SqlTimestampDiffFunction("TIME_DIFF",
          OperandTypes.family(SqlTypeFamily.TIME, SqlTypeFamily.TIME,
              SqlTypeFamily.ANY));

  /** The "DATE_TRUNC(date, timeUnit)" function (BigQuery);
   * truncates a DATE value to the beginning of a timeUnit. */
  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction DATE_TRUNC =
      SqlBasicFunction.create("DATE_TRUNC",
          ReturnTypes.DATE_NULLABLE,
          OperandTypes.sequence("'DATE_TRUNC(<DATE>, <DATETIME_INTERVAL>)'",
              OperandTypes.DATE, OperandTypes.dateInterval()),
          SqlFunctionCategory.TIMEDATE);

  /** The "TIME_SUB(time, interval)" function (BigQuery);
   * subtracts an interval from a time, independent of any time zone.
   *
   * <p>In BigQuery, the syntax is "TIME_SUB(time, INTERVAL int64 date_part)"
   * but in Calcite the second argument can be any interval expression, not just
   * an interval literal. */
  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction TIME_SUB =
      SqlBasicFunction.create(SqlKind.TIME_SUB, ReturnTypes.ARG0_NULLABLE,
              OperandTypes.TIME_INTERVAL)
          .withFunctionType(SqlFunctionCategory.TIMEDATE);

  /** The "TIME_TRUNC(time, timeUnit)" function (BigQuery);
   * truncates a TIME value to the beginning of a timeUnit. */
  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction TIME_TRUNC =
      SqlBasicFunction.create("TIME_TRUNC",
          ReturnTypes.TIME_NULLABLE,
          OperandTypes.sequence("'TIME_TRUNC(<TIME>, <DATETIME_INTERVAL>)'",
              OperandTypes.TIME, OperandTypes.timeInterval()),
          SqlFunctionCategory.TIMEDATE);

  /** The "TIMESTAMP_SUB(timestamp, interval)" function (BigQuery);
   * subtracts an interval from a timestamp, independent of any time zone.
   *
   * <p>In BigQuery, the syntax is "TIMESTAMP_SUB(timestamp,
   * INTERVAL int64 date_part)" but in Calcite the second argument can be any
   * interval expression, not just an interval literal. */
  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction TIMESTAMP_SUB =
      SqlBasicFunction.create(SqlKind.TIMESTAMP_SUB, ReturnTypes.ARG0_NULLABLE,
          OperandTypes.TIMESTAMP_INTERVAL)
          .withFunctionType(SqlFunctionCategory.TIMEDATE);

  /** The "TIMESTAMP_TRUNC(timestamp, timeUnit[, timeZone])" function (BigQuery);
   * truncates a TIMESTAMP value to the beginning of a timeUnit. */
  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction TIMESTAMP_TRUNC =
      SqlBasicFunction.create("TIMESTAMP_TRUNC",
          ReturnTypes.TIMESTAMP_NULLABLE,
          OperandTypes.sequence(
              "'TIMESTAMP_TRUNC(<TIMESTAMP>, <DATETIME_INTERVAL>)'",
              OperandTypes.TIMESTAMP, OperandTypes.timestampInterval()),
          SqlFunctionCategory.TIMEDATE);

  /** The "TIMESTAMP_SECONDS(bigint)" function; returns a TIMESTAMP value
   * a given number of seconds after 1970-01-01 00:00:00. */
  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction TIMESTAMP_SECONDS =
      SqlBasicFunction.create("TIMESTAMP_SECONDS",
          ReturnTypes.TIMESTAMP_NULLABLE, OperandTypes.INTEGER,
          SqlFunctionCategory.TIMEDATE);

  /** The "TIMESTAMP_MILLIS(bigint)" function; returns a TIMESTAMP value
   * a given number of milliseconds after 1970-01-01 00:00:00. */
  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction TIMESTAMP_MILLIS =
      SqlBasicFunction.create("TIMESTAMP_MILLIS",
          ReturnTypes.TIMESTAMP_NULLABLE, OperandTypes.INTEGER,
          SqlFunctionCategory.TIMEDATE);

  /** The "TIMESTAMP_MICROS(bigint)" function; returns a TIMESTAMP value
   * a given number of micro-seconds after 1970-01-01 00:00:00. */
  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction TIMESTAMP_MICROS =
      SqlBasicFunction.create("TIMESTAMP_MICROS",
          ReturnTypes.TIMESTAMP_NULLABLE, OperandTypes.INTEGER,
          SqlFunctionCategory.TIMEDATE);

  /** The "UNIX_SECONDS(bigint)" function; returns the number of seconds
   * since 1970-01-01 00:00:00. */
  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction UNIX_SECONDS =
      SqlBasicFunction.create("UNIX_SECONDS", ReturnTypes.BIGINT_NULLABLE,
          OperandTypes.TIMESTAMP, SqlFunctionCategory.TIMEDATE);

  /** The "UNIX_MILLIS(bigint)" function; returns the number of milliseconds
   * since 1970-01-01 00:00:00. */
  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction UNIX_MILLIS =
      SqlBasicFunction.create("UNIX_MILLIS",
          ReturnTypes.BIGINT_NULLABLE, OperandTypes.TIMESTAMP,
          SqlFunctionCategory.TIMEDATE);

  /** The "UNIX_MICROS(bigint)" function; returns the number of microseconds
   * since 1970-01-01 00:00:00. */
  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction UNIX_MICROS =
      SqlBasicFunction.create("UNIX_MICROS",
          ReturnTypes.BIGINT_NULLABLE, OperandTypes.TIMESTAMP,
          SqlFunctionCategory.TIMEDATE);

  /** The "CHAR(n)" function; returns the character whose ASCII code is
   * {@code n} % 256, or null if {@code n} &lt; 0. */
  @LibraryOperator(libraries = {MYSQL, SPARK})
  public static final SqlFunction CHAR =
      SqlBasicFunction.create("CHAR",
          ReturnTypes.CHAR_FORCE_NULLABLE,
          OperandTypes.INTEGER,
          SqlFunctionCategory.STRING);

  /** The "CHR(n)" function; returns the character whose UTF-8 code is
   * {@code n}. */
  @LibraryOperator(libraries = {ORACLE, POSTGRESQL})
  public static final SqlFunction CHR =
      SqlBasicFunction.create("CHR",
          ReturnTypes.CHAR,
          OperandTypes.INTEGER,
          SqlFunctionCategory.STRING);

  @LibraryOperator(libraries = {BIG_QUERY, ORACLE})
  public static final SqlFunction TANH =
      SqlBasicFunction.create("TANH",
          ReturnTypes.DOUBLE_NULLABLE,
          OperandTypes.NUMERIC,
          SqlFunctionCategory.NUMERIC);

  @LibraryOperator(libraries = {BIG_QUERY, ORACLE})
  public static final SqlFunction COSH =
      SqlBasicFunction.create("COSH",
          ReturnTypes.DOUBLE_NULLABLE,
          OperandTypes.NUMERIC,
          SqlFunctionCategory.NUMERIC);

  @LibraryOperator(libraries = {BIG_QUERY, ORACLE})
  public static final SqlFunction SINH =
      SqlBasicFunction.create("SINH",
          ReturnTypes.DOUBLE_NULLABLE,
          OperandTypes.NUMERIC,
          SqlFunctionCategory.NUMERIC);

  @LibraryOperator(libraries = {BIG_QUERY, MYSQL, POSTGRESQL})
  public static final SqlFunction MD5 =
      SqlBasicFunction.create("MD5",
          ReturnTypes.explicit(SqlTypeName.VARCHAR)
              .andThen(SqlTypeTransforms.TO_NULLABLE),
          OperandTypes.STRING.or(OperandTypes.BINARY),
          SqlFunctionCategory.STRING);

  @LibraryOperator(libraries = {BIG_QUERY, MYSQL, POSTGRESQL})
  public static final SqlFunction SHA1 =
      SqlBasicFunction.create("SHA1",
          ReturnTypes.explicit(SqlTypeName.VARCHAR)
              .andThen(SqlTypeTransforms.TO_NULLABLE),
          OperandTypes.STRING.or(OperandTypes.BINARY),
          SqlFunctionCategory.STRING);

  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction POW =
      SqlStdOperatorTable.POWER.withName("POW");

  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction TRUNC =
      SqlStdOperatorTable.TRUNCATE.withName("TRUNC");

  /** Infix "::" cast operator used by PostgreSQL, for example
   * {@code '100'::INTEGER}. */
  @LibraryOperator(libraries = { POSTGRESQL })
  public static final SqlOperator INFIX_CAST =
      new SqlCastOperator();

  /** NULL-safe "&lt;=&gt;" equal operator used by MySQL, for example
   * {@code 1<=>NULL}. */
  @LibraryOperator(libraries = { MYSQL })
  public static final SqlOperator NULL_SAFE_EQUAL =
      new SqlBinaryOperator(
          "<=>",
          SqlKind.IS_NOT_DISTINCT_FROM,
          30,
          true,
          ReturnTypes.BOOLEAN,
          InferTypes.FIRST_KNOWN,
          OperandTypes.COMPARABLE_UNORDERED_COMPARABLE_UNORDERED);
}
