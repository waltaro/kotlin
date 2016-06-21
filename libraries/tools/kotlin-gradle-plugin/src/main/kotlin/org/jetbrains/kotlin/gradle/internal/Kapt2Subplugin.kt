/*
 * Copyright 2010-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.gradle.internal

import com.android.build.gradle.BaseExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.UnknownDomainObjectException
import org.gradle.api.tasks.compile.AbstractCompile
import org.jetbrains.kotlin.gradle.plugin.KotlinGradleSubplugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import java.io.File

// Use apply plugin: 'kapt2' to enable Android Extensions in an Android project.
// Just a marker plugin.
class Kapt2SubpluginIndicator : Plugin<Project> {
    override fun apply(target: Project?) {}
}

class Kapt2Subplugin : KotlinGradleSubplugin {
    override fun isApplicable(project: Project, task: AbstractCompile): Boolean {
        try {
            project.extensions.getByName("android") as? BaseExtension ?: return false
        } catch (e: UnknownDomainObjectException) {
            return false
        }
        
        return project.plugins.findPlugin(Kapt2SubpluginIndicator::class.java) != null
    }

    override fun getExtraArguments(project: Project, task: AbstractCompile): List<SubpluginOption> {
        val apClasspath = project.configurations.getByName("kapt2").resolve()
        if (apClasspath.isEmpty()) return emptyList()

        val pluginOptions = arrayListOf<SubpluginOption>()
        
        pluginOptions += SubpluginOption("generated", File(project.buildDir, "generated/source/kapt2").absolutePath)
        
        for (classpathEntry in apClasspath) {
            pluginOptions += SubpluginOption("apclasspath", classpathEntry.absolutePath)
        }

        return pluginOptions
    }

    override val isBundled = true
    override fun getPluginName() = "org.jetbrains.kotlin.kapt2"
    override fun getGroupName() = "org.jetbrains.kotlin"
    override fun getArtifactName() = "kotlin-kapt"
}