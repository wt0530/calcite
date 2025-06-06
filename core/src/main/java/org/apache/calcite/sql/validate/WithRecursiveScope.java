package org.apache.calcite.sql.validate;

import com.google.common.collect.ImmutableList;
import org.apache.calcite.rel.type.StructKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlWithItem;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/** Scope providing the objects that are being defined using {@code WITH} clause to
 * the with clause query definitions.
 *
 * <p>For example, in
 *
 * <blockquote>{@code WITH t1 AS (q1_can_use_t1) t2 AS (q2_can_use_t1_and_t2) q3}</blockquote>
 *
 * <p>{@code t1} provides a scope that is used to validate {@code q2} and {@code q1 with union}
 * (and therefore {@code q2} may reference {@code t1} and {@code t2}),
 * and {@code t2} provides a scope that is used to validate {@code q3}
 * (and therefore q3 may reference {@code t1} and {@code t2}).
 */
public class WithRecursiveScope extends ListScope {
    private final SqlWithItem withItem;

    /** Creates a WithRecursiveScope. */
    WithRecursiveScope(SqlValidatorScope parent, SqlWithItem withItem) {
        super(parent);
        this.withItem = withItem;
    }

    @Override public SqlNode getNode() {
        return withItem;
    }

    @Override public @Nullable SqlValidatorNamespace getTableNamespace(List<String> names) {
        if (names.size() == 1 && names.get(0).equals(withItem.name.getSimple())) {
            return validator.getNamespace(withItem);
        }
        return super.getTableNamespace(names);
    }

    @Override public void resolveTable(List<String> names,
                                       SqlNameMatcher nameMatcher, Path path, Resolved resolved) {
        if (names.size() == 1
                && names.equals(withItem.name.names)) {
            final SqlValidatorNamespace ns = validator.getNamespaceOrThrow(withItem);
            // create a recursive name space so that we can create a WITH_ITEM_TABLE_REFs
            final SqlValidatorNamespace recursiveNS =
                    new WithItemRecursiveNamespace(this.validator, withItem, ns.getEnclosingNode());
            final Step path2 = path
                    .plus(recursiveNS.getRowType(), 0, names.get(0), StructKind.FULLY_QUALIFIED);
            resolved.found(recursiveNS, false, this, path2, ImmutableList.of());
            return;
        }
        super.resolveTable(names, nameMatcher, path, resolved);
    }
}
