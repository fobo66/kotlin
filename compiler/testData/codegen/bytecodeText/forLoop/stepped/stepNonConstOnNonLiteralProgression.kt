// TARGET_BACKEND: JVM_IR

// IMPORTANT!
// Please, when your changes cause failures in bytecodeText tests for 'for' loops,
// examine the resulting bytecode shape carefully.
// Range and progression-based loops generated with Kotlin compiler should be
// as close as possible to Java counter loops ('for (int i = a; i < b; ++i) { ... }').
// Otherwise it may result in performance regression due to missing HotSpot optimizations.
// Run Kotlin compiler benchmarks (https://github.com/Kotlin/kotlin-benchmarks)
// with compiler built from your changes if you are not sure.

fun one() = 1

fun box(): String {
    val intProgression = 1..7 step 3  // `step` ensures type is IntProgression, NOT IntRange
    for (i in intProgression step one()) {
    }

    return "OK"
}

// For "step" progressions in JVM IR, a call to getProgressionLastElement() is made to compute the "last" value.
// If "step" is called on a non-literal progression, there is a check to see if that progression's step value is < 0.
// If the step is non-constant, there is a check that it is > 0, and if not, an IllegalArgumentException is thrown.
//


// 0 iterator
// 0 getStart
// 0 getEnd
// 1 getFirst
// 1 getLast
// 1 getStep
// 1 INVOKESTATIC kotlin/internal/ProgressionUtilKt.getProgressionLastElement
// 1 NEW java/lang/IllegalArgumentException
// 1 ATHROW
// 1 IF_ICMPGT
// 1 IF_ICMPLE
// 1 IF_ICMPEQ
// 1 IFLE
// 2 IFGT
// 1 IFGE
// 7 IF
// 1 INEG
// 18 ILOAD
// 8 ISTORE
// 1 IADD
// 0 ISUB
// 0 IINC