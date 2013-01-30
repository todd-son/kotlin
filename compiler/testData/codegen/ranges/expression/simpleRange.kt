// Auto-generated by org.jetbrains.jet.generators.tests.GenerateRangesCodegenTestData. DO NOT EDIT!
import java.util.ArrayList

fun box(): String {
    val list1 = ArrayList<Int>()
    val range1 = 3..9
    for (i in range1) {
        list1.add(i)
    }
    if (list1 != listOf<Int>(3, 4, 5, 6, 7, 8, 9)) {
        return "Wrong elements for 3..9: $list1"
    }

    val list2 = ArrayList<Byte>()
    val range2 = 3.toByte()..9.toByte()
    for (i in range2) {
        list2.add(i)
    }
    if (list2 != listOf<Byte>(3, 4, 5, 6, 7, 8, 9)) {
        return "Wrong elements for 3.toByte()..9.toByte(): $list2"
    }

    val list3 = ArrayList<Short>()
    val range3 = 3.toShort()..9.toShort()
    for (i in range3) {
        list3.add(i)
    }
    if (list3 != listOf<Short>(3, 4, 5, 6, 7, 8, 9)) {
        return "Wrong elements for 3.toShort()..9.toShort(): $list3"
    }

    val list4 = ArrayList<Long>()
    val range4 = 3.toLong()..9.toLong()
    for (i in range4) {
        list4.add(i)
    }
    if (list4 != listOf<Long>(3, 4, 5, 6, 7, 8, 9)) {
        return "Wrong elements for 3.toLong()..9.toLong(): $list4"
    }

    val list5 = ArrayList<Char>()
    val range5 = 'c'..'g'
    for (i in range5) {
        list5.add(i)
    }
    if (list5 != listOf<Char>('c', 'd', 'e', 'f', 'g')) {
        return "Wrong elements for 'c'..'g': $list5"
    }

    val list6 = ArrayList<Double>()
    val range6 = 3.0..9.0
    for (i in range6) {
        list6.add(i)
    }
    if (list6 != listOf<Double>(3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0)) {
        return "Wrong elements for 3.0..9.0: $list6"
    }

    val list7 = ArrayList<Float>()
    val range7 = 3.0.toFloat()..9.0.toFloat()
    for (i in range7) {
        list7.add(i)
    }
    if (list7 != listOf<Float>(3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0)) {
        return "Wrong elements for 3.0.toFloat()..9.0.toFloat(): $list7"
    }

   return "OK"
}
