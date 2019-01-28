/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.dfa

import org.rust.lang.core.psi.RsExpr

sealed class DfaException : RuntimeException()

class DfaDivisionByZeroException(val expr: RsExpr) : DfaException()

class DfaTooComplex(override val message: String) : DfaException()