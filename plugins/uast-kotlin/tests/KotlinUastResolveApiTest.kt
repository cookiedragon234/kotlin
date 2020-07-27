/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.uast.test.kotlin

import com.intellij.psi.*
import com.intellij.testFramework.LightProjectDescriptor
import junit.framework.TestCase
import org.jetbrains.kotlin.idea.test.KotlinLightCodeInsightFixtureTestCase
import org.jetbrains.kotlin.idea.test.KotlinWithJdkAndRuntimeLightProjectDescriptor
import org.jetbrains.kotlin.test.JUnit3WithIdeaConfigurationRunner
import org.jetbrains.kotlin.utils.addToStdlib.cast
import org.jetbrains.uast.*
import org.jetbrains.uast.kotlin.KotlinUFunctionCallExpression
import org.jetbrains.uast.test.env.kotlin.findElementByText
import org.jetbrains.uast.test.env.kotlin.findElementByTextFromPsi
import org.jetbrains.uast.test.env.kotlin.findUElementByTextFromPsi
import org.junit.runner.RunWith

@RunWith(JUnit3WithIdeaConfigurationRunner::class)
class KotlinUastResolveApiTest : KotlinLightCodeInsightFixtureTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor =
        KotlinWithJdkAndRuntimeLightProjectDescriptor.INSTANCE


    fun testResolveStringFromUast() {
        val file = myFixture.addFileToProject(
            "s.kt", """fun foo(){
                val s = "abc"
                s.toUpperCase()
                }
            ""${'"'}"""
        )

        val refs = file.findUElementByTextFromPsi<UQualifiedReferenceExpression>("s.toUpperCase()")
        val receiver = refs.receiver
        TestCase.assertEquals(CommonClassNames.JAVA_LANG_STRING, (receiver.getExpressionType() as PsiClassType).resolve()!!.qualifiedName!!)
        val resolve = receiver.cast<UReferenceExpression>().resolve()

        val variable = file.findUElementByTextFromPsi<UVariable>("val s = \"abc\"")
        TestCase.assertEquals(resolve, variable.javaPsi)
        TestCase.assertTrue(
            "resolved expression $resolve should be equivalent to ${variable.sourcePsi}",
            PsiManager.getInstance(project).areElementsEquivalent(resolve, variable.sourcePsi)
        )
    }

    fun testMultiResolve() {
        val file = myFixture.configureByText(
            "s.kt", """
                fun foo(): Int = TODO()
                fun foo(a: Int): Int = TODO()
                fun foo(a: Int, b: Int): Int = TODO()


                fun main(args: Array<String>) {
                    foo(1<caret>
                }"""
        )

        val main = file.toUElement()!!.findElementByTextFromPsi<UElement>("main").getContainingUMethod()!!
        val functionCall =
            main.findElementByText<UElement>("foo").uastParent as KotlinUFunctionCallExpression

        val resolvedDeclaration = functionCall.multiResolve()
        val resolvedDeclarationsStrings = resolvedDeclaration.map { it.element.text ?: "<null>" }
        assertContainsElements(
            resolvedDeclarationsStrings,
            "fun foo(): Int = TODO()",
            "fun foo(a: Int): Int = TODO()",
            "fun foo(a: Int, b: Int): Int = TODO()"
        )

        TestCase.assertEquals(PsiType.INT, functionCall.getExpressionType())

        val firstArgument = main.findElementByText<UElement>("1")
        val firstParameter = functionCall.getArgumentForParameter(0)
        TestCase.assertEquals(firstArgument, firstParameter)

    }

    fun testMultiResolveJava() {
        val file = myFixture.configureByText(
            "s.kt", """

                fun main(args: Array<String>) {
                    System.out.print("1"
                }
                """
        )

        val main = file.toUElement()!!.findElementByTextFromPsi<UElement>("main").getContainingUMethod()!!
        val functionCall = main.findElementByText<UElement>("print").uastParent as KotlinUFunctionCallExpression

        val resolvedDeclaration = functionCall.multiResolve()
        val resolvedDeclarationsStrings = resolvedDeclaration.map { it.element.text ?: "<null>" }
        assertContainsElements(
            resolvedDeclarationsStrings,
            "public void print(char c) { /* compiled code */ }",
            "public void print(int i) { /* compiled code */ }",
            "public void print(long l) { /* compiled code */ }",
            "public void print(float f) { /* compiled code */ }",
            "public void print(double d) { /* compiled code */ }",
            "public void print(char[] s) { /* compiled code */ }",
            "public void print(java.lang.String s) { /* compiled code */ }",
            "public void print(java.lang.Object obj) { /* compiled code */ }"
        )

        TestCase.assertEquals(PsiType.VOID, functionCall.getExpressionType())

        val firstArgument = main.findElementByText<UElement>("1")
        val firstParameter = functionCall.getArgumentForParameter(0)
        TestCase.assertEquals(firstArgument, firstParameter)
    }

    fun testMultiResolveJavaAmbiguous() {
        myFixture.addClass(
            """
            public class JavaClass {

                public void setParameter(String name, int value){}
                public void setParameter(String name, double value){}
                public void setParameter(String name, String value){}

            }
        """
        )
        val file = myFixture.configureByText(
            "s.kt", """

                fun main(args: Array<String>) {
                    JavaClass().setParameter("1"
                }
                """
        )

        val main = file.toUElement()!!.findElementByTextFromPsi<UElement>("main").getContainingUMethod()!!
        val functionCall = main.findElementByText<UElement>("setParameter").uastParent as KotlinUFunctionCallExpression

        val resolvedDeclaration = functionCall.multiResolve()
        val resolvedDeclarationsStrings = resolvedDeclaration.map { it.element.text ?: "<null>" }
        assertContainsElements(
            resolvedDeclarationsStrings,
            "public void setParameter(String name, int value){}",
            "public void setParameter(String name, double value){}",
            "public void setParameter(String name, String value){}"

        )

        TestCase.assertEquals(PsiType.VOID, functionCall.getExpressionType())

        val firstArgument = main.findElementByText<UElement>("1")
        val firstParameter = functionCall.getArgumentForParameter(0)
        TestCase.assertEquals(firstArgument, firstParameter)
    }

    fun testMultiResolveInClass() {
        val file = myFixture.configureByText(
            "s.kt", """
                class MyClass {

                    fun foo(): Int = TODO()
                    fun foo(a: Int): Int = TODO()
                    fun foo(a: Int, b: Int): Int = TODO()

                }

                fun foo(string: String) = TODO()


                fun main(args: Array<String>) {
                    MyClass().foo(
                }
            """
        )


        val functionCall =
            file.toUElement()!!.findElementByTextFromPsi<UElement>("main").getContainingUMethod()!!
                .findElementByText<UElement>("foo").uastParent as KotlinUFunctionCallExpression

        val resolvedDeclaration = functionCall.multiResolve()
        val resolvedDeclarationsStrings = resolvedDeclaration.map { it.element.text ?: "<null>" }
        assertContainsElements(
            resolvedDeclarationsStrings,
            "fun foo(): Int = TODO()",
            "fun foo(a: Int): Int = TODO()",
            "fun foo(a: Int, b: Int): Int = TODO()"
        )
        assertDoesntContain(resolvedDeclarationsStrings, "fun foo(string: String) = TODO()")
        TestCase.assertEquals(PsiType.INT, functionCall.getExpressionType())
    }

    fun testMultiConstructorResolve() {
        val file = myFixture.configureByText(
            "s.kt", """
                class MyClass(int: Int) {

                    constructor(int: Int, int1: Int) : this(int + int1)

                    fun foo(): Int = TODO()

                }

                fun MyClass(string: String): MyClass = MyClass(1)


                fun main(args: Array<String>) {
                    MyClass(
                }
            """
        )


        val functionCall =
            file.toUElement()!!.findElementByTextFromPsi<UElement>("main").getContainingUMethod()!!
                .findElementByText<UElement>("MyClass").uastParent as KotlinUFunctionCallExpression

        val resolvedDeclaration = functionCall.multiResolve()
        val resolvedDeclarationsStrings = resolvedDeclaration.map { it.element.text ?: "<null>" }
        assertContainsElements(
            resolvedDeclarationsStrings,
            "(int: Int)",
            "constructor(int: Int, int1: Int) : this(int + int1)",
            "fun MyClass(string: String): MyClass = MyClass(1)"
        )
        assertDoesntContain(resolvedDeclarationsStrings, "fun foo(): Int = TODO()")
        TestCase.assertEquals(PsiType.getTypeByName("MyClass", project, file.resolveScope), functionCall.getExpressionType())
    }


    fun testMultiInvokableObjectResolve() {
        val file = myFixture.configureByText(
            "s.kt", """
                object Foo {

                    operator fun invoke(i: Int): Int = TODO()
                    operator fun invoke(i1: Int, i2: Int): Int = TODO()
                    operator fun invoke(i1: Int, i2: Int, i3: Int): Int = TODO()

                }

                fun main(args: Array<String>) {
                    Foo(
                }
            """
        )

        val functionCall =
            file.toUElement()!!.findElementByTextFromPsi<UElement>("main").getContainingUMethod()!!
                .findElementByText<UElement>("Foo").uastParent as KotlinUFunctionCallExpression

        val resolvedDeclaration = functionCall.multiResolve()
        val resolvedDeclarationsStrings = resolvedDeclaration.map { it.element.text ?: "<null>" }
        assertContainsElements(
            resolvedDeclarationsStrings,
            "operator fun invoke(i: Int): Int = TODO()",
            "operator fun invoke(i1: Int, i2: Int): Int = TODO()",
            "operator fun invoke(i1: Int, i2: Int, i3: Int): Int = TODO()"
        )
        TestCase.assertEquals(PsiType.INT, functionCall.getExpressionType())
    }

    fun testMultiResolveJvmOverloads() {
        val file = myFixture.configureByText(
            "s.kt", """

                class MyClass {

                    @JvmOverloads
                    fun foo(i1: Int = 1, i2: Int = 2): Int = TODO()

                }

                fun main(args: Array<String>) {
                    MyClass().foo(
                }"""
        )

        val functionCall =
            file.toUElement()!!.findElementByTextFromPsi<UElement>("main").getContainingUMethod()!!
                .findElementByText<UElement>("foo").uastParent as KotlinUFunctionCallExpression

        val resolvedDeclaration = functionCall.multiResolve()
        val resolvedDeclarationsStrings = resolvedDeclaration.map { it.element.text ?: "<null>" }
        assertContainsElements(
            resolvedDeclarationsStrings,
            "@JvmOverloads\n                    fun foo(i1: Int = 1, i2: Int = 2): Int = TODO()"
        )
        TestCase.assertEquals(PsiType.INT, functionCall.getExpressionType())
    }

    fun testLocalResolve() {
        myFixture.configureByText(
            "MyClass.kt", """
            fun foo() {
                fun bar() {}
                
                ba<caret>r()
            }
        """
        )


        val uCallExpression = myFixture.file.findElementAt(myFixture.caretOffset).toUElement().getUCallExpression().orFail("cant convert to UCallExpression")
        val resolved = uCallExpression.resolve().orFail("cant resolve from $uCallExpression")
        TestCase.assertEquals("bar", resolved.name)
    }


    fun testResolveCompiledAnnotation() {
        myFixture.configureByText(
            "MyClass.kt", """
            @Deprecated(message = "deprecated")    
            fun foo() {}
        """
        )

        val compiledAnnotationParameter = myFixture.file.toUElement()!!.findElementByTextFromPsi<USimpleNameReferenceExpression>("message")
        val resolved = compiledAnnotationParameter.resolve() as PsiMethod
        TestCase.assertEquals("message", resolved.name)
    }

    fun testExtensionReceiverAnnotation() {
        testExtensionReceiverAnnotation("""fun @receiver:Suppress("unused") String.foo() {}""", "s.foo()") {
            val ref = file.findUElementByTextFromPsi<UQualifiedReferenceExpression>(it)
            ref.resolve() as PsiMethod
        }
    }

    fun testInfixReceiverAnnotation() {
        testExtensionReceiverAnnotation("""infix fun @receiver:Suppress("unused") String.foo(x: Int) {}""", "s foo 7") {
            val ref = file.findUElementByTextFromPsi<UBinaryExpression>(it)
            ref.resolveOperator()!!
        }
    }

    fun testBinaryOpReceiverAnnotation() {
        testExtensionReceiverAnnotation("""operator fun @receiver:Suppress("unused") String.times(x: Int) = this""", "s * 7") {
            val ref = file.findUElementByTextFromPsi<UBinaryExpression>(it)
            ref.resolveOperator()!!
        }
    }

    fun testPrefixOpReceiverAnnotation() {
        testExtensionReceiverAnnotation("""operator fun @receiver:Suppress("unused") String.unaryPlus() = this""", "+s") {
            val ref = file.findUElementByTextFromPsi<UUnaryExpression>(it)
            ref.resolveOperator()!!
        }
    }

    fun testPostfixOpReceiverAnnotation() {
        testExtensionReceiverAnnotation("""operator fun @receiver:Suppress("unused") String.inc() = this""", "s++") {
            val ref = file.findUElementByTextFromPsi<UUnaryExpression>(it)
            ref.resolveOperator()!!
        }
    }

    fun testExtensionPropertyReceiverAnnotation() {
        testExtensionReceiverAnnotation("""val @receiver:Suppress("unused") String.foo get() = length""", "s.foo") {
            val ref = file.findUElementByTextFromPsi<UQualifiedReferenceExpression>(it)
            ref.resolve() as PsiMethod
        }
    }

    private fun testExtensionReceiverAnnotation(
        declaration: String, reference: String, annoQn: String = "kotlin.Suppress", resolve: (String) -> PsiMethod
    ) {
        myFixture.configureByText(
            "MyClass.kt", """
            $declaration
            fun reference(s: String) {
                $reference
            }
        """
        )

        val resolved = resolve(reference)
        TestCase.assertTrue(resolved.parameterList.parametersCount >= 1)

        val receiverParam = resolved.parameterList.parameters[0]
        val actualAnnos = receiverParam.modifierList?.annotations ?: throw AssertionError("empty modifier list: ${receiverParam}")
        TestCase.assertEquals("$receiverParam: ${actualAnnos.toList()}", 2, actualAnnos.size) // @NonNull + @Suppress
        TestCase.assertNotNull(actualAnnos.find { it.hasQualifiedName(annoQn) })
        TestCase.assertEquals(actualAnnos.find { it.hasQualifiedName(annoQn) }, receiverParam.getAnnotation(annoQn))

        val method = file.findUElementByTextFromPsi<UMethod>(declaration)
        TestCase.assertEquals(method.uastParameters.size, resolved.parameterList.parametersCount)
        TestCase.assertEquals(method.uastParameters[0].javaPsi, receiverParam)

        val resolvedSourceParam = receiverParam.toUElement() as UParameter
        TestCase.assertEquals(method.uastParameters[0], resolvedSourceParam)
        TestCase.assertNotNull(resolvedSourceParam.findAnnotation(annoQn))

        // TODO: resolvedSourceParam.sourcePsi should equal method.uastParameters[0].sourcePsi and be non-null in all cases
        // method.uastParameters[0].sourcePsi is already non-null for extension functions but null for extension properties
        TestCase.assertNull(resolvedSourceParam.sourcePsi)
    }

    fun testAssigningArrayElementType() {
        myFixture.configureByText(
            "MyClass.kt", """ 
            fun foo() {
                val arr = arrayOfNulls<List<*>>(10)
                arr[0] = emptyList<Any>()
                
                val lst = mutableListOf<List<*>>()
                lst[0] = emptyList<Any>()
            }
        """
        )

        val uFile = myFixture.file.toUElement()!!

        TestCase.assertEquals(
            "PsiType:List<?>",
            uFile.findElementByTextFromPsi<UExpression>("arr[0]").getExpressionType().toString()
        )
        TestCase.assertEquals(
            "PsiType:List<?>",
            uFile.findElementByTextFromPsi<UExpression>("lst[0]").getExpressionType().toString()
        )
    }
}

