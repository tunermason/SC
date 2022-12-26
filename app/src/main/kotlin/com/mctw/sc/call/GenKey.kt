package com.mctw.sc.call

import kotlin.random.Random

class GenKey {
    fun random(): String? {
        val generator = Random()
        val randomStringBuilder = StringBuilder()
        val randomLength: Int = generator.nextInt(256)
        var tempChar: Char
        for (i in 0 until randomLength) {
            tempChar = (generator.nextInt(96) + 32)
            randomStringBuilder.append(tempChar)
        }
        return randomStringBuilder.toString()
    }

}