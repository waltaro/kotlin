@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN", "UNCHECKED_CAST", "NOTHING_TO_INLINE")
@file:JvmName("CollectionsJDK8Kt")
package kotlin.jdk8.collections

import java.util.*
import java.util.function.*
import java.util.stream.Stream

/**
 * Returns the value to which the specified key is mapped, or
 * [defaultValue] if this map contains no mapping for the key.
 */
@Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
public inline fun <@kotlin.internal.OnlyInputTypes K, V> Map<out K, V>.getOrDefault(key: K, defaultValue: V): V
        = getOrDefault(key, defaultValue)


/**
 * Removes the entry for the specified key only if it is currently
 * mapped to the specified value.
 */
@Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
public inline fun <@kotlin.internal.OnlyInputTypes K, @kotlin.internal.OnlyInputTypes V> MutableMap<out K, out V>.remove(key: K, value: V): Boolean
        = (this as MutableMap<K, V>).remove(key, value)


