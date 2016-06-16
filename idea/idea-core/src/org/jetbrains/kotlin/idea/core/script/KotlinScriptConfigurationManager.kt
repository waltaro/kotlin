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

package org.jetbrains.kotlin.idea.core.script

import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ex.ProjectRootManagerEx
import com.intellij.openapi.util.EmptyRunnable
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveScopeProvider
import com.intellij.psi.impl.compiled.*
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.NonClasspathDirectoriesScope
import com.intellij.psi.util.MethodSignatureUtil
import com.intellij.util.indexing.IndexableSetContributor
import com.intellij.util.io.URLUtil
import org.jetbrains.kotlin.idea.caches.resolve.FileLibraryScope
import org.jetbrains.kotlin.idea.util.application.runReadAction
import org.jetbrains.kotlin.idea.util.application.runWriteAction
import org.jetbrains.kotlin.script.*
import org.jetbrains.kotlin.utils.PathUtil
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class KotlinScriptConfigurationManager(
        private val project: Project,
        private val dumbService: DumbService,
        private val scriptDefinitionProvider: KotlinScriptDefinitionProvider,
        private val scriptExternalImportsProvider: KotlinScriptExternalImportsProvider?
) {

    private val kotlinEnvVars: Map<String, List<String>> by lazy {
        generateKotlinScriptClasspathEnvVarsFromPaths(project, PathUtil.getKotlinPathsForIdeaPlugin())
    }

    init {
        dumbService.runWhenSmart {
            reloadScriptDefinitions()
            // TODO: sort out read/write action business and if possible make it lazy (e.g. move to getAllScriptsClasspath)
            runReadAction { cacheAllScriptsExtraImports() }

            project.messageBus.connect().subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener.Adapter() {
                override fun after(events: List<VFileEvent>) {
                    val isChanged = scriptExternalImportsProvider?.updateExternalImportsCache(events.mapNotNull { it.file })?.any() ?: false
                    if (isChanged) {
                        // TODO: consider more fine-grained update
                        cacheLock.write {
                            allScriptsClasspathCache = null
                            allLibrarySourcesCache = null
                        }
                        notifyRootsChanged()
                    }
                }
            })
        }
    }

    private var allScriptsClasspathCache: List<VirtualFile>? = null
    private var allLibrarySourcesCache: List<VirtualFile>? = null
    private val cacheLock = ReentrantReadWriteLock()

    private fun notifyRootsChanged() {
        runWriteAction { ProjectRootManagerEx.getInstanceEx(project)?.makeRootsChange(EmptyRunnable.getInstance(), false, true) }
    }

    fun getScriptClasspath(file: VirtualFile): List<VirtualFile> =
            scriptExternalImportsProvider
                    ?.getExternalImports(file)
                    ?.flatMap { it.classpath }
                    ?.map { it.classpathEntryToVfs() }
            ?: emptyList()

    fun getAllScriptsClasspath(): List<VirtualFile> = cacheLock.read {
        if (allScriptsClasspathCache == null) {
            dumbService.runWhenSmart {
                cacheLock.write {
                    allScriptsClasspathCache =
                            (scriptExternalImportsProvider?.getKnownCombinedClasspath() ?: emptyList())
                                    .distinct()
                                    .mapNotNull { it.classpathEntryToVfs() }
                }
                notifyRootsChanged()
            }
        }
        return allScriptsClasspathCache ?: emptyList()
    }

    fun getAllLibrarySources(): List<VirtualFile> = cacheLock.read {
        if (allLibrarySourcesCache == null) {
            dumbService.runWhenSmart {
                cacheLock.write {
                    allLibrarySourcesCache =
                            (scriptExternalImportsProvider?.getKnownSourceRoots() ?: emptyList())
                                    .distinct()
                                    .mapNotNull { it.classpathEntryToVfs() }
                }
                notifyRootsChanged()
            }
        }
        return allLibrarySourcesCache ?: emptyList()
    }

    private fun File.classpathEntryToVfs(): VirtualFile =
            if (isDirectory)
                StandardFileSystems.local()?.findFileByPath(this.canonicalPath) ?: throw FileNotFoundException("Classpath entry points to a non-existent location: ${this}")
            else
                StandardFileSystems.jar()?.findFileByPath(this.canonicalPath + URLUtil.JAR_SEPARATOR) ?: throw FileNotFoundException("Classpath entry points to a file that is not a JAR archive: ${this}")

    fun getAllScriptsClasspathScope(): GlobalSearchScope {
        return getAllScriptsClasspath().let { cp ->
            if (cp.isEmpty()) GlobalSearchScope.EMPTY_SCOPE
            else GlobalSearchScope.union(cp.map { FileLibraryScope(project, it) }.toTypedArray())
        }
    }

    fun getAllLibrarySourcesScope(): GlobalSearchScope {
        return getAllLibrarySources().let { cp ->
            if (cp.isEmpty()) GlobalSearchScope.EMPTY_SCOPE
            else GlobalSearchScope.union(cp.map { FileLibraryScope(project, it) }.toTypedArray())
        }
    }

    private fun reloadScriptDefinitions() {
        (makeScriptDefsFromTemplateProviderExtensions(project /* TODO: add logging here */) +
         loadScriptConfigsFromProjectRoot(File(project.basePath ?: "")).map { KotlinConfigurableScriptDefinition(it, kotlinEnvVars) } +
         makeScriptDefsFromConfigs(loadScriptDefConfigsFromProjectRoot(File(project.basePath ?: "")))).let {
            if (it.isNotEmpty()) {
                scriptDefinitionProvider.setScriptDefinitions(it + StandardScriptDefinition)
            }
        }
    }

    private fun cacheAllScriptsExtraImports() {
        scriptExternalImportsProvider?.apply {
            invalidateCaches()
            cacheExternalImports(
                    scriptDefinitionProvider.getAllKnownFileTypes()
                            .flatMap { FileTypeIndex.getFiles(it, GlobalSearchScope.allScope(project)) })
        }
    }

    companion object {
        @JvmStatic
        fun getInstance(project: Project): KotlinScriptConfigurationManager =
                ServiceManager.getService(project, KotlinScriptConfigurationManager::class.java)
    }
}


