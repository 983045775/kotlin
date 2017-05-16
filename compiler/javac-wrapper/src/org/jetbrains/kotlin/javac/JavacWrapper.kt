/*
 * Copyright 2010-2017 JetBrains s.r.o.
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

package org.jetbrains.kotlin.javac

import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.CommonClassNames
import com.intellij.psi.search.EverythingGlobalScope
import com.intellij.psi.search.GlobalSearchScope
import com.sun.source.tree.CompilationUnitTree
import com.sun.source.util.TreePath
import com.sun.tools.javac.api.JavacTrees
import com.sun.tools.javac.code.Flags
import com.sun.tools.javac.code.Symbol
import com.sun.tools.javac.code.Symtab
import com.sun.tools.javac.file.JavacFileManager
import com.sun.tools.javac.jvm.ClassReader
import com.sun.tools.javac.main.JavaCompiler
import com.sun.tools.javac.model.JavacElements
import com.sun.tools.javac.model.JavacTypes
import com.sun.tools.javac.tree.JCTree
import com.sun.tools.javac.util.Context
import com.sun.tools.javac.util.List as JavacList
import com.sun.tools.javac.util.Names
import com.sun.tools.javac.util.Options
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.config.jvmClasspathRoots
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.javac.wrappers.symbols.SymbolBasedClass
import org.jetbrains.kotlin.javac.wrappers.symbols.SymbolBasedClassifierType
import org.jetbrains.kotlin.javac.wrappers.symbols.SymbolBasedPackage
import org.jetbrains.kotlin.load.java.structure.JavaClass
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.isSubpackageOf
import org.jetbrains.kotlin.name.parentOrNull
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.javac.wrappers.trees.TreeBasedClass
import org.jetbrains.kotlin.javac.wrappers.trees.TreeBasedPackage
import org.jetbrains.kotlin.javac.wrappers.trees.TreePathResolverCache
import org.jetbrains.kotlin.javac.wrappers.trees.computeClassId
import org.jetbrains.kotlin.load.java.structure.JavaClassifier
import org.jetbrains.kotlin.load.java.structure.JavaPackage
import org.jetbrains.kotlin.name.ClassId
import java.io.Closeable
import java.io.File
import javax.lang.model.element.Element
import javax.lang.model.type.TypeMirror
import javax.tools.JavaFileManager
import javax.tools.JavaFileObject
import javax.tools.StandardLocation

class JavacWrapper(javaFiles: Collection<File>,
                   kotlinFiles: Collection<KtFile>,
                   arguments: Array<String>?,
                   private val environment: KotlinCoreEnvironment) : Closeable {

    companion object {
        fun getInstance(project: Project): JavacWrapper = ServiceManager.getService(project, JavacWrapper::class.java)
    }

    val JAVA_LANG_OBJECT by lazy {
        findClassInSymbols(CommonClassNames.JAVA_LANG_OBJECT)
                ?.let { SymbolBasedClassifierType(it.element.asType(), this) }
    }
    val JAVA_LANG_ENUM by lazy {
        findClassInSymbols(CommonClassNames.JAVA_LANG_ENUM)
                ?.let { SymbolBasedClassifierType(it.element.asType(), this) }
    }
    val JAVA_LANG_ANNOTATION_ANNOTATION by lazy {
        findClassInSymbols(CommonClassNames.JAVA_LANG_ANNOTATION_ANNOTATION)
                ?.let { SymbolBasedClassifierType(it.element.asType(), this) }
    }

    private val messageCollector = environment.configuration[CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY]
    private val context = Context()

    init {
        messageCollector?.let { JavacLogger.preRegister(context, it) }
        arguments?.toList()?.let { JavacOptionsMapper.map(Options.instance(context), it) }
    }

    private val javac = object : JavaCompiler(context) {
        override fun parseFiles(files: Iterable<JavaFileObject>?) = compilationUnits
    }
    private val fileManager = context[JavaFileManager::class.java] as JavacFileManager

    init {
        // use rt.jar instead of lib/ct.sym
        fileManager.setSymbolFileEnabled(false)
        fileManager.setLocation(StandardLocation.CLASS_PATH, environment.configuration.jvmClasspathRoots)
    }

    private val names = Names.instance(context)
    private val symbols = Symtab.instance(context)
    private val trees = JavacTrees.instance(context)
    private val elements = JavacElements.instance(context)
    private val types = JavacTypes.instance(context)
    private val fileObjects = fileManager.getJavaFileObjectsFromFiles(javaFiles).toJavacList()
    private val compilationUnits: JavacList<JCTree.JCCompilationUnit> = fileObjects.map(javac::parse).toJavacList()

    private val javaClasses = compilationUnits
            .flatMap { unit -> unit.typeDecls
                    .flatMap { TreeBasedClass(it as JCTree.JCClassDecl,
                                              trees.getPath(unit, it),
                                              this,
                                              unit.sourceFile)
                            .withInnerClasses() }
            }
            .associateBy(JavaClass::fqName)

    private val javaClassesAssociatedByClassId = javaClasses.values.associateBy { it.computeClassId() }

    private val javaPackages = compilationUnits
            .mapNotNullTo(hashSetOf()) { unit -> unit.packageName?.toString()?.let { TreeBasedPackage(it, this, unit.sourcefile) } }
            .associateBy(TreeBasedPackage::fqName)

    private val kotlinClassifiersCache = KotlinClassifiersCache(if (javaFiles.isNotEmpty()) kotlinFiles else emptyList(), this)
    private val treePathResolverCache = TreePathResolverCache(this)
    private val symbolBasedClassesCache = hashMapOf<String, SymbolBasedClass?>()
    private val symbolBasedPackagesCache = hashMapOf<String, SymbolBasedPackage?>()

    fun compile(outDir: File? = null): Boolean = with(javac) {
        if (errorCount() > 0) return false

        val javaFilesNumber = fileObjects.length()
        if (javaFilesNumber == 0) return true

        fileManager.setClassPathForCompilation(outDir)
        messageCollector?.report(CompilerMessageSeverity.INFO,
                                 "Compiling $javaFilesNumber Java source files" +
                                 " to [${fileManager.getLocation(StandardLocation.CLASS_OUTPUT)?.firstOrNull()?.path}]")
        compile(fileObjects)
        errorCount() == 0
    }

    override fun close() {
        fileManager.close()
        javac.close()
    }

    fun findClass(fqName: FqName, scope: GlobalSearchScope = EverythingGlobalScope()): JavaClass? {
        javaClasses[fqName]?.let { javaClass ->
            javaClass.virtualFile?.let { if (it in scope) return javaClass }
        }

        findClassInSymbols(fqName.asString())?.let { javaClass ->
            javaClass.virtualFile?.let { if (it in scope) return javaClass }
        }

        return null
    }

    fun findClass(classId: ClassId, scope: GlobalSearchScope = EverythingGlobalScope()): JavaClass? {
        javaClassesAssociatedByClassId[classId]?.let { javaClass ->
            javaClass.virtualFile?.let { if (it in scope) return javaClass }
        }

        findPackageInSymbols(classId.packageFqName.asString())?.let {
            (it.element as Symbol.PackageSymbol).findClass(classId.relativeClassName.asString())?.let { javaClass ->
                javaClass.virtualFile?.let { if (it in scope) return javaClass }
            }

        }

        return null
    }

    fun findPackage(fqName: FqName, scope: GlobalSearchScope): JavaPackage? {
        javaPackages[fqName]?.let { javaPackage ->
            javaPackage.virtualFile?.let { if (it in scope) return javaPackage }
        }

        return findPackageInSymbols(fqName.asString())
    }

    fun findSubPackages(fqName: FqName): List<JavaPackage> = symbols.packages
                                                                     .filterKeys { it.toString().startsWith("$fqName.") }
                                                                     .map { SymbolBasedPackage(it.value, this) } +
                                                             javaPackages
                                                                     .filterKeys { it.isSubpackageOf(fqName) && it != fqName }
                                                                     .map { it.value }

    fun findClassesFromPackage(fqName: FqName): List<JavaClass> = javaClasses
                                                                          .filterKeys { it?.parentOrNull() == fqName }
                                                                          .flatMap { it.value.withInnerClasses() } +
                                                                  elements.getPackageElement(fqName.asString())
                                                                          ?.members()
                                                                          ?.elements
                                                                          ?.filterIsInstance(Symbol.ClassSymbol::class.java)
                                                                          ?.map { SymbolBasedClass(it, this, it.classfile) }
                                                                          .orEmpty()

    fun knownClassNamesInPackage(fqName: FqName): Set<String> = javaClasses.filterKeys { it?.parentOrNull() == fqName }
                                                                        .mapTo(hashSetOf()) { it.value.name.asString() } +
                                                                elements.getPackageElement(fqName.asString())
                                                                        ?.members_field
                                                                        ?.elements
                                                                        ?.filterIsInstance<Symbol.ClassSymbol>()
                                                                        ?.map { it.name.toString() }.orEmpty()

    fun getTreePath(tree: JCTree, compilationUnit: CompilationUnitTree): TreePath = trees.getPath(compilationUnit, tree)

    fun getKotlinClassifier(fqName: FqName): JavaClass? = kotlinClassifiersCache.getKotlinClassifier(fqName)

    fun isDeprecated(element: Element) = elements.isDeprecated(element)

    fun isDeprecated(typeMirror: TypeMirror) = isDeprecated(types.asElement(typeMirror))

    fun resolve(treePath: TreePath): JavaClassifier? = treePathResolverCache.resolve(treePath)

    fun toVirtualFile(javaFileObject: JavaFileObject): VirtualFile? = javaFileObject.toUri().let {
        if (it.scheme == "jar") {
            environment.findJarFile(it.schemeSpecificPart.substring("file:".length))
        } else {
            environment.findLocalFile(it.schemeSpecificPart)
        }
    }

    private inline fun <reified T> Iterable<T>.toJavacList() = JavacList.from(this)

    private fun findClassInSymbols(fqName: String): SymbolBasedClass? {
        if (symbolBasedClassesCache.containsKey(fqName)) return symbolBasedClassesCache[fqName]

        elements.getTypeElement(fqName)?.let { SymbolBasedClass(it, this, it.classfile) }
                .let { symbol ->
                    symbolBasedClassesCache[fqName] = symbol
                    return symbol
                }
    }

    private fun findPackageInSymbols(fqName: String): SymbolBasedPackage? {
        if (symbolBasedPackagesCache.containsKey(fqName)) return symbolBasedPackagesCache[fqName]

        elements.getPackageElement(fqName)?.let { SymbolBasedPackage(it, this) }
                .let { symbol ->
                    symbolBasedPackagesCache[fqName] = symbol
                    return symbol
                }
    }

    private fun JavacFileManager.setClassPathForCompilation(outDir: File?) = apply {
        (outDir ?: environment.configuration[JVMConfigurationKeys.OUTPUT_DIRECTORY])?.let {
            it.mkdirs()
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, listOf(it))
        }

        val reader = ClassReader.instance(context)
        val names = Names.instance(context)
        val outDirName = getLocation(StandardLocation.CLASS_OUTPUT)?.firstOrNull()?.path ?: ""

        list(StandardLocation.CLASS_OUTPUT, "", setOf(JavaFileObject.Kind.CLASS), true)
                .forEach {
                    val fqName = it.name
                            .substringAfter(outDirName)
                            .substringBefore(".class")
                            .replace(File.separator, ".")
                            .let { if (it.startsWith(".")) it.substring(1) else it }
                            .let(names::fromString)

                    symbols.classes[fqName]?.let { symbols.classes[fqName] = null }
                    val symbol = reader.enterClass(fqName, it)

                    (elements.getPackageOf(symbol) as? Symbol.PackageSymbol)?.let {
                        it.members_field.enter(symbol)
                        it.flags_field = it.flags_field or Flags.EXISTS.toLong()
                    }
                }

    }

    private fun TreeBasedClass.withInnerClasses(): List<TreeBasedClass> = listOf(this) + innerClasses.values.flatMap { it.withInnerClasses() }

    private fun Symbol.PackageSymbol.findClass(name: String): SymbolBasedClass? {
        val nameParts = name.replace("$", ".").split(".")
        var symbol = members_field.getElementsByName(names.fromString(nameParts.first()))
                             ?.firstOrNull() as? Symbol.ClassSymbol ?: return null
        if (nameParts.size > 1) {
            symbol.complete()
            for (it in nameParts.drop(1)) {
                symbol = symbol.members_field?.getElementsByName(names.fromString(it))?.firstOrNull() as? Symbol.ClassSymbol ?: return null
                symbol.complete()
            }
        }

        return symbol.let { SymbolBasedClass(it, this@JavacWrapper, it.classfile) }
    }

}