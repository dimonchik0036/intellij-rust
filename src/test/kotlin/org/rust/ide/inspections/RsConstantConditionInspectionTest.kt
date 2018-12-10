/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.inspections

import org.intellij.lang.annotations.Language

class RsConstantConditionInspectionTest : RsInspectionsTestBase(RsConstantConditionInspection()) {
    fun `test declaration from integer constant`() = checkDeclaration("42", "{42}")

    fun `test declaration from integer constant with suffix`() = checkDeclaration("42u16", "{42}")

    fun `test declaration from integer constant with separator`() = checkDeclaration("1_000__000", "{1000000}")

    fun `test declaration from unary expression`() = checkDeclaration("-42", "{-42}")

    fun `test declaration from binary expression`() = checkDeclaration("21 + 21", "{42}")

    fun `test declaration from boolean constant`() = checkDeclaration("true", "{true}")

    private fun checkDeclaration(expression: String, value: String) = checkWithExpandValues("""
        fn main() {
            let x/*$value*/: i32 = $expression;
        }
    """)

    fun `test declaration from overflow expression`() = checkWithExpandValues("""
        fn main() {
            let x/*{200}*/: u8 = 200;
            let y/*{!}*/: u8 = x * 2u8;
        }
    """)

    fun `test declaration from arguments`() = checkWithExpandValues("""
        fn foo(
            a/*{-2147483648..2147483647}*/: i32,
            b/*{?}*/: bool,
            c/*{0..255}*/: u8,
            d/*{-9223372036854775808..9223372036854775807}*/: i128
            ) { }
    """)

    fun `test declaration from tuple of 2 elements`() = checkWithExpandValues("""
        fn main() {
            let (x/*{42}*/, y/*{24}*/) = (42, 24);
        }
    """)

    fun `test declaration from tuple of 3 elements`() = checkWithExpandValues("""
        fn main() {
            let (x/*{1}*/, y/*{true}*/, z/*{-3}*/) = (1, true , -3);
        }
    """)

    fun `test declaration from function`() = checkWithExpandValues("""
        fn foo() -> i8 { 42 }
        fn main() {
            let x/*{-128..127}*/ = foo();
        }
    """)

    fun `test declaration equal to myself`() = checkWithExpandValues("""
        fn foo(a/*{?}*/: bool) {
            let t/*{true}*/ = a == a;
            let f/*{false}*/ = a != a;
        }
    """)

    fun `test declaration equal to other with unknown`() = checkWithExpandValues("""
        fn foo(a/*{?}*/: bool, b/*{?}*/: bool) {
            let x/*{?}*/ = a == b;
            let y/*{?}*/ = a != b;
        }
    """)

    fun `test declaration equal to other with constant`() = checkWithExpandValues("""
        fn foo(a/*{?}*/: bool) {
            let b/*{true}*/ = true;
            let x/*{?}*/ = a == b;
            let y/*{?}*/ = a != b;
        }
    """)

    fun `test declaration with identical names`() = checkWithExpandValues("""
        fn foo(a/*{?}*/: bool) {
            let t/*{true}*/ = a == a;
            let f/*{false}*/ = a != a;
        }
    """)

    private fun checkWithExpandValues(@Language("Rust") text: String) = checkByText(text.expandValues, checkWeakWarn = true)

    private val String.expandValues: String
        get() = setOfValuesRegex.replace(this) {
            val variableName = it.value.substringBefore('/')
            val setOfValue = it.value.substringAfter("/*").substringBefore("*/")
            "<weak_warning descr=\"Value is '$setOfValue'\">$variableName</weak_warning>"
        }

    companion object {
        private val setOfValuesRegex = Regex("([_a-zA-Z0-9])*(/[*]).*?([*]/)")
    }
}
