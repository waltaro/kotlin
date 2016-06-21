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

package org.jetbrains.kotlin.java.model.types

import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiTypeParameter
import org.jetbrains.kotlin.java.model.elements.JeTypeElement
import javax.lang.model.type.TypeKind
import javax.lang.model.type.TypeMirror
import javax.lang.model.type.TypeVariable
import javax.lang.model.type.TypeVisitor

class JeTypeVariableType(override val psiType: PsiClassType, val parameter: PsiTypeParameter) : JeAbstractType(), TypeVariable {
    override fun getKind() = TypeKind.TYPEVAR
    override fun <R : Any?, P : Any?> accept(v: TypeVisitor<R, P>, p: P) = v.visitTypeVariable(this, p)

    override fun getLowerBound(): TypeMirror? {
        TODO()
    }

    override fun getUpperBound(): TypeMirror? {
        TODO()
    }

    override fun asElement() = JeTypeElement(parameter)
}