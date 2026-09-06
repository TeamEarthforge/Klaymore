package com.earthforge.klaymore.script

import kotlin.script.experimental.api.CompiledScript

object CompiledScriptCompat {
  @Suppress("UNCHECKED_CAST") @JvmStatic fun cast(obj: Any): CompiledScript = obj as CompiledScript
}
