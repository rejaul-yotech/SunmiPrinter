// Java reflection (`Field.type`) hands back java.lang.* classes, so we must
// reference them directly here — Kotlin's "prefer kotlin.Float" advisory is
// not applicable in this file.
@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")

package com.yotech.valtprinter.domain.model

import com.yotech.valtprinter.domain.model.orderdata.BillingData
import com.yotech.valtprinter.domain.model.orderdata.OrderItem
import com.yotech.valtprinter.domain.model.orderdata.SubOrderItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType

/**
 * Enforces the stability contract documented in `sdk/compose_stability.conf`.
 *
 * The conf file tells the Compose compiler "treat these classes as stable so
 * recomposition can be skipped" — but it's plain text. Nothing structurally
 * links the conf file to the actual classes. If a PR adds a `var` field or a
 * non-stable type to one of these models, the conf keeps lying to the compiler
 * and you get silent stale-UI bugs in the receipt preview that are extremely
 * hard to track down.
 *
 * This test fails the build the moment that drift happens.
 *
 * The contract every listed class must satisfy:
 *   1. Every primary-constructor property is a `val` (no `var`).
 *   2. Every property type is itself stable: a Java primitive, [String], a
 *      [Boolean], or another class in [STABLE_CLASSES], or `List<stable>`.
 *   3. The class list in this test mirrors the conf file exactly (no drift in
 *      either direction).
 *
 * Why we cannot just use `@Immutable`: the four classes below are pure-Kotlin
 * domain models. Adding `@Immutable` would force a dependency on
 * `androidx.compose.runtime` and break Clean Architecture. The conf-file +
 * this enforcement test is the architecturally-correct alternative — see the
 * extended rationale at the top of `compose_stability.conf`.
 */
class ComposeStabilityContractTest {

    /** Mirror of `compose_stability.conf` — kept in sync by the third test. */
    private val STABLE_CLASSES: Set<Class<*>> = setOf(
        ReceiptData::class.java,
        BillingData::class.java,
        OrderItem::class.java,
        SubOrderItem::class.java,
    )

    /**
     * Java/Kotlin types Compose treats as inherently stable. We deliberately
     * do NOT include user-defined types here — those must go through the conf
     * file (and therefore through [STABLE_CLASSES]).
     */
    private val STABLE_LEAF_TYPES: Set<Class<*>> = setOf(
        // Java primitives (the JVM-level type for an unboxed Kotlin Int/Long/etc.)
        java.lang.Integer.TYPE,
        java.lang.Long.TYPE,
        java.lang.Double.TYPE,
        java.lang.Float.TYPE,
        java.lang.Boolean.TYPE,
        java.lang.Short.TYPE,
        java.lang.Byte.TYPE,
        java.lang.Character.TYPE,
        // Boxed equivalents (used when the property is nullable)
        java.lang.Integer::class.java,
        java.lang.Long::class.java,
        java.lang.Double::class.java,
        java.lang.Float::class.java,
        java.lang.Boolean::class.java,
        java.lang.Short::class.java,
        java.lang.Byte::class.java,
        java.lang.Character::class.java,
        String::class.java,
    )

    @Test
    fun `every class declared stable in compose_stability_conf has only val properties`() {
        for (clazz in STABLE_CLASSES) {
            val varFields = clazz.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) }
                .filterNot { it.isSynthetic }
                .filterNot { Modifier.isFinal(it.modifiers) }
                .map { it.name }
            assertTrue(
                "Class ${clazz.simpleName} is declared stable in compose_stability.conf " +
                    "but has `var` properties: $varFields. Either make them `val`, or " +
                    "REMOVE the class from compose_stability.conf — lying to the Compose " +
                    "compiler about stability causes silent stale-UI bugs that are " +
                    "extremely hard to diagnose.",
                varFields.isEmpty()
            )
        }
    }

    @Test
    fun `every property of every stable class has a stable type`() {
        for (clazz in STABLE_CLASSES) {
            for (field in clazz.declaredFields) {
                if (Modifier.isStatic(field.modifiers) || field.isSynthetic) continue
                val unstable = findUnstableType(field.genericType)
                assertNull(
                    "Class ${clazz.simpleName}.${field.name} has unstable type `$unstable`. " +
                        "Allowed types: primitives, String, Boolean, classes listed in " +
                        "compose_stability.conf, and List<stable>. If you genuinely need a " +
                        "new stable type, add the class to compose_stability.conf AND to " +
                        "STABLE_CLASSES in this test.",
                    unstable
                )
            }
        }
    }

    @Test
    fun `STABLE_CLASSES mirrors compose_stability_conf exactly`() {
        val confFile = locateStabilityConf()
        val declaredInConf: Set<String> = confFile.readLines()
            .map { it.trim() }
            .filterNot { it.isEmpty() || it.startsWith("//") }
            .toSet()
        val declaredInTest: Set<String> = STABLE_CLASSES.map { it.name }.toSet()

        assertEquals(
            "Drift detected between compose_stability.conf and STABLE_CLASSES in this " +
                "test. When you add (or remove) a class in the conf file, you MUST also " +
                "update STABLE_CLASSES — otherwise the contract checks above will silently " +
                "stop covering the new class.",
            declaredInTest,
            declaredInConf
        )
    }

    // ------------------------------------------------------------------------

    /**
     * Recursively classifies a reflected [Type] as stable (returns `null`) or
     * unstable (returns a human-readable name describing what was found).
     * Recursion handles `List<T>` (and wildcard-bounded `List<? extends T>`)
     * by drilling into the type parameter.
     */
    private fun findUnstableType(type: Type): String? = when (type) {
        is Class<*> -> when {
            type in STABLE_LEAF_TYPES -> null
            type in STABLE_CLASSES -> null
            else -> type.name
        }
        is ParameterizedType -> {
            val raw = type.rawType as? Class<*> ?: return type.toString()
            if (raw == List::class.java || raw == java.util.List::class.java) {
                findUnstableType(type.actualTypeArguments[0])
            } else {
                // Any non-List parameterized type (Map, Set, custom generic) is
                // not in our stable contract.
                raw.name
            }
        }
        is WildcardType -> {
            val upper = type.upperBounds.firstOrNull() ?: return type.toString()
            findUnstableType(upper)
        }
        else -> type.toString()
    }

    /**
     * Locate `sdk/compose_stability.conf` from the test's working directory.
     * Gradle's `:sdk:testDebugUnitTest` task runs with CWD = the `sdk/`
     * module dir, so `File("compose_stability.conf")` resolves correctly in
     * the common case. We also walk a few parent dirs as a fallback in case
     * a future build tool changes the CWD.
     */
    private fun locateStabilityConf(): File {
        val candidates = listOf(
            File("compose_stability.conf"),
            File("sdk/compose_stability.conf"),
            File("../sdk/compose_stability.conf"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: error(
                "compose_stability.conf not found. Tried: " +
                    candidates.joinToString { it.absolutePath }
            )
    }
}
