package dev.ghostflyby.livetemplateswithselection
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolder
import kotlin.reflect.KProperty

internal operator fun <T : Any> Key<T>.getValue(thisRef: UserDataHolder, property: KProperty<*>): T? =
    thisRef.getUserData(this)

internal operator fun <T : Any> Key<T>.setValue(thisRef: UserDataHolder, property: KProperty<*>, value: T?) =
    thisRef.putUserData(this, value)