class KotlinScriptDependenciesIndexableSetContributor : IndexableSetContributor() {

    override fun getAdditionalProjectRootsToIndex(project: Project): Set<VirtualFile> {
        val manager = KotlinScriptConfigurationManager.getInstance(project)
        return (manager.getAllScriptsClasspath() + manager.getAllLibrarySources()).toSet()
    }

    override fun getAdditionalRootsToIndex(): Set<VirtualFile> = emptySet()
}

class ScriptDependencySourceNavigationPolicy : ClsCustomNavigationPolicyEx() {
    override fun getNavigationElement(clsClass: ClsClassImpl): PsiClass? {
        val containingClass = clsClass.containingClass as? ClsClassImpl
        if (containingClass != null) {
            return getNavigationElement(containingClass)?.findInnerClassByName(clsClass.name, false)
        }

        val clsFileImpl = clsClass.containingFile as? ClsFileImpl ?: return null
        return getFileNavigationElement(clsFileImpl)?.classes?.singleOrNull()
    }

    override fun getNavigationElement(clsMethod: ClsMethodImpl): PsiElement? {
        val clsClass = getNavigationElement(clsMethod.containingClass as ClsClassImpl) ?: return null
        return clsClass.findMethodsByName(clsMethod.name, false)
                .firstOrNull { MethodSignatureUtil.areParametersErasureEqual(it, clsMethod) }
    }

    override fun getNavigationElement(clsField: ClsFieldImpl): PsiElement? {
        val srcClass = getNavigationElement(clsField.containingClass as ClsClassImpl) ?: return null
        return srcClass.findFieldByName(clsField.name, false)
    }

    override fun getFileNavigationElement(file: ClsFileImpl): PsiClassOwner? {
        val virtualFile = file.virtualFile
        val project = file.project

        val kotlinScriptConfigurationManager = KotlinScriptConfigurationManager.getInstance(project)
        if (virtualFile !in kotlinScriptConfigurationManager.getAllScriptsClasspathScope()) return null

        val sourceFileName = (file.classes.first() as ClsClassImpl).sourceFileName
        val packageName = file.packageName
        val relativePath = if (packageName.isEmpty()) sourceFileName else packageName.replace('.', '/') + '/' + sourceFileName

        for (root in kotlinScriptConfigurationManager.getAllLibrarySources()) {
            val sourceFile = root.findFileByRelativePath(relativePath)
            if (sourceFile != null && sourceFile.isValid) {
                val sourcePsi = file.manager.findFile(sourceFile)
                if (sourcePsi is PsiClassOwner) {
                    return sourcePsi
                }
            }
        }
        return null
    }
}

// TODO: implement FileResolveScopeProvider instead via KtFile
class KotlinScriptResolveScopeProvider: ResolveScopeProvider() {
    override fun getResolveScope(file: VirtualFile, project: Project): GlobalSearchScope? {
        KotlinScriptDefinitionProvider.getInstance(project).findScriptDefinition(file) ?: return null
        // TODO: actually all dependencies for this particular file/ also should include the file itself
        return KotlinScriptConfigurationManager.getInstance(project).getAllScriptsClasspathScope()
    }
}