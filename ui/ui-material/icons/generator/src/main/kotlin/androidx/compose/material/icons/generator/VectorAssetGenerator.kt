/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.material.icons.generator

import androidx.compose.material.icons.generator.vector.Vector
import androidx.compose.material.icons.generator.vector.VectorNode
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.buildCodeBlock

/**
 * Generator for creating a Kotlin source file with a VectorAsset property for the given [vector],
 * with name [iconName] and theme [iconTheme].
 *
 * @param iconName the name for the generated property, which is also used for the generated file.
 * I.e if the name is `Menu`, the property will be `Menu` (inside a theme receiver object) and
 * the file will be `Menu.kt` (under the theme package name).
 * @param iconTheme the theme that this vector belongs to. Used to scope the property to the
 * correct receiver object, and also for the package name of the generated file.
 * @param vector the parsed vector to generate VectorAssetBuilder commands for
 */
class VectorAssetGenerator(
    private val iconName: String,
    private val iconTheme: IconTheme,
    private val vector: Vector
) {
    /**
     * @return a [FileSpec] representing a Kotlin source file containing the property for this
     * programmatic [vector] representation.
     *
     * The package name and hence file location of the generated file is:
     * [PackageNames.MaterialIconsPackage] + [IconTheme.themePackageName].
     */
    fun createFileSpec(): FileSpec {
        val iconsPackage = PackageNames.MaterialIconsPackage.packageName
        val themePackage = iconTheme.themePackageName
        val combinedPackageName = "$iconsPackage.$themePackage"
        val backingProperty = backingProperty()
        return FileSpec.builder(
            packageName = combinedPackageName,
            fileName = iconName
        ).addProperty(
            PropertySpec.builder(name = iconName, type = ClassNames.VectorAsset)
                .receiver(iconTheme.className)
                .getter(iconGetter(backingProperty))
                .build()
        ).addProperty(
            backingProperty
        ).setIndent().build()
    }

    /**
     * @return the body of the getter for the icon property. This getter returns the backing
     * property if it is not null, otherwise creates the icon and 'caches' it in the backing
     * property, and then returns the backing property.
     */
    private fun iconGetter(backingProperty: PropertySpec): FunSpec {
        return FunSpec.getterBuilder()
            .addStatement("if (%N != null) return %N!!", backingProperty, backingProperty)
            .addCode(
                buildCodeBlock {
                    beginControlFlow("%N = %M", backingProperty, MemberNames.MaterialIcon)
                    vector.nodes.forEach { node -> addRecursively(node) }
                    endControlFlow()
                }
            )
            .addStatement("return %N!!", backingProperty)
            .build()
    }

    /**
     * @return The private backing property that is used to cache the VectorAsset for a given
     * icon once created.
     */
    private fun backingProperty(): PropertySpec {
        val nullableVectorAsset = ClassNames.VectorAsset.copy(nullable = true)
        return PropertySpec.builder(name = "icon", type = nullableVectorAsset)
            .mutable()
            .addModifiers(KModifier.PRIVATE)
            .initializer("null")
            .build()
    }
}

/**
 * Recursively adds function calls to construct the given [vectorNode] and its children.
 */
private fun CodeBlock.Builder.addRecursively(vectorNode: VectorNode) {
    when (vectorNode) {
        // TODO: b/147418351 - add clip-paths once they are supported
        is VectorNode.Group -> {
            addFunctionWithLambda(MemberNames.Group) {
                vectorNode.paths.forEach { path ->
                    addRecursively(path)
                }
            }
        }
        is VectorNode.Path -> {
            addFunctionWithLambda(MemberNames.MaterialPath, vectorNode.getParameters()) {
                vectorNode.nodes.forEach { pathNode ->
                    addStatement(pathNode.asFunctionCall())
                }
            }
        }
    }
}

/**
 * @return a [String] representing the parameters to add to the path function call to create the
 * correct path.
 */
private fun VectorNode.Path.getParameters(): String {
    val parameterList = listOfNotNull(
        "fillAlpha = ${fillAlpha}f".takeIf { fillAlpha != 1f },
        "strokeAlpha = ${strokeAlpha}f".takeIf { strokeAlpha != 1f }
    )

    return if (parameterList.isNotEmpty()) {
        parameterList.joinToString(prefix = "(", postfix = ")")
    } else {
        ""
    }
}

/**
 * Generates a correctly indented lambda invocation for the given [function] and [lambdaBody].
 *
 * @param function [MemberName] of the function call
 * @param parameters string representing the parameters of this function call, e.g. "(1, 3)".
 * @param lambdaBody body for the trailing lambda of this function call
 *
 */
private fun CodeBlock.Builder.addFunctionWithLambda(
    function: MemberName,
    parameters: String = "",
    lambdaBody: CodeBlock.Builder.() -> Unit
) {
    beginControlFlow("%M$parameters", function)
    lambdaBody()
    endControlFlow()
}
