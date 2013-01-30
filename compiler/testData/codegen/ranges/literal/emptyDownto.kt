// Auto-generated by org.jetbrains.jet.generators.tests.GenerateRangesCodegenTestData. DO NOT EDIT!
import java.util.ArrayList

fun box(): String {
    val list1 = ArrayList<Int>()
    for (i in 5 downTo 10) {
        list1.add(i)
    }
    if (list1 != listOf<Int>()) {
        return "Wrong elements for 5 downTo 10: $list1"
    }

    val list2 = ArrayList<Byte>()
    for (i in 5.toByte() downTo 10.toByte()) {
        list2.add(i)
    }
    if (list2 != listOf<Byte>()) {
        return "Wrong elements for 5.toByte() downTo 10.toByte(): $list2"
    }

    val list3 = ArrayList<Short>()
    for (i in 5.toShort() downTo 10.toShort()) {
        list3.add(i)
    }
    if (list3 != listOf<Short>()) {
        return "Wrong elements for 5.toShort() downTo 10.toShort(): $list3"
    }

    val list4 = ArrayList<Long>()
    for (i in 5.toLong() downTo 10.toLong()) {
        list4.add(i)
    }
    if (list4 != listOf<Long>()) {
        return "Wrong elements for 5.toLong() downTo 10.toLong(): $list4"
    }

    val list5 = ArrayList<Char>()
    for (i in 'a' downTo 'z') {
        list5.add(i)
    }
    if (list5 != listOf<Char>()) {
        return "Wrong elements for 'a' downTo 'z': $list5"
    }

    val list6 = ArrayList<Double>()
    for (i in -1.0 downTo 5.0) {
        list6.add(i)
    }
    if (list6 != listOf<Double>()) {
        return "Wrong elements for -1.0 downTo 5.0: $list6"
    }

    val list7 = ArrayList<Float>()
    for (i in -1.0.toFloat() downTo 5.0.toFloat()) {
        list7.add(i)
    }
    if (list7 != listOf<Float>()) {
        return "Wrong elements for -1.0.toFloat() downTo 5.0.toFloat(): $list7"
    }

   return "OK"
}
