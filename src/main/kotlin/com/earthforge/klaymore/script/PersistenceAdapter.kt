package com.earthforge.klaymore.script

interface PersistenceAdapter<T> {
  fun getType(): Class<T>

  fun generateKey(instance: T): String

  fun resolve(key: String): T?
}
