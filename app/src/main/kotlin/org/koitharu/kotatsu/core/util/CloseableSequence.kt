package org.haziffe.dropsauce.core.util

interface CloseableSequence<T> : Sequence<T>, AutoCloseable
