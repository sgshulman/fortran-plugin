package org.jetbrains.fortran.lang.resolve.ref

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.fortran.lang.psi.*
import org.jetbrains.fortran.lang.psi.ext.FortranNamedElement
import org.jetbrains.fortran.lang.psi.mixin.FortranDataPathImplMixin
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.FileBasedIndex
import org.jetbrains.fortran.FortranFileType
import org.jetbrains.fortran.FortranFixedFormFileType
import org.jetbrains.fortran.lang.core.stubs.FortranProgramUnitStub


class FortranPathReferenceImpl(element: FortranDataPathImplMixin) :
        FortranReferenceBase<FortranDataPathImplMixin>(element), FortranReference {

    override val FortranDataPathImplMixin.referenceAnchor: PsiElement get() = referenceNameElement

    override fun getVariants(): Array<Any> = emptyArray()

    override fun resolveInner(): List<FortranNamedElement> {
        // module rename
        val useStmt = PsiTreeUtil.getParentOfType(element, FortranUseStmt::class.java)
        if (useStmt != null && element.parent is FortranRenameStmt) {
            return resolveModuleRename(useStmt)
        }

        val programUnit = PsiTreeUtil.getParentOfType(element, FortranProgramUnit::class.java) ?: return emptyList()
        // types should be done first of all
        if (element is FortranTypeName) {
            return resolveTypes(programUnit)
        }

        // resolve paths like a%b%c
        if (element.firstChild is FortranDataPath)
        {
            return resolveDifficultPath()
        }

        return resolveName(programUnit)
    }

    private fun resolveModuleRename(useStmt: FortranUseStmt) =
            useStmt.dataPath!!.reference.multiResolve().filterNotNull()
            .map { PsiTreeUtil.getParentOfType(it, FortranModule::class.java) }.filterNotNull()
            .flatMap { collectAllNamesFromModule(it) }.toList()


    private fun resolveTypes(programUnit: FortranProgramUnit) : List<FortranNamedElement> {
        val types = programUnit.types.filter { element.referenceName.equals(it.name, true) }
                .toMutableSet()
        val outerProgramUnit : FortranProgramUnit
        // if we are internal program unit
        if (programUnit.parent is FortranModuleSubprogramPart
                || programUnit.parent is FortranInternalSubprogramPart) {
            outerProgramUnit = PsiTreeUtil.getParentOfType(programUnit, FortranProgramUnit::class.java) ?: programUnit
            types.addAll(outerProgramUnit.types.filter { element.referenceName.equals(it.name, true) })
        } else {
            outerProgramUnit = programUnit
        }

        // renamed types
        types.addAll(programUnit.variables.filter{ PsiTreeUtil.getParentOfType(it, FortranRenameStmt::class.java) != null })

        // from modules
        if (element.parent !is FortranUseStmt) { // We do not need recursion here
            val allModules = collectAllModules(programUnit, outerProgramUnit)
            types.addAll(collectAllTypesFromModules(allModules))
        }
        return types.toList()
    }

    fun resolveDifficultPath() : List<FortranNamedElement> {
        val innerPart = (element.firstChild.reference as FortranPathReferenceImpl).multiResolve().filterNotNull()

        val innerPartTypeStmt = innerPart.map { PsiTreeUtil.getParentOfType(it, FortranTypeDeclarationStmt::class.java)
                ?: PsiTreeUtil.getParentOfType(it, FortranDataComponentDefStmt::class.java) }.firstOrNull()

        val innerType = if (innerPartTypeStmt is FortranTypeDeclarationStmt) {
            innerPartTypeStmt.derivedTypeSpec?.typeName
        } else {
            (innerPartTypeStmt as FortranDataComponentDefStmt?)?.derivedTypeSpec?.typeName
        }
        if (innerType == null) {
            return emptyList()
        } else {
            // we should find type declaration. If it is in use -> rename we'll search for the origin name (several times)
            var type = innerType.reference.multiResolve().firstOrNull()
            while (type?.parent is FortranRenameStmt) type = (type.parent as FortranRenameStmt).dataPath?.reference?.multiResolve()?.firstOrNull()

            return PsiTreeUtil.getParentOfType(type, FortranDerivedTypeDef::class.java)?.variables
                    ?.filter { element.referenceName.equals(it.name, true) }?.toMutableList() ?: emptyList()
        }
    }

    fun resolveName(programUnit: FortranProgramUnit) : List<FortranNamedElement> {
        val outerProgramUnit : FortranProgramUnit
        // local variables
        val names = programUnit.variables.filter { element.referenceName.equals(it.name, true) }
                .toMutableSet()
        if (element.referenceName.equals(programUnit.unit?.name, true) ) names.add(programUnit.unit as FortranNamedElement)

        // if we are real program unit
        if (programUnit.parent !is FortranModuleSubprogramPart
                && programUnit.parent !is FortranInternalSubprogramPart) {
            names.addAll(programUnit.subprograms.filter { element.referenceName.equals(it.name, true) })

            outerProgramUnit = programUnit
        } else {
            outerProgramUnit = PsiTreeUtil.getParentOfType(programUnit, FortranProgramUnit::class.java) ?: programUnit
            names.addAll(outerProgramUnit.variables.filter { element.referenceName.equals(it.name, true) })
            names.addAll(outerProgramUnit.subprograms.filter { element.referenceName.equals(it.name, true) })
        }

        // other files
        val vFiles = FileBasedIndex.getInstance()
                .getContainingFiles(FileTypeIndex.NAME, FortranFileType, GlobalSearchScope.projectScope(element.project))
                .plus(FileBasedIndex.getInstance()
                        .getContainingFiles(FileTypeIndex.NAME, FortranFixedFormFileType, GlobalSearchScope.projectScope(element.project)))

        for (file in vFiles) {
            val psiFile = PsiManager.getInstance(element.project).findFile(file)
            val fileStub = fortranFileStub(psiFile)
            if ( fileStub != null) {
                val unitStubs = fileStub.childrenStubs
                        .filter{ it is FortranProgramUnitStub}
                        .filter{ (it as FortranProgramUnitStub).name.equals(element.referenceName, true) }
                names.addAll(unitStubs.map{it.psi as FortranNamedElement})
                names.addAll(unitStubs.map{it.psi}.filterIsInstance(FortranFunctionSubprogram::class.java)
                        .flatMap { f -> f.variables.filter { element.referenceName.equals(it.name, true) } }
                        .filterNotNull())
            } else if (psiFile != null) {
                val unitPsi = psiFile.children.filter { it is FortranProgramUnit }
                        .filter{ element.referenceName.equals((it as FortranProgramUnit).name, true) }

                names.addAll(unitPsi.map { it -> (it as FortranProgramUnit).unit }.filterNotNull() )
                names.addAll(unitPsi.filterIsInstance(FortranFunctionSubprogram::class.java)
                        .flatMap { f -> f.variables.filter { element.referenceName.equals(it.name, true) } }
                        .filterNotNull())
            }
        }

        // modules
        // speed up needed
        if (element.parent !is FortranUseStmt) { // We do not need recursion here
            val allModules = collectAllModules(programUnit, outerProgramUnit)
            for (module in allModules) {
                names.addAll(module.reference.multiResolve().filterNotNull()
                        .map { PsiTreeUtil.getParentOfType(it, FortranModule::class.java) }.filterNotNull()
                        .flatMap { collectAllNamesFromModule(it) }.toList())
            }
        }
        return names.toList()
    }

    fun collectAllModules(programUnit: FortranProgramUnit, outerProgramUnit: FortranProgramUnit)
            : MutableSet<FortranDataPath> {
        val allModules = outerProgramUnit.usedModules.toMutableSet()
        if (programUnit != outerProgramUnit) allModules.addAll(programUnit.usedModules)
        return allModules
    }

    fun collectAllModules(programUnit: FortranProgramUnit?) : MutableSet<FortranDataPath> {
        return if (programUnit != null) collectAllModules(programUnit, programUnit) else emptySet<FortranDataPath>().toMutableSet()
    }

    fun collectAllTypesFromModules(allModules : MutableSet<FortranDataPath>) : MutableSet<FortranNamedElement>  {
        val allTypes : MutableSet<FortranNamedElement> = mutableSetOf()
        for (module in allModules) {
            val onlyIsUsed = !(module.parent as FortranUseStmt).onlyStmtList.isEmpty()
            val onlyList = (module.parent as FortranUseStmt).onlyStmtList
                    .map { it.entityDecl?.name?.toLowerCase() }.filterNotNull()
            val renameList = (module.parent as FortranUseStmt).renameStmtList
                    .plus((module.parent as FortranUseStmt).onlyStmtList.map { it.renameStmt })
                    .map { it?.dataPath?.referenceName?.toLowerCase() }.filterNotNull()

            allTypes.addAll(module.reference.multiResolve().flatMap {
                collectAllTypesFromModules(collectAllModules(PsiTreeUtil.getParentOfType(it, FortranModule::class.java)))
            }.plus(module.reference.multiResolve().flatMap {
                PsiTreeUtil.getParentOfType(it, FortranModule::class.java)?.types?.toList() ?: emptyList<FortranNamedElement>()
            }).plus(module.reference.multiResolve().flatMap {
                (PsiTreeUtil.getParentOfType(it, FortranModule::class.java)?.variables?.toList() ?: emptyList<FortranNamedElement>())
                        .filter{ PsiTreeUtil.getParentOfType(it, FortranRenameStmt::class.java) != null }
            }).filter {
                element.referenceName.equals(it.name, true)
                        && element.referenceName.toLowerCase() !in renameList
                        && (!onlyIsUsed || element.referenceName.toLowerCase() in onlyList)
            })
        }
        return allTypes
    }

    fun collectAllNamesFromModule(module : FortranModule?) : MutableSet<FortranNamedElement> {
        if (module == null) return mutableSetOf()
        val allNames : MutableSet<FortranNamedElement> = mutableSetOf()
        val allModules = collectAllModules(module)
        for (m in allModules) {
            val onlyIsUsed = !(m.parent as FortranUseStmt).onlyStmtList.isEmpty()
            val onlyList = (m.parent as FortranUseStmt).onlyStmtList
                    .map { it.entityDecl?.name?.toLowerCase() }.filterNotNull()
            val renameList = (m.parent as FortranUseStmt).renameStmtList
                    .plus((m.parent as FortranUseStmt).onlyStmtList.map { it.renameStmt })
                    .map { it?.dataPath?.referenceName?.toLowerCase() }.filterNotNull()

            allNames.addAll(m.reference.multiResolve().flatMap {
                collectAllNamesFromModule(PsiTreeUtil.getParentOfType(it, FortranModule::class.java))
            }.filter {
                element.referenceName.equals(it.name, true)
                        && element.referenceName.toLowerCase() !in renameList
                        && (!onlyIsUsed || element.referenceName.toLowerCase() in onlyList)
            })
        }
        allNames.addAll(module.variables.filter { element.referenceName.equals(it.name, true)}
                .plus(module.subprograms.filter { element.referenceName.equals(it.name, true) })
                .plus(module.types.filter { element.referenceName.equals(it.name, true) }))
        return allNames
    }
}

fun fortranFileStub(file : PsiFile?) = if (file is FortranFile) file.stub else (file as? FortranFixedFormFile)?.stub
