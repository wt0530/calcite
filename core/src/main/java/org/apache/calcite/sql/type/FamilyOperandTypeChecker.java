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
package org.apache.calcite.sql.type;

import org.apache.calcite.linq4j.Ord;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.SqlCallBinding;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlOperandCountRange;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlUtil;
import org.apache.calcite.sql.validate.implicit.TypeCoercion;

import com.google.common.collect.ImmutableList;
import org.apache.calcite.util.Util;

import java.util.List;
import java.util.function.Predicate;

import static org.apache.calcite.util.Static.RESOURCE;

/**
 * Operand type-checking strategy which checks operands for inclusion in type
 * families.
 *
 * <p>Subclasses that check a single operand should override
 * {@link #checkSingleOperandType(SqlCallBinding, SqlNode, int, SqlTypeFamily, boolean)}.
 *
 * <p>Subclasses that check multiple operands should override either
 * {@link #checkSingleOperandType(SqlCallBinding, SqlNode, int, SqlTypeFamily, boolean)},
 * or *both* {@link #checkOperandTypes(SqlCallBinding, boolean)} and
 * {@link #checkOperandTypesWithoutTypeCoercion(SqlCallBinding, boolean)}.
 */
public class FamilyOperandTypeChecker implements SqlSingleOperandTypeChecker,
    ImplicitCastOperandTypeChecker {
  //~ Instance fields --------------------------------------------------------

  protected final ImmutableList<SqlTypeFamily> families;
  protected final Predicate<Integer> optional;

  //~ Constructors -----------------------------------------------------------

  /**
   * Package private. Create using {@link OperandTypes#family}.
   */
  FamilyOperandTypeChecker(List<SqlTypeFamily> families,
      Predicate<Integer> optional) {
    this.families = ImmutableList.copyOf(families);
    this.optional = optional;
  }

  //~ Methods ----------------------------------------------------------------

  @Override public boolean isOptional(int i) {
    return optional.test(i);
  }

  @Override public boolean checkSingleOperandType(
      SqlCallBinding callBinding,
      SqlNode node,
      int iFormalOperand,
      boolean throwOnFailure) {
    Util.discard(iFormalOperand);
    if (families.size() != 1) {
      throw new IllegalStateException(
          "Cannot use as SqlSingleOperandTypeChecker without exactly one family");
    }
    return checkSingleOperandType(
        callBinding, node, iFormalOperand, families.get(0), throwOnFailure);
  }

  /**
   * Helper function used by {@link #checkSingleOperandType(SqlCallBinding, SqlNode, int, boolean)},
   * {@link #checkOperandTypesWithoutTypeCoercion(SqlCallBinding, boolean)}, and
   * {@link #checkOperandTypes(SqlCallBinding, boolean)}.
   */
  protected boolean checkSingleOperandType(
          SqlCallBinding callBinding,
          SqlNode operand,
          int iFormalOperand,
          SqlTypeFamily family,
          boolean throwOnFailure) {
    Util.discard(iFormalOperand);
    switch (family) {
      case ANY:
        final RelDataType type = SqlTypeUtil.deriveType(callBinding, operand);
        SqlTypeName typeName = type.getSqlTypeName();

        if (typeName == SqlTypeName.CURSOR) {
          // We do not allow CURSOR operands, even for ANY
          if (throwOnFailure) {
            throw callBinding.newValidationSignatureError();
          }
          return false;
        }
        // fall through
      case IGNORE:
        // no need to check
        return true;
      default:
        break;
    }
    if (SqlUtil.isNullLiteral(operand, false)) {
      if (callBinding.isTypeCoercionEnabled()) {
        return true;
      } else if (throwOnFailure) {
        throw callBinding.getValidator().newValidationError(operand,
            RESOURCE.nullIllegal());
      } else {
        return false;
      }
    }
    RelDataType type = SqlTypeUtil.deriveType(callBinding, operand);
    SqlTypeName typeName = type.getSqlTypeName();

    // Pass type checking for operators if it's of type 'ANY'.
    if (typeName.getFamily() == SqlTypeFamily.ANY) {
      return true;
    }

    if (!family.getTypeNames().contains(typeName)) {
      if (throwOnFailure) {
        throw callBinding.newValidationSignatureError();
      }
      return false;
    }
    return true;
  }

  @Override public boolean checkOperandTypes(
      SqlCallBinding callBinding,
      boolean throwOnFailure) {
    if (families.size() != callBinding.getOperandCount()) {
      // assume this is an inapplicable sub-rule of a composite rule;
      // don't throw
      return false;
    }
    for (Ord<SqlNode> op : Ord.zip(callBinding.operands())) {
      if (!checkSingleOperandType(
          callBinding,
          op.e,
          op.i,
          families.get(op.i),
          false)) {
        // try to coerce type if it is allowed.
        boolean coerced = false;
        if (callBinding.isTypeCoercionEnabled()) {
          TypeCoercion typeCoercion = callBinding.getValidator().getTypeCoercion();
          ImmutableList.Builder<RelDataType> builder = ImmutableList.builder();
          for (int i = 0; i < callBinding.getOperandCount(); i++) {
            builder.add(callBinding.getOperandType(i));
          }
          ImmutableList<RelDataType> dataTypes = builder.build();
          coerced = typeCoercion.builtinFunctionCoercion(callBinding, dataTypes, families);
        }
        // re-validate the new nodes type.
        for (Ord<SqlNode> op1 : Ord.zip(callBinding.operands())) {
          if (!checkSingleOperandType(
              callBinding,
              op1.e,
              op1.i,
              families.get(op1.i),
              throwOnFailure)) {
            return false;
          }
        }
        return coerced;
      }
    }
    return true;
  }

  @Override public boolean checkOperandTypesWithoutTypeCoercion(SqlCallBinding callBinding,
      boolean throwOnFailure) {
    if (families.size() != callBinding.getOperandCount()) {
      // assume this is an inapplicable sub-rule of a composite rule;
      // don't throw exception.
      return false;
    }

    for (Ord<SqlNode> op : Ord.zip(callBinding.operands())) {
      if (!checkSingleOperandType(
          callBinding,
          op.e,
          op.i,
          families.get(op.i),
          throwOnFailure)) {
        return false;
      }
    }
    return true;
  }

  @Override public SqlTypeFamily getOperandSqlTypeFamily(int iFormalOperand) {
    return families.get(iFormalOperand);
  }

  @Override public SqlOperandCountRange getOperandCountRange() {
    final int max = families.size();
    int min = max;
    while (min > 0 && optional.test(min - 1)) {
      --min;
    }
    return SqlOperandCountRanges.between(min, max);
  }

  @Override public String getAllowedSignatures(SqlOperator op, String opName) {
    return SqlUtil.getAliasedSignature(op, opName, families);
  }
}
