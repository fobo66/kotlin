/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.plugin.statistics

import com.gradle.scan.plugin.BuildScanExtension
import org.jetbrains.kotlin.gradle.plugin.stat.CompileStatData
import org.jetbrains.kotlin.gradle.plugin.stat.ReportStatistics
import org.jetbrains.kotlin.konan.file.File

class ReportStatisticsToBuildScan(private val buildScan: BuildScanExtension, private val buildUuid: String, private val kotlinVersion: String) : ReportStatistics {
    override fun report(data: CompileStatData) {
        buildScan.value(data.taskName, readableString(data))
        data.tags.forEach { buildScan.tag(it) }
    }

    private fun readableString(data: CompileStatData): String {
        val nonIncrementalReasons = data.nonIncrementalAttributes.filterValues { it > 0 }.keys
        val readableString = StringBuilder()
        if (nonIncrementalReasons.isEmpty()) {
            readableString.append("Incremental build; ")
        } else {
            nonIncrementalReasons.joinTo(readableString, prefix = "Non incremental build because: [", postfix = "]; ") { it.readableString }
        }
        data.changes.joinTo(readableString, prefix = "Changes: [", postfix = "]; ") { it.substringAfterLast(File.separator) }

        readableString.append("Performance: [")
        data.timeData.forEach { (key, value) -> readableString.append(key.readableString).append(": ").append(value).append("ms, ") }
        data.perfData.forEach { (key, value) -> readableString.append(key.readableString).append(": ").append(value).append("kb, ") }
        readableString.append("]; ")
        return readableString.toString()
    }
}